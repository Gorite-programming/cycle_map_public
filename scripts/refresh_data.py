#!/usr/bin/env python3
"""データ更新の一括パイプライン: 最新 OSM を取得し、検索DB・グラフ・タイルを同じ OSM 時点で作り直す。

流れ:
  1. 準備   : 必要なツール・ディスク空きの確認、現行の成果物を `packages/_prev/<日時>/` に退避
  2. OSM    : Geofabrik の中国地方 PBF (chugoku-latest) を `data/` に取得 (または --source-pbf)
  3. 検索DB : sandbox/data-tool/rebuild_all.py (OSM 切り出し → 取り込み → Overture → 統合 → 書き出し)
  4. graph  : build_prefectures.py をステージング (work/refresh/<日時>/packages) に出力
  4b. CH    : build_ch.py (graph ごとの Contraction Hierarchies、<県>.graph.ch。ZIP の外の別成果物)
  5. タイル : build_tiles.py (Protomaps 最新ビルド) をステージングに出力
  6. 検証   : 県ごとに 検索DBの整合・件数の増減・graph のサイズ・ZIP の構成・タイルの検証、
             経路の回帰チェック (新旧 graph で同じ OD を引き、到達率と距離の中央値を比べる)
  7. 昇格   : 検証を通した県だけを packages/ に置換。通らなかった県は現行の成果物のまま (検索DBも退避から戻す)
  7b. 通り口: build_portals.py (県またぎの通り口 <A>__<B>.portals)。昇格後の packages/ の graph の組で作る
             (組の両方の県が確定してからでないと識別が合わないため、CH の直後ではなく昇格の後に置く)
  8. 記録   : packages/data_manifest.json (出典と時点) と、ステージングの report.md

実機への転送はしない (scripts/push_data.sh を別途実行する)。

使い方 (リポジトリ直下から。venv の python で実行すること):
    venv/bin/python scripts/refresh_data.py --dry-run
    venv/bin/python scripts/refresh_data.py                         # 5県すべて、最新の Geofabrik PBF
    venv/bin/python scripts/refresh_data.py --only Tottori,Shimane
    venv/bin/python scripts/refresh_data.py --source-pbf data/chugoku-261107.osm.pbf --skip download
    venv/bin/python scripts/refresh_data.py --overture-release latest
"""

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PACKAGES = ROOT / "packages"
DATA_DIR = ROOT / "data"
DATA_TOOL = ROOT / "sandbox" / "data-tool"
DATA_TOOL_OUT = DATA_TOOL / "out"
MASTER_DB = DATA_TOOL / "data" / "work" / "master.db"
IMPORTER = ROOT / "application/osm-importer/build/install/osm-importer/bin/osm-importer"
GEOFABRIK_URL = "https://download.geofabrik.de/asia/japan/chugoku-latest.osm.pbf"
PREFECTURES = ["Tottori", "Shimane", "Okayama", "Hiroshima", "Yamaguchi"]
ZIP_MEMBERS = {"%s.search.db", "%s.graph", "%s.graph.idx", "manifest.json", "LICENSE.txt"}

# 昇格の許容範囲 (前回比)。これを外れるものは異常として昇格しない
SEARCH_ROWS_RATIO = (0.80, 1.30)
GRAPH_BYTES_RATIO = (0.70, 1.40)
MIN_FREE_GB = 12
# 経路の回帰チェック (新旧 graph で同じ固定シードの OD を引いて比べる)。これを外れたら昇格しない
REGRESSION_OD = 60
REGRESSION_MAX_REACH_DROP = 3.0       # 到達率の低下 (ポイント)
REGRESSION_MAX_DISTANCE_CHANGE = 5.0  # 経路距離の中央値の変化 (%)
GRADLEW = ROOT / "application" / "gradlew"


class Step:
    """各段階の所要時間と結果を記録する。"""

    def __init__(self):
        self.rows = []

    def run(self, name, fn):
        start = time.time()
        print("\n######## %s (%s)" % (name, datetime.now().strftime("%H:%M:%S")), flush=True)
        try:
            result = fn()
            self.rows.append((name, "OK", time.time() - start, ""))
            return result
        except Exception as e:  # 段階が失敗しても、他の段階・県は続ける
            self.rows.append((name, "FAIL", time.time() - start, "%s: %s" % (type(e).__name__, e)))
            print("!! %s 失敗: %s" % (name, e), file=sys.stderr, flush=True)
            return None


def run_cmd(cmd, log_path, cwd=ROOT, env=None):
    """コマンドを実行して標準出力をログに残す。失敗時は例外。"""
    print("$ " + " ".join(str(c) for c in cmd), flush=True)
    with open(log_path, "a", encoding="utf-8") as log:
        log.write("$ %s\n" % " ".join(str(c) for c in cmd))
        proc = subprocess.Popen([str(c) for c in cmd], cwd=cwd, env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, bufsize=1)
        for line in proc.stdout:
            log.write(line)
            log.flush()
        rc = proc.wait()
    if rc != 0:
        raise RuntimeError("exit %d: %s (ログ: %s)" % (rc, " ".join(str(c) for c in cmd[:4]), log_path))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def free_gb(path):
    st = shutil.disk_usage(path)
    return st.free / 1024 ** 3


def osm_timestamp(pbf):
    out = subprocess.run(["osmium", "fileinfo", "-g", "header.option.osmosis_replication_timestamp", str(pbf)],
                         capture_output=True, text=True)
    return out.stdout.strip() or None


# ------------------------------- 準備 -------------------------------

def preflight(names):
    problems = []
    if shutil.which("osmium") is None:
        problems.append("osmium が PATH にありません")
    if shutil.which("java") is None:
        problems.append("java が PATH にありません")
    if not IMPORTER.is_file():
        problems.append("osm-importer が未ビルドです (cd application && ./gradlew :osm-importer:installDist)")
    if free_gb(ROOT) < MIN_FREE_GB:
        problems.append("ディスクの空きが %.1f GB しかありません (必要 %d GB)" % (free_gb(ROOT), MIN_FREE_GB))
    if problems:
        raise RuntimeError("; ".join(problems))
    print("事前確認 OK (空き %.1f GB, 対象 %s)" % (free_gb(ROOT), ",".join(names)))


def snapshot(stamp, names):
    """現行の成果物を packages/_prev/<日時>/ に退避する (昇格に失敗しても元に戻せるように)。"""
    prev = PACKAGES / "_prev" / stamp
    (prev / "out").mkdir(parents=True, exist_ok=True)
    for name in names:
        for src in (PACKAGES / ("%s.zip" % name), PACKAGES / ("%s.pmtiles" % name.lower()),
                    DATA_TOOL_OUT / ("%s.search.db" % name.lower())):
            if src.is_file():
                dest = prev / ("out" if src.name.endswith(".search.db") else "") / src.name
                shutil.copy2(src, dest)
    if MASTER_DB.is_file():
        shutil.copy2(MASTER_DB, prev / "master.db")
    print("退避: %s" % prev)
    return prev


# ------------------------------- 各段階 -------------------------------

def fetch_osm(url, force):
    """Geofabrik の PBF を data/ に取得する。リダイレクト先のファイル名 (chugoku-YYMMDD) で保存する。"""
    DATA_DIR.mkdir(exist_ok=True)
    req = urllib.request.Request(url, headers={"User-Agent": "cycle_map-refresh-data/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        final_name = Path(resp.geturl()).name
        dest = DATA_DIR / final_name
        if dest.is_file() and not force:
            print("取得済み: %s" % dest)
            return dest
        total = int(resp.headers.get("Content-Length") or 0)
        tmp = dest.with_suffix(".tmp")
        got, last = 0, time.time()
        with open(tmp, "wb") as f:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
                got += len(chunk)
                if time.time() - last > 15:
                    print("  取得 %.0f MB%s" % (got / 1048576, " / %.0f MB" % (total / 1048576) if total else ""), flush=True)
                    last = time.time()
    if total and got != total:
        tmp.unlink(missing_ok=True)
        raise RuntimeError("ダウンロードが途中で切れました (%d / %d バイト)" % (got, total))
    tmp.replace(dest)
    if not osm_timestamp(dest):
        raise RuntimeError("PBF のヘッダを読めません: %s" % dest)
    print("取得完了: %s (%.0f MB, データ時点 %s)" % (dest, dest.stat().st_size / 1048576, osm_timestamp(dest)))
    return dest


def rebuild_search_dbs(pbf, names, overture_release, log):
    cmd = [sys.executable, DATA_TOOL / "rebuild_all.py", "--extract-from", pbf,
           "--pref", ",".join(n.lower() for n in names), "--keep-going", "--progress-interval", "60"]
    if overture_release:
        cmd += ["--overture-release", overture_release]
    run_cmd(cmd, log)


def build_graphs(pbf, names, stage, log):
    env = dict(os.environ, JAVA_OPTS=os.environ.get("JAVA_OPTS", "-Xmx5g"))
    run_cmd([sys.executable, ROOT / "build_prefectures.py", "--source-pbf", pbf,
             "--output-dir", stage / "packages", "--work-dir", stage / "work", "--force",
             "--only", ",".join(names)], log, env=env)


def build_ch(names, stage, log):
    """CH (<県>.graph.ch) を、ステージングの ZIP から作る。ZIP の外の別成果物。"""
    run_cmd([sys.executable, ROOT / "scripts" / "build_ch.py", "--only", ",".join(names), "--force",
             "--source-dir", stage / "packages", "--out", stage / "packages"], log)


def build_portals(log):
    """県またぎの通り口 (<A>__<B>.portals) を、昇格後の packages/ の ZIP から作る。古い組だけ作り直される。"""
    run_cmd([sys.executable, ROOT / "scripts" / "build_portals.py"], log)


def build_tiles(names, stage, log):
    run_cmd([sys.executable, ROOT / "scripts" / "build_tiles.py", "--only", ",".join(names),
             "--force", "--out", stage / "packages"], log)


# ------------------------------- 検証 -------------------------------

def search_rows(db_path):
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    try:
        return con.execute("SELECT COUNT(*) FROM places").fetchone()[0]
    finally:
        con.close()


def detail_coverage(db_path):
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    try:
        cols = {r[1] for r in con.execute("PRAGMA table_info(places)")}
        total = con.execute("SELECT COUNT(*) FROM places").fetchone()[0]
        out = {"total": total}
        for col in ("opening_hours", "phone", "website", "wheelchair"):
            out[col] = con.execute("SELECT COUNT(*) FROM places WHERE %s IS NOT NULL AND %s != ''" % (col, col)).fetchone()[0] \
                if col in cols else None
        return out
    finally:
        con.close()


def check_pref(name, stage, prev, check_tiles=True):
    """県の検証。戻り値は (合格か, 所見のリスト, 数値の dict)。"""
    lower = name.lower()
    notes, facts, ok = [], {}, True

    def fail(msg):
        nonlocal ok
        ok = False
        notes.append("NG: " + msg)

    new_db = DATA_TOOL_OUT / ("%s.search.db" % lower)
    zip_path = stage / "packages" / ("%s.zip" % name)
    tiles = stage / "packages" / ("%s.pmtiles" % lower)

    # 検索DB
    if not new_db.is_file():
        fail("検索DBがありません: %s" % new_db)
    else:
        sys.path.insert(0, str(DATA_TOOL))
        from tools.core.validate import validate_search_db  # noqa: E402
        result = validate_search_db(str(new_db))
        bad = [r["name"] for r in result["results"] if not r["ok"]]
        if bad:
            fail("検索DBの検証に失敗: %s" % ", ".join(bad))
        rows = search_rows(new_db)
        facts["search_rows"] = rows
        facts["detail"] = detail_coverage(new_db)
        old_db = prev / "out" / new_db.name
        if old_db.is_file():
            old_rows = search_rows(old_db)
            facts["search_rows_prev"] = old_rows
            lo, hi = SEARCH_ROWS_RATIO
            if not (lo * old_rows <= rows <= hi * old_rows):
                fail("検索DBの件数が前回比で異常: %d → %d" % (old_rows, rows))
    # graph の ZIP
    if not zip_path.is_file():
        fail("ZIP がありません: %s" % zip_path)
    else:
        try:
            with zipfile.ZipFile(zip_path) as z:
                names_in = set(z.namelist())
                want = {m % name if "%s" in m else m for m in ZIP_MEMBERS}
                if names_in != want:
                    fail("ZIP の構成が不正: %s" % sorted(names_in ^ want))
                graph_bytes = z.getinfo("%s.graph" % name).file_size
                facts["graph_bytes"] = graph_bytes
                if z.testzip() is not None:
                    fail("ZIP が壊れています")
            old_zip = prev / ("%s.zip" % name)
            if old_zip.is_file():
                with zipfile.ZipFile(old_zip) as z:
                    old_bytes = z.getinfo("%s.graph" % name).file_size
                facts["graph_bytes_prev"] = old_bytes
                lo, hi = GRAPH_BYTES_RATIO
                if not (lo * old_bytes <= graph_bytes <= hi * old_bytes):
                    fail("graph のサイズが前回比で異常: %d → %d" % (old_bytes, graph_bytes))
        except zipfile.BadZipFile:
            fail("ZIP を開けません")
    # タイル (--skip tiles のときは検査しない。現行のタイルをそのまま使う)
    if not check_tiles:
        pass
    elif not tiles.is_file():
        fail("タイルがありません: %s" % tiles)
    else:
        cli = shutil.which("pmtiles") or str(ROOT / "sandbox/vector_test/pipeline/pmtiles")
        if subprocess.run([cli, "verify", str(tiles)], capture_output=True).returncode != 0:
            fail("タイルの検証に失敗")
    if ok:
        notes.append("合格")
    return ok, notes, facts


def bench_baseline(graph_path, log):
    """routing-core のベンチで、基準 (おすすめ) の到達率 (%) と経路距離の中央値 (m) を返す。"""
    out = subprocess.run([str(GRADLEW), "-q", "--offline", ":routing-core:benchmarkRealGraph",
                          "-Pgraph=%s" % graph_path, "-Pod=%d" % REGRESSION_OD],
                         cwd=ROOT / "application", capture_output=True, text=True)
    with open(log, "a", encoding="utf-8") as f:
        f.write(out.stdout + out.stderr)
    if out.returncode != 0:
        raise RuntimeError("ベンチが失敗しました (ログ: %s)" % log)
    return parse_bench_baseline(out.stdout)


def parse_bench_baseline(text):
    """ベンチの表から、基準の行の (到達率 %, 経路距離の中央値 m) を取り出す。"""
    for line in text.splitlines():
        if line.startswith("| 基準"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            return float(cells[1].rstrip("%")), float(cells[6].rstrip("m"))
    raise ValueError("ベンチの出力に基準の行がありません")


def regression_check(name, stage, prev, log):
    """新旧 graph の経路を比べる。戻り値は (合格か, 所見, 数値)。旧版が無ければ合格扱い。"""
    old_zip = prev / ("%s.zip" % name)
    new_zip = stage / "packages" / ("%s.zip" % name)
    if not old_zip.is_file() or not new_zip.is_file():
        return True, "回帰チェック: 比較対象なし", {}
    work = stage / "regression" / name
    results = {}
    for label, z in (("old", old_zip), ("new", new_zip)):
        d = work / label
        d.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(z) as zf:
            for member in ("%s.graph" % name, "%s.graph.idx" % name):
                zf.extract(member, d)
        results[label] = bench_baseline(d / ("%s.graph" % name), log)
    shutil.rmtree(work, ignore_errors=True)
    (old_reach, old_dist), (new_reach, new_dist) = results["old"], results["new"]
    change = (new_dist / old_dist - 1.0) * 100.0 if old_dist > 0 else 0.0
    facts = {"reach_old": old_reach, "reach_new": new_reach, "median_distance_change_pct": round(change, 2)}
    summary = "回帰チェック: 到達率 %.1f%% → %.1f%%、距離中央値 %+.1f%%" % (old_reach, new_reach, change)
    ok = (old_reach - new_reach) <= REGRESSION_MAX_REACH_DROP and abs(change) <= REGRESSION_MAX_DISTANCE_CHANGE
    return ok, ("" if ok else "NG: ") + summary, facts


# ------------------------------- 昇格・記録 -------------------------------

def promote(name, stage, with_tiles=True):
    lower = name.lower()
    PACKAGES.mkdir(exist_ok=True)
    pairs = [(stage / "packages" / ("%s.zip" % name), PACKAGES / ("%s.zip" % name))]
    ch = stage / "packages" / ("%s.graph.ch" % name)
    if ch.is_file():  # graph を作り直したのに古い CH が残ると、識別が合わず使われない。新しいものに置き換える
        pairs.append((ch, PACKAGES / ("%s.graph.ch" % name)))
    if with_tiles:
        pairs.append((stage / "packages" / ("%s.pmtiles" % lower), PACKAGES / ("%s.pmtiles" % lower)))
    for src, dest in pairs:
        tmp = dest.with_suffix(dest.suffix + ".new")
        shutil.copy2(src, tmp)
        os.replace(tmp, dest)


def restore_search_db(name, prev):
    old = prev / "out" / ("%s.search.db" % name.lower())
    if old.is_file():
        shutil.copy2(old, DATA_TOOL_OUT / old.name)


def write_manifest(sources, results):
    """packages/data_manifest.json を更新する。今回扱わなかった県 (--only で外した県) の記録は残す。"""
    manifest_path = PACKAGES / "data_manifest.json"
    previous = {}
    if manifest_path.is_file():
        try:
            previous = json.loads(manifest_path.read_text(encoding="utf-8")).get("prefectures", {})
        except (ValueError, OSError):
            previous = {}
    manifest = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "sources": sources,
        "prefectures": {k: v for k, v in previous.items() if k not in results},
    }
    for name, info in results.items():
        if not info["promoted"] and name in previous:
            # 昇格しなかった県は、packages/ の中身が前回のままなので前回の記録を残す
            manifest["prefectures"][name] = dict(previous[name], last_attempt_failed=True)
            continue
        entry = {"promoted": info["promoted"], "facts": info["facts"]}
        for path_key, path in (("zip_sha256", PACKAGES / ("%s.zip" % name)),
                               ("tiles_sha256", PACKAGES / ("%s.pmtiles" % name.lower()))):
            if info["promoted"] and path.is_file():
                entry[path_key] = sha256(path)
        manifest["prefectures"][name] = entry
    manifest["prefectures"] = {k: manifest["prefectures"][k] for k in PREFECTURES if k in manifest["prefectures"]}
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_report(stage, stamp, steps, results, sources):
    lines = ["# データ更新レポート %s" % stamp, "", "## 出典と時点", ""]
    lines += ["- %s: %s" % (k, v) for k, v in sources.items()]
    lines += ["", "## 段階", "", "| 段階 | 結果 | 所要 | 備考 |", "|---|---|---:|---|"]
    lines += ["| %s | %s | %.0f秒 | %s |" % (n, r, s, note) for n, r, s, note in steps.rows]
    lines += ["", "## 県ごとの検証", "", "| 県 | 昇格 | 検索DB件数(前回) | 営業時間 | 電話 | Web | graph(MB) | 所見 |", "|---|---|---|---:|---:|---:|---:|---|"]
    for name, info in results.items():
        f = info["facts"]
        d = f.get("detail") or {}
        lines.append("| %s | %s | %s (%s) | %s | %s | %s | %s | %s |" % (
            name, "昇格" if info["promoted"] else "見送り", f.get("search_rows", "-"), f.get("search_rows_prev", "-"),
            d.get("opening_hours", "-"), d.get("phone", "-"), d.get("website", "-"),
            "%.0f" % (f["graph_bytes"] / 1048576) if "graph_bytes" in f else "-", " / ".join(info["notes"])))
    (stage / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return stage / "report.md"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="カンマ区切りの県名 (例: Tottori,Shimane)")
    ap.add_argument("--source-pbf", type=Path, help="使う OSM PBF (省略時は Geofabrik の chugoku-latest を取得)")
    ap.add_argument("--skip", default="", help="省く段階 (カンマ区切り): download,search,graph,ch,portals,tiles,regression")
    ap.add_argument("--overture-release", help="Overture の版 (例 2026-09-23.1)。'latest' で最新")
    ap.add_argument("--dry-run", action="store_true", help="計画だけ表示する")
    args = ap.parse_args()

    names = PREFECTURES
    if args.only:
        wanted = {x.strip().lower() for x in args.only.split(",") if x.strip()}
        names = [p for p in PREFECTURES if p.lower() in wanted]
        if not names:
            ap.error("対象の県がありません: %s" % args.only)
    skip = {x.strip() for x in args.skip.split(",") if x.strip()}
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    stage = ROOT / "work" / "refresh" / stamp
    log = stage / "refresh.log"

    print("対象: %s / 省略: %s / ステージング: %s" % (",".join(names), ",".join(sorted(skip)) or "なし", stage))
    if args.dry_run:
        print("計画: 事前確認 → 退避 → OSM取得 → 検索DB → graph → CH → タイル → 検証 → 昇格 → 通り口 → 記録 (何も実行しません)")
        return 0

    stage.mkdir(parents=True, exist_ok=True)
    steps = Step()
    steps.run("事前確認", lambda: preflight(names))
    if steps.rows[-1][1] != "OK":
        write_report(stage, stamp, steps, {}, {})
        return 2
    prev = steps.run("退避", lambda: snapshot(stamp, names))
    if prev is None:  # 退避できないまま進めると、失敗時に元へ戻せない
        write_report(stage, stamp, steps, {}, {})
        return 2

    pbf = args.source_pbf
    if pbf is None and "download" not in skip:
        pbf = steps.run("OSM取得", lambda: fetch_osm(GEOFABRIK_URL, False))
    if pbf is None:
        pbf = next(iter(sorted(DATA_DIR.glob("chugoku-*.osm.pbf"), reverse=True)), None) or ROOT / "japan-latest.osm.pbf"
    pbf = Path(pbf).resolve()  # 相対パスだと、作業ディレクトリの違う data-tool から見つからない
    if not pbf.is_file():
        print("OSM の PBF がありません: %s" % pbf, file=sys.stderr)
        return 2
    sources = {"OSM": "%s (データ時点 %s)" % (pbf.name, osm_timestamp(pbf) or "不明"),
               "Overture": args.overture_release or "overture.py の固定版",
               "Protomaps": "build_tiles.py が選んだ最新ビルド", "標高": "GSI dem_png z14 (build_terrain.py のキャッシュ。今回は再生成しない)"}

    if "search" not in skip:
        steps.run("検索DB", lambda: rebuild_search_dbs(pbf, names, args.overture_release, log))
    if "graph" not in skip:
        steps.run("graph", lambda: build_graphs(pbf, names, stage, log))
    if "graph" not in skip and "ch" not in skip:
        steps.run("CH", lambda: build_ch(names, stage, log))
    if "tiles" not in skip:
        steps.run("タイル", lambda: build_tiles(names, stage, log))

    results = {}
    for name in names:
        ok, notes, facts = check_pref(name, stage, prev, check_tiles="tiles" not in skip)
        if ok and "graph" not in skip and "regression" not in skip:
            try:
                r_ok, r_note, r_facts = regression_check(name, stage, prev, log)
            except Exception as e:  # ベンチ自体が動かないときは、昇格を止めて人に見てもらう
                r_ok, r_note, r_facts = False, "NG: 回帰チェックを実行できません (%s)" % e, {}
            notes.insert(0, r_note)
            facts["regression"] = r_facts
            if not r_ok:
                ok = False
                notes = [n for n in notes if n != "合格"]
        results[name] = {"promoted": False, "notes": notes, "facts": facts, "ok": ok}
        print("[%s] %s: %s" % (name, "合格" if ok else "不合格", " / ".join(notes)), flush=True)
    for name, info in results.items():
        if info["ok"]:
            promote(name, stage, with_tiles="tiles" not in skip)
            info["promoted"] = True
        else:
            restore_search_db(name, prev)  # 検索DBも現行のものに戻す
    if "graph" not in skip and "portals" not in skip and any(i["promoted"] for i in results.values()):
        # 失敗しても昇格は取り消さない (古い .portals は識別が合わず、アプリは従来の照合に戻る)
        steps.run("通り口", lambda: build_portals(log))
    write_manifest(sources, results)
    report = write_report(stage, stamp, steps, results, sources)
    print("\nレポート: %s" % report)
    print("昇格: %s / 見送り: %s" % (
        ",".join(n for n, i in results.items() if i["promoted"]) or "なし",
        ",".join(n for n, i in results.items() if not i["promoted"]) or "なし"))
    return 0 if all(i["promoted"] for i in results.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
