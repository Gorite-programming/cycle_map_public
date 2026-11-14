"""build_prefecture_polygons.py: 県判定ポリゴンのリソースが境界 GeoJSON と合っていること。"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import build_prefecture_polygons as bpp  # noqa: E402
from scripts.prefectures import PREFECTURES  # noqa: E402


def test_num_trims_trailing_zeros_but_keeps_one_decimal():
    assert bpp.num(133.98810) == "133.9881"
    assert bpp.num(36.0) == "36.0"
    assert bpp.num(34.123456) == "34.12346"


def test_committed_resources_match_boundaries():
    """boundaries/ を更新したのに build_prefecture_polygons.py を回し忘れると落ちる。"""
    built = bpp.build()
    assert len(built) == 47 == len(PREFECTURES)
    stale = [i for i, text in built.items()
             if (bpp.OUT_DIR / ("%s.txt" % i)).read_text(encoding="utf-8") != text]
    assert not stale, "古いファイル (python3 scripts/build_prefecture_polygons.py を実行): %s" % stale


def test_every_ring_is_closed_and_in_japan():
    for pref_id, text in bpp.build().items():
        for polygon in text.split(";"):
            for ring in polygon.split("|"):
                points = [tuple(float(v) for v in p.split(",")) for p in ring.split(" ")]
                assert len(points) >= 4, pref_id
                assert points[0] == points[-1], "%s: リングが閉じていません" % pref_id
                for lon, lat in points:
                    assert 122.0 <= lon <= 154.5 and 20.0 <= lat <= 46.0, "%s: 範囲外 %s,%s" % (pref_id, lon, lat)
