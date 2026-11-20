"""scripts/check_all.sh の構文・Windows Git Bash 互換 (venv Python / hash_stdin) のテスト。"""
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "check_all.sh"


def _git_bash():
    for candidate in (
        Path(r"C:\Program Files\Git\bin\bash.exe"),
        Path(r"C:\Program Files (x86)\Git\bin\bash.exe"),
    ):
        if candidate.is_file():
            return str(candidate)
    return shutil.which("bash")


def test_check_all_bash_syntax_and_no_bare_python3_or_shasum():
    bash = _git_bash()
    if not bash:
        pytest.skip("bash not available")
    res = subprocess.run([bash, "-n", str(SCRIPT)], capture_output=True, text=True)
    assert res.returncode == 0, res.stderr

    text = SCRIPT.read_text(encoding="utf-8")
    for lineno, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        assert "| shasum" not in stripped, f"line {lineno}: bare shasum pipe remains"
        if re.search(r"(^|[;&|]\s*)python3\b", stripped):
            pytest.fail(f"line {lineno}: bare python3 invocation remains: {line}")


def test_check_all_hash_stdin_python_fallback(tmp_path):
    bash = _git_bash()
    if not bash:
        pytest.skip("bash not available")

    text = SCRIPT.read_text(encoding="utf-8").replace("\r\n", "\n")
    match = re.search(r"(hash_stdin\(\) \{.*?\n\})", text, re.DOTALL)
    assert match, "hash_stdin() not found in check_all.sh"
    hash_fn = match.group(1)

    py_exe = Path(os.sys.executable).as_posix()
    probe = tmp_path / "probe.sh"
    probe.write_text(
        f"set -euo pipefail\n"
        f"PY={py_exe!r}\n"
        f"{hash_fn}\n"
        f"printf 'hello' | hash_stdin\n",
        encoding="utf-8",
        newline="\n",
    )

    # PATH に sha256sum / shasum が無い状態にして Python hashlib フォールバックを通す
    empty_bin = tmp_path / "empty_bin"
    empty_bin.mkdir()
    res = subprocess.run(
        [
            bash,
            "-c",
            'empty_bin="$1"; probe="$2"; '
            'if command -v cygpath >/dev/null 2>&1; then '
            '  empty_bin="$(cygpath -u "$empty_bin")"; probe="$(cygpath -u "$probe")"; '
            'fi; '
            'export PATH="$empty_bin"; source "$probe"',
            "probe-runner",
            empty_bin.as_posix(),
            probe.as_posix(),
        ],
        capture_output=True,
        text=True,
        timeout=10,
    )
    assert res.returncode == 0, res.stderr
    assert res.stdout.strip().startswith("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
