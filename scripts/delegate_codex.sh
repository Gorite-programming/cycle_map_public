#!/usr/bin/env bash
# Claude (親) が Codex (子) にコーディング・テストを委譲するためのラッパー。
# 使い方: scripts/delegate_codex.sh [--readonly] <指示文ファイル | ->
#   指示文は Markdown。目的 / 触ってよいファイル / 受け入れ条件 / 実行するテスト を書く。
# 出力: Codex の最終報告を stdout に出し、全文ログは work/codex/<時刻>.log に残す。
# 規約: Codex は commit/push/adb を使わない (指示文に必ず書く)。実機検証は Claude が行う。
set -euo pipefail

SANDBOX="workspace-write"
if [[ "${1:-}" == "--readonly" ]]; then SANDBOX="read-only"; shift; fi
SRC="${1:--}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$ROOT/work/codex"
mkdir -p "$OUT_DIR"

PREAMBLE='あなたは Claude (親) から委譲された実装担当です。AGENTS.md に従うこと。
禁止: git commit / git push / git checkout・reset などの履歴や作業ツリーを壊す操作、adb、指示にないファイルの編集。
最後に必ず「変更したファイル」「実行したテストと結果」「未解決の点」を簡潔に報告すること。

--- 以下が指示 ---
'

# CODEX_MODEL (例: gpt-6.1-sol) を指定すると、~/.codex/config.toml の model を上書きする。
# 既定のモデルが ChatGPT アカウントで使えないとき (model_not_found) のフォールバック用。
MODEL_ARGS=()
[[ -n "${CODEX_MODEL:-}" ]] && MODEL_ARGS=(-m "$CODEX_MODEL")

{ printf '%s' "$PREAMBLE"; cat "$SRC"; } | codex exec "${MODEL_ARGS[@]}" \
  --cd "$ROOT" \
  --sandbox "$SANDBOX" \
  -c sandbox_workspace_write.network_access=true \
  -c "sandbox_workspace_write.writable_roots=[\"$HOME/.gradle\"]" \
  --output-last-message "$OUT_DIR/$STAMP.last.md" \
  - 2>&1 | tee "$OUT_DIR/$STAMP.log" || { echo "codex failed; see $OUT_DIR/$STAMP.log" >&2; exit 1; }

echo
echo "===== Codex 最終報告 ====="
cat "$OUT_DIR/$STAMP.last.md"
