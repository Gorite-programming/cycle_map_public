"""Run an isolated copy of push_data.sh against a fake adb; never touch devices."""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys

import pytest


SCRIPT = Path(__file__).resolve().parents[1] / "push_data.sh"
SERIALS = ("RFG_PRIVATE_ONE", "RFC_PRIVATE_TWO")
MOCK_ADB = r'''#!/usr/bin/env bash
set -eu
printf '%s|' "$@" >> "$MOCK_LOG"
printf '\n' >> "$MOCK_LOG"
if [[ "$1" == devices ]]; then
    printf 'List of devices attached\n%s\tdevice\r\n' "$MOCK_SERIAL_ONE"
    if [[ "${MOCK_TWO:-1}" == 1 ]]; then
        printf '%s\tdevice\r\n' "$MOCK_SERIAL_TWO"
    fi
    exit 0
fi
[[ "$1" == -s ]] || exit 90
serial="$2"
shift 2
count_call() {
    count=0
    [[ ! -f "$MOCK_STATE/$1" ]] || read -r count < "$MOCK_STATE/$1"
    count=$((count + 1))
    printf '%s\n' "$count" > "$MOCK_STATE/$1"
}
case "$1" in
    start-server) exit 0 ;;
    push)
        count_call push
        if [[ "$count" -le "${MOCK_PUSH_FAILURES:-0}" ]]; then
            echo "error: device '$serial' disconnected" >&2
            exit 1
        fi
        echo "1 file pushed to $serial"
        ;;
    shell)
        case "$2" in
            getprop)
                if [[ "$serial" == "$MOCK_SERIAL_ONE" ]]; then
                    echo 'Galaxy S26'
                else
                    echo 'Galaxy S21'
                fi
                ;;
            sha256sum)
                count_call hash
                if [[ "$count" -le "${MOCK_HASH_EMPTY:-0}" ]]; then
                    exit 0
                fi
                if [[ "$count" -le "${MOCK_HASH_ERRORS:-0}" ]]; then
                    echo "error: device '$serial' disconnected" >&2
                    exit 1
                fi
                if [[ "${MOCK_MISMATCH:-0}" == 1 ||
                      ( "${MOCK_MISMATCH_ONCE:-0}" == 1 && "$count" == 1 ) ]]; then
                    printf '%064d  %s\n' 0 "$3"
                else
                    printf '%s  %s\n' "$MOCK_HASH" "$3"
                fi
                ;;
            ls) printf '%s 1 shell shell 10 today file\n' "${MOCK_MODE:--rw-rw-rw-}" ;;
            *) exit 0 ;;
        esac
        ;;
    *) exit 91 ;;
esac
'''


@pytest.fixture
def deploy(tmp_path):
    bash = shutil.which("bash")
    if not bash:
        pytest.skip("bash is required")
    root = tmp_path / "isolated"
    scripts = root / "scripts"
    scripts.mkdir(parents=True)
    target = scripts / "push_data.sh"
    shutil.copyfile(SCRIPT, target)
    packages = root / "packages"
    packages.mkdir()
    payload = b"mock map tiles\n"
    (packages / "yamaguchi.pmtiles").write_bytes(payload)
    fake_bin = root / "bin"
    fake_bin.mkdir()
    log = root / "adb.log"
    state = root / "state"
    state.mkdir()
    for name, content in {
        "adb": MOCK_ADB,
        "sleep": "#!/usr/bin/env bash\nexit 0\n",
        "python3": f'#!/usr/bin/env bash\nexec "{Path(sys.executable).as_posix()}" "$@"\n',
    }.items():
        path = fake_bin / name
        path.write_text(content, encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def run(*args, **settings):
        env = os.environ.copy()
        env.pop("ANDROID_SERIAL", None)
        env.update({
            "MOCK_LOG": log.as_posix(),
            "MOCK_STATE": state.as_posix(),
            "MOCK_SERIAL_ONE": SERIALS[0],
            "MOCK_SERIAL_TWO": SERIALS[1],
            "MOCK_HASH": hashlib.sha256(payload).hexdigest(),
        })
        env.update(settings)
        # Set PATH inside bash, avoiding Windows PATH conversion and ensuring
        # the fake adb precedes every real SDK installation on the host.
        result = subprocess.run(
            [bash, "-c", 'mock_bin="$1"; if command -v cygpath >/dev/null 2>&1; then '
             'mock_bin="$(cygpath -u "$mock_bin")"; fi; '
             'export PATH="$mock_bin:$PATH"; shift; exec bash "$@"',
             "mock-deploy", fake_bin.as_posix(), target.as_posix(),
             "--tiles-only", "--pref", "Yamaguchi", *args],
            env=env, capture_output=True, text=True, encoding="utf-8", timeout=30,
        )
        calls = log.read_text().splitlines() if log.exists() else []
        output = result.stdout + result.stderr
        for serial in SERIALS:
            assert serial not in output
        return result, calls

    return run


def count(calls, command):
    return sum(command in call for call in calls)


def test_two_devices_require_serial(deploy):
    result, calls = deploy()
    assert result.returncode != 0
    assert "--serial" in result.stderr
    assert "Galaxy S26" in result.stderr and "Galaxy S21" in result.stderr
    assert "RFG…" in result.stderr and "RFC…" in result.stderr
    assert count(calls, "|push|") == 0
    assert len(calls) == 3  # discovery and two model queries only


@pytest.mark.parametrize("selection", ["option", "environment", "override", "single"])
def test_selected_device_is_used_for_every_command(deploy, selection):
    args, settings = [], {}
    selected = SERIALS[1]
    if selection in ("option", "override"):
        args = ["--serial", selected]
    if selection == "environment":
        settings["ANDROID_SERIAL"] = selected
    if selection == "override":
        settings["ANDROID_SERIAL"] = SERIALS[0]
    if selection == "single":
        selected = SERIALS[0]
        settings["MOCK_TWO"] = "0"
    result, calls = deploy(*args, **settings)
    assert result.returncode == 0, result.stderr
    assert "VERIFIED SUCCESSFULLY" in result.stdout
    assert all(c == "devices|" or c.startswith(f"-s|{selected}|") for c in calls)
    assert count(calls, "|push|") == 1
    assert count(calls, "|shell|ls|-l|") == 1
    assert count(calls, "|shell|chmod|666|") == 0


@pytest.mark.parametrize("failure", ["MOCK_HASH_EMPTY", "MOCK_HASH_ERRORS"])
def test_transient_hash_failure_retries_without_repush(deploy, failure):
    result, calls = deploy("--serial", SERIALS[0], **{failure: "1"})
    assert result.returncode == 0, result.stderr
    assert count(calls, "|shell|sha256sum|") == 2
    assert count(calls, "|push|") == 1
    assert count(calls, "|start-server|") == 1


def test_push_retries_and_recovers_server_only_once(deploy):
    result, calls = deploy("--serial", SERIALS[0], MOCK_PUSH_FAILURES="2", MOCK_HASH_EMPTY="1")
    assert result.returncode == 0, result.stderr
    assert count(calls, "|push|") == 3
    assert count(calls, "|start-server|") == 1


@pytest.mark.parametrize("failure, command", [
    ("MOCK_PUSH_FAILURES", "|push|"),
    ("MOCK_HASH_EMPTY", "|shell|sha256sum|"),
])
def test_transient_retries_are_bounded(deploy, failure, command):
    result, calls = deploy("--serial", SERIALS[0], **{failure: "9"})
    assert result.returncode != 0
    assert count(calls, command) == 3
    assert count(calls, "|start-server|") == 1


@pytest.mark.parametrize("recovers", [False, True])
def test_hash_mismatch_gets_only_one_corrective_push(deploy, recovers):
    setting = "MOCK_MISMATCH_ONCE" if recovers else "MOCK_MISMATCH"
    result, calls = deploy("--serial", SERIALS[0], **{setting: "1"})
    assert (result.returncode == 0) == recovers
    assert count(calls, "|push|") == 2
    assert count(calls, "|shell|sha256sum|") == 2
    assert count(calls, "|start-server|") == 0


def test_permissions_are_repaired(deploy):
    result, calls = deploy("--serial", SERIALS[0], MOCK_MODE="-rw-rw----")
    assert result.returncode == 0, result.stderr
    assert count(calls, "|shell|ls|-l|") == 1
    assert count(calls, "|shell|chmod|666|") == 1


def test_serial_argument_is_required(deploy):
    result, calls = deploy("--serial")
    assert result.returncode == 2
    assert calls == []


def test_dry_run_does_not_call_adb(deploy):
    result, calls = deploy("--dry-run")
    assert result.returncode == 0, result.stderr
    assert calls == []
