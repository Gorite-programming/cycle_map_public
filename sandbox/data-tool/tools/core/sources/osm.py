"""OSM PBF → 共通スキーマ (SPEC 6.1)。

- category は現行と同じ規則 (place > railway=station > amenity > shop >
  tourism > leisure > highway=bus_stop > public_transport > named)。
- name が空の要素は取り込まない。
- Way の座標はノード座標の平均 (現行同様。改善しない)。
- addr:* タグがあれば address を組み立てる (任意)。
- opening_hours / phone / website / wheelchair があれば取り込む (contact.py で正規化)。
"""

import osmium

from .. import config, masterdb
from .. import overlay as overlay_mod
from ..contact import (normalize_hours, normalize_phone, normalize_website,
                       normalize_wheelchair)
from ..kana import tag_reading


def _as_dict(tags):
    if isinstance(tags, dict):
        return tags
    return {t.k: t.v for t in tags}


def category_for(tags):
    """現行 category_for と同じ規則でカテゴリを決める。"""
    d = _as_dict(tags)
    v = d.get("place")
    if v:
        return "place:%s" % v
    if d.get("railway") == "station":
        return "railway:station"
    v = d.get("amenity")
    if v:
        return "amenity:%s" % v
    v = d.get("shop")
    if v:
        return "shop:%s" % v
    v = d.get("tourism")
    if v:
        return "tourism:%s" % v
    v = d.get("leisure")
    if v:
        return "leisure:%s" % v
    # ここから下は、amenity/shop/tourism/leisure を持たない名所・史跡・寺社・自然・駅。
    # 例: 厳島神社 (way) は heritage・landuse=religious・building=shrine だけで、以前は "named" に落ち、
    # 地図にも出ず検索でも種類不明になっていた。
    if d.get("heritage") or d.get("heritage:operator"):
        return "tourism:attraction"
    v = d.get("historic")
    if v and v not in ("yes", "no"):
        return "historic:%s" % v
    if (d.get("building") in _WORSHIP_BUILDINGS or d.get("landuse") == "religious"
            or (d.get("religion") and d.get("building"))):
        return "amenity:place_of_worship"
    v = d.get("natural")
    if v in _NATURAL_LANDMARKS:
        return "natural:%s" % v
    v = d.get("railway")
    if v in ("halt", "tram_stop"):
        return "railway:%s" % v
    if d.get("aeroway") == "aerodrome":
        return "aeroway:aerodrome"
    if d.get("highway") == "bus_stop":
        return "highway:bus_stop"
    v = d.get("public_transport")
    if v:
        return "public_transport:%s" % v
    if d.get("historic") == "yes":
        return "historic:yes"
    return "named"


_WORSHIP_BUILDINGS = {"shrine", "temple", "church", "cathedral", "chapel", "mosque", "synagogue"}
_NATURAL_LANDMARKS = {"peak", "volcano", "waterfall", "cape", "beach", "bay", "spring", "cave_entrance", "hot_spring"}


def address_from_tags(tags):
    """addr:* から住所文字列を組み立てる。取れなければ None。"""
    d = _as_dict(tags)
    full = (d.get("addr:full") or "").strip()
    if full:
        return full
    body = "".join([
        d.get("addr:prefecture") or "",
        d.get("addr:city") or "",
        d.get("addr:ward") or "",
        d.get("addr:suburb") or "",
        d.get("addr:quarter") or "",
        d.get("addr:neighbourhood") or "",
        d.get("addr:street") or "",
        d.get("addr:block_number") or "",
        d.get("addr:housenumber") or "",
    ]).strip()
    postcode = (d.get("addr:postcode") or "").strip()
    if postcode and body:
        return postcode + " " + body
    return body or postcode or None


class _PoiHandler(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.rows = []

    def _push(self, osm_type, osm_id, tags, lat, lon):
        d = _as_dict(tags)
        name = (d.get("name") or "").strip()
        if not name:
            return
        self.rows.append({
            "name": name,
            "category": category_for(d),
            "lat": lat,
            "lon": lon,
            "address": address_from_tags(d),
            "prefecture": None,  # 取り込み時に県判定する
            "source": "osm",
            "source_id": "%s/%s" % (osm_type, osm_id),
            "priority": config.source_priority("osm"),
            # 読みタグ (name:ja-Hira 優先、なければ name:ja_rm)。無ければ None
            "kana": tag_reading(d),
            # 詳細情報 (営業時間・電話・Web・車椅子)。無い・解釈できないものは None
            "hours": normalize_hours(d.get("opening_hours")),
            "phone": normalize_phone(
                d.get("phone") or d.get("contact:phone") or d.get("contact:mobile")),
            "website": normalize_website(
                d.get("website") or d.get("contact:website") or d.get("url")),
            "wheelchair": normalize_wheelchair(d.get("wheelchair")),
        })

    def node(self, n):
        if not n.visible:
            return
        try:
            lat, lon = n.location.lat, n.location.lon
        except Exception:
            return
        self._push("node", n.id, n.tags, lat, lon)

    def way(self, w):
        if not w.visible:
            return
        lats, lons = [], []
        for nd in w.nodes:
            try:
                loc = nd.location
                lats.append(loc.lat)
                lons.append(loc.lon)
            except Exception:
                continue
        if not lats:
            return
        self._push("way", w.id, w.tags,
                   sum(lats) / len(lats), sum(lons) / len(lons))


def parse_pbf(pbf_path):
    """PBF をパースして共通スキーマ行リストを返す (DBに触らない)。"""
    handler = _PoiHandler()
    # 位置キャッシュはフィルターより先に処理されるため、way の座標を
    # 保ったまま、Python コールバックは name タグのある要素だけに絞れる。
    handler.apply_file(
        pbf_path,
        locations=True,
        filters=[osmium.filter.KeyFilter("name")],
    )
    return handler.rows


def import_osm(pbf_path, prefecture, db_path=None):
    """PBF を取り込み、(prefecture, 'osm') の poi を入れ替える。

    県判定は座標→行政界ポリゴン (なければ bbox) で行う (SPEC 7)。
    どの県の行政界からも遠い点 (prefecture_for_point が None: 行政界から 5km 超) は取り込まない。
    例: 千葉に陸揚げされた海底ケーブル (way) の代表点が南シナ海になり、検証の範囲外チェックで県全体が失敗した (2026-11-19)。
    overlay は残る。戻り値は件数サマリ。
    """
    db_path = db_path or config.MASTER_DB_PATH
    masterdb.init_db(db_path)
    overlay_mod.ensure_schema(db_path)

    rows = parse_pbf(pbf_path)
    n_named = sum(1 for r in rows if r["category"] == "named")
    assigned, fallback = [], 0
    for r in rows:
        hit = config.prefecture_for_point(r["lat"], r["lon"])
        if hit is None:
            fallback += 1  # 県外の遠い点は捨てる (件数は fallback_to_target ではなく outside_dropped で報告)
            continue
        r["prefecture"] = hit
        assigned.append(r)

    from datetime import datetime, timezone
    stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
    target_rows = [r for r in assigned if r["prefecture"] == prefecture]
    for r in target_rows:
        r["imported_at"] = stamp

    conn = masterdb.connect(db_path)
    try:
        deleted, inserted = masterdb.replace_source(
            conn, prefecture, "osm", target_rows)
    finally:
        conn.close()
    return {
        "parsed": len(rows),
        "named": n_named,
        "assigned_to_target": len(target_rows),
        "assigned_elsewhere": len(assigned) - len(target_rows),
        "outside_dropped": fallback,
        "deleted": deleted,
        "inserted": inserted,
    }
