"""scripts/build_tiles.py の UTC 日付・ネットワークエラー捕捉のテスト。"""
import io
import sys
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import build_tiles as bt


class _OkResp:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def test_latest_build_uses_utc_date(monkeypatch):
    seen_urls = []

    class _FakeDatetime:
        @classmethod
        def now(cls, tz=None):
            assert tz == timezone.utc
            return datetime(2026, 11, 20, 18, 0, tzinfo=timezone.utc)

    def fake_urlopen(req, timeout=30):
        seen_urls.append(req.full_url)
        return _OkResp()

    monkeypatch.setattr(bt, "datetime", _FakeDatetime)
    monkeypatch.setattr(bt.urllib.request, "urlopen", fake_urlopen)

    stamp = bt.latest_build()
    assert stamp == "20261120"
    assert seen_urls == ["https://build.protomaps.com/20261120.pmtiles"]


def test_latest_build_handles_url_error_with_nonzero_exit(monkeypatch, capsys):
    def fake_urlopen(req, timeout=30):
        raise urllib.error.URLError("Name or service not known")

    monkeypatch.setattr(bt.urllib.request, "urlopen", fake_urlopen)

    with pytest.raises(SystemExit) as exc:
        bt.latest_build()
    assert exc.value.code != 0
    assert "Name or service not known" in str(exc.value.code)


def test_latest_build_handles_timeout_and_http_503_with_nonzero_exit(monkeypatch):
    def fake_timeout(req, timeout=30):
        raise TimeoutError("timed out")

    monkeypatch.setattr(bt.urllib.request, "urlopen", fake_timeout)
    with pytest.raises(SystemExit) as exc_t:
        bt.latest_build()
    assert exc_t.value.code != 0
    assert "timed out" in str(exc_t.value.code)

    def fake_503(req, timeout=30):
        raise urllib.error.HTTPError(req.full_url, 503, "Service Unavailable", {}, io.BytesIO(b""))

    monkeypatch.setattr(bt.urllib.request, "urlopen", fake_503)
    with pytest.raises(SystemExit) as exc_h:
        bt.latest_build()
    assert exc_h.value.code != 0
    assert "503" in str(exc_h.value.code)


def test_main_exits_nonzero_when_latest_build_fails_with_url_error(monkeypatch):
    monkeypatch.setattr(bt, "find_pmtiles_cli", lambda: "pmtiles")
    monkeypatch.setattr(
        bt.urllib.request,
        "urlopen",
        lambda req, timeout=30: (_ for _ in ()).throw(urllib.error.URLError("network down")),
    )
    monkeypatch.setattr(sys, "argv", ["build_tiles.py", "--only", "Tottori"])

    with pytest.raises(SystemExit) as exc:
        bt.main()
    assert exc.value.code != 0
