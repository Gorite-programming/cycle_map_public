"""scripts/device_regression.sh の構文と trap 後始末のテスト。"""
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "device_regression.sh"


def _git_bash():
    # Prefer Git Bash on Windows to avoid WSL bash.exe if present without a distro
    for candidate in (
        Path(r"C:\Program Files\Git\bin\bash.exe"),
        Path(r"C:\Program Files (x86)\Git\bin\bash.exe"),
    ):
        if candidate.is_file():
            return str(candidate)
    return shutil.which("bash")


def test_device_regression_bash_syntax_and_trap():
    bash = _git_bash()
    if not bash:
        pytest.skip("bash not available")
    res = subprocess.run([bash, "-n", str(SCRIPT)], capture_output=True, text=True)
    assert res.returncode == 0, res.stderr

    text = SCRIPT.read_text(encoding="utf-8")
    assert "trap cleanup EXIT INT TERM" in text


def test_device_regression_cleanup_kills_lp_and_stops_app(tmp_path):
    bash = _git_bash()
    if not bash:
        pytest.skip("bash not available")

    text = SCRIPT.read_text(encoding="utf-8").replace("\r\n", "\n")
    match = re.search(r"(LP=\"\"\ncleanup\(\) \{.*?\ntrap cleanup EXIT INT TERM)", text, re.DOTALL)
    assert match, "cleanup() and trap block not found in device_regression.sh"
    trap_block = match.group(1)

    adb_log = tmp_path / "adb.log"
    stub_adb = tmp_path / "adb"
    stub_adb.write_text(
        f"#!/usr/bin/env bash\n"
        f"echo \"$*\" >> '{adb_log.as_posix()}'\n"
        f"exit 0\n",
        encoding="utf-8",
    )
    stub_adb.chmod(0o755)

    lp_pid_file = tmp_path / "lp.pid"
    probe = tmp_path / "probe.sh"
    probe.write_text(
        f"set -u\n"
        f"PKG=com.gorite.cyclemap.test\n"
        f"{trap_block}\n"
        f"( while true; do sleep 0.2; done ) >/dev/null 2>&1 &\n"
        f"LP=$!\n"
        f"echo \"$LP\" > '{lp_pid_file.as_posix()}'\n"
        f"exit 130\n",
        encoding="utf-8",
    )

    env = os.environ.copy()
    env["PATH"] = f"{tmp_path}{os.pathsep}{env.get('PATH', '')}"
    res = subprocess.run([bash, str(probe)], env=env, capture_output=True, text=True, timeout=10)
    assert res.returncode == 130
    assert "shell am force-stop com.gorite.cyclemap.test" in adb_log.read_text(encoding="utf-8")
    # Ensure cleanup also succeeds cleanly when LP is empty (no unbound variable under set -u)
    probe_empty = tmp_path / "probe_empty.sh"
    probe_empty.write_text(
        f"set -u\n"
        f"PKG=com.gorite.cyclemap.test\n"
        f"{trap_block}\n"
        f"exit 0\n",
        encoding="utf-8",
    )
    res_empty = subprocess.run([bash, str(probe_empty)], env=env, capture_output=True, text=True, timeout=10)
    assert res_empty.returncode == 0, res_empty.stderr
