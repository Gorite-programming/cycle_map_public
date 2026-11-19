#!/usr/bin/env python3
"""県別の等高線 (ベクター) と陰影起伏 (ラスター) の PMTiles を、国土地理院の標高タイルから生成する。

入力: 国土地理院「標高タイル (dem_png)」z14 (約10mメッシュ)。`work/terrain/dem14/` にキャッシュする (再実行で再取得しない)。
出力 (packages/ 配下。アプリは県のタイル `<県>.pmtiles` と同じ階層で同名の派生ファイルを探す):
    <県小文字>.contours.pmtiles   等高線 MVT  z12-14  レイヤー "contour" (ele=標高m, kind=minor|index)
    <県小文字>.hillshade.pmtiles  陰影起伏 PNG z9-13  (透明地に影=黒/光=白の半透明。昼夜どちらの地図にも重ねられる)

等高線の間隔: z14 は 10m (50mごとに index)、z13 は 20m (100m)、z12 は 50m (200m)。
出典表示: 「国土地理院」(アプリの帰属表示に含まれる)。基盤地図情報数値標高モデルを元にしている。

依存: venv/bin/pip install numpy pillow contourpy shapely mapbox-vector-tile pmtiles

使い方 (リポジトリ直下から):
    venv/bin/python scripts/build_terrain.py --only Tottori
    venv/bin/python scripts/build_terrain.py                  # 5県すべて
    venv/bin/python scripts/build_terrain.py --only Tottori --max-tiles 60 --out work/terrain/sample   # 動作確認 (一部のみ)
    venv/bin/python scripts/build_terrain.py --dry-run        # 対象タイル数だけ表示
"""

import argparse
import concurrent.futures
import functools
import gzip
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# Support direct CLI execution and importlib-based callers alike.
if str(Path(__file__).resolve().parent.parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from scripts.prefectures import BY_NAME, resolve


def _load_generation_dependencies():
    global np, contour_generator, Image, Compression, TileType, zxy_to_tileid
    global pmtiles_write, LineString, MultiLineString, box, shape, unary_union, mvt
    import numpy as np
    from contourpy import contour_generator
    from PIL import Image
    from pmtiles.tile import Compression, TileType, zxy_to_tileid
    from pmtiles.writer import write as pmtiles_write
    from shapely.geometry import LineString, MultiLineString, box, shape
    from shapely.ops import unary_union
    import mapbox_vector_tile as mvt


ROOT = Path(__file__).resolve().parent.parent
BOUNDARY_DIR = ROOT / "boundaries"
PACKAGES = ROOT / "packages"
CACHE = ROOT / "work" / "terrain" / "dem14"
PREFECTURES = list(BY_NAME)

DEM_URL = "https://cyberjapandata.gsi.go.jp/xyz/dem_png/14/{x}/{y}.png"
Z_DEM = 14
MARGIN_DEG = 0.02
USER_AGENT = "cycle_map-build-terrain/1.0 (personal offline map)"

# 等高線: z -> (間隔m, index線の間隔m)
CONTOUR_ZOOMS = {14: (10, 50), 13: (20, 100), 12: (50, 200)}
HILLSHADE_ZOOMS = range(9, 14)  # 9..13
EXTENT = 4096


# ------------------------------- タイル座標 -------------------------------

def lonlat_to_tile(lon, lat, z):
    n = 2 ** z
    x = (lon + 180.0) / 360.0 * n
    y = (1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n
    return int(math.floor(x)), int(math.floor(y))


def tile_bounds(x, y, z):
    """タイルの (minLon, minLat, maxLon, maxLat)。"""
    n = 2 ** z
    def lon(xx): return xx / n * 360.0 - 180.0
    def lat(yy): return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * yy / n))))
    return lon(x), lat(y + 1), lon(x + 1), lat(y)


def prefecture_shape(name):
    from shapely.geometry import shape
    from shapely.ops import unary_union
    from shapely.validation import make_valid
    path = BOUNDARY_DIR / ("%s.geojson" % name)
    if not path.is_file():
        sys.exit("境界ポリゴンがありません: %s (先に build_prefectures.py で取得してください)" % path)
    data = json.loads(path.read_text(encoding="utf-8"))
    feats = data.get("features", [data])
    # 境界データに自己交差などの不正なポリゴンが含まれることがあるため、補正してから結合する
    geoms = [make_valid(shape(f.get("geometry", f))) for f in feats]
    return unary_union(geoms).buffer(MARGIN_DEG)


def dem_tiles_for(name, max_tiles=None):
    """県のポリゴン (+余白) と交わる z14 タイルの (x, y) 一覧。"""
    poly = prefecture_shape(name)
    minx, miny, maxx, maxy = poly.bounds
    x0, y1 = lonlat_to_tile(minx, miny, Z_DEM)
    x1, y0 = lonlat_to_tile(maxx, maxy, Z_DEM)
    from shapely.geometry import box
    from shapely.prepared import prep
    prepared = prep(poly)
    tiles = []
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            if prepared.intersects(box(*tile_bounds(x, y, Z_DEM))):
                tiles.append((x, y))
    if max_tiles:
        # 動作確認用: 中心付近の連続した塊を使う
        cx = sum(t[0] for t in tiles) / len(tiles)
        cy = sum(t[1] for t in tiles) / len(tiles)
        tiles.sort(key=lambda t: (t[0] - cx) ** 2 + (t[1] - cy) ** 2)
        tiles = tiles[:max_tiles]
    return tiles


# ------------------------------- ダウンロード -------------------------------

def cache_path(x, y):
    return CACHE / str(x) / ("%d.png" % y)


RETRYABLE_HTTP_CODES = frozenset({403, 429})


def _fetch_dem_tile(xy, *, recheck=False, max_attempts=4, backoff_base=1.5):
    """標高タイルを取得してキャッシュする。404 のみ空ファイルで記録し、403/429/5xx は再試行後に失敗とする。"""
    x, y = xy
    path = cache_path(x, y)
    if not recheck and path.exists():
        return xy, "cached"
    path.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(DEM_URL.format(x=x, y=y), headers={"User-Agent": USER_AGENT})
    for attempt in range(max_attempts):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                data = resp.read()
            if not data:
                if not (recheck and path.exists() and path.stat().st_size == 0):
                    path.write_bytes(b"")
                return xy, "not_found"
            tmp = path.with_suffix(".tmp")
            tmp.write_bytes(data)
            tmp.replace(path)
            return xy, ("replaced" if recheck else "fetched")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                if not (recheck and path.exists() and path.stat().st_size == 0):
                    path.write_bytes(b"")  # データなし (404 のみ)
                return xy, "not_found"
            if e.code in RETRYABLE_HTTP_CODES or 500 <= e.code < 600:
                if attempt < max_attempts - 1:
                    time.sleep(backoff_base * (2 ** attempt))
                    continue
            return xy, "failed"
        except Exception:
            if attempt < max_attempts - 1:
                time.sleep(backoff_base * (2 ** attempt))
                continue
            return xy, "failed"
    return xy, "failed"


def fetch_dem(xy):
    """標高タイルを取得してキャッシュする。海などデータが無いタイル (404) は空ファイルで記録して再取得しない。"""
    _, status = _fetch_dem_tile(xy)
    return xy, status != "failed"


def find_empty_cache_tiles():
    """CACHE 配下の 0 バイト *.png タイル (x, y) を昇順で返す。"""
    if not CACHE.is_dir():
        return []
    tiles = []
    for x_dir in sorted(CACHE.iterdir()):
        if not x_dir.is_dir():
            continue
        try:
            x = int(x_dir.name)
        except ValueError:
            continue
        for png in sorted(x_dir.glob("*.png")):
            try:
                y = int(png.stem)
            except ValueError:
                continue
            if png.stat().st_size == 0:
                tiles.append((x, y))
    return tiles


def recheck_empty(tiles=None, workers=4):
    """既にある空ファイルを確かめ直し、画像が返れば置き換える。"""
    if tiles is None:
        todo = find_empty_cache_tiles()
    else:
        todo = [t for t in tiles if cache_path(*t).exists() and cache_path(*t).stat().st_size == 0]
    print("  空タイルの再確認: %d 枚 (並列 %d)" % (len(todo), workers), flush=True)
    stats = {"total": len(todo), "replaced": 0, "not_found": 0, "failed": 0}
    if not todo:
        print("  置き換え: 0 枚", flush=True)
        return stats
    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for i, (_, status) in enumerate(pool.map(lambda xy: _fetch_dem_tile(xy, recheck=True), todo), 1):
            if status == "replaced":
                stats["replaced"] += 1
            elif status == "not_found":
                stats["not_found"] += 1
            else:
                stats["failed"] += 1
            if i % 500 == 0 or i == len(todo):
                rate = i / max(time.time() - started, 1e-6)
                print("    再確認 %d/%d (置き換え %d, %.1f枚/秒)" % (i, len(todo), stats["replaced"], rate), flush=True)
    load_dem.cache_clear()
    print(
        "  空タイル再確認の結果: 置き換え %d 枚 / データなし維持 %d 枚 / 失敗 %d 枚"
        % (stats["replaced"], stats["not_found"], stats["failed"]),
        flush=True,
    )
    return stats


def download_all(tiles, workers=6):
    todo = [t for t in tiles if not cache_path(*t).exists()]
    print("  標高タイル: 全 %d 枚 / 未取得 %d 枚" % (len(tiles), len(todo)), flush=True)
    if not todo:
        return
    failed = []
    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for i, (xy, ok) in enumerate(pool.map(fetch_dem, todo), 1):
            if not ok:
                failed.append(xy)
            if i % 500 == 0 or i == len(todo):
                rate = i / (time.time() - started)
                print("    取得 %d/%d (%.1f枚/秒, 残り約 %d 秒)" % (i, len(todo), rate, (len(todo) - i) / max(rate, 0.01)), flush=True)
    if failed:
        sys.exit("取得に失敗したタイルが %d 枚あります (再実行すると続きから取得します)" % len(failed))


# ------------------------------- 標高の読み込み -------------------------------

@functools.lru_cache(maxsize=1200)
def load_dem(x, y):
    """z14 タイルの標高 (m, float32 256x256)。データなしは None、海などの欠測は 0m。"""
    path = cache_path(x, y)
    if not path.exists() or path.stat().st_size == 0:
        return None
    rgb = np.asarray(Image.open(path).convert("RGB"), dtype=np.int64)
    v = rgb[..., 0] * 65536 + rgb[..., 1] * 256 + rgb[..., 2]
    h = np.where(v < 2 ** 23, v, v - 2 ** 24).astype(np.float32) * 0.01
    h[v == 2 ** 23] = 0.0
    return h


def dem_window(z, tx, ty, pad):
    """z のタイル (tx, ty) を、周囲 pad px (z の解像度) を含めて返す。z14 の標高を平均して縮小する。"""
    scale = 2 ** (Z_DEM - z)
    size = (256 + 2 * pad) * scale
    px0 = tx * scale * 256 - pad * scale
    py0 = ty * scale * 256 - pad * scale
    out = np.zeros((size, size), dtype=np.float32)
    any_data = False
    for gx in range(px0 // 256, (px0 + size - 1) // 256 + 1):
        for gy in range(py0 // 256, (py0 + size - 1) // 256 + 1):
            tile = load_dem(gx, gy)
            if tile is None:
                continue
            any_data = True
            sx0, sy0 = max(px0, gx * 256), max(py0, gy * 256)
            sx1, sy1 = min(px0 + size, gx * 256 + 256), min(py0 + size, gy * 256 + 256)
            out[sy0 - py0:sy1 - py0, sx0 - px0:sx1 - px0] = tile[sy0 - gy * 256:sy1 - gy * 256, sx0 - gx * 256:sx1 - gx * 256]
    if not any_data:
        return None
    if scale > 1:
        n = 256 + 2 * pad
        out = out.reshape(n, scale, n, scale).mean(axis=(1, 3))
    return out


# ------------------------------- 陰影起伏 -------------------------------

AZIMUTH_DEG = 315.0
ZENITH_DEG = 45.0


def hillshade_tile(z, x, y):
    """陰影起伏の PNG (LA, 256x256)。影のない平地・海だけのタイルは None。"""
    arr = dem_window(z, x, y, pad=1)
    if arr is None:
        return None
    lat = math.radians((tile_bounds(x, y, z)[1] + tile_bounds(x, y, z)[3]) / 2)
    cell = 156543.03392 * math.cos(lat) / (2 ** z)  # 1px の実距離 (m)
    exaggeration = 1.5 * 2 ** ((Z_DEM - z) * 0.25)  # 引くほど地形が平らに見えるので少し強調する
    dzdx = (arr[1:-1, 2:] - arr[1:-1, :-2]) / (2 * cell) * exaggeration
    dzdy_north = -(arr[2:, 1:-1] - arr[:-2, 1:-1]) / (2 * cell) * exaggeration  # 画像は y が下向き
    slope = np.arctan(np.hypot(dzdx, dzdy_north))
    aspect = np.arctan2(dzdy_north, -dzdx)
    zen = math.radians(ZENITH_DEG)
    az = math.radians((360.0 - AZIMUTH_DEG + 90.0) % 360.0)
    shade = math.cos(zen) * np.cos(slope) + math.sin(zen) * np.sin(slope) * np.cos(az - aspect)
    flat = math.cos(zen)
    dark = np.clip((flat - shade) / flat, 0.0, 1.0)
    light = np.clip((shade - flat) / (1.0 - flat), 0.0, 1.0)
    alpha = np.clip(dark * 0.65 + light * 0.22, 0.0, 1.0)
    if float(alpha.max()) < 0.02:
        return None
    gray = np.where(dark >= light, 0, 255).astype(np.uint8)
    la = np.dstack([gray, (alpha * 255).astype(np.uint8)])
    buf = __import__("io").BytesIO()
    Image.fromarray(la, mode="LA").save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def hillshade_job(args):
    z, x, y = args
    return (z, x, y), hillshade_tile(z, x, y)


# ------------------------------- 等高線 -------------------------------

def smooth(arr):
    """3x3 の平均で標高の細かいノイズを均す (等高線のぎざぎざを抑える)。"""
    p = np.pad(arr, 1, mode="edge")
    return sum(p[i:i + arr.shape[0], j:j + arr.shape[1]] for i in range(3) for j in range(3)) / 9.0


def contour_tile(z, x, y):
    """等高線の MVT (gzip)。等高線のないタイルは None。"""
    pad = 2
    arr = dem_window(z, x, y, pad=pad)
    if arr is None:
        return None
    arr = smooth(arr)
    interval, index_interval = CONTOUR_ZOOMS[z]
    lo, hi = float(arr.min()), float(arr.max())
    if hi - lo < 1.0 or hi <= interval:
        return None
    levels = np.arange(max(interval, math.floor(lo / interval) * interval), hi + 0.001, interval)
    if len(levels) == 0:
        return None
    cg = contour_generator(z=arr)
    clip = box(-8, -8, 264, 264)
    features = []
    for level in levels:
        lines = cg.lines(float(level))
        geoms = []
        for pts in lines:
            if len(pts) < 2:
                continue
            xy = pts - pad + 0.5  # 配列の添字 → タイル内の px
            line = LineString(xy).simplify(0.3)
            clipped = line.intersection(clip)
            if clipped.is_empty:
                continue
            if isinstance(clipped, (LineString, MultiLineString)):
                geoms.append(clipped)
        if not geoms:
            continue
        kind = "index" if int(level) % index_interval == 0 else "minor"
        geometry = MultiLineString([g for geom in geoms for g in (geom.geoms if hasattr(geom, "geoms") else [geom])])
        features.append({"geometry": geometry.wkt, "properties": {"ele": int(level), "kind": kind}})
    if not features:
        return None
    data = mvt.encode(
        [{"name": "contour", "features": features}],
        quantize_bounds=(0, 0, 256, 256), y_coord_down=True, extents=EXTENT,
    )
    return gzip.compress(data, 6)


def contour_job(args):
    z, x, y = args
    return (z, x, y), contour_tile(z, x, y)


# ------------------------------- 書き出し -------------------------------

def parent_tiles(tiles, z):
    scale = 2 ** (Z_DEM - z)
    return sorted({(x // scale, y // scale) for x, y in tiles})


def write_pmtiles(path, tiles, tile_type, compression, min_zoom, max_zoom, bounds, metadata):
    """tiles: {(z,x,y): bytes}。PMTiles はタイル ID 順に書く必要がある。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".pmtiles.tmp")
    tmp.unlink(missing_ok=True)
    minx, miny, maxx, maxy = bounds
    header = {
        "tile_type": tile_type, "tile_compression": compression,
        "min_zoom": min_zoom, "max_zoom": max_zoom,
        "min_lon_e7": int(minx * 1e7), "min_lat_e7": int(miny * 1e7),
        "max_lon_e7": int(maxx * 1e7), "max_lat_e7": int(maxy * 1e7),
        "center_zoom": min(max_zoom, 12),
        "center_lon_e7": int((minx + maxx) / 2 * 1e7), "center_lat_e7": int((miny + maxy) / 2 * 1e7),
    }
    with pmtiles_write(str(tmp)) as writer:
        for (z, x, y), data in sorted(tiles.items(), key=lambda kv: zxy_to_tileid(*kv[0])):
            writer.write_tile(zxy_to_tileid(z, x, y), data)
        writer.finalize(header, metadata)
    tmp.replace(path)


def run_pool(fn, jobs, workers, label):
    results = {}
    started = time.time()
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers, initializer=_load_generation_dependencies) as pool:
        for i, (key, data) in enumerate(pool.map(fn, jobs, chunksize=8), 1):
            if data:
                results[key] = data
            if i % 1000 == 0 or i == len(jobs):
                print("    %s %d/%d (%.0f秒)" % (label, i, len(jobs), time.time() - started), flush=True)
    return results


def build_prefecture(name, workers, force, max_tiles, out_dir):
    _load_generation_dependencies()
    lname = name.lower()
    hill_path = out_dir / ("%s.hillshade.pmtiles" % lname)
    cont_path = out_dir / ("%s.contours.pmtiles" % lname)
    if not force and hill_path.exists() and cont_path.exists():
        print("[%s] SKIP: 既にあります (%s, %s)" % (name, hill_path.name, cont_path.name))
        return
    print("[%s] 標高タイルの対象を計算中..." % name, flush=True)
    tiles = dem_tiles_for(name, max_tiles)
    download_all(tiles)
    poly_bounds = prefecture_shape(name).bounds

    print("  陰影起伏を生成 (z%d-%d)" % (min(HILLSHADE_ZOOMS), max(HILLSHADE_ZOOMS)), flush=True)
    jobs = [(z, x, y) for z in HILLSHADE_ZOOMS for x, y in parent_tiles(tiles, z)]
    hill = run_pool(hillshade_job, jobs, workers, "陰影")
    write_pmtiles(hill_path, hill, TileType.PNG, Compression.NONE, min(HILLSHADE_ZOOMS), max(HILLSHADE_ZOOMS), poly_bounds,
                  {"name": "%s hillshade" % name, "format": "png", "type": "overlay",
                   "attribution": "国土地理院 標高タイル (基盤地図情報数値標高モデル)",
                   "description": "GSI dem_png から生成した陰影起伏 (半透明のオーバーレイ)"})
    print("  -> %s (%d 枚, %.1f MB)" % (hill_path.name, len(hill), hill_path.stat().st_size / 1048576), flush=True)

    print("  等高線を生成 (z%d-%d)" % (min(CONTOUR_ZOOMS), max(CONTOUR_ZOOMS)), flush=True)
    jobs = [(z, x, y) for z in CONTOUR_ZOOMS for x, y in parent_tiles(tiles, z)]
    cont = run_pool(contour_job, jobs, workers, "等高線")
    write_pmtiles(cont_path, cont, TileType.MVT, Compression.GZIP, min(CONTOUR_ZOOMS), max(CONTOUR_ZOOMS), poly_bounds,
                  {"name": "%s contours" % name, "format": "pbf", "type": "overlay",
                   "attribution": "国土地理院 標高タイル (基盤地図情報数値標高モデル)",
                   "vector_layers": [{"id": "contour", "fields": {"ele": "Number", "kind": "String"},
                                      "minzoom": min(CONTOUR_ZOOMS), "maxzoom": max(CONTOUR_ZOOMS)}],
                   "json": json.dumps({"vector_layers": [{"id": "contour", "fields": {"ele": "Number", "kind": "String"}}]})})
    print("  -> %s (%d 枚, %.1f MB)" % (cont_path.name, len(cont), cont_path.stat().st_size / 1048576), flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="+", help="Prefectures, regions, or all (comma/space separated; default: Chugoku five)")
    ap.add_argument("--force", action="store_true", help="出力があっても作り直す")
    ap.add_argument("--dry-run", action="store_true", help="対象タイル数だけ表示する")
    ap.add_argument("--dem-only", action="store_true",
                    help="標高タイル (work/terrain/dem14) の取得だけ行う。osm-importer が辺の勾配を書き込むために先に要る")
    ap.add_argument("--recheck-empty", action="store_true",
                    help="既にある空キャッシュ (0バイト) を再取得し、画像が返れば置き換える")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1), help="生成の並列数")
    ap.add_argument("--max-tiles", type=int, help="動作確認用: 県の中心付近の z14 タイルをこの枚数だけ使う")
    ap.add_argument("--out", type=Path, default=PACKAGES, help="出力先 (既定: packages/)")
    args = ap.parse_args()

    try:
        names = resolve(args.only)
    except ValueError as exc:
        ap.error(str(exc))
    if args.recheck_empty:
        tiles = None
        if args.only is not None:
            seen = set()
            tiles = []
            for name in names:
                for xy in dem_tiles_for(name, args.max_tiles):
                    if xy not in seen:
                        seen.add(xy)
                        tiles.append(xy)
        stats = recheck_empty(tiles=tiles, workers=min(4, max(1, args.workers)))
        return 1 if stats["failed"] else 0
    print("Selected: %s" % ",".join(names))
    for name in names:
        if args.dry_run:
            tiles = dem_tiles_for(name, args.max_tiles)
            cached = sum(1 for t in tiles if cache_path(*t).exists())
            print("[%s] z14 標高タイル %d 枚 (取得済み %d 枚)" % (name, len(tiles), cached))
        elif args.dem_only:
            print("[%s] 標高タイルの取得のみ" % name, flush=True)
            download_all(dem_tiles_for(name, args.max_tiles))
        else:
            build_prefecture(name, args.workers, args.force, args.max_tiles, args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
