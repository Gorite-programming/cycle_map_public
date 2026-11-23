# 実機検証レポート (2026-11-20) 修正まとめ・テスト結果

- 作成日: 2026-11-21
- 元の指示書: `work/agy/fix-device-review-2026-11-20.md`（実機レポート: `codex/cyclemap-device-review-2026-11-20.md`）
- 対象範囲: 指示 A（A-1, A-2, A-3）および指示 B（B-1〜B-4）※文字サイズ 200% の欠けは対象外
- Git 操作: 未コミット（作業ツリーに変更を残した状態）

---

## 1. 修正内容の要約

### 【A-1】県またぎナビ開始直後の不要な再計算（最優先）
- **方針 1（端点接続の有効化）** と **再生成耐性＋初期未合流ガード** の両面で対応。
- 県またぎ（`usedCrossRoute == true`）の `crossInstructions` は `distanceFromStartMeters` 基準、経由地通過判定（`WaypointPassTracker`）も `RouteProgressIndex`＋Haversine 距離基準で座標インデックスに依存しないことを確認したうえで、`connectRouteEndpoints` を県またぎでも有効化した。
- 画面再生成（文字サイズ変更・回転等）で `lastRerouteFrom` が消えないよう `rememberSaveable` で保持し、`LaunchedEffect(isNavigationActive)` では `!isNavigationActive` のときのみクリアするよう修正した。
- さらに、開始直後に道路から 30m 超離れて静止しているケースでも不要な再計算を防ぐ `InitialRerouteGuard`（一度も 30m 以内に入っていない間は自動再計算を抑止。ただし始点から経路先頭まで 200m 超、または開始位置から 50m 以上移動した場合は許可）を導入した。

### 【A-2】「岩国」と「岩国駅」の重複候補統合
- `SearchHelper.kt` の `dedupeNearbySameName` に、従来の完全一致（150m 以内）に加えて以下の条件を追加した（先に並んだ上位候補を残す）。
  - 2 件とも鉄道駅カテゴリ（`railway:station` / `railway:halt` / `public_transport:station` の接頭辞一致。カテゴリ不明・バス停・非駅施設は対象外）
  - 2 件の距離が 50m 以内
  - 正規化・空白除去後に片方がもう片方から末尾の「駅」を除いた名前と一致（単独「駅」への誤一致は除外）
- 郵便局・交番などの別カテゴリ、50m 超の同名駅、「東口」「西口」などの別名出入口はまとめない。

### 【A-3】ナビ中の到着時刻（上段・下段）の 1 分ずれ解消
- `NavigationScreen.kt` に共通整形関数 `formatArrivalClock(epochMillis, zoneId)` を追加し、**最も近い分（30 秒以上繰り上げ、23:59:30 以降は翌日 `00:00`）**に統一した。
- 上段（`LandscapeNavHudBar`）・下段（`GoogleNavFooterV2` / `NavBottomPanelC`）のすべてで `formatArrivalClock` を使うようにし、`NavigationPanelC.kt` の重複 private `formatClock` を削除した。
- `formatEta(seconds)` も `formatArrivalClock` を利用して同じ丸め規則に統一した。

### 【B】GPX 記録とサイコン「一時停止」の区別（UI）
- **B-1（ホーム）**: `HomeDashboard.kt` の「ルートなしで走る」ボタン直下に、未記録時のみ「押すと記録が始まります」を小さく追加（ボタン高さ `56.dp`・色・動作および記録中表示は変更なし）。
- **B-2（サイコン ステータス行）**: `MapScreen.kt` → `CycleComputerHost` → `CycleComputerScreen` → `CycleComputerStatusBar` に `isGpxRecording: Boolean = false` を伝播し、走行状態の丸ピル（「走行中 / 一時停止 / 待機」）とは色・形を分けた角丸矩形＋枠線バッジで「GPX記録中」「GPX停止中」を表示（`FlowRow` 配置で文字拡大時も重ならない）。
- **B-3（サイコン アクションバー）**: `CycleComputerActionBar` に「記録を終了して保存」ボタン（高さ `64.dp` 以上、`fontScale > 1.3f` 時は「保存して終了」）を追加。押下時に確認ダイアログ（「記録を終えて GPX に保存します」／「保存して終える」「キャンセル」）を出し、確定時のみ既存リセット長押しと同一の `onFinishRecording → rideSession.reset() → store.clear()` を実行（既存リセット長押しは維持）。
- **B-4（ナビ終了時の継続確認）**: `MapScreen.kt` で `hadActiveNavigation` を保持し、ナビ開始後の `clearRoute` 時に GPX 記録が続いていれば、従来のルート消去処理を行った直後に「記録は続けますか?」（「記録を続ける」／「記録を終えて保存」）の確認ダイアログを表示するよう実装した。

---

## 2. 変更ファイル一覧と変更理由（1 ファイル 2 行以内）

### プロダクションコード（12 ファイル）
| ファイル | 変更理由 |
|---|---|
| `application/app/src/main/java/com/gorite/cyclemap/navigation/RouteEndpointConnections.kt` | 接続線長 `headLengthMeters` 分だけ案内距離をずらす `shiftInstructionsForHead` を切り出し、テスト可能にした。 |
| `application/app/src/main/java/com/gorite/cyclemap/navigation/MapRouteCalculation.kt` | 県またぎ（`usedCrossRoute`）でも `connectRouteEndpoints` を有効化し、`shiftInstructionsForHead` で案内距離を整合させた。 |
| `application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapTrackingEffects.kt` | `lastRerouteFrom` を `rememberSaveable` で保持（`!isNavigationActive` 時のみ解除）し、開始直後の静止誤リルートを防ぐ `InitialRerouteGuard` を追加した。 |
| `application/app/src/main/java/com/gorite/cyclemap/search/SearchHelper.kt` | `dedupeNearbySameName` に鉄道駅カテゴリ限定・50m 以内・末尾「駅」有無一致の重複統合ルールを追加し KDoc を更新した。 |
| `application/app/src/main/java/com/gorite/cyclemap/ui/map/NavigationScreen.kt` | 30 秒以上繰り上げ・日跨ぎ対応の共通関数 `formatArrivalClock` を追加し、`formatEta(seconds)` も同規則に統一した。 |
| `application/app/src/main/java/com/gorite/cyclemap/ui/map/NavigationHudViews.kt` | `GoogleNavFooterV2` と `LandscapeNavHudBar` の到着時刻整形を共通関数 `formatArrivalClock` に統一した。 |
| `application/app/src/main/java/com/gorite/cyclemap/ui/map/NavigationPanelC.kt` | `NavBottomPanelC` の時刻整形を `formatArrivalClock` へ置き換え、重複していた private `formatClock` と不要 import を削除した。 |
| `application/app/src/main/java/com/gorite/cyclemap/ui/home/HomeDashboard.kt` | 未記録時の「ルートなしで走る」ボタン直下に「押すと記録が始まります」の説明文を追加した（ボタン仕様・記録中表示は不変）。 |
| `application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerHost.kt` | `isGpxRecording: Boolean = false` 引数を追加し、記録終了保存コールバックと合わせて `CycleComputerScreen` へ渡した。 |
| `application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerScreen.kt` | `isGpxRecording` と `onFinishAndSave` を既定値付きで受け取り、縦・横の各コンテンツおよび 200% 文字サイズ含む Preview へ反映した。 |
| `application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerComponents.kt` | ステータス行に独立した「GPX記録中 / GPX停止中」バッジを追加し、アクションバーに 64dp 以上の「記録を終了して保存」ボタンと確認ダイアログを追加した。 |
| `application/app/src/main/java/com/gorite/cyclemap/ui/screens/MapScreen.kt` | `CycleComputerHost` へ `state.isRecordingState.value` を渡し、ナビ終了後のルート消去時に GPX 記録中なら継続確認ダイアログを出す処理を追加した。 |

### 単体テスト（変更 3 ファイル・新規 3 ファイル）
| ファイル | 変更理由 |
|---|---|
| `application/app/src/test/java/com/gorite/cyclemap/navigation/RouteEndpointConnectionsTest.kt` (新規) | 県またぎ相当（`trim = null`）の端点接続、`headLengthMeters` と案内距離シフトの一致、`WaypointPassTracker` の非ずれを検証。 |
| `application/app/src/test/java/com/gorite/cyclemap/ui/effects/InitialRerouteGuardTest.kt` (新規) | 静止 40m×10 サンプルで再計算 0 回、50m 移動で 1 回、経路合流後 40m 逸脱で発火、200m 超開始で許可を検証。 |
| `application/app/src/test/java/com/gorite/cyclemap/SearchHelperTest.kt` | 「岩国」×「岩国駅」6m 統合、郵便局・カテゴリ不明の非統合、300m 離間・50m 超・東口/西口の非統合テストを追加。 |
| `application/app/src/test/java/com/gorite/cyclemap/ui/map/NavigationScreenTest.kt` | `formatArrivalClock`（`01:45:29` / `01:45:30` / `23:59:40`）と `formatEta` の一致・丸めテストを追加。 |
| `application/app/src/test/java/com/gorite/cyclemap/ui/cycling/CycleComputerLayoutTest.kt` | `gpxStatusBadgeLabel` と `finishAndSaveButtonLabel`（`fontScale > 1.3f` で短縮）のテストを追加。 |
| `application/app/src/test/java/com/gorite/cyclemap/ui/home/HomeDashboardTest.kt` (新規) | ホームの記録開始案内文言およびナビ終了時の記録継続確認条件（4 通りの真理値表）を検証。 |

---

## 3. テスト実行結果

Windows 環境にて JDK 21 (`C:\Program Files\Android\Android Studio\jbr`) を使用して実行。

### 全体テスト（`.\gradlew.bat --offline test --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx3g" "-Dkotlin.daemon.jvmargs=-Xmx3g"`）
※ 初回の並列実行時に Kotlin デーモンが OOM になったため、指示書の通り Gradle デーモン停止後に `--max-workers=1` で再実行し全パスを確認。

| タスク | クラス数 | テスト件数 | 失敗 | エラー | スキップ | 結果 |
|---|---:|---:|---:|---:|---:|---|
| `:app:testDebugUnitTest` | 136 | 707 | 0 | 0 | 6 | PASS |
| `:app:testFieldUnitTest` | 136 | 707 | 0 | 0 | 6 | PASS |
| `:app:testReleaseUnitTest` | 136 | 707 | 0 | 0 | 6 | PASS |
| `:routing-core:test` | 26 | 201 | 0 | 0 | 0 | PASS |
| `:osm-importer:test` | 6 | 35 | 0 | 0 | 0 | PASS |

### 主要関連テストクラス内訳（`:app:testDebugUnitTest`）
- `com.gorite.cyclemap.navigation.RouteEndpointConnectionsTest`: 4 passed / 0 failed
- `com.gorite.cyclemap.ui.effects.InitialRerouteGuardTest`: 4 passed / 0 failed
- `com.gorite.cyclemap.routing.AdaptiveRerouteTest`: 3 passed / 0 failed
- `com.gorite.cyclemap.routing.AdaptiveRerouteRaceTest`: 8 passed / 0 failed
- `com.gorite.cyclemap.routing.RerouteSuccessTest`: 1 passed / 0 failed
- `com.gorite.cyclemap.SearchHelperTest`: 20 passed / 0 failed
- `com.gorite.cyclemap.search.SearchRankingAndLatencyTest`: 5 passed / 0 failed
- `com.gorite.cyclemap.ui.map.NavigationScreenTest`: 5 passed / 0 failed
- `com.gorite.cyclemap.ui.cycling.CycleComputerLayoutTest`: 4 passed / 0 failed
- `com.gorite.cyclemap.ui.home.HomeDashboardTest`: 2 passed / 0 failed
- `com.gorite.cyclemap.ui.home.RideTotalsTest`: 2 passed / 0 failed

---

## 4. 未解決の点・申し送り事項（許可ファイル外の確認事項）

1. **【A-3】到着時刻の計算元一本化（`MapTopOverlays.kt` / `MapBottomOverlays.kt` は今回許可ファイル外）**
   - 今回、許可ファイル内で整形・丸め（30 秒以上繰り上げ）を `formatArrivalClock` に統一した。
   - ただし横画面の上段呼び出し元 `ui/layout/MapTopOverlays.kt`（254〜264 行）では `LandscapeNavHudBar` に `eta = null`、`remainingSeconds = (remaining ?: 0.0) / 4.2` を渡している一方、横画面下段の `ui/layout/MapBottomOverlays.kt`（167〜178 行）は `navStats` 由来（既定速度 `15km/h = 4.167 m/s` または実測平均速度）の `EtaInfo` を渡している。
   - 計算元まで完全に一本化する場合は、`MapTopOverlays.kt` に `navStats` 相当の `EtaInfo`（または共通の `etaEpochMillis`）を渡す変更が別途必要。
2. **【A-1】県またぎ標高プロファイルの始点オフセット（軽微・`MapScreenStateFactory.kt` は許可ファイル外）**
   - 県またぎ経路で `head`（3〜200m）が先頭に付いた場合、`ui/state/MapScreenStateFactory.kt`（299〜310 行）の `crossRouteElevation` はグラフ先頭ノード起点のため、標高グラフの始点距離が `headLengthMeters` 分だけわずかにずれる（案内・通過判定・リルートには影響なし）。
3. **【B-4】ナビ終了確認ダイアログの 2 段表示（`MapScreenScaffold.kt` は許可ファイル外）**
   - 今回は許可ファイルである `MapScreen.kt` 内で、ナビ終了後の `clearRoute()` 完了直後に「記録は続けますか?」を出す形で実装した（プレビュー解除では出ず、到着カード「終了」も含めてカバー）。
   - ナビ中の終了ボタンからは既存の `ui/layout/MapScreenScaffold.kt`（581〜597 行）の「ナビを終了しますか?」確認の直後に続けて表示されるため、1 枚のダイアログに統合したい場合は `MapScreenScaffold.kt` 側の調整が必要。

---

## 5. `git status` / `git diff --stat`

```text
 M application/app/src/main/java/com/gorite/cyclemap/navigation/MapRouteCalculation.kt
 M application/app/src/main/java/com/gorite/cyclemap/navigation/RouteEndpointConnections.kt
 M application/app/src/main/java/com/gorite/cyclemap/search/SearchHelper.kt
 M application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerComponents.kt
 M application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerHost.kt
 M application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerScreen.kt
 M application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapTrackingEffects.kt
 M application/app/src/main/java/com/gorite/cyclemap/ui/home/HomeDashboard.kt
 M application/app/src/main/java/com/gorite/cyclemap/ui/map/NavigationHudViews.kt
 M application/app/src/main/java/com/gorite/cyclemap/ui/map/NavigationPanelC.kt
 M application/app/src/main/java/com/gorite/cyclemap/ui/map/NavigationScreen.kt
 M application/app/src/main/java/com/gorite/cyclemap/ui/screens/MapScreen.kt
 M application/app/src/test/java/com/gorite/cyclemap/SearchHelperTest.kt
 M application/app/src/test/java/com/gorite/cyclemap/ui/cycling/CycleComputerLayoutTest.kt
 M application/app/src/test/java/com/gorite/cyclemap/ui/map/NavigationScreenTest.kt
?? application/app/src/test/java/com/gorite/cyclemap/navigation/RouteEndpointConnectionsTest.kt
?? application/app/src/test/java/com/gorite/cyclemap/ui/effects/InitialRerouteGuardTest.kt
?? application/app/src/test/java/com/gorite/cyclemap/ui/home/HomeDashboardTest.kt
```

```text
 .../cyclemap/navigation/MapRouteCalculation.kt     |  11 +-
 .../navigation/RouteEndpointConnections.kt         |  10 +
 .../com/gorite/cyclemap/search/SearchHelper.kt     |  37 +++-
 .../cyclemap/ui/cycling/CycleComputerComponents.kt | 222 +++++++++++++++++----
 .../cyclemap/ui/cycling/CycleComputerHost.kt       |   6 +-
 .../cyclemap/ui/cycling/CycleComputerScreen.kt     |  26 ++-
 .../cyclemap/ui/effects/MapTrackingEffects.kt      |  70 ++++++-
 .../com/gorite/cyclemap/ui/home/HomeDashboard.kt   |  14 ++
 .../gorite/cyclemap/ui/map/NavigationHudViews.kt   |  12 +-
 .../com/gorite/cyclemap/ui/map/NavigationPanelC.kt |  11 +-
 .../com/gorite/cyclemap/ui/map/NavigationScreen.kt |  31 ++-
 .../com/gorite/cyclemap/ui/screens/MapScreen.kt    |  46 +++++
 .../java/com/gorite/cyclemap/SearchHelperTest.kt   |  46 +++++
 .../cyclemap/ui/cycling/CycleComputerLayoutTest.kt |  10 +
 .../cyclemap/ui/map/NavigationScreenTest.kt        |  29 +++
 15 files changed, 495 insertions(+), 86 deletions(-)
```
