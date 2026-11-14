"""データ工房の計画づくり: 県と作る物の選択 → 依存つきジョブの一覧 (plan.json)。

実行は `scripts/studio_engine.py`、画面は `scripts/data_studio.py`。ここは GUI にも実行エンジンにも依存せず、単体でテストできる。
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import time
from pathlib import Path
from typing import Dict, Iterable, List, Optional

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.prefectures import BY_NAME, PREFECTURES, REGIONS  # noqa: E402
from scripts.studio_engine import Task  # noqa: E402

PACKAGES = ROOT / "packages"
STUDIO_DIR = ROOT / "work" / "studio"
SEARCH_DB_DIR = ROOT / "sandbox" / "data-tool" / "out"

# 画面に出す順 = 計画の優先順 (先のものから、枠に収まる限り走る)
STEPS = [
    ("graph", "経路グラフ + ZIP", "OSM から経路グラフを作り、検索 DB と合わせて ZIP にする"),
    ("tiles", "地図タイル", "Protomaps から県の範囲を切り出す (ネットワーク)"),
    ("terrain", "地形 (等高線・陰影)", "国土地理院の標高タイルから作る (ネットワーク + CPU)"),
    ("ch", "CH (高速経路)", "Contraction Hierarchies の前処理"),
    ("portals", "県またぎの通り口", "隣り合う県のグラフ同士の共通ノード"),
    ("push", "端末へ転送", "adb で実機へ送り、SHA-256 を確かめる"),
]
STEP_KEYS = [k for k, _, _ in STEPS]

# 同じ lock のジョブを同時に走らせてよい数。標高タイルの取得 (国土地理院) は少数に絞り、adb は 1 台に 1 つ
LOCK_LIMITS = {"dem": 3, "adb": 1}

PROFILES = {
    "eco": "省電力 (CPU の 1/4、メモリ控えめ。ほかの作業をしながら)",
    "balanced": "バランス (CPU の 1/2)",
    "full": "フルパワー (CPU・メモリを使い切る)",
}


def resources() -> Dict[str, float]:
    """この PC の資源。psutil が無ければ控えめな値を返す。"""
    cpu = os.cpu_count() or 4
    total = avail = 8.0
    try:
        import psutil
        vm = psutil.virtual_memory()
        total, avail = vm.total / 1e9, vm.available / 1e9
    except Exception:
        pass
    free_disk = shutil.disk_usage(ROOT).free / 1e9
    return {"cpu": cpu, "ram_total_gb": round(total, 1), "ram_avail_gb": round(avail, 1), "disk_free_gb": round(free_disk, 1)}


def size_hint(name: str) -> float:
    """県の大きさの目安 (標高タイル・ノード数の多さ)。境界ポリゴンの面積。shapely が無ければ境界ファイルの大きさ。"""
    path = ROOT / "boundaries" / ("%s.geojson" % name)
    try:
        import json as _json
        from shapely.geometry import shape
        from shapely.validation import make_valid
        feats = _json.loads(path.read_text(encoding="utf-8"))["features"]
        return float(sum(make_valid(shape(f["geometry"])).area for f in feats))
    except Exception:
        try:
            return float(path.stat().st_size)
        except OSError:
            return 0.0


def order_by_size(prefs: Iterable[str]) -> List[str]:
    """小さい県から並べる。大きい県 (北海道など) が標高取得などの順番待ちの先頭を塞ぎ、他の県が始められなくなるのを防ぐ。"""
    return sorted(dict.fromkeys(prefs), key=lambda n: (size_hint(n), n))


def profile_budget(profile: str, res: Optional[Dict[str, float]] = None) -> Dict[str, float]:
    """プロファイルごとの CPU 枠・メモリ枠・各ジョブの既定値。"""
    res = res or resources()
    cpu, avail = int(res["cpu"]), float(res["ram_avail_gb"])
    if profile == "eco":
        return {"cpu": max(2, cpu // 4), "mem_gb": round(max(3.0, min(6.0, avail * 0.4)), 1),
                "graph_heap_gb": 1.5, "ch_heap_gb": 2.5, "terrain_workers": max(1, cpu // 8)}
    if profile == "balanced":
        return {"cpu": max(2, cpu // 2), "mem_gb": round(max(4.0, avail * 0.6), 1),
                "graph_heap_gb": 2.0, "ch_heap_gb": 3.0, "terrain_workers": max(2, cpu // 4)}
    # full: OS とブラウザの分だけ残す
    return {"cpu": cpu, "mem_gb": round(max(4.0, avail - 2.5), 1),
            "graph_heap_gb": 2.0, "ch_heap_gb": 3.0, "terrain_workers": max(2, cpu // 2)}


def osmium_dir() -> Optional[str]:
    from scripts._platform import find_tool
    tool = find_tool("osmium")
    return str(Path(tool).parent) if tool else None


def find_bash() -> Optional[str]:
    found = shutil.which("bash")
    if found:
        return found
    for cand in (r"C:\Program Files\Git\bin\bash.exe", r"C:\Program Files\Git\usr\bin\bash.exe"):
        if Path(cand).is_file():
            return cand
    return None


def existing(name: str) -> Dict[str, bool]:
    """県ごとの成果物の有無 (画面の一覧用)。"""
    pref = BY_NAME[name]
    lower = name.lower()
    return {
        "search_db": (SEARCH_DB_DIR / ("%s.search.db" % lower)).is_file(),
        "graph": (PACKAGES / ("%s.zip" % name)).is_file(),
        "tiles": (PACKAGES / pref["tile"]).is_file(),
        "terrain": (PACKAGES / ("%s.contours.pmtiles" % lower)).is_file() and (PACKAGES / ("%s.hillshade.pmtiles" % lower)).is_file(),
        "ch": (PACKAGES / ("%s.graph.ch" % name)).is_file(),
    }


def make_tasks(prefs: Iterable[str], steps: Iterable[str], *, budget: Dict[str, float], force: bool = False,
               grade: bool = True, package: str = "com.gorite.cyclemap.test", order: str = "size",
               skip_existing: bool = True, exists=None) -> List[Task]:
    """選んだ県 × 作る物 から、依存つきのジョブ一覧を作る。

    - `skip_existing` (既定): 成果物がすでにある県・工程は、ジョブにしない (`force` のときは全部作り直す)。
      標高タイルも、グラフか地形をその県で作るときだけ取る。ZIP がある県のために標高を取らない。
    - `order="size"`: 小さい県から並べる。
    """
    prefs = [p for p in dict.fromkeys(prefs)]
    steps = set(steps)
    unknown = [p for p in prefs if p not in BY_NAME]
    if unknown:
        raise ValueError("未知の県: %s" % unknown)
    if order == "size":
        prefs = order_by_size(prefs)
    exists = exists or existing
    py = sys.executable
    f = ["--force"] if force else []
    workers = int(budget.get("terrain_workers", 2))
    tasks: List[Task] = []

    def add(t: Task) -> Task:
        tasks.append(t)
        return t

    # 県ごとに、実際に作る工程を決める
    todo: Dict[str, set] = {}
    for p in prefs:
        have = exists(p) if skip_existing and not force else {}
        want = set()
        if "graph" in steps and not have.get("graph"):
            want.add("graph")
        if "tiles" in steps and not have.get("tiles"):
            want.add("tiles")
        if "terrain" in steps and not have.get("terrain"):
            want.add("terrain")
        if "ch" in steps and ("graph" in want or not have.get("ch")):   # グラフを作り直すなら CH も
            want.add("ch")
        todo[p] = want

    # 標高タイルは、辺の勾配 (「おすすめ」の坂道の罰則) のためにグラフより先に要る。取得は少数ずつ (lock)
    dem_prefs = [p for p in prefs if "terrain" in todo[p] or ("graph" in todo[p] and grade)]
    for p in dem_prefs:
        add(Task("dem:" + p, "%s 標高タイルの取得" % p, "dem", [py, "scripts/build_terrain.py", "--only", p, "--dem-only"],
                 pref=p, cpu=1, overhead_gb=0.4, lock="dem"))
    # タイルは先に (ネットワーク待ちを他の工程と重ねる)。グラフは最新のタイルが置かれた後に作る
    # (build_prefectures が sandbox/ の古いタイルを packages/ に置くのは、packages/ に無いか古いときだけ)
    for p in prefs:
        if "tiles" in todo[p]:
            add(Task("tiles:" + p, "%s 地図タイル" % p, "tiles", [py, "scripts/build_tiles.py", "--only", p] + f,
                     pref=p, cpu=1, overhead_gb=0.4))
    graph_ids: List[str] = []
    for p in prefs:
        if "graph" in todo[p]:
            deps = (["dem:" + p] if grade else []) + (["tiles:" + p] if "tiles" in todo[p] else [])
            add(Task("graph:" + p, "%s 経路グラフ + ZIP" % p, "graph",
                     [py, "build_prefectures.py", "--only", p, "--heap", "{heap}"] + f,
                     pref=p, deps=deps, cpu=2, overhead_gb=1.0, heap_gb=budget.get("graph_heap_gb", 2.0), heap_max_gb=12.0))
            graph_ids.append("graph:" + p)
    for p in prefs:
        if "terrain" in todo[p]:
            add(Task("terrain:" + p, "%s 地形 (等高線・陰影)" % p, "terrain",
                     [py, "scripts/build_terrain.py", "--only", p, "--workers", str(workers)] + f,
                     pref=p, deps=["dem:" + p], cpu=workers, overhead_gb=1.0 + 0.4 * workers))
    for p in prefs:
        if "ch" in todo[p]:
            add(Task("ch:" + p, "%s CH" % p, "ch", [py, "scripts/build_ch.py", "--only", p, "--heap", "{heap}"] + f,
                     pref=p, deps=(["graph:" + p] if "graph:" + p in graph_ids else []), cpu=1, overhead_gb=1.2,
                     heap_gb=budget.get("ch_heap_gb", 3.0), heap_max_gb=12.0))
    portals_id = ""
    if "portals" in steps and prefs:
        portals_id = "portals"
        add(Task("portals", "県またぎの通り口", "portals", [py, "scripts/build_portals.py", "--only", *prefs, "--heap", "{heap}"] + f,
                 deps=list(graph_ids), cpu=2, overhead_gb=1.0, heap_gb=2.0, heap_max_gb=8.0))
    if "push" in steps:
        bash = find_bash() or "bash"
        for p in prefs:
            deps = [t.id for t in tasks if t.pref == p and t.step in ("graph", "tiles", "terrain", "ch")]
            if portals_id:
                deps.append(portals_id)
            add(Task("push:" + p, "%s を端末へ転送" % p, "push", [bash, "scripts/push_data.sh", "--pref", p],
                     pref=p, deps=deps, cpu=1, overhead_gb=0.2, lock="adb", env={"CYCLEMAP_PACKAGE_NAME": package}))
    return tasks


def write_plan(tasks: List[Task], budget: Dict[str, float], name: str = "") -> Path:
    """work/studio/<日時>/plan.json を書いてパスを返す。"""
    run_dir = STUDIO_DIR / time.strftime("%Y%m%d-%H%M%S")
    run_dir.mkdir(parents=True, exist_ok=True)
    env = {}
    osm = osmium_dir()
    if osm:
        env["PATH"] = osm + os.pathsep + os.environ.get("PATH", "")
    plan = {
        "name": name,
        "budget": {"cpu": int(budget["cpu"]), "mem_gb": float(budget["mem_gb"])},
        "env": env,
        "lock_limits": LOCK_LIMITS,
        "tasks": [{k: v for k, v in t.__dict__.items()} for t in tasks],
    }
    path = run_dir / "plan.json"
    path.write_text(json.dumps(plan, ensure_ascii=False, indent=1), encoding="utf-8")
    return path


def estimate_disk_gb(prefs: Iterable[str], steps: Iterable[str]) -> float:
    """1 県あたりの概算 (docs/nationwide_design.md): ZIP 0.08 + タイル 0.1 + 地形 0.08 + CH 0.15 + 作業領域 1.5。"""
    steps = set(steps)
    per = (0.08 if "graph" in steps else 0) + (0.10 if "tiles" in steps else 0) + (0.08 if "terrain" in steps else 0) \
        + (0.15 if "ch" in steps else 0) + (1.5 if "graph" in steps else 0) + (0.03 if "terrain" in steps else 0)
    return round(per * len(list(prefs)), 1)


def list_runs(limit: int = 20) -> List[Path]:
    if not STUDIO_DIR.is_dir():
        return []
    return sorted((p for p in STUDIO_DIR.iterdir() if (p / "plan.json").is_file()), reverse=True)[:limit]


def read_status(run_dir: Path) -> Optional[dict]:
    try:
        return json.loads((Path(run_dir) / "status.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def engine_alive(run_dir: Path) -> bool:
    """エンジンが生きているか (pid ファイルと psutil で)。"""
    pid_file = Path(run_dir) / "engine.pid"
    try:
        pid = int(pid_file.read_text(encoding="utf-8").strip())
        import psutil
        return psutil.pid_exists(pid) and "studio_engine" in " ".join(psutil.Process(pid).cmdline())
    except Exception:
        return False


def launch(plan_path: Path) -> int:
    """エンジンを別プロセスで起動する (GUI を閉じても止まらない)。pid を返す。"""
    import subprocess
    flags = 0
    if sys.platform == "win32":
        flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | getattr(subprocess, "CREATE_NO_WINDOW", 0)
    out = open(Path(plan_path).parent / "engine.out", "ab")
    proc = subprocess.Popen([sys.executable, str(ROOT / "scripts" / "studio_engine.py"), "--plan", str(plan_path)],
                            stdout=out, stderr=subprocess.STDOUT, cwd=str(ROOT), creationflags=flags, close_fds=True)
    return proc.pid


def request_stop(run_dir: Path) -> None:
    (Path(run_dir) / "STOP").write_text("stop", encoding="utf-8")


__all__ = ["STEPS", "STEP_KEYS", "PROFILES", "REGIONS", "PREFECTURES", "resources", "profile_budget", "make_tasks",
           "write_plan", "existing", "estimate_disk_gb", "list_runs", "read_status", "engine_alive", "launch", "request_stop"]
