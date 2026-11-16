"""scripts/build_ch.py の並列実行・時間切れ・直接 java 起動のテスト (Java は起動しない)。"""
import importlib.util
import os
from pathlib import Path
import struct
import subprocess
import sys
import threading
import time
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

spec = importlib.util.spec_from_file_location("build_ch", ROOT / "scripts" / "build_ch.py")
build_ch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_ch)


def _make_pref_zip(source_dir: Path, name: str) -> Path:
    source_dir.mkdir(parents=True, exist_ok=True)
    zip_path = source_dir / f"{name}.zip"
    with zipfile.ZipFile(zip_path, "w") as z:
        z.writestr(f"{name}.graph", f"graph-data-{name}".encode("utf-8"))
        z.writestr(f"{name}.graph.idx", f"idx-data-{name}".encode("utf-8"))
    return zip_path


def _write_matching_ch(graph_path: Path, out_path: Path) -> None:
    size, first_crc, last_crc = build_ch.graph_fingerprint(graph_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(b"\x00" * 20 + struct.pack(">qqq", size, first_crc, last_crc))


def _setup_direct_env(tmp_path: Path, monkeypatch, *, with_classes: bool = True, with_stdlib: bool = True) -> dict:
    monkeypatch.setattr(build_ch, "ROOT", tmp_path)
    app_dir = tmp_path / "application"
    (app_dir / "gradle").mkdir(parents=True)
    (app_dir / "gradle" / "libs.versions.toml").write_text('[versions]\nkotlin = "2.0.21"\n', encoding="utf-8")

    classes_dir = app_dir / "routing-core" / "build" / "classes" / "kotlin" / "main"
    if with_classes:
        classes_dir.mkdir(parents=True)
        (classes_dir / "ChBuildMain.class").write_bytes(b"class")

    gradle_home = tmp_path / ".gradle"
    monkeypatch.setenv("GRADLE_USER_HOME", str(gradle_home))
    stdlib_jar = None
    if with_stdlib:
        jar_dir = (
            gradle_home / "caches" / "modules-2" / "files-2.1"
            / "org.jetbrains.kotlin" / "kotlin-stdlib" / "2.0.21" / "abc123"
        )
        jar_dir.mkdir(parents=True)
        stdlib_jar = jar_dir / "kotlin-stdlib-2.0.21.jar"
        stdlib_jar.write_bytes(b"jar")

    return {
        "app_dir": app_dir,
        "classes_dir": classes_dir,
        "gradle_home": gradle_home,
        "stdlib_jar": stdlib_jar,
    }


def test_parallel_runs_with_configured_concurrency(tmp_path, monkeypatch, capsys):
    env_info = _setup_direct_env(tmp_path, monkeypatch)
    src_dir = tmp_path / "src_zips"
    out_dir = tmp_path / "out_ch"
    prefs = ["Tottori", "Shimane", "Okayama", "Hiroshima"]
    for p in prefs:
        _make_pref_zip(src_dir, p)

    lock = threading.Lock()
    active = 0
    max_active = 0
    first_wave = threading.Barrier(2, timeout=2.0)
    wave_calls = 0
    cmds = []

    def fake_run(cmd, **kwargs):
        nonlocal active, max_active, wave_calls
        assert build_ch.CH_MAIN_CLASS in cmd
        assert "-Xmx5g" in cmd
        cp_val = cmd[cmd.index("-cp") + 1].split(os.pathsep)
        assert str(env_info["classes_dir"]) in cp_val
        assert str(env_info["stdlib_jar"]) in cp_val

        with lock:
            active += 1
            max_active = max(max_active, active)
            cmds.append(cmd)
            wave_calls += 1
            join_barrier = wave_calls <= 2

        try:
            if join_barrier:
                first_wave.wait()
            time.sleep(0.03)
            graph_path = Path(cmd[-2])
            out_path = Path(cmd[-1])
            _write_matching_ch(graph_path, out_path)
            return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")
        finally:
            with lock:
                active -= 1

    monkeypatch.setattr(build_ch.subprocess, "run", fake_run)

    rc = build_ch.main([
        "--only", ",".join(prefs),
        "--source-dir", str(src_dir),
        "--out", str(out_dir),
        "--jobs", "2",
    ])
    assert rc == 0
    assert max_active == 2
    assert len(cmds) == 4
    for p in prefs:
        assert (out_dir / f"{p}.graph.ch").is_file()
        assert not (out_dir / f"{p}.graph.ch.new").exists()

    captured = capsys.readouterr()
    assert "成功 4 件 / 失敗 0 件" in captured.out
    for p in prefs:
        assert f"[{p}] 完了 " in captured.out


def test_timeout_marks_failure_cleans_up_and_continues(tmp_path, monkeypatch, capsys):
    _setup_direct_env(tmp_path, monkeypatch)
    src_dir = tmp_path / "src_zips"
    out_dir = tmp_path / "out_ch"
    _make_pref_zip(src_dir, "Tottori")
    _make_pref_zip(src_dir, "Shimane")

    observed_timeouts = []

    def fake_run(cmd, **kwargs):
        observed_timeouts.append(kwargs.get("timeout"))
        graph_path = Path(cmd[-2])
        out_path = Path(cmd[-1])
        if graph_path.stem == "Tottori":
            # Simulate partial output left before timeout.
            out_path.write_bytes(b"partial")
            raise subprocess.TimeoutExpired(cmd, kwargs.get("timeout"))
        _write_matching_ch(graph_path, out_path)
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

    monkeypatch.setattr(build_ch.subprocess, "run", fake_run)

    rc = build_ch.main([
        "--only", "Tottori,Shimane",
        "--source-dir", str(src_dir),
        "--out", str(out_dir),
        "--jobs", "2",
        "--timeout-min", "1.5",
        "--heap", "4g",
    ])
    assert rc == 1
    assert observed_timeouts == [90.0, 90.0]
    assert not (out_dir / "Tottori.graph.ch").exists()
    assert not (out_dir / "Tottori.graph.ch.new").exists()
    assert (out_dir / "Shimane.graph.ch").is_file()
    # Temporary directories under work/ must be cleaned up.
    assert list((tmp_path / "work").iterdir()) == []

    captured = capsys.readouterr()
    assert "[Tottori] 時間切れ" in captured.err
    assert "[Shimane] 完了 " in captured.out
    assert "成功 1 件 / 失敗 1 件" in captured.out
    assert "失敗: Tottori" in captured.err


def test_existing_options_and_single_job_gradle_behavior(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(build_ch, "ROOT", tmp_path)
    src_dir = tmp_path / "packages"
    out_dir = tmp_path / "packages"
    chugoku = build_ch.resolve(None)
    for p in chugoku:
        _make_pref_zip(src_dir, p)

    # Dry run with default no-args covers all 5 Chugoku prefectures without running subprocess.
    called = []
    monkeypatch.setattr(build_ch.subprocess, "run", lambda *a, **kw: called.append(a))
    assert build_ch.main(["--dry-run"]) == 0
    assert called == []
    dry_out = capsys.readouterr().out
    for p in chugoku:
        assert f"[{p}] " in dry_out

    # Single-job mode (--jobs 1 default) invokes Gradle :routing-core:buildCh with default heap 6g.
    gradle_cmds = []

    def fake_gradle(cmd, **kwargs):
        gradle_cmds.append(cmd)
        graph_arg = next(a for a in cmd if a.startswith("-Pgraph=")).split("=", 1)[1]
        out_arg = next(a for a in cmd if a.startswith("-Pout=")).split("=", 1)[1]
        _write_matching_ch(Path(graph_arg), Path(out_arg))
        return subprocess.CompletedProcess(cmd, 0)

    monkeypatch.setattr(build_ch.subprocess, "run", fake_gradle)
    assert build_ch.main([
        "--only", "Tottori",
        "--source-dir", str(src_dir),
        "--out", str(out_dir),
    ]) == 0
    assert len(gradle_cmds) == 1
    assert ":routing-core:buildCh" in gradle_cmds[0]
    assert "-Pheap=6g" in gradle_cmds[0]

    # Second run without --force skips because fingerprint matches.
    gradle_cmds.clear()
    assert build_ch.main([
        "--only", "Tottori",
        "--source-dir", str(src_dir),
        "--out", str(out_dir),
    ]) == 0
    assert gradle_cmds == []
    assert "スキップ" in capsys.readouterr().out

    # --force and --heap 4g rebuilds.
    assert build_ch.main([
        "--only", "Tottori",
        "--source-dir", str(src_dir),
        "--out", str(out_dir),
        "--force",
        "--heap", "4g",
    ]) == 0
    assert len(gradle_cmds) == 1
    assert "-Pheap=4g" in gradle_cmds[0]


def test_missing_classes_dir_errors_and_does_not_run_java(tmp_path, monkeypatch, capsys):
    _setup_direct_env(tmp_path, monkeypatch, with_classes=False)
    src_dir = tmp_path / "src_zips"
    out_dir = tmp_path / "out_ch"
    _make_pref_zip(src_dir, "Tottori")

    cmds = []

    def fake_compile_fail(cmd, **kwargs):
        cmds.append(cmd)
        return subprocess.CompletedProcess(cmd, 1)

    monkeypatch.setattr(build_ch.subprocess, "run", fake_compile_fail)
    rc = build_ch.main([
        "--only", "Tottori",
        "--source-dir", str(src_dir),
        "--out", str(out_dir),
        "--jobs", "2",
    ])
    assert rc == 1
    assert len(cmds) == 1
    assert ":routing-core:compileKotlin" in cmds[0]
    assert not any(build_ch.CH_MAIN_CLASS in c for c in cmds)
    assert "エラー:" in capsys.readouterr().err

    # Even if compileKotlin returns 0 without creating classes_dir, still error cleanly.
    cmds.clear()
    monkeypatch.setattr(
        build_ch.subprocess, "run",
        lambda cmd, **kwargs: cmds.append(cmd) or subprocess.CompletedProcess(cmd, 0),
    )
    rc = build_ch.main([
        "--only", "Tottori",
        "--source-dir", str(src_dir),
        "--out", str(out_dir),
        "--jobs", "2",
    ])
    assert rc == 1
    assert len(cmds) == 1
    assert not any(build_ch.CH_MAIN_CLASS in c for c in cmds)
    assert "コンパイル済みクラスフォルダが見つかりません" in capsys.readouterr().err


def test_stale_classes_compiles_once_and_missing_stdlib_errors(tmp_path, monkeypatch, capsys):
    env_info = _setup_direct_env(tmp_path, monkeypatch, with_classes=True, with_stdlib=True)
    src_kt = env_info["app_dir"] / "routing-core" / "src" / "main" / "kotlin" / "Main.kt"
    src_kt.parent.mkdir(parents=True)
    src_kt.write_text("fun main() {}", encoding="utf-8")
    cls_file = env_info["classes_dir"] / "ChBuildMain.class"
    # Make source newer than class.
    old_time = time.time() - 100
    os.utime(cls_file, (old_time, old_time))

    src_dir = tmp_path / "src_zips"
    out_dir = tmp_path / "out_ch"
    _make_pref_zip(src_dir, "Tottori")
    _make_pref_zip(src_dir, "Shimane")

    cmds = []

    def fake_run(cmd, **kwargs):
        cmds.append(cmd)
        if ":routing-core:compileKotlin" in cmd:
            cls_file.write_bytes(b"recompiled")
            return subprocess.CompletedProcess(cmd, 0)
        _write_matching_ch(Path(cmd[-2]), Path(cmd[-1]))
        return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

    monkeypatch.setattr(build_ch.subprocess, "run", fake_run)
    rc = build_ch.main([
        "--only", "Tottori,Shimane",
        "--source-dir", str(src_dir),
        "--out", str(out_dir),
        "--jobs", "2",
    ])
    assert rc == 0
    compile_calls = [c for c in cmds if ":routing-core:compileKotlin" in c]
    java_calls = [c for c in cmds if build_ch.CH_MAIN_CLASS in c]
    assert len(compile_calls) == 1
    assert len(java_calls) == 2

    # Missing kotlin-stdlib jar stops with clear error before running java.
    _setup_direct_env(tmp_path / "no_jar", monkeypatch, with_classes=True, with_stdlib=False)
    monkeypatch.setattr(build_ch.subprocess, "run", lambda *a, **kw: pytest.fail("java should not run"))
    rc = build_ch.main([
        "--only", "Tottori",
        "--source-dir", str(src_dir),
        "--out", str(out_dir),
        "--jobs", "2",
    ])
    assert rc == 1
    assert "kotlin-stdlib" in capsys.readouterr().err
