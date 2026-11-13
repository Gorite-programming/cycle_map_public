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
  5. ダーク (夜) は「墨と朱」: 青みのある灰色を、温かみのある墨色に置き換える (SUMI_SHU_DARK)。
     アプリの画面の配色 (ui/theme/Palette.kt の SumiShu) と同じ系統。
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
    "dark": {"base": "#7D7468", "tick": "#1C1A17", "tram": "#5A5248"},
}

# 鉄道の太さ (ズーム → 幅)
RAIL_WIDTH_BASE = ["interpolate", ["exponential", 1.6], ["zoom"], 6, 0.5, 12, 1.8, 15, 3.0, 18, 5.0]
RAIL_WIDTH_TICK = ["interpolate", ["exponential", 1.6], ["zoom"], 6, 0.3, 12, 1.0, 15, 1.7, 18, 2.8]


def shield_len_image(prefix):
    return ["concat", prefix, ["length", ["get", "shield_text"]], "char"]


def patch_shields(layer, theme):
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
    layer["layout"]["text-size"] = 12
    layer["layout"]["icon-size"] = 1
    layer["layout"]["symbol-spacing"] = 320
    # 逆三角形 (国道) は数字を載せる広い上寄りに、六角形 (県道) は中央に置く
    layer["layout"]["text-offset"] = ["match", ["get", "network"], "JP:national", ["literal", [0, -0.18]], ["literal", [0, 0]]]
    layer["layout"]["text-padding"] = 2
    paint = layer["paint"]
    # 国道・県道とも青地に白文字 (形で区別する)。標識の上に載る文字なので、地図背景ではなく標識の青との対比で決める
    paint["text-color"] = "#FFFFFF"
    if theme == "dark":
        # ダークの既存規約 (ハロー幅 1.5 以上・地図背景より暗い色) を守りつつ、標識の青に溶ける濃紺にして数字を太く見せない
        paint["text-halo-color"] = "#062244"


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
    ("landuse_wood", ["forest", "wood", "scrub"], "#B7D9B0", "#1D251A"),
    ("landuse_farmland", ["farmland", "orchard", "vineyard", "meadow", "greenhouse_horticulture", "plant_nursery"],
     "#E8EDC8", "#26261C"),
    ("landuse_cemetery", ["cemetery"], "#D6E3D2", "#222820"),
    ("landuse_residential", ["residential"], "#ECEEF3", "#221F1C"),
    ("landuse_commercial", ["commercial", "retail"], "#F3E9DE", "#2B241E"),
    ("landuse_pitch", ["pitch", "recreation_ground", "sports_centre"], "#CDE8C8", "#26301F"),
    ("landuse_military", ["military", "naval_base"], "#E3E1E6", "#27231F"),
]
PARK_COLORS = {"light": "#C9E8C4", "dark": "#232B1F"}
WATER_COLORS = {"light": "#AFCFE6", "dark": "#1E2A33"}

# ダークの「墨と朱」: 層ごとの色 (paint のキー → 色)。土地利用の分割分と水域は上の表が担当する。
# 墨の地 #1C1A17、道は少し明るい墨、文字は和紙色、ハローは地より暗い墨。
_SUMI_ROAD_CASING = "#141210"
_SUMI_ROAD_MINOR = "#2E2A25"
_SUMI_ROAD_MAJOR = "#3B342C"
_SUMI_ROAD_HIGHWAY = "#6B5A44"
_SUMI_HALO = "#141210"
SUMI_SHU_DARK = {
    "background": {"background-color": "#1C1A17"},
    "earth": {"fill-color": "#1C1A17"},
    "landuse_urban_green": {"fill-color": "#212A1F"},
    "landuse_hospital": {"fill-color": "#2A2220"},
    "landuse_industrial": {"fill-color": "#24221E"},
    "landuse_school": {"fill-color": "#27241F"},
    "landuse_beach": {"fill-color": "#2A2620"},
    "landuse_zoo": {"fill-color": "#222820"},
    "landuse_aerodrome": {"fill-color": "#26231F"},
    "landuse_runway": {"fill-color": "#2E2A25"},
    "landuse_pedestrian": {"fill-color": "#25221E"},
    "landuse_pier": {"fill-color": "#25221E"},
    "landuse_platform": {"fill-color": "#322B23"},
    "roads_runway": {"line-color": "#2E2A25"},
    "roads_taxiway": {"line-color": "#2E2A25"},
    "roads_pier": {"line-color": "#25221E"},
    "buildings-2d": {"fill-color": "#28241F", "fill-outline-color": "#3A342D"},
    "buildings_station": {"fill-color": "#322B23", "fill-outline-color": "#3A342D"},
    "buildings-3d": {"fill-extrusion-color": "#2E2A25"},
    "boundaries_country": {"line-color": "#5A5248"},
    "boundaries": {"line-color": "#5A5248"},
    "address_label": {"text-color": "#CFC6B8", "text-halo-color": _SUMI_HALO},
    "roads_labels_minor": {"text-color": "#E3DACB", "text-halo-color": _SUMI_HALO},
    "roads_labels_major": {"text-color": "#F2EBDF", "text-halo-color": _SUMI_HALO},
    "earth_label_islands": {"text-color": "#E3DACB", "text-halo-color": _SUMI_HALO},
    "water_waterway_label": {"text-color": "#A9C0D2", "text-halo-color": "#121A20"},
    "water_label_ocean": {"text-color": "#A9C0D2", "text-halo-color": "#121A20"},
    "water_label_lakes": {"text-color": "#A9C0D2", "text-halo-color": "#121A20"},
    "places_subplace": {"text-color": "#E3DACB", "text-halo-color": _SUMI_HALO},
    "places_region": {"text-color": "#CFC6B8", "text-halo-color": _SUMI_HALO},
    "places_locality": {"text-color": "#F5EFE4", "text-halo-color": "#0E0D0B"},
    "places_country": {"text-color": "#EDE6DA", "text-halo-color": _SUMI_HALO},
    "pois": {"text-halo-color": _SUMI_HALO},
}


def patch_sumi_shu(style):
    """ダークを「墨と朱」の色にする。道路は id の形 (casing / highway / major / それ以外) で塗り分ける。"""
    for layer in style["layers"]:
        lid = layer["id"]
        paint = layer.setdefault("paint", {})
        if lid in SUMI_SHU_DARK:
            paint.update(SUMI_SHU_DARK[lid])
            continue
        if not lid.startswith("roads_") or layer["type"] != "line" or "rail" in lid:
            continue
        if lid in ("roads_runway", "roads_taxiway", "roads_pier"):
            continue
        if "casing" in lid:
            color = _SUMI_ROAD_CASING
        elif "highway" in lid:
            color = _SUMI_ROAD_HIGHWAY
        elif "major" in lid:
            color = _SUMI_ROAD_MAJOR
        else:
            color = _SUMI_ROAD_MINOR
        paint["line-color"] = color


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


def raise_shields(style):
    """道路番号の標識の層を、道路名・POI・地名の上 (配置の優先が高い位置) へ移す。

    MapLibre は上の層から順にラベルを置き、重なるものは後の層が消える。標識は道路名・POI より下に
    あったため、市街地では z15 でも標識がほとんど出なかった (Google マップは z12 から出す)。冪等。
    """
    layers = style["layers"]
    shield = next(l for l in layers if l["id"] == "roads_shields")
    layers.remove(shield)
    anchor = max(i for i, l in enumerate(layers) if l["id"] in ("pois", "roads_labels_major"))
    layers.insert(anchor + 1, shield)
    # 標識は小さいので、ほかのラベルとの間隔を詰めて置ける場所を増やす
    shield["layout"]["icon-padding"] = 1
    shield["layout"]["text-padding"] = 1
    # 標識は、ほかのラベルと重なっても出す (Google マップと同じく番号を優先)。間隔は symbol-spacing で保つ
    shield["layout"]["icon-allow-overlap"] = True
    shield["layout"]["text-allow-overlap"] = True


def patch_style(theme):
    path = STYLE_DIR / ("protomaps_%s.json" % theme)
    style = json.loads(path.read_text(encoding="utf-8"))
    layers = {l["id"]: l for l in style["layers"]}
    patch_shields(layers["roads_shields"], theme)
    patch_rail(layers, theme)
    patch_pois(layers["pois"])
    patch_landuse(style, theme)
    if theme == "dark":
        patch_sumi_shu(style)
    raise_shields(style)
    path.write_text(json.dumps(style, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("patched %s" % path.name)


def main():
    for theme in THEMES:
        patch_style(theme)
    return 0


if __name__ == "__main__":
    sys.exit(main())
