"""連絡先・営業情報の正規化 (core/contact.py) と、取り込み〜検索DBまでの受け渡し。"""
import sqlite3
from types import SimpleNamespace

import pytest

from tools.core import export, masterdb, merge as mg
from tools.core.contact import (normalize_hours, normalize_phone,
                                normalize_website, normalize_wheelchair)
from tools.core.sources.osm import _PoiHandler


@pytest.mark.parametrize("raw, expected", [
    ("082-208-2822", "082-208-2822"),
    ("+81 82-208-2822", "082-208-2822"),
    ("+81822082822", "082-208-2822"),            # Overture の E.164
    ("０８２－２０８－２８２２", "082-208-2822"),   # 全角
    ("(082)208-2822", "082-208-2822"),
    ("TEL: 082-208-2822", "082-208-2822"),
    ("082-208-2822; 082-208-2823", "082-208-2822"),  # 複数なら先頭
    ("0120-123-456", "0120-123-456"),
    ("090-1234-5678", "090-1234-5678"),
    ("+1 650-253-0000", "+1 650-253-0000"),          # 海外は国際表記
])
def test_normalize_phone_accepts_common_forms(raw, expected):
    assert normalize_phone(raw) == expected


@pytest.mark.parametrize("raw", [None, "", "   ", "abc", "12", "電話番号なし", "0"])
def test_normalize_phone_rejects_garbage(raw):
    assert normalize_phone(raw) is None


@pytest.mark.parametrize("raw, expected", [
    ("https://example.com/", "https://example.com/"),
    ("http://www.honkawa-youchien.jp", "http://www.honkawa-youchien.jp"),
    ("example.co.jp/shop", "http://example.co.jp/shop"),   # スキーム補完
    ("ＨＴＴＰＳ://example.com", "https://example.com"),    # 全角
])
def test_normalize_website_accepts_urls(raw, expected):
    assert normalize_website(raw) == expected


@pytest.mark.parametrize("raw", [None, "", "not a url", "ftp://example.com", "javascript:alert(1)",
                                 "http://", "localhost", "x" * 400])
def test_normalize_website_rejects_non_http(raw):
    assert normalize_website(raw) is None


def test_normalize_hours_collapses_whitespace_and_limits_length():
    assert normalize_hours("Mo-Fr  09:00-17:00;\n Sa 09:00-12:00") == "Mo-Fr 09:00-17:00; Sa 09:00-12:00"
    assert normalize_hours("24/7") == "24/7"
    assert normalize_hours("") is None
    assert normalize_hours("x" * 500) is None


def test_normalize_wheelchair():
    assert normalize_wheelchair("yes") == "yes"
    assert normalize_wheelchair("designated") == "yes"
    assert normalize_wheelchair("Limited") == "limited"
    assert normalize_wheelchair("no") == "no"
    assert normalize_wheelchair("maybe") is None


def _node(nid, tags):
    return SimpleNamespace(visible=True, id=nid, tags=tags,
                           location=SimpleNamespace(lat=34.0, lon=132.0))


def test_osm_handler_extracts_detail_tags():
    h = _PoiHandler()
    h.node(_node(1, {"name": "店", "shop": "convenience", "opening_hours": "24/7",
                     "contact:phone": "+81 82-208-2822", "contact:website": "https://example.com",
                     "wheelchair": "yes"}))
    h.node(_node(2, {"name": "無情報", "shop": "convenience"}))
    detailed, bare = h.rows
    assert (detailed["hours"], detailed["phone"], detailed["website"], detailed["wheelchair"]) == \
        ("24/7", "082-208-2822", "https://example.com", "yes")
    assert (bare["hours"], bare["phone"], bare["website"], bare["wheelchair"]) == (None, None, None, None)


def test_old_master_db_is_migrated_with_detail_columns(tmp_path):
    path = str(tmp_path / "old.db")
    conn = sqlite3.connect(path)
    conn.execute("""CREATE TABLE poi (uid INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
        category TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, address TEXT, prefecture TEXT,
        source TEXT NOT NULL, source_id TEXT, priority INTEGER NOT NULL, imported_at TEXT NOT NULL,
        merged_into INTEGER, kana TEXT)""")
    conn.execute("INSERT INTO poi(name,category,lat,lon,source,priority,imported_at) VALUES('旧','x',1,1,'osm',10,'t')")
    conn.commit(); conn.close()
    masterdb.init_db(path)
    conn = masterdb.connect(path)
    try:
        cols = {r[1] for r in conn.execute("PRAGMA table_info(poi)")}
        assert set(masterdb.DETAIL_COLUMNS) <= cols
        assert conn.execute("SELECT name, hours FROM poi").fetchone()[:] == ("旧", None)  # 既存データは無傷
    finally:
        conn.close()


def _row(name, source, source_id, priority, **extra):
    row = {"name": name, "category": "shop:convenience", "lat": 34.39, "lon": 132.46, "address": None,
           "prefecture": "hiroshima", "source": source, "source_id": source_id, "priority": priority}
    row.update(extra)
    return row


def test_merge_fills_empty_detail_fields_from_the_loser(tmp_db):
    conn = masterdb.connect(tmp_db)
    try:
        masterdb.insert_pois(conn, [
            _row("ローソン井口店", "osm", "node/1", 30, hours="24/7"),
            _row("ローソン井口店", "overture:places", "ov1", 20, phone="082-208-2822",
                 website="https://lawson.example", hours="Mo-Su 09:00-18:00"),
        ])
        winner_uid, loser_uid = 1, 2
        merged, _ = mg.apply_merge(conn, winner_uid, loser_uid)
        assert merged["hours"] == "24/7"                  # 勝者の値を優先
        assert merged["phone"] == "082-208-2822"           # 空だった項目は敗者から補完
        assert merged["website"] == "https://lawson.example"
    finally:
        conn.close()


def test_export_writes_detail_columns_and_counts(tmp_db, tmp_path):
    conn = masterdb.connect(tmp_db)
    try:
        masterdb.insert_pois(conn, [
            _row("詳細あり", "osm", "node/1", 30, hours="24/7", phone="082-208-2822",
                 website="https://example.com", wheelchair="yes"),
            _row("詳細なし", "osm", "node/2", 30),
        ])
    finally:
        conn.close()
    out = str(tmp_path / "x.search.db")
    summary = export.export_search_db(tmp_db, "hiroshima", out)
    assert summary["detail_rows"] == {"hours": 1, "phone": 1, "website": 1, "wheelchair": 1}
    db = sqlite3.connect("file:%s?mode=ro" % out, uri=True)
    try:
        rows = {r[0]: r[1:] for r in db.execute(
            "SELECT name, opening_hours, phone, website, wheelchair FROM places")}
        assert rows["詳細あり"] == ("24/7", "082-208-2822", "https://example.com", "yes")
        assert rows["詳細なし"] == (None, None, None, None)
        assert dict(db.execute("SELECT key, value FROM meta"))["schema_version"] == "2"
    finally:
        db.close()


def test_export_adds_search_only_reading_for_kanji_names(tmp_db, tmp_path):
    """漢字名は、かな・ローマ字で探せるよう search_text に読みを足す。表示用の kana 列には入れない (誤読があるため)。"""
    pytest.importorskip("pykakasi")
    conn = masterdb.connect(tmp_db)
    try:
        masterdb.insert_pois(conn, [_row("広島大学", "osm", "node/1", 30)])
    finally:
        conn.close()
    out = str(tmp_path / "y.search.db")
    export.export_search_db(tmp_db, "hiroshima", out)
    db = sqlite3.connect("file:%s?mode=ro" % out, uri=True)
    try:
        kana, search_text = db.execute("SELECT kana, search_text FROM places").fetchone()
        assert kana is None
        assert "ひろしまだいがく" in search_text
        assert "hiroshimadaigaku" in search_text
    finally:
        db.close()


def test_export_drops_overture_stations_far_from_the_osm_station():
    """Overture の「広島駅」(海田町など、名前と位置が合わない駅) を除き、OSM に無い駅は残す。"""
    rows = [
        {"name": "広島", "category": "railway:station", "lat": 34.3974, "lon": 132.4758, "source": "osm"},
        {"name": "広島駅", "category": "railway:station", "lat": 34.3815, "lon": 132.5645, "source": "overture:places"},
        {"name": "西広島駅", "category": "railway:station", "lat": 34.37, "lon": 132.42, "source": "overture:places"},
        {"name": "広島駅", "category": "highway:bus_stop", "lat": 34.30, "lon": 132.30, "source": "overture:places"},
    ]
    kept, dropped = export.drop_mislocated_overture_stations(rows)
    assert dropped == 1
    assert [r["lat"] for r in kept if r["name"] == "広島駅"] == [34.30]  # バス停は対象外
    assert any(r["name"] == "西広島駅" for r in kept)                   # OSM に同名の駅が無ければ残す


def test_export_drops_overture_stations_next_to_an_osm_station():
    """OSM の駅のそばにある Overture の駅 (重複・出入口・別の駅名) は、名前を問わず除く。"""
    rows = [
        {"name": "大阪", "category": "railway:station", "lat": 34.7022, "lon": 135.4956, "source": "osm"},
        {"name": "新大阪駅", "category": "railway:station", "lat": 34.7021, "lon": 135.4957, "source": "overture:places"},
        {"name": "品川駅", "category": "railway:station", "lat": 34.7025, "lon": 135.4974, "source": "overture:places"},
        {"name": "Bahnhof Ōsaka", "category": "railway:station", "lat": 34.7019, "lon": 135.4950, "source": "overture:places"},
        {"name": "ＪＲ大阪駅", "category": "railway:station", "lat": 34.7025, "lon": 135.4961, "source": "overture:places"},
        {"name": "遠くの駅", "category": "railway:station", "lat": 34.80, "lon": 135.60, "source": "overture:places"},
        {"name": "大阪駅前", "category": "highway:bus_stop", "lat": 34.7020, "lon": 135.4957, "source": "overture:places"},
    ]
    kept, dropped = export.drop_mislocated_overture_stations(rows)
    assert dropped == 4
    assert sorted(r["name"] for r in kept) == ["大阪", "大阪駅前", "遠くの駅"]
