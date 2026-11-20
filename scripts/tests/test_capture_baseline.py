"""scripts/capture_baseline.sh の構文と CYCLEMAP_PACKAGE_NAME 上書きのテスト。"""
import os
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "capture_baseline.sh"


def _git_bash():
    for candidate in (
        Path(r"C:\Program Files\Git\bin\bash.exe"),
        Path(r"C:\Program Files (x86)\Git\bin\bash.exe"),
    ):
        if candidate.is_file():
            return str(candidate)
    return shutil.which("bash")


def _run_with_mock_adb(tmp_path, extra_env=None):
    bash = _git_bash()
    if not bash:
        pytest.skip("bash not available")

    fake_bin = tmp_path / "bin"
    fake_bin.mkdir(exist_ok=True)
    adb_log = tmp_path / "adb.log"

    stub_adb = fake_bin / "adb"
    stub_adb.write_text(
        "#!/usr/bin/env bash\n"
        'printf \'%s\\n\' "$*" >> "$MOCK_ADB_LOG"\n'
        'case "${1:-}" in\n'
        "  get-state) echo device ;;\n"
        "  shell)\n"
        '    if [ "${2:-}" = dumpsys ] && [ "${3:-}" = power ]; then\n'
        "      echo 'mWakefulness=Awake'\n"
        "    fi\n"
        "    ;;\n"
        "  exec-out) printf 'PNG' ;;\n"
        "esac\n",
        encoding="utf-8",
        newline="\n",
    )
    stub_adb.chmod(0o755)

    out_dir = tmp_path / "out"
    env = os.environ.copy()
    env["MOCK_ADB_LOG"] = adb_log.as_posix()
    env.pop("CYCLEMAP_PACKAGE_NAME", None)
    if extra_env:
        env.update(extra_env)

    # test_push_data.py と同様に bash 内で PATH と sleep スタブを設定し、ホストの adb 誤実行を防ぐ
    res = subprocess.run(
        [
            bash,
            "-c",
            'mock_bin="$1"; script="$2"; out_dir="$3"; '
            'if command -v cygpath >/dev/null 2>&1; then '
            '  mock_bin="$(cygpath -u "$mock_bin")"; '
            '  script="$(cygpath -u "$script")"; '
            '  out_dir="$(cygpath -u "$out_dir")"; '
            'fi; '
            'export PATH="$mock_bin:$PATH"; '
            'sleep() { :; }; export -f sleep; '
            'exec bash "$script" "$out_dir"',
            "mock-baseline",
            fake_bin.as_posix(),
            SCRIPT.as_posix(),
            out_dir.as_posix(),
        ],
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=30,
    )
    assert res.returncode == 0, res.stderr
    return adb_log.read_text(encoding="utf-8").splitlines(), out_dir


def test_capture_baseline_bash_syntax():
    bash = _git_bash()
    if not bash:
        pytest.skip("bash not available")
    res = subprocess.run([bash, "-n", str(SCRIPT)], capture_output=True, text=True)
    assert res.returncode == 0, res.stderr


def test_capture_baseline_default_package(tmp_path):
    lines, out_dir = _run_with_mock_adb(tmp_path)
    assert "shell am force-stop com.gorite.cyclemap.test" in lines
    assert "shell monkey -p com.gorite.cyclemap.test -c android.intent.category.LAUNCHER 1" in lines
    broadcasts = [l for l in lines if l.startswith("shell am broadcast ")]
    assert len(broadcasts) == 24
    assert all("-a com.gorite.cyclemap.test.CAMERA -p com.gorite.cyclemap.test " in b for b in broadcasts)
    assert (out_dir / "1_hiroshima_z12.png").is_file()


def test_capture_baseline_package_override_keeps_fixed_action(tmp_path):
    lines, _ = _run_with_mock_adb(tmp_path, {"CYCLEMAP_PACKAGE_NAME": "com.gorite.cyclemap.win"})
    assert "shell am force-stop com.gorite.cyclemap.win" in lines
    assert "shell monkey -p com.gorite.cyclemap.win -c android.intent.category.LAUNCHER 1" in lines
    broadcasts = [l for l in lines if l.startswith("shell am broadcast ")]
    assert len(broadcasts) == 24
    assert all("-a com.gorite.cyclemap.test.CAMERA -p com.gorite.cyclemap.win " in b for b in broadcasts)
