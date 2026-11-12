#!/usr/bin/env python3
"""県またぎの通り口ファイル (<A>__<B>.portals) の生成。

`packages/<県>.zip` (または --source-dir の ZIP) の graph 同士で共通する OSM ノード (通り口) を
`./gradlew :routing-core:buildPortals` で計算し、`packages/<A>__<B>.portals` に置く (A, B は県名の辞書順)。
- 対象は 5 県のすべての組。隣り合う組かどうかは計算して決める (通り口が 0 件の組はファイルを作らない)。
- ZIP (5 ファイル構成) には入れず、CH と同じく別成果物にする。アプリは無くても従来の ID 照合で動く。
- ヘッダにある 2 つの graph の識別 (サイズ + 先頭/末尾 1MB の CRC32) が今の graph と一致する
  ファイルはスキップする (--force で作り直し)。0 件だった組は `portals_empty.json` に識別を
  控え、graph が変わるまで計算し直さない。
- graph を作り直したら再実行する (古いファイルは識別が合わず、アプリでは使われない)。

使い方 (リポジトリ直下から):
    python3 scripts/build_portals.py                    # 全組 (一致するファイルはスキップ)
    python3 scripts/build_portals.py --only Hiroshima   # 広島を含む組だけ
    python3 scripts/build_portals.py --force
    python3 scripts/build_portals.py --source-dir work/refresh/<日時>/packages --out work/refresh/<日時>/packages
    python3 scripts/build_portals.py --dry-run
"""

import argparse
import itertools
import json
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zipfile
import zlib
from pathlib import Path

from build_ch import PREFECTURES, extract_graph, graph_fingerprint

ROOT = Path(__file__).resolve().parent.parent
PORTALS_MAGIC = 0x434D5054        # "CMPT"
PORTALS_VERSION = 1
PORTALS_HEADER_BYTES = 68         # magic, version, count (各 Int) + A/B の識別 (各 Long x3) + 本体の CRC32 (Long)
PORTALS_RECORD_BYTES = 16         # nodeId (Long), aIndex (Int), bIndex (Int)
EMPTY_RECORD = "portals_empty.json"
CHUNK = 1 << 20


def pair_name(a: str, b: str) -> str:
    """CrossPortalsFile.fileName (Kotlin) と同じ: 大文字小文字を無視した辞書順。"""
    first, second = sorted((a, b), key=lambda n: (n.lower(), n))
    return "%s__%s" % (first, second)


def zip_graph_fingerprint(zip_path: Path, name: str):
    """ZIP 内の graph の識別を、展開せずに求める (graph_fingerprint と同じ値)。"""
    with zipfile.ZipFile(zip_path) as z:
        info = z.getinfo(name + ".graph")
        size = info.file_size
        chunk = min(size, CHUNK)
        with z.open(info) as src:
            head = src.read(chunk)
            tail = head
            while True:
                block = src.read(CHUNK)
                if not block:
                    break
                tail = (tail + block)[-chunk:]
    return size, zlib.crc32(head) & 0xFFFFFFFF, zlib.crc32(tail) & 0xFFFFFFFF


def portals_header(path: Path):
    """(通り口の数, A の識別, B の識別)。形式が違う・切れているファイルは None。"""
    try:
        with path.open("rb") as f:
            head = f.read(PORTALS_HEADER_BYTES)
        if len(head) < PORTALS_HEADER_BYTES:
            return None
        magic, version, count = struct.unpack(">iii", head[:12])
        if magic != PORTALS_MAGIC or version != PORTALS_VERSION or count <= 0:
            return None
        if path.stat().st_size != PORTALS_HEADER_BYTES + count * PORTALS_RECORD_BYTES:
            return None
        fps = struct.unpack(">qqqqqq", head[12:60])
        return count, fps[:3], fps[3:]
    except OSError:
        return None


def is_current(path: Path, fp_a, fp_b) -> bool:
    header = portals_header(path)
    return header is not None and header[1] == tuple(fp_a) and header[2] == tuple(fp_b)


def load_empty(out_dir: Path) -> dict:
    try:
        data = json.loads((out_dir / EMPTY_RECORD).read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save_empty(out_dir: Path, empty: dict) -> None:
    path = out_dir / EMPTY_RECORD
    tmp = path.with_suffix(".json.new")
    tmp.write_text(json.dumps(empty, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(path)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="カンマ区切りの県名。その県を含む組だけを対象にする")
    ap.add_argument("--source-dir", type=Path, default=ROOT / "packages", help="<県>.zip のある場所")
    ap.add_argument("--out", type=Path, default=ROOT / "packages", help=".portals の出力先")
    ap.add_argument("--heap", default="2g", help="JVM ヒープ (既定 2g)")
    ap.add_argument("--force", action="store_true", help="一致するファイルがあっても作り直す")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    wanted = None
    if args.only:
        wanted = {x.strip().lower() for x in args.only.split(",") if x.strip()}
        if not any(p.lower() in wanted for p in PREFECTURES):
            ap.error("対象の県がありません: %s" % args.only)
    pairs = [tuple(sorted(pair, key=str.lower)) for pair in itertools.combinations(PREFECTURES, 2)
             if wanted is None or any(p.lower() in wanted for p in pair)]

    failed = False
    fingerprints = {}
    for name in sorted({p for pair in pairs for p in pair}):
        zip_path = args.source_dir / ("%s.zip" % name)
        if not zip_path.is_file():
            print("[%s] ZIP がありません: %s (この県を含む組は作れません)" % (name, zip_path), file=sys.stderr)
            failed = True
            continue
        fingerprints[name] = zip_graph_fingerprint(zip_path, name)

    empty = load_empty(args.out)
    todo = []
    for a, b in pairs:
        if a not in fingerprints or b not in fingerprints:
            continue
        key = pair_name(a, b)
        out = args.out / (key + ".portals")
        fps = [list(fingerprints[a]), list(fingerprints[b])]
        if not args.force and is_current(out, *fps):
            print("[%s] 既存の .portals は今の graph と一致 (スキップ、%d 件)" % (key, portals_header(out)[0]))
        elif not args.force and empty.get(key) == fps and not out.exists():
            print("[%s] 通り口なし (隣り合わない組。前回の計算と graph が同じなのでスキップ)" % key)
        else:
            todo.append((a, b, key, out, fps))
    if not todo:
        return 1 if failed else 0
    if args.dry_run:
        for a, b, key, out, _ in todo:
            print("[%s] %s + %s -> %s" % (key, args.source_dir / (a + ".zip"), args.source_dir / (b + ".zip"), out))
        return 1 if failed else 0

    (ROOT / "work").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="portals_", dir=ROOT / "work") as tmp:
        tmp_dir = Path(tmp)
        graph_dir = tmp_dir / "graphs"
        result_dir = tmp_dir / "out"
        graph_dir.mkdir()
        result_dir.mkdir()
        names = sorted({p for a, b, _, _, _ in todo for p in (a, b)})
        graphs = []
        for name in names:
            graph = extract_graph(args.source_dir / ("%s.zip" % name), name, graph_dir)
            if graph_fingerprint(graph) != fingerprints[name]:
                print("[%s] 取り出した graph の識別が ZIP 内の計算と一致しません" % name, file=sys.stderr)
                return 1
            graphs.append(graph)
        started = time.time()
        # 1 回の JVM で、取り出した graph のすべての組を計算する (各 graph の ID 索引を読むのは 1 回)。
        cmd = ["./gradlew", ":routing-core:buildPortals", "-q", "-Pgraphs=%s" % ",".join(str(g) for g in graphs),
               "-PoutDir=%s" % result_dir, "-Pheap=%s" % args.heap]
        print("通り口の計算 (%s): %s" % (",".join(names), " ".join(cmd)))
        proc = subprocess.run(cmd, cwd=ROOT / "application", stdout=subprocess.PIPE, text=True)
        counts = {m.group(1): int(m.group(2)) for m in re.finditer(r"^PORTALS pair=(\S+) count=(\d+)", proc.stdout, re.M)}
        if proc.returncode != 0:
            sys.stderr.write(proc.stdout)
            print("buildPortals に失敗 (rc=%s)" % proc.returncode, file=sys.stderr)
            return 1
        args.out.mkdir(parents=True, exist_ok=True)
        for a, b, key, out, fps in todo:
            count = counts.get(key)
            produced = result_dir / (key + ".portals")
            if count is None:
                print("[%s] buildPortals の出力に結果がありません" % key, file=sys.stderr)
                failed = True
            elif count == 0:
                if out.exists():  # 以前は隣り合っていた組の古いファイル
                    out.unlink()
                empty[key] = fps
                print("[%s] 通り口なし (ファイルは作りません)" % key)
            elif not is_current(produced, *fps) or portals_header(produced)[0] != count:
                print("[%s] 生成した .portals の識別・件数が graph と一致しません" % key, file=sys.stderr)
                failed = True
            else:
                partial = out.with_suffix(out.suffix + ".new")
                shutil.copy2(produced, partial)
                partial.replace(out)
                empty.pop(key, None)
                print("[%s] 完了 %d 件、%.0f KB -> %s" % (key, count, out.stat().st_size / 1e3, out))
        save_empty(args.out, empty)
        print("所要 %.0f 秒" % (time.time() - started))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
