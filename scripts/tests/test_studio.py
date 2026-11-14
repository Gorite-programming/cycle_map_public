"""データ工房: 実行エンジン (scripts/studio_engine.py) と計画づくり (scripts/studio_plan.py) の単体テスト。

エンジンの試験には、すぐ終わる python の 1 行コマンドを使う (本物のデータ生成は走らせない)。
"""
import json
import sys
import threading
import time
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import studio_plan  # noqa: E402
from scripts.studio_engine import Scheduler, Task, load_plan  # noqa: E402

PY = sys.executable


def py(code):
    return [PY, "-c", code]


def run(tasks, tmp_path, cpu=4, mem=8.0):
    sch = Scheduler(tasks, cpu=cpu, mem_gb=mem, run_dir=tmp_path, poll=0.05)
    ok = sch.run()
    return sch, ok


def overlaps(a, b):
    return a.started < b.ended and b.started < a.ended


SLEEP = "import time; time.sleep(0.6)"


def test_dependencies_run_in_order(tmp_path):
    out = tmp_path / "order.txt"
    code = "open(r'%s','a').write('{}\\n')" % out
    tasks = [
        Task("b", "b", "x", py(code.format("b")), deps=["a"]),
        Task("a", "a", "x", py(code.format("a"))),
        Task("c", "c", "x", py(code.format("c")), deps=["b"]),
    ]
    sch, ok = run(tasks, tmp_path)
    assert ok
    assert out.read_text().split() == ["a", "b", "c"]


def test_failure_skips_dependents_but_not_independents(tmp_path):
    tasks = [
        Task("bad", "bad", "x", py("raise SystemExit(3)")),
        Task("child", "child", "x", py("pass"), deps=["bad"]),
        Task("grandchild", "gc", "x", py("pass"), deps=["child"]),
        Task("other", "other", "x", py("pass")),
    ]
    sch, ok = run(tasks, tmp_path)
    status = {t.id: t.status for t in sch.tasks}
    assert not ok
    assert status == {"bad": "failed", "child": "skipped", "grandchild": "skipped", "other": "done"}
    assert sch.by_id["bad"].rc == 3


def test_cpu_budget_prevents_overlap(tmp_path):
    tasks = [Task("t1", "1", "x", py(SLEEP), cpu=2), Task("t2", "2", "x", py(SLEEP), cpu=2)]
    sch, _ = run(tasks, tmp_path, cpu=3)
    assert not overlaps(*sch.tasks)


def test_tasks_overlap_when_budget_allows(tmp_path):
    tasks = [Task("t1", "1", "x", py(SLEEP), cpu=1), Task("t2", "2", "x", py(SLEEP), cpu=1)]
    sch, _ = run(tasks, tmp_path, cpu=4)
    assert overlaps(*sch.tasks)


def test_memory_budget_prevents_overlap(tmp_path):
    tasks = [Task("t1", "1", "x", py(SLEEP), overhead_gb=3, heap_gb=2),
             Task("t2", "2", "x", py(SLEEP), overhead_gb=3, heap_gb=2)]
    sch, _ = run(tasks, tmp_path, cpu=8, mem=8.0)
    assert not overlaps(*sch.tasks)


def test_oversized_task_still_runs_alone(tmp_path):
    tasks = [Task("big", "big", "x", py("pass"), cpu=64, overhead_gb=100)]
    _, ok = run(tasks, tmp_path, cpu=4, mem=8.0)
    assert ok


def test_same_lock_never_overlaps(tmp_path):
    tasks = [Task("t1", "1", "x", py(SLEEP), lock="adb"), Task("t2", "2", "x", py(SLEEP), lock="adb"),
             Task("t3", "3", "x", py(SLEEP))]
    sch, _ = run(tasks, tmp_path, cpu=8)
    t1, t2, t3 = sch.tasks
    assert not overlaps(t1, t2)
    assert overlaps(t1, t3) or overlaps(t2, t3)


def test_out_of_memory_retries_with_doubled_heap(tmp_path):
    # {heap} は "256m" のように渡る。1024m 未満なら OutOfMemoryError を出して失敗する
    code = ("import sys\n"
            "h = int(sys.argv[1].rstrip('m'))\n"
            "if h < 1024:\n"
            "    print('java.lang.OutOfMemoryError: Java heap space')\n"
            "    sys.exit(1)\n")
    task = Task("j", "j", "x", [PY, "-c", code, "{heap}"], heap_gb=0.25, heap_max_gb=4)
    sch, ok = run([task], tmp_path)
    assert ok
    assert task.attempts == 3          # 256m → 512m → 1024m
    assert task.heap_gb == 1.0
    assert "再実行" in task.note


def test_out_of_memory_gives_up_at_heap_cap(tmp_path):
    code = "print('java.lang.OutOfMemoryError'); raise SystemExit(1)"
    task = Task("j", "j", "x", py(code), heap_gb=1, heap_max_gb=2)
    _, ok = run([task], tmp_path)
    assert not ok and task.status == "failed"
    assert task.attempts == 2          # 1g → 2g で打ち止め


def test_ordinary_failure_is_not_retried(tmp_path):
    task = Task("j", "j", "x", py("raise SystemExit(2)"), heap_gb=1, heap_max_gb=8)
    run([task], tmp_path)
    assert task.attempts == 1 and task.status == "failed"


def test_status_json_is_written(tmp_path):
    run([Task("a", "a", "x", py("pass"))], tmp_path)
    data = json.loads((tmp_path / "status.json").read_text(encoding="utf-8"))
    assert data["finished"] is not None
    assert data["tasks"][0]["status"] == "done" and data["tasks"][0]["id"] == "a"
    assert (tmp_path / "logs" / "a.log").is_file()


def test_stop_flag_kills_running_and_skips_pending(tmp_path):
    tasks = [Task("long", "long", "x", py("import time; time.sleep(30)")),
             Task("after", "after", "x", py("pass"), deps=["long"])]
    sch = Scheduler(tasks, cpu=4, mem_gb=8, run_dir=tmp_path, poll=0.05)
    thread = threading.Thread(target=sch.run)
    thread.start()
    time.sleep(0.8)
    (tmp_path / "STOP").write_text("x")
    thread.join(timeout=20)
    assert not thread.is_alive()
    assert tasks[0].status == "stopped" and tasks[1].status == "skipped"


def test_unknown_dependency_and_duplicate_ids_are_rejected(tmp_path):
    with pytest.raises(ValueError):
        Scheduler([Task("a", "a", "x", py("pass"), deps=["zzz"])], 2, 4, tmp_path)
    with pytest.raises(ValueError):
        Scheduler([Task("a", "a", "x", py("pass")), Task("a", "a2", "x", py("pass"))], 2, 4, tmp_path)


# ---------------- 計画づくり ----------------

BUDGET = {"cpu": 16, "mem_gb": 12.0, "graph_heap_gb": 3.0, "ch_heap_gb": 4.0, "terrain_workers": 4}


def ids(tasks):
    return [t.id for t in tasks]


def test_plan_dependencies_for_all_steps():
    tasks = studio_plan.make_tasks(["Kagawa", "Okinawa"], studio_plan.STEP_KEYS, budget=BUDGET)
    by = {t.id: t for t in tasks}
    assert by["graph:Kagawa"].deps == ["dem:Kagawa", "tiles:Kagawa"]   # 勾配のため標高タイルが先、最新のタイルも先
    assert by["tiles:Kagawa"].deps == []
    assert by["ch:Kagawa"].deps == ["graph:Kagawa"]
    assert by["terrain:Kagawa"].deps == ["dem:Kagawa"]
    assert set(by["portals"].deps) == {"graph:Kagawa", "graph:Okinawa"}
    push = by["push:Okinawa"]
    assert {"graph:Okinawa", "tiles:Okinawa", "terrain:Okinawa", "ch:Okinawa", "portals"} <= set(push.deps)
    assert push.lock == "adb" and by["dem:Kagawa"].lock == "dem"
    seen = set()
    for t in tasks:                      # すべての依存は存在し、先に並んでいる
        assert set(t.deps) <= seen, t.id
        seen.add(t.id)


def test_plan_without_grade_skips_dem_for_graph_only():
    tasks = studio_plan.make_tasks(["Kagawa"], ["graph"], budget=BUDGET, grade=False)
    assert ids(tasks) == ["graph:Kagawa"] and tasks[0].deps == []
    with_grade = studio_plan.make_tasks(["Kagawa"], ["graph"], budget=BUDGET, grade=True)
    assert ids(with_grade) == ["dem:Kagawa", "graph:Kagawa"]
    assert with_grade[1].deps == ["dem:Kagawa"]


def test_plan_force_and_heap_placeholders():
    tasks = studio_plan.make_tasks(["Kagawa"], ["graph", "ch", "tiles"], budget=BUDGET, force=True)
    by = {t.id: t for t in tasks}
    for tid in ("graph:Kagawa", "ch:Kagawa", "tiles:Kagawa"):
        assert "--force" in by[tid].cmd
    assert "{heap}" in by["graph:Kagawa"].cmd and by["graph:Kagawa"].heap_gb == 3.0
    assert by["ch:Kagawa"].heap_gb == 4.0 and by["tiles:Kagawa"].heap_gb == 0


def test_plan_rejects_unknown_prefecture_and_dedupes():
    with pytest.raises(ValueError):
        studio_plan.make_tasks(["Atlantis"], ["graph"], budget=BUDGET)
    tasks = studio_plan.make_tasks(["Kagawa", "Kagawa"], ["tiles"], budget=BUDGET)
    assert ids(tasks) == ["tiles:Kagawa"]


def test_profiles_scale_with_the_machine():
    res = {"cpu": 16, "ram_total_gb": 32.0, "ram_avail_gb": 20.0, "disk_free_gb": 200}
    eco, bal, full = (studio_plan.profile_budget(p, res) for p in ("eco", "balanced", "full"))
    assert eco["cpu"] < bal["cpu"] < full["cpu"] == 16
    assert eco["mem_gb"] < bal["mem_gb"] < full["mem_gb"] <= 20.0
    small = studio_plan.profile_budget("full", {"cpu": 2, "ram_total_gb": 4, "ram_avail_gb": 2.0, "disk_free_gb": 10})
    assert small["mem_gb"] >= 4.0


def test_write_plan_roundtrips_into_a_scheduler(tmp_path, monkeypatch):
    monkeypatch.setattr(studio_plan, "STUDIO_DIR", tmp_path)
    tasks = studio_plan.make_tasks(["Kagawa"], ["graph", "tiles"], budget=BUDGET)
    path = studio_plan.write_plan(tasks, BUDGET, "test")
    sch = load_plan(path)
    assert [t.id for t in sch.tasks] == ids(tasks)
    assert sch.cpu == 16 and sch.mem_gb == 12.0
