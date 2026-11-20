import sqlite3

from conftest import sample_rows
from tools.core import export as export_mod
from tools.core import masterdb
from tools.core import validate as validate_mod


def test_export_validate_app_queries(tmp_path, tmp_db):
    conn = masterdb.connect(tmp_db)
    try:
        masterdb.insert_pois(conn, sample_rows())
    finally:
        conn.close()
    out = str(tmp_path / "hiroshima.search.db")
    summary = export_mod.export_search_db(tmp_db, "hiroshima", out)
    assert summary["row_count"] == 5

    result = validate_mod.validate_search_db(out)
    for r in result["results"]:
        assert r["ok"], (r["id"], r["name"], r["detail"])
    assert result["passed"]

    # アプリと同じクエリ (SPEC 3.3 の a〜e) が実行できること
    conn = sqlite3.connect("file:%s?mode=ro" % out, uri=True)
    try:
        rows = conn.execute(
            validate_mod.APP_QUERIES["fts"], ("広島*", 10)).fetchall()
        assert len(rows) >= 1
        assert len(rows[0]) == 4  # name, category, lat, lon の順
        conn.execute(validate_mod.APP_QUERIES["like"],
                     ("%広島%", 10)).fetchall()
        conn.execute(validate_mod.APP_QUERIES["category_near"],
                     ("shop:%", 34.0, 35.0, 132.0, 133.0,
                      34.39, 34.39, 132.46, 132.46, 10)).fetchall()
        conn.execute(validate_mod.APP_QUERIES["around"],
                     (34.0, 35.0, 132.0, 133.0, "shop:convenience%",
                      34.39, 34.39, 132.46, 132.46, 10)).fetchall()
        rev = conn.execute(validate_mod.APP_QUERIES["revgeo"],
                           (34.0, 35.0, 132.0, 133.0, 10)).fetchall()
        assert len(rev) == 1 and rev[0][1] == "place:city"
    finally:
        conn.close()


def test_export_exclude_named(tmp_path, tmp_db):
    conn = masterdb.connect(tmp_db)
    try:
        masterdb.insert_pois(conn, sample_rows())
    finally:
        conn.close()
    out = str(tmp_path / "h.search.db")
    summary = export_mod.export_search_db(tmp_db, "hiroshima", out,
                                          include_named=False)
    assert summary["row_count"] == 4
    assert summary["named_excluded"] == 1
    result = validate_mod.validate_search_db(out)
    assert result["passed"]


def test_export_creates_search_text_index_and_handles_bare_filename(
        tmp_path, tmp_db, monkeypatch):
    conn = masterdb.connect(tmp_db)
    try:
        masterdb.insert_pois(conn, sample_rows())
    finally:
        conn.close()
    monkeypatch.chdir(tmp_path)
    summary = export_mod.export_search_db(tmp_db, "hiroshima", "bare.search.db")
    assert summary["row_count"] == 5
    out = tmp_path / "bare.search.db"
    assert out.exists()
    assert not (tmp_path / "bare.search.db.tmp").exists()

    with sqlite3.connect(out) as c:
        indexes = {
            r[0] for r in c.execute(
                "SELECT name FROM sqlite_master WHERE type='index'"
            )
        }
    assert "idx_places_search_text" in indexes
    result = validate_mod.validate_search_db(str(out))
    assert result["passed"]
    assert any(r["id"] == 13 and r["ok"] for r in result["results"])


def test_validate_rejects_missing_search_text_index(tmp_path, tmp_db):
    conn = masterdb.connect(tmp_db)
    try:
        masterdb.insert_pois(conn, sample_rows())
    finally:
        conn.close()
    out = tmp_path / "no_idx.search.db"
    export_mod.export_search_db(tmp_db, "hiroshima", str(out))
    with sqlite3.connect(out) as c:
        c.execute("DROP INDEX idx_places_search_text")
        c.commit()

    result = validate_mod.validate_search_db(str(out))
    assert not result["passed"]
    check3 = next(r for r in result["results"] if r["id"] == 3)
    assert not check3["ok"]


def test_validate_rejects_corrupted_and_empty_db(tmp_path, tmp_db):
    garbage = tmp_path / "corrupt.search.db"
    garbage.write_bytes(b"not a sqlite database at all" * 10)
    res_garbage = validate_mod.validate_search_db(str(garbage))
    assert not res_garbage["passed"]
    assert any(not r["ok"] for r in res_garbage["results"])

    # Valid SQLite DB truncated / corrupted so PRAGMA integrity_check fails
    conn = masterdb.connect(tmp_db)
    try:
        masterdb.insert_pois(conn, sample_rows())
    finally:
        conn.close()
    damaged = tmp_path / "damaged.search.db"
    export_mod.export_search_db(tmp_db, "hiroshima", str(damaged))
    raw = damaged.read_bytes()
    damaged.write_bytes(raw[:-4096])
    res_damaged = validate_mod.validate_search_db(str(damaged))
    assert not res_damaged["passed"]
    check13 = next(r for r in res_damaged["results"] if r["id"] == 13)
    assert not check13["ok"]

    empty_sqlite = tmp_path / "empty.search.db"
    with sqlite3.connect(empty_sqlite) as c:
        c.execute("CREATE TABLE unrelated(x INTEGER)")
    res_empty = validate_mod.validate_search_db(str(empty_sqlite))
    assert not res_empty["passed"]
