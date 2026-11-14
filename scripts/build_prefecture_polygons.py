#!/usr/bin/env python3
"""県判定ポリゴンの生成: boundaries/<Romaji>.geojson → アプリのリソース。

`application/app/src/main/resources/prefecture_polygons/<id>.txt` (id は県名の小文字ローマ字) を 47 県分作る。
アプリ側 (`PrefectureBoundaries.kt`) は、必要な県のファイルだけを遅延で読んで県判定 (`findPrefectureName`) に使う。
Kotlin の定数に埋め込むと 1 定数 64KB の上限と起動時の負荷が問題になるため、リソースにしている。

形式 (1 県 1 ファイル、UTF-8、末尾改行なし):
    ポリゴン同士は ';'、ポリゴン内のリング (外周 + 穴) は '|'、リングの点は 'lon,lat' を空白区切り。
    座標は小数 5 桁 (末尾の 0 は省く)、リングは始点で閉じる (GeoJSON のまま)。

冪等。境界 GeoJSON を更新したら再実行し、差分を commit する。

使い方 (リポジトリ直下から):
    python3 scripts/build_prefecture_polygons.py            # 書き出す
    python3 scripts/build_prefecture_polygons.py --check    # 書き出さず、いまのファイルと一致するか確かめる (CI 向け)
"""

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from scripts.prefectures import PREFECTURES

BOUNDARIES = ROOT / "boundaries"
OUT_DIR = ROOT / "application" / "app" / "src" / "main" / "resources" / "prefecture_polygons"


def num(value: float) -> str:
    """小数 5 桁で丸め、末尾の 0 を省く (133.98810 → 133.9881、36.00000 → 36.0)。"""
    text = ("%.5f" % value).rstrip("0")
    return text + "0" if text.endswith(".") else text


def encode(geojson_path: Path) -> str:
    data = json.loads(geojson_path.read_text(encoding="utf-8"))
    polygons = []
    for feature in data["features"]:
        geometry = feature["geometry"]
        if geometry["type"] == "MultiPolygon":
            polygons.extend(geometry["coordinates"])
        elif geometry["type"] == "Polygon":
            polygons.append(geometry["coordinates"])
        else:
            raise ValueError("%s: 未対応の geometry %s" % (geojson_path.name, geometry["type"]))
    if not polygons:
        raise ValueError("%s: ポリゴンがありません" % geojson_path.name)
    return ";".join(
        "|".join(" ".join("%s,%s" % (num(lon), num(lat)) for lon, lat, *_ in ring) for ring in polygon)
        for polygon in polygons
    )


def build() -> dict:
    """{id: 内容} を返す (書き出しはしない)。"""
    result = {}
    for pref in PREFECTURES:
        path = BOUNDARIES / ("%s.geojson" % pref["name"])
        result[pref["name"].lower()] = encode(path)
    return result


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="書き出さず、いまのファイルと一致するか確かめる")
    args = ap.parse_args()
    built = build()
    if args.check:
        stale = [i for i, text in built.items()
                 if not (OUT_DIR / ("%s.txt" % i)).is_file()
                 or (OUT_DIR / ("%s.txt" % i)).read_text(encoding="utf-8") != text]
        if stale:
            print("古い・無いファイル: %s" % ", ".join(stale), file=sys.stderr)
            return 1
        print("OK: %d 県とも一致" % len(built))
        return 0
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    total = 0
    for pref_id, text in built.items():
        # 改行コードを変換されないよう、バイト列で書く
        (OUT_DIR / ("%s.txt" % pref_id)).write_bytes(text.encode("utf-8"))
        total += len(text)
    print("%d 県、合計 %.2f MB を %s に書きました" % (len(built), total / 1e6, OUT_DIR.relative_to(ROOT)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
