"""連絡先・営業情報 (電話・Web・営業時間・車椅子) の正規化。

OSM のタグと Overture の列は表記がまちまち (全角、括弧、国際表記、複数番号) なので、
検索DBに入れる前に表示用の形へそろえる。解釈できないものは None にして捨てる (誤った情報を載せない)。

- 電話: 日本の番号は 082-208-2822 のような国内表記、海外の番号は国際表記。複数あれば先頭の1件。
- Web: http(s) の URL だけ。スキームの無い `example.com/path` は http:// を補う。
- 営業時間: 解釈はアプリの OpeningHoursParser が行うので、ここでは空白の整形と長さの上限だけ。
- 車椅子: yes / limited / no のどれか。
"""

import re
import unicodedata
from urllib.parse import urlparse

try:  # 電話番号の整形に使う。無い環境では整形できないので電話は取り込まない
    import phonenumbers
except ImportError:  # pragma: no cover - requirements.txt で導入する
    phonenumbers = None

MAX_HOURS_LEN = 200
MAX_URL_LEN = 300
_SPLIT = re.compile(r"[;,／/、，]|\s{2,}|\n")
_DOMAIN = re.compile(r"^(?:[a-z0-9-]+\.)+[a-z]{2,}(?:[:/?#].*)?$", re.IGNORECASE)


def normalize_phone(raw):
    """電話番号を表示用の文字列にする。取れなければ None。"""
    if not raw or phonenumbers is None:
        return None
    text = unicodedata.normalize("NFKC", str(raw)).strip()
    if not text:
        return None
    # 複数の番号が並んでいたら先頭の1件 (OSM は ; 区切りが多い)
    first = _SPLIT.split(text, maxsplit=1)[0].strip()
    first = re.sub(r"^(?:tel|phone|電話|ＴＥＬ|TEL)[:：\s]*", "", first, flags=re.IGNORECASE).strip()
    if not first:
        return None
    try:
        number = phonenumbers.parse(first, "JP")
    except phonenumbers.NumberParseException:
        return None
    if not phonenumbers.is_possible_number(number):
        return None
    if number.country_code == 81:
        return phonenumbers.format_number(number, phonenumbers.PhoneNumberFormat.NATIONAL)
    return phonenumbers.format_number(number, phonenumbers.PhoneNumberFormat.INTERNATIONAL)


def normalize_website(raw):
    """Web の URL を正規化する。http(s) 以外・体裁の怪しいものは None。"""
    if not raw:
        return None
    text = unicodedata.normalize("NFKC", str(raw)).strip()
    if not text or " " in text or len(text) > MAX_URL_LEN:
        return None
    first = _SPLIT.split(text, maxsplit=1)[0] if ";" in text else text
    if "://" not in first:
        if not _DOMAIN.match(first):
            return None
        first = "http://" + first
    parsed = urlparse(first)
    if parsed.scheme.lower() not in ("http", "https") or not parsed.netloc or "." not in parsed.netloc:
        return None
    return parsed.scheme.lower() + first[len(parsed.scheme):]  # スキームは小文字にそろえる


def normalize_hours(raw):
    """営業時間の文字列を整形する (解釈はしない)。"""
    if not raw:
        return None
    text = re.sub(r"\s+", " ", unicodedata.normalize("NFKC", str(raw))).strip()
    if not text or len(text) > MAX_HOURS_LEN:
        return None
    return text


def normalize_wheelchair(raw):
    """車椅子対応を yes / limited / no にする。"""
    if not raw:
        return None
    value = str(raw).strip().lower()
    if value in ("yes", "designated"):
        return "yes"
    if value in ("limited",):
        return "limited"
    if value == "no":
        return "no"
    return None
