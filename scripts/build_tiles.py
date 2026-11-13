#!/usr/bin/env python3
"""県別ベクタータイル (PMTiles) の生成。

Protomaps の公開ビルド (build.protomaps.com/YYYYMMDD.pmtiles, 世界全体) から、
`boundaries/<県>.geojson` の外接矩形 (+余白) を `pmtiles extract` で切り出し、
`packages/<県小文字>.pmtiles` に置く。アプリの `TileSourceSelector` が探すファイル名と同じ。

- スキーマは既存スタイル (app/src/main/assets/styles/protomaps_*.json) と同じ Protomaps Basemap。
- 県境ではなく矩形で切るため、隣県が少し入る (県をまたぐ走行でも地図が途切れない)。
- 実行時のみネット接続が必要 (範囲リクエストで必要分だけ取得する)。

使い方 (リポジトリ直下から):
    python3 scripts/build_tiles.py                       # 5県すべて (有効なファイルがあればスキップ)
    python3 scripts/build_tiles.py --only Yamaguchi,Tottori --force
    python3 scripts/build_tiles.py --build 20261107      # ビルド日を固定
    python3 scripts/build_tiles.py --dry-run
"""

import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import date, timedelta
from pathlib import Path

# Support direct CLI execution and importlib-based callers alike.
if str(Path(__file__).resolve().parent.parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from scripts.prefectures import BY_NAME, resolve
from scripts._platform import find_tool

ROOT = Path(__file__).resolve().parent.parent
BOUNDARY_DIR = ROOT / "boundaries"
OUTPUT_DIR = ROOT / "packages"
PREFECTURES = list(BY_NAME)
BUILD_URL = "https://build.protomaps.com/%s.pmtiles"
MARGIN_DEG = 0.02          # 県境付近の地図が切れないよう外接矩形に足す余白 (約2km)
LOOKBACK_DAYS = 10         # 当日分が未公開のことがあるため、遡って最新を探す
MIN_VALID_BYTES = 1_000_000


def find_pmtiles_cli():
    found = find_tool("pmtiles")
    if found:
        return found
    sys.exit("pmtiles CLI not found on PATH or in known local locations")


def latest_build():
    """build.protomaps.com で実在する最新のビルド日 (YYYYMMDD) を返す。"""
    for back in range(LOOKBACK_DAYS):
        stamp = (date.today() - timedelta(days=back)).strftime("%Y%m%d")
        # 既定の Python-urllib の User-Agent は CDN に 403 で拒否されるため明示する
        req = urllib.request.Request(BUILD_URL % stamp, method="HEAD",
                                     headers={"User-Agent": "cycle_map-build-tiles/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                if resp.status == 200:
                    return stamp
        except urllib.error.HTTPError as e:
            if e.code != 404:
                raise
    sys.exit("Protomaps の公開ビルドが見つかりません (直近 %d 日)" % LOOKBACK_DAYS)


def boundary_bbox(name):
    """境界ポリゴンの外接矩形 (minLon, minLat, maxLon, maxLat) + 余白。"""
    path = BOUNDARY_DIR / ("%s.geojson" % name)
    if not path.is_file():
        sys.exit("境界ポリゴンがありません: %s (先に build_prefectures.py で取得してください)" % path)
    data = json.loads(path.read_text(encoding="utf-8"))
    xs, ys = [], []

    def walk(coords):
        if isinstance(coords[0], (int, float)):
            xs.append(coords[0])
            ys.append(coords[1])
        else:
            for c in coords:
                walk(c)

    for feat in data.get("features", [data]):
        walk((feat.get("geometry") or feat)["coordinates"])
    return (min(xs) - MARGIN_DEG, min(ys) - MARGIN_DEG,
            max(xs) + MARGIN_DEG, max(ys) + MARGIN_DEG)


def is_valid(cli, path):
    if not path.is_file() or path.stat().st_size < MIN_VALID_BYTES:
        return False
    return subprocess.run([cli, "verify", str(path)], capture_output=True).returncode == 0


def build_one(cli, name, build, force, dry_run, output_dir=None):
    output_dir = output_dir or OUTPUT_DIR
    out = output_dir / ("%s.pmtiles" % name.lower())
    if not dry_run and not force and is_valid(cli, out):
        print("[%s] SKIP: 有効なファイルがあります: %s" % (name, out))
        return True
    bbox = boundary_bbox(name)
    bbox_arg = "--bbox=%.4f,%.4f,%.4f,%.4f" % bbox
    tmp = out.with_suffix(".pmtiles.tmp")
    cmd = [cli, "extract", BUILD_URL % build, str(tmp), bbox_arg]
    print("[%s] $ %s" % (name, " ".join(cmd)))
    if dry_run:
        return True
    output_dir.mkdir(parents=True, exist_ok=True)
    tmp.unlink(missing_ok=True)  # pmtiles extract は既存の出力先を上書きしない
    rc = subprocess.run(cmd).returncode
    if rc != 0 or not is_valid(cli, tmp):
        tmp.unlink(missing_ok=True)
        print("[%s] FAIL: 抽出または検証に失敗 (exit %d)" % (name, rc), file=sys.stderr)
        return False
    tmp.replace(out)
    print("[%s] OK: %s (%.1f MB, ビルド %s)" % (name, out, out.stat().st_size / 1048576, build))
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="+", help="Prefectures, regions, or all (comma/space separated; default: Chugoku five)")
    ap.add_argument("--build", help="Protomaps のビルド日 YYYYMMDD (省略時は最新)")
    ap.add_argument("--force", action="store_true", help="有効なファイルがあっても作り直す")
    ap.add_argument("--dry-run", action="store_true", help="実行内容を表示するだけ")
    ap.add_argument("--out", type=Path, help="出力先 (既定: packages/)。ステージング用")
    args = ap.parse_args()

    try:
        names = resolve(args.only)
    except ValueError as exc:
        ap.error(str(exc))
    cli = (find_tool("pmtiles") or "pmtiles") if args.dry_run else find_pmtiles_cli()
    build = args.build or ("YYYYMMDD" if args.dry_run else latest_build())
    print("Protomaps ビルド: %s / 対象: %s" % (build, ",".join(names)))
    results = [build_one(cli, n, build, args.force, args.dry_run, args.out) for n in names]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
