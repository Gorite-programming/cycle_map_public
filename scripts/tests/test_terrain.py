"""scripts/build_terrain.py のダウンロード・再試行・--recheck-empty のテスト (HTTP はモック)。"""
import io
import sys
import urllib.error
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import build_terrain as bt


class _FakeResp:
    def __init__(self, data: bytes):
        self._data = data

    def read(self):
        return self._data

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _http_error(code: int, url: str = "https://example.invalid/dem.png"):
    return urllib.error.HTTPError(url, code, f"HTTP {code}", {}, io.BytesIO(b""))


def test_fetch_dem_404_caches_empty_file_without_retry(tmp_path, monkeypatch):
    monkeypatch.setattr(bt, "CACHE", tmp_path)
    calls = []
    sleeps = []

    def fake_urlopen(req, timeout=30):
        calls.append(req.full_url)
        raise _http_error(404, req.full_url)

    monkeypatch.setattr(bt.urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(bt.time, "sleep", sleeps.append)

    xy, ok = bt.fetch_dem((100, 200))
    assert (xy, ok) == ((100, 200), True)
    assert len(calls) == 1
    assert sleeps == []
    cached = bt.cache_path(100, 200)
    assert cached.exists()
    assert cached.read_bytes() == b""


@pytest.mark.parametrize("code", [403, 429, 500, 503])
def test_fetch_dem_transient_http_error_retries_with_backoff_and_does_not_cache(tmp_path, monkeypatch, code):
    monkeypatch.setattr(bt, "CACHE", tmp_path)
    calls = []
    sleeps = []

    def fake_urlopen(req, timeout=30):
        calls.append(req.full_url)
        raise _http_error(code, req.full_url)

    monkeypatch.setattr(bt.urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(bt.time, "sleep", sleeps.append)

    xy, ok = bt.fetch_dem((101, 201))
    assert (xy, ok) == ((101, 201), False)
    assert len(calls) == 4
    assert len(sleeps) >= 2
    assert sleeps == sorted(sleeps) and sleeps[-1] > sleeps[0]
    assert not bt.cache_path(101, 201).exists()


def test_fetch_dem_403_then_200_succeeds_and_caches_image(tmp_path, monkeypatch):
    monkeypatch.setattr(bt, "CACHE", tmp_path)
    sleeps = []
    attempt = {"n": 0}
    png_bytes = b"\x89PNG\r\n\x1a\nfake"

    def fake_urlopen(req, timeout=30):
        attempt["n"] += 1
        if attempt["n"] < 3:
            raise _http_error(403, req.full_url)
        return _FakeResp(png_bytes)

    monkeypatch.setattr(bt.urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(bt.time, "sleep", sleeps.append)

    xy, ok = bt.fetch_dem((102, 202))
    assert (xy, ok) == ((102, 202), True)
    assert attempt["n"] == 3
    assert bt.cache_path(102, 202).read_bytes() == png_bytes


def test_recheck_empty_replaces_recovered_tiles_and_keeps_404(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(bt, "CACHE", tmp_path)
    monkeypatch.setattr(bt.time, "sleep", lambda _: None)

    p_recover = bt.cache_path(10, 1)
    p_still_404 = bt.cache_path(10, 2)
    p_existing = bt.cache_path(10, 3)
    for p in (p_recover, p_still_404, p_existing):
        p.parent.mkdir(parents=True, exist_ok=True)
    p_recover.write_bytes(b"")
    p_still_404.write_bytes(b"")
    p_existing.write_bytes(b"\x89PNGexisting")

    requested = []
    recovered_png = b"\x89PNG\r\n\x1a\nrecovered"

    def fake_urlopen(req, timeout=30):
        requested.append(req.full_url)
        if "/10/1.png" in req.full_url:
            return _FakeResp(recovered_png)
        if "/10/2.png" in req.full_url:
            raise _http_error(404, req.full_url)
        raise AssertionError(f"unexpected tile fetch: {req.full_url}")

    monkeypatch.setattr(bt.urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(sys, "argv", ["build_terrain.py", "--recheck-empty"])

    rc = bt.main()
    out = capsys.readouterr().out

    assert rc == 0
    assert p_recover.read_bytes() == recovered_png
    assert p_still_404.read_bytes() == b""
    assert p_existing.read_bytes() == b"\x89PNGexisting"
    assert not any("/10/3.png" in url for url in requested)
    assert "1" in out


def test_recheck_empty_caps_workers_and_preserves_empty_on_403(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(bt, "CACHE", tmp_path)
    monkeypatch.setattr(bt.time, "sleep", lambda _: None)

    p_err = bt.cache_path(20, 1)
    p_err.parent.mkdir(parents=True, exist_ok=True)
    p_err.write_bytes(b"")

    observed_workers = []
    real_executor = bt.concurrent.futures.ThreadPoolExecutor

    class SpyExecutor(real_executor):
        def __init__(self, max_workers=None, *args, **kwargs):
            observed_workers.append(max_workers)
            super().__init__(max_workers=max_workers, *args, **kwargs)

    monkeypatch.setattr(bt.concurrent.futures, "ThreadPoolExecutor", SpyExecutor)
    monkeypatch.setattr(bt.urllib.request, "urlopen", lambda req, timeout=30: (_ for _ in ()).throw(_http_error(403, req.full_url)))
    monkeypatch.setattr(sys, "argv", ["build_terrain.py", "--recheck-empty", "--workers", "16"])

    rc = bt.main()
    out = capsys.readouterr().out

    assert rc == 1
    assert observed_workers == [4]
    assert p_err.read_bytes() == b""
    assert "失敗 1" in out


def _write_dem_png(path: Path, elev_m: float, nodata_slice=None):
    import numpy as np
    from PIL import Image

    raw_val = round(elev_m * 100.0)
    if raw_val < 0:
        raw_val += 2 ** 24
    arr = np.empty((256, 256, 3), dtype=np.uint8)
    arr[..., 0] = (raw_val >> 16) & 0xFF
    arr[..., 1] = (raw_val >> 8) & 0xFF
    arr[..., 2] = raw_val & 0xFF
    if nodata_slice is not None:
        arr[nodata_slice] = (128, 0, 0)  # v == 2**23 (GSI nodata)
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(arr, mode="RGB").save(path, format="PNG")


def test_load_dem_treats_nodata_as_nan_and_avoids_hillshade_cliff(tmp_path, monkeypatch):
    import numpy as np

    bt._load_generation_dependencies()
    bt.load_dem.cache_clear()
    monkeypatch.setattr(bt, "CACHE", tmp_path)

    # Constant 500m plateau with an interior 4x4 nodata (v == 2**23) patch
    _write_dem_png(bt.cache_path(100, 100), 500.0, nodata_slice=(slice(120, 124), slice(120, 124)))
    dem = bt.load_dem(100, 100)
    assert dem is not None
    assert np.isnan(dem[120:124, 120:124]).all()
    assert np.isclose(dem[0, 0], 500.0)

    # Flat plateau with nodata patch must NOT create artificial cliff shadows
    assert bt.hillshade_tile(14, 100, 100) is None
    # Contours on flat plateau with nodata patch must NOT create spurious crater rings
    assert bt.contour_tile(14, 100, 100) is None

    # All-nodata tile returns None from dem_window
    _write_dem_png(bt.cache_path(200, 200), 0.0, nodata_slice=(slice(None), slice(None)))
    assert bt.dem_window(14, 200, 200, pad=1) is None


def test_contour_extracts_linestrings_from_geometry_collection():
    from shapely.geometry import GeometryCollection, LineString, Point

    bt._load_generation_dependencies()
    gc = GeometryCollection([
        Point(1.0, 2.0),
        LineString([(0.0, 0.0), (10.0, 10.0)]),
        GeometryCollection([LineString([(20.0, 20.0), (30.0, 30.0)])]),
    ])
    lines = bt._extract_linestrings(gc)
    assert len(lines) == 2
    assert all(isinstance(g, LineString) for g in lines)


def test_contour_tile_preserves_geometry_collection_intersections(tmp_path, monkeypatch):
    import gzip
    import mapbox_vector_tile as mvt
    import numpy as np
    from PIL import Image

    bt._load_generation_dependencies()
    bt.load_dem.cache_clear()
    monkeypatch.setattr(bt, "CACHE", tmp_path)

    # Ramp elevation from 0m to 100m across x so contours at 10m..90m cross the tile
    xs = np.linspace(0.0, 100.0, 256, dtype=np.float32)
    elev = np.broadcast_to(xs, (256, 256))
    vals = np.round(elev * 100.0).astype(np.int64)
    rgb = np.stack([(vals >> 16) & 0xFF, (vals >> 8) & 0xFF, vals & 0xFF], axis=-1).astype(np.uint8)
    # Put a nodata strip on one side; contour lines still generate on the valid slope
    rgb[:8, :8] = (128, 0, 0)
    p = bt.cache_path(50, 50)
    p.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(rgb, mode="RGB").save(p, format="PNG")

    # Force line.intersection(clip) to return a GeometryCollection (LineString + Point)
    from shapely.geometry import GeometryCollection, Point

    orig_intersection = bt.LineString.intersection

    def gc_intersection(self, other):
        res = orig_intersection(self, other)
        if not res.is_empty:
            return GeometryCollection([res, Point(0, 0)])
        return res

    monkeypatch.setattr(bt.LineString, "intersection", gc_intersection)
    data = bt.contour_tile(14, 50, 50)
    assert data is not None
    decoded = mvt.decode(gzip.decompress(data))
    assert "contour" in decoded
    assert len(decoded["contour"]["features"]) > 0

