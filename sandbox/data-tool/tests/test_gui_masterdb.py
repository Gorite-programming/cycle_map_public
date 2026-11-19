"""gui.py の master.db 状態判定・0バイト/破損時の安全な挙動のテスト。"""
import sqlite3

from tools.core import masterdb
from tools import gui


def test_check_master_db_handles_missing_zero_byte_and_corrupt(tmp_path):
    missing = tmp_path / "missing.db"
    status, msg = gui.check_master_db(str(missing))
    assert status == "missing"
    assert msg
    assert gui._master_stats("hiroshima", db_path=str(missing)) == {
        "count": 0, "last_import": "-", "by_source": []
    }

    zero = tmp_path / "zero.db"
    zero.write_bytes(b"")
    status, msg = gui.check_master_db(str(zero))
    assert status == "empty"
    assert msg
    assert gui._master_stats("hiroshima", db_path=str(zero)) == {
        "count": 0, "last_import": "-", "by_source": []
    }

    corrupt = tmp_path / "corrupt.db"
    corrupt.write_bytes(b"not a sqlite database at all")
    status, msg = gui.check_master_db(str(corrupt))
    assert status == "corrupt"
    assert msg
    assert gui._master_stats("hiroshima", db_path=str(corrupt)) == {
        "count": 0, "last_import": "-", "by_source": []
    }


def test_check_master_db_handles_empty_sqlite_without_poi_table(tmp_path):
    db = tmp_path / "no_poi.db"
    conn = sqlite3.connect(str(db))
    conn.execute("CREATE TABLE other (id INTEGER)")
    conn.close()

    status, msg = gui.check_master_db(str(db))
    assert status == "empty"
    assert msg
    assert gui._master_stats("hiroshima", db_path=str(db)) == {
        "count": 0, "last_import": "-", "by_source": []
    }


def test_check_master_db_returns_ok_and_stats_for_valid_db(tmp_db):
    conn = masterdb.connect(tmp_db)
    try:
        masterdb.insert_pois(conn, [{
            "name": "テスト店",
            "category": "shop:convenience",
            "lat": 34.39,
            "lon": 132.46,
            "prefecture": "hiroshima",
            "source": "osm",
            "source_id": "node/1",
            "priority": 30,
        }])
    finally:
        conn.close()

    status, msg = gui.check_master_db(tmp_db)
    assert status == "ok"
    assert msg is None
    stats = gui._master_stats("hiroshima", db_path=tmp_db)
    assert stats["count"] == 1
    assert stats["by_source"] == [("osm", 1)]
