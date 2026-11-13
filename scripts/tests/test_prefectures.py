"""Selection and CLI regression checks; never generate data or launch build tools."""
import importlib
from pathlib import Path
import sys

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts import prefectures as pref

HISTORICAL = {"Tottori", "Shimane", "Okayama", "Hiroshima", "Yamaguchi"}
GENERATORS = ["build_prefectures", "scripts.build_tiles", "scripts.build_terrain",
              "scripts.build_ch", "scripts.build_portals", "scripts.refresh_data"]


def test_definitions_match_all_boundaries_and_jis():
    assert len(pref.PREFECTURES) == 47
    assert sorted(int(p["code"]) for p in pref.PREFECTURES) == list(range(1, 48))
    assert len(pref.BY_NAME) == 47
    boundaries = {p.stem for p in (ROOT / "boundaries").glob("*.geojson")
                  if p.stem != "prefectures"}
    assert set(pref.BY_NAME) == boundaries
    assert len({p["jp"] for p in pref.PREFECTURES}) == 47
    assert {n for names in pref.REGIONS.values() for n in names} == boundaries
    assert sum(map(len, pref.REGIONS.values())) == 47


def test_defaults_and_legacy_pmtiles_names():
    assert pref.DEFAULT_PREFECTURES == ["Hiroshima", "Okayama", "Yamaguchi", "Shimane", "Tottori"]
    assert set(pref.resolve()) == HISTORICAL
    selected = pref.resolve()
    selected.clear()
    assert len(pref.resolve()) == 5
    builder = importlib.import_module("build_prefectures")
    assert {p["name"] for p in builder.select_only(None)} == HISTORICAL
    for name in HISTORICAL:
        assert builder.PMTILES_FILENAMES[name] == name.lower() + ".pmtiles"
    assert builder.PMTILES_FILENAMES == pref.PMTILES_FILENAMES


@pytest.mark.parametrize("names,expected", [
    ("tokyo,OSAKA", ["Tokyo", "Osaka"]),
    (["tokyo", "OSAKA,Tokyo"], ["Tokyo", "Osaka"]),
    ("東京都 広島県", ["Tokyo", "Hiroshima"]),
    ("北海道 東北", ["Hokkaido", "Aomori", "Iwate", "Miyagi", "Akita", "Yamagata", "Fukushima"]),
    ("九州沖縄", ["Fukuoka", "Saga", "Nagasaki", "Kumamoto", "Oita", "Miyazaki", "Kagoshima", "Okinawa"]),
])
def test_resolve(names, expected):
    assert pref.resolve(names) == expected


def test_all_and_regions():
    assert pref.resolve("ALL") == list(pref.BY_NAME)
    assert set(pref.resolve("中国")) == HISTORICAL
    for region, names in pref.REGIONS.items():
        assert pref.resolve(region) == names


@pytest.mark.parametrize("names", ["Atlantis", "Tokyo,typo", "all,typo", "", [], [" , "]])
def test_unknown_or_empty_selection_is_an_error(names):
    with pytest.raises(ValueError, match="Unknown prefecture or region|No prefectures selected"):
        pref.resolve(names)


def test_portal_selection_preserves_touching_pairs_and_adds_within():
    from scripts.build_portals import select_pairs
    assert len(select_pairs()) == 10
    assert {p for pair in select_pairs() for p in pair} == HISTORICAL
    only = ["Hiroshima", "Okayama", "Hyogo"]
    within = select_pairs(only, within=True)
    assert len(within) == 3
    assert all(set(pair) <= set(only) for pair in within)
    touching = select_pairs(only)
    assert len(touching) == 135
    assert all(set(pair) & set(only) for pair in touching)
    assert len(select_pairs("all", within=True)) == 1081
    assert select_pairs("Tokyo", within=True) == []


@pytest.mark.parametrize("module_name", GENERATORS)
def test_help_without_tools_or_generation(monkeypatch, module_name, capsys):
    module = importlib.import_module(module_name)
    monkeypatch.setattr(sys, "argv", [module_name, "--help"])
    with pytest.raises(SystemExit) as exc:
        module.main()
    assert exc.value.code == 0
    assert "--only" in capsys.readouterr().out


@pytest.mark.parametrize("module_name", GENERATORS)
@pytest.mark.parametrize("selection", [None, ["Tokyo", "Osaka"], ["四国"], ["all"]])
def test_dry_run_selects_defaults_and_national_names_without_effects(
        monkeypatch, module_name, selection, capsys):
    import subprocess
    import urllib.request
    module = importlib.import_module(module_name)
    def forbidden(*args, **kwargs):
        pytest.fail("dry-run attempted a write, subprocess, or network request")
    monkeypatch.setattr(subprocess, "Popen", forbidden)
    monkeypatch.setattr(urllib.request, "urlopen", forbidden)
    monkeypatch.setattr(Path, "mkdir", forbidden)
    monkeypatch.setattr(Path, "open", forbidden)
    if module_name == "scripts.build_terrain":
        monkeypatch.setattr(module, "dem_tiles_for", lambda *args: [])
    if module_name == "scripts.build_tiles":
        monkeypatch.setattr(module, "boundary_bbox", lambda name: (1, 2, 3, 4))
    argv = [module_name, "--dry-run"]
    if selection:
        argv += ["--only", *selection]
    monkeypatch.setattr(sys, "argv", argv)
    assert module.main() == 0
    output = capsys.readouterr().out
    for name in pref.resolve(selection):
        assert name in output
    if selection is None:
        assert "Tokyo" not in output
    if module_name == "scripts.refresh_data":
        expected = "chugoku-latest.osm.pbf" if selection is None else "japan-latest.osm.pbf"
        assert expected in output


def test_refresh_passes_selection_to_portals(monkeypatch):
    from scripts import refresh_data as rd
    calls = []
    monkeypatch.setattr(rd, "run_cmd", lambda cmd, log: calls.append(cmd))
    rd.build_portals("unused")
    assert "--only" not in calls[-1]
    rd.build_portals("unused", ["Hyogo", "Osaka"])
    assert calls[-1][-3:] == ["--only", "Hyogo,Osaka", "--within"]
