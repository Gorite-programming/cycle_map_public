import importlib.util
from pathlib import Path
import sqlite3

import pytest


def load(relative, name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[2] / relative)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


@pytest.mark.parametrize("reader", ["builder", "search_rows"])
def test_search_readonly_uri_special_path(tmp_path, reader):
    directory = tmp_path / "search # data"
    directory.mkdir()
    path = directory / "sample.db"
    with sqlite3.connect(path) as conn:
        conn.execute("CREATE TABLE places (name, category, lat, lon, search_text)")
        conn.execute("CREATE INDEX places_coords_idx ON places(lat, lon)")
        conn.execute("INSERT INTO places VALUES ('sample', 'named', 34, 132, 'sample')")
    if reader == "builder":
        mod = load("build_prefectures.py", "uri_builder")
        assert mod.verify_search_db(path)["row_count"] == 1
    else:
        mod = load("scripts/refresh_data.py", "uri_refresh")
        assert mod.search_rows(path) == 1
