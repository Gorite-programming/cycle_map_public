"""Search pipeline regressions: merge safety, source refresh and boundary assignment."""
import json
import sqlite3

import pytest

from tools.core import config, masterdb, merge, validate, export


def poi(source="overture", **kw):
    return dict(name="ローソン", category="shop:convenience", lat=34.39,
                lon=132.46, prefecture="hiroshima", source=source,
                address="広島県広島市中区本町1-1", **kw)


@pytest.mark.parametrize("address,lat,lon,category", [
    ("広島県福山市本町1-1", 34.49, 133.36, "shop:convenience"),
    ("広島県広島市中区本町1-1", 34.49, 133.36, "shop:convenience"),
    ("広島県広島市中区本町1-1", 34.3901, 132.4601, "amenity:hospital"),
    ("広島県福山市本町1-1", 34.3901, 132.4601, "shop:convenience"),
])
def test_address_merge_requires_city_distance_and_category(tmp_db, address, lat, lon, category):
    with masterdb.connect(tmp_db) as conn:
        other = poi()
        other.update(address=address, lat=lat, lon=lon, category=category)
        masterdb.insert_pois(conn, [poi(), other])
        assert merge.merge_all(conn, "hiroshima") == 0
        assert masterdb.count_total(conn) == 2


@pytest.mark.parametrize("pref_name,city", [("広島県", "広島市中区"), ("三重県", "津市"),
                                         ("京都府", "京都市中京区"), ("北海道", "札幌市中央区")])
def test_address_variants_still_merge(tmp_db, pref_name, city):
    with masterdb.connect(tmp_db) as conn:
        other = poi()
        other.update(address=city + "本町１丁目１番", lat=34.392)
        masterdb.insert_pois(conn, [dict(poi(), address=pref_name + city + "本町1-1"), other])
        assert merge.merge_all(conn, "hiroshima") == 1


def test_address_group_keeps_independent_nearby_clusters(tmp_db):
    with masterdb.connect(tmp_db) as conn:
        masterdb.insert_pois(conn, [poi(), dict(poi(), lat=34.49), dict(poi(), lat=34.491)])
        assert merge.merge_all(conn, "hiroshima") == 1
        assert masterdb.count_total(conn) == 2


def test_refresh_deleted_winner_releases_surviving_source(tmp_db):
    with masterdb.connect(tmp_db) as conn:
        k = poi("kokudo:medical")
        k.update(name="中央病院", category="amenity:hospital")
        o = dict(k, source="osm")
        masterdb.insert_pois(conn, [k, o])
        assert merge.merge_all(conn, "hiroshima") == 1
        masterdb.replace_source(conn, "hiroshima", "kokudo:medical", [])
        merge.merge_all(conn, "hiroshima")
        assert [p["source"] for p in masterdb.query_pois(conn)] == ["osm"]


def test_refresh_recomputes_supplemented_fields_and_merges(tmp_db):
    with masterdb.connect(tmp_db) as conn:
        original = poi("osm")
        original.update(address=None, phone=None)
        supplement = dict(poi(), phone="old-phone")
        masterdb.insert_pois(conn, [original, supplement])
        assert merge.merge_all(conn, "hiroshima") == 1
        assert masterdb.query_pois(conn)[0]["phone"] == "old-phone"
        masterdb.replace_source(conn, "hiroshima", "overture", [dict(supplement, phone="new-phone")])
        active = masterdb.query_pois(conn)
        assert len(active) == 1
        assert active[0]["phone"] == "new-phone"
        masterdb.replace_source(conn, "hiroshima", "overture", [])
        active = masterdb.query_pois(conn)
        assert len(active) == 1
        assert active[0]["phone"] is None
        assert active[0]["address"] is None


def test_failed_source_refresh_keeps_previous_data_and_merges(tmp_db):
    with masterdb.connect(tmp_db) as conn:
        masterdb.insert_pois(conn, [poi("osm"), poi()])
        merge.merge_all(conn, "hiroshima")
        before = masterdb.query_pois(conn, include_merged=True)
        with pytest.raises(sqlite3.IntegrityError):
            masterdb.replace_source(conn, "hiroshima", "overture", [dict(poi(), name=None)])
        assert masterdb.query_pois(conn, include_merged=True) == before


def square(x, y, size):
    return [[x, y], [x+size, y], [x+size, y+size], [x, y+size], [x, y]]


def test_boundary_holes_islands_nearest_and_missing(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "BOUNDARIES_DIR", str(tmp_path))
    monkeypatch.setattr(config, "_boundary_cache", {})
    prefs = {
        "first": {"bbox": (130, 30, 134, 34), "boundary": "first.json"},
        "second": {"bbox": (130, 30, 134, 34), "boundary": "second.json"},
    }
    monkeypatch.setattr(config, "PREFECTURES", prefs)
    for name, coordinates in [
        ("first", [[square(130, 30, 2), square(130.5, 30.5, 1)], [square(133, 33, .1)]]),
        ("second", [[square(130.5, 30.5, 1)]]),
    ]:
        (tmp_path / (name + ".json")).write_text(json.dumps({"type": "Feature", "geometry": {
            "type": "MultiPolygon", "coordinates": coordinates}}), encoding="utf-8")
    assert config.prefecture_for_point(31, 131) == "second"  # first's hole
    assert config.prefecture_for_point(30.2, 130.2) == "first"
    assert config.prefecture_for_point(33.05, 133.05) == "first"  # island
    assert config.prefecture_for_point(33.05, 133.12) == "first"  # nearest island
    assert config.prefecture_for_point(32.5, 132.5) is None
    assert config.prefecture_for_point(29.99, 130.2) == "first"  # outside bbox, <5km
    (tmp_path / "second.json").unlink()
    config._boundary_cache.clear()
    assert config.prefecture_for_point(31, 131) is None  # deep inside hole


@pytest.mark.parametrize("lat,lon,expected", [
    (34.60, 138.95, "shizuoka"), (35.15, 139.40, None),
    (34.75, 139.35, "tokyo"), (35.68, 139.76, "tokyo"),
    (26.21, 127.68, "okinawa"),
])
def test_real_coast_city_and_island_boundaries(lat, lon, expected):
    if not all(config.load_boundary_rings(pref) for pref in ("tokyo", "kanagawa", "shizuoka", "okinawa")):
        pytest.skip("local boundary data unavailable")
    assert config.prefecture_for_point(lat, lon) == expected


def test_readonly_uri_special_path(tmp_path):
    directory = tmp_path / "search # data"
    directory.mkdir()
    path = directory / "sample.db"
    with sqlite3.connect(path) as conn:
        conn.execute("CREATE TABLE meta (key TEXT, value TEXT)")
        conn.execute("INSERT INTO meta VALUES ('test', 'ok')")
    with validate._connect_ro(str(path)) as conn:
        assert conn.execute("SELECT value FROM meta").fetchone()[0] == "ok"
        with pytest.raises(sqlite3.OperationalError, match="readonly"):
            conn.execute("DELETE FROM meta")
    assert export.read_meta(str(path)) == {"test": "ok"}
