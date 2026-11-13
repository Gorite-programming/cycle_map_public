"""Canonical prefecture names, JIS codes and selection for data generators."""
import re

# JIS order. Romaji spellings are also the boundary and ZIP member basenames.
_ROWS = """
Hokkaido 北海道
Aomori 青森県
Iwate 岩手県
Miyagi 宮城県
Akita 秋田県
Yamagata 山形県
Fukushima 福島県
Ibaraki 茨城県
Tochigi 栃木県
Gunma 群馬県
Saitama 埼玉県
Chiba 千葉県
Tokyo 東京都
Kanagawa 神奈川県
Niigata 新潟県
Toyama 富山県
Ishikawa 石川県
Fukui 福井県
Yamanashi 山梨県
Nagano 長野県
Gifu 岐阜県
Shizuoka 静岡県
Aichi 愛知県
Mie 三重県
Shiga 滋賀県
Kyoto 京都府
Osaka 大阪府
Hyogo 兵庫県
Nara 奈良県
Wakayama 和歌山県
Tottori 鳥取県
Shimane 島根県
Okayama 岡山県
Hiroshima 広島県
Yamaguchi 山口県
Tokushima 徳島県
Kagawa 香川県
Ehime 愛媛県
Kochi 高知県
Fukuoka 福岡県
Saga 佐賀県
Nagasaki 長崎県
Kumamoto 熊本県
Oita 大分県
Miyazaki 宮崎県
Kagoshima 鹿児島県
Okinawa 沖縄県
"""
PREFECTURES = [
    {"code": str(code), "name": name, "jp": jp, "tile": name.lower() + ".pmtiles"}
    for code, (name, jp) in enumerate(
        (row.split() for row in _ROWS.strip().splitlines()), start=1)
]
BY_NAME = {p["name"]: p for p in PREFECTURES}
PMTILES_FILENAMES = {p["name"]: p["tile"] for p in PREFECTURES}
REGIONS = {
    region: [p["name"] for p in PREFECTURES if first <= int(p["code"]) <= last]
    for region, first, last in (
        ("北海道", 1, 1), ("東北", 2, 7), ("関東", 8, 14), ("中部", 15, 23),
        ("近畿", 24, 30), ("中国", 31, 35), ("四国", 36, 39), ("九州沖縄", 40, 47))
}
DEFAULT_PREFECTURES = ["Hiroshima", "Okayama", "Yamaguchi", "Shimane", "Tottori"]


def resolve(names=None):
    """Expand names/regions/all, preserving input order and removing duplicates.

    Accept a string or iterable; comma and whitespace separators both work.
    None (an omitted --only) selects the historical five prefectures.
    """
    if names is None:
        return list(DEFAULT_PREFECTURES)
    tokens = [names] if isinstance(names, str) else list(names)
    aliases = {p[key].casefold(): p["name"] for p in PREFECTURES for key in ("name", "jp")}
    result = []
    for token in tokens:
        for part in filter(None, re.split(r"[,\s]+", token.strip())):
            if part.casefold() == "all":
                expanded = list(BY_NAME)
            elif part in REGIONS:
                expanded = REGIONS[part]
            elif part.casefold() in aliases:
                expanded = [aliases[part.casefold()]]
            else:
                raise ValueError(f"Unknown prefecture or region: {part!r}. Use Romaji, 日本語県名, "
                                 f"{', '.join(REGIONS)}, or all.")
            result.extend(name for name in expanded if name not in result)
    if not result:
        raise ValueError("No prefectures selected. Specify a prefecture, region, or all.")
    return result
