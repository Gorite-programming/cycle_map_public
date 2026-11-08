#!/usr/bin/env python3
"""アプリ同梱の Protomaps スタイル (protomaps_light/dark.json) に CycleMap 独自の調整を当てる。

スタイルの元は @protomaps/basemaps が生成したもの。CycleMap 向けの差分はこのスクリプトを正とし、
何度実行しても同じ結果になる (冪等)。スタイルを作り直した後は、このスクリプトを再実行すること。

調整内容:
  1. 国道・県道のシールド: JP:national / JP:prefectural を日本の標識 (青いおにぎり型 / 六角形) にする。
     画像は実行時にコード (VectorMapStyles.kt の registerRoadShieldImages) が jp-national-Nchar /
     jp-prefectural-Nchar として登録する。高速道路 (kind=highway) は関連路線の番号が混ざるため対象外。
  2. 鉄道: JR 等の本線を「黒地に白い破線」のゼブラ表示にする。路面電車・地下鉄などは細い実線のまま。
  3. 土地利用: 公園・森林・農地・墓地・住宅地・商業地・運動場・軍用地を別々の色で塗り分け、水域を濃くする。
     色の定義はこのスクリプトが正。Kotlin 側の VectorMapThemeHelper.vectorMapAreaFillColors も同じ色にそろえる
     (StyleJsonTest が一致を検証する)。
  4. タイル内 POI: 動的 POI ピン (検索DB由来) が担うカテゴリ (飲食・コンビニ・トイレ・給水・バス停) は
     スタイル側から外し、同じ施設が二重に出ないようにする。

使い方 (リポジトリ直下から):
    python3 scripts/patch_map_style.py
"""

import json
import sys
from pathlib import Path

STYLE_DIR = Path(__file__).resolve().parent.parent / "application/app/src/main/assets/styles"
THEMES = ("light", "dark")

# 動的 POI ピンが担うため、タイルの POI から外す kind
POI_KINDS_COVERED_BY_PINS = {"restaurant", "fast_food", "cafe", "convenience", "toilets", "drinking_water", "bus_stop"}

# テーマ別の鉄道配色 (base=黒地, tick=白い破線)
RAIL = {
    "light": {"base": "#4B5058", "tick": "#FFFFFF", "tram": "#7C838B"},
    "dark": {"base": "#6E7681", "tick": "#1B1F24", "tram": "#5A616A"},
}

# 鉄道の太さ (ズーム → 幅)
RAIL_WIDTH_BASE = ["interpolate", ["exponential", 1.6], ["zoom"], 6, 0.5, 12, 1.8, 15, 3.0, 18, 5.0]
RAIL_WIDTH_TICK = ["interpolate", ["exponential", 1.6], ["zoom"], 6, 0.3, 12, 1.0, 15, 1.7, 18, 2.8]


def shield_len_image(prefix):
    return ["concat", prefix, ["length", ["get", "shield_text"]], "char"]


def patch_shields(layer):
    layer["filter"] = [
        "all",
        ["==", ["get", "kind"], "major_road"],
        ["has", "shield_text"],
        ["<=", ["length", ["get", "shield_text"]], 4],
    ]
    layer["layout"]["icon-image"] = [
        "match", ["get", "network"],
        "JP:national", shield_len_image("jp-national-"),
        "JP:prefectural", shield_len_image("jp-prefectural-"),
        shield_len_image("generic_shield-"),
    ]
    layer["layout"]["text-size"] = 10
    layer["layout"]["icon-size"] = 1
    layer["layout"]["symbol-spacing"] = 300
    paint = layer["paint"]
    # 国道・県道とも青地に白文字 (形で区別する)。標識の上に載る文字なので、地図背景ではなく標識の青との対比で決める
    paint["text-color"] = "#FFFFFF"


def patch_rail(layers, theme):
    colors = RAIL[theme]
    base = layers["roads_rail_base"]
    hatch = layers["roads_rail_hatch"]
    tram = layers["roads_rail_tram"]
    base["paint"]["line-color"] = colors["base"]
    base["paint"]["line-width"] = RAIL_WIDTH_BASE
    hatch["paint"]["line-color"] = colors["tick"]
    hatch["paint"]["line-width"] = RAIL_WIDTH_TICK
    hatch["paint"]["line-dasharray"] = [2, 2]
    tram["paint"]["line-color"] = colors["tram"]
    # 橋の上の鉄道も同じ表現にする
    for bridge_id, src in (("roads_bridges_rail_base", base), ("roads_bridges_rail_hatch", hatch),
                           ("roads_bridges_rail_tram", tram)):
        if bridge_id in layers:
            layers[bridge_id]["paint"] = json.loads(json.dumps(src["paint"]))


def patch_pois(layer):
    flt = layer["filter"]
    # ["all", ["in", ["get","kind"], ["literal", [...]]], [">=", ...]]
    kinds = flt[1][2][1]
    flt[1][2][1] = [k for k in kinds if k not in POI_KINDS_COVERED_BY_PINS]


# 土地利用の塗り分け。landuse_park は残りの種別 (公園・草地・ゴルフ場など) を担当する。
# (layer id, 担当する kind, light の色, dark の色)
LANDUSE_PARK_KINDS = ["national_park", "park", "protected_area", "nature_reserve", "golf_course", "grassland", "grass",
                      "glacier", "sand", "airfield"]
LANDUSE_SPLIT = [
    ("landuse_wood", ["forest", "wood", "scrub"], "#B7D9B0", "#162A1D"),
    ("landuse_farmland", ["farmland", "orchard", "vineyard", "meadow", "greenhouse_horticulture", "plant_nursery"],
     "#E8EDC8", "#26301F"),
    ("landuse_cemetery", ["cemetery"], "#D6E3D2", "#1F2C25"),
    ("landuse_residential", ["residential"], "#ECEEF3", "#262729"),
    ("landuse_commercial", ["commercial", "retail"], "#F3E9DE", "#2D2823"),
    ("landuse_pitch", ["pitch", "recreation_ground", "sports_centre"], "#CDE8C8", "#23402F"),
    ("landuse_military", ["military", "naval_base"], "#E3E1E6", "#2B2B30"),
]
PARK_COLORS = {"light": "#C9E8C4", "dark": "#20372A"}
WATER_COLORS = {"light": "#AFCFE6", "dark": "#1A3552"}


def patch_landuse(style, theme):
    layers = style["layers"]
    by_id = {l["id"]: l for l in layers}
    park = by_id["landuse_park"]
    park["filter"] = ["in", "kind"] + LANDUSE_PARK_KINDS
    park["paint"]["fill-color"] = PARK_COLORS[theme]
    insert_at = layers.index(park) + 1
    for layer_id, kinds, light, dark in LANDUSE_SPLIT:
        color = light if theme == "light" else dark
        if layer_id in by_id:
            layer = by_id[layer_id]
        else:
            layer = json.loads(json.dumps(park))
            layer["id"] = layer_id
            layers.insert(insert_at, layer)
            insert_at += 1
        layer["filter"] = ["in", "kind"] + kinds
        layer["paint"]["fill-color"] = color
    for water_id in ("water", "water_river", "water_stream"):
        if water_id in by_id:
            paint = by_id[water_id]["paint"]
            key = "fill-color" if "fill-color" in paint else "line-color"
            paint[key] = WATER_COLORS[theme]


def patch_style(theme):
    path = STYLE_DIR / ("protomaps_%s.json" % theme)
    style = json.loads(path.read_text(encoding="utf-8"))
    layers = {l["id"]: l for l in style["layers"]}
    patch_shields(layers["roads_shields"])
    patch_rail(layers, theme)
    patch_pois(layers["pois"])
    patch_landuse(style, theme)
    path.write_text(json.dumps(style, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("patched %s" % path.name)


def main():
    for theme in THEMES:
        patch_style(theme)
    return 0


if __name__ == "__main__":
    sys.exit(main())
