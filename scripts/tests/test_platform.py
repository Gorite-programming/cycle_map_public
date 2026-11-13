import os
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts import _platform as platform


@pytest.mark.parametrize("system", ["win32", "darwin", "linux"])
def test_gradle_command(monkeypatch, tmp_path, system):
    monkeypatch.setattr(sys, "platform", system)
    command = platform.gradle_command(tmp_path)
    if system == "win32":
        assert command == str(tmp_path.resolve() / "gradlew.bat")
    else:
        assert command == "./gradlew"


@pytest.mark.parametrize("name", ["pmtiles", "osmium"])
def test_tool_not_found_returns_none(monkeypatch, tmp_path, name):
    monkeypatch.setattr(platform, "ROOT", tmp_path)
    monkeypatch.setattr(platform.shutil, "which", lambda name: None)
    assert platform.find_tool(name) is None


def test_tool_path_has_priority(monkeypatch):
    monkeypatch.setattr(platform.shutil, "which", lambda name: "on-path")
    assert platform.find_tool("pmtiles") == "on-path"


@pytest.mark.parametrize("system,relative", [
    ("win32", "sandbox/vector_test/pipeline/pmtiles-win/pmtiles.exe"),
    ("darwin", "sandbox/vector_test/pipeline/pmtiles"),
    ("linux", "sandbox/vector_test/pipeline/pmtiles"),
])
def test_local_tool_discovery(monkeypatch, tmp_path, system, relative):
    monkeypatch.setattr(sys, "platform", system)
    monkeypatch.setattr(platform, "ROOT", tmp_path)
    monkeypatch.setattr(platform.shutil, "which", lambda name: None)
    tool = tmp_path / relative
    tool.parent.mkdir(parents=True)
    tool.touch()
    tool.chmod(0o755)
    assert platform.find_tool("pmtiles") == str(tool)


def test_jdk_and_child_environment_do_not_mutate_parent(monkeypatch, tmp_path):
    suffix = ".exe" if sys.platform == "win32" else ""
    (tmp_path / "bin").mkdir()
    for name in ("java", "javac"):
        tool = tmp_path / "bin" / (name + suffix)
        tool.touch()
        tool.chmod(0o755)
    monkeypatch.setenv("JAVA_HOME", str(tmp_path))
    before = dict(os.environ)
    assert platform.find_jdk() == tmp_path
    child = platform.java_environment({"PATH": "original", "JAVA_OPTS": "-Xmx6g"})
    assert child["JAVA_HOME"] == str(tmp_path)
    assert child["PATH"] == str(tmp_path / "bin") + os.pathsep + "original"
    assert child["JAVA_OPTS"] == "-Xmx6g"
    assert dict(os.environ) == before


def test_jdk_not_found(monkeypatch):
    monkeypatch.delenv("JAVA_HOME", raising=False)
    monkeypatch.setattr(Path, "is_file", lambda path: False)
    assert platform.find_jdk() is None


def test_invalid_java_home_falls_back_to_android_studio(monkeypatch, tmp_path):
    monkeypatch.setattr(sys, "platform", "win32")
    monkeypatch.setenv("JAVA_HOME", str(tmp_path / "missing"))
    monkeypatch.setenv("ProgramFiles", str(tmp_path))
    home = tmp_path / "Android/Android Studio/jbr"
    (home / "bin").mkdir(parents=True)
    for name in ("java.exe", "javac.exe"):
        (home / "bin" / name).touch()
        (home / "bin" / name).chmod(0o755)
    assert platform.find_jdk() == home
