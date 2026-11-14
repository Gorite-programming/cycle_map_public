#!/usr/bin/env python3
"""データ工房の実行エンジン: 資源 (CPU・メモリ) を見ながらジョブを並列に流す。

GUI (`scripts/data_studio.py`) が計画 (plan.json) を書き、このエンジンを別プロセスで起動する。
GUI を閉じても止まらず、状態は `<run_dir>/status.json`、各ジョブのログは `<run_dir>/logs/<id>.log` に出る。

- ジョブは依存 (`deps`) を持つ。依存が失敗したジョブは skipped になる。
- CPU 枠・メモリ枠 (GB) に収まる限り、依存の済んだジョブを同時に走らせる。1 つだけで枠を超えるジョブは、
  他に何も走っていないときに走らせる (デッドロックしない)。
- `lock` が同じジョブは同時に走らせない (adb への転送、標高タイルの取得など)。
- JVM を使うジョブ (`heap_gb` > 0) は、ログに OutOfMemoryError があれば、ヒープを倍にして自動で再実行する
  (上限 `heap_max_gb`)。最初は小さめのヒープで多数を並列に走らせ、足りない県だけ増やすための仕組み。
- `<run_dir>/STOP` というファイルができたら、走っているジョブをすべて (子プロセスごと) 止めて終わる。

使い方 (通常は GUI から。単体でも実行できる):
    python scripts/studio_engine.py --plan work/studio/<run>/plan.json
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional

ROOT = Path(__file__).resolve().parent.parent

TERMINAL = {"done", "failed", "skipped", "stopped"}
OOM_MARKERS = ("OutOfMemoryError", "java.lang.OutOfMemory", "MemoryError")


@dataclass
class Task:
    id: str
    label: str
    step: str
    cmd: List[str]
    pref: str = ""
    deps: List[str] = field(default_factory=list)
    cpu: int = 1
    overhead_gb: float = 0.5   # JVM のヒープ以外の分 (プロセス自身・子プロセス・ネイティブ)
    heap_gb: float = 0.0       # >0 なら cmd 中の "{heap}" を "<n>m" に置き換える。OOM で倍にして再実行する
    heap_max_gb: float = 12.0
    lock: str = ""
    env: Dict[str, str] = field(default_factory=dict)
    # 実行時の状態
    status: str = "pending"    # pending / running / done / failed / skipped / stopped
    rc: Optional[int] = None
    attempts: int = 0
    started: Optional[float] = None
    ended: Optional[float] = None
    note: str = ""

    @property
    def mem_gb(self) -> float:
        return self.overhead_gb + self.heap_gb

    def resolved_cmd(self) -> List[str]:
        heap = "%dm" % int(round(self.heap_gb * 1024)) if self.heap_gb > 0 else ""
        return [c.replace("{heap}", heap) for c in self.cmd]


def kill_tree(proc) -> None:
    """子プロセスごと止める (Gradle や JVM を残さない)。"""
    try:
        import psutil
        parent = psutil.Process(proc.pid)
        victims = parent.children(recursive=True) + [parent]
        for p in victims:
            try:
                p.kill()
            except psutil.Error:
                pass
    except Exception:
        try:
            proc.kill()
        except Exception:
            pass


def default_spawn(task: Task, log_file, env: Dict[str, str]):
    flags = 0
    if sys.platform == "win32":
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    return subprocess.Popen(task.resolved_cmd(), stdout=log_file, stderr=subprocess.STDOUT, cwd=str(ROOT),
                            env=env, creationflags=flags)


class Scheduler:
    def __init__(self, tasks: List[Task], cpu: int, mem_gb: float, run_dir: Path, poll: float = 0.5,
                 spawn: Callable = default_spawn, base_env: Optional[Dict[str, str]] = None):
        ids = [t.id for t in tasks]
        if len(set(ids)) != len(ids):
            raise ValueError("ジョブ ID が重複しています")
        known = set(ids)
        for t in tasks:
            missing = [d for d in t.deps if d not in known]
            if missing:
                raise ValueError("%s: 未知の依存 %s" % (t.id, missing))
        self.tasks = tasks
        self.by_id = {t.id: t for t in tasks}
        self.cpu = max(1, int(cpu))
        self.mem_gb = float(mem_gb)
        self.run_dir = Path(run_dir)
        self.log_dir = self.run_dir / "logs"
        self.poll = poll
        self.spawn = spawn
        self.base_env = dict(base_env if base_env is not None else os.environ)
        self.procs: Dict[str, tuple] = {}   # id -> (proc, log_file)
        self.started_at = time.time()
        self.finished_at: Optional[float] = None
        self.stopped = False
        self._last_write = 0.0

    # ---- 資源の勘定 ----
    def used_cpu(self) -> int:
        return sum(self.by_id[i].cpu for i in self.procs)

    def used_mem(self) -> float:
        return sum(self.by_id[i].mem_gb for i in self.procs)

    def _locked(self, lock: str) -> bool:
        return bool(lock) and any(self.by_id[i].lock == lock for i in self.procs)

    def _fits(self, t: Task) -> bool:
        if not self.procs:
            return True
        return self.used_cpu() + t.cpu <= self.cpu and self.used_mem() + t.mem_gb <= self.mem_gb

    # ---- 状態の書き出し ----
    def log_path(self, t: Task) -> Path:
        return self.log_dir / ("%s.log" % t.id.replace(":", "_"))

    def write_status(self, force: bool = False) -> None:
        now = time.time()
        if not force and now - self._last_write < 1.0:
            return
        self._last_write = now
        data = {
            "started": self.started_at,
            "updated": now,
            "finished": self.finished_at,
            "stopped": self.stopped,
            "budget": {"cpu": self.cpu, "mem_gb": self.mem_gb},
            "used": {"cpu": self.used_cpu(), "mem_gb": round(self.used_mem(), 2)},
            "tasks": [dict(asdict(t), mem_gb=t.mem_gb, log=str(self.log_path(t))) for t in self.tasks],
        }
        tmp = self.run_dir / "status.json.tmp"
        tmp.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, self.run_dir / "status.json")

    # ---- 実行 ----
    def _start(self, t: Task) -> None:
        self.log_dir.mkdir(parents=True, exist_ok=True)
        log_file = open(self.log_path(t), "ab")
        t.attempts += 1
        header = "\n===== %s 試行 %d (heap=%s) =====\n" % (time.strftime("%H:%M:%S"), t.attempts, t.heap_gb or "-")
        log_file.write(header.encode("utf-8"))
        log_file.write(("$ " + " ".join(t.resolved_cmd()) + "\n").encode("utf-8", "replace"))
        log_file.flush()
        env = dict(self.base_env)
        env.update(t.env)
        try:
            proc = self.spawn(t, log_file, env)
        except Exception as exc:
            log_file.write(("起動に失敗: %s\n" % exc).encode("utf-8", "replace"))
            log_file.close()
            t.status, t.rc, t.ended, t.note = "failed", -1, time.time(), "起動に失敗: %s" % exc
            return
        t.status, t.started, t.ended, t.rc = "running", time.time(), None, None
        self.procs[t.id] = (proc, log_file)

    def _tail(self, t: Task, limit: int = 300_000) -> str:
        try:
            with open(self.log_path(t), "rb") as f:
                f.seek(0, os.SEEK_END)
                f.seek(max(0, f.tell() - limit))
                return f.read().decode("utf-8", "replace")
        except OSError:
            return ""

    def _reap(self) -> None:
        for tid in list(self.procs):
            proc, log_file = self.procs[tid]
            rc = proc.poll()
            if rc is None:
                continue
            log_file.close()
            del self.procs[tid]
            t = self.by_id[tid]
            t.rc, t.ended = rc, time.time()
            if rc == 0:
                t.status = "done"
                continue
            tail = self._tail(t)
            if t.heap_gb > 0 and t.heap_gb * 2 <= t.heap_max_gb and any(m in tail for m in OOM_MARKERS):
                t.note = "メモリ不足のためヒープ %.0fg → %.0fg で再実行" % (t.heap_gb, t.heap_gb * 2)
                t.heap_gb *= 2
                t.status = "pending"
                continue
            t.status = "failed"

    def _start_ready(self) -> None:
        for t in self.tasks:
            if t.status != "pending":
                continue
            deps = [self.by_id[d] for d in t.deps]
            if any(d.status in ("failed", "skipped", "stopped") for d in deps):
                t.status, t.note = "skipped", "依存するジョブが失敗したため省略"
                continue
            if any(d.status != "done" for d in deps):
                continue
            if self._locked(t.lock) or not self._fits(t):
                continue
            self._start(t)

    def stop_all(self) -> None:
        self.stopped = True
        for tid in list(self.procs):
            proc, log_file = self.procs.pop(tid)
            kill_tree(proc)
            try:
                proc.wait(timeout=10)
            except Exception:
                pass
            log_file.close()
            t = self.by_id[tid]
            t.status, t.ended, t.note = "stopped", time.time(), "停止しました"
        for t in self.tasks:
            if t.status == "pending":
                t.status, t.note = "skipped", "停止のため省略"

    def run(self) -> bool:
        self.run_dir.mkdir(parents=True, exist_ok=True)
        stop_flag = self.run_dir / "STOP"
        while True:
            if stop_flag.exists():
                self.stop_all()
                break
            self._reap()
            self._start_ready()
            self.write_status()
            if not self.procs and not any(t.status == "pending" for t in self.tasks):
                break
            time.sleep(self.poll)
        self.finished_at = time.time()
        self.write_status(force=True)
        return all(t.status == "done" for t in self.tasks)


def load_plan(path: Path) -> Scheduler:
    plan = json.loads(Path(path).read_text(encoding="utf-8"))
    fields = {f for f in Task.__dataclass_fields__}
    tasks = [Task(**{k: v for k, v in t.items() if k in fields}) for t in plan["tasks"]]
    budget = plan.get("budget", {})
    return Scheduler(tasks, budget.get("cpu", os.cpu_count() or 4), budget.get("mem_gb", 8.0),
                     Path(path).parent, base_env={**os.environ, **plan.get("env", {})})


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--plan", type=Path, required=True)
    args = ap.parse_args()
    scheduler = load_plan(args.plan)
    (scheduler.run_dir / "engine.pid").write_text(str(os.getpid()), encoding="utf-8")
    ok = scheduler.run()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
