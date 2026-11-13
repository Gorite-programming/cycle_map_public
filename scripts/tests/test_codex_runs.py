import json
from pathlib import Path
import subprocess
import sys


SCRIPT = Path(__file__).resolve().parents[1] / "codex_runs.py"


def run_cli(directory, *args):
    result = subprocess.run(
        [sys.executable, "-X", "utf8", str(SCRIPT), "--dir", str(directory), *args],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
    return result.stdout


def test_table_lists_runs_once_in_reverse_order(tmp_path):
    (tmp_path / "20261113-101500.last.md").write_text("完了\n詳細\n", encoding="utf-8")
    (tmp_path / "20261113-101500.log").write_text("log", encoding="utf-8")
    (tmp_path / "20261113-093000.log").write_text("log only", encoding="utf-8")
    (tmp_path / "unrelated.log").touch()

    assert run_cli(tmp_path).splitlines() == [
        "Timestamp        Summary",
        "---------------  -------",
        "20261113-101500  完了",
        "20261113-093000  -",
    ]


def test_json_limits_first_line_to_80_characters(tmp_path):
    (tmp_path / "20261113-102000.last.md").write_text("あ" * 81 + "\n詳細", encoding="utf-8")
    (tmp_path / "20261113-100000.last.md").write_text("\n詳細", encoding="utf-8")

    assert json.loads(run_cli(tmp_path, "--json")) == [
        {"timestamp": "20261113-102000", "summary": "あ" * 80},
        {"timestamp": "20261113-100000", "summary": ""},
    ]
    assert json.loads(run_cli(tmp_path / "missing", "--json")) == []
