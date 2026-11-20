import json
import re
import shutil
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts import patch_map_style as pms

CYCLING_DATA = ROOT / "application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CyclingData.kt"


def _load_style(theme: str) -> dict:
    path = pms.STYLE_DIR / f"protomaps_{theme}.json"
    return json.loads(path.read_text(encoding="utf-8"))


def _layers_by_id(style: dict) -> dict[str, dict]:
    return {layer["id"]: layer for layer in style["layers"]}


def test_covered_poi_kinds_sync_with_cycling_data_explicit_prefixes():
    """CyclingData.kt の PoiCategory に定義された具体的 key:value が除外対象に含まれている。"""
    source = CYCLING_DATA.read_text(encoding="utf-8")
    enum_body = source.split("enum class PoiCategory", 1)[1].split("companion object", 1)[0]
    prefixes = re.findall(r'"([a-z0-9_]+:[a-z0-9_]*)"', enum_body)
    explicit_kinds = {p.split(":", 1)[1] for p in prefixes if not p.endswith(":")}
    # 既定でオフのカテゴリ (駐車場・バー・観光案内・診療所・薬局・郵便局・銀行・学校) は、ピンが出ない既定設定で
    # 地図から消えないよう、意図的にタイル側に残す。
    kept_on_tiles = {
        "parking", "bar", "pub", "information", "clinic", "doctors", "dentist", "pharmacy", "chemist",
        "post_office", "bank", "atm", "school", "college", "university",
    }
    missing = explicit_kinds - pms.POI_KINDS_COVERED_BY_PINS - kept_on_tiles
    assert not missing, f"PoiCategory kinds missing from POI_KINDS_COVERED_BY_PINS: {sorted(missing)}"


@pytest.mark.parametrize("keep_kind", [
    "forest", "marina", "garden", "bench", "animal", "building",
    "books", "beauty", "electronics", "clothes", "theatre",
])
def test_non_pin_kinds_are_not_removed(keep_kind):
    """動的ピンが描かない景観・小さな店などは除外対象に入れない。"""
    assert keep_kind not in pms.POI_KINDS_COVERED_BY_PINS


@pytest.mark.parametrize("theme", pms.THEMES)
def test_style_pois_exclude_dynamic_pins_and_keep_scenic_and_small_shops(theme):
    pois = _layers_by_id(_load_style(theme))["pois"]
    kinds = pms._find_kind_literal_list(pois["filter"])
    assert kinds is not None
    assert not (set(kinds) & pms.POI_KINDS_COVERED_BY_PINS)
    for expected_kept in ("forest", "garden", "marina", "bench", "books", "clothes", "theatre"):
        assert expected_kept in kinds


@pytest.mark.parametrize("theme", pms.THEMES)
def test_shields_disable_overlap_and_prioritize_national_routes(theme):
    style = _load_style(theme)
    ids = [layer["id"] for layer in style["layers"]]
    shields = _layers_by_id(style)["roads_shields"]
    layout = shields["layout"]

    assert layout["icon-allow-overlap"] is False
    assert layout["text-allow-overlap"] is False
    assert layout["icon-padding"] >= 2
    assert layout["text-padding"] >= 2
    assert layout["symbol-sort-key"] == [
        "match", ["get", "network"], "JP:national", 0, "JP:prefectural", 1, 2,
    ]
    assert ids.index("roads_shields") > ids.index("pois")
    assert ids.index("roads_shields") > ids.index("roads_labels_major")
    assert ids.index("roads_shields") < ids.index("places_subplace")


def test_patch_pois_handles_nested_filter_and_rejects_missing():
    layer = {
        "filter": [
            "all",
            [">=", ["zoom"], 12],
            ["any", ["in", ["get", "kind"], ["literal", ["park", "forest", "station"]]]],
        ]
    }
    pms.patch_pois(layer)
    assert layer["filter"][2][1][2][1] == ["forest"]

    with pytest.raises(ValueError, match="kind"):
        pms.patch_pois({"filter": ["all", [">=", ["zoom"], 12]]})


@pytest.mark.parametrize("theme", pms.THEMES)
def test_patch_style_is_idempotent(monkeypatch, tmp_path, theme):
    src = pms.STYLE_DIR / f"protomaps_{theme}.json"
    dst = tmp_path / src.name
    shutil.copy2(src, dst)
    monkeypatch.setattr(pms, "STYLE_DIR", tmp_path)

    pms.patch_style(theme)
    first = dst.read_text(encoding="utf-8")
    pms.patch_style(theme)
    second = dst.read_text(encoding="utf-8")
    assert first == second
