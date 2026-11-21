"""CMHZ v1 の抽出・読み書きのテストと、Kotlin 用見本ファイルの再生成。"""

from pathlib import Path
import struct
import zlib

import pytest

from scripts import build_hazards as bh


SAMPLE_PATH = bh.ROOT / "application/app/src/test/resources/hazards_small.bin"
SAMPLE = bh.Hazards(
    crossings=(
        (343901000, 1324600000),
        (343910000, 1324610000),
        (343920000, 1324620000),
    ),
    bridges=(
        bh.Bridge(((343903000, 1324600000), (343906000, 1324600000), (343909000, 1324600000)), "見本橋"),
        bh.Bridge(((343910000, 1324613000), (343910000, 1324618000))),
        bh.Bridge(((343923000, 1324620000), (343926000, 1324620000), (343927000, 1324622000))),
    ),
)


def regenerate_fixture(path: Path = SAMPLE_PATH) -> None:
    bh.write_hazards(path, SAMPLE, force=True)


def test_round_trip(tmp_path):
    path = tmp_path / "hazards.bin"
    assert bh.write_hazards(path, SAMPLE)
    assert bh.read_hazards(path) == SAMPLE
    assert path.read_bytes()[:20] == struct.pack("<4sHHIII", b"CMHZ", 1, 0, 3, 3, 10)


def test_fixture_is_reproducible(tmp_path):
    path = tmp_path / "hazards_small.bin"
    regenerate_fixture(path)
    assert path.read_bytes() == SAMPLE_PATH.read_bytes()
    assert bh.read_hazards(SAMPLE_PATH) == SAMPLE


def with_crc(payload):
    return payload + struct.pack("<I", zlib.crc32(payload))


@pytest.mark.parametrize("damage", [
    lambda data: data[:-1] + bytes([data[-1] ^ 1]),
    lambda data: data[:19],
    lambda data: with_crc(b"FAIL" + data[4:-4]),
    lambda data: with_crc(data[:4] + b"\x02\x00" + data[6:-4]),
    lambda data: with_crc(data[:48] + b"\xff\xff" + data[50:-4]),
    lambda data: with_crc(data[:44] + struct.pack("<I", 1) + data[48:-4]),
    lambda data: with_crc(data[:-5] + b"x"),
], ids=["crc", "truncated", "magic", "version", "point count", "name offset", "unterminated name"])
def test_read_rejects_corruption(tmp_path, damage):
    path = tmp_path / "bad.bin"
    path.write_bytes(damage(SAMPLE_PATH.read_bytes()))
    with pytest.raises(ValueError):
        bh.read_hazards(path)


def test_preserves_existing_output_until_a_forced_rebuild_succeeds(tmp_path, monkeypatch):
    path = tmp_path / "hazards.bin"
    path.write_bytes(b"existing output")
    assert bh.main(["--input", str(tmp_path / "missing.pbf"), "--output", str(path)]) == 0
    assert path.read_bytes() == b"existing output"

    original_read = bh.read_hazards

    def corrupt_temp(temp):
        temp.write_bytes(b"truncated")
        return original_read(temp)

    with monkeypatch.context() as patch:
        patch.setattr(bh, "read_hazards", corrupt_temp)
        with pytest.raises(ValueError):
            bh.write_hazards(path, SAMPLE, force=True)
    assert path.read_bytes() == b"existing output"
    assert list(tmp_path.iterdir()) == [path]
    assert bh.write_hazards(path, SAMPLE, force=True)
    assert bh.read_hazards(path) == SAMPLE


@pytest.mark.parametrize(("railway", "expected"), [("level_crossing", True), ("crossing", False), (None, False)])
def test_crossing_tags(railway, expected):
    assert bh.is_crossing({"railway": railway}) is expected


@pytest.mark.parametrize(("tags", "expected"), [
    ({"bridge": "yes", "highway": "secondary"}, True),
    ({"bridge": "viaduct", "highway": "primary_link"}, True),
    ({"bridge": "yes", "highway": "motorway"}, False),
    ({"bridge": "yes", "highway": "motorway_link"}, False),
    ({"bridge": "yes", "highway": "footway"}, False),
    ({"bridge": "no", "highway": "secondary"}, False),
    ({"highway": "secondary"}, False),
    ({"bridge": "yes", "highway": "secondary", "tunnel": "culvert"}, False),
])
def test_bridge_tags(tags, expected):
    assert bh.is_bridge(tags) is expected


def test_bridge_length_thresholds_and_name_precedence():
    start = (343900000, 1324600000)
    short = (start, (343901000, 1324600000))  # 約 11 m
    medium = (start, (343901500, 1324600000))  # 約 17 m
    long = (*medium, (343902900, 1324600000))  # 隣接点の合計で約 32 m
    assert bh.bridge_from_way({"name": "見本橋"}, short) is None
    assert bh.bridge_from_way({}, medium) is None
    assert bh.bridge_from_way({"name:ja": "別名"}, medium) is None
    assert bh.bridge_from_way({"bridge:name": "見本橋"}, medium) == bh.Bridge(medium, "見本橋")
    assert bh.bridge_from_way({"name": "見本橋"}, medium) == bh.Bridge(medium, "見本橋")
    # bridge:name が name より優先される
    assert bh.bridge_from_way({"name": "新岩国停車場線", "bridge:name": "次点橋"}, medium) == bh.Bridge(medium, "次点橋")
    assert bh.bridge_from_way({}, long) == bh.Bridge(long)


@pytest.mark.parametrize(("tags", "expected"), [
    ({"name": "錦帯橋"}, "錦帯橋"),
    ({"name": "広島大橋"}, "広島大橋"),
    ({"name": "Rainbow Bridge"}, "Rainbow Bridge"),
    ({"name": "新岩国停車場線"}, None),   # 道路の名前は橋の名前として出さない
    ({"name": "国道2号"}, None),
    ({"name": "橋本通り"}, None),         # 途中に「橋」を含むだけ
    ({"name": "橋本通り", "bridge:name": "八幡橋"}, "八幡橋"),
    ({}, None),
])
def test_bridge_name_only_when_it_looks_like_a_bridge(tags, expected):
    assert bh.bridge_name(tags) == expected


def test_deduplicate_crossings_across_cell_boundary():
    first = (459999999, 1324603999)
    near = (459999999, 1324604009)
    farther = (459999999, 1324608000)
    assert bh.deduplicate_crossings((first, near, farther, first)) == (first, farther)


def test_extract_resolves_way_nodes_and_filters_bbox(tmp_path):
    osmium = pytest.importorskip("osmium")
    path = tmp_path / "input.osm.pbf"
    with osmium.SimpleWriter(path) as writer:
        writer.add_node(osmium.osm.mutable.Node(id=1, location=(132.46, 34.39), tags={"railway": "level_crossing"}))
        writer.add_node(osmium.osm.mutable.Node(id=2, location=(132.46, 34.3901), tags={"railway": "level_crossing"}))
        writer.add_node(osmium.osm.mutable.Node(id=3, location=(132.46, 34.391)))
        writer.add_node(osmium.osm.mutable.Node(id=4, location=(132.46, 34.5), tags={"railway": "level_crossing"}))
        writer.add_way(osmium.osm.mutable.Way(id=1, nodes=[2, 3], tags={"highway": "secondary", "bridge": "yes"}))
        writer.add_way(osmium.osm.mutable.Way(id=2, nodes=[2, 4], tags={"highway": "secondary", "bridge": "yes"}))
        writer.add_way(osmium.osm.mutable.Way(id=3, nodes=[2, 99], tags={"highway": "secondary", "bridge": "yes"}))

    hazards = bh.extract_hazards(path, bh.parse_bbox("132.45,34.39,132.47,34.4"))
    assert hazards == bh.Hazards(
        ((343900000, 1324600000),),
        (bh.Bridge(((343901000, 1324600000), (343910000, 1324600000))),),
    )


if __name__ == "__main__":
    regenerate_fixture()
