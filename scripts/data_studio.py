"""CycleMap データ工房: 県ごとのデータ生成を、画面から選んで、PC の性能を使い切って流すツール (Streamlit)。

経路グラフ + ZIP、地図タイル、地形、CH、県またぎの通り口、端末への転送を、依存の順に、CPU・メモリの枠に収まる限り
並列に実行する。実行は別プロセス (scripts/studio_engine.py) なので、この画面を閉じても止まらない。

起動 (リポジトリ直下から):
    venv\\Scripts\\streamlit run scripts/data_studio.py          # Windows
    venv/bin/streamlit run scripts/data_studio.py               # Mac
    scripts\\data_studio.cmd                                      # Windows (ダブルクリック)
"""
from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import streamlit as st  # noqa: E402

from scripts import studio_plan as sp  # noqa: E402
from scripts.prefectures import BY_NAME, PREFECTURES, REGIONS  # noqa: E402

st.set_page_config(page_title="CycleMap データ工房", page_icon="🗺️", layout="wide")

JP = {p["name"]: p["jp"] for p in PREFECTURES}
ICON = {"pending": "⏳", "running": "🔄", "done": "✅", "failed": "❌", "skipped": "⏭️", "stopped": "⏹️"}
STEP_LABEL = {"dem": "標高取得", "graph": "グラフ", "tiles": "タイル", "terrain": "地形", "ch": "CH", "portals": "通り口", "push": "転送"}


def pref_label(name: str) -> str:
    return "%s (%s)" % (JP.get(name, name), name)


def fmt_sec(sec) -> str:
    if sec is None:
        return "-"
    sec = int(sec)
    return "%d:%02d:%02d" % (sec // 3600, sec % 3600 // 60, sec % 60) if sec >= 3600 else "%d:%02d" % (sec // 60, sec % 60)


# ------------------------------------------------------------------ サイドバー: PC の状態

@st.fragment(run_every=3)
def system_monitor():
    import psutil
    res = sp.resources()
    cpu = psutil.cpu_percent(interval=None)
    vm = psutil.virtual_memory()
    st.markdown("**この PC**")
    st.progress(min(cpu / 100, 1.0), text="CPU %d%% (%d スレッド)" % (cpu, res["cpu"]))
    st.progress(vm.percent / 100, text="メモリ %.1f / %.1f GB (空き %.1f)" % (vm.used / 1e9, vm.total / 1e9, vm.available / 1e9))
    st.caption("ディスク空き %.0f GB" % res["disk_free_gb"])


with st.sidebar:
    st.title("🗺️ データ工房")
    system_monitor()
    st.divider()
    tool = sp.osmium_dir()
    st.caption(("✅ osmium: " + tool) if tool else "⚠️ osmium が見つかりません (経路グラフが作れません)")
    st.caption("✅ bash あり" if sp.find_bash() else "⚠️ bash が無いので端末への転送は使えません")

tab_make, tab_run, tab_history, tab_other = st.tabs(["① 作る", "② 実行状況", "③ 履歴", "④ ほかのツール"])


# ------------------------------------------------------------------ ① 作る

with tab_make:
    st.subheader("県を選ぶ")
    if "prefs" not in st.session_state:
        st.session_state["prefs"] = []
    state = {name: sp.existing(name) for name in BY_NAME}

    cols = st.columns([1, 1, 1, 1, 1, 3])
    if cols[0].button("全国 47 県"):
        st.session_state["prefs"] = list(BY_NAME)
    if cols[1].button("未生成の県"):
        st.session_state["prefs"] = [n for n in BY_NAME if not state[n]["graph"]]
    if cols[2].button("ZIP がある県"):
        st.session_state["prefs"] = [n for n in BY_NAME if state[n]["graph"]]
    if cols[3].button("中国 5 県"):
        st.session_state["prefs"] = ["Hiroshima", "Okayama", "Yamaguchi", "Shimane", "Tottori"]
    if cols[4].button("全部外す"):
        st.session_state["prefs"] = []
    region_cols = st.columns(len(REGIONS))
    for col, (region, names) in zip(region_cols, REGIONS.items()):
        if col.button("＋ " + region, key="region_" + region):
            st.session_state["prefs"] = list(dict.fromkeys(st.session_state["prefs"] + names))
    selected = st.multiselect("選んだ県", list(BY_NAME), key="prefs", format_func=pref_label)

    rows = []
    for name in BY_NAME:
        e = state[name]
        rows.append({"選択": "●" if name in selected else "", "県": pref_label(name), "検索DB": "✓" if e["search_db"] else "–",
                     "グラフ ZIP": "✓" if e["graph"] else "–", "タイル": "✓" if e["tiles"] else "–",
                     "地形": "✓" if e["terrain"] else "–", "CH": "✓" if e["ch"] else "–"})
    with st.expander("47 県の成果物の状況 (packages/ と検索 DB)", expanded=False):
        st.dataframe(rows, hide_index=True, use_container_width=True, height=420)

    st.subheader("作る物")
    step_cols = st.columns(len(sp.STEPS))
    chosen = []
    defaults = {"graph": True, "tiles": True, "terrain": True, "ch": True, "portals": True, "push": False}
    for col, (key, label, help_text) in zip(step_cols, sp.STEPS):
        if col.checkbox(label, value=defaults[key], help=help_text, key="step_" + key):
            chosen.append(key)
    opt1, opt2, opt3 = st.columns(3)
    force = opt1.checkbox("既にあるものも作り直す", value=False, help="オフなら、有効な成果物がある県・工程は各スクリプトが省略します")
    grade = opt2.checkbox("勾配を経路グラフに入れる", value=True,
                          help="標高タイルを先に取得してからグラフを作る。オフだと坂道を避ける「おすすめ」が効きません (沖縄は最初これが無し)")
    package = opt3.selectbox("転送先アプリ", ["com.gorite.cyclemap.test", "com.gorite.cyclemap.test.win", "com.gorite.cyclemap.test.field"],
                             help="「端末へ転送」を選んだときだけ使う")

    st.subheader("性能")
    res = sp.resources()
    profile = st.radio("プロファイル", list(sp.PROFILES), index=2, format_func=lambda k: sp.PROFILES[k], horizontal=True)
    budget = sp.profile_budget(profile, res)
    with st.expander("枠を細かく決める", expanded=False):
        c1, c2, c3, c4, c5 = st.columns(5)
        budget["cpu"] = c1.number_input("CPU 枠 (スレッド)", 1, 64, int(budget["cpu"]), key="cpu_" + profile)
        budget["mem_gb"] = c2.number_input("メモリ枠 (GB)", 2.0, 256.0, float(budget["mem_gb"]), step=0.5, key="mem_" + profile)
        budget["graph_heap_gb"] = c3.number_input("グラフ JVM の最初のヒープ (GB)", 1.0, 16.0, float(budget["graph_heap_gb"]), step=0.5,
                                                   key="gheap_" + profile, help="足りない県は自動で倍にして再実行します (上限 12GB)")
        budget["ch_heap_gb"] = c4.number_input("CH JVM の最初のヒープ (GB)", 1.0, 16.0, float(budget["ch_heap_gb"]), step=0.5, key="cheap_" + profile)
        budget["terrain_workers"] = c5.number_input("地形の並列数", 1, 32, int(budget["terrain_workers"]), key="tw_" + profile)
    st.caption("この PC: %d スレッド、メモリ %.1f GB (空き %.1f GB)、ディスク空き %.0f GB。"
               "枠に収まる限り、依存の済んだ工程を同時に走らせます。" % (res["cpu"], res["ram_total_gb"], res["ram_avail_gb"], res["disk_free_gb"]))

    st.subheader("実行")
    problems = []
    if not selected:
        problems.append("県を選んでください")
    if not chosen:
        problems.append("作る物を選んでください")
    if "graph" in chosen and not sp.osmium_dir():
        problems.append("osmium が見つからないので経路グラフは作れません")
    if "push" in chosen and not sp.find_bash():
        problems.append("bash が見つからないので端末へ転送できません")
    need = sp.estimate_disk_gb(selected, chosen)
    if need > res["disk_free_gb"] * 0.9:
        problems.append("ディスクが足りません (概算 %.0f GB 要る / 空き %.0f GB)" % (need, res["disk_free_gb"]))
    if selected and chosen:
        tasks = sp.make_tasks(selected, chosen, budget=budget, force=force, grade=grade, package=package)
        by_step = {}
        for t in tasks:
            by_step[t.step] = by_step.get(t.step, 0) + 1
        st.write("**%d 県 × %d 工程 = %d ジョブ** (%s)。ディスクの概算 %.0f GB。"
                 % (len(selected), len(chosen), len(tasks), "、".join("%s %d" % (STEP_LABEL[k], v) for k, v in by_step.items()), need))
    for p in problems:
        st.warning(p)
    running_now = [r for r in sp.list_runs(5) if sp.engine_alive(r)]
    if running_now:
        st.info("いま実行中のジョブがあります (%s)。並べて流すと枠を取り合うので、終わってから始めるのがおすすめです。" % running_now[0].name)
    if st.button("▶ 実行を開始", type="primary", disabled=bool(problems)):
        path = sp.write_plan(tasks, budget, name=", ".join(selected))
        pid = sp.launch(path)
        st.session_state["run_dir"] = str(path.parent)
        st.success("開始しました (実行 %s、pid %d)。「② 実行状況」で進み具合を見られます。この画面を閉じても止まりません。" % (path.parent.name, pid))


# ------------------------------------------------------------------ ② 実行状況

def render_status(run_dir: Path) -> None:
    status = sp.read_status(run_dir)
    alive = sp.engine_alive(run_dir)
    if status is None:
        st.info("まだ状態がありません (エンジンの起動待ち)。" if alive else "状態ファイルがありません。")
        return
    tasks = status["tasks"]
    counts = {k: sum(1 for t in tasks if t["status"] == k) for k in ICON}
    total = len(tasks)
    finished = status.get("finished")
    head = "終了" if finished else ("実行中" if alive else "停止 (エンジンが動いていません)")
    st.markdown("### %s — %s" % (run_dir.name, head))
    st.progress((counts["done"] + counts["failed"] + counts["skipped"] + counts["stopped"]) / max(total, 1),
                text="完了 %d / %d　失敗 %d　省略 %d　実行中 %d" % (counts["done"], total, counts["failed"], counts["skipped"], counts["running"]))
    used, budget = status["used"], status["budget"]
    c1, c2, c3 = st.columns(3)
    c1.metric("CPU 枠の使用", "%d / %d" % (used["cpu"], budget["cpu"]))
    c2.metric("メモリ枠の使用", "%.1f / %.1f GB" % (used["mem_gb"], budget["mem_gb"]))
    c3.metric("経過", fmt_sec((finished or time.time()) - status["started"]))
    if alive:
        if st.button("⏹ 停止する", key="stop_" + run_dir.name, help="走っているジョブを子プロセスごと止め、残りを省略します"):
            sp.request_stop(run_dir)
            st.warning("停止を依頼しました。数秒で止まります。")
    now = time.time()
    rows = []
    for t in tasks:
        elapsed = (t["ended"] or now) - t["started"] if t["started"] else None
        rows.append({"状態": ICON.get(t["status"], t["status"]), "工程": STEP_LABEL.get(t["step"], t["step"]), "内容": t["label"],
                     "経過": fmt_sec(elapsed), "試行": t["attempts"], "ヒープ": ("%.0fg" % t["heap_gb"]) if t["heap_gb"] else "",
                     "メモ": t["note"]})
    st.dataframe(rows, hide_index=True, use_container_width=True, height=min(60 + 35 * len(rows), 520))
    choices = {"%s %s" % (ICON.get(t["status"], ""), t["label"]): t for t in tasks if t["started"]}
    if choices:
        pick = st.selectbox("ログを見る", list(choices), key="logpick_" + run_dir.name)
        try:
            lines = Path(choices[pick]["log"]).read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            lines = []
        st.code("\n".join(lines[-80:]) or "(まだログがありません)", language="text")


with tab_run:
    runs = sp.list_runs(30)
    if not runs:
        st.info("まだ実行がありません。「① 作る」から始めてください。")
    else:
        current = st.session_state.get("run_dir")
        names = [r.name for r in runs]
        index = names.index(Path(current).name) if current and Path(current).name in names else 0
        picked = st.selectbox("実行", names, index=index)
        run_dir = sp.STUDIO_DIR / picked

        @st.fragment(run_every=2)
        def live():
            render_status(run_dir)

        live()


# ------------------------------------------------------------------ ③ 履歴

with tab_history:
    rows = []
    for r in sp.list_runs(50):
        s = sp.read_status(r)
        if not s:
            continue
        ts = s["tasks"]
        rows.append({"実行": r.name, "ジョブ": len(ts), "成功": sum(t["status"] == "done" for t in ts),
                     "失敗": sum(t["status"] == "failed" for t in ts), "省略": sum(t["status"] in ("skipped", "stopped") for t in ts),
                     "所要": fmt_sec((s.get("finished") or s["updated"]) - s["started"]),
                     "CPU 枠": s["budget"]["cpu"], "メモリ枠 GB": s["budget"]["mem_gb"], "状態": "実行中" if sp.engine_alive(r) else "終了"})
    st.dataframe(rows, hide_index=True, use_container_width=True) if rows else st.info("履歴はまだありません。")
    st.caption("ログと状態は work/studio/<日時>/ にあります (plan.json・status.json・logs/)。")


# ------------------------------------------------------------------ ④ ほかのツール

with tab_other:
    st.subheader("検索 DB 管理ツール")
    st.write("Overture / 国土数値情報 / OSM を統合した検索 DB (`sandbox/data-tool`) の画面です。経路グラフの ZIP に入る検索 DB はここで作ります。")
    if st.button("検索 DB ツールを別ポート (8502) で起動"):
        flags = 0
        if sys.platform == "win32":
            flags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | getattr(subprocess, "CREATE_NO_WINDOW", 0)
        subprocess.Popen([sys.executable, "-m", "streamlit", "run", str(ROOT / "sandbox" / "data-tool" / "tools" / "gui.py"),
                          "--server.port", "8502", "--server.address", "127.0.0.1", "--server.headless", "true"], cwd=str(ROOT / "sandbox" / "data-tool"),
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=flags)
        st.success("起動しました: http://localhost:8502 (数秒かかります)")
    st.subheader("実機 (adb)")
    if st.button("接続を確かめる"):
        try:
            out = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=15).stdout
            st.code(out)
        except Exception as exc:
            st.error("adb を実行できません: %s" % exc)
