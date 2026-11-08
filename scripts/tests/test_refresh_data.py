"""scripts/refresh_data.py の検証・記録まわりのテスト (重い処理は呼ばない)。"""
import importlib.util
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("refresh_data", ROOT / "scripts" / "refresh_data.py")
rd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rd)


def test_manifest_keeps_prefectures_not_in_this_run(tmp_path, monkeypatch):
    monkeypatch.setattr(rd, "PACKAGES", tmp_path)
    (tmp_path / "data_manifest.json").write_text(json.dumps(
        {"prefectures": {p: {"promoted": True, "facts": {"x": p}} for p in rd.PREFECTURES}}))
    (tmp_path / "Tottori.zip").write_bytes(b"z")
    rd.write_manifest({"OSM": "t"}, {
        "Tottori": {"promoted": True, "facts": {"new": 1}},
        "Shimane": {"promoted": False, "facts": {}},
    })
    m = json.loads((tmp_path / "data_manifest.json").read_text())
    assert list(m["prefectures"]) == rd.PREFECTURES
    assert m["prefectures"]["Tottori"]["facts"] == {"new": 1}
    assert "zip_sha256" in m["prefectures"]["Tottori"]
    assert m["prefectures"]["Shimane"]["last_attempt_failed"] is True
    assert m["prefectures"]["Okayama"]["facts"] == {"x": "Okayama"}
    # manifest 以外のファイルを書き換えない
    assert (tmp_path / "Tottori.zip").read_bytes() == b"z"


def test_parse_bench_baseline_reads_reach_and_median_distance():
    text = """### Hiroshima.graph (OD 60 組)

| 条件 | 到達率 | 範囲外再試行 | 中央値 時間ms | p95 時間ms | 中央値 展開ノード | 中央値 経路距離 | 基準比 距離増加(中央値) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 基準 (おすすめ) | 95.0% | 14 | 60.7 | 219.9 | 89381 | 37636.0m | +0.00% |
| 平坦優先 | 95.0% | 14 | 80.1 | 351.5 | 132628 | 39530.4m | +1.68% |
"""
    assert rd.parse_bench_baseline(text) == (95.0, 37636.0)
