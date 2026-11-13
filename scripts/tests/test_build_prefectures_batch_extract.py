"""build_prefectures.py の batch_extract() まわりの単体テスト (osmium は呼ばない)。"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import build_prefectures as bp  # noqa: E402


def test_batch_extract_config_lists_each_prefecture(tmp_path, monkeypatch):
    monkeypatch.setattr(bp, "WORK_DIR", tmp_path / "work")
    monkeypatch.setattr(bp, "BOUNDARY_DIR", tmp_path / "boundaries")
    prefs = [{"name": "Hyogo"}, {"name": "Okayama"}]

    config = bp.batch_extract_config(prefs)

    assert [e["output"] for e in config["extracts"]] == [
        str((tmp_path / "work" / "Hyogo" / "Hyogo.osm.pbf").resolve()),
        str((tmp_path / "work" / "Okayama" / "Okayama.osm.pbf").resolve()),
    ]
    assert all(e["output_format"] == "pbf" for e in config["extracts"])
    assert config["extracts"][0]["polygon"] == {
        "file_name": str((tmp_path / "boundaries" / "Hyogo.geojson").resolve()),
        "file_type": "geojson",
    }


def test_batch_extract_skips_for_fewer_than_two(tmp_path, monkeypatch):
    monkeypatch.setattr(bp, "WORK_DIR", tmp_path / "work")
    calls = []
    monkeypatch.setattr(bp, "run_live", lambda *a, **k: calls.append(a) or 0)

    assert bp.batch_extract([]) == set()
    assert bp.batch_extract([{"name": "Hyogo"}]) == set()
    assert calls == []  # osmium (run_live) は一度も呼ばれない


def test_batch_extract_success_reports_prefectures_with_output(tmp_path, monkeypatch):
    work_dir = tmp_path / "work"
    monkeypatch.setattr(bp, "WORK_DIR", work_dir)
    monkeypatch.setattr(bp, "BOUNDARY_DIR", tmp_path / "boundaries")
    monkeypatch.setattr(bp, "LOG_DIR", tmp_path / "logs")
    prefs = [{"name": "Hyogo"}, {"name": "Okayama"}]

    def fake_run_live(cmd, log_file, env=None):
        # 本物の osmium の代わりに、設定ファイルどおりの出力を作る。
        assert "--config" in cmd
        (work_dir / "Hyogo").mkdir(parents=True, exist_ok=True)
        (work_dir / "Hyogo" / "Hyogo.osm.pbf").write_bytes(b"x")
        # Okayama はあえて作らない (抽出に失敗したケースを模す)。
        return 0

    monkeypatch.setattr(bp, "run_live", fake_run_live)

    done = bp.batch_extract(prefs)

    assert done == {"Hyogo"}


def test_batch_extract_failure_falls_back_to_empty_set(tmp_path, monkeypatch):
    monkeypatch.setattr(bp, "WORK_DIR", tmp_path / "work")
    monkeypatch.setattr(bp, "BOUNDARY_DIR", tmp_path / "boundaries")
    monkeypatch.setattr(bp, "LOG_DIR", tmp_path / "logs")
    monkeypatch.setattr(bp, "run_live", lambda *a, **k: 1)

    assert bp.batch_extract([{"name": "Hyogo"}, {"name": "Okayama"}]) == set()
