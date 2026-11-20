#!/usr/bin/env bash
# CI (.github/workflows/ci.yml) と同じ確認を手元で実行する。
# 使い方: scripts/check_all.sh [--skip-gradle]
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
PY="$ROOT/venv/bin/python"
[ -x "$PY" ] || PY="$ROOT/venv/Scripts/python.exe"
[ -x "$PY" ] || PY=$(command -v python3 2>/dev/null || command -v python)

hash_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256
  else
    "$PY" -c "import hashlib, sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())"
  fi
}

if [ "${1:-}" != "--skip-gradle" ]; then
  echo "== Kotlin/JVM tests =="
  (cd application && ./gradlew test --offline)
fi
echo "== data-tool pytest =="
(cd sandbox/data-tool && "$PY" -m pytest -q)
echo "== style patch idempotence =="
before=$(git diff -- application/app/src/main/assets/styles | hash_stdin)
"$PY" scripts/patch_map_style.py >/dev/null
after=$(git diff -- application/app/src/main/assets/styles | hash_stdin)
[ "$before" = "$after" ] || { echo "patch_map_style.py がスタイルを変更しました"; exit 1; }
echo "== shell / python syntax =="
for f in scripts/*.sh; do bash -n "$f"; done
"$PY" -m py_compile scripts/*.py build_prefectures.py
echo "== script tests =="
"$PY" -m pytest -q scripts/tests
echo "ALL OK"
