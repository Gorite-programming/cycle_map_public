#!/usr/bin/env python3
"""配布用 ZIP (packages/<県>.zip) の検索DBだけを、作り直したものに入れ替える。

グラフ (<県>.graph / .graph.idx) は作り直さず、元の ZIP の中身をそのまま移す。
検索DBは sandbox/data-tool/out/<県 (小文字)>.search.db を build_prefectures.py と同じ検査に
通してから入れ、manifest.json も作り直す。ZIP の中の 5 ファイルという決まりは変えない。
ZIP の中の検索DBがすでに同じ内容なら何もしない (何度実行してもよい)。

使い方 (リポジトリ直下から):
    venv/bin/python scripts/repack_search_db.py --dry-run
    venv/bin/python scripts/repack_search_db.py --backup-dir work/packages_backup [--only Hiroshima,Okayama]
"""
import argparse
import hashlib
import os
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import build_prefectures as bp  # noqa: E402

CHUNK = 1 << 20


def _sha256_stream(f) -> str:
    h = hashlib.sha256()
    for block in iter(lambda: f.read(CHUNK), b""):
        h.update(block)
    return h.hexdigest()


def repack(zip_path: Path, new_db: Path, backup_dir=None, dry_run=False) -> str:
    """1 つの ZIP の検索DBを new_db に入れ替える。"same" / "would-replace" / "replaced" を返す。

    元の ZIP が 5 ファイルの決まりを満たさない、または new_db が検査に通らないときは例外。
    """
    name = zip_path.stem
    db_name = f"{name}.search.db"
    if not bp.valid_zip(zip_path, name):
        raise RuntimeError(f"元の ZIP が正しくない: {zip_path}")
    stats = bp.verify_search_db(new_db)
    with new_db.open("rb") as f:
        new_sha = _sha256_stream(f)

    with zipfile.ZipFile(zip_path, "r") as src:
        with src.open(db_name) as f:
            if _sha256_stream(f) == new_sha:
                return "same"
        if dry_run:
            return "would-replace"
        kept = {n: src.getinfo(n).CRC for n in (f"{name}.graph", f"{name}.graph.idx")}
        partial = zip_path.with_suffix(zip_path.suffix + ".partial")
        partial.unlink(missing_ok=True)
        with zipfile.ZipFile(partial, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as dst:
            dst.write(new_db, db_name)
            for n in kept:
                info = src.getinfo(n)
                out_info = zipfile.ZipInfo(n, date_time=info.date_time)
                out_info.compress_type = zipfile.ZIP_DEFLATED
                out_info.external_attr = info.external_attr
                with src.open(n) as fin, dst.open(out_info, "w", force_zip64=True) as fout:
                    shutil.copyfileobj(fin, fout, CHUNK)
            dst.writestr("manifest.json", bp.generate_manifest(name, stats))
            dst.writestr("LICENSE.txt", bp.PACKAGE_LICENSE_TEXT)

    # 入れ替える前に、新しい ZIP が正しく、グラフが元と同じ中身であることを確かめる。
    try:
        if not bp.valid_zip(partial, name):
            raise RuntimeError(f"作り直した ZIP の検査に失敗: {partial}")
        with zipfile.ZipFile(partial, "r") as z:
            for n, crc in kept.items():
                if z.getinfo(n).CRC != crc:
                    raise RuntimeError(f"グラフの中身が変わった: {n}")
            with z.open(db_name) as f:
                if _sha256_stream(f) != new_sha:
                    raise RuntimeError(f"検索DBの中身が合わない: {db_name}")
    except Exception:
        partial.unlink(missing_ok=True)
        raise

    if backup_dir is not None:
        backup_dir.mkdir(parents=True, exist_ok=True)
        backup = backup_dir / zip_path.name
        if not backup.exists():  # 最初の (入れ替え前の) ZIP を残す
            shutil.copy2(zip_path, backup)
    os.replace(partial, zip_path)
    return "replaced"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--packages", type=Path, default=ROOT / "packages")
    ap.add_argument("--db-dir", type=Path, default=ROOT / "sandbox" / "data-tool" / "out")
    ap.add_argument("--backup-dir", type=Path, help="入れ替える前の ZIP をここに残す")
    ap.add_argument("--only", help="対象の県 (ZIP の名前、カンマ区切り)")
    ap.add_argument("--dry-run", action="store_true", help="入れ替えずに、何をするかだけ表示する")
    args = ap.parse_args(argv)

    zips = sorted(args.packages.glob("*.zip"))
    if args.only:
        wanted = {s.strip() for s in args.only.split(",") if s.strip()}
        unknown = wanted - {z.stem for z in zips}
        if unknown:
            print("ZIP が無い: %s" % ", ".join(sorted(unknown)), file=sys.stderr)
            return 2
        zips = [z for z in zips if z.stem in wanted]

    counts = {}
    failed = False
    for z in zips:
        new_db = args.db_dir / f"{z.stem.lower()}.search.db"
        try:
            if not new_db.is_file():
                raise FileNotFoundError(f"作り直した検索DBが無い: {new_db}")
            result = repack(z, new_db, backup_dir=args.backup_dir, dry_run=args.dry_run)
        except Exception as exc:  # 1 県の失敗で残りを止めない
            result = "failed"
            failed = True
            print("%-12s FAILED: %s" % (z.stem, exc), file=sys.stderr, flush=True)
        else:
            print("%-12s %s" % (z.stem, result), flush=True)
        counts[result] = counts.get(result, 0) + 1
    print("合計: " + ", ".join("%s %d" % kv for kv in sorted(counts.items())))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
