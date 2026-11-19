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

