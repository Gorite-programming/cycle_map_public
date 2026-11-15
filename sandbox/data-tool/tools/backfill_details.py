"""生成済みの検索DB (*.search.db) に、OSM の詳細情報 (営業時間・電話・Web・車椅子) を書き足す。

経緯: 2026-11-08 (2288f24) に master.db の poi 表へ hours/phone/website/wheelchair 列を
ALTER TABLE で足したが、OSM の取り込みをやり直さなかったため、その後に書き出した検索DBでは
4 列が全件空になっていた。本来は rebuild_all.py で取り込みからやり直せばよいが、
master.db には重複統合の判断や手動の編集 (overlay) が入っており、別の環境 (Mac) にしかない。
そこで、検索DBの OSM 由来の行 (source='osm'、osm_type/osm_id が OSM の番号) に限って、
全国 PBF を 1 回読んで詳細だけを上書きする。整形は取り込みと同じ contact.py の関数を使う。

使い方 (リポジトリ直下から):
    venv/Scripts/python -m tools.backfill_details --pbf ../../data/japan-latest.osm.pbf out/*.search.db
  (sandbox/data-tool で実行する。--dry-run で件数だけ数える)
"""
import argparse
import datetime
import glob
import os
import sqlite3
import sys

from .core.contact import (normalize_hours, normalize_phone, normalize_website,
                           normalize_wheelchair)

DETAIL_TAGS = ("opening_hours", "phone", "contact:phone", "contact:mobile",
               "website", "contact:website", "url", "wheelchair")
DETAIL_COLUMNS = ("opening_hours", "phone", "website", "wheelchair")


def details_from_tags(tags):
    """OSM のタグ (dict) から (hours, phone, website, wheelchair) を作る。
    取り込み (sources/osm.py) と同じ優先順位・整形。全部 None なら None。"""
    row = (
        normalize_hours(tags.get("opening_hours")),
        normalize_phone(tags.get("phone") or tags.get("contact:phone") or tags.get("contact:mobile")),
        normalize_website(tags.get("website") or tags.get("contact:website") or tags.get("url")),
        normalize_wheelchair(tags.get("wheelchair")),
    )
    return row if any(v is not None for v in row) else None


def collect_details(pbf_path, progress=None):
    """PBF を読み、詳細タグを持つ node / way の {(osm_type, osm_id): details} を返す。"""
    import osmium  # 読み込みのときだけ必要 (テストでは使わない)

    found = {}

    class Handler(osmium.SimpleHandler):
        def __init__(self):
            super().__init__()
            self.seen = 0

        def _take(self, osm_type, obj):
            self.seen += 1
            if progress and self.seen % 5_000_000 == 0:
                progress(self.seen, len(found))
            tags = obj.tags
            if not any(k in tags for k in DETAIL_TAGS):
                return
            d = details_from_tags({k: tags.get(k) for k in DETAIL_TAGS if k in tags})
            if d is not None:
                found[(osm_type, obj.id)] = d

        def node(self, n):
            self._take("node", n)

        def way(self, w):
            self._take("way", w)

    Handler().apply_file(pbf_path)
    return found


def backfill_db(db_path, details, dry_run=False, add_columns=False):
    """1 つの検索DBに書き足す。{rows, osm_rows, matched, per_column, added_columns} を返す。
    列が無い古い DB (schema_version=1) は、add_columns=True なら 4 列を足して schema_version を 2 にする
    (v1 と v2 の違いはこの 4 列だけ。アプリは PRAGMA table_info で列の有無を見る)。
    add_columns=False なら書き換えずに matched=None を返す。"""
    conn = sqlite3.connect(db_path)
    try:
        cols = {r[1] for r in conn.execute("PRAGMA table_info(places)")}
        rows = conn.execute("SELECT COUNT(*) FROM places").fetchone()[0]
        missing = [c for c in DETAIL_COLUMNS if c not in cols]
        added = False
        if missing:
            if not add_columns:
                return {"rows": rows, "osm_rows": None, "matched": None, "per_column": None,
                        "added_columns": False}
            if not dry_run:
                with conn:
                    for c in missing:
                        conn.execute("ALTER TABLE places ADD COLUMN %s TEXT" % c)
                    has_meta = conn.execute(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='meta'").fetchone()
                    if has_meta:
                        conn.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('schema_version', '2')")
            added = True
        osm_rows = conn.execute(
            "SELECT osm_type, osm_id FROM places WHERE source = 'osm'").fetchall()
        updates = []
        for osm_type, osm_id in osm_rows:
            d = details.get((osm_type, osm_id))
            if d is not None:
                updates.append(d + (osm_type, osm_id))
        per_column = [sum(1 for u in updates if u[i] is not None) for i in range(4)]
        if not dry_run and updates:
            with conn:
                conn.executemany(
                    "UPDATE places SET opening_hours = ?, phone = ?, website = ?, wheelchair = ?"
                    " WHERE source = 'osm' AND osm_type = ? AND osm_id = ?",
                    updates,
                )
                has_meta = conn.execute(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='meta'").fetchone()
                if has_meta:
                    conn.execute(
                        "INSERT OR REPLACE INTO meta(key, value) VALUES ('details_backfilled_at', ?)",
                        (datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),),
                    )
        return {"rows": rows, "osm_rows": len(osm_rows), "matched": len(updates),
                "per_column": dict(zip(DETAIL_COLUMNS, per_column)), "added_columns": added}
    finally:
        conn.close()


def save_cache(path, details):
    """{(osm_type, osm_id): details} を JSON に保存する。"""
    import json
    with open(path, "w", encoding="utf-8") as f:
        json.dump([[t, i, list(d)] for (t, i), d in details.items()], f, ensure_ascii=False)


def load_cache(path):
    import json
    with open(path, encoding="utf-8") as f:
        return {(t, i): tuple(d) for t, i, d in json.load(f)}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pbf", required=True, help="全国 (または対象県を含む) の OSM PBF")
    ap.add_argument("--dry-run", action="store_true", help="書き換えずに件数だけ数える")
    ap.add_argument("--add-columns", action="store_true",
                    help="詳細の列が無い古い DB (schema_version=1) に列を足して書き足す")
    ap.add_argument("--cache", help="PBF から集めた詳細を保存・再利用するファイル (JSON)。あれば PBF を読まない")
    ap.add_argument("dbs", nargs="+", help="対象の *.search.db (glob 可)")
    args = ap.parse_args(argv)

    paths = sorted({p for pat in args.dbs for p in (glob.glob(pat) or [pat])})
    missing = [p for p in paths if not os.path.isfile(p)]
    if missing:
        print("見つからない: %s" % ", ".join(missing), file=sys.stderr)
        return 2

    if args.cache and os.path.isfile(args.cache):
        print("保存済みの詳細を読み込み中: %s" % args.cache, flush=True)
        details = load_cache(args.cache)
    else:
        print("PBF を読み込み中: %s" % args.pbf, flush=True)
        details = collect_details(
            args.pbf, progress=lambda seen, n: print("  %d 件を読んだ (詳細あり %d)" % (seen, n), flush=True))
        if args.cache:
            save_cache(args.cache, details)
    print("詳細タグを持つ OSM の node/way: %d 件" % len(details), flush=True)

    total = 0
    for p in paths:
        r = backfill_db(p, details, dry_run=args.dry_run, add_columns=args.add_columns)
        name = os.path.basename(p)
        if r["matched"] is None:
            print("%-24s SKIP (詳細の列が無い古い DB)" % name)
            continue
        total += r["matched"]
        pc = r["per_column"]
        if r["added_columns"]:
            name += " (+列)"
        print("%-24s %7d 行 (OSM %6d) に書き足し %6d: 営業時間 %d / 電話 %d / Web %d / 車椅子 %d" % (
            name, r["rows"], r["osm_rows"], r["matched"],
            pc["opening_hours"], pc["phone"], pc["website"], pc["wheelchair"]))
    print("%s: 合計 %d 行" % ("(dry-run) 書き足す予定" if args.dry_run else "書き足した", total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
