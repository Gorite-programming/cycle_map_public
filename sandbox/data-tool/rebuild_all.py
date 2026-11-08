#!/usr/bin/env python3
"""検索DB (*.search.db) の一括再生成スクリプト。

既存の `python -m tools.cli` を県ごとに次の順で呼び出す。
    [extract] -> import-osm -> import-kokudo(medical, school)
              -> import-overture -> merge -> export(+validate)

各 import は master.db 内の自ソース分を置換するため、何度実行しても結果は同じ。
実行前に master.db と既存の out/<県>.search.db を自動バックアップする。

使い方 (リポジトリ直下の venv を使う。duckdb / osmium / pykakasi が必要):
    venv/bin/python sandbox/data-tool/rebuild_all.py --dry-run
    venv/bin/python sandbox/data-tool/rebuild_all.py
    venv/bin/python sandbox/data-tool/rebuild_all.py --pref hiroshima,yamaguchi
    venv/bin/python sandbox/data-tool/rebuild_all.py --overture-release latest
    venv/bin/python sandbox/data-tool/rebuild_all.py \\
        --extract-from data/japan-latest.osm.pbf   # OSMを最新PBFから切り出し直す
"""

import argparse
import datetime
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.request

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, BASE_DIR)

from tools.core import config  # noqa: E402
from tools.core.sources import overture as overture_src  # noqa: E402

STEPS = ("osm", "kokudo", "overture", "merge")
OVERTURE_S3_TEMPLATE = (
    "s3://overturemaps-us-west-2/release/%s/theme=places/type=place/*")
OVERTURE_STAC_URL = "https://stac.overturemaps.org/catalog.json"


class StepFailed(Exception):
    pass


class Logger:
    """標準出力とログファイルの両方に書く。"""

    def __init__(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self._f = open(path, "w", encoding="utf-8")
        self.path = path

    def write(self, text):
        sys.stdout.write(text)
        sys.stdout.flush()
        self._f.write(text)
        self._f.flush()

    def line(self, text=""):
        self.write(text + "\n")

    def close(self):
        self._f.close()


class Progress:
    """全体の段階数と経過時間を管理し、一定間隔で進捗行を出す。"""

    def __init__(self, log, total, interval):
        self.log = log
        self.total = total
        self.interval = interval
        self.done = 0
        self.started = time.time()

    @staticmethod
    def fmt(seconds):
        seconds = int(seconds)
        return "%d:%02d:%02d" % (seconds // 3600, seconds % 3600 // 60, seconds % 60)

    def eta(self):
        if self.done == 0:
            return "算出中"
        remain = (time.time() - self.started) / self.done * (self.total - self.done)
        return "約 " + self.fmt(remain)

    def heartbeat(self, label, step_start, stop):
        while not stop.wait(self.interval):
            self.log.line("  [進捗] %d/%d 実行中: %s | 段階 %s / 全体 %s | 残り %s"
                          % (self.done + 1, self.total, label,
                             self.fmt(time.time() - step_start),
                             self.fmt(time.time() - self.started), self.eta()))


def run(log, cmd, dry_run, progress=None, label=""):
    log.line("$ " + " ".join(cmd))
    if dry_run:
        return
    start = time.time()
    stop = threading.Event()
    beat = None
    if progress and progress.interval > 0:
        beat = threading.Thread(
            target=progress.heartbeat, args=(label, start, stop), daemon=True)
        beat.start()
    try:
        proc = subprocess.Popen(
            cmd, cwd=BASE_DIR, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1)
        for out_line in proc.stdout:
            log.write("    " + out_line)
        rc = proc.wait()
    finally:
        stop.set()
        if beat:
            beat.join()
    log.line("    -> exit %d (%.1fs)" % (rc, time.time() - start))
    if progress:
        progress.done += 1
        log.line("  [進捗] %d/%d 完了 (%s) | 経過 %s | 残り %s"
                 % (progress.done, progress.total, label,
                    progress.fmt(time.time() - progress.started), progress.eta()))
    if rc != 0:
        raise StepFailed("%s (exit %d)" % (" ".join(cmd[:4]), rc))


def cli(*args):
    return [sys.executable, "-m", "tools.cli"] + list(args)


def resolve_overture_release(value):
    """--overture-release の値を S3 パスにする。None なら既定 (コード内固定値)。"""
    if value is None:
        return overture_src.OVERTURE_S3_PATH
    if value == "latest":
        with urllib.request.urlopen(OVERTURE_STAC_URL, timeout=30) as resp:
            catalog = json.load(resp)
        value = catalog.get("latest")
        if not value:
            raise RuntimeError(
                "STAC カタログから latest を取得できません。"
                "--overture-release に版 (例 2026-10-22.0) を直接指定してください")
    return OVERTURE_S3_TEMPLATE % value


def pbf_timestamp(path):
    """PBF のデータ時刻 (取得できなければ空文字)。"""
    try:
        out = subprocess.run(
            ["osmium", "fileinfo", "-g",
             "header.option.osmosis_replication_timestamp", path],
            capture_output=True, text=True, timeout=60)
        return out.stdout.strip()
    except Exception:
        return ""


def extract_pbf(log, japan_pbf, pref_id, dry_run, progress=None):
    """日本全体PBFから県境ポリゴンで切り出し、data/raw/<Name>.osm.pbf を置換する。"""
    pref = config.PREFECTURES[pref_id]
    boundary = os.path.join(config.BOUNDARIES_DIR, pref["boundary"])
    dest = config.default_pbf_path(pref_id)
    tmp = dest + ".tmp"
    run(log, ["osmium", "extract", "--polygon", boundary,
              "--strategy", "complete_ways", "--overwrite",
              "--output-format", "pbf", japan_pbf, "--output", tmp],
        dry_run, progress, "%s: OSM切り出し" % pref_id)
    if not dry_run:
        os.replace(tmp, dest)
        log.line("    extract: %s (データ時刻 %s)" % (dest, pbf_timestamp(dest)))


def backup(log, pref_ids, stamp, dry_run):
    log.line("== バックアップ ==")
    targets = [(config.MASTER_DB_PATH,
                os.path.join(config.BACKUP_DIR, "master_%s.db" % stamp))]
    for pref_id in pref_ids:
        src = config.search_db_path(pref_id)
        targets.append((src, os.path.join(
            config.OUT_DIR, "backup", stamp, os.path.basename(src))))
    for src, dst in targets:
        if not os.path.exists(src):
            log.line("  (なし) %s" % src)
            continue
        log.line("  %s -> %s" % (src, dst))
        if not dry_run:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)


def steps_per_pref(args):
    """1県あたりの実行段階数 (進捗の分母)。"""
    steps = set(STEPS) - set(args.skip)
    return (int(bool(args.extract_from)) + int("osm" in steps)
            + 2 * int("kokudo" in steps) + int("overture" in steps)
            + int("merge" in steps) + 1)


def build_pref(log, pref_id, args, s3_path, progress):
    steps = set(STEPS) - set(args.skip)
    log.line("")
    log.line("===== %s (%s) =====" % (pref_id, config.PREFECTURES[pref_id]["name_ja"]))

    def do(label, cmd):
        run(log, cmd, args.dry_run, progress, "%s: %s" % (pref_id, label))

    if args.extract_from:
        extract_pbf(log, args.extract_from, pref_id, args.dry_run, progress)
    if "osm" in steps:
        do("OSM取り込み", cli("import-osm", "--pref", pref_id))
    if "kokudo" in steps:
        for dataset in ("medical", "school"):
            do("国土数値情報(%s)" % dataset,
               cli("import-kokudo", "--dataset", dataset, "--pref", pref_id))
    if "overture" in steps:
        do("Overture取得", cli("import-overture", "--pref", pref_id,
                               "--min-confidence", str(args.min_confidence),
                               "--s3-path", s3_path))
    if "merge" in steps:
        do("重複統合", cli("merge", "--pref", pref_id))
    export_args = ["export", "--pref", pref_id]
    if args.exclude_named:
        export_args.append("--exclude-named")
    if args.pykakasi:
        export_args.append("--pykakasi")
    do("DB生成・検証", cli(*export_args))


def describe_db(path):
    """生成DBの要約 (件数・ソース内訳・住所/読みの充足率)。"""
    if not os.path.exists(path):
        return None
    conn = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
    try:
        total, addr, kana, named = conn.execute(
            "SELECT count(*), sum(address IS NOT NULL AND address != ''),"
            " sum(kana IS NOT NULL AND kana != ''),"
            " sum(category = 'named') FROM places").fetchone()
        by_source = dict(conn.execute(
            "SELECT source, count(*) FROM places GROUP BY source").fetchall())
        created = conn.execute(
            "SELECT value FROM meta WHERE key='created_at'").fetchone()
    finally:
        conn.close()
    total = total or 0
    pct = (lambda n: "%.1f%%" % (100.0 * (n or 0) / total)) if total else (lambda n: "-")
    return {
        "total": total, "by_source": by_source, "address": pct(addr),
        "kana": pct(kana), "named": pct(named),
        "size_mb": os.path.getsize(path) / 1024 / 1024,
        "created_at": created[0] if created else "-",
    }


def summarize(log, pref_ids, results, stamp):
    lines = ["| 県 | 結果 | 件数 | 住所あり | 読みあり | named | サイズ | 生成日時 | 内訳 |",
             "|---|---|---:|---:|---:|---:|---:|---|---|"]
    for pref_id in pref_ids:
        info = describe_db(config.search_db_path(pref_id))
        status = results.get(pref_id, "未実行")
        if info is None:
            lines.append("| %s | %s | - | - | - | - | - | - | - |" % (pref_id, status))
            continue
        src = " / ".join("%s %d" % kv for kv in sorted(info["by_source"].items()))
        lines.append("| %s | %s | %d | %s | %s | %s | %.1fMB | %s | %s |" % (
            pref_id, status, info["total"], info["address"], info["kana"],
            info["named"], info["size_mb"], info["created_at"], src))
    text = "\n".join(lines)
    log.line("")
    log.line("===== サマリ =====")
    log.line(text)
    path = os.path.join(config.OUT_DIR, "rebuild_%s_summary.md" % stamp)
    with open(path, "w", encoding="utf-8") as f:
        f.write("# 検索DB再生成サマリ (%s)\n\n%s\n" % (stamp, text))
    log.line("summary: %s" % path)


def parse_args():
    p = argparse.ArgumentParser(
        description="検索DBを5県分まとめて再生成する",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    p.add_argument("--pref", default="all",
                   help="カンマ区切りの県ID (既定: all = %s)"
                   % ",".join(config.PREFECTURES))
    p.add_argument("--extract-from", metavar="JAPAN_PBF",
                   help="指定した日本全体PBFから県境で切り出し、data/raw の県別PBFを置き換える")
    p.add_argument("--overture-release", metavar="REL",
                   help="Overture の版 (例 2026-10-22.0)。'latest' で最新を問い合わせる。"
                   "省略時は overture.py の固定値")
    p.add_argument("--min-confidence", type=float, default=0.6,
                   help="Overture の最小 confidence (既定 0.6)")
    p.add_argument("--skip", default="", help="省略する段階 (カンマ区切り): %s" % ",".join(STEPS))
    p.add_argument("--exclude-named", action="store_true", help="named カテゴリを出力から除外")
    p.add_argument("--pykakasi", action="store_true", help="pykakasi で読みを補完 (誤変換あり)")
    p.add_argument("--progress-interval", type=int, default=30, metavar="SEC",
                   help="実行中の進捗行を出す間隔 (秒、既定 30。0 で無効)")
    p.add_argument("--keep-going", action="store_true", help="県が失敗しても次の県に進む")
    p.add_argument("--no-backup", action="store_true", help="バックアップを取らない")
    p.add_argument("--dry-run", action="store_true", help="実行内容を表示するだけで何もしない")
    args = p.parse_args()

    args.prefs = (list(config.PREFECTURES) if args.pref == "all"
                  else [x.strip().lower() for x in args.pref.split(",") if x.strip()])
    unknown = [x for x in args.prefs if x not in config.PREFECTURES]
    args.skip = [x.strip() for x in args.skip.split(",") if x.strip()]
    bad_skip = [x for x in args.skip if x not in STEPS]
    if unknown or bad_skip:
        p.error("不明な値: 県=%s 段階=%s" % (unknown or "-", bad_skip or "-"))
    if args.extract_from and not os.path.isfile(args.extract_from):
        p.error("PBF が見つかりません: %s" % args.extract_from)
    if args.extract_from and shutil.which("osmium") is None:
        p.error("osmium CLI が PATH にありません (brew install osmium-tool)")
    return args


def main():
    args = parse_args()
    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    log = Logger(os.path.join(config.OUT_DIR, "rebuild_%s.log" % stamp))
    log.line("rebuild_all: %s%s" % (stamp, " (dry-run)" if args.dry_run else ""))
    log.line("県: %s / 省略: %s" % (",".join(args.prefs), ",".join(args.skip) or "なし"))
    try:
        s3_path = resolve_overture_release(args.overture_release)
    except Exception as e:
        log.line("Overture の版を決められません: %s" % e)
        return 2
    log.line("Overture: %s" % s3_path)
    if args.extract_from:
        log.line("OSM 切り出し元: %s (データ時刻 %s)"
                 % (args.extract_from, pbf_timestamp(args.extract_from)))

    if not args.no_backup:
        backup(log, args.prefs, stamp, args.dry_run)

    progress = Progress(log, steps_per_pref(args) * len(args.prefs),
                        args.progress_interval)
    results = {}
    for pref_id in args.prefs:
        try:
            build_pref(log, pref_id, args, s3_path, progress)
            results[pref_id] = "OK"
        except StepFailed as e:
            results[pref_id] = "FAIL"
            log.line("!! %s 失敗: %s" % (pref_id, e))
            if not args.keep_going:
                break

    if not args.dry_run:
        log.line("全体所要時間: %s" % Progress.fmt(time.time() - progress.started))
        summarize(log, args.prefs, results, stamp)
    log.line("log: %s" % log.path)
    log.close()
    return 0 if all(v == "OK" for v in results.values()) and results else 1


if __name__ == "__main__":
    sys.exit(main())
