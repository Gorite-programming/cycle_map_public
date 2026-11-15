"""repack_search_db.py: ZIP の検索DBだけを入れ替える。小さな一時ファイルだけを使う。"""
import json
import sqlite3
import sys
import zipfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
for p in (ROOT, ROOT / "scripts"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

import build_prefectures as bp  # noqa: E402
import repack_search_db as rp  # noqa: E402


def _make_db(path: Path, hours=None, schema="1"):
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE places (id INTEGER PRIMARY KEY, name TEXT, category TEXT, lat REAL,"
                " lon REAL, search_text TEXT, opening_hours TEXT)")
    con.execute("CREATE INDEX places_coords_idx ON places(lat, lon)")
    con.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
    con.execute("INSERT INTO meta VALUES ('schema_version', ?)", (schema,))
    con.execute("INSERT INTO places VALUES (1, 'A', 'cafe', 34.4, 132.4, 'a', ?)", (hours,))
    con.commit()
    con.close()


def _make_zip(tmp_path: Path, name="Hiroshima") -> Path:
    old_db = tmp_path / f"{name}.search.db"
    _make_db(old_db)
    graph = tmp_path / f"{name}.graph"
    idx = tmp_path / f"{name}.graph.idx"
    graph.write_bytes(b"GRAPH" * 1000)
    idx.write_bytes(b"IDX" * 1000)
    out = tmp_path / "packages" / f"{name}.zip"
    bp.make_zip(name, old_db, graph, idx, out, bp.generate_manifest(name, bp.verify_search_db(old_db)))
    return out


def _new_db(tmp_path: Path) -> Path:
    d = tmp_path / "out"
    d.mkdir()
    new_db = d / "hiroshima.search.db"
    _make_db(new_db, hours="24/7", schema="2")
    return new_db


def test_repack_replaces_only_search_db(tmp_path):
    z = _make_zip(tmp_path)
    new_db = _new_db(tmp_path)
    backup = tmp_path / "backup"

    assert rp.repack(z, new_db, backup_dir=backup) == "replaced"

    assert bp.valid_zip(z, "Hiroshima")
    with zipfile.ZipFile(z) as zf:
        assert zf.read("Hiroshima.search.db") == new_db.read_bytes()
        assert zf.read("Hiroshima.graph") == b"GRAPH" * 1000
        assert zf.read("Hiroshima.graph.idx") == b"IDX" * 1000
        manifest = json.loads(zf.read("manifest.json"))
        assert manifest["search_db"]["meta"]["schema_version"] == "2"
        assert manifest["search_db"]["places_count"] == 1
    with zipfile.ZipFile(backup / "Hiroshima.zip") as zf:  # 入れ替え前の ZIP が残る
        assert json.loads(zf.read("manifest.json"))["search_db"]["meta"]["schema_version"] == "1"
    assert not list(z.parent.glob("*.partial"))


def test_repack_is_idempotent(tmp_path):
    z = _make_zip(tmp_path)
    new_db = _new_db(tmp_path)
    assert rp.repack(z, new_db) == "replaced"
    before = z.read_bytes()
    assert rp.repack(z, new_db) == "same"
    assert z.read_bytes() == before


def test_dry_run_does_not_write(tmp_path):
    z = _make_zip(tmp_path)
    before = z.read_bytes()
    assert rp.repack(z, _new_db(tmp_path), backup_dir=tmp_path / "backup", dry_run=True) == "would-replace"
    assert z.read_bytes() == before
    assert not (tmp_path / "backup").exists()


def test_invalid_new_db_leaves_zip_untouched(tmp_path):
    z = _make_zip(tmp_path)
    before = z.read_bytes()
    bad = tmp_path / "bad.search.db"
    sqlite3.connect(bad).execute("CREATE TABLE places (name TEXT)").connection.commit()
    with pytest.raises(ValueError):
        rp.repack(z, bad)
    assert z.read_bytes() == before


def test_main_reports_missing_db_and_continues(tmp_path, capsys):
    z = _make_zip(tmp_path)
    _make_zip_other = _make_zip(tmp_path, name="Okayama")
    new_db = _new_db(tmp_path)
    rc = rp.main(["--packages", str(z.parent), "--db-dir", str(new_db.parent)])
    assert rc == 1  # Okayama の検索DBが無い
    out = capsys.readouterr()
    assert "Hiroshima    replaced" in out.out
    assert "Okayama" in out.err
    assert bp.valid_zip(_make_zip_other, "Okayama")
