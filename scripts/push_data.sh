#!/usr/bin/env bash
# =============================================================================
# scripts/push_data.sh - Deploy CycleMap offline data to connected Android device
# =============================================================================
#
# Target directory on device:
#   /sdcard/Android/data/com.gorite.cyclemap.test/files/Documents/CycleMap/
#   (getExternalFilesDir(DOCUMENTS)/CycleMap/)
#
# Files deployed per prefecture:
#   - <Prefecture>.search.db (Overture + MLIT + OSM integrated POI search database)
#   - <Prefecture>.graph     (OSM routing graph)
#   - <Prefecture>.graph.idx (Routing graph node index)
#   - <Prefecture>.graph.ch  (Contraction Hierarchies sidecar; optional, app falls back to A*)
#   - <prefecture>.pmtiles   (Offline vector map tiles; built by scripts/build_tiles.py)
# Files deployed once (not per prefecture):
#   - <A>__<B>.portals       (Precomputed cross-prefecture portals; built by scripts/build_portals.py; optional)
#   - <prefecture>.contours.pmtiles / <prefecture>.hillshade.pmtiles (terrain overlays; built by scripts/build_terrain.py)
#
# =============================================================================
set -euo pipefail

PACKAGE_NAME="${CYCLEMAP_PACKAGE_NAME:-com.gorite.cyclemap.test}"
DEST_DIR="/sdcard/Android/data/${PACKAGE_NAME}/files/Documents/CycleMap"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

DRY_RUN=false
DB_ONLY=false
TILES_ONLY=false
TARGET_PREF=""

ALL_PREFS=("Hokkaido" "Aomori" "Iwate" "Miyagi" "Akita" "Yamagata" "Fukushima"     "Ibaraki" "Tochigi" "Gunma" "Saitama" "Chiba" "Tokyo" "Kanagawa" "Niigata"     "Toyama" "Ishikawa" "Fukui" "Yamanashi" "Nagano" "Gifu" "Shizuoka" "Aichi"     "Mie" "Shiga" "Kyoto" "Osaka" "Hyogo" "Nara" "Wakayama" "Tottori" "Shimane"     "Okayama" "Hiroshima" "Yamaguchi" "Tokushima" "Kagawa" "Ehime" "Kochi"     "Fukuoka" "Saga" "Nagasaki" "Kumamoto" "Oita" "Miyazaki" "Kagoshima" "Okinawa")

usage() {
    cat <<EOF
Usage: $0 [options]

Deploy CycleMap offline data (*.search.db, *.graph, *.graph.idx) to an Android device.

Options:
  --dry-run       Show transfer plan and local hashes without making any changes
  --db-only       Transfer only search.db files (skip routing graph and map tile files)
  --tiles-only    Transfer only vector map tiles (packages/<prefecture>.pmtiles)
  --pref <name>   Transfer single prefecture (e.g. Yamaguchi, Hiroshima, Okayama, Shimane, Tottori)
  -h, --help      Show this help message
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        --db-only)
            DB_ONLY=true
            shift
            ;;
        --tiles-only)
            TILES_ONLY=true
            shift
            ;;
        --pref)
            TARGET_PREF="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo "Unknown option: $1" >&2
            usage
            ;;
    esac
done

calc_sha256() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print $1}'
    else
        python3 -c "import hashlib, sys; print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$file"
    fi
}

human_size() {
    local bytes="$1"
    python3 -c "
import sys
b = int(sys.argv[1])
if b >= 1073741824:
    print(f'{b/1073741824:.2f} GiB')
elif b >= 1048576:
    print(f'{b/1048576:.2f} MiB')
elif b >= 1024:
    print(f'{b/1024:.2f} KiB')
else:
    print(f'{b} B')
" "$bytes"
}

echo "=== CycleMap Data Deployment ==="
echo "Destination: ${DEST_DIR}"
echo "Mode: $(if [[ "$DRY_RUN" == true ]]; then echo "DRY RUN (no device changes)"; else echo "LIVE DEPLOY"; fi)"
if [[ "$DB_ONLY" == true && "$TILES_ONLY" == true ]]; then
    echo "ERROR: --db-only and --tiles-only cannot be combined." >&2
    exit 2
fi
if [[ "$DB_ONLY" == true ]]; then
    echo "Filter: search.db only"
fi
if [[ "$TILES_ONLY" == true ]]; then
    echo "Filter: vector map tiles only"
fi

if [[ -n "$TARGET_PREF" ]]; then
    # Capitalize first letter
    TARGET_PREF="$(tr '[:lower:]' '[:upper:]' <<< "${TARGET_PREF:0:1}")$(tr '[:upper:]' '[:lower:]' <<< "${TARGET_PREF:1}")"
    PREFS=("$TARGET_PREF")
else
    PREFS=("${ALL_PREFS[@]}")
fi

# Check adb device connection if live mode
if [[ "$DRY_RUN" == false ]]; then
    if ! command -v adb >/dev/null 2>&1; then
        echo "ERROR: adb command not found on PATH." >&2
        exit 1
    fi
    DEVICE_LIST=$(adb devices | grep -v "List of devices" | grep "device$" || true)
    if [[ -z "$DEVICE_LIST" ]]; then
        echo "ERROR: No connected Android device found in 'device' state." >&2
        echo "Please connect device via USB and ensure USB debugging is enabled." >&2
        adb devices
        exit 1
    fi
    DEVICE_ID=$(echo "$DEVICE_LIST" | head -n1 | awk '{print $1}')
    echo "Connected device: ${DEVICE_ID}"

    echo "Stopping ${PACKAGE_NAME} to safely write files..."
    adb shell am force-stop "${PACKAGE_NAME}" || true

    echo "Ensuring remote directory exists: ${DEST_DIR}"
    adb shell mkdir -p "${DEST_DIR}"
fi

TMP_STAGE="${ROOT_DIR}/work/_push_stage"
mkdir -p "${TMP_STAGE}"
cleanup() {
    rm -rf "${TMP_STAGE}"
}
trap cleanup EXIT

TOTAL_FILES=0
TOTAL_BYTES=0

# Print size + SHA-256, then (live mode) push and verify the on-device hash.
push_file() {
    local local_path="$1"
    local file_name file_bytes local_hash remote_path remote_hash_line remote_hash
    file_name="$(basename "$local_path")"
    file_bytes="$(wc -c < "$local_path" | tr -d ' ')"
    local_hash="$(calc_sha256 "$local_path")"
    remote_path="${DEST_DIR}/${file_name}"

    echo "  [FILE] ${file_name} ($(human_size "$file_bytes"))"
    echo "         SHA-256: ${local_hash}"

    TOTAL_FILES=$((TOTAL_FILES + 1))
    TOTAL_BYTES=$((TOTAL_BYTES + file_bytes))

    if [[ "$DRY_RUN" == true ]]; then
        echo "         -> Would push to: ${remote_path}"
    else
        echo "         -> Pushing to device..."
        adb push "$local_path" "$remote_path"

        echo "         -> Verifying on-device SHA-256..."
        remote_hash_line=$(adb shell sha256sum "$remote_path" 2>/dev/null || true)
        remote_hash=$(echo "$remote_hash_line" | awk '{print $1}' | tr -d '\r\n')

        if [[ "$local_hash" != "$remote_hash" ]]; then
            echo "ERROR: Hash mismatch for ${file_name}!" >&2
            echo "  Local:  ${local_hash}" >&2
            echo "  Remote: ${remote_hash}" >&2
            exit 1
        fi
        echo "         -> Verified OK (SHA-256 match)"
    fi
}

echo ""
echo "--- Preparing and Transferring Files ---"

for pref in "${PREFS[@]}"; do
    pref_lower="$(tr '[:upper:]' '[:lower:]' <<< "$pref")"
    echo ">> Prefecture: ${pref}"

    # Files to transfer for this prefecture
    files_to_push=()

    # 1. Search DB (prefer sandbox/data-tool/out, fallback to packages zip)
    db_src="${ROOT_DIR}/sandbox/data-tool/out/${pref_lower}.search.db"
    db_staged="${TMP_STAGE}/${pref}.search.db"
    if [[ "$TILES_ONLY" == false ]]; then
        if [[ -f "$db_src" ]]; then
            cp -f "$db_src" "$db_staged"
        elif [[ -f "${ROOT_DIR}/packages/${pref}.zip" ]]; then
            unzip -p "${ROOT_DIR}/packages/${pref}.zip" "${pref}.search.db" > "$db_staged"
        else
            echo "WARNING: No search.db found for ${pref}" >&2
        fi
        if [[ -f "$db_staged" ]]; then
            files_to_push+=("$db_staged")
        fi
    fi

    # 2. Graph & Graph Index (from packages zip or work)
    if [[ "$DB_ONLY" == false && "$TILES_ONLY" == false ]]; then
        zip_file="${ROOT_DIR}/packages/${pref}.zip"
        graph_staged="${TMP_STAGE}/${pref}.graph"
        idx_staged="${TMP_STAGE}/${pref}.graph.idx"

        if [[ -f "$zip_file" ]]; then
            unzip -p "$zip_file" "${pref}.graph" > "$graph_staged" 2>/dev/null || true
            unzip -p "$zip_file" "${pref}.graph.idx" > "$idx_staged" 2>/dev/null || true
        fi
        if [[ ! -s "$graph_staged" && -f "${ROOT_DIR}/work/${pref}/${pref}.graph" ]]; then
            cp -f "${ROOT_DIR}/work/${pref}/${pref}.graph" "$graph_staged"
        fi
        if [[ ! -s "$idx_staged" && -f "${ROOT_DIR}/work/${pref}/${pref}.graph.idx" ]]; then
            cp -f "${ROOT_DIR}/work/${pref}/${pref}.graph.idx" "$idx_staged"
        fi

        if [[ -s "$graph_staged" ]]; then
            files_to_push+=("$graph_staged")
        fi
        if [[ -s "$idx_staged" ]]; then
            files_to_push+=("$idx_staged")
        fi

        # Contraction Hierarchies sidecar (optional: the app falls back to A* when it is absent).
        ch_staged="${TMP_STAGE}/${pref}.graph.ch"
        if [[ -f "$zip_file" ]]; then
            unzip -p "$zip_file" "${pref}.graph.ch" > "$ch_staged" 2>/dev/null || true
        fi
        for ch_src in "${ROOT_DIR}/packages/${pref}.graph.ch" "${ROOT_DIR}/work/${pref}/${pref}.graph.ch" "${ROOT_DIR}/work/codex/ch_out/${pref}.graph.ch"; do
            if [[ ! -s "$ch_staged" && -f "$ch_src" ]]; then
                cp -f "$ch_src" "$ch_staged"
            fi
        done
        if [[ -s "$ch_staged" ]]; then
            files_to_push+=("$ch_staged")
        else
            echo "WARNING: No contraction hierarchy (${pref}.graph.ch) for ${pref}; the app will use A* only" >&2
        fi
    fi

    # 3. Vector map tiles (packages/<prefecture>.pmtiles, transferred as-is; not staged/copied)
    if [[ "$DB_ONLY" == false ]]; then
        tiles_src="${ROOT_DIR}/packages/${pref_lower}.pmtiles"
        if [[ -s "$tiles_src" ]]; then
            files_to_push+=("$tiles_src")
        else
            echo "WARNING: No vector tiles found for ${pref} (run scripts/build_tiles.py)" >&2
        fi
        # Terrain overlays (contours / hillshade; built by scripts/build_terrain.py). Optional: the app skips them when absent.
        for kind in contours hillshade; do
            terrain_src="${ROOT_DIR}/packages/${pref_lower}.${kind}.pmtiles"
            if [[ -s "$terrain_src" ]]; then
                files_to_push+=("$terrain_src")
            else
                echo "NOTE: No ${kind} terrain tiles for ${pref} (run scripts/build_terrain.py)" >&2
            fi
        done
    fi

    for local_path in "${files_to_push[@]}"; do
        push_file "$local_path"
    done
done

# Cross-prefecture portals (packages/<A>__<B>.portals, transferred as-is). Optional: the app matches node IDs when absent or stale.
# With --pref, only the pairs that include that prefecture are sent.
if [[ "$DB_ONLY" == false && "$TILES_ONLY" == false ]]; then
    echo ">> Cross-prefecture portals"
    portal_count=0
    for portal_src in "${ROOT_DIR}"/packages/*__*.portals; do
        [[ -s "$portal_src" ]] || continue
        if [[ -n "$TARGET_PREF" ]]; then
            portal_base="$(basename "$portal_src" .portals)"
            [[ "$portal_base" == "${TARGET_PREF}__"* || "$portal_base" == *"__${TARGET_PREF}" ]] || continue
        fi
        push_file "$portal_src"
        portal_count=$((portal_count + 1))
    done
    if [[ "$portal_count" -eq 0 ]]; then
        echo "NOTE: No portal files (run scripts/build_portals.py); the app will match portals at route time" >&2
    fi
fi

echo ""
echo "=== Deployment Summary ==="
echo "Total files: ${TOTAL_FILES}"
echo "Total data size: $(human_size "$TOTAL_BYTES")"
if [[ "$DRY_RUN" == true ]]; then
    echo "Status: DRY RUN completed successfully (no files transferred)."
else
    echo "Status: ALL FILES TRANSFERRED AND VERIFIED SUCCESSFULLY."
fi
