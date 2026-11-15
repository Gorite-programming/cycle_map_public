import sqlite3

from tools.backfill_details import backfill_db, details_from_tags


def _make_db(path, with_details=True):
    conn = sqlite3.connect(path)
    extra = ", opening_hours TEXT, phone TEXT, website TEXT, wheelchair TEXT" if with_details else ""
    conn.execute(
        "CREATE TABLE places (id INTEGER PRIMARY KEY, osm_type TEXT, osm_id INTEGER, name TEXT,"
        " source TEXT%s)" % extra)
    conn.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
    conn.executemany(
        "INSERT INTO places (id, osm_type, osm_id, name, source) VALUES (?, ?, ?, ?, ?)",
        [
            (1, "node", 10, "A", "osm"),
            (2, "way", 10, "B", "osm"),          # 同じ番号でも way は別物
            (3, "ext", 10, "C", "overture:places"),  # OSM 以外は触らない
            (4, "node", 99, "D", "osm"),         # 詳細なし
        ])
    conn.commit()
    conn.close()


def test_details_from_tags_uses_same_priority_as_import():
    d = details_from_tags({"opening_hours": "Mo-Fr 09:00-18:00", "contact:phone": "082-208-2822",
                           "url": "example.com", "wheelchair": "limited"})
    assert d[0] == "Mo-Fr 09:00-18:00"
    assert d[1] == "082-208-2822"
    assert d[2] == "http://example.com"
    assert d[3] == "limited"


def test_details_from_tags_returns_none_when_nothing_usable():
    assert details_from_tags({"wheelchair": "maybe"}) is None
    assert details_from_tags({}) is None


def test_backfill_updates_only_matching_osm_rows(tmp_path):
    db = tmp_path / "x.search.db"
    _make_db(db)
    details = {
        ("node", 10): ("24/7", None, None, "yes"),
        ("way", 10): (None, "082-111-2222", None, None),
    }
    r = backfill_db(str(db), details)
    assert r["matched"] == 2 and r["osm_rows"] == 3
    conn = sqlite3.connect(db)
    got = {row[0]: row[1:] for row in conn.execute(
        "SELECT id, opening_hours, phone, website, wheelchair FROM places")}
    assert got[1] == ("24/7", None, None, "yes")
    assert got[2] == (None, "082-111-2222", None, None)
    assert got[3] == (None, None, None, None)
    assert got[4] == (None, None, None, None)
    assert conn.execute("SELECT value FROM meta WHERE key='details_backfilled_at'").fetchone()


def test_backfill_dry_run_does_not_write(tmp_path):
    db = tmp_path / "x.search.db"
    _make_db(db)
    r = backfill_db(str(db), {("node", 10): ("24/7", None, None, None)}, dry_run=True)
    assert r["matched"] == 1
    conn = sqlite3.connect(db)
    assert conn.execute("SELECT opening_hours FROM places WHERE id=1").fetchone()[0] is None


def test_backfill_skips_old_schema_without_detail_columns(tmp_path):
    db = tmp_path / "old.search.db"
    _make_db(db, with_details=False)
    r = backfill_db(str(db), {("node", 10): ("24/7", None, None, None)})
    assert r["matched"] is None


def test_backfill_adds_columns_to_old_schema_when_asked(tmp_path):
    db = tmp_path / "old.search.db"
    _make_db(db, with_details=False)
    sqlite3.connect(db).execute("INSERT INTO meta VALUES ('schema_version', '1')").connection.commit()
    r = backfill_db(str(db), {("node", 10): ("24/7", None, None, None)}, add_columns=True)
    assert r["added_columns"] is True and r["matched"] == 1
    conn = sqlite3.connect(db)
    assert conn.execute("SELECT opening_hours FROM places WHERE id=1").fetchone()[0] == "24/7"
    assert conn.execute("SELECT value FROM meta WHERE key='schema_version'").fetchone()[0] == "2"


def test_cache_round_trip(tmp_path):
    from tools.backfill_details import load_cache, save_cache
    details = {("node", 1): ("24/7", None, "https://a.example", "yes"), ("way", 2): (None, "082-1", None, None)}
    path = tmp_path / "c.json"
    save_cache(str(path), details)
    assert load_cache(str(path)) == details
