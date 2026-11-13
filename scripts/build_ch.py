#!/usr/bin/env python3
"""県別の Contraction Hierarchies ファイル (<県>.graph.ch) の生成。

`packages/<県>.zip` (または --source-dir の ZIP) から <県>.graph / .graph.idx を取り出し、
`./gradlew :routing-core:buildCh` で前処理して `packages/<県>.graph.ch` に置く。
- CH は ZIP (5 ファイル構成) には入れず、タイルと同じく別成果物にする。アプリは無くても A* で動く。
- 既存の .graph.ch のヘッダにある graph の識別 (サイズ + 先頭/末尾 1MB の CRC32) が、
  今の graph と一致していればスキップする (--force で作り直し)。
- 1 県あたり数分、`-Xmx6g` 程度のメモリが要る。

使い方 (リポジトリ直下から):
    python3 scripts/build_ch.py                       # 5県すべて (一致する .graph.ch があればスキップ)
    python3 scripts/build_ch.py --only Tottori,Shimane --force
    python3 scripts/build_ch.py --source-dir work/refresh/<日時>/packages --out work/refresh/<日時>/packages
    python3 scripts/build_ch.py --dry-run
"""

import argparse
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zipfile
import zlib
from pathlib import Path

# Support direct CLI execution and importlib-based callers alike.
if str(Path(__file__).resolve().parent.parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from scripts.prefectures import BY_NAME, resolve
from scripts._platform import gradle_command, java_environment

ROOT = Path(__file__).resolve().parent.parent
PREFECTURES = list(BY_NAME)
CH_MAGIC_AND_VERSION_BYTES = 20   # magic, version, nodeCount, upCount, downCount (各 Int)
CH_HEADER_BYTES = 44              # 上記 + 識別 (size, firstCrc, lastCrc の各 Long)


def graph_fingerprint(path: Path):
    """GraphFingerprint.fromFile (Kotlin) と同じ: (サイズ, 先頭 chunk の CRC32, 末尾 chunk の CRC32)。"""
    size = path.stat().st_size
    chunk = min(size, 1 << 20)
    with path.open("rb") as f:
        first = zlib.crc32(f.read(chunk)) & 0xFFFFFFFF
        f.seek(max(size - chunk, 0))
        last = zlib.crc32(f.read(chunk)) & 0xFFFFFFFF
    return size, first, last


def ch_fingerprint(path: Path):
    with path.open("rb") as f:
        head = f.read(CH_HEADER_BYTES)
    if len(head) < CH_HEADER_BYTES:
        return None
    return struct.unpack(">qqq", head[CH_MAGIC_AND_VERSION_BYTES:CH_HEADER_BYTES])


def is_current(ch: Path, graph: Path) -> bool:
    return ch.is_file() and ch_fingerprint(ch) == graph_fingerprint(graph)


def extract_graph(zip_path: Path, name: str, dest: Path) -> Path:
    with zipfile.ZipFile(zip_path) as z:
        for suffix in (".graph", ".graph.idx"):
            with z.open(name + suffix) as src, (dest / (name + suffix)).open("wb") as out:
                shutil.copyfileobj(src, out, 1 << 20)
    return dest / (name + ".graph")


def build(name: str, source_dir: Path, out_dir: Path, heap: str, force: bool, dry_run: bool) -> bool:
    zip_path = source_dir / ("%s.zip" % name)
    out = out_dir / ("%s.graph.ch" % name)
    if dry_run:
        print("[%s] %s -> %s" % (name, zip_path, out))
        return True
    if not zip_path.is_file():
        print("[%s] ZIP がありません: %s" % (name, zip_path), file=sys.stderr)
        return False
    with tempfile.TemporaryDirectory(prefix="ch_%s_" % name, dir=ROOT / "work") as tmp:
        tmp_dir = Path(tmp)
        graph = extract_graph(zip_path, name, tmp_dir)
        if not force and is_current(out, graph):
            print("[%s] 既存の .graph.ch は今の graph と一致 (スキップ)" % name)
            return True
        started = time.time()
        tmp_out = tmp_dir / out.name
        cmd = [gradle_command(ROOT / "application"), ":routing-core:buildCh", "-q", "-Pgraph=%s" % graph, "-Pout=%s" % tmp_out, "-Pheap=%s" % heap]
        print("[%s] 前処理: %s" % (name, " ".join(cmd)))
        rc = subprocess.run(cmd, cwd=ROOT / "application", env=java_environment()).returncode
        if rc != 0 or not tmp_out.is_file():
            print("[%s] buildCh に失敗 (rc=%s)" % (name, rc), file=sys.stderr)
            return False
        if not is_current(tmp_out, graph):
            print("[%s] 生成した .graph.ch の識別が graph と一致しません" % name, file=sys.stderr)
            return False
        out_dir.mkdir(parents=True, exist_ok=True)
        partial = out.with_suffix(out.suffix + ".new")
        shutil.copy2(tmp_out, partial)
        partial.replace(out)
        print("[%s] 完了 %.0f 秒、%.0f MB (graph 比 %.2f 倍) -> %s" % (
            name, time.time() - started, out.stat().st_size / 1e6, out.stat().st_size / graph.stat().st_size, out))
    return True


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="+", help="Prefectures, regions, or all (comma/space separated; default: Chugoku five)")
    ap.add_argument("--source-dir", type=Path, default=ROOT / "packages", help="<県>.zip のある場所")
    ap.add_argument("--out", type=Path, default=ROOT / "packages", help=".graph.ch の出力先")
    ap.add_argument("--heap", default="6g", help="前処理の JVM ヒープ (既定 6g)")
    ap.add_argument("--force", action="store_true", help="一致する .graph.ch があっても作り直す")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    try:
        names = resolve(args.only)
    except ValueError as exc:
        ap.error(str(exc))
    print("対象: %s" % ",".join(names))
    if not args.dry_run:
        (ROOT / "work").mkdir(exist_ok=True)
    failed = [n for n in names if not build(n, args.source_dir, args.out, args.heap, args.force, args.dry_run)]
    if failed:
        print("失敗: %s" % ", ".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
