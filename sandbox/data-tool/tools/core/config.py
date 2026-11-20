"""パス・県定義・カテゴリ対応表 (SPEC 2, 4, 7)。

ロジックは持たず定数と軽い県判定ヘルパーのみ。
地理演算は標準ライブラリの自前実装 (shapely 不使用)。
"""

import json
import math
import os

TOOL_VERSION = "0.1.0"
SEARCH_SCHEMA_VERSION = "2"  # 2: opening_hours / phone / website / wheelchair 列を追加

BASE_DIR = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)
REPO_ROOT = os.path.dirname(os.path.dirname(BASE_DIR))
DATA_DIR = os.path.join(BASE_DIR, "data")
RAW_DIR = os.path.join(DATA_DIR, "raw")
WORK_DIR = os.path.join(DATA_DIR, "work")
OUT_DIR = os.path.join(BASE_DIR, "out")
ROOT_BOUNDARIES_DIR = os.path.join(REPO_ROOT, "boundaries")


def resolve_boundaries_dir(raw_dir=RAW_DIR, repo_root=REPO_ROOT):
    """行政界ディレクトリを解決する。data/raw/boundaries に無ければルートの boundaries/ へフォールバック。"""
    local = os.path.join(raw_dir, "boundaries")
    root = os.path.join(repo_root, "boundaries")
    if os.path.isdir(local) and any(name.endswith(".geojson") for name in os.listdir(local)):
        return local
    if os.path.isdir(root):
        return root
    return local


BOUNDARIES_DIR = resolve_boundaries_dir()
REFERENCE_DIR = os.path.join(RAW_DIR, "reference")
MASTER_DB_PATH = os.path.join(WORK_DIR, "master.db")
BACKUP_DIR = os.path.join(WORK_DIR, "backup")
BACKUP_KEEP = 5

# 県定義。bbox は data/raw/boundaries/<Boundary>.geojson から算出
# (lon_min, lat_min, lon_max, lat_max)。検索DB名は <県ID小文字>.search.db に統一。
PREFECTURES = {
    "hokkaido": {"id": "hokkaido", "name_ja": "北海道", "name_en": "Hokkaido", "search_db": "hokkaido.search.db", "boundary": "Hokkaido.geojson", "bbox": (139.33437, 41.35212, 148.89234, 45.55724)},
    "aomori": {"id": "aomori", "name_ja": "青森県", "name_en": "Aomori", "search_db": "aomori.search.db", "boundary": "Aomori.geojson", "bbox": (139.85973, 40.21771, 141.68259, 41.55541)},
    "iwate": {"id": "iwate", "name_ja": "岩手県", "name_en": "Iwate", "search_db": "iwate.search.db", "boundary": "Iwate.geojson", "bbox": (140.65323, 38.74781, 142.07248, 40.45026)},
    "miyagi": {"id": "miyagi", "name_ja": "宮城県", "name_en": "Miyagi", "search_db": "miyagi.search.db", "boundary": "Miyagi.geojson", "bbox": (140.27489, 37.77337, 141.67464, 39.00276)},
    "akita": {"id": "akita", "name_ja": "秋田県", "name_en": "Akita", "search_db": "akita.search.db", "boundary": "Akita.geojson", "bbox": (139.69296, 38.87301, 140.99543, 40.51106)},
    "yamagata": {"id": "yamagata", "name_ja": "山形県", "name_en": "Yamagata", "search_db": "yamagata.search.db", "boundary": "Yamagata.geojson", "bbox": (139.52046, 37.73383, 140.64646, 39.20853)},
    "fukushima": {"id": "fukushima", "name_ja": "福島県", "name_en": "Fukushima", "search_db": "fukushima.search.db", "boundary": "Fukushima.geojson", "bbox": (139.16491, 36.7916, 141.04586, 37.97664)},
    "ibaraki": {"id": "ibaraki", "name_ja": "茨城県", "name_en": "Ibaraki", "search_db": "ibaraki.search.db", "boundary": "Ibaraki.geojson", "bbox": (139.68794, 35.73914, 140.85197, 36.94528)},
    "tochigi": {"id": "tochigi", "name_ja": "栃木県", "name_en": "Tochigi", "search_db": "tochigi.search.db", "boundary": "Tochigi.geojson", "bbox": (139.32657, 36.19968, 140.29216, 37.15506)},
    "gunma": {"id": "gunma", "name_ja": "群馬県", "name_en": "Gunma", "search_db": "gunma.search.db", "boundary": "Gunma.geojson", "bbox": (138.39709, 35.98533, 139.66994, 37.05863)},
    "saitama": {"id": "saitama", "name_ja": "埼玉県", "name_en": "Saitama", "search_db": "saitama.search.db", "boundary": "Saitama.geojson", "bbox": (138.71136, 35.75338, 139.89997, 36.2834)},
    "chiba": {"id": "chiba", "name_ja": "千葉県", "name_en": "Chiba", "search_db": "chiba.search.db", "boundary": "Chiba.geojson", "bbox": (139.73956, 34.8994, 140.88162, 36.1041)},
    "tokyo": {"id": "tokyo", "name_ja": "東京都", "name_en": "Tokyo", "search_db": "tokyo.search.db", "boundary": "Tokyo.geojson", "bbox": (138.94287, 24.22485, 153.98607, 35.89826)},
    "kanagawa": {"id": "kanagawa", "name_ja": "神奈川県", "name_en": "Kanagawa", "search_db": "kanagawa.search.db", "boundary": "Kanagawa.geojson", "bbox": (138.9164, 35.12919, 139.80139, 35.67241)},
    "niigata": {"id": "niigata", "name_ja": "新潟県", "name_en": "Niigata", "search_db": "niigata.search.db", "boundary": "Niigata.geojson", "bbox": (137.63498, 36.73655, 139.8999, 38.55295)},
    "toyama": {"id": "toyama", "name_ja": "富山県", "name_en": "Toyama", "search_db": "toyama.search.db", "boundary": "Toyama.geojson", "bbox": (136.76838, 36.27436, 137.76244, 36.9798)},
    "ishikawa": {"id": "ishikawa", "name_ja": "石川県", "name_en": "Ishikawa", "search_db": "ishikawa.search.db", "boundary": "Ishikawa.geojson", "bbox": (136.24304, 36.06723, 137.36101, 37.85517)},
    "fukui": {"id": "fukui", "name_ja": "福井県", "name_en": "Fukui", "search_db": "fukui.search.db", "boundary": "Fukui.geojson", "bbox": (135.44941, 35.34368, 136.83235, 36.2953)},
    "yamanashi": {"id": "yamanashi", "name_ja": "山梨県", "name_en": "Yamanashi", "search_db": "yamanashi.search.db", "boundary": "Yamanashi.geojson", "bbox": (138.18009, 35.16838, 139.13441, 35.97144)},
    "nagano": {"id": "nagano", "name_ja": "長野県", "name_en": "Nagano", "search_db": "nagano.search.db", "boundary": "Nagano.geojson", "bbox": (137.32455, 35.19847, 138.73925, 37.03038)},
    "gifu": {"id": "gifu", "name_ja": "岐阜県", "name_en": "Gifu", "search_db": "gifu.search.db", "boundary": "Gifu.geojson", "bbox": (136.27623, 35.13373, 137.65306, 36.46507)},
    "shizuoka": {"id": "shizuoka", "name_ja": "静岡県", "name_en": "Shizuoka", "search_db": "shizuoka.search.db", "boundary": "Shizuoka.geojson", "bbox": (137.47411, 34.57361, 139.17656, 35.64596)},
    "aichi": {"id": "aichi", "name_ja": "愛知県", "name_en": "Aichi", "search_db": "aichi.search.db", "boundary": "Aichi.geojson", "bbox": (136.67105, 34.57829, 137.83812, 35.4242)},
    "mie": {"id": "mie", "name_ja": "三重県", "name_en": "Mie", "search_db": "mie.search.db", "boundary": "Mie.geojson", "bbox": (135.85325, 33.7229, 136.9877, 35.25753)},
    "shiga": {"id": "shiga", "name_ja": "滋賀県", "name_en": "Shiga", "search_db": "shiga.search.db", "boundary": "Shiga.geojson", "bbox": (135.76407, 34.79059, 136.45504, 35.70374)},
    "kyoto": {"id": "kyoto", "name_ja": "京都府", "name_en": "Kyoto", "search_db": "kyoto.search.db", "boundary": "Kyoto.geojson", "bbox": (134.85373, 34.70597, 136.05548, 35.77891)},
    "osaka": {"id": "osaka", "name_ja": "大阪府", "name_en": "Osaka", "search_db": "osaka.search.db", "boundary": "Osaka.geojson", "bbox": (135.09202, 34.27182, 135.74659, 35.05104)},
    "hyogo": {"id": "hyogo", "name_ja": "兵庫県", "name_en": "Hyogo", "search_db": "hyogo.search.db", "boundary": "Hyogo.geojson", "bbox": (134.25274, 34.15569, 135.46859, 35.67471)},
    "nara": {"id": "nara", "name_ja": "奈良県", "name_en": "Nara", "search_db": "nara.search.db", "boundary": "Nara.geojson", "bbox": (135.5397, 33.85896, 136.22976, 34.78133)},
    "wakayama": {"id": "wakayama", "name_ja": "和歌山県", "name_en": "Wakayama", "search_db": "wakayama.search.db", "boundary": "Wakayama.geojson", "bbox": (134.99907, 33.43302, 136.01316, 34.38448)},
    "tottori": {"id": "tottori", "name_ja": "鳥取県", "name_en": "Tottori", "search_db": "tottori.search.db", "boundary": "Tottori.geojson", "bbox": (133.13628, 35.05768, 134.51537, 35.61459)},
    "shimane": {"id": "shimane", "name_ja": "島根県", "name_en": "Shimane", "search_db": "shimane.search.db", "boundary": "Shimane.geojson", "bbox": (131.66797, 34.30244, 133.38599, 37.24426)},
    "okayama": {"id": "okayama", "name_ja": "岡山県", "name_en": "Okayama", "search_db": "okayama.search.db", "boundary": "Okayama.geojson", "bbox": (133.26679, 34.2986, 134.41316, 35.3529)},
    "hiroshima": {"id": "hiroshima", "name_ja": "広島県", "name_en": "Hiroshima", "search_db": "hiroshima.search.db", "boundary": "Hiroshima.geojson", "bbox": (132.0366, 34.03423, 133.47064, 35.10508)},
    "yamaguchi": {"id": "yamaguchi", "name_ja": "山口県", "name_en": "Yamaguchi", "search_db": "yamaguchi.search.db", "boundary": "Yamaguchi.geojson", "bbox": (130.775, 33.71288, 132.49121, 34.79865)},
    "tokushima": {"id": "tokushima", "name_ja": "徳島県", "name_en": "Tokushima", "search_db": "tokushima.search.db", "boundary": "Tokushima.geojson", "bbox": (133.66075, 33.53956, 134.82083, 34.25202)},
    "kagawa": {"id": "kagawa", "name_ja": "香川県", "name_en": "Kagawa", "search_db": "kagawa.search.db", "boundary": "Kagawa.geojson", "bbox": (133.44704, 34.01252, 134.44021, 34.56403)},
    "ehime": {"id": "ehime", "name_ja": "愛媛県", "name_en": "Ehime", "search_db": "ehime.search.db", "boundary": "Ehime.geojson", "bbox": (132.01264, 32.89789, 133.6928, 34.29979)},
    "kochi": {"id": "kochi", "name_ja": "高知県", "name_en": "Kochi", "search_db": "kochi.search.db", "boundary": "Kochi.geojson", "bbox": (132.47997, 32.7025, 134.31253, 33.88336)},
    "fukuoka": {"id": "fukuoka", "name_ja": "福岡県", "name_en": "Fukuoka", "search_db": "fukuoka.search.db", "boundary": "Fukuoka.geojson", "bbox": (130.03268, 33.00002, 131.19057, 34.25)},
    "saga": {"id": "saga", "name_ja": "佐賀県", "name_en": "Saga", "search_db": "saga.search.db", "boundary": "Saga.geojson", "bbox": (129.73971, 32.95052, 130.54196, 33.61899)},
    "nagasaki": {"id": "nagasaki", "name_ja": "長崎県", "name_en": "Nagasaki", "search_db": "nagasaki.search.db", "boundary": "Nagasaki.geojson", "bbox": (128.34402, 31.98641, 130.38194, 34.70802)},
    "kumamoto": {"id": "kumamoto", "name_ja": "熊本県", "name_en": "Kumamoto", "search_db": "kumamoto.search.db", "boundary": "Kumamoto.geojson", "bbox": (129.9636, 32.09492, 131.32951, 33.19518)},
    "oita": {"id": "oita", "name_ja": "大分県", "name_en": "Oita", "search_db": "oita.search.db", "boundary": "Oita.geojson", "bbox": (130.82485, 32.71478, 132.08532, 33.74028)},
    "miyazaki": {"id": "miyazaki", "name_ja": "宮崎県", "name_en": "Miyazaki", "search_db": "miyazaki.search.db", "boundary": "Miyazaki.geojson", "bbox": (130.70338, 31.36088, 131.88571, 32.83898)},
    "kagoshima": {"id": "kagoshima", "name_ja": "鹿児島県", "name_en": "Kagoshima", "search_db": "kagoshima.search.db", "boundary": "Kagoshima.geojson", "bbox": (128.39537, 27.01868, 131.20545, 32.30815)},
    "okinawa": {"id": "okinawa", "name_ja": "沖縄県", "name_en": "Okinawa", "search_db": "okinawa.search.db", "boundary": "Okinawa.geojson", "bbox": (122.93382, 24.04588, 131.33196, 27.88545)},
}

# SPEC 4.2: アプリが前方一致 LIKE で判定するカテゴリプレフィックス (CyclingData.kt PoiCategory と同期)
APP_CATEGORY_PREFIXES = [
    # 交通
    "railway:station",
    "railway:halt",
    "public_transport:station",
    "amenity:bus_station",
    "amenity:ferry_terminal",
    "highway:bus_stop",
    "railway:tram_stop",
    "amenity:parking",
    "amenity:fuel",
    "amenity:charging_station",
    # 自転車
    "shop:bicycle",
    "amenity:bicycle_rental",
    "amenity:bicycle_parking",
    "amenity:bicycle_repair_station",
    # 食・買い物
    "shop:convenience",
    "shop:supermarket",
    "shop:department_store",
    "shop:mall",
    "amenity:restaurant",
    "amenity:cafe",
    "amenity:fast_food",
    "shop:bakery",
    "amenity:bar",
    "amenity:pub",
    # 観光・休憩
    "tourism:",
    "amenity:place_of_worship",
    "historic:",
    "natural:",
    "aeroway:aerodrome",
    "leisure:park",
    "amenity:public_bath",
    "tourism:hotel",
    "tourism:guest_house",
    "tourism:hostel",
    "tourism:motel",
    "tourism:resort",
    "tourism:camp_site",
    "tourism:alpine_hut",
    "tourism:wilderness_hut",
    "tourism:information",
    "leisure:stadium",
    "leisure:sports_centre",
    "leisure:swimming_pool",
    "leisure:golf_course",
    # 生活インフラ
    "amenity:toilets",
    "amenity:drinking_water",
    "amenity:hospital",
    "amenity:clinic",
    "amenity:doctors",
    "amenity:dentist",
    "amenity:pharmacy",
    "shop:chemist",
    "amenity:post_office",
    "amenity:bank",
    "amenity:atm",
    "amenity:school",
    "amenity:college",
    "amenity:university",
    "amenity:townhall",
    "amenity:police",
    "amenity:fire_station",
    "amenity:library",
    "amenity:public_building",
    # 非ピンカテゴリ: 逆ジオコーディング (place:)・フォールバック (named)
    "place:",
    "named",
]

# SPEC 5.4: ソース優先度 (大きいほど優先)
SOURCE_PRIORITY = {
    "manual": 100,
    "kokudo": 50,
    "csv": 40,
    "osm": 30,
    "overture": 20,
}


def source_priority(source):
    """'kokudo:medical' のような接尾辞付きソースにも対応して優先度を返す。"""
    if source in SOURCE_PRIORITY:
        return SOURCE_PRIORITY[source]
    head = (source or "").split(":", 1)[0]
    return SOURCE_PRIORITY.get(head, 0)


def search_db_path(pref_id):
    return os.path.join(OUT_DIR, PREFECTURES[pref_id]["search_db"])


# --- 国土数値情報 (第3段階・SPEC追記) ---

# 県コード (ファイル名 P04-20_<code> / P29-21_<code> 用)
KOKUDO_PREF_CODES = {pref_id: f"{index:02d}" for index, pref_id in enumerate(PREFECTURES, 1)}

KOKUDO_DATASETS = {
    "medical": {
        "source": "kokudo:medical",
        "label": "医療機関",
        "dir": os.path.join(RAW_DIR, "kokudo", "medical"),
        "file_template": "P04-20_{code}_GML/P04-20_{code}.geojson",
        "url": ("https://nlftp.mlit.go.jp/ksj/gml/datalist/"
                "KsjTmplt-P04-v3_0.html"),
        "year": "2020",
        "version": "3.0",
        "license": "PDL1.0",
    },
    "school": {
        "source": "kokudo:school",
        "label": "学校",
        "dir": os.path.join(RAW_DIR, "kokudo", "school"),
        "file_template": "P29-21_{code}_GML/P29-21_{code}.geojson",
        "url": ("https://nlftp.mlit.go.jp/ksj/gml/datalist/"
                "KsjTmplt-P29-v2_0.html"),
        "year": "2021",
        "version": "2.0",
        "license": "PDL1.0",
    },
}

KOKUDO_SOURCES = {d["source"]: d for d in KOKUDO_DATASETS.values()}

# 医療機関分類コード (MedClassCd。1=病院, 2=診療所, 3=歯科診療所)
# 根拠: https://nlftp.mlit.go.jp/ksj/gml/codelist/MedClassCd.html
KOKUDO_MEDICAL_CATEGORY = {
    1: "amenity:hospital",
    2: "amenity:clinic",
    3: "amenity:dentist",
}

# 学校分類コード (SchoolClassCd-v2_0)
# 根拠: https://nlftp.mlit.go.jp/ksj/gml/codelist/SchoolClassCd-v2_0.html
KOKUDO_SCHOOL_CATEGORY = {
    16001: "amenity:school",       # 小学校
    16002: "amenity:school",       # 中学校
    16003: "amenity:school",       # 中等教育学校
    16004: "amenity:school",       # 高等学校
    16005: "amenity:college",      # 高等専門学校
    16006: "amenity:college",      # 短期大学
    16007: "amenity:university",   # 大学
    16011: "amenity:kindergarten",  # 幼稚園
    16012: "amenity:school",       # 特別支援学校
    16013: "amenity:kindergarten",  # 幼保連携型認定こども園 (暫定・承認済み)
    16014: "amenity:school",       # 義務教育学校
    16015: "amenity:school",       # 各種学校 (暫定・承認済み)
    16016: "amenity:college",      # 専修学校 (暫定・承認済み)
}

# 休校コード (ClosedSchoolCode)。2=休校中のみ除外。0=調査なし・9=不明は含める
# 根拠: https://nlftp.mlit.go.jp/ksj/gml/codelist/ClosedSchoolCode.html
KOKUDO_CLOSED_CODE = 2

KOKUDO_LICENSE_LINES = [
    "出典：国土交通省国土数値情報ダウンロードサイト",
    "「国土数値情報（医療機関データ）」（国土交通省）"
    "（https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P04-v3_0.html）"
    "をもとに作成",
    "「国土数値情報（学校データ）」（国土交通省）"
    "（https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P29-v2_0.html）"
    "をもとに作成",
]


def kokudo_geojson_path(dataset, pref_id):
    ds = KOKUDO_DATASETS[dataset]
    return os.path.join(
        ds["dir"],
        ds["file_template"].format(code=KOKUDO_PREF_CODES[pref_id]))


def default_pbf_path(pref_id):
    return os.path.join(RAW_DIR, PREFECTURES[pref_id]["name_en"] + ".osm.pbf")


# --- 県判定 (行政界ポリゴン優先、なければ bbox) ---

_boundary_cache = {}


def load_boundary_rings(pref_id):
    """行政界のポリゴン一覧。各要素は [外周リング, 穴リング, ...]。"""
    if pref_id in _boundary_cache:
        return _boundary_cache[pref_id]
    path = os.path.join(BOUNDARIES_DIR, PREFECTURES[pref_id]["boundary"])
    rings = []
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            geo = json.load(f)
        feats = (
            geo["features"]
            if geo.get("type") == "FeatureCollection"
            else [geo]
        )
        for feat in feats:
            geom = feat["geometry"]
            if geom["type"] == "Polygon":
                rings.append([[(x, y) for x, y in ring] for ring in geom["coordinates"]])
            elif geom["type"] == "MultiPolygon":
                for poly in geom["coordinates"]:
                    rings.append([[(x, y) for x, y in ring] for ring in poly])
    _boundary_cache[pref_id] = rings
    return rings


def point_in_ring(lon, lat, ring):
    """レイキャスティング法による点-in-リング判定。"""
    inside = False
    n = len(ring)
    for i in range(n):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % n]
        if (y1 > lat) != (y2 > lat):
            xinters = (x2 - x1) * (lat - y1) / (y2 - y1) + x1
            if lon < xinters:
                inside = not inside
    return inside


def ring_distance_m(lon, lat, ring):
    """点からリング線分までの最短距離。近距離用の局所正距円筒近似。"""
    sx = math.pi / 180 * 6371000 * math.cos(math.radians(lat))
    sy = math.pi / 180 * 6371000
    best = float("inf")
    for i in range(len(ring)):
        a, b = ring[i - 1], ring[i]
        ax, ay = (a[0] - lon) * sx, (a[1] - lat) * sy
        bx, by = (b[0] - lon) * sx, (b[1] - lat) * sy
        dx, dy = bx - ax, by - ay
        length2 = dx * dx + dy * dy
        t = max(0.0, min(1.0, -(ax * dx + ay * dy) / length2)) if length2 else 0.0
        best = min(best, math.hypot(ax + t * dx, ay + t * dy))
    return best


def prefecture_for_point(lat, lon):
    """行政界の内側、または最も近い行政界から5km以内の県。その他は None。"""
    if not math.isfinite(lat) or not math.isfinite(lon):
        return None
    max_distance = 5000.0
    margin_lat = math.degrees(max_distance / 6371000)
    margin_lon = margin_lat / max(0.01, math.cos(math.radians(lat)))
    cands = []
    for pref_id, pref in PREFECTURES.items():
        lon_min, lat_min, lon_max, lat_max = pref["bbox"]
        if (lon_min - margin_lon <= lon <= lon_max + margin_lon
                and lat_min - margin_lat <= lat <= lat_max + margin_lat):
            cands.append(pref_id)
    for pref_id in cands:
        for poly in load_boundary_rings(pref_id):
            if (poly and point_in_ring(lon, lat, poly[0])
                    and not any(point_in_ring(lon, lat, hole) for hole in poly[1:])):
                return pref_id
    nearest, best = None, max_distance
    for pref_id in cands:
        for poly in load_boundary_rings(pref_id):
            for ring in poly:
                distance = ring_distance_m(lon, lat, ring)
                if distance <= best:
                    nearest, best = pref_id, distance
    return nearest
