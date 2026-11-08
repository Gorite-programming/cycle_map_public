#!/usr/bin/env bash
# =============================================================================
# scripts/rebuild_and_deploy.sh - 検索DB再生成 → 県別ZIP作成 → 実機転送 を一括実行
# =============================================================================
#
# 段階:
#   1. db       sandbox/data-tool/rebuild_all.py   検索DBを再生成 (out/<県>.search.db)
#   2. package  build_prefectures.py --force       graph 生成 + packages/<県>.zip
#   3. push     scripts/push_data.sh               実機へ転送 + SHA-256検証
#
# 使い方:
#   scripts/rebuild_and_deploy.sh --dry-run               # 全段階の予定を表示
#   scripts/rebuild_and_deploy.sh                         # 5県すべて
#   scripts/rebuild_and_deploy.sh --pref hiroshima,yamaguchi
#   scripts/rebuild_and_deploy.sh --skip push             # 実機転送だけ省く
#   scripts/rebuild_and_deploy.sh --overture-release latest --extract-from data/japan-latest.osm.pbf
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
PY="${ROOT_DIR}/venv/bin/python"
ALL_PREFS="tottori,shimane,okayama,hiroshima,yamaguchi"

PREFS="$ALL_PREFS"
SKIP=""
DRY_RUN=false
DB_ARGS=()

usage() {
    sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
    cat <<EOF

オプション:
  --pref a,b,..            対象の県 (小文字ID。既定: 5県すべて)
  --skip db,package,push   省く段階 (カンマ区切り)
  --dry-run                実行せず予定だけ表示 (db と push は各スクリプトの dry-run)
  --extract-from PBF       db 段階: 日本全体PBFから県別PBFを切り出し直す
  --overture-release REL   db 段階: Overture の版 ('latest' 可)
  --pykakasi               db 段階: 読み仮名を補完
  --exclude-named          db 段階: named カテゴリを除外
  --keep-going             db 段階: 県が失敗しても続行
  --progress-interval SEC  db 段階: 進捗表示の間隔 (既定 30)
  -h, --help
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pref) PREFS="$2"; shift 2 ;;
        --skip) SKIP="$2"; shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        --extract-from|--overture-release|--progress-interval)
            DB_ARGS+=("$1" "$2"); shift 2 ;;
        --pykakasi|--exclude-named|--keep-going)
            DB_ARGS+=("$1"); shift ;;
        -h|--help) usage ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

skipped() { [[ ",${SKIP}," == *",$1,"* ]]; }

# --skip の打ち間違いで意図しない段階 (実機転送など) が走らないよう、値を検証する
if [[ -n "$SKIP" ]]; then
    IFS=',' read -r -a SKIP_IDS <<< "$SKIP"
    for s in "${SKIP_IDS[@]}"; do
        case "$s" in
            db|package|push) ;;
            *) echo "不明な --skip 段階: ${s} (db,package,push のいずれか)" >&2; exit 2 ;;
        esac
    done
fi

# 検索DB(--extract-from の PBF)とグラフ(build_prefectures.py の SOURCE_PBF)の OSM 時点をそろえる
SOURCE_PBF_DEFAULT="${ROOT_DIR}/data/japan-latest.osm.pbf"  # ルート直下のシンボリックリンクの実体
EXTRACT_FROM=""
for ((i = 0; i < ${#DB_ARGS[@]}; i++)); do
    [[ "${DB_ARGS[$i]}" == "--extract-from" ]] && EXTRACT_FROM="${DB_ARGS[$((i + 1))]}"
done
if [[ -n "$EXTRACT_FROM" ]]; then
    real() { python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$1"; }
    if [[ "$(real "$EXTRACT_FROM")" != "$(real "$SOURCE_PBF_DEFAULT")" ]]; then
        echo "警告: --extract-from (${EXTRACT_FROM}) が build_prefectures.py の入力 (${SOURCE_PBF_DEFAULT}) と異なります。" >&2
        echo "      検索DBとグラフの OSM データ時点がずれます。" >&2
        if ! $DRY_RUN; then
            read -r -p "このまま続行しますか? [y/N] " ans
            [[ "$ans" == [yY] ]] || exit 1
        fi
    fi
fi

# 県ID (小文字) を build_prefectures.py / push_data.sh 用の先頭大文字名に変換
cap() { local s; s="$(tr '[:upper:]' '[:lower:]' <<< "$1")"; echo "$(tr '[:lower:]' '[:upper:]' <<< "${s:0:1}")${s:1}"; }

IFS=',' read -r -a PREF_IDS <<< "$PREFS"
NAMES=()
for p in "${PREF_IDS[@]}"; do
    case ",${ALL_PREFS}," in
        *",${p},"*) NAMES+=("$(cap "$p")") ;;
        *) echo "不明な県ID: ${p}" >&2; exit 2 ;;
    esac
done
ONLY_ARG="$(IFS=','; echo "${NAMES[*]}")"

[[ -x "$PY" ]] || { echo "venv が見つかりません: $PY" >&2; exit 1; }

START=$(date +%s)
stage() { echo; echo "######## [$1] $2 ($(date '+%H:%M:%S'), 開始から $(( $(date +%s) - START ))秒)"; }

echo "対象: ${PREFS} / 省略: ${SKIP:-なし} / dry-run: ${DRY_RUN}"

# --- 1. 検索DB再生成 ---------------------------------------------------------
if ! skipped db; then
    stage db "検索DB再生成"
    DRY_FLAG=(); $DRY_RUN && DRY_FLAG=(--dry-run)
    "$PY" sandbox/data-tool/rebuild_all.py --pref "$PREFS" ${DB_ARGS[@]+"${DB_ARGS[@]}"} ${DRY_FLAG[@]+"${DRY_FLAG[@]}"}
fi

# --- 2. graph 生成 + ZIP 作成 ------------------------------------------------
if ! skipped package; then
    stage package "graph生成・ZIP作成 (${ONLY_ARG})"
    IMPORTER="application/osm-importer/build/install/osm-importer/bin/osm-importer"
    if $DRY_RUN; then
        [[ -x "$IMPORTER" ]] || echo "(osm-importer 未ビルド: 実行時に installDist を行います)"
        echo "$PY build_prefectures.py --only ${ONLY_ARG} --force"
    else
        if [[ ! -x "$IMPORTER" ]]; then
            echo "osm-importer が未ビルドのため installDist を実行します"
            (cd application && ./gradlew :osm-importer:installDist)
        fi
        "$PY" build_prefectures.py --only "$ONLY_ARG" --force
    fi
fi

# --- 3. 実機転送 -------------------------------------------------------------
if ! skipped push; then
    stage push "実機へ転送 (${ONLY_ARG})"
    PUSH_FLAG=(); $DRY_RUN && PUSH_FLAG=(--dry-run)
    if [[ "${#NAMES[@]}" -eq 5 ]]; then
        scripts/push_data.sh ${PUSH_FLAG[@]+"${PUSH_FLAG[@]}"}
    else
        # push_data.sh は --pref を1県ずつ受け取る
        for n in "${NAMES[@]}"; do
            scripts/push_data.sh --pref "$n" ${PUSH_FLAG[@]+"${PUSH_FLAG[@]}"}
        done
    fi
fi

echo
echo "######## 完了 (所要 $(( $(date +%s) - START ))秒)"
