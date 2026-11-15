"""PMTiles lookup and packaging regressions; use only tiny temporary files."""
import logging
import os
from pathlib import Path
import sys
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import build_prefectures as bp  # noqa: E402


@pytest.fixture
def isolated_builder(tmp_path, monkeypatch):
    monkeypatch.chdir(tmp_path)
    for attr, relative in (
        ("OUTPUT_DIR", "packages"),
        ("WORK_DIR", "work"),
        ("LOG_DIR", "logs"),
        ("BOUNDARY_DIR", "boundaries"),
        ("DATA_TOOL_OUT_DIR", "sandbox/data-tool/out"),
    ):
        monkeypatch.setattr(bp, attr, tmp_path / relative)
    return tmp_path


@pytest.mark.parametrize("skip_existing_zip", [False, True])
@pytest.mark.parametrize("sandbox_dir", ["sandbox/data-tool/out", "sandbox/vector_test/data"])
@pytest.mark.parametrize("package_tiles", [None, b"PMTiles\x03current", b"", b"invalid"])
def test_process_only_uses_existing_package_tiles(
        isolated_builder, monkeypatch, caplog, skip_existing_zip, sandbox_dir, package_tiles):
    root = isolated_builder
    name = "Okinawa"
    stale = root / sandbox_dir / "okinawa.pmtiles"
    stale.parent.mkdir(parents=True, exist_ok=True)
    stale.write_bytes(b"PMTiles\x03stale")
    os.utime(stale, (1, 1))

    target = bp.OUTPUT_DIR / "okinawa.pmtiles"
    if package_tiles is not None:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(package_tiles)
        # Even a newer sandbox file must not overwrite the published archive.
        os.utime(target, (0, 0))
        original_stat = target.stat()
    valid_tiles = package_tiles is not None and package_tiles.startswith(b"PMTiles\x03")
    assert bp.expected_pmtiles_path(name) == (target if valid_tiles else None)

    inputs = root / "inputs"
    inputs.mkdir()
    db, graph, idx = [inputs / f"{name}{suffix}" for suffix in (".search.db", ".graph", ".graph.idx")]
    for path in (db, graph, idx):
        path.write_bytes(b"dummy")
    out = bp.OUTPUT_DIR / f"{name}.zip"
    if skip_existing_zip:
        bp.make_zip(name, db, graph, idx, out, bp.generate_manifest(name, {}))
        original_zip = out.read_bytes()

        def forbidden(*args, **kwargs):
            pytest.fail("existing ZIP must skip data generation")

        monkeypatch.setattr(bp, "run_live", forbidden)
    else:
        # Stub all external data operations; exercise the actual ZIP creation.
        bp.DATA_TOOL_OUT_DIR.mkdir(parents=True, exist_ok=True)
        (bp.DATA_TOOL_OUT_DIR / "okinawa.search.db").write_bytes(b"dummy db")
        monkeypatch.setattr(bp, "verify_search_db", lambda path: {})
        monkeypatch.setattr(bp, "osm_stats", lambda path: {})
        monkeypatch.setattr(bp, "java_environment", lambda env: env)
        monkeypatch.setattr(bp, "find_tool", lambda tool: tool)

        def fake_run_live(cmd, log_file, env=None):
            if "extract" in cmd:
                assert cmd[cmd.index("--strategy") + 1] == "complete_ways"
                Path(cmd[cmd.index("--output") + 1]).write_bytes(b"dummy pbf")
            else:
                assert cmd[1:3] == ["--bbox", "none"]
                Path(cmd[-1]).write_bytes(b"dummy graph")
                Path(cmd[-1] + ".idx").write_bytes(b"dummy idx")
            log_file.parent.mkdir(parents=True, exist_ok=True)
            log_file.write_text("", encoding="utf-8")
            return 0

        monkeypatch.setattr(bp, "run_live", fake_run_live)

    with caplog.at_level(logging.INFO, logger=bp.LOG.name):
        success, _ = bp.process({"name": name}, force=False, keep_work=True)

    assert success
    assert bp.valid_zip(out, name)
    with zipfile.ZipFile(out) as archive:
        assert set(archive.namelist()) == {
            "Okinawa.search.db", "Okinawa.graph", "Okinawa.graph.idx", "manifest.json", "LICENSE.txt",
        }
    if skip_existing_zip:
        assert out.read_bytes() == original_zip
    assert ("offline vector tiles sidecar:" in caplog.text) == valid_tiles
    assert stale.read_bytes() == b"PMTiles\x03stale"
    if package_tiles is None:
        assert not target.exists()
    else:
        assert target.read_bytes() == package_tiles
        assert target.stat().st_mtime_ns == original_stat.st_mtime_ns
    assert not target.with_suffix(".pmtiles.partial").exists()
