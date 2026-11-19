"""Overture Maps ソースモジュールの単体テスト。"""

import sqlite3
import unittest
from pathlib import Path

import duckdb

from tools.core import config, masterdb
from tools.core.sources import overture


class TestOvertureSource(unittest.TestCase):
    def test_source_priority(self):
        """Overture の優先度 (20) が kokudo (50) および osm (30) より低いことを確認 (OSMの正確な座標を勝者とするため)。"""
        self.assertEqual(config.source_priority("overture"), 20)
        self.assertEqual(config.source_priority("overture:places"), 20)
        self.assertTrue(config.source_priority("kokudo") > config.source_priority("osm"))
        self.assertTrue(config.source_priority("osm") > config.source_priority("overture"))

    def test_category_resolution_convenience(self):
        """コンビニ名称パターンによるカテゴリ判定。"""
        self.assertEqual(overture.resolve_category("セブン-イレブン 山口葵店", "shopping", None), "shop:convenience")
        self.assertEqual(overture.resolve_category("ファミリーマート道場門前", None, "store"), "shop:convenience")
        self.assertEqual(overture.resolve_category("LAWSON", None, None), "shop:convenience")
        self.assertEqual(overture.resolve_category("ミニストップ", "restaurant", None), "shop:convenience")

    def test_category_resolution_mapping(self):
        """Overture タクソノミーからの標準マッピング。"""
        self.assertEqual(overture.resolve_category("スターバックス", "coffee_shop", "cafe"), "amenity:cafe")
        self.assertEqual(overture.resolve_category("済生会総合病院", "general_hospital", "hospital"), "amenity:hospital")
        self.assertEqual(overture.resolve_category("あさひ自転車", "bicycle_shop", None), "shop:bicycle")
        self.assertEqual(overture.resolve_category("道の駅 仁保の郷", "tourist_attraction", None), "tourism:attraction")
        self.assertEqual(overture.resolve_category("湯田温泉 温泉宿", "hotel", None), "amenity:public_bath")

    def test_address_formatting(self):
        """住所フォーマット処理。"""
        addr = overture.format_address("葵2丁目6-8", "山口市", "山口県")
        self.assertEqual(addr, "山口県 山口市 葵2丁目6-8")

        # 既にfreeformに県や市が含まれている場合の重複防止
        addr2 = overture.format_address("山口県山口市中央1-1", "山口市", "山口県")
        self.assertEqual(addr2, "山口県山口市中央1-1")


def _write_fake_overture_parquet(path: Path, with_operating_status: bool = True, with_sources: bool = True) -> str:
    con = duckdb.connect()
    try:
        rows_sql = """
            SELECT
                'ov-open' AS id,
                {'primary': 'オープンカフェ'} AS names,
                {'primary': 'cafe'} AS taxonomy,
                'cafe' AS basic_category,
                [{'freeform': '中区基町1-1', 'locality': '広島市', 'region': '広島県', 'postcode': '730-0011'}] AS addresses,
                {'xmin': 132.455, 'xmax': 132.455, 'ymin': 34.395, 'ymax': 34.395} AS bbox,
                0.95 AS confidence,
                ['+81822082822'] AS phones,
                ['https://open.example.com'] AS websites,
                'open' AS operating_status,
                [{'property': '', 'dataset': 'meta'}, {'property': '/websites', 'dataset': 'Foursquare'}] AS sources
            UNION ALL
            SELECT
                'ov-perm-closed',
                {'primary': '閉店コンビニ'},
                {'primary': 'convenience_store'},
                'convenience_store',
                [{'freeform': '中区紙屋町1-2', 'locality': '広島市', 'region': '広島県', 'postcode': '730-0011'}],
                {'xmin': 132.456, 'xmax': 132.456, 'ymin': 34.396, 'ymax': 34.396},
                0.90,
                ['082-111-2222'],
                ['https://closed.example.com'],
                'permanently_closed',
                [{'property': '', 'dataset': 'Foursquare'}]
            UNION ALL
            SELECT
                'ov-temp-closed',
                {'primary': '休業食堂'},
                {'primary': 'restaurant'},
                'restaurant',
                [{'freeform': '中区大手町1-3', 'locality': '広島市', 'region': '広島県', 'postcode': '730-0051'}],
                {'xmin': 132.457, 'xmax': 132.457, 'ymin': 34.397, 'ymax': 34.397},
                0.85,
                ['082-333-4444'],
                ['https://temp.example.com'],
                'temporarily_closed',
                [{'property': '', 'dataset': 'Microsoft'}]
            UNION ALL
            SELECT
                'ov-null-status',
                {'primary': '旧形式ショップ'},
                {'primary': 'bicycle_shop'},
                'bicycle_shop',
                [{'freeform': '中区本通1-4', 'locality': '広島市', 'region': '広島県', 'postcode': '730-0035'}],
                {'xmin': 132.458, 'xmax': 132.458, 'ymin': 34.398, 'ymax': 34.398},
                0.80,
                ['not-a-phone', '082-222-3333'],
                ['example.jp/bike'],
                NULL,
                [{'property': '', 'dataset': 'Microsoft'}]
        """
        exclude_cols = []
        if not with_operating_status:
            exclude_cols.append("operating_status")
        if not with_sources:
            exclude_cols.append("sources")
        exclude_clause = f" EXCLUDE ({', '.join(exclude_cols)})" if exclude_cols else ""
        pq_path = str(path).replace("\\", "/")
        con.execute(f"COPY (SELECT *{exclude_clause} FROM ({rows_sql})) TO '{pq_path}' (FORMAT PARQUET)")
        return pq_path
    finally:
        con.close()


def test_fetch_overture_filters_closed_and_keeps_sources_phones_websites(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "prefecture_for_point", lambda lat, lon: "hiroshima")
    pq_path = _write_fake_overture_parquet(tmp_path / "places.parquet")

    rows = overture.fetch_overture_places_for_bbox(
        bbox=config.PREFECTURES["hiroshima"]["bbox"],
        prefecture_id="hiroshima",
        imported_at="2026-11-19T00:00:00+00:00",
        s3_path=pq_path,
    )

    by_id = {r["source_id"]: r for r in rows}
    assert set(by_id) == {"ov-open", "ov-null-status"}

    open_row = by_id["ov-open"]
    assert open_row["phone"] == "082-208-2822"
    assert open_row["website"] == "https://open.example.com"
    assert open_row["source_dataset"] == "meta,Foursquare"

    null_row = by_id["ov-null-status"]
    assert null_row["phone"] == "082-222-3333"
    assert null_row["website"] == "http://example.jp/bike"
    assert null_row["source_dataset"] == "Microsoft"


def test_fetch_overture_supports_legacy_parquet_without_operating_status_or_sources(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "prefecture_for_point", lambda lat, lon: "hiroshima")
    pq_path = _write_fake_overture_parquet(
        tmp_path / "legacy.parquet",
        with_operating_status=False,
        with_sources=False,
    )

    rows = overture.fetch_overture_places_for_bbox(
        bbox=config.PREFECTURES["hiroshima"]["bbox"],
        prefecture_id="hiroshima",
        imported_at="2026-11-19T00:00:00+00:00",
        s3_path=pq_path,
    )

    assert len(rows) == 4
    assert all(r["source_dataset"] is None for r in rows)


def test_import_overture_migrates_existing_master_db_and_persists_dataset_and_contacts(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "prefecture_for_point", lambda lat, lon: "hiroshima")
    db_path = str(tmp_path / "master.db")
    conn = sqlite3.connect(db_path)
    conn.execute("""
        CREATE TABLE poi (
            uid INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            category TEXT NOT NULL,
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            address TEXT,
            prefecture TEXT,
            source TEXT NOT NULL,
            source_id TEXT,
            priority INTEGER NOT NULL,
            imported_at TEXT NOT NULL,
            merged_into INTEGER,
            kana TEXT,
            hours TEXT,
            phone TEXT,
            website TEXT,
            wheelchair TEXT
        )
    """)
    conn.execute(
        "INSERT INTO poi(name, category, lat, lon, prefecture, source, source_id, priority, imported_at)"
        " VALUES('既存OSM', 'shop:convenience', 34.39, 132.45, 'hiroshima', 'osm', 'node/1', 30, 't')"
    )
    conn.commit()
    conn.close()

    pq_path = _write_fake_overture_parquet(tmp_path / "places.parquet")
    summary = overture.import_overture("hiroshima", db_path=db_path, s3_path=pq_path)
    assert summary["inserted"] == 2

    mconn = masterdb.connect(db_path)
    try:
        pois = {r["source_id"]: r for r in masterdb.query_pois(mconn, prefecture="hiroshima")}
        assert set(pois) == {"node/1", "ov-open", "ov-null-status"}
        assert pois["node/1"]["source_dataset"] is None
        assert pois["ov-open"]["phone"] == "082-208-2822"
        assert pois["ov-open"]["website"] == "https://open.example.com"
        assert pois["ov-open"]["source_dataset"] == "meta,Foursquare"
    finally:
        mconn.close()


if __name__ == "__main__":
    unittest.main()
