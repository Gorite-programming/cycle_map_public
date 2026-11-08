#!/usr/bin/env bash
# =============================================================================
# scripts/capture_baseline.sh - 固定地点・固定ズームの CycleMap 画面を実機から撮影する
# =============================================================================
#
# debug ビルドの CameraDebugReceiver (adb broadcast) で地図カメラを動かし、adb screencap で撮る。
# 現在地や GPS に依存せず、各 Stage の前後で同じ地点・ズームを再現できる。
#
# 使い方:
#   scripts/capture_baseline.sh [出力先ディレクトリ]      # 既定: work/baseline/<日時>/
#
# 前提: 端末が接続・ロック解除・点灯済みで、debug ビルドの CycleMap (com.gorite.cyclemap.test) が入っていること。
# ズームは MapLibre の値。Google Maps の z とは 0.5 ずれる (MapLibre = Google z - 0.5 で縮尺が揃う)。
# 画像は work/ (git 管理外) に置く。他社地図の画面は絶対にリポジトリへ入れないこと。
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="com.gorite.cyclemap.test"
OUT="${1:-${ROOT_DIR}/work/baseline/$(date +%Y%m%d_%H%M%S)}"
mkdir -p "$OUT"

# id lat lon
LOCATIONS=(
    "1_hiroshima 34.3945 132.4553"
    "2_miyajima 34.2960 132.3198"
    "3_yamaguchi 34.1700 131.4300"
    "4_okayama 34.6666 133.9183"
    "5_matsue 35.4752 133.0506"
    "6_tottori 35.5413 134.2290"
    "7_onomichi 34.4040 133.2000"
    "8_daisen 35.3711 133.5400"
)
# 比較用の Google ズーム → MapLibre ズーム
ZOOMS=("12 11.5" "15 14.5" "17 16.5")

command -v adb >/dev/null || { echo "adb が見つかりません" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "端末が接続されていません" >&2; exit 1; }
adb shell dumpsys power | grep -q "mWakefulness=Awake" || { echo "端末の画面が消えています。ロック解除して点灯してください" >&2; exit 1; }

adb shell am force-stop "$PKG"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 9

for loc in "${LOCATIONS[@]}"; do
    read -r id lat lon <<< "$loc"
    for z in "${ZOOMS[@]}"; do
        read -r gz mz <<< "$z"
        adb shell am broadcast -a "${PKG}.CAMERA" -p "$PKG" --ed lat "$lat" --ed lon "$lon" --ed zoom "$mz" >/dev/null
        sleep 6
        adb exec-out screencap -p > "${OUT}/${id}_z${gz}.png"
        echo "captured ${id} z${gz} (MapLibre ${mz})"
    done
done
echo "完了: ${OUT}"
