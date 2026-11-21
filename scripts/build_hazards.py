#!/usr/bin/env python3
"""OSM から踏切・橋を抽出し、注意地点データ (hazards.bin v1) を生成する。"""

import argparse
from collections import defaultdict
from dataclasses import dataclass
from itertools import pairwise
import math
from pathlib import Path
import struct
import sys
from tempfile import NamedTemporaryFile
import time
import zlib


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT = ROOT / "data/japan-latest.osm.pbf"
DEFAULT_OUTPUT = ROOT / "packages/hazards.bin"
JAPAN_BBOX = (122, 20, 154, 46)  # minLon, minLat, maxLon, maxLat
E7 = 10_000_000
Point = tuple[int, int]  # latE7, lonE7
Bbox = tuple[float, float, float, float]

BRIDGE_HIGHWAYS = frozenset({
    "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
    "living_street", "service", "track", "cycleway", "path",
})
BRIDGE_HIGHWAYS |= {f"{highway}_link" for highway in BRIDGE_HIGHWAYS}

# 国外を除いた緯度帯では、0.0004° は東西方向でも 30 m より長い。
CROSSING_CELL_E7 = 4000
CROSSING_DISTANCE_M = 30

MAGIC = b"CMHZ"
VERSION = 1
NO_NAME = 0xFFFFFFFF
HEADER = struct.Struct("<4sHHIII")
POINT = struct.Struct("<ii")
BRIDGE_HEADER = struct.Struct("<IHH")
CRC = struct.Struct("<I")


@dataclass(frozen=True)
class Bridge:
    points: tuple[Point, ...]
    name: str | None = None


@dataclass(frozen=True)
class Hazards:
    crossings: tuple[Point, ...]
    bridges: tuple[Bridge, ...]


def is_crossing(tags) -> bool:
    return tags.get("railway") == "level_crossing"


def is_bridge(tags) -> bool:
    bridge = tags.get("bridge")
    return (bool(bridge) and bridge != "no"
            and tags.get("highway") in BRIDGE_HIGHWAYS and tags.get("tunnel") != "culvert")


def distance_m(first: Point, second: Point) -> float:
    radians_per_e7 = math.pi / (180 * E7)
    dlat = (second[0] - first[0]) * radians_per_e7
    dlon = (second[1] - first[1]) * radians_per_e7
    a = (math.sin(dlat / 2) ** 2 + math.cos(first[0] * radians_per_e7)
         * math.cos(second[0] * radians_per_e7) * math.sin(dlon / 2) ** 2)
    return 2 * 6_371_000 * math.asin(math.sqrt(min(1, a)))


BRIDGE_NAME_SUFFIXES = ("橋", "ブリッジ", "Bridge", "bridge")


def bridge_name(tags) -> str | None:
    """橋の名前。`bridge:name` を優先する。`name` は、橋らしい名前 (末尾が「橋」など) のときだけ使う。

    橋は道路の名前 (「新岩国停車場線」「国道 2 号」など) を `name` に持つことが多く、そのまま出すと
    「橋 (国道 2 号)」のように紛らわしいため。「橋本通り」のように途中に「橋」を含むだけの名前も除く。
    """
    named = tags.get("bridge:name")
    if named:
        return named
    name = tags.get("name")
    if name and name.endswith(BRIDGE_NAME_SUFFIXES):
        return name
    return None


def bridge_from_way(tags, points: tuple[Point, ...]) -> Bridge | None:
    if len(points) < 2:
        return None
    name = bridge_name(tags)
    minimum = 15 if name else 30
    if sum(distance_m(a, b) for a, b in pairwise(points)) < minimum:
        return None
    return Bridge(points, name)


def deduplicate_crossings(points) -> tuple[Point, ...]:
    kept = []
    grid = defaultdict(list)
    for point in points:
        row, col = (coord // CROSSING_CELL_E7 for coord in point)
        if any(
            distance_m(point, near) <= CROSSING_DISTANCE_M
            for dy in (-1, 0, 1)
            for dx in (-1, 0, 1)
            for near in grid.get((row + dy, col + dx), ())
        ):
            continue
        grid[row, col].append(point)
        kept.append(point)
    return tuple(kept)


def extract_hazards(path: Path, bbox: Bbox = JAPAN_BBOX) -> Hazards:
    import osmium

    # bbox は国内範囲を狭めるだけ。浮動小数の境界は座標の E7 単位で比較する。
    west, south, east, north = (
        max(bbox[0], JAPAN_BBOX[0]) * E7, max(bbox[1], JAPAN_BBOX[1]) * E7,
        min(bbox[2], JAPAN_BBOX[2]) * E7, min(bbox[3], JAPAN_BBOX[3]) * E7,
    )
    crossings = []
    bridges = []

    def point_in_bounds(location) -> Point | None:
        if location.valid() and south <= location.y <= north and west <= location.x <= east:
            return location.y, location.x
        return None

    class Handler(osmium.SimpleHandler):
        def node(self, node):
            if is_crossing(node.tags):
                point = point_in_bounds(node.location)
                if point is not None:
                    crossings.append(point)

        def way(self, way):
            if not is_bridge(way.tags):
                return
            points = []
            for node in way.nodes:
                point = point_in_bounds(node.location)
                if point is None:
                    return  # 範囲外や座標の欠損があれば、ウェイを途中で切らない。
                points.append(point)
            bridge = bridge_from_way(way.tags, tuple(points))
            if bridge is not None:
                bridges.append(bridge)

    # ノード座標のキャッシュには全ノードを渡し、Python コールバックだけを絞る。
    Handler().apply_file(path, locations=True, filters=[osmium.filter.KeyFilter("railway", "bridge")])
    return Hazards(deduplicate_crossings(crossings), tuple(bridges))


def _read_points(data, offset: int, count: int) -> tuple[Point, ...]:
    return tuple(POINT.iter_unpack(data[offset:offset + count * POINT.size]))


def read_hazards(path: Path) -> Hazards:
    """CMHZ v1 を検証して復元する。不正な形式・CRC は ValueError。"""
    data = memoryview(Path(path).read_bytes())
    if len(data) < HEADER.size + CRC.size:
        raise ValueError("hazards header or CRC is truncated")

    magic, version, reserved, crossing_count, bridge_count, name_bytes = HEADER.unpack_from(data)
    if magic != MAGIC or version != VERSION or reserved != 0:
        raise ValueError("invalid hazards magic, version or reserved field")

    payload_end = len(data) - CRC.size
    names_start = payload_end - name_bytes
    bridges_start = HEADER.size + crossing_count * POINT.size
    if names_start - bridges_start < bridge_count * (BRIDGE_HEADER.size + 2 * POINT.size):
        raise ValueError("invalid hazards section lengths")
    if zlib.crc32(data[:payload_end]) != CRC.unpack_from(data, payload_end)[0]:
        raise ValueError("hazards CRC mismatch")

    names = {}
    name_table = data[names_start:payload_end].tobytes()
    offset = 0
    while offset < len(name_table):
        end = name_table.find(b"\0", offset)
        if end <= offset:
            raise ValueError("invalid hazards name table")
        names[offset] = name_table[offset:end].decode("utf-8")
        offset = end + 1

    crossings = _read_points(data, HEADER.size, crossing_count)
    bridges = []
    offset = bridges_start
    for _ in range(bridge_count):
        if offset + BRIDGE_HEADER.size > names_start:
            raise ValueError("hazards bridge header is truncated")
        name_offset, point_count, reserved = BRIDGE_HEADER.unpack_from(data, offset)
        offset += BRIDGE_HEADER.size
        points_end = offset + point_count * POINT.size
        if reserved != 0 or point_count < 2 or points_end > names_start:
            raise ValueError("invalid hazards bridge geometry")
        if name_offset != NO_NAME and name_offset not in names:
            raise ValueError("invalid hazards bridge name offset")
        bridges.append(Bridge(_read_points(data, offset, point_count), names.get(name_offset)))
        offset = points_end
    if offset != names_start:
        raise ValueError("unexpected data before hazards name table")
    return Hazards(crossings, tuple(bridges))


def write_hazards(path: Path, hazards: Hazards, *, force: bool = False) -> bool:
    """検証済みファイルを公開する。出力が既にあって force がなければ False。"""
    path = Path(path)
    if path.exists() and not force:
        return False

    names = bytearray()
    name_offsets = {}
    records = bytearray()
    for point in hazards.crossings:
        records.extend(POINT.pack(*point))
    for bridge in hazards.bridges:
        name_offset = NO_NAME
        if bridge.name:
            if bridge.name not in name_offsets:
                name_offsets[bridge.name] = len(names)
                names.extend(bridge.name.encode("utf-8") + b"\0")
            name_offset = name_offsets[bridge.name]
        records.extend(BRIDGE_HEADER.pack(name_offset, len(bridge.points), 0))
        for point in bridge.points:
            records.extend(POINT.pack(*point))

    header = HEADER.pack(MAGIC, VERSION, 0, len(hazards.crossings), len(hazards.bridges), len(names))
    checksum = zlib.crc32(names, zlib.crc32(records, zlib.crc32(header)))
    path.parent.mkdir(parents=True, exist_ok=True)
    file = NamedTemporaryFile(dir=path.parent, prefix=f"{path.name}.", suffix=".tmp", delete=False)
    temp = Path(file.name)
    try:
        with file:
            file.write(header)
            file.write(records)
            file.write(names)
            file.write(CRC.pack(checksum))
        if read_hazards(temp) != hazards:
            raise ValueError("hazards contents differ after reading temporary file")
        if path.exists() and not force:
            return False
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)
    return True


def parse_bbox(value: str) -> Bbox:
    try:
        west, south, east, north = map(float, value.split(","))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("use minLon,minLat,maxLon,maxLat") from exc
    if not (-180 <= west < east <= 180 and -90 <= south < north <= 90):
        raise argparse.ArgumentTypeError("invalid bbox longitude or latitude bounds")
    return west, south, east, north


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT, help="OSM PBF (default: data/japan-latest.osm.pbf)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="output file (default: packages/hazards.bin)")
    parser.add_argument("--force", action="store_true", help="replace an existing output file")
    parser.add_argument("--bbox", type=parse_bbox, default=JAPAN_BBOX, help="limit to minLon,minLat,maxLon,maxLat")
    args = parser.parse_args(argv)
    if args.output.exists() and not args.force:
        print(f"SKIP: output already exists: {args.output}")
        return 0

    started = time.perf_counter()
    try:
        hazards = extract_hazards(args.input, args.bbox)
        if not write_hazards(args.output, hazards, force=args.force):
            print(f"SKIP: output already exists: {args.output}")
            return 0
    except ImportError as exc:
        print(f"ERROR: use the repository venv with pyosmium: {exc}", file=sys.stderr)
        return 1
    except (OSError, RuntimeError, ValueError, struct.error) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(f"{args.output}: {len(hazards.crossings):,} crossings, {len(hazards.bridges):,} bridges, "
          f"{args.output.stat().st_size:,} bytes, {time.perf_counter() - started:.2f} s")
    return 0


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(errors="replace")
    sys.exit(main())
