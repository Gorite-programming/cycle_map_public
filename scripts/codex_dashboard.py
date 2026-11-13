"""Read-only, localhost dashboard for delegate_codex.sh runs."""

import argparse
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
from urllib.parse import parse_qs, urlsplit


DEFAULT_RUNS_DIR = Path(__file__).resolve().parents[1] / "work" / "codex"
TIMESTAMP = re.compile(r"[0-9]{8}-[0-9]{6}")
RUN_FILE = re.compile(r"([0-9]{8}-[0-9]{6})\.(?:log|last\.md)")


def run_file(directory, timestamp, suffix):
    """Only return regular files inside the configured directory."""
    path = directory / f"{timestamp}.{suffix}"
    try:
        resolved = path.resolve()
        if resolved.is_relative_to(directory) and resolved.is_file():
            return resolved
    except (OSError, RuntimeError):
        pass
    return None


def list_runs(directory):
    try:
        timestamps = {
            match.group(1)
            for path in directory.iterdir()
            if (match := RUN_FILE.fullmatch(path.name))
        }
    except OSError:
        return []
    runs = []
    for timestamp in sorted(timestamps, reverse=True):
        log = run_file(directory, timestamp, "log")
        report = run_file(directory, timestamp, "last.md")
        try:
            files = [path.stat() for path in (log, report) if path]
            if not files:
                continue
            summary = ""
            if report:
                with report.open(encoding="utf-8", errors="replace") as handle:
                    summary = handle.readline(80).rstrip("\r\n")
            runs.append({
                "timestamp": timestamp,
                "status": "done" if report else "running",
                "summary": summary,
                "log_bytes": log.stat().st_size if log else 0,
                "updated": datetime.fromtimestamp(
                    max(stat.st_mtime for stat in files), timezone.utc
                ).isoformat(),
            })
        except OSError:
            continue  # A writer may rename/remove a run between reads.
    return runs


def git_state(directory):
    empty = {"count": 0, "entries": [], "diff_stat": ""}
    try:
        def git(*args):
            return subprocess.run(
                ["git", "--no-optional-locks", *args], cwd=directory,
                capture_output=True, encoding="utf-8", errors="replace",
                timeout=5, check=True,
            ).stdout

        entries = git("status", "--porcelain").splitlines()
        stat = git("diff", "--stat").strip().splitlines()
        return {"count": len(entries), "entries": entries[:50],
                "diff_stat": stat[-1].strip() if stat else ""}
    except (OSError, subprocess.SubprocessError):
        return empty


HTML = r'''<!doctype html>
<html lang="ja"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Codex 進行ダッシュボード</title>
<style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,sans-serif;background:#0c111b;color:#e4eaf3}
*{box-sizing:border-box}body{margin:0}header,main{max-width:1440px;margin:auto;padding:24px}
header{display:flex;align-items:center;justify-content:space-between;gap:16px;padding-bottom:8px}
h1{font-size:22px;letter-spacing:.03em;margin:6px 0}h2{font-size:14px;margin:0;color:#b4c2d6}
.eyebrow{color:#8ba4ce;font-size:11px;letter-spacing:.16em}#connection{color:#8e9eb5;font-size:12px}
.workspace{display:grid;grid-template-columns:300px minmax(0,1fr);gap:20px;align-items:start}
.panel{border:1px solid #263247;border-radius:14px;background:#131c2b;overflow:hidden;min-width:0}
.heading{padding:18px 20px;border-bottom:1px solid #263247;display:flex;justify-content:space-between;gap:12px;align-items:center}
#runs{max-height:700px;overflow:auto;padding:10px}.run{width:100%;text-align:left;color:inherit;background:transparent;border:1px solid transparent;border-radius:9px;padding:14px 12px;cursor:pointer;margin:2px 0}
.run:hover{background:#1c2940}.run.selected{background:#1b2c47;border-color:#46699c}.run-top{display:flex;justify-content:space-between;align-items:center;gap:8px}
.stamp{font:12px ui-monospace,Consolas,monospace}.badge{font-size:10px;padding:4px 8px;border-radius:20px;background:#3a3220;color:#e4c683}.badge.done{background:#163c32;color:#86ddb4}
.summary{display:block;font-size:12px;color:#a5b4ca;margin-top:10px;line-height:1.6;overflow-wrap:anywhere}.meta{font-size:11px;color:#7e92ae;margin-top:8px;display:block}
.detail{display:grid;gap:20px}pre{margin:0;padding:20px;white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.8 ui-monospace,SFMono-Regular,Consolas,monospace;tab-size:4}
#report{max-height:380px;overflow:auto}#log{max-height:500px;overflow:auto;color:#b9c9df;background:#0e1725}
.git{margin-top:20px}.muted{padding:24px;color:#899bb5;font-size:13px}#selected{font-size:11px;color:#8e9eb5}
@media(max-width:760px){header,main{padding:16px}header{align-items:flex-start;flex-direction:column;gap:4px}.workspace{grid-template-columns:minmax(0,1fr)}#runs{max-height:260px}.heading{padding:16px}pre{padding:16px}h1{font-size:20px}}
</style></head><body>
<header><div><span class="eyebrow">CYCLEMAP / CODEX</span><h1>委譲の進行ダッシュボード</h1></div><span id="connection" role="status">接続中…</span></header>
<main><div class="workspace"><aside class="panel"><div class="heading"><h2>実行一覧</h2><span id="total" class="meta"></span></div><div id="runs"></div></aside>
<div class="detail"><section class="panel"><div class="heading"><h2>最終報告</h2><span id="selected"></span></div><pre id="report">実行を選択してください。</pre></section>
<section class="panel"><div class="heading"><h2>ログ末尾</h2><span class="meta">最新 200 行</span></div><pre id="log">ログはまだありません。</pre></section></div></div>
<section class="panel git"><div class="heading"><h2>作業ツリーの変更</h2><span id="git-count" class="meta"></span></div><pre id="git">取得中…</pre></section></main>
<script>
const el=id=>document.getElementById(id);let selected=null;
async function api(path){const r=await fetch(path,{cache:'no-store'});if(!r.ok)throw new Error(r.status);return r.json()}
async function detail(){const ts=selected;if(!ts){el('selected').textContent='';el('report').textContent='実行はまだありません。';el('log').textContent='ログはまだありません。';return}
 const run=await api('/api/run?ts='+encodeURIComponent(ts));if(ts!==selected)return;
 el('selected').textContent=ts;el('report').textContent=run.report===null?'実行中です。最終報告を待っています。':run.report;
 const log=el('log'),bottom=log.scrollHeight-log.scrollTop-log.clientHeight<40;
 log.textContent=run.log_tail||'ログはまだありません。';if(bottom)log.scrollTop=log.scrollHeight;
}
async function refresh(){try{
 const [runs,git]=await Promise.all([api('/api/runs'),api('/api/git')]);
 if(!runs.some(r=>r.timestamp===selected))selected=runs.length?runs[0].timestamp:null;
 const list=el('runs'),scroll=list.scrollTop;list.replaceChildren();el('total').textContent=runs.length+' 件';
 if(!runs.length){const empty=document.createElement('div');empty.className='muted';empty.textContent='実行ログはまだありません。';list.append(empty)}
 for(const run of runs){const button=document.createElement('button');button.type='button';button.className='run'+(run.timestamp===selected?' selected':'');button.setAttribute('aria-pressed',run.timestamp===selected);
 const top=document.createElement('span');top.className='run-top';const stamp=document.createElement('span');stamp.className='stamp';stamp.textContent=run.timestamp;
 const badge=document.createElement('span');badge.className='badge '+run.status;badge.textContent=run.status;top.append(stamp,badge);
 const summary=document.createElement('span');summary.className='summary';summary.textContent=run.summary||(run.status==='running'?'実行中 · 報告待ち':'報告の見出しなし');
 const meta=document.createElement('span');meta.className='meta';meta.textContent=(run.log_bytes/1024).toFixed(1)+' KB · '+new Date(run.updated).toLocaleString('ja-JP');
 button.append(top,summary,meta);button.onclick=()=>{selected=run.timestamp;refresh()};list.append(button)}list.scrollTop=scroll;
 el('git-count').textContent=git.count+' 件';el('git').textContent=[git.entries.join('\n'),git.count>50?'… 先頭 50 件を表示':'',git.diff_stat].filter(Boolean).join('\n\n')||'変更なし（または Git 情報を取得できません）。';
 await detail();el('connection').textContent='2 秒ごとに自動更新 · '+new Date().toLocaleTimeString('ja-JP');
 }catch(error){el('connection').textContent='接続できません。自動で再試行します。'}}
let refreshing=false;async function tick(){if(refreshing)return;refreshing=true;try{await refresh()}finally{refreshing=false}}
tick();setInterval(tick,2000);
</script></body></html>'''


def create_server(directory=DEFAULT_RUNS_DIR, port=8765, git_directory=None):
    directory = Path(directory).resolve()
    git_directory = Path(git_directory or Path(__file__).resolve().parents[1])

    class Handler(BaseHTTPRequestHandler):
        def send_payload(self, payload, status=200, html=False):
            body = (payload if html else json.dumps(payload, ensure_ascii=False)).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8" if html
                             else "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            url = urlsplit(self.path)
            if url.path == "/":
                self.send_payload(HTML, html=True)
            elif url.path == "/api/runs":
                self.send_payload(list_runs(directory))
            elif url.path == "/api/git":
                self.send_payload(git_state(git_directory))
            elif url.path == "/api/run":
                query = parse_qs(url.query, keep_blank_values=True)
                timestamps = query.get("ts", [])
                if len(timestamps) != 1 or not TIMESTAMP.fullmatch(timestamps[0]):
                    self.send_payload({"error": "不正な timestamp です。"}, 400)
                    return
                try:
                    tails = query.get("tail", ["200"])
                    if len(tails) != 1:
                        raise ValueError
                    tail = min(1000, max(1, int(tails[0])))
                except ValueError:
                    self.send_payload({"error": "tail は整数で指定してください。"}, 400)
                    return
                timestamp = timestamps[0]
                report = run_file(directory, timestamp, "last.md")
                log = run_file(directory, timestamp, "log")
                try:
                    report_text = report.read_text(encoding="utf-8", errors="replace") if report else None
                    log_tail = ""
                    if log:
                        with log.open(encoding="utf-8", errors="replace") as handle:
                            log_tail = "".join(deque(handle, maxlen=tail))
                    self.send_payload({"report": report_text, "log_tail": log_tail})
                except OSError:
                    self.send_payload({"error": "実行ファイルを読み取れません。"}, 503)
            else:
                self.send_payload({"error": "見つかりません。"}, 404)

        def log_message(self, format, *args):
            pass

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dir", type=Path, default=DEFAULT_RUNS_DIR)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args(argv)
    with create_server(args.dir, args.port) as server:
        print(f"Codex dashboard: http://127.0.0.1:{server.server_port}/", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
