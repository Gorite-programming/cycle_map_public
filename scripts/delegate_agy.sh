#!/usr/bin/env bash
# Claude (親) が Antigravity (子, agy CLI / Gemini 4 Hydrogen) に作業を委譲するためのラッパー。
# 使い方: scripts/delegate_agy.sh [--readonly] [--model <id>] <指示文ファイル | ->
#   指示文は Markdown。目的 / 触ってよいファイル / 受け入れ条件 / 実行するテスト を書く。
#   --readonly は plan モード (ファイルを書き換えない)。既定は auto モード。
# 出力: Antigravity の最終報告を stdout に出し、全文は work/agy/<時刻>.log に残す。
# 注意: 無人で動かすため --dangerously-skip-permissions で全コマンドを許可している
#   (2026-11-15 Gorite 承認)。禁止事項は下の前置きでのみ伝わるので、Claude は返ってきた
#   diff と git status を必ず確かめる。
set -euo pipefail

MODE="auto"
MODEL="${AGY_MODEL:-gemini-4-hydrogen-high}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --readonly) MODE="plan"; shift ;;
    --model) MODEL="$2"; shift 2 ;;
    *) break ;;
  esac
done
SRC="${1:--}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$ROOT/work/agy"
mkdir -p "$OUT_DIR"

PREAMBLE='あなたは Claude (親) から委譲された担当です。AGENTS.md に従うこと。
禁止: git commit / git push / git checkout・reset・stash などの履歴や作業ツリーを壊す操作、adb、
指示にないファイルの編集・削除、data/ work/ venv/ Archives/ への書き込み。
最後に必ず「変更したファイル」「実行したテストと結果」「未解決の点」を簡潔に報告すること。

--- 以下が指示 ---
'

PROMPT="$PREAMBLE$(cat "$SRC")"

cd "$ROOT"
agy -p "$PROMPT" \
  --model "$MODEL" \
  --mode "$MODE" \
  --dangerously-skip-permissions \
  --print-timeout 0 \
  2>&1 | tee "$OUT_DIR/$STAMP.log" || { echo "agy failed; see $OUT_DIR/$STAMP.log" >&2; exit 1; }
