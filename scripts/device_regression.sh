#!/usr/bin/env bash
# 実機 (adb 接続の debug ビルド) で経路計算の回帰を 3 ケース実行し、結果表を出す。
# in-pref (広島県内のみ, CH 対象) / same-pref (実際は山口→広島の県またぎ) / 2-pref / 3-pref。各ケースはアプリを再起動して、SIMLOC を送り続けながら
# 目的地へカメラを動かして長押しし、ROUTE_RESULT / ROUTE_FAILURE を待つ。
# 失敗 (結果なし・success=false・OOM) があれば非 0 で終了する。端末設定は変えない。
# 使い方: scripts/device_regression.sh [--timeout 90]  (長押しする画面の y は PRESS_Y、既定 560 = 下のパネルの上の地図部分)
set -u
# 別パッケージ ID で入れた debug ビルド (-Pcyclemap.appIdSuffix) を試すときは CYCLEMAP_PACKAGE_NAME で指定する。
# 検証用ブロードキャストの action 名は applicationId によらず com.gorite.cyclemap.test.* で固定。
PKG=${CYCLEMAP_PACKAGE_NAME:-com.gorite.cyclemap.test}
ACTION=com.gorite.cyclemap.test
ACT=$PKG/com.gorite.cyclemap.MainActivity
TIMEOUT=90
[ "${1:-}" = "--timeout" ] && TIMEOUT="${2:-90}"

# name | 出発 lat,lon | 目的地 lat,lon
CASES=(
  "in-pref|34.39,132.46|34.49,133.36"
  "same-pref|34.05,131.0|34.17,132.22"
  "2-pref|34.05,131.0|34.39,132.46"
  "3-pref|34.05,131.0|34.666,133.918"
)

# 兵庫のデータ (Hyogo.graph ほか) を端末に入れたときだけ: HYOGO=1 scripts/device_regression.sh
if [ "${HYOGO:-}" = "1" ]; then
  CASES+=(
    "2-pref-hyogo|34.666,133.918|34.8164,134.6855"
    "3-pref-hyogo|34.39,132.46|34.8164,134.6855"
  )
fi
# 山口→姫路 (山口>広島>岡山>兵庫の 4 県)。2026-11-14 時点でヒープ上限 512MB を超えて OOM になる既知の課題 (docs/nationwide_design.md 7-4)。
if [ "${HYOGO4:-}" = "1" ]; then CASES+=("4-pref-hyogo|34.05,131.0|34.8164,134.6855"); fi

adb get-state >/dev/null 2>&1 || { echo "adb 端末が見つかりません" >&2; exit 2; }

focus_ok() { adb shell dumpsys window | grep mCurrentFocus | grep -q "$PKG"; }
sim() { adb shell am broadcast -a $ACTION.SIMLOC -p $PKG --ed lat "$1" --ed lon "$2" >/dev/null 2>&1; }

printf "%-10s %-8s %-9s %-10s %-12s %-12s %s\n" case result totalMs distM cost pathHash notes
fail=0
for c in "${CASES[@]}"; do
  IFS='|' read -r name from to <<<"$c"
  [ -n "${ONLY:-}" ] && [ "$name" != "$ONLY" ] && continue
  flat=${from%,*}; flon=${from#*,}; tlat=${to%,*}; tlon=${to#*,}
  adb shell am force-stop $PKG; adb logcat -c
  adb shell am start -n "$ACT" >/dev/null 2>&1
  sleep 4
  if ! focus_ok; then printf "%-10s %-8s %s\n" "$name" SKIP "CycleMap が前面でない (ロック中?)"; fail=1; continue; fi
  ( while true; do sim "$flat" "$flon"; sleep 0.5; done ) & LP=$!
  sleep 14
  adb shell am broadcast -a $ACTION.CAMERA -p $PKG --ed lat "$tlat" --ed lon "$tlon" --ed zoom 16 >/dev/null 2>&1
  sleep 4
  if focus_ok; then adb shell input swipe 540 ${PRESS_Y:-560} 540 ${PRESS_Y:-560} 900; fi
  end=$((SECONDS + TIMEOUT)); res=""
  while [ $SECONDS -lt $end ]; do
    res=$(adb logcat -d | grep -E "ROUTE_RESULT|ROUTE_FAILURE" | tail -1)
    [ -n "$res" ] && break; sleep 2
  done
  kill $LP 2>/dev/null; wait $LP 2>/dev/null
  log=$(adb logcat -d)
  total=$(grep -o "totalMs=[0-9]*" <<<"$log" | tail -1 | cut -d= -f2)
  if grep -q "ROUTE_RESULT.*success=true" <<<"$res"; then
    dist=$(grep -o "routeDistanceM=[0-9.]*" <<<"$res" | cut -d= -f2)
    cost=$(grep -o "routeCost=[0-9.]*" <<<"$res" | cut -d= -f2)
    hash=$(grep -o "pathHash=-\?[0-9]*" <<<"$res" | cut -d= -f2)
    eng=$(grep -o "engine=[a-z]*" <<<"$res" | cut -d= -f2)
    printf "%-10s %-8s %-9s %-10s %-12s %-12s %s\n" "$name" OK "${total:--}" "$dist" "$cost" "$hash" "engine=${eng:--}"
  else
    note=$(grep -oE "reason=[a-z_-]+" <<<"$res" | head -1)
    grep -q "OutOfMemory" <<<"$log" && note="$note OOM"
    printf "%-10s %-8s %-9s %-10s %-12s %-12s %s\n" "$name" FAIL "${total:--}" - - - "${note:-結果なし}"
    fail=1
  fi
done
exit $fail
