#!/usr/bin/env python3
"""県別の Contraction Hierarchies ファイル (<県>.graph.ch) の生成。

`packages/<県>.zip` (または --source-dir の ZIP) から <県>.graph / .graph.idx を取り出し、
`./gradlew :routing-core:buildCh` (または `--jobs 2` 以上では直接 `java -cp ... ChBuildMain`)
で前処理して `packages/<県>.graph.ch` に置く。
- CH は ZIP (5 ファイル構成) には入れず、タイルと同じく別成果物にする。アプリは無くても A* で動く。
- 既存の .graph.ch のヘッダにある graph の識別 (サイズ + 先頭/末尾 1MB の CRC32) が、
  今の graph と一致していればスキップする (--force で作り直し)。
- 1 県あたり数分、`-Xmx6g` (並列時は既定 `-Xmx5g`) 程度のメモリが要る。

使い方 (リポジトリ直下から):
    python3 scripts/build_ch.py                       # 5県すべて (一致する .graph.ch があればスキップ)
    python3 scripts/build_ch.py --only Tottori,Shimane --force
    python3 scripts/build_ch.py --only all --jobs 4 --heap 5g --timeout-min 30
    python3 scripts/build_ch.py --source-dir work/refresh/<日時>/packages --out work/refresh/<日時>/packages
    python3 scripts/build_ch.py --dry-run
"""

import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import zipfile
import zlib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# Support direct CLI execution and importlib-based callers alike.
if str(Path(__file__).resolve().parent.parent) not in sys.path:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from scripts.prefectures import BY_NAME, resolve
from scripts._platform import find_jdk, gradle_command, java_environment

ROOT = Path(__file__).resolve().parent.parent
PREFECTURES = list(BY_NAME)
CH_MAGIC_AND_VERSION_BYTES = 20   # magic, version, nodeCount, upCount, downCount (各 Int)
CH_HEADER_BYTES = 44              # 上記 + 識別 (size, firstCrc, lastCrc の各 Long)
CH_MAIN_CLASS = "com.gorite.cyclemap.routing.ChBuildMain"
_PRINT_LOCK = threading.Lock()


def _log(msg: str, *, stream=None) -> None:
    with _PRINT_LOCK:
        print(msg, file=stream or sys.stdout, flush=True)


def _read_kotlin_version(app_dir: Path) -> str | None:
    toml = app_dir / "gradle" / "libs.versions.toml"
    if not toml.is_file():
        return None
    m = re.search(r'(?m)^\s*kotlin\s*=\s*"([^"]+)"', toml.read_text(encoding="utf-8"))
    return m.group(1) if m else None


def _version_key(ver: str) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", ver))


def find_kotlin_stdlib_jar(app_dir: Path, gradle_home: Path | None = None) -> Path:
    base = gradle_home or Path(os.environ.get("GRADLE_USER_HOME") or (Path.home() / ".gradle"))
    stdlib_dir = base / "caches" / "modules-2" / "files-2.1" / "org.jetbrains.kotlin" / "kotlin-stdlib"
    if not stdlib_dir.is_dir():
        raise FileNotFoundError(
            "kotlin-stdlib の Gradle キャッシュが見つかりません: %s "
            "(先に ./gradlew :routing-core:compileKotlin を実行してください)" % stdlib_dir
        )

    def _jars_in(version_dir: Path) -> list[Path]:
        return sorted(
            p for p in version_dir.glob("*/*.jar")
            if p.is_file() and not p.name.endswith(("-sources.jar", "-javadoc.jar"))
        )

    preferred = _read_kotlin_version(app_dir)
    if preferred and (stdlib_dir / preferred).is_dir():
        jars = _jars_in(stdlib_dir / preferred)
        if jars:
            return jars[0]

    version_dirs = sorted(
        (d for d in stdlib_dir.iterdir() if d.is_dir()),
        key=lambda d: (_version_key(d.name), d.name),
        reverse=True,
    )
    for vdir in version_dirs:
        jars = _jars_in(vdir)
        if jars:
            return jars[0]

    raise FileNotFoundError(
        "kotlin-stdlib の JAR が見つかりません: %s "
        "(先に ./gradlew :routing-core:compileKotlin を実行してください)" % stdlib_dir
    )


def classes_need_compile(classes_dir: Path, src_dir: Path) -> bool:
    if not classes_dir.is_dir():
        return True
    if not src_dir.is_dir():
        return False
    src_files = [p for p in src_dir.rglob("*") if p.is_file()]
    if not src_files:
        return False
    cls_files = [p for p in classes_dir.rglob("*.class") if p.is_file()]
    if not cls_files:
        return True
    latest_src = max(p.stat().st_mtime for p in src_files)
    latest_cls = max(p.stat().st_mtime for p in cls_files)
    return latest_src > latest_cls


def prepare_direct_classpath(root: Path) -> tuple[str, str]:
    app_dir = root / "application"
    classes_dir = app_dir / "routing-core" / "build" / "classes" / "kotlin" / "main"
    src_dir = app_dir / "routing-core" / "src" / "main"
    if classes_need_compile(classes_dir, src_dir):
        cmd = [gradle_command(app_dir), ":routing-core:compileKotlin"]
        _log("[compile] %s" % " ".join(cmd))
        rc = subprocess.run(cmd, cwd=app_dir if app_dir.is_dir() else root, env=java_environment()).returncode
        if rc != 0:
            raise RuntimeError("routing-core のコンパイルに失敗しました (rc=%s)" % rc)
    if not classes_dir.is_dir():
        raise FileNotFoundError("コンパイル済みクラスフォルダが見つかりません: %s" % classes_dir)

    stdlib_jar = find_kotlin_stdlib_jar(app_dir)
    cp_entries = [str(classes_dir)]
    resources_dir = app_dir / "routing-core" / "build" / "resources" / "main"
    if resources_dir.is_dir():
        cp_entries.append(str(resources_dir))
    cp_entries.append(str(stdlib_jar))

    jdk = find_jdk()
    java_name = "java.exe" if sys.platform == "win32" else "java"
    java_exe = str(jdk / "bin" / java_name) if jdk is not None else "java"
    return java_exe, os.pathsep.join(cp_entries)


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


def build(
    name: str,
    source_dir: Path,
    out_dir: Path,
    heap: str,
    force: bool,
    dry_run: bool,
    timeout_min: float = 0.0,
    java_exe: str | None = None,
    classpath: str | None = None,
) -> bool:
    zip_path = source_dir / ("%s.zip" % name)
    out = out_dir / ("%s.graph.ch" % name)
    if dry_run:
        _log("[%s] %s -> %s" % (name, zip_path, out))
        return True
    if not zip_path.is_file():
        _log("[%s] 失敗: ZIP がありません: %s" % (name, zip_path), stream=sys.stderr)
        return False
    app_dir = ROOT / "application"
    cwd = app_dir if app_dir.is_dir() else ROOT
    timeout_sec = timeout_min * 60.0 if timeout_min > 0 else None
    with tempfile.TemporaryDirectory(prefix="ch_%s_" % name, dir=ROOT / "work") as tmp:
        tmp_dir = Path(tmp)
        graph = extract_graph(zip_path, name, tmp_dir)
        if not force and is_current(out, graph):
            _log("[%s] 既存の .graph.ch は今の graph と一致 (スキップ)" % name)
            return True
        started = time.time()
        tmp_out = tmp_dir / out.name
        if classpath is not None:
            cmd = [
                java_exe or "java",
                "-Xmx%s" % heap,
                "-cp",
                classpath,
                CH_MAIN_CLASS,
                str(graph),
                str(tmp_out),
            ]
            run_kwargs = {"capture_output": True, "text": True, "errors": "replace"}
        else:
            cmd = [
                gradle_command(app_dir),
                ":routing-core:buildCh",
                "-q",
                "-Pgraph=%s" % graph,
                "-Pout=%s" % tmp_out,
                "-Pheap=%s" % heap,
            ]
            run_kwargs = {}
        _log("[%s] 前処理: %s" % (name, " ".join(cmd)))
        try:
            res = subprocess.run(cmd, cwd=cwd, env=java_environment(), timeout=timeout_sec, **run_kwargs)
        except subprocess.TimeoutExpired:
            _log("[%s] 時間切れ (%g 分)" % (name, timeout_min), stream=sys.stderr)
            return False
        if res.returncode != 0 or not tmp_out.is_file():
            stderr_tail = (getattr(res, "stderr", None) or "").strip()[-200:].replace("\n", " ")
            detail = (": " + stderr_tail) if stderr_tail else ""
            _log("[%s] 失敗 (rc=%s)%s" % (name, res.returncode, detail), stream=sys.stderr)
            return False
        if not is_current(tmp_out, graph):
            _log("[%s] 失敗: 生成した .graph.ch の識別が graph と一致しません" % name, stream=sys.stderr)
            return False
        out_dir.mkdir(parents=True, exist_ok=True)
        partial = out.with_suffix(out.suffix + ".new")
        try:
            shutil.copy2(tmp_out, partial)
            partial.replace(out)
        finally:
            if partial.exists():
                partial.unlink()
        graph_size = graph.stat().st_size
        out_size = out.stat().st_size
        ratio = (out_size / graph_size) if graph_size else 0.0
        _log("[%s] 完了 %.0f 秒 %.0f MB (graph 比 %.2f 倍) -> %s" % (
            name, time.time() - started, out_size / 1e6, ratio, out))
    return True


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="+", help="Prefectures, regions, or all (comma/space separated; default: Chugoku five)")
    ap.add_argument("--source-dir", type=Path, default=ROOT / "packages", help="<県>.zip のある場所")
    ap.add_argument("--out", type=Path, default=ROOT / "packages", help=".graph.ch の出力先")
    ap.add_argument("--heap", default=None, help="前処理の JVM ヒープ (既定: jobs=1 は 6g、jobs>=2 は 5g)")
    ap.add_argument("--jobs", type=int, default=1, help="同時に作る県の数 (既定 1。2 以上で Gradle を通さず直接 java 実行)")
    ap.add_argument("--timeout-min", type=float, default=0.0, help="県ごとの時間切れ (分、既定 0 = 無制限)")
    ap.add_argument("--force", action="store_true", help="一致する .graph.ch があっても作り直す")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args(argv)
    if args.jobs < 1:
        ap.error("--jobs must be >= 1")
    if args.timeout_min < 0:
        ap.error("--timeout-min must be >= 0")
    try:
        names = resolve(args.only)
    except ValueError as exc:
        ap.error(str(exc))

    heap = args.heap if args.heap is not None else ("5g" if args.jobs >= 2 else "6g")
    _log("対象: %s" % ",".join(names))

    java_exe = None
    classpath = None
    if not args.dry_run:
        (ROOT / "work").mkdir(exist_ok=True)
        if args.jobs >= 2:
            try:
                java_exe, classpath = prepare_direct_classpath(ROOT)
            except (FileNotFoundError, RuntimeError) as exc:
                _log("エラー: %s" % exc, stream=sys.stderr)
                return 1

    if args.jobs >= 2 and not args.dry_run:
        with ThreadPoolExecutor(max_workers=args.jobs) as executor:
            results = list(executor.map(
                lambda n: build(
                    n, args.source_dir, args.out, heap, args.force, args.dry_run,
                    timeout_min=args.timeout_min, java_exe=java_exe, classpath=classpath,
                ),
                names,
            ))
        failed = [n for n, ok in zip(names, results) if not ok]
    else:
        failed = [
            n for n in names
            if not build(
                n, args.source_dir, args.out, heap, args.force, args.dry_run,
                timeout_min=args.timeout_min,
            )
        ]

    if not args.dry_run:
        _log("完了: 成功 %d 件 / 失敗 %d 件 (合計 %d 件)" % (len(names) - len(failed), len(failed), len(names)))
    if failed:
        _log("失敗: %s" % ", ".join(failed), stream=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
