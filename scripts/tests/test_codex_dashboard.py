import importlib.util
import json
from pathlib import Path
import threading
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import urlopen

import pytest


SPEC = importlib.util.spec_from_file_location(
    "codex_dashboard", Path(__file__).resolve().parents[1] / "codex_dashboard.py"
)
dashboard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dashboard)


@pytest.fixture
def server(tmp_path):
    httpd = dashboard.create_server(tmp_path, port=0, git_directory=tmp_path)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{httpd.server_port}"
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def get_json(server, path):
    with urlopen(server + path, timeout=5) as response:
        return json.load(response)


def test_runs_status_summary_and_page(server, tmp_path):
    (tmp_path / "20261113-101500.log").write_bytes(b"running\xff\n")
    (tmp_path / "20261113-093000.log").write_text("done\n", encoding="utf-8")
    (tmp_path / "20261113-093000.last.md").write_text("完" * 81 + "\n詳細", encoding="utf-8")
    (tmp_path / "unrelated.log").touch()
    runs = get_json(server, "/api/runs")
    assert [run["timestamp"] for run in runs] == ["20261113-101500", "20261113-093000"]
    assert [run["status"] for run in runs] == ["running", "done"]
    assert runs[0]["summary"] == ""
    assert runs[1]["summary"] == "完" * 80
    assert runs[0]["log_bytes"] == 9
    assert runs[0]["updated"].endswith("+00:00")
    assert get_json(server, "/api/git") == {"count": 0, "entries": [], "diff_stat": ""}
    with urlopen(server + "/", timeout=5) as response:
        assert "委譲の進行ダッシュボード" in response.read().decode("utf-8")


def test_run_tail_default_limit_report_and_utf8(server, tmp_path):
    timestamp = "20261113-101500"
    (tmp_path / f"{timestamp}.log").write_bytes(
        "".join(f"line {i}\n" for i in range(1100)).encode() + b"invalid\xff\n"
    )
    path = f"/api/run?ts={timestamp}"
    result = get_json(server, path + "&tail=2")
    assert result == {"report": None, "log_tail": "line 1099\ninvalid\ufffd\n"}
    assert len(get_json(server, path)["log_tail"].splitlines()) == 200
    assert len(get_json(server, path + "&tail=5000")["log_tail"].splitlines()) == 1000
    (tmp_path / f"{timestamp}.last.md").write_text("完了\n詳細\n", encoding="utf-8")
    assert get_json(server, path)["report"] == "完了\n詳細\n"


def test_rejects_invalid_timestamp_and_tail(server):
    for timestamp in ("../x", "", "20261113-101500/../x", "20261113-101500\n", "２０２６１１１３-101500"):
        with pytest.raises(HTTPError) as error:
            get_json(server, "/api/run?" + urlencode({"ts": timestamp}))
        assert error.value.code == 400
    for query in ("", "ts=20261113-101500&ts=20261113-101501", "ts=20261113-101500&tail=nope"):
        with pytest.raises(HTTPError) as error:
            get_json(server, "/api/run?" + query)
        assert error.value.code == 400
