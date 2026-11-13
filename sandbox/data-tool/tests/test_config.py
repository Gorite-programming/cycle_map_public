import pytest
from tools.core import config


def test_47_prefectures_defined():
    # 全国対応で 47 都道府県に拡張済み (以前は中国 5 県のみ)。
    assert len(config.PREFECTURES) == 47
    assert {"tottori", "shimane", "okayama", "hiroshima", "yamaguchi"} <= set(config.PREFECTURES)


def test_search_db_names_lowercase_id():
    for pref_id, pref in config.PREFECTURES.items():
        assert pref["search_db"] == "%s.search.db" % pref_id


def test_bbox_order():
    for pref in config.PREFECTURES.values():
        lon_min, lat_min, lon_max, lat_max = pref["bbox"]
        assert lon_min < lon_max and lat_min < lat_max


def test_source_priority_kokudo_suffix():
    assert config.source_priority("kokudo:medical") == 50
    assert config.source_priority("manual") == 100
    assert config.source_priority("osm") == 30
    assert config.source_priority("overture") == 20
    assert config.source_priority("csv") == 40


def test_prefecture_for_point_hiroshima_city():
    # 広島市中心部は広島と判定されること。県境ポリゴン (git 管理外の data/) が無い環境 (CI など) では
    # bbox だけの判定になり、重なる島根と区別できないので飛ばす
    if not config.load_boundary_rings("hiroshima"):
        pytest.skip("県境ポリゴンが無い (data/ は git 管理外)")
    assert config.prefecture_for_point(34.385, 132.455) == "hiroshima"


def test_prefecture_for_point_outside():
    # 47 都道府県化で東京都の bbox が伊豆・小笠原諸島まで広がり、旧来の座標 (東京都心)
    # はもう「県の範囲外」ではなくなった。ソウル (日本国外) を使う。
    assert config.prefecture_for_point(37.5665, 126.9780) is None
