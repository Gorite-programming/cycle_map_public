# CycleMap 総合技術調査レポート（2026-11-18）

- **調査対象リポジトリ**: `c:\dev\cycle_map`（検証時点 HEAD: `52355e1`）
- **調査実施日**: 2026-11-18
- **調査目的**: 次期開発・改修計画（バグ修正および新機能追加の優先度付け・工数見積）のための現状把握。現行コード全体のバグ・修正点（R1）、今後追加すべき機能（R2）、UI上の懸念点（R3）、実機操作による確認結果（R4）を網羅的かつ客観的な証跡付きで整理する。
- **調査方針**: リポジトリ内の既存追跡ファイルは一切編集せず、自動テスト実行・静的コード監査・データサンプリング・実機（Galaxy S26 / `RFGL626QR3N`）検証により事実のみを記録した。

---

## エグゼクティブサマリー・調査概要

### 1. 対象リポジトリ範囲
1. **Android アプリ本体 (`application/app/`)**: MVI/Compose UI、MapLibre 3Dベクター + osmdroid ハイブリッド地図、位置情報・GPX記録・サイコン・音声案内（VOICEVOX 四国めたん）・オフライン検索・天気・設定・ビルド設定。
2. **純粋 JVM ルーティング & インポータ基盤 (`application/routing-core/`, `application/osm-importer/`, `application/*.gradle*`)**: `RoadGraph` / `LazyMappedRoadGraph`、A\* / Contraction Hierarchies (`ContractionHierarchy`)、`CyclingCostModel` / `BicycleCost`、`TurnClassifier`、`EndpointTrim`、`RoadRuleMatcher`、OSM `.pbf` インポータ、`Archives/application/bugs.md` との照合。
3. **オフラインデータパイプライン (`build_prefectures.py`, `application/osm-importer/build_search_db.py`, `boundaries/`, `scripts/`, `sandbox/data-tool/`)**: 47都道府県パッケージ生成、ベクタータイル・地形タイル生成（`build_tiles.py`, `build_terrain.py`）、地図スタイルパッチ（`patch_map_style.py`）、CH/Portal生成（`build_ch.py`, `build_portals.py`）、検索DB統合（OSM + Overture Maps + 国土数値情報）、Data Studio / Streamlit ツール、実機デプロイ（`push_data.sh`）。

### 2. 自動テスト・ビルド実行ベースライン一覧

| モジュール / スイート | 実行コマンド（読み取り専用） | 結果サマリー | 備考・警告 |
|---|---|---|---|
| `:app:testDebugUnitTest` | `.\gradlew.bat :app:testDebugUnitTest --console=plain` | **109 suites / 579 tests**（**574 executed, 0 failed, 0 errors, 5 skipped**）<br>`BUILD SUCCESSFUL in 37s` | スキップ5件は `RealPlaceDetailProviderTest`×4, `JmaWeatherProbeTest`×1。<br>Kotlin警告: `VectorMapView.kt:674:17` (`setPadding` deprecated) |
| `:routing-core:test` | `.\gradlew :routing-core:test --rerun-tasks --no-daemon --console=plain` (JDK 21) | **25 suites / 183 tests**（**0 failed, 0 errors, 0 skipped**） | `application/routing-core/build/test-results/test/TEST-*.xml` |
| `:osm-importer:test` | `.\gradlew :osm-importer:test --rerun-tasks --no-daemon --console=plain` (JDK 21) | **5 suites / 34 tests**（**0 failed, 0 errors, 0 skipped**） | `:routing-core:test` と合算 `BUILD SUCCESSFUL in 36s`。<br>警告: `BikeInfoReindex.kt:354` (`Condition is always 'true'`), `DemElevationTest.kt:43,54,62,96` (`createTempDir` deprecated), `gradle.properties:25` (`android.overridePathCheck=true`), `app/build.gradle.kts:9,19` (`project.exec` Gradle 9非互換) |
| `:app:assembleDebug` | `.\gradlew.bat :app:assembleDebug "-Pcyclemap.appIdSuffix=.win" --no-daemon` | **`BUILD SUCCESSFUL in 43s`** | JDK 21 (`C:\Program Files\Android\Android Studio\jbr`) でビルド、`52355e1` を実機導入 |
| `sandbox/data-tool/tests` | `..\..\venv\Scripts\python.exe -B -m pytest -q -p no:cacheprovider tests` | **118 passed** in 2.05s | — |
| `scripts/tests` | `venv\Scripts\python.exe -B -m pytest -q -p no:cacheprovider scripts/tests` | **125 passed, 15 failed** | 失敗15件はすべて `scripts/tests/test_push_data.py`。Windows上で `shutil.which("bash")` (`test_push_data.py:81`) が WSL `C:\WINDOWS\system32\bash.EXE` を拾い `cygpath` 不在＋`UnicodeDecodeError` が発生 |

### 3. 実機テスト環境サマリー
- **端末**: Samsung Galaxy S26 (`samsung SC-51G`, ADB serial `RFGL626QR3N`)
- **OS / 画面**: Android 17 (`ro.build.version.release=17`, `ro.build.version.sdk=37`), 解像度 `1080x2340`, `480 dpi` (`3.0x`)
- **アプリ構成**: `com.gorite.cyclemap.test.win` (`versionCode=12`, `minSdk=33`, `targetSdk=35`, 表示バージョン `CycleMap α0.9.10-unified-win`, コミット `52355e1`, ビルド日時 `2026-11-18 18:28`)
- **通信状態**: 機内モード ON（`settings get global airplane_mode_on == 1`、18:38〜18:53の全主要オフライン操作を完全オフラインで検証）
- **オフラインデータ**: `/sdcard/Android/data/com.gorite.cyclemap.test.win/files/Documents/CycleMap/` に **47都道府県・419エントリ（合計 26.2 GB）**（`.graph`, `.graph.ch`, `.graph.idx`, `.search.db`, `.portals`, `.pmtiles`, `.contours.pmtiles`, `.hillshade.pmtiles`）＋ `tiles/cache.db`（`2.8M`）を配置済み。
- **動作安定性**: 全20確認フローを通じ、**クラッシュ・FATAL・ANR・SQLite/FTSエラーは0件**。機内モード下でのひらがな/ローマ字/漢字検索、CH/A\*単県探索、広島県→山口県の県跨ぎポータル探索（32.1 km / 5.19秒）まで正常動作を確認。

### 4. 全体件数サマリー＆最優先対応事項（P0 / P1 ハイライト）

| 分類 | 対象領域 | Critical (P0) | High (P1) | Medium (P2) | Low (P3) | 合計 |
|---|---|---:|---:|---:|---:|---:|
| **R1: バグと修正点** | Android `:app` | 2 | 6 | 6 | 1 | **15** |
| | `:routing-core` / `:osm-importer` / Gradle | 0 | 3 | 7 | 2 | **12** |
| | パイプライン / `scripts/` / `data-tool` | 4 | 9 | 10 | 7 | **30** |
| **R3: UI上の懸念点** | 実機確認 UI/UX 欠陥 | 1 | 4 | 6 | 2 | **13** |
| | コード解析 UI/状態/アクセシビリティ懸念 | 0 | 2 | 4 | 2 | **8** |
| | データ/スタイル由来 & Streamlit ツールUI | 0 | 4 | 3 | 0 | **7** |
| **R2: 今後の追加機能** | アプリ / ルーティング / パイプライン | 2 | 9 | 14 | 4 | **29** |
| **総合計** | **全領域（R1: 57 / R3: 28 / R2: 29）** | **9** | **37** | **50** | **18** | **114** |

> ※ R1・R3・R2は各領域の重複を含まない独立項目数。実機確認とコード解析が対応するUI不具合（例: 横画面衝突、ナビHUD、ドロワー下部クリップ等）は相互に紐づけて整理している。

**最優先対応ハイライト（P0 / 即時着手推奨）**:
1. **MapLibre ライフサイクル破壊によるカメラ強制リセット (`APP-R1-01`)** & **案内開始ズームのキャンセル競合 (`APP-R1-02`)**: `MapViewsContainer.kt:254` の `key(tileKey, vectorDataRevision)` がGPS初回確定時や県境通過時に `MapView` ごと破棄・再生成（実機ログでも起動時2回生成を確認）しており、`VectorMapView.kt:564-566` の `moveCamera` が `animateCamera` を中断する。
2. **横画面ナビ・拡大表示時の重大UI衝突 (`DEV-R3-01`)**: 実機確認（`51_landscape.png`）で横画面の `LandscapeControlRow` が `GPS信号が途絶えました` バナーとカテゴリチップ列に直接重なり、`"盤面に戻る"` CTAも画面外に消失。ナビ中の `LandscapeNavHudBar` と `LandscapeControlRow` も右上端で衝突する（`MapTopOverlays.kt:222-226` vs `MapControlsOverlay.kt:185-213`）。
3. **県またぎ・別都市POIの80km超誤マージ (`PIPE-R1-01`)**: `sandbox/data-tool/tools/core/merge.py:207` が市区町村名まで削除してPass 3で距離制限なしマージを行い、`広島市中区本町1-1` と `福山市本町1-1`（84.2km離隔）を同一店舗として結合・削除する。
4. **地形DEMのHTTP 403恒久空キャッシュ (`PIPE-R1-02`)**: `scripts/build_terrain.py:146-149` がレート制限 `403` を欠損海タイル `404` と同様に0バイトファイルでキャッシュし、以後の等高線・陰影・勾配計算に恒久的な標高0mの穴を作る。
5. **沿岸POIの東京誤割当・島嶼部飛び地欠落 (`PIPE-R1-03`)** & **Overture `operating_status` / ライセンス / `phone` 欠落 (`PIPE-R1-04`)**: 静岡・神奈川沖が `tokyo` 扱いになり、出荷済み8県の `.search.db` すべてで Overture 電話番号が0件、Foursquare/ODbL/GSI DEMのライセンス整合も未完備。

---

## 第1章: 現行コード全体のバグと修正点（R1）

### 1.1 `application/app/`（Android UI・MVI状態・地図・GPX・検索・TTS・ビルド設定）

#### [APP-R1-01] Critical / P0: `tileKey` 変更による MapLibre `MapView` 破棄・カメラ強制リセットと `vectorMapState` 未クリア
- **優先度根拠**: 県境通過時・GPS初回確定時（山口県外での起動時）に地図インスタンス自体が破棄再生成され、ズーム・チルト・方角がすべて初期化される。実機ログでも起動時に `MapLibreGLSurfaceView` が2回生成・破棄されている。ユーザー体験への影響が極めて大きく、修正コストは低い。
- **検証済み根拠**:
  - `application/app/src/main/java/com/gorite/cyclemap/ui/layout/MapViewsContainer.kt:220-255`: `tileKey` が `currentLocation`（`null` 時は `FALLBACK_LATITUDE to FALLBACK_LONGITUDE`）のみから計算され、line 254 で `key(tileKey, vectorDataRevision)` で `VectorMapView` をラップしている。
  - `application/app/src/main/java/com/gorite/cyclemap/ui/mapview/VectorMapView.kt:192-197`: 再生成時は既定の `zoom=14.5`, `tilt=0`, `bearing=0` に戻る。実際には `VectorMapView` は `rebuildStyle` により `.pmtiles` ソースを動的に切替可能である。
  - `MapViewsContainer.kt:214` には osmdroid 用の `onMapDestroyed` がある一方、`VectorMapView` 側には `state.vectorMapState` を `null` クリアする `onVectorMapDestroyed` コールバックが存在しない。
  - 実機ログ `04_head_logcat.txt`: `MapLibreGLSurfaceView` インスタンス `182711773` が `18:28:58.959` に生成され `18:28:59.327` に破棄、直後 `123382370` が `18:28:59.416` に再生成。
- **推奨修正方針**: `VectorMapView` を囲む `key(...)` から `tileKey` を外し（必要なスタイル切替は既存の `rebuildStyle` に委任）、`onVectorMapDestroyed` コールバックを追加して破棄時に `state.vectorMapState.value = null` を設定する。

#### [APP-R1-02] Critical / P0: 追従中 `moveCamera` による `animateCamera` ズーム遷移のキャンセル競合
- **優先度根拠**: ナビ開始時や現在地ボタン押下時のカメラズーム遷移が、直後の位置追従 `moveCamera` により強制中断され、案内用ズーム（`NAVIGATION_ZOOM = 16.0`）や対象地点への滑らかな遷移が失敗する。
- **検証済み根拠**:
  - `application/app/src/main/java/com/gorite/cyclemap/ui/screens/MapScreen.kt:830-835`（`startNavigation`）および `application/app/src/main/java/com/gorite/cyclemap/ui/layout/MapScreenScaffold.kt:239`（`onTargetClick`）で `state.vectorMapState.value?.animateCamera(...)` を呼び出している。
  - `application/app/src/main/java/com/gorite/cyclemap/ui/mapview/VectorMapView.kt:564-566`: `if (moved && currentFollowLocation) { map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(pos.latitude, pos.longitude))) }` を実行。
  - `application/app/src/main/java/com/gorite/cyclemap/ui/mapview/MotionSmoothing.kt:149-150`: `moveCamera` が実行中の `animateCamera`/`easeCamera` をキャンセルする仕様が明記されている。
- **推奨修正方針**: ナビ開始／現在地ボタン時は位置・ズームを単一カメラアニメーションに統合するか、アニメーション実行中フラグ／`MapZoomAnimator` と同様に追従 `moveCamera` を一時抑制（またはズーム変更を含めて結合更新）する。

#### [APP-R1-03] High / P1: `MapViewModel` と `MapScreenState` 間のMVI状態不整合（到着・ミュート・代替ルートポリライン）
- **優先度根拠**: 画面状態（ナビ中フラグ・音声ミュート状態・表示ルート線）がUIとViewModel・地図オーバーレイ間で食い違い、ユーザーが別候補ルートを選んでも地図が更新されない、到着後も `activeSheet` がナビのまま残る等の不具合を引き起こす。
- **検証済み根拠**:
  - **(a) 到着時の状態未同期**: `MapScreen.kt:691`（`onNavigationActiveChanged = { state.isNavigationActiveState.value = it }`）および `ui/effects/MapTrackingEffects.kt:205-208` で到着時に `isNavigationActive` を `false` にするが、`viewModel.onIntent(MapUiIntent.StopNavigation)` を送らず `viewModel.uiState.value.isNavigating == true`・`activeSheet == SheetType.Navigation`（`MapState.kt:197-202`）が残留する。
  - **(b) ミュート状態未同期**: `MapScreenScaffold.kt:306-311` の `onToggleMute` は `voiceGuidanceModeState` と `viewModel.onIntent(ToggleMute)` の両方を更新するが、`MapSettingsDialogs.kt:214-217`（`SettingsPanelSheet`）は `voiceGuidanceModeState` のみ変更し `viewModel.uiState.isMuted` を同期しない。
  - **(c) 代替ルート選択のラスター地図未反映**: `MapScreen.kt:368` は `state.routeOverlayState.value == null` の場合のみ osmdroid `Polyline` を生成し、`selectRouteOption`（`MapScreen.kt:789-793`）は `navigationRouteState.value` のみ更新して `routeOverlayState.value` を削除・差し替えしないため、ラスター地図上で初期候補線のまま残る。
- **推奨修正方針**: 到着時に `StopNavigation` インテントを発行し、音声モード変更を `MapViewModel` 経由に一本化し、`selectRouteOption` 時に既存の `routeOverlayState` を削除・再描画する。

#### [APP-R1-04] High / P1: GPS未測位時の手動出発地無視およびルート探索失敗時の `destination` リーク
- **優先度根拠**: 屋内起動やトンネル等でGPS未取得のまま手動設定した出発地が経由地変更や好み変更時に山口市役所（`FALLBACK_LATITUDE/LONGITUDE`）へ勝手にすり替わり、対象県データ未導入時にも `HomeDashboard` へ戻れなくなる。
- **検証済み根拠**:
  - **(a) 手動出発地無視**: `MapScreenScaffold.kt:84-85`（`applyWaypointEdit`）, `278`（`onRoutePreferenceChange`）, `483`（`onAddWaypoint`）が `if (location != null) GeoPoint(...) else GeoPoint(FALLBACK_LATITUDE, FALLBACK_LONGITUDE)` と計算しており、`state.startMarkerState.value?.position` を参照していない（`MapScreen.kt:251` の `startRouteToDestination` と不整合）。
  - **(b) 探索失敗時の `destination` 残留**: `navigation/MapRouteCalculation.kt:104-106` が `RoutingGraphSelector.select(start)`（`lines 149-157`）の前に `env.onDestinationChanged(goal)` を実行し、`Unavailable` で早期 return しても `destination` をロールバックしない。`MapContentLayout.kt:184` は `destination == null` で `isIdle` を判定するため、`HomeDashboard` が隠れたままになる。
- **推奨修正方針**: 出発地解決を `state.startMarkerState.value?.position ?: currentLocation ?: fallback` の共通ヘルパーに統一し、`RoutingGraphSelector` 検証成功後に `onDestinationChanged(goal)` を呼ぶ（または失敗時に直前の `destination` へ戻す）。

#### [APP-R1-05] High / P1: ルート候補生成が `RoutePreference` を無視・候補カードとサマリーの距離乖離
- **優先度根拠**: ユーザーが「平坦（`FLAT`）」や「最短」を選んでも代替候補の算出が常に `TravelModeType.Bicycle`（標準）になり、また同じ経路でプレビュー上部と候補カードで距離表示が一致しない。
- **検証済み根拠**:
  - `navigation/MapRouteCalculation.kt:549-562` (`buildOptionsFromPrimary`) が選択中の `RoutePreference` に関わらず常に `TravelModeType.Bicycle`（`RealRoutePlanner.kt:96-100, 135-143`）で `RealRoutePlanner` を呼び、`arterialPenaltyMultiplier = 1.30` / `heuristicMultiplier = 1.15` を上乗せする。
  - `MapRouteCalculation.kt:420` は `displayedRouteDistance(lineCoordinates)`（`headPoints + tailPoints` 込み）で `routeSummary` を算出する一方、`RealRoutePlanner.kt:121` はトリム後・延長前の `primaryResult.totalDistanceMeters` を返す（`RouteInfoPanels.kt:172-173` では選択中インデックスのみ部分回避）。
- **推奨修正方針**: `RealRoutePlanner` / `buildOptionsFromPrimary` に現在の `RoutePreference` を引き渡し、距離計算基準を `displayedRouteDistance`（または実表示座標長）へ統一する。

#### [APP-R1-06] High / P1: GPX記録停止時のファイル書き出しスレッドと `stopSelf()` の競合
- **優先度根拠**: 走行記録の終了直後にサービスが `stopSelf()` で破棄されると、バックグラウンドのGPXファイル書き込みが完了する前にプロセスが強制終了され、走行ログが破損・消失するリスクがある。
- **検証済み根拠**:
  - `tracking/LocationTrackingService.kt:221, 257-266`: `stopTracking()` 内で `stopRecordingIfNeeded()` -> `stopRecording()` を呼び、`line 221` で未管理の `Thread { snapshot.writeToFile(...) }` を起動した直後、完了を待たずに `stopForeground(...)` と `stopSelf()`（`lines 264-265`）を実行する。
- **推奨修正方針**: ファイル書き出しを `ExecutorService` または管理されたコルーチンで実行し、書き込み完了（コールバックまたは `Future`/Join）を待ってから `stopForeground()`・`stopSelf()` を呼ぶ。

#### [APP-R1-07] High / P1: 1Hz GPS更新ごとのラスターPOI再クエリ連打と住所逆ジオコーディングのデバウンス飢餓
- **優先度根拠**: ラスター地図表示中は毎秒SQLite POIクエリとオーバーレイ全再描画が発生し電池・CPUを消費する。さらに走行中はGPS更新間隔が1秒未満（最小250ms）になるため1000msデバウンスが毎回キャンセルされ、現在地地名が更新されなくなる。
- **検証済み根拠**:
  - **(a) POI過剰再描画**: `ui/effects/MapLifecycleEffects.kt:127`: `LaunchedEffect(poiVisible, poiSelected, poiRefreshTick, currentLocation, mapView, isNavigationActive)` が `currentLocation` をキーに持つため、ラスター地図（`mapView != null`）では1HzのGPS更新ごとにコルーチンが再起動し、`500L` 待機後に毎回SQLite POI検索＋全マーカー再描画を行う。
  - **(b) 住所デバウンス飢餓**: `MapLifecycleEffects.kt:235`: `LaunchedEffect(currentLocation, mapView)` 先頭で `delay(1_000L)` を待つ。`LocationTrackingService.kt:158` の最小更新間隔は `250ms`（要求間隔 `1_000L`）のため、1秒未満で次の `Location` が来るとコルーチンがキャンセルされ `addressController.update(...)` に到達しない。
- **推奨修正方針**: 距離閾値（例: 前回クエリから50m以上移動）や地図カメラ移動をトリガーにするか、最新値のみ処理する非キャンセル型チャネル／一定間隔ティッカーへ変更する。

#### [APP-R1-08] High / P1: ベクターモードでの不要ラスターDL、`SqlTileWriter` リーク、ワーカースレッドからのUI状態直接更新
- **優先度根拠**: ベクター表示中にもかかわらず経路回廊DLで旧式国土地理院ラスターPNGタイルが保存され、例外発生時にSQLiteキャッシュライターがリークする。さらに別スレッドからCompose `MutableState` を直接書き換えている。
- **検証済み根拠**:
  - **(a) 誤レイヤーマッピング**: `navigation/MapRoutingActions.kt:101` が `MapLayer.VECTOR_LIBERTY` を `gsiTileSource()` と `MapSourceType.VECTOR_LIBERTY` に割り当て、`data/Prefectures.kt:272-276` が osmdroid キャッシュへGSI PNGをダウンロードしている。
  - **(b) 非メインスレッド状態更新**: `MapRoutingActions.kt:106-136` が `Thread` 内で `onProgressUpdate` / `onFinished`（`MapScreen.kt:977-978` のCompose `MutableState` 代入）を直接呼び出している（`MapScreenScaffold.kt:449, 524` は `runOnUiThread` を使用）。
  - **(c) `SqlTileWriter` リーク**: `data/Prefectures.kt:426-485`（`downloadTiles`）で `writer.onDetach()` が `line 484` にあり、`try/finally` で保護されていない（`downloadRouteCorridor` line 364 は `try/finally` 使用）。
- **推奨修正方針**: ベクター表示時の回廊タイルDLの意味付けを見直し（R2-08参照）、コールバックを `Dispatchers.Main` / `runOnUiThread` 経由にし、`downloadTiles` に `try/finally` とキャンセル機構を追加する。

#### [APP-R1-09] Medium / P2: `WeatherRepository` Haversine 定義域ガード漏れおよびプレースホルダー文字列の `cityHint` 送信
- **優先度根拠**: `Archives/application/bugs.md` の `BUG-82` 対策が `WeatherRepository` に未適用で、浮動小数点誤差で `NaN` になる恐れがある。また検索前の仮文字列を天気API等の都市ヒントに渡している。
- **検証済み根拠**:
  - `weather/WeatherRepository.kt:242`: `return r * 2 * atan2(sqrt(a), sqrt(1 - a))` に `.coerceIn(0.0, 1.0)` がない。
  - `ui/screens/MapScreen.kt:753-758`: `state.locationLabelState.value`（`"現在地を取得中…"`, `"現在地を特定できません"`, `"名称なし"` 等のセンチネル値を含む）をそのまま `cityHint` として渡している。
- **推奨修正方針**: `a.coerceIn(0.0, 1.0)` を追加し、`cityHint` にはセンチネル値を除外した有効な地名のみを渡す。

#### [APP-R1-10] Medium / P2: `.search.db` ホットインポート後のSQLiteスキーマキャッシュ陳腐化
- **優先度根拠**: アプリ起動中に新しい形式の `<pref>.search.db` を取り込んだ場合、再起動するまで古いスキーマ判定キャッシュが使われ検索列が不一致になる。
- **検証済み根拠**: `search/SearchHelper.kt:296-312, 395-410`: `placesColumnsCache` と `placesHasSearchTextCache` が `db.path` の文字列のみをキーとして無期限キャッシュしている。
- **推奨修正方針**: キャッシュキーにファイルの `(path, lastModified, length)` を含めるか、インポート完了時にキャッシュを明示クリアする。

#### [APP-R1-11] Medium / P2: ルーティング処理内の非構造化 `CoroutineScope(Dispatchers.IO)` 生成
- **優先度根拠**: 画面破棄後もキャンセルされず孤立コルーチンやコールバック参照が残存する原因となる。
- **検証済み根拠**: `navigation/MapRouteCalculation.kt:237` でライフサイクルに紐づかない `kotlinx.coroutines.CoroutineScope(Dispatchers.IO)` を都度生成している。
- **推奨修正方針**: `viewModelScope` または呼び出し元の `rememberCoroutineScope()` を受け取り構造化並行性へ移行する。

#### [APP-R1-12] Medium / P2: 500ms無限ポーリングループおよび走行記録終了時の `rideSession.reset()` 漏れ
- **優先度根拠**: `MapScreen` 表示中ずっと500ms間隔でメインスレッドが起き続け不要な再評価を走らせるほか、「保存して終える」操作後もセッション状態がリセットされない。
- **検証済み根拠**: `ui/screens/MapScreen.kt:495-500` の `LaunchedEffect(trackingService.value) { while (true) { showRecovery.value = trackingService.value?.recoveryPending == true; delay(500) } }`、および `line 515` での `rideSession.reset()` 未呼び出し。
- **推奨修正方針**: `LocationTrackingService` 側から `StateFlow<Boolean>` を公開して購読し、セッション保存・終了時に `rideSession.reset()` を呼ぶ。

#### [APP-R1-13] Medium / P2: 未使用 `AndroidTextToSpeechEngine` の毎回初期化と音声発話距離ウィンドウの狭さ
- **優先度根拠**: 選択肢にないAndroid標準TTSエンジンを毎起動時生成しており無駄な初期化コストがかかる。また高速走行・GPS遅延時に40m幅のNEAR判定窓を通過して曲がり角音声がスキップされるリスクがある。
- **検証済み根拠**:
  - `ui/state/MapScreenStateFactory.kt:352-356`: `VoiceGuidanceMode` は `VOICEVOX` と `OFF` のみ（`VoiceGuidance.kt:25-35`）にもかかわらず `AndroidTextToSpeechEngine(context)` を毎回生成。
  - `speech/VoiceGuidance.kt:389-406` (`VoiceTriggerEvaluator.evaluate`): NEAR を `[80, 120]` m、FAR を `[260, 340]` m に限定しており、高速降坂やGPS更新遅延で40m帯を飛び越える可能性がある。
- **推奨修正方針**: 未使用の `AndroidTextToSpeechEngine` 生成を削除（または設定機能として復活させる場合のみ遅延初期化）し、`VoiceTriggerEvaluator` の発話判定に下限通過トリガー／ヒステリシスを導入する。

#### [APP-R1-14] Medium / P2: 山口市役所ハードコードのデッドコールバック `onHomeClick`
- **優先度根拠**: 未使用の引数とハードコード座標がコード上に残存しており保守上の混乱を招く。
- **検証済み根拠**: `ui/layout/MapScreenScaffold.kt:206-212`（`GeoPoint(FALLBACK_LATITUDE, FALLBACK_LONGITUDE)` へルーティングし未使用変数 `val start` を保持）および `ui/layout/MapTopOverlays.kt:71-72`（宣言のみで呼び出し箇所0）。
- **推奨修正方針**: R2-03（自宅／職場登録機能）を実装するまで削除するか、保存済み「自宅」座標へ接続する。

#### [APP-R1-15] Low / P3: リリースビルド設定（`isMinifyEnabled = false`, test ID共有, `allowBackup`）
- **優先度根拠**: 本番配布前のセキュリティ・APKサイズ・バックアップ整合に関わる設定不備。開発ビルド自体には支障がないためLow。
- **検証済み根拠**: `application/app/build.gradle.kts:80` で `release` ビルドが `isMinifyEnabled = false` かつ `applicationId = "com.gorite.cyclemap.test"` を使用。`application/app/src/main/AndroidManifest.xml:15` が除外ルールなしで `android:allowBackup="true"` を指定。
- **推奨修正方針**: リリース用 `applicationId` 分離、ProGuard/R8有効化（`isMinifyEnabled = true` + keepルール整備）、`dataExtractionRules` / `fullBackupContent` による一時キャッシュ除外を設定する。

---

### 1.2 `application/routing-core/` & `application/osm-importer/` & Gradle

#### [CORE-R1-01] High / P1: `OsmImporter` の一方通行・自転車逆走許可タグ（ラウンドアバウト・`oneway:bicycle=no`）解釈漏れ
- **優先度根拠**: 日本の市街地に多い「自動車一方通行・自転車を除く（`oneway=yes` + `oneway:bicycle=no`）」がグラフ構築時に通行禁止と誤判定され大回りルートになり、逆に `oneway` タグを省略した `junction=roundabout` が両方向通行可能になり逆走ルートを案内し得る（安全・経路品質に直結）。
- **検証済み根拠**:
  - `application/osm-importer/src/main/kotlin/com/gorite/cyclemap/importer/OsmImporter.kt`: `TwoPassGraphBuilder.build()` L317–354、`OsmGraphBuilder.build()` L376–391、`collectBicycleNetworkStats()` L616 は `way.tags["oneway"] in setOf("yes", "true", "1", "-1")` のみ判定。
  - 一方 `application/routing-core/src/main/kotlin/com/gorite/cyclemap/routing/BicycleInfrastructure.kt:104-151` (`classifyBicycleRoad()`) は `junction=roundabout`（暗黙の `oneway=yes`）、`oneway:bicycle=no` / `cycleway=opposite*` / `cycleway:*:oneway=no`、`oneway:bicycle=yes|-1` をすべて解釈している。
- **推奨修正方針**: `OsmImporter` のエッジ方向判定ロジックを `BicycleInfrastructure.classifyBicycleRoad()` の仕様と統一し、`junction=roundabout` および `oneway:bicycle` / 対向自転車レーンタグを反映する。

#### [CORE-R1-02] High / P1: `highway=cycleway` と `trunk` が `OTHER (1.15)` に落ち、幹線国道・車道より自転車道が高コスト化
- **優先度根拠**: 自転車専用道（`cycleway`）が `primary (0.90)` / `secondary (0.95)` / `tertiary (1.00)` より重いペナルティ `1.15` を受け、結果的に自転車道を避けて幹線道路を案内してしまう。また `trunk` が `isArterial()` 対象外になる。
- **検証済み根拠**:
  - `OsmImporter.kt:64-82` (`BicycleAccessFilter.isAllowed()`) は `cycleway`, `trunk`, `trunk_link`, `primary_link`, `secondary_link`, `tertiary_link`, `living_street`, `road`, 自転車可の `footway`/`pedestrian` を許可している。
  - しかし `BicycleCost.kt:34-45` (`roadMultiplier()`) と `LazyMappedRouting.kt:853-877, 1106-1119` (`TYPE_CODE_COUNT = 11`) は 10種の `highway` 文字列（`motorway`, `motorway_link`, `primary`, `secondary`, `tertiary`, `residential`, `unclassified`, `service`, `track`, `path`）しか識別せず、上記すべてが `code = 0` (`OTHER = 1.15`, `isArterial() = false`) に分類される。
- **推奨修正方針**: `BicycleCost.kt` と `LazyMappedRouting.kt` に `cycleway`, `trunk`, `*_link`, `living_street`, `footway`, `pedestrian` 用の専用 `TYPE_*` コードと自転車係数を追加し、`trunk` を `isArterial()` に含める（最小倍率 `>= 0.90` を維持し、修正後は `.graph.ch` を再生成する）。

#### [CORE-R1-03] High / P1: `CYCLEMAP_BIKEINFO_V1` と `.bikerules` が標準パッケージ生成で出力されず、道路ルール警告が本番で無効化
- **優先度根拠**: アプリ本体（`MapScreen.kt:470-485, 633-658`）は走行中に自動車専用道路・歩行者天国・逆走などの警告機能を持っているが、標準パイプラインの `.graph.idx` / パッケージに肝心のデータが含まれずサイレントに空振りしている。
- **検証済み根拠**:
  - `OsmImporter.kt:788-821` (`main()`) および `839-913` (`writeGraphIndex()`) は `ELEVATION_V1`, `ROADNAME_V1`, `TURNLANES_V1` のみ書き出し、`CYCLEMAP_BIKEINFO_V1` と `.bikerules`（`ExcludedRoadIndex`）を出力しない。
  - `application/osm-importer/src/main/kotlin/com/gorite/cyclemap/importer/BikeInfoReindex.kt:322-374` (`BikeInfoReindexMain`) のみが生成できるが、`build_prefectures.py` / `scripts/*.py` / `scripts/*.sh` のどこからも呼ばれていない。
- **推奨修正方針**: `OsmImporter.main()` / `writeGraphIndex()` に自転車インフラ情報と `.bikerules` 出力を直接統合するか、`build_prefectures.py` に `:osm-importer:reindexBikeInfo` ステップを組み込む。

#### [CORE-R1-04] Medium / P2: `RoadRuleMatcher` が通常両面道路（`bits == 0`）を候補から除外し、並走時の誤警告抑制が機能しにくい
- **優先度根拠**: 高架のバイパス（自動車専用）と側道（自転車通行可の通常道路）が並走する際、通常道路側のエッジが `null` としてスキップされるため、並走判定による誤警告抑止（`RoadRuleMatcher.update()`）が効かなくなる。
- **検証済み根拠**:
  - `LazyMappedRouting.kt:129-132`: `bicycleInfoOfEdge()` が `bits == 0` 時に `null` を返し、`RoadRuleMatcher.kt:28` (`roadCandidatesNear()`) が `branch.bicycleInfo == null` のエッジを `continue` でスキップしている（`RoadRuleMatcher.kt:87-97` の曖昧さ抑制ロジックは通常候補の存在を前提としている）。
- **推奨修正方針**: `roadCandidatesNear()` に通常の通行可能エッジ（軽量な道路セグメント情報）も含めて比較できるようにする。

#### [CORE-R1-05] Medium / P2: `EndpointTrim` のトリム距離と座標先頭インデックスの不一致・経由地誤トリム・候補距離不整合
- **優先度根拠**: 出発地・目的地近辺の折り返し除去処理（`EndpointTrim`）において、実際に削除した座標区間の長さと減算した `totalDistanceMeters` にズレがあり、また出発地/目的地から250m以内の経由地が巻き添えで消される恐れがある。
- **検証済み根拠**:
  - `EndpointTrim.kt:50-124`: `cutFromEnd()` は `best.e + 1` ノードを落とし `coords[best.e + 1]` を新先頭にするが、`dropMeters = best.cumulative + t * best.length`（投影点 `cut.point` までの距離）を `totalDistanceMeters` から減算（`line 120`）するため、投影点から `coords[best.e + 1]` までの差が距離誤差となる。
  - `MapRouteCalculation.kt:363-368`: 中間 `waypoints` が存在しても `stops.first()` / `stops.last()` を渡しており、`WINDOW_METERS = 250.0` 以内の経由地ノードがトリムされる保護がない。
  - また `MapRouteCalculation.kt:420` と `RealRoutePlanner.kt:102, 111` / `MapRouteCalculation.kt:554` 間で距離集計対象が不一致（`APP-R1-05` と関連）。
- **推奨修正方針**: `cut.point` を残存座標列に含めるか、実際に落としたノード間距離を正確に減算し、経由地ノードをトリム対象外として保護する。

#### [CORE-R1-06] Medium / P2: `TurnClassifier.classifyMappedRoute()` の ±2.5° 方位角マッチングによる分岐特定誤差
- **優先度根拠**: カーブした交差点や短い区間が連続する場所で、15〜30m先読みした `outgoingBearing` と直近1エッジの方位 `raw.bearing` が 2.5° 以上ズレると選択分岐の特定に失敗し、案内精度が `"angle-only"` へ劣化する。
- **検証済み根拠**:
  - `TurnClassifier.kt:116-133, 136, 162-169, 555-566` および `NavigationHelper.kt:70-79`: `MappedBranch` が `targetNodeId` を持たないため `RawBranch(null, ...)` を生成し、`abs(signedTurnDegrees(outgoing, raw.bearing)) <= 2.5` のみで分岐を突合している。
- **推奨修正方針**: `MappedBranch` に `targetNodeId`（または `targetIndex`）を追加し、方位角比較ではなくノードIDで一意に分岐を特定する。

#### [CORE-R1-07] Medium / P2: `OsmImporter.SearchIndexWriter` に `search_text` 列が未実装
- **優先度根拠**: Gradle CLI の `--search-index` 経路で検索DBを作ると `search_text` 列が存在せず、FTS5非対応Android実機でのひらがな・部分一致検索が破綻する（`build_search_db.py` 側では修正済み）。
- **検証済み根拠**: `OsmImporter.kt:435-490` (`SearchIndexWriter.write()`) が `(id, osm_type, osm_id, name, category, lat, lon)` のみを生成し、`search_text` と `idx_places_search_text` を欠く（`application/osm-importer/build_search_db.py:302-316` と不整合）。
- **推奨修正方針**: `SearchIndexWriter` を現行スキーマ（`search_text` 含む）に追従させるか、非推奨として `build_search_db.py` / `sandbox/data-tool` へ一本化する。

#### [CORE-R1-08] Medium / P2: `OsmImporter` の無バッファ書込み・エッジ連続性検証漏れ・64MiB固定確保
- **優先度根拠**: 数百万エッジの大規模県で無バッファ `DataOutputStream` が数千万回のシステムコールを発生させビルド時間を悪化させている。また `.graph.idx` 構築時に前提条件のアサーションがない。
- **検証済み根拠**:
  - `OsmImporter.kt:647` (`GraphBinaryWriter.write()`) と `887` (`writeGraphIndex()`): `FileOutputStream` を `BufferedOutputStream` なしで直接 `DataOutputStream` にラップ。
  - `OsmImporter.kt:879-884`: 同一 `from` ノードのエッジが連続配置されている前提で `starts`/`counts` を構築するが連続性検証がない（`RoutingEdgeCursor` L225-227 が連続性を要求）。
  - `OsmImporter.kt:281, 287-290`: 極小PBFでも最初に `LongOpenHashSet(23)`（64 MiB）を確保し、2ノード未満の `way` のノードも登録している。
- **推奨修正方針**: `BufferedOutputStream`（64KiB〜1MiB）を挟み、エッジ連続性チェックと適切な初期容量計算・`< 2` ノード `way` 除外を追加する。

#### [CORE-R1-09] Medium / P2: `LazyMappedRoadGraph` のバイナリ検証不備・空グラフ参照・`maxExpandedNodes` チェック間引き
- **優先度根拠**: 破損・細工された `.graph` / `.idx` を読み込んだ際に明確な `IllegalArgumentException` ではなく `NegativeArraySizeException` / OOM でクラッシュし、空グラフでも `ArrayIndexOutOfBoundsException` を起こす。
- **検証済み根拠**:
  - `LazyMappedRouting.kt:927-929`: `nodeCount < 0` のチェックがなく負値で `nodeBase + nodeCount * 24L <= limit` をすり抜け、L960 の `DoubleArray(nodeCount)` で `NegativeArraySizeException`。`readStringTableSection()` L1052-1054 も残バイト数上限検証なしで `Array(count)` を確保。
  - `LazyMappedRouting.kt:403` (`nearestNode()`): `nodeCount == 0` 時に `nearestNodeIndex` が `-1` を返してもそのまま `nodeIds[-1]` を参照。
  - `LazyMappedRouting.kt:551` (`route()`): `(expandedNodes and 1023) == 0` のときだけ上限確認するため、`maxExpandedNodes < 1024` 指定時でも最大1024ノード展開する。
- **推奨修正方針**: `require(nodeCount >= 0)`、文字列テーブルのバイト数上界検証、`nearestNode()` の `-1` ガード、`maxExpandedNodes` 判定見直しを実施する。

#### [CORE-R1-10] Medium / P2: `OsmImporter` でのノード `barrier=*` および `bicycle=use_sidepath` 未対応
- **優先度根拠**: 私有地ゲート／ボラード（`barrier=gate|bollard` + `bicycle=no`）を通り抜け可能と誤認したり、自転車専用側道が並走する車道（`bicycle=use_sidepath`）を案内してしまう。
- **検証済み根拠**: `OsmImporter.kt:48-82, 283-305`: `Node` タグ（`barrier=*`）や `Relation`（`type=restriction`）を解析せず、`BicycleAccessFilter.isAllowed()` も `bicycle=use_sidepath` を除外していない（`BicycleInfrastructure.kt:191` は `FORBIDDEN` 扱い）。
- **推奨修正方針**: `use_sidepath` 除外（またはペナルティ化）と、通行制限付きノードバリアの反映を追加する。

#### [CORE-R1-11] Low / P3: Gradle 9非互換、Javaツールチェイン未固定、ビルド警告とテスト補助の不備
- **優先度根拠**: 現JDK 21ではビルドが通るが、将来のGradle 9移行やJava 11互換性保証・警告ゼロ化のため対処が必要。
- **検証済み根拠**:
  - `application/app/build.gradle.kts:9-28`: `project.exec` が非推奨（Gradle 9削除予定、Configuration Cache阻害）、`getBuildTimeJst()` が構成フェーズで毎回実行。lines 111, 113 および `osm-importer/build.gradle.kts:8-9` にバージョンカタログ外の直書き依存あり。
  - `application/routing-core/build.gradle.kts:1-11` / `application/osm-importer/build.gradle.kts:1-11`: `jvmToolchain(11)` 指定がなくホストJDK 21のターゲットになる。
  - `application/gradle.properties:25`: 実験的フラグ `android.overridePathCheck=true` が残存。
  - `BikeInfoReindex.kt:354`: `Condition is always 'true'` コンパイル警告。
  - `OsmImporter.kt:933` (`runRouteSanityCheck`): 平行エッジのうち最短/最安ではなく `.first { it.to == to }` を選択。
- **推奨修正方針**: `providers.exec` への移行、`jvmToolchain(11)` 設定、`libs.versions.toml` 統合、不要な分岐・警告の解消を行う。

#### [CORE-R1-12] Low / P3: `WALK`/`CAR` コストモードの前提不整合と `ContractionHierarchy` キャンセル未対応
- **優先度根拠**: 現行アプリの本線（自転車ナビ）への即時悪影響は限定的だが、モード流用や長時間クエリの途中中断で問題になり得る。
- **検証済み根拠**:
  - `LazyMappedRouting.kt:861-873` (`multiplier()`): `OsmImporter` が `footway`/`pedestrian`/`steps` や `motorway`/`bicycle=no` を削るため、`WALK`・`CAR` モードが正確なコストで動作しない。
  - `ContractionHierarchy.kt:271-291, 328-380` (`route()`, `searchMany()`): `@Synchronized` かつ探索ループ内にキャンセル確認（`Thread.interrupted()` 等）がない。
- **推奨修正方針**: ドキュメント上でモード制約を明記し、CH探索ループに中断チェック（および読取スレッドローカル化）を検討する。

---

### 1.3 ルートパイプライン・`scripts/`・`sandbox/data-tool/`

#### [PIPE-R1-01] Critical / P0: `sandbox/data-tool/tools/core/merge.py` による別都市POIの誤マージ（80km超の施設統合・消失）
- **優先度根拠**: **データの完全性・安全性に直結する最重大データバグ**。同一県内で異なる市の同じ番地名（例: `広島市中区本町1-1` と `福山市本町1-1`）を持つチェーン店（セブン-イレブン等）を、距離確認なし・カテゴリ互換確認なしで1件に統合し、片方のPOIをDBから消去する。実際にインメモリDB再現テストで **84,188.4 m 離れた店舗が統合候補になることを確認済み**。
- **検証済み根拠**:
  - `sandbox/data-tool/tools/core/merge.py:193-238` (`normalize_addr_key`, Pass 3 `addr_map`): line 207 の `for _ in range(3): s = re.sub(r"^.*?(?:県|府|都|道|市|郡|区)-*", "", s)` が都道府県だけでなく市・区名まで削除する。その後 Pass 3 (`lines 213-237`) が `(c_brand, n_addr)` のみでグループ化し、空間距離チェックも `categories_compatible` も実行しない。
- **推奨修正方針**: `normalize_addr_key` では都道府県のみ除去して市区町村を残し、Pass 3 で `categories_compatible(winner["category"], loser["category"])` と空間距離上限（例: `d <= 500.0` m）を必須化する。

#### [PIPE-R1-02] Critical / P0: `scripts/build_terrain.py` が HTTP 403 レート制限を0バイトファイルとして恒久キャッシュ
- **優先度根拠**: 国土地理院サーバーからの一時的な WAF / レート制限 `403` を海上欠損 `404` と同一視して0バイトのキャッシュファイルを書き込むため、以後の全実行でスキップされ続け、地形タイル・等高線・道路勾配データに恒久的な「標高0mの穴」を生む。
- **検証済み根拠**: `scripts/build_terrain.py:134, 146-149` (`fetch_dem`): `if e.code in (404, 403): path.write_bytes(b"")` により0バイトファイルが作成され、次回以降は `path.exists() == True`（`line 134`）で再取得されない。
- **推奨修正方針**: 0バイトキャッシュは厳密に `404` のみに限定し、`403` / `429` / `5xx` は指数バックオフ再試行または失敗終了とする。既存キャッシュに対してもPNGヘッダ検証と不正0バイトファイルの清掃を行う。

#### [PIPE-R1-03] Critical / P0: ポリゴン穴（飛び地）未対応と沿岸POIの東京誤割当 (`config.py`)
- **優先度根拠**: 沿岸部・桟橋等のPOIがポリゴン外に出た際のフォールバックが `PREFECTURES` 定義順先頭（`cands[0]`）であるため、巨大な小笠原含むBBoxを持つ `tokyo`（#13）が神奈川（#14）・静岡（#22）・千葉等の海沿いPOIを誤吸収する。実測テストで `(34.60, 138.95)`（静岡沖）と `(35.15, 139.40)`（神奈川・相模湾）が `'tokyo'` を返すことを再現確認済み。
- **検証済み根拠**: `sandbox/data-tool/tools/core/config.py:41, 213-263` (`load_boundary_rings`, `prefecture_for_point`): `lines 228-233` が外周リング `coordinates[0]` のみ抽出し穴ポリゴン `poly[1:]` を無視、さらに `line 263` がポリゴン非包含時に `cands[0]` を返している。
- **推奨修正方針**: ポリゴン穴（even-odd / 外周マイナス穴）に対応し、ポリゴン外フォールバック時は `cands[0]` ではなく最短ポリゴン距離（または一定距離超過時は `None`）で判定する。

#### [PIPE-R1-04] Critical / P0: Overture Places の閉店POIフィルタ欠如・Foursquare帰属追跡欠如・`phone`/`website` 未収録とライセンス統合不備
- **優先度根拠**: ODbL 1.0（二次DB条項）、Apache 2.0/NOTICE（Overture Foursquareソース）、国土地理院DEM出典の法務・ライセンス要件に関わる上、出荷済み8県すべての `.search.db` で Overture の電話番号が 0 件（`ov_ph = 0`）となっている。
- **検証済み根拠**:
  - `sandbox/data-tool/tools/core/sources/overture.py:23, 193-213, 258-267`: `2026-09-23.1` 固定で閉店店舗を除外する `operating_status` フィルタがなく、`sources[].dataset`（`docs/poi_source_compatibility.md:12, 66` が求めるFoursquare等帰属情報）を取得していない。
  - 8県サンプリング（`hiroshima`, `yamaguchi`, `okayama`, `tottori`, `shimane`, `tokyo`, `fukuoka`, `okinawa`）全件で `schema_version=2` ながら `ov_ph = 0`（OSMからの `details_backfilled_at = 2026-11-15T07:32:*+00:00` のみ存在）。
  - `build_prefectures.py:72-99, 168-184`: `PACKAGE_LICENSE_TEXT` と `manifest.json` が静的文言で、統合 `search.db` の ODbL 派生DB表示および `--dem-dir` 使用時の国土地理院標高データ出典を欠く。
- **推奨修正方針**: `operating_status` フィルタと `sources` 取得を `overture.py` に追加し、最新データから `master.db` を再構築・再エクスポートする。`LICENSE.txt` / `manifest.json` は実収録ソースに応じて動的生成する（`PIPE-R2-01`, `PIPE-R2-02`）。

#### [PIPE-R1-05] High / P1: `sandbox/data-tool/tools/gui.py` が0バイト `master.db` で即クラッシュ（現行ワークスペースで再現）
- **優先度根拠**: 現在のリポジトリ状態（`sandbox/data-tool/data/work/master.db` が0バイト・テーブルなし）で Streamlit UI を開くと即座に `OperationalError` で停止する。
- **検証済み根拠**: `sandbox/data-tool/tools/gui.py:32-44` (`_master_stats`), `164-172` (`show_poi`), `233-238` (`show_merge`): ファイル存在確認のみで `masterdb.init_db()` や `poi` テーブル有無の確認を行わず `SELECT` を発行し、`sqlite3.OperationalError: no such table: poi` が発生。
- **推奨修正方針**: DB接続時に `masterdb.init_db()` を呼ぶか `sqlite_master` でテーブル存在確認を行い、空状態メッセージを表示する。

#### [PIPE-R1-06] High / P1: OSM `way` 全量取り込みによる `'named'` ノイズ肥大化（47%–63%）と約1.9万組の重複ペア発生
- **優先度根拠**: 国道・新幹線・在来線の各リンクが1本ずつ別POIとして検索DBに入り（例: 広島で `国道2号` 537件、`JR山陽本線` 833件、`ダイドードリンコ` 1,472件）、DB肥大化と検索品質低下・50m以内の同一名重複ペア **18,913組**（非 `'named'` 含む）を引き起こしている。
- **検証済み根拠**:
  - `sandbox/data-tool/tools/core/sources/osm.py:107-156` (`_PoiHandler.way`), `application/osm-importer/build_search_db.py:211-222`, `sandbox/data-tool/tools/core/sources/overture.py:26-113`, `export.py:155` (`include_named=True`)。
  - 実DB計測: `'named'` 比率が `hiroshima` 52.2%, `yamaguchi` 59.9%, `okayama` 54.6%, `tottori` 63.1%, `shimane` 61.4%, `tokyo` 47.4%, `fukuoka` 52.7%, `okinawa` 50.5%。広島DBで半径50m以内の同一名称ペアが18,913組（うち非 `'named'` のソース間重複286組：`ファミリーマート`、`あやめ幼稚園` など）。
- **推奨修正方針**: `osm.py:way()` で線状インフラ（`highway` ただし `bus_stop`/named rest_area除く、`railway=rail/narrow_gauge`、`waterway`）を除外し、`OVERTURE_CATEGORY_MAP` に自販機・ATM・郵便局・銀行・学校等を追加した上で `include_named` の扱いを縮小／無効化する。

#### [PIPE-R1-07] High / P1: `normalize.py`（および `SearchNormalizer.kt`）のデッドコードによる `'ヶ'` → `'け'` 変換不発
- **優先度根拠**: カタカナ範囲終端 `_KATA_END = 0x30F6` が `'ヶ'` と一致するため、後続の `elif c == "ヶ": out.append("け")` が到達不能のデッドコードになっており、`"八ヶ岳"` 等が誤変換（`'八ゖ岳'`）され検索ヒットしなくなる。
- **検証済み根拠**: `sandbox/data-tool/tools/core/normalize.py:21, 88-91` (`to_hiragana`)、および同範囲 `'ァ'..'ヶ'` を持つ `application/app/src/main/java/com/gorite/cyclemap/search/SearchNormalizer.kt:20`。実測で `normalize.to_hiragana("八ヶ岳") == '八ゖ岳'` を確認。
- **推奨修正方針**: `'ヶ'`/`'ヵ'` の個別分岐を範囲変換より前に配置し、Python側とKotlin側の正規化仕様を完全一致させる。

#### [PIPE-R1-08] High / P1: Windows 上で `find_bash()` が WSL `system32\bash.EXE` を選択しデプロイ・テスト全15件が失敗
- **優先度根拠**: Windows 環境で Data Studio や `pytest` 経由で `push_data.sh` を呼ぶと、WSL側（`cygpath` なし）が起動されて全件失敗する（`scripts/tests/test_push_data.py` 15件全滅を実測確認）。
- **検証済み根拠**: `scripts/studio_plan.py:103-110` (`find_bash`), `scripts/tests/test_push_data.py:81`。`shutil.which("bash")` が `C:\WINDOWS\system32\bash.EXE` を返し、`/bin/bash: line 1: cygpath: command not found`＋文字化け例外で落ちる。
- **推奨修正方針**: `sys.platform == "win32"` では Git Bash（`C:\Program Files\Git\bin\bash.exe` 等）を優先探索し、`system32\bash.exe` を除外する（中期的には `push_data.py` へ移行）。

#### [PIPE-R1-09] High / P1: `scripts/push_data.sh` の `unzip` 不在時サイレント空出力・全47県一括送信・旧ファイル名優先競合
- **優先度根拠**: `unzip` がない Windows Git Bash 等でエラーなく `.graph` が欠損したまま終了コード0で終わる危険があり、さらに旧ファイル名（小文字 `yamaguchi.graph` / `search.db`）がアプリ側で優先されるため新データが読まれない。
- **検証済み根拠**:
  - `scripts/push_data.sh:366-367`: `unzip -p "$zip_file" "${pref}.graph" > "$graph_staged" 2>/dev/null || true` が失敗してもスキップ継続。
  - `lines 49, 211-217, 349-381`: 引数未指定時 `ALL_PREFS`（47県＋87ポータル、15GB超）を転送しようとし、`--pref` は1県のみで県名バリデーションなし。
  - `lines 325-329`: `Yamaguchi.graph` を転送するが、`PrefectureSelectors.kt:29, 113` は小文字 `yamaguchi.graph` / `search.db` を先に採用する。また `chmod 666` に `|| true` がなく Android FUSE で中断する恐れ、および `lines 115, 183` で Windows Store スタブに当たり得る `python3` を直呼びしている。
- **推奨修正方針**: `unzip` 欠如時の Python `zipfile` フォールバック＋空ファイル即エラー化、`--pref` 明示化または `--all` 必須化、旧小文字ファイルの削除/警告、`chmod` の `|| true` 付与（または `push_data.py` 化）を実施。

#### [PIPE-R1-10] High / P1: `scripts/refresh_data.py` の `--within` ポータル更新漏れ・`rebuild_all.py` Windows非対応・ロールバック不完全
- **優先度根拠**: 単県リフレッシュ（例: `--only Hyogo`）で県境ポータル（`Okayama__Hyogo.portals` 等）が更新されず、失敗時ロールバックも `master.db` を戻せない。
- **検証済み根拠**:
  - `scripts/refresh_data.py:521, 217`: 非中国地方指定時に `--within` を付与するため、`len(names) == 1` の場合に `select_pairs` が0ペアを返す。
  - `sandbox/data-tool/rebuild_all.py:168, 328`: `_platform.find_tool("osmium")` を使わず `"osmium"` 直呼び。
  - `refresh_data.py:141-154, 192-197, 372-384, 513-521`: `master.db` を直接書き換えロールバックでは `out/*.search.db` のみ復元、また `--skip ch` 時に古い `.graph.ch` を削除・警告しない。
- **推奨修正方針**: 単県時または `refresh_data.py` では `--within` を外し、`rebuild_all.py` で `_platform.find_tool` を使用、`master.db` のスナップショット保全と古い `.graph.ch` の無効化を行う。

#### [PIPE-R1-11] High / P1: Data Studio (`studio_plan.py` / `data_studio.py`) の `search_db` ステップ欠落・未宣言 `psutil` 依存・64スレッド上限クラッシュ
- **優先度根拠**: ユーザーが GUI から新規県の `build_prefectures.py` を起動しても前段の `search.db` がなく即失敗するほか、環境依存パッケージ `psutil` の `requirements.txt` 記載漏れと高コアCPUでのクラッシュ要因がある。
- **検証済み根拠**: `scripts/studio_plan.py:27-34, 153-185, 258` と `scripts/data_studio.py:49, 124, 134-145`: `STEPS` に `search_db` がなく、ルート `requirements.txt` に `psutil` が未定義、CPU入力上限が `max_value=64` 固定。
- **推奨修正方針**: `search_db` 生成ステップ（またはプリフライト遮断）を追加し、`psutil` のフォールバックガード／依存明記、`max(64, int(res["cpu"]))` への動的調整を行う。

#### [PIPE-R1-12] High / P1: `scripts/build_tiles.py` が単一外接矩形抽出で `tokyo.pmtiles` を326MBに肥大化
- **優先度根拠**: 小笠原・南鳥島を含む外接矩形（`138.94..153.99, 24.22..35.90`）でタイル抽出およびOverture取得を行うため、太平洋と中部・関東の半分のタイルまで含まれファイルサイズと取得時間を圧迫している。
- **検証済み根拠**: `scripts/build_tiles.py:68-87` (`boundary_bbox`), `sandbox/data-tool/tools/core/config.py:41`, `sandbox/data-tool/tools/core/sources/overture.py:209-210`。実測 `packages/tokyo.pmtiles` = **326,081,896 bytes**。
- **推奨修正方針**: 島嶼県（東京・鹿児島・沖縄・長崎・島根・北海道）を本土BBoxと島嶼サブBBoxに分割（タイルマージまたはポリゴンマスク抽出、DuckDBのマルチBBox `OR` クエリ）する。

#### [PIPE-R1-13] High / P1: `scripts/generate_admin_boundaries.py` が広島県専用にハードコード
- **優先度根拠**: 他46都道府県で `admin_level=7,8` 自治体ポリゴンが生成されず、全県展開時に市町村単位の逆ジオコーディング精度が広島県のみに限定される。
- **検証済み根拠**: `scripts/generate_admin_boundaries.py:42, 76, 77, 223`: `work/hiroshima_admin_all.geojson`, `"広島市"`, `"広島県"` が直書きされ、`AdministrativeBoundaries.kt` を広島のみで上書きする設計。
- **推奨修正方針**: 全47都道府県対応へ一般化し、`.kt` 直書きではなく `build_prefecture_polygons.py` 同様に県別リソース／アセット形式へ出力先を変更する。

#### [PIPE-R1-14] Medium / P2: `build_prefectures.py` の運用・検証・ログ解析バグ（6項目）
- **優先度根拠**: 旧アーカイブ参照やDEM不在時の無警告、ログ再追記時の初回実行結果誤読など、ビルド品質とデバッグ性を損なう。
- **検証済み根拠**:
  - `build_prefectures.py:37-52`: `application/` インポータ未ビルド時に `Archives/` へ黙ってフォールバック。
  - `lines 105-157` (`verify_search_db`): `places_fts`, `places_osm_idx`, 非空 `search_text`, `place:*` 行、`schema_version=2` 列を未検証。
  - `lines 598-599`: `work/terrain/dem14` 固定で `--dem-dir` 引数がなく、DEM不在でも警告なし（勾配0固定グラフを生成）。
  - `lines 609-610`: `run_live` が追記モード `"a"` で `logs/<name>.log` に書き、`parse_importer_counts` が先頭から `re.search` するため**最古の実行結果**を返す。
  - `line 33`: Windows git symlink テキストスタブ（数バイト）を `_root_pbf.is_file()` が有効PBFと誤認する（サイズ `> 1 MB` 未確認）。
  - `line 188`: Python 3.12+ 非推奨の `datetime.utcnow()` 使用。
- **推奨修正方針**: 上記6点のバリデーション強化、ログの上書き／末尾パース、`--dem-dir` オプション＋不在時警告、サイズ検証、タイムゾーン付き `datetime.now(timezone.utc)` へ修正。

#### [PIPE-R1-15] Medium / P2: フォールバック用 `application/osm-importer/build_search_db.py` のスキーマ・検索非互換
- **優先度根拠**: メインツール未利用時のフォールバックDBが現行アプリと非互換（v1スキーマ、ひらがな/ローマ字欠落、`search_text` 不十分）になっている。
- **検証済み根拠**: `application/osm-importer/build_search_db.py:70, 81-96, 271-272, 302-337, 423`: v1スキーマのみ、FTSに素の `name` のみ格納、古い `category_for()`、非推奨 `tempfile.mktemp()`、行ごと `import re`、`dict` 使用。
- **推奨修正方針**: スキーマ・正規化ロジックを `sandbox/data-tool` 互換に合わせるか、`sandbox/data-tool` 側へ統一する。

#### [PIPE-R1-16] Medium / P2: `merge.py` のデッドコードと重複排除漏れ・Undo不整合
- **優先度根拠**: 広島DBで286組の非 `'named'` 重複や、イオンモール本体とテナントの誤結合、Undo時のメタデータ復旧漏れを引き起こしている。
- **検証済み根拠**: `sandbox/data-tool/tools/core/merge.py:24, 47, 65, 67, 79, 85-87, 158, 168-190, 260-285`:
  - `COMPATIBLE_GROUPS` の `highway:bus_stop` + `amenity:bus_station` が `STRICT_EXACT_ONLY_CATEGORIES` によりデッドコード化。
  - Pass 1 の `50.0 m` 固定閾値により大型敷地（学校・病院 `kokudo:school` vs `osm` の `あやめ幼稚園` 等）が未マージ。
  - Pass 2 がOSM 1件につきOverture 1件しか統合せず余剰重複が残り、`o["address"]` をインメモリ書き換え。
  - `COMPACT_CHAINS` 部分一致＋`categories_compatible("named", ...)` によりモール内店舗が親モールに統合される恐れ。
  - `apply_merge` が親POIの `name`/`category`/`address` を破壊的上書きするため `undo_merge`/`undo_all` で復元できない。
- **推奨修正方針**: スクール/病院向け半径拡大、1:N重複統合、親モールとテナントの区分、Undoスナップショット保存を実装。

#### [PIPE-R1-17] Medium / P2: `overture.py` のカテゴリ優先順位誤り・DuckDB `httpfs` 毎回インストール・`bbox` 座標利用
- **優先度根拠**: `"〇〇温泉駅"` や `"〇〇温泉病院"` が一律 `amenity:public_bath`（温泉・銭湯）に誤分類される。
- **検証済み根拠**: `sandbox/data-tool/tools/core/sources/overture.py:139-140, 190, 203-204`: 名前部分一致が `overture_cat` より先に評価され、`INSTALL httpfs; LOAD httpfs;` を毎県実行し、点ではなく `bbox.xmin, bbox.ymin` を採用している。
- **推奨修正方針**: `overture_cat` 判定を優先し、`LOAD httpfs` のみの試行→失敗時のみ `INSTALL` に変更、中心座標（または `ST_Centroid`）を採用する。

#### [PIPE-R1-18] Medium / P2: `export.py`・`overlay.py`・`validate.py` のエッジケース不備（8点）
- **優先度根拠**: すべてのサンプルDBで `idx_places_search_text` インデックスが欠落（`st_idx=0`）し、検索の応答性能や `to_romaji` での `'sebun'irebun'` アポストロフィ分断による部分一致漏れを引き起こしている。
- **検証済み根拠**:
  - `sandbox/data-tool/tools/core/export.py:14-30, 55-58, 166-168, 213` / `normalize.py:148-149`: 重複import、`out_path` の親ディレクトリ空文字時の `makedirs` 失敗、非アトミック削除、`idx_places_search_text` 欠落、`n'` アポストロフィ挿入。
  - `overlay.py:59-72, 199-244`: 1秒精度バックアップ衝突・ディレクトリ固定、`edit` で `prefecture` を変更したPOIが解決処理で両方から消える。
  - `validate.py`: docstring「11項目」に対し12項目実行し、`PRAGMA integrity_check` が未実施。
- **推奨修正方針**: `idx_places_search_text` 生成追加、アポストロフィ除去/併用トークン出力、一時ファイルからのアトミックリネーム、`PRAGMA integrity_check` 追加を実施。

#### [PIPE-R1-19] Medium / P2: `scripts/build_terrain.py` の欠損値0m扱い・`GeometryCollection` 黙殺・`--workers` 無視
- **優先度根拠**: 内陸DEMデータの欠損値（`2**23`）が標高0mとして処理され、山中に人工的なクレーター／等高線リングを生成する。
- **検証済み根拠**: `scripts/build_terrain.py:185, 195, 289, 355-357, 360, 408`: 欠損値 `0.0 m` 変換、`GeometryCollection` クリップ結果の破棄、既存ファイル妥当性未検証、`download_all` での `--workers` 無視。
- **推奨修正方針**: 欠損値を `NaN` として補間または等高線生成スキップ、`GeometryCollection` 内の `LineString` 抽出、並列ワーカー適用を行う。

#### [PIPE-R1-20] Medium / P2: `scripts/build_tiles.py` のローカル日付依存とネットワーク例外未捕捉
- **優先度根拠**: JST 00:00〜09:00 の時間帯にUTC基準の Protomaps ビルド探索で1日ズレ・タイムアウト時の例外捕捉漏れが発生する。
- **検証済み根拠**: `scripts/build_tiles.py:51-65` (`latest_build`): `date.today()`（ローカル時刻）使用、`URLError` 未捕捉。
- **推奨修正方針**: `datetime.now(timezone.utc).date()` を使用し、`URLError` / `TimeoutError` を明示ハンドリングする。

#### [PIPE-R1-21] Medium / P2: `scripts/patch_map_style.py` の壊れやすいAST直参照とPOIピン重複除去漏れ
- **優先度根拠**: スタイルと動的ピンの二重描画（UI懸念 `DATA-R3-01` 直結）および Protomaps Basemaps 更新時のスクリプト破損リスク。
- **検証済み根拠**: `scripts/patch_map_style.py:35, 128`: `POI_KINDS_COVERED_BY_PINS` が7種のみ除外（残り16種以上は動的ピンと重複）、`flt[1][2][1]` の配列インデックス直書き。
- **推奨修正方針**: `CyclingData.kt`（`PoiCategory` v3）と同期して対象種別を網羅し、ASTは再帰検索または明示的スキーマ検証で書き換える。

#### [PIPE-R1-22] Medium / P2: `scripts/build_ch.py` / `scripts/build_portals.py` の無駄なZIP展開とオフラインビルド不備
- **優先度根拠**: 更新不要な県でも ~300 MB/県の `.graph` 展開が走りビルド時間を浪費し、Gradle実行時に `--offline` がなくネット不通環境で失敗する。
- **検証済み根拠**: `scripts/build_ch.py:120-142, 200-203, 219`, `scripts/build_portals.py:194`: `build_ch.py` がフィンガープリント照合前にZIP展開、Gradle呼び出しに `--offline` なし、`-Pheap=2g` 固定（OOM再試行なし）、`prepare_direct_classpath` が `build.gradle.kts` mtimeやサードパーティJARを考慮しない。
- **推奨修正方針**: `build_portals.py:61-75` 同様にストリーム上で指紋照合して不要展開を回避し、`--offline` フォールバック・OOM時ヒープ自動増量を追加。

#### [PIPE-R1-23] Medium / P2: `scripts/rebuild_and_deploy.sh` & `scripts/download_data.ps1` の旧中国5県固定・ツール連携欠落
- **優先度根拠**: 全国47都道府県および CH / Portals / Terrain 対応に追従しておらず、誤用または検証不足を招く。
- **検証済み根拠**: `scripts/rebuild_and_deploy.sh:24, 84, 101-103, 142`（中国5県以外拒否、`build_ch`/`portals`/`terrain` 欠落、`venv/bin/python` と `python3` 混在）、`scripts/download_data.ps1:27-33, 55, 80`（中国5県固定、`-f` および SHA-256 検証なし）。
- **推奨修正方針**: 47県・全成果物対応へ更新するか、非推奨として `refresh_data.py` / `push_data.py` へ誘導・統合する。

#### [PIPE-R1-24]〜[PIPE-R1-30] Low / P3: その他運用スクリプト・データツール細部バグ（7件）
- **優先度根拠**: 特定条件・開発者補助ツール限定の不具合であり本番データや走行体験への直接影響は限定的。
- **検証済み根拠と推奨修正**:
  - **[PIPE-R1-24]** `scripts/check_all.sh:16-22`: `"$PY"` と素の `python3` 混在、最小Windows Git Bashにない `shasum` 使用 → `"$PY"` と Python `hashlib` に統一。
  - **[PIPE-R1-25]** `scripts/device_regression.sh:49`: バックグラウンド `SIMLOC` ループの `EXIT`/`INT` `trap` 終了処理欠落 → `trap 'kill $LP 2>/dev/null || true' EXIT INT` 追加。
  - **[PIPE-R1-26]** `scripts/capture_baseline.sh:19`: `PKG="com.gorite.cyclemap.test"` 直書きで `CYCLEMAP_PACKAGE_NAME`（`.win` 等）を無視 → 環境変数オーバーライド対応。
  - **[PIPE-R1-27]** `scripts/data_studio.py:248-255`: `tools/gui.py` をルート `sys.executable` で起動（`sandbox/data-tool/.venv` 不使用）、ポート8502競合未確認、PID/stderr破棄 → venv解決・ポート確認・ログ保持を追加。
  - **[PIPE-R1-28]** `sandbox/data-tool/tools/core/config.py:79-100`: `APP_CATEGORY_PREFIXES` が `CyclingData.kt:424-480` と乖離 → カテゴリ定義を自動同期。
  - **[PIPE-R1-29]** `sandbox/data-tool/tools/core/sources/kokudo.py:77`: `medical:name@addr` の `source_id` が同名・同住所施設で衝突可能 → 行番号や座標ハッシュを付与。
  - **[PIPE-R1-30]** `sandbox/data-tool/tools/core/config.py:20`: ルート `boundaries/` へフォールバックせず `sandbox/data-tool/data/raw/boundaries/` へ複製要求 → ルート `boundaries/` 参照に統一。

---

### 1.4 既往バグ修正確認（`Archives/application/bugs.md` との照合結果）

`AGENTS.md` で確認が求められている `Archives/application/bugs.md` の既往Critical/High項目と現行コードとの照合結果は以下の通り。

| 既往ID | 概要 | 現行コードでの検証状態 | 根拠箇所 |
|---|---|---|---|
| **BUG-72 〜 BUG-85（全般）** | 統合前の主要バグ群 | **修正済み確認**（1件を除く） | `:app` 574テスト / `:routing-core` 183テスト / `:osm-importer` 34テスト全パス |
| **BUG-75** | 非幹線ペナルティの累積適用問題 | **修正済み** | `LazyMappedRouting.kt:599-602` (`combinedMultiplier`, `>= BicycleCost.MIN_MULTIPLIER` 0.90) / `BicycleCost.kt:14-52` |
| **BUG-76** | `search_text` 列欠落問題 | **一部未適用** | `build_search_db.py:302-316` & `export.py` は修正済み。**`OsmImporter.kt:444-464` (`SearchIndexWriter.write`) のみ未修正**（`CORE-R1-07`） |
| **BUG-79** | NaN方位角によるUI回転破損 | **修正済み** | `MapState.kt:207-208` (`val safeBearing = if (bearing.isNaN()) 0f else bearing`) |
| **BUG-82** | Haversine `sqrt(1 - a)` の `NaN` 問題 | **一部未適用** | `Routing.kt:146` (`val clampedA = a.coerceIn(0.0, 1.0)`) は修正済み。**`WeatherRepository.kt:242` のみ未適用**（`APP-R1-09`） |
| **BUG-83** | 空グラフでの `nearestNodeIndex` クラッシュ | **一部残存** | `LazyMappedRouting.kt:329, 367` は `-1` 返却へ修正済み。**同 `nearestNode()` line 403 が `-1` をそのまま添字参照**（`CORE-R1-09`） |

---

## 第2章: UI上の懸念点（R3）

### 2.1 実機操作で確認されたUI・レイアウト・操作性の懸念点（Galaxy S26実機検証）

#### [DEV-R3-01] Critical / P0: 横画面（Landscape）での重大レイアウト衝突および `"盤面に戻る"` CTA消失
- **優先度根拠**: 横画面マウント利用時に操作ボタン群・ステータスバナー・カテゴリチップが全面衝突し、下部の主要アクションも完全に画面外へ消えて操作不能になる。実機で100%再現。
- **検証済み根拠**:
  - **実機再現手順**: 拡大地図（`"盤面に戻る"` 表示状態）またはナビ中に端末を横向き回転（`user_rotation 1`）。
  - **実測・座標**: `51_landscape.png`, `51_landscape.xml`: 横並びの `MapControls`（`[981,306][2304,462]`）が `"GPS信号が途絶えました"` バナー（`[900,300][1441,408]`）とカテゴリチップ列（`[42,420][2298,564]`）に重なり、`"盤面に戻る"` CTAが1080px高の画面外に消失。
  - **コード箇所**:
    - `MapControlsOverlay.kt:185-213`: `LandscapeControlRow` が右上（`top = effectiveTopPadding + 12.dp` ナビ中 / `68.dp` 非ナビ中）に固定配置され、動的バナーによる高さ増加や `LandscapeNavHudBar`（`MapTopOverlays.kt:222-226`, `NavigationHudViews.kt:298` `fillMaxWidth()`, `top = effectiveTopPadding + 8.dp`）との幅競合を考慮していない。
    - `MapBottomOverlays.kt:471, 504`: `if (!isLandscape && onBackToDashboard != null)` により横画面では `"盤面に戻る"` と下部タブが非表示になる一方、ナビ中も `LandscapeNavHudBar` と `GoogleNavFooterView`（`MapBottomOverlays.kt:161-172`）の両方に `"終了"` ボタンが重複出現する。
- **推奨修正方針**: 横画面ナビ中は `LandscapeNavHudBar` と `LandscapeControlRow` を重複しない領域（例: `Row` での左右分割または下端ドック配置）に分離し、非ナビ時は `SearchTopBar`＋バナーの計測高さを基準に `LandscapeControlRow` を配置、さらに横画面でも「盤面に戻る」アクセス手段を提供する。

#### [DEV-R3-02] High / P1: 上部通知バナー出現時のカテゴリチップとレイヤー切替FABの衝突
- **優先度根拠**: GPS低下・圏外通知・ルート探索中バナーなど、実走行中に高頻度で出現するバナーが出るたびに `"道の駅"` チップとレイヤー切替FABが重なり誤タップを誘発する。
- **検証済み根拠**:
  - **実機再現手順**: 拡大地図モードでステータスバナー表示中（`GPS精度が低下しています` / `最適サイクリングルートを探索中...` 等）。
  - **実測・座標**: `09_expand_btn.png`, `09_expand_btn.xml`, `13_area_download.png`, `38_cross_pref_route.png`: `"道の駅"` チップ（`[763,445][1037,589]`）がレイヤー切替FAB（`[894,481][1038,625]`）と重なる。
  - **コード箇所**: `MapControlsOverlay.kt:219` が右上コントロール列の上端オフセットを固定値 `effectiveTopPadding + 122.dp` にしているのに対し、`MapTopOverlays.kt:121-123, 355-358` の `SearchTopBar` はバナー分の高さが動的に増える。
- **推奨修正方針**: `MapContentLayout.kt:405` で取得可能な `placeTopHeight`（`SearchTopBar`＋バナー＋チップの実測高さ）を `MapControlsOverlay` へ渡し、動的オフセットで配置する。

#### [DEV-R3-03] High / P1: クイックカテゴリチップ押下時に前回開いていた `"保存済み"` タブが残留する
- **優先度根拠**: ユーザーが上部チップ「コンビニ」をタップしたのに「お気に入りスポット」が開き、検索結果が裏の「スポット」タブに隠れて「動かない／空だ」と誤認する。
- **検証済み根拠**:
  - **実機再現手順**: 下部シートで `保存済み` タブを開く → シートを閉じる → 地図上部チップ `コンビニ` をタップ。
  - **実測**: `54_conv_opened.png`, `55_spot_tab_after_conv.png`: `お気に入りスポット` タブのまま開き、`スポット` タブへ手動切替するまでコンビニ一覧が見えない。
  - **コード箇所**: `MapScreenScaffold.kt:217-221` の `onSelectCategory` が `state.showSpotPanelState.value = true` のみ設定し、`state.showFavoritesOnlyInSheetState.value = false` および `state.googleNavTabState.value = GoogleNavTab.EXPLORE` へのリセットを行っていない（`SpotPanelSheet.kt:75` の `showFavoritesOnly` も初期値に依存）。
- **推奨修正方針**: `onSelectCategory` 内（および検索結果選択時などスポット表示意図の入口）で `showFavoritesOnlyInSheetState.value = false` と `googleNavTabState.value = GoogleNavTab.EXPLORE` を明示設定する。

#### [DEV-R3-04] High / P1: エリアダウンロード選択モードによる表示モード状態の破壊とUI重なり
- **優先度根拠**: 拡大地図からエリア選択に入りキャンセルすると元の拡大地図ではなくシンプル盤面（`HomeDashboard`）へ強制戻しされ、選択中も操作バナーとFAB・下部バーが崩れる。
- **検証済み根拠**:
  - **実機再現手順**: 拡大地図（`"盤面に戻る"`） → 右上エリア選択FAB（`[894,829][1038,973]`）をタップ → ドラッグ → キャンセル。
  - **実測**: `13_area_download.png`, `13_area_download.xml`, `14_area_drag.png`, `15_saved_tab.png`: エリア選択に入った瞬間 `"盤面に戻る"` が `スポット/保存済み/記録` タブへ切り替わり、`"ドラッグしてダウンロード範囲を選択"` バナーがレイヤー切替FABと衝突、キャンセル後は `HomeDashboard` へ戻る。
  - **コード箇所**: `MapContentLayout.kt:184` が `isIdle = ... && !isAreaSelectMode` と定義しているため、エリア選択開始で `isIdle` が `false` になり `line 524` の `onBackToDashboard` が `null` 化、`MapBottomOverlays.kt:504` の通常タブバーへ落ちてしまう。
- **推奨修正方針**: `onBackToDashboard` 条件や状態遷移から `isAreaSelectMode` による下部バー切替を切り離し、エリア選択前の状態（拡大地図／通常地図）を保持・復帰させる。

#### [DEV-R3-05] High / P1: `地理院標準` レイヤー切替時のステータスバー視認性低下・帰属表示欠落・急なズームアウト
- **優先度根拠**: 屋外の明るい環境下で画面上部の時刻・電池アイコンが白地図に溶けて読めなくなるほか、**`AGENTS.md` で必須要件とされているオフライン時の地理院・OSM帰属表示が地図隅に表示されない**（コンプライアンス上の問題）。
- **検証済み根拠**:
  - **実機再現手順**: レイヤー切替メニューから `地理院標準` を選択。
  - **実測**: `11_gsi_standard.png`: 明色ラスター地図上にステータスバーの白アイコンがスクリムなしで載りほぼ判読不能。地図も意図せずズームアウトし、画面隅にOSM/地理院の帰属テキストが一切出ていない（Vector時は `MapLibre i` のみ）。
- **推奨修正方針**: `StatusBarScrim` を通常時かつ明色ラスター地図上でも適用し、osmdroid `MapView` 切り替え時のズーム同期（`VectorMapView` とのズーム引継ぎ）修正と、地図隅の常設帰属表示オーバーレイを追加する。

#### [DEV-R3-06] Medium / P2: サイコンモードの屋内静止GPSドリフト蓄積および終了時の地図白紙化
- **優先度根拠**: 机上で静止していても62m走行・最高速度18.0km/hが記録され、終了直後に地図がベージュの白紙面になり再中心合わせまで描画されない。
- **検証済み根拠**:
  - **実機再現手順**: 屋内で `サイコンモード` に切替ししばらく放置後、通常地図へ戻る。
  - **実測**: `50_cycle_computer.png`, `50_cycle_computer.xml`, `43_cycle_computer_mode.png`, `44_after_recenter.png`: 静止中に `走行距離 62 m`, `平均速度 3.1 km/h`, `最高速度 18.0 km/h` が積算。退出時に `43_cycle_computer_mode.png` の空白面が発生し、現在地ボタンによる `surfaceCreated` まで復旧しなかった。
- **推奨修正方針**: サイコン計測に速度閾値・精度フィルタ（HDOP/accuracy・最小移動距離ゲート）を導入し、サイコンモードの前面オーバーレイ終了時に `MapLibre` サーフェス再描画（または裏面での破棄抑止）を確実に行う。

#### [DEV-R3-07] Medium / P2: `3D地形ビュー` が実地図ではなく静的サンプルジオラマを表示＆英語表記・上下クリップ
- **優先度根拠**: ユーザーが「3D地形ビュー」から現在のエリアの立体地形が見られるものと期待して開くと、固定イラスト風ジオラマ（`"3D Simulation"` 英語チップ）が表示され、下部ヒントと上部ステータスバーもクリップされる。
- **検証済み根拠**:
  - **実機再現手順**: ドロワー → `3D地形ビュー`。
  - **実測・座標**: `49_3d_terrain_view.png`, `49_3d_terrain_view.xml`: ヘッダが英語 `"3D Simulation"`、中身は `山と川・ログハウス` / `雪山ジオラマ` のサンプルカード、下部ヒントピル（`[128,2263][952,2340]`）が画面下端でクリップ、上端 `y=0..115` に背景の地図とPOIピンが透ける。
  - **コード箇所**: `ui/simulation/Isometric3dSimulationView.kt:64-67, 150, 355, 444-458`: `line 355` に `"3D Simulation"` 直書き、`line 150` に `statusBarsPadding()` 欠如、`line 449` に `navigationBarsPadding()` 欠如。
- **推奨修正方針**: 上下セーフエリアパディングと日本語ラベル（または「3Dサンプル表示」等の明確化・将来的な実DEM 3Dビュー化）を適用する。

#### [DEV-R3-08] Medium / P2: アクティブナビ中の数値カード上下位置不揃い＆`立ち寄り` FAB のクリップ
- **優先度根拠**: ナビ画面の視認性・バランスを損ね、狭い画面や長文案内時は右側の `立ち寄り` FAB が下パネル上端に食い込んでタップしづらくなる。
- **検証済み根拠**:
  - **実機再現手順**: ナビ開始（`24_nav_active.png`, `24_nav_active.xml`, `28_nav_waypoint_added.png`）。
  - **実測・座標**: `速度` 数値 `0`（`[72,1583][166,1780]`）が上寄せで、`残り 387` と `勾配 0` が下寄せ（`y=1675..1780`）。`立ち寄り` FAB（`[894,1320][1038,1447]`）は高さ `127px`（標準 `144px`）にクリップされナビパネル上辺（`y=1447`）に接触。
  - **コード箇所**: `NavigationPanelC.kt:393-421, 620-647`（`IntrinsicSize.Min`＋`SpaceBetween` の中で `56sp` と `30sp` のテキスト高差によりベースライン／配置差が出る）、`MapControlsOverlay.kt:327-389`（縦に5個並べたFABが下部パネルの上端を考慮していない）。
- **推奨修正方針**: `MetricCard` 内の数値行の垂直アラインメント（`Alignment.Bottom`）を統一し、ナビ中右側コントロール列をボトムパネル上端より上に収める（高さ制限／横2列またはコンパクト間隔化）。

#### [DEV-R3-09] Medium / P2: 左ドロワー最下部 `"バージョン情報"` がシステムジェスチャーバー背後にクリップ
- **優先度根拠**: ナビゲーションバー領域のセーフエリア適用場所を誤っているため、最下段メニューの半分がジェスチャーバーに隠れる。
- **検証済み根拠**:
  - **実機・座標**: `02_menu.png`, `02_menu.xml`: `"バージョン情報"`（`[0,2248][929,2340]`, テキスト `[156,2296][488,2340]`）がシステムバー（`[0,2295][1080,2340]`）に食い込んでいる。
  - **コード箇所**: `application/app/src/main/java/com/gorite/cyclemap/ui/layout/MapDrawerContent.kt:77, 142-157`: `navigationBarsPadding()` がメニュー群の後の出典 `Text`（`line 155`）にのみ付いており、スクロールする `Column`（`line 77`）のボトムパディングまたは下部余白として機能していない（初期スクロール位置で最下項目がバー裏にかかる）。
- **推奨修正方針**: `MapDrawerContent.kt:77` の `Column` に `navigationBarsPadding()` を移動する（または末尾パディングを十分確保する）。

#### [DEV-R3-10] Medium / P2: 検索結果最上部カードがステータスバー裏にクリップ・重複表示・詳細チップ低コントラスト
- **優先度根拠**: 一覧の最上位（逆順レイアウトの上端）が見切れ、また同地点の駅が2重表示されたりスポット種別ラベルが暗背景に暗青文字で読みづらい。
- **検証済み根拠**:
  - **実機再現・座標**: `36_search_iwakuni_kanji.png`, `36_search_iwakuni_kanji.xml`, `37_iwakuni_spot.png`: `"岩国"` 検索時の最上段 `"岩国港"`（`bounds="[36,50][1044,236]"`）がステータスバー裏にクリップ。`"岩国"`（`鉄道駅`）と `"岩国駅"`（`駅・ターミナル`）が近接重複表示。スポット詳細 `"駅・港"` チップが暗い背景上に濃い青文字で低コントラスト。
  - **コード箇所**: `DestinationSearchDialog.kt:279`（フルスクリーン `Dialog` 内で `statusBarsPadding()` が効かない端末・設定条件あり）、`MapSheetContents.kt:92-99`（`Surface(color = catColor.copy(alpha = 0.16f))` 上に `Text(color = catColor)` を直置きしているため、駅カテゴリ等の暗い `catColor` でダークテーマ時の文字コントラスト比が著しく低下）。
- **推奨修正方針**: `DestinationSearchDialog` に `DialogProperties(decorFitsSystemWindows = true)` または確実なトップインセットを適用し、`MapSheetContents.kt:92-99` でダークテーマ時に明度を補正した `catColor` または高コントラスト色を使う（重複POIはデータパイプライン側 `PIPE-R1-06`/`16` で解消）。

#### [DEV-R3-11] Medium / P2: オフラインデータ画面 Tab 1 の検索/フィルタ欠如とサマリー文言の不一致
- **優先度根拠**: Tab 2（旧ラスタータイル）には県名検索と地方フィルタがあるのに、主役の Tab 1（47県ベクター/ルート/検索DB管理）にはなく47県スクロールが必要。さらに `地形`・`高速探索` チップがあるのに文言が旧来のまま。
- **検証済み根拠**: `06_offline_pill.png`, `06_offline_pill.xml` vs `07_offline_tiles_tab.png`、`PrefectureListDialog.kt:165-177, 494-496, 509-512`（`status.isComplete` で固定文言 `"ルート・検索・地図が入っています"` を表示）。
- **推奨修正方針**: Tab 1 にも検索窓・地方フィルタチップを共通配置し、サマリー文言を実際の保有コンポーネント（`地形`・`高速探索` を含む）に合わせて更新する。

#### [DEV-R3-12] Low / P3: シンプルモード主要ボタンのアクセシビリティラベル欠落（`NAF="true"`）
- **優先度根拠**: スクリーンリーダー（TalkBack）やUI自動テストでボタン目的が読み上げられない。
- **検証済み根拠**: `01_initial_launch.xml`（シンプルモードのメニュー `[36,139][180,283]`・拡大 `[900,1170][1044,1314]`・現在地 `[900,1344][1044,1488]` の3つのFABが `NAF="true"` かつ空 `content-desc`）。呼び出し側の `MapContentLayout.kt:312, 349, 360` はそれぞれ `contentDescription`（`"メニュー"`, `"地図を広げる"`, `"現在地へ"`）を渡しているが、`ui/map/MapControls.kt:159-182` の `FloatingCircleButton` が引数 `contentDescription` を `.semantics { this.contentDescription = contentDescription }` 等に適用せず破棄している（内部 `Icon` も `contentDescription = null`）。なお、`55_spot_tab_after_conv.xml` のお気に入り `☆` ボタン（`[525,1900][669,2044]`）は `QuickSpotSheet.kt:499` により内部 `Icon` に `contentDescription` が設定されており、XML上も子ノードに `content-desc="お気に入り追加"` が出力されている。
- **推奨修正方針**: `ui/map/MapControls.kt:159-182` の `FloatingCircleButton` 側で `Surface` の `Modifier` に `.semantics { this.contentDescription = contentDescription }` を適用し、呼び出し側から渡されたラベルが TalkBack / UI Automator に反映されるようにする。

#### [DEV-R3-13] Low / P3: 開発者ベンチマーク `"A*"` 誤ラベル・表記ゆれ・軽微なログ／パフォーマンス雑音
- **優先度根拠**: 開発者・ユーザー体験の細部品質および起動/アニメーション効率の改善点。
- **検証済み根拠**:
  - **ベンチマーク誤ラベル**: `48_dev_benchmark.png`, `48_benchmark_logcat.txt` で `expanded=0`（CH実行結果）が `"A*"` と表示される（`MapHelpers.kt:251` `appendLine("algorithm=A*")`, `MapRoutingActions.kt:165`）。
  - **お気に入り空画面の文言とアイコン不一致**: `19_saved_spots.png` の本文は `"★アイコン"`（`SpotPanelSheet.kt:153`）、下部ナビバーはブックマークリボンアイコン（`ic_google_bookmark`）。
  - **気温フォーマット不統一**: シンプル盤面 `"9° 雨20%"`（`HomeDashboard.kt:159`）vs 通常画面 `"9℃ 20%"`。
  - **天気ピル無反応（オフライン時）**: `08_weather_tap.png`（`HomeDashboard.kt:150` `clickable(onClick = onRefreshWeather)` が機内モード時に無反応でトースト等を出さない）。
  - **起動/描画ログ雑音**: `04_head_logcat.txt` でコールド起動時36フレームスキップ、および `Mbgl` の sprite / Noto Sans glyph `65024-65279` 読込エラー、`24_nav_logcat.txt:25-130` で1秒未満に `TILE_CENTER` が約105回出力、`38_cross_pref_logcat.txt` で `yamaguchi.graph` が1経路探索中に3回mmap（`APP-R3-08 参照`）。
- **推奨修正方針**: ベンチマーク出力に実使用エンジン（`CH` / `A*`）を反映、文言とアイコンの統一、オフライン時天気タップへの通知追加、不要なログ出力・重複グラフオープン削減を実施。

---

### 2.2 Android コード解析によるレイアウト・アクセシビリティ・デッドUI・状態管理の懸念点

実機で発生した現象に加え、`:app` コード静的監査で判明した潜在UI/UX懸念点を以下に示す。

#### [APP-R3-01] High / P1: ネストスクロールおよび横画面／小画面でのシート・ダイアログ縦クリップ
- **優先度根拠**: コンテンツが増えた際や横画面時にボタン・確認操作がスクロール不能領域に押し出されて押せなくなる重大操作障害。
- **検証済み根拠**:
  - `ui/cycling/SpotPanelSheet.kt:85, 160, 192` および `ui/cycling/RideHistoryView.kt:146, 280`: `Column.verticalScroll(...)` 内に `LazyColumn(heightIn(max=320/340.dp))` を入れ子にしている（スクロール競合・外枠との違和感）。
  - `MapBottomOverlays.kt:187-318` & `ui/cycling/RouteInfoPanels.kt:309-382`: ルートプレビューシートに `verticalScroll` や `maxHeight` 制約がなく、経由地＋ルート候補＋標高プロファイル表示時に横画面で溢れる。
  - `RideDetailDialog`（`RideHistoryView.kt:494-618`）および `AreaDownloadConfirmDialog` / `PrefectureDownloadConfirmDialog`（`MapDialogs.kt:55, 183`）: `AlertDialog` 本体に `verticalScroll` がなく、`RideDetailDialog` は4つのボタンを `confirmButton`/`dismissButton` に詰め込んでいる。
- **推奨修正方針**: シート全体の単一 `LazyColumn` 化、ルートプレビューシートへの最大高＋スクロール付与、確認ダイアログ本体の `verticalScroll` 化とアクション整理を行う。

#### [APP-R3-02] High / P1: ルートプレビュー下部ボタン列の窮屈な固定レイアウトと大フォント時の文字切れ
- **優先度根拠**: 狭幅端末やシステム文字サイズ拡大時に `"サイコン"` や `"ナビ開始"` が折り返し禁止（`softWrap = false`）で途中で切れる・隣と重なる。
- **検証済み根拠**: `ui/cycling/RouteInfoPanels.kt:309-382`（`MapBottomOverlays.kt:293` から呼ばれる `RouteInfoCardContent` 内の固定 `height(52.dp)` の `Row` に、48dp丸ボタン×2＋`height(48.dp)` の `"サイコン"`＋`"ナビ開始"`（ともに `softWrap = false`）を詰め込んでいる）。
- **推奨修正方針**: `FlowRow` 化またはアイコンパディング・フォント自動縮小・最小幅配分の見直しを行う。

#### [APP-R3-03] Medium / P2: 48dp 未満のタップ領域とスクリーンリーダー補助ラベル欠落の多発
- **優先度根拠**: 自転車乗車中のグローブ着用時や振動下での誤タップ・タップ不発に直結する。
- **検証済み根拠**:
  - `DestinationSearchDialog.kt:309, 621, 655`（閉じる `24.dp`、戻る・クリア `40.dp`）
  - `MapHelpers.kt:351`（`PoiDetailCard` 閉じるボタン `32.dp`）
  - `MapSheetContents.kt:137`（閉じる `Surface` `36.dp`）および `255-268`（`PlaceInfoList` の `tel:`/`http:` 行高 約`36.dp`）
  - `RouteInfoPanels.kt:102`（クリアボタン `36.dp`）
  - `NavigationHudViews.kt:359`（横画面ナビ `"終了"` ボタン高 `36.dp`）
  - `NavigationScreen.kt:211-226`（`GoogleNavFooterView` のボタン最小サイズ48dp未確保、かつ `contentDescription` に状態文字列 `"ミュート中"/"音声ON"` を誤用）
  - `ui/cycling/WaypointList.kt:77-94`（`"↑"`, `"↓"`, `"×"` のテキストのみで `contentDescription` なし）
- **推奨修正方針**: 全インタラクティブ要素に `48.dp` 以上のタッチターゲット（`defaultMinSize` / 外側ヒットボックス）とアクションベースの `contentDescription` を付与する。

#### [APP-R3-04] Medium / P2: 未使用・重複UIコンポーネント群の残存（デッドコード）
- **優先度根拠**: 既に現行画面から外れた旧UIコンポーネントが多数残り、修正漏れ（例: 新パネルは直したが旧クラスに同様の問題が残る）や開発者の判断ミスを生んでいる。
- **検証済み根拠**: `application/app/src/main/java` 全体検索で実行時呼び出し元が0件（または `if (false)` 無効化）であることを確認済み:
  - `GoogleNavHeaderView`（`NavigationScreen.kt:36, 76`）および `GoogleNavFooterView`（`NavigationScreen.kt:55, 164`）
  - `GoogleNavHeaderV2` & `GoogleNavFooterV2`（`NavigationHudViews.kt:42, 190` — 未使用の `NavigationScreen.kt` からのみ参照）
  - `ui/cycling/GoogleNavFooter`（`GoogleNavFooter.kt:54`）
  - `MapTopBar`（`MapTopBar.kt:61`）
  - `PlaceDetailSheet` & `RouteCandidateCard`（`MapSheets.kt:189, 293`）
  - `RouteOptionCard`（`MapSheetContents.kt:349`）
  - `VectorMapThemeHelper.applyNightModeColors`（`VectorMapThemeHelper.kt:21`）
  - `MapControls`（`MapControls.kt:38` — `MapControlsOverlay.kt:178` の `if (false)` 内のみ）
  - `onHomeClick` / `onAvatarClick`（`MapTopOverlays.kt:71-72`）
- **推奨修正方針**: ユニットテストが参照しているものを整理・移行した上でデッドUI群を削除する。

#### [APP-R3-05] Medium / P2: `GoogleNavFooterView` の総距離表示バグおよび候補カード/サマリー距離乖離
- **優先度根拠**: 横画面ナビ開始直後（`navStats == null`）に残り距離ではなく全行程距離が表示され、また複数ルート候補間で異なる距離計算基準により表示値が食い違う。
- **検証済み根拠**: `MapBottomOverlays.kt:171-172`（`remainingDistanceMeters = routeSummary.distanceMeters` を無条件設定）、`MapRouteCalculation.kt:420` vs `RealRoutePlanner.kt:121`、`RouteInfoPanels.kt:172-173`。
- **推奨修正方針**: 進行状況 `navigationProgress` を考慮した残距離計算へ直し、距離算出基準を一元化する。

#### [APP-R3-06] Medium / P2: ロケール依存 `String.format` と日本語ハードコード文字列・オフラインZIP取り込み時の強制終了
- **優先度根拠**: 端末言語設定（アラビア語・欧州ロケール等）によって小数点や数字記号が崩れる可能性があり、またオフラインZIP取り込み後の再起動が `Runtime.getRuntime().exit(0)` の強制終了になっている。
- **検証済み根拠**:
  - `NavigationPanelC.kt:603, 612, 616`, `NavigationScreen.kt:236, 255`, `NavigationHudViews.kt:243, 348` が `Locale` 指定なしで `String.format` を使用。
  - `PrefectureListDialog.kt:114, 540-545`: `Dispatchers.IO` ブロック内から `importMessage` を更新し、`restartApp` が `exit(0)` を呼ぶ。
- **推奨修正方針**: `Locale.US` / `Locale.JAPAN` を明示し、文字リソースの `strings.xml` 化と、インポート後のアプリ内ホットリロード（再起動不要化）を進める。

#### [APP-R3-07] Low / P3: 設定シートの自動閉じ挙動不統一・`LocationSummarySheet` 初期 `"23°C"`・検索GPS未取得時フォールバック
- **優先度根拠**: 設定画面の一貫性欠如と、県外オフライン閲覧時の検索体験低下。
- **検証済み根拠**: `SettingsPanelSheet.kt:319`（`DisplayMode` 変更時のみ自動閉じ）、`LocationSummarySheet.kt:74, 93`（既定 `"23°C"`）、`DestinationSearchDialog.kt:125, 143`（現在地 `null` 時に地図中心ではなく `findFirstAvailableDb` を使う）。
- **推奨修正方針**: 設定トグルの自動閉じ仕様を統一し、検索原点フォールバックに地図カメラ中心座標を使用する。

#### [APP-R3-08] Low / P3: ルーティング／案内ロジック由来のUI違和感（道路ルール無効、三重オープン、汎用エラーメッセージ、分岐案内癖）
- **優先度根拠**: コア層（`CORE-R1-03` および `:routing-core` / `:osm-importer` 静的解析）の制約がそのままUI上の分かりにくさ・待ち時間・汎用エラー表示として表出している。
- **検証済み根拠**:
  - **道路ルールバッジ・車線案内**: `OsmImporter` が `CYCLEMAP_BIKEINFO_V1` を書かないためUIの警告が出ず、`LANE_INCREASE`/`LANE_DECREASE`（`Instruction.kt:53-54`, `TurnClassifier.kt:64`）は未実装、`RoadNames.kt:28-35` で双方向道路の `:forward`/`:backward` なし `turn:lanes` が `null` になる。
  - **県またぎ時の待ち時間・メモリスパイク**: `MultiLegRoutePlanner.kt:162-165, 220-226` が外側県グラフを最大3回オープンし（実機 `38_cross_pref_logcat.txt` で `yamaguchi.graph` 3回mmap確認）、`LazyMappedRoadGraph.load()`（`LazyMappedRouting.kt:960-961`）が `readRichSections = false` でも北海道級で約73MBの座標配列を確保、`CrossChainRouting.kt` / `CrossChainChRouting.kt` が `System.gc()` で一時停止を起こす。
  - **汎用エラー**: `SnapFallbackRouting.kt:40-41`（400m上限）、`SpatialGridIndex.findWithin()`（`LazyMappedRouting.kt:1274` 4096セル超で黙って空返却）および `MapRouteCalculation.kt:447-455` が「道路から遠い」「非連結」「進入禁止」を単一エラー文に丸める。
  - **案内指示の癖**: `Instruction.kt:125` / `TurnClassifier.kt:434-540` が4ノード以下のミニラウンドアバウトを検出せず、連続分岐 `mergeConsecutiveForks()` が最初の左右方向を `CONSECUTIVE_FORK` で上書きし、`NavigationHelper.kt:129-130` (`outgoingRoadNameAt`) が3エッジ先まで見て後の道路名を拾う場合がある。
  - **ドキュメントの乖離**: `application/docs/specs/PROJECT_LOGIC.md`, `application/docs/plans/cautions.md`, `application/docs/reports/FINAL_REPORT.md`, `application/docs/plans/parity_checklist.md` に統合前の `re_test/` パスや削除済み `HsaRouting.kt`/`AltRouting.kt` の記述が残存。
- **推奨修正方針**: 県またぎ時のグラフハンドルのキャッシュ共有（1回オープン）、詳細エラー種別のUI出し分け、案内ロジックの改善と `application/docs/` の整理を行う。

---

### 2.3 データパイプライン・地図スタイル由来のUI懸念点

#### [DATA-R3-01] High / P1: `patch_map_style.py` の除外リスト不足による地図上の二重POIアイコン・文字重複
- **優先度根拠**: ベースベクタータイル側の `pois` レイヤーと、アプリ側の動的ピン（`PoiCategory` v3）が同じ駅・公園・病院・スーパー等に二重でアイコンとラベルを描画し、地図が非常に煩雑になる。
- **検証済み根拠**: `scripts/patch_map_style.py:35` (`POI_KINDS_COVERED_BY_PINS`) が7種類しか除外しておらず、`application/app/src/main/assets/styles/protomaps_light.json` の `pois` フィルタに `park`, `peak`, `zoo`, `station`, `ferry_terminal`, `school`, `supermarket`, `attraction`, `museum` 等16種以上が残ったまま `CyclingData.kt:424-480` の動的ピンと競合している。
- **推奨修正方針**: `POI_KINDS_COVERED_BY_PINS` を `PoiCategory` v3 全種別と同期して再パッチする。

#### [DATA-R3-02] High / P1: 検索DBの47%–63%を占める `'named'` 行による検索ノイズとピン混雑
- **優先度根拠**: `"国道"` や `"山陽"` と検索したり周辺スポットを探すと、`国道2号`（537件）や `JR山陽本線`（833件）が `"スポット"`（`SearchHelper.kt:111`）として並び、真の目的地を押し流す。
- **検証済み根拠**: 8県サンプリング実測値（`'named'` 比率47.4%〜63.1%）、広島DBでの `ダイドードリンコ` 1,472件・`JR山陽本線` 833件・`国道2号` 537件、および18,913組の近接重複ペア（`PIPE-R1-06`）。
- **推奨修正方針**: `PIPE-R1-06` の通り道路/鉄道セグメント除外と自販機/ATMカテゴリの正規マッピングを行う。

#### [DATA-R3-03] High / P1: 市街地交差点での道路標識（`roads_shields`）の重なり・他ラベル隠蔽
- **優先度根拠**: 複数国道の重複区間や市街地ジャンクションで道路番号バッジ同士が重なり、駅名や主要道路ラベルを覆い隠す。
- **検証済み根拠**: `scripts/patch_map_style.py:256-257`: `raise_shields` が `icon-allow-overlap: True` と `text-allow-overlap: True` を設定し、`roads_shields` レイヤーを `pois` と `roads_labels_major` の上位に持ち上げている。
- **推奨修正方針**: `allow-overlap` を `False`（または適切な `symbol-sort-key` / `icon-padding`）に戻し、過剰な重複表示を抑止する。

#### [DATA-R3-04] Medium / P2: 「道の駅」専用カテゴリバッジの喪失＆Overture POI連絡先情報の空欄化
- **優先度根拠**: サイクリストにとって最重要拠点である「道の駅」が単なる「観光名所」に埋もれ、また飲食店・店舗の詳細画面で電話番号やWebサイトが空欄のまま表示される。
- **検証済み根拠**:
  - `SearchHelper.kt:64` は `tourism:road_station -> "道の駅"` を持つにもかかわらず、`overture.py:100, 135-136` が `"roadside_station"` および `"道の駅"` を `"tourism:attraction"`（`"観光名所"`）へ潰している。
  - サンプル8県の `.search.db` で `ov_ph = 0`（`PIPE-R1-04`）。
- **推奨修正方針**: `"tourism:road_station"` への正しいマッピングと Overture 連絡先再エクスポートを実施する。

#### [DATA-R3-05] Medium / P2: 地形タイルの欠損ノイズ（同心円等高線・不自然な崖陰影）と勾配計算の無言無効化
- **優先度根拠**: `403` キャッシュ（`PIPE-R1-02`）や内陸DEM欠損値 `0m` 扱い（`PIPE-R1-19`）が、地図上の不自然な等高線リング・真っ黒な崖影として描画される。
- **検証済み根拠**: `scripts/build_terrain.py:147, 185, 195` および `build_prefectures.py:598-599`。
- **推奨修正方針**: 欠損タイル／欠損画素の適切処理と、`work/terrain/dem14` 不在時のビルド警告化。

---

### 2.4 Data Studio / Streamlit ツールのUI懸念点

#### [TOOL-R3-01] High / P1: `sandbox/data-tool/tools/gui.py` の0バイトDBクラッシュ・Overture取込画面欠如・巨大県でのフリーズ
- **優先度根拠**: 現在リポジトリのまま起動すると即クラッシュし、50万行超の東京データを開くと無制限クエリと自動全探索でGUIが応答不能になる。
- **検証済み根拠**:
  - `gui.py:34-44`: 0バイト `master.db` でクラッシュ（`PIPE-R1-05`）。`gui.py:84`: Overture Maps 取込タブがなく `"CSV (第4段階)"` が無効表示のまま。
  - `gui.py:66-79`: 47県×2のDB開閉を毎リラン実行。
  - `gui.py:166-177`: `masterdb.query_pois` に SQL `LIMIT` なしで全行メモリ読込後に `[:200]` スライス。
  - `gui.py:235`: `show_merge()` が画面表示・ウィジェット操作のたびに自動で重い `find_candidates()` を全実行（しかもCLIの `100.0m` に対し既定 `50.0m`）。
- **推奨修正方針**: 初期化チェック、SQL `LIMIT`/`OFFSET` ページネーション、重複候補検索の明示ボタン実行化、Overture取込UI追加を行う。

#### [TOOL-R3-02] Medium / P2: `tools/gui.py` の編集フォーム項目不足と `data_studio.py` の誤導バッジ・事前チェック欠如
- **優先度根拠**: GUIでのPOI修正が名前のみ（座標・カテゴリ・住所・かな編集不可）かつ数値 `uid` 手入力で使いづらく、また Data Studio が「古い `.graph.ch`」や「0バイト `.pmtiles`」にも緑の `✓` を出してビルド失敗を誘発する。
- **検証済み根拠**: `gui.py:26, 84, 195-210`（タイトル `"(第3段階)"` 残存、編集項目が `name` のみ）、`scripts/studio_plan.py:113-123`（`existing()` がファイル存在有無しか見ない）、`scripts/data_studio.py:248-255`（ポート8502確認なし）。
- **推奨修正方針**: 編集フォーム拡張とテーブル選択連携、`existing()` の指紋／最低サイズ検証、`search.db` 不在時のビルド前ブロックを導入する。

---

## 第3章: 今後追加すべき機能（R2）

### 3.1 アプリ機能・サイクリング体験

| ID | 優先度 | 機能タイトル | 根拠（なぜこの優先度か） | 現行コード上のギャップ・証跡 | 実装方向性 |
|---|---|---|---|---|---|
| **APP-R2-01** | **High / P1** | 完全オフラインパッケージ管理＆SHA-256検証 | 現状は全47県（26.2GB）を `adb` 手動転送する必要があり一般ユーザー導入の最大障壁。またZIP改ざん/破損検証がない。 | `OfflineDataImporter.kt:16-17` にハッシュ検証未実装と明記。`PrefectureListDialog.kt` は旧ラスタータイルのみダウンロード可能。 | `data_manifest.json`（`PIPE-R2-07`）と連携し、アプリ内から県単位の `.pmtiles`/`.graph`/`.ch`/`.search.db`/地形一式をSHA-256検証付きで取得・更新可能にする。 |
| **APP-R2-02** | **High / P1** | 勾配考慮ETAと車種・脚力プロファイル | 現在のETAが平坦15km/h固定換算のため、峠越えやE-Bike等で到着予想時刻が大きく外れる。 | `MapScreen.kt:730`（`4.16 m/s`）、`MapBottomOverlays.kt:144, 286`（`PRE_START_ASSUMED_SPEED_MPS`）、`RealRoutePlanner.kt:103-109`（`roadType="secondary"`, `gradePercent=null` 固定）。 | `ElevationRepository` / グラフ勾配（`segmentGradesAlongIndices()`）＋設定画面の車種（ロード/クロス/グラベル/E-Bike）基準速度を組み合わせた区間別ETAを算出。 |
| **APP-R2-03** | **High / P1** | 「自宅」「職場」の登録・管理＆お気に入り/履歴バックアップ | サイクリストが日常で最も多用する「帰宅ルート」ボタンが山口市役所直書きで死んでおり、端末移行時のデータ保全もない。 | `MapScreenScaffold.kt:206-212`（`onHomeClick` が `FALLBACK_LATITUDE/LONGITUDE` 直書き）、`MapTopOverlays.kt:71-72`（UI未接続）。`FavoritesManager` / `SearchHistoryManager` に書き出し機能なし。 | 「自宅」「職場」スロットを `FavoritesManager` / 設定に追加し、`HomeDashboard` からワンタップ帰宅ルート計算＋お気に入り/履歴のJSON/GPXインポート・エクスポートを実装。 |
| **APP-R2-04** | **High / P1** | インポートGPXの道路名・交通ルール警告・全県標高補完 | 他サービスで作ったGPXルートを取り込んでも道路名・車線案内・交通ルール警告が出ず、県を跨ぐと標高プロファイルが欠ける。 | `MapScreen.kt:105-116`（`importRouteLauncher` が `navigationInstructionsState = emptyList()` 設定）、`realElevProfileState` が単県 `mappedGraphState.value` のみ参照。 | GPX点列の簡易マップマッチング（または全県標高参照）により道路名・警告・標高断面を自動補完する。 |
| **APP-R2-05** | **Medium / P2** | `FLAT`/`SHORTEST` 好みと県跨ぎルートへの代替ルート対応 | 現状は「おすすめ」単県のみ複数候補が出るため、平坦優先時や長距離県跨ぎライドで別ルートを比較できない。 | `MapRouteCalculation.kt:540, 550` が県跨ぎおよび `RoutePreference` 反映の複数候補生成を抑止。 | `buildOptionsFromPrimary` への `RoutePreference` 反映と、ポータルペナルティを用いた県跨ぎ代替1ルートの生成。 |
| **APP-R2-06** | **Medium / P2** | 全導入県横断検索＋「現在営業中」/「地図中心基準」フィルタ | 隣接県リスト（`PREFECTURE_ADJACENCY`）外の検索ができず、営業時間がDBにあるのに「今開いている店」で絞れない。 | `MultiPrefectureSearch.kt:18` が静的隣接表のみ参照、`DestinationSearchDialog.kt:125, 143` がGPSなし時 `findFirstAvailableDb` 使用、`OpeningHoursParser` 存在するがUIフィルタなし。 | 導入済み全 `.search.db` の距離順横断検索、GPSなし時の地図中心フォールバック、`OpeningHoursParser` 連動の「営業中のみ」チップを追加。 |
| **APP-R2-07** | **Medium / P2** | 計画ルートGPXエクスポート共有改善＆サイコン自動一時停止 | 計算したルートをサイクルコンピュータへ渡す導線の拡充と、信号待ち中の平均速度低下を防ぐ自動一時停止。 | `exportPlannedRoute`（`MapScreen.kt:931`）は存在するがUI上目立ちにくく共有インテント非対応。`RideSession` の自動一時停止/セグメント分割が不十分。 | プレビュー画面でのGPX共有（`FileProvider` + Share Sheet）と、速度閾値による自動一時停止（`auto-pause`）設定の追加。 |
| **APP-R2-08** | **Medium / P2** | ベクター主軸のタイルDL体験整理（旧ラスターDLの廃止/隔離） | ベクター `.pmtiles` 利用者に旧GSIラスターDLボタンが見えて混乱する（`APP-R1-08`）。 | `MapRoutingActions.kt:101`・`Prefectures.kt:272-276`・`MapBottomOverlays` がベクター中も旧回廊DLを露出。 | ラスター選択時のみ旧タイルDLを表示し、ベクター時はローカル `.pmtiles`/`.contours`/`.hillshade` の整備状況を表示。 |
| **APP-R2-09** | **Medium / P2** | 強制終了後のルート復元確認UX改善 | バックグラウンドキル後の復帰時に無言で再探索するのではなく、現在地取得まで安全に待機・再開選択させる。 | `MapState.kt:136-147` (`RouteSessionSnapshot`), `MapScreen.kt:855-883`。 | 復元確認バナー／キャンセル可能ダイアログと、`location`・`Service` 状態のリアクティブ統合。 |
| **APP-R2-10** | **Low / P3** | 多言語対応（`values-en`）および単位系（km/mi, ℃/℉）切替 | 国内オフライン特化の現時点では低優先度だが、訪日サイクリスト向け拡張時に必須。 | `res/values/strings.xml` が最小限でCompose内文字列が日本語直書き。単位設定なし。 | 文字列リソース抽出、`values-en` 追加、設定画面への単位系切替追加。 |

---

### 3.2 ルーティング・インポータ基盤

| ID | 優先度 | 機能タイトル | 根拠（なぜこの優先度か） | 現行コード上のギャップ・証跡 | 実装方向性 |
|---|---|---|---|---|---|
| **CORE-R2-01** | **High / P1** | 路面状態（`surface`/`tracktype`）と自転車道ネットワーク（NCR）優先 | ロードバイクで未舗装路・砂利道（`track`/`path`）に誘導される事故を防ぎ、公式サイクリングルートを優先できる。 | `OsmImporter.kt:316-354` が `surface`, `tracktype`, `smoothness`, `lit`, `route=bicycle` を破棄。`RoutePreference` に未舗装回避がない。 | `.graph.idx` 末尾セクションにエッジ属性ビットセットを格納し、「未舗装路回避」「サイクリングロード優先」コスト係数を追加。 |
| **CORE-R2-02** | **High / P1** | フェリー航路（`route=ferry`）＆短距離押し歩き（`bicycle=dismount`）特例 | 関門トンネル人道（`way 93475591`）やしまなみ/瀬戸内海の島嶼間フェリーが通行不能と判定され海を渡れない。 | `BicycleAccessFilter.isAllowed()` (`OsmImporter.kt:64-82`) が `route=ferry` と全 `bicycle=dismount` を一律除外（`docs/handoff_20261113.md:88`, `VERSIONS.md:24`）。 | フェリーに固定乗船コスト、明示/短距離 `dismount` 区間に徒歩速度相当の高ペナルティ＋UI「押し歩き区間あり」通知を付与して許可。 |
| **CORE-R2-03** | **High / P1** | `FLAT` 用 Contraction Hierarchies サイドカーと県跨ぎ代替ルート | 現在 `FLAT`（平坦）や `HILL_CLIMB` はCHが効かず、県跨ぎで4〜17秒超のA\*探索になる（実機・設計値）。 | `ContractionHierarchy.kt:55`, `LazyMappedRouting.kt:480-482` が `RECOMMENDED × BICYCLE` のみCH適用（`docs/ch_design.md:8`, `docs/cross_prefecture_design.md:14`）。 | `<name>.flat.graph.ch` の任意サイドカー対応と、ポータル経由コストペナルティによる県跨ぎ代替1本生成。 |
| **CORE-R2-04** | **Medium / P2** | OSM 右左折禁止（`type=restriction`）およびノードバリア（`barrier=*`）対応 | 市街地の右折禁止や自転車通行不可ゲートを貫通するルートを排除し実走精度を上げる。 | `TwoPassGraphBuilder` (`OsmImporter.kt:283-305`) が `Relation` と `Node` タグを未処理。 | 転回制限リレーション（`except=bicycle` 考慮）と通行不可バリアノードによるエッジ遮断/分割を実装。 |
| **CORE-R2-05** | **Medium / P2** | エッジ考慮の事前ETA精密計算 | 現行 `TravelTimeEstimator` 呼び出しが固定係数のみで坂道や交差点停止を反映していない。 | `RealRoutePlanner.kt:103-109, 153-159` が `roadType="secondary"/"tertiary"`, `gradePercent=null` を渡す。 | `segmentGradesAlongIndices()` と各エッジ `roadType`・信号/交差点密度から区間別所要時間を積算。 |
| **CORE-R2-06** | **Medium / P2** | 仮想ノード挿入によるエッジスナップ（オフノード発着改善） | `EndpointTrim` はスナップ後経路の折り返しのみ削るため、最寄りノードが裏通り側にあると不要な迂回が残る。 | `EndpointTrim.kt` はノードスナップ後の事後トリムのみ（`VERSIONS.md:24`）。 | 発着点を上位K本の近傍エッジへ投影し、両端ノードまでの距離をシードしてA\*/CHを起動する。 |
| **CORE-R2-07** | **Medium / P2** | `.graph.ch` ヘッダへの `BicycleCost` 定数ハッシュ埋め込み | `BicycleCost.kt` の係数調整後に古い `.ch` が残っているとエラーにならず旧コストのルートを返してしまう。 | `GraphFingerprint` (`ContractionHierarchy.kt:182-194`, `BicycleCost.kt:12`) が `.graph` バイト列のみハッシュ化。 | CHバージョンを `2 -> 3` へ上げ、`BicycleCost` 定数ハッシュをヘッダに埋め込み不一致時に自動A\*フォールバック。 |
| **CORE-R2-08** | **Medium / P2** | 複数経由地の最適巡回順並び替え＆目標距離周回（ループ）ルート生成 | ポタリング・周回トレーニング・スタンプラリー用途の付加価値向上。 | `MultiLegRoutePlanner.kt:95-236` は入力順固定で直列探索のみ。 | 経由地のTSP近似並び替え、およびCH/A\*放射ウェイポイントを用いた目標距離ループ生成。 |
| **CORE-R2-09** | **Low / P3** | 2 GiB 超グラフ対応（`Long` オフセット）＆ `DeadReckoner` の `RouteProgressIndex` 活用 | 現行の県別分割では2GiBに収まるが地方ブロック統合時の上限撤廃、および毎ティックO(N)積算の解消。 | `LazyMappedRouting.kt:919, 933`・`ContractionHierarchy.kt:575`（`Int` 制限）、`DeadReckoner.kt:86-100`（毎tick累積長計算）。 | `DeadReckoner` に既存の `RouteProgressIndex.pointAt()` を流用し、将来の大型統合グラフ用にチャンク `FileChannel` 読込を設計。 |

---

### 3.3 データパイプライン・運用基盤

| ID | 優先度 | 機能タイトル | 根拠（なぜこの優先度か） | 現行コード上のギャップ・証跡 | 実装方向性 |
|---|---|---|---|---|---|
| **PIPE-R2-01** | **Critical / P0** | ライセンス／出典生成の一元化とODbL・Apache 2.0・GSI DEM準拠 | 公開・配布を行う上での法的コンプライアンス（ODbL二次DB条項、Overture Foursquare NOTICE、国土地理院DEM出典）を確実に担保するため。 | `build_prefectures.py:72`, `manifest.json`, `config.KOKUDO_LICENSE_LINES`, Android `MapDialogs.kt` に重複分散し項目が欠落（`docs/poi_source_compatibility.md:64-67`）。 | パイプライン出力時に実使用ソース（`sources[].dataset`, DEM使用有無）から `LICENSE.txt` と `manifest.json` を一元生成する。 |
| **PIPE-R2-02** | **Critical / P0** | `operating_status`・連絡先・OSM `way` フィルタ反映の47県 `.search.db` 再生成 | 出荷済みDBの全県で Overture 電話番号が0件、かつ約半数が道路/鉄道 `'named'` ノイズであるため、アプリ検索体験を劇的に改善する即効性が高い。 | サンプル8県で `ov_ph = 0`、`'named'` 47.4%–63.1%、`st_idx = 0`（`PIPE-R1-04`, `06`, `18`）。 | `overture.py` と `osm.py` と `export.py`（`idx_places_search_text` 追加）を修正後、47県の `.search.db` を一括再エクスポート＆再パッケージ。 |
| **PIPE-R2-03** | **High / P1** | `data_studio.py` への `search_db` ステップ統合と実効妥当性チェック | 開発者が Data Studio 上だけで最初から最後まで安全に県データをビルドできるようにする。 | `studio_plan.py:27-34, 113-123` に `search_db` がなく、`existing()` は0バイト・指紋不整合を検知できない。 | `search_db` 生成ステップ追加と、CH指紋一致・PMTilesヘッダ・最低サイズ検証の導入。 |
| **PIPE-R2-04** | **High / P1** | クロスプラットフォーム Python `push_data.py` による `push_data.sh` 置換 | Windows環境（WSL衝突・`unzip` 不在・テスト15件失敗）を根治し、Mac/Linux/Windows で同一の高速デプロイを実現する。 | `scripts/tests/test_push_data.py` 15件全敗、`push_data.sh` の環境依存多発（`PIPE-R1-08`, `09`）。 | `scripts/_platform.py` と Python `zipfile` + `adb` を使った純Pythonデプロイツールを実装し、旧小文字ファイルの自動整理も行う。 |
| **PIPE-R2-05** | **Medium / P2** | 47都道府県対応 `generate_admin_boundaries.py`（リソース形式出力） | 広島県以外の46都道府県でもオフライン逆ジオコーディングで正確な市町村名を出せるようにする。 | `scripts/generate_admin_boundaries.py:42, 76, 77` が広島専用。 | `build_prefecture_polygons.py` 同様の圧縮バイナリ／県別アセット方式で47県分の行政界を出力。 |
| **PIPE-R2-06** | **Medium / P2** | サイクリスト特化POIカテゴリの拡充（道の駅・自販機・給水・峠・輪行拠点） | 実用サイクリング地図としての価値向上。既にアプリ側にUIアイコンや変換コードがあるものも多い。 | `CyclingData.kt:446`（自転車修理）、`SearchHelper.kt:64`（`tourism:road_station`="道の駅"）がある一方、DB側マッピングが不足（`overture.py:100,136` 等）。 | `tourism:road_station`、自販機（`amenity:vending_machine`）、給水（`drinking_water`/`water_tap`）、峠（`mountain_pass=yes`/`natural=saddle`）、休憩所（`highway=rest_area`）を抽出し検索DBへ収録。 |
| **PIPE-R2-07** | **Medium / P2** | 配信インデックス `packages/data_manifest.json` 生成基盤 | アプリ内ダウンロードマネージャ（`APP-R2-01`）の前提基盤。 | 現在 `packages/data_manifest.json` が未生成。 | 各県の `.zip`・`.graph.ch`・`.pmtiles`・地形・`.portals` のサイズ・バージョン・SHA-256・依存関係を集約出力するスクリプト整備。 |
| **PIPE-R2-08** | **Medium / P2** | 島嶼県マルチBBox分割（`build_tiles.py` & `overture.py`） | `tokyo.pmtiles`（326MB）等の過剰サイズとOverture S3課金/時間コストを大幅削減する。 | `scripts/build_tiles.py:68-87`, `config.py:41` の単一BBox矩形。 | 本土・離島ごとにサブBBox分割し、タイル抽出結合およびDuckDBマルチBBoxクエリ化。 |
| **PIPE-R2-09** | **Low / P3** | ルート回廊高ズームタイル生成（`build_corridor.py`）＆差分OSM更新・CI検証 | 全域z15-16ラスター（1GB超）を避ける設計方針（`AGENTS.md`）への対応と、2.5GB PBF全走査の高速化。 | `build_prefectures.py` のフルスキャン中心構成と、`build_prefectures`/`build_terrain` のテスト不足。 | 経路沿い回廊抽出ツール、`osmium apply-changes` 差分更新、合成フィクスチャを用いたCI回帰テスト追加。 |
| **PIPE-R2-10** | **Low / P3** | 県境バッファ（2〜5km）POIオーバーラップ索引オプション | 県境付近での最寄りコンビニ・駅検索が別県DB側にあって漏れる現象を防止（`APP-R2-06` 全県検索の補完）。 | `sandbox/data-tool/HANDOFF.md:78`, `docs/nationwide_design.md:17`。 | オプションで隣接2〜5km圏内の主要POIを近隣県DBへ重複収録するか、アプリ側全県検索で代替。 |

---

## 第4章: 実機操作による確認結果（R4 証跡記録）

### 4.1 テスト環境・APKビルド・オフラインデータ検証
- **検証端末**: Samsung Galaxy S26 (`samsung SC-51G`, ADB `RFGL626QR3N`, Android 17 / SDK 37, `1080x2340`, `480 dpi`)
- **検証ビルド**: 初期搭載版（`2a108c9-dirty`）を確認後、現行HEAD `52355e1` を `.\gradlew.bat :app:assembleDebug "-Pcyclemap.appIdSuffix=.win" --no-daemon`（JDK 21, **43sで成功**）にてビルドし、`adb -s RFGL626QR3N install -r` で上書き導入。アプリ内「バージョン情報」画面で `CycleMap α0.9.10-unified-win` / `コミット: 52355e1` / `ビルド日時: 2026-11-18 18:28` を目視確認（`05_head_version.png`）。
- **通信・オフラインデータ**:
  - 端末の `/sdcard/Android/data/com.gorite.cyclemap.test.win/files/Documents/CycleMap/` に **47都道府県・419エントリ（合計 26.2 GB）**＋ `tiles/cache.db`（`2.8M`）を確認。
  - フロー12以降（18:38〜18:53）は `adb shell settings get global airplane_mode_on == 1`（機内モードON）状態で全検索・描画・CH/A\*探索・県跨ぎポータル探索・ベンチマークを完全オフライン実施。

### 4.2 確認済み全20フローのテストマトリクス

| # | 機能・画面領域 | 実施手順 | 実測結果（期待との比較・観測値） | 参照証跡ファイル |
|---|---|---|---|---|
| 1 | コールド起動＆HomeDashboard（シンプルモード） | `MainActivity` 起動し地図・グラフ読込完了まで待機 | `"井口五丁目"`, `"9° 雨20%"`, `"広島 ・ オフラインで使えます"` を表示。`Hiroshima.graph`（`1859802` nodes, `3858862` edges）を `258ms` でmmap（`pssDeltaKb=140031`）、`CH_WARMUP` `110ms`、`Hiroshima.search.db exists=true`。一方で起動時36フレームスキップと `MapLibreGLSurfaceView` 2回生成を観測。 | `04_head_launch.png`, `04_head_logcat.txt`, `01_initial_launch.xml` |
| 2 | ドロワーメニュー＆バージョン情報 | 左ドロワーを開き `バージョン情報` を開く | 10項目を表示。最下項目 `"バージョン情報"`（`[0,2248][929,2340]`）がシステムジェスチャーバー（`[0,2295][1080,2340]`）背後にクリップ。ダイアログで `52355e1` 確認。 | `02_menu.png`, `02_menu.xml`, `05_head_version.png` |
| 3 | オフラインデータダイアログ | `"広島 ・ オフラインで使えます"` ピルを押下し両タブ確認 | Tab 1（`端末のデータ`）は47県一覧に検索・フィルタがなく、サマリー文言が `地形`/`高速探索` に言及していない。Tab 2（`補助の地図タイル (旧方式)`）には検索・地方チップあり。 | `06_offline_pill.png`, `06_offline_pill.xml`, `07_offline_tiles_tab.png` |
| 4 | 天気ピルタップ | HomeDashboard の `"9° 雨20%"` をタップ | ダイアログ・スナックバー・更新通知など視覚的反応なし。 | `08_weather_tap.png` |
| 5 | 拡大地図＆レイヤー切替（3D/地理院） | 拡大ボタン → レイヤー切替 → `地理院標準` → `3Dベクター` + `3D` | `3Dベクター` で3D建物表示が機能。`地理院標準` へ切り替えると想定外にズームアウトし、明色地図上で白ステータスバーアイコンが視認困難、地図隅にOSM/地理院帰属表示が出ない。 | `09_expand_btn.png`, `10_layer_switcher.png`, `11_gsi_standard.png`, `12_vector_3d.png` |
| 6 | エリアダウンロード範囲選択 | 拡大地図からエリア選択FAB → ドラッグ → キャンセル | 開始と同時に下部が `"盤面に戻る"` から `スポット/保存済み/記録` タブへ変化し、選択バナーがレイヤーFABと衝突、キャンセル時は拡大地図ではなく `HomeDashboard` へ戻った。 | `13_area_download.png`, `13_area_download.xml`, `14_area_drag.png`, `15_saved_tab.png` |
| 7 | 走行記録・履歴閲覧・削除 | `ルートなしで走る` → 停止 → `記録を見る` → 詳細 → 削除 | GPX記録が正常保存され `記録` タブ・詳細画面に反映、削除確認後に正常削除（`18b_after_delete.png`）。 | `16_record_tab.png`, `17_ride_history.png`, `18_ride_detail.png`, `18b_after_delete.png` |
| 8 | クイックスポット＆チップ押下タブ残留 | `コンビニ`/`トイレ` 押下、および `保存済み` 閉後に上部 `コンビニ` チップ押下 | 通常検索は成功（`ローソン` 等表示）。しかし `保存済み` で閉じた後に上部 `コンビニ` チップを押すと `保存済み`（`お気に入りスポット`）タブが開き、結果が裏タブに隠れる不具合を確認。 | `20_quick_convenience.png`, `21_quick_toilet.png`, `54_conv_opened.png`, `55_spot_tab_after_conv.png` |
| 9 | お気に入り（`☆` / `保存済み`） | `ローソン` の `☆` 押下 → `保存済み` 確認 → 解除 | `"「ローソン」を保存しました"` トースト表示、一覧反映、解除で空状態へ復帰。空状態本文は `★アイコン` だがタブはブックマークアイコン（`☆` アイコンの子ノードには `content-desc="お気に入り追加"` を確認）。 | `19_saved_spots.png`, `55_spot_tab_after_conv.xml`, `56_saved_added.png`, `56_saved_added.xml` |
| 10 | 単県ルーティング（CH & A\*） | `ローソン`（`東 290 m`）へ `おすすめ`(CH) と `平坦`(A\*) で探索 | `RECOMMENDED` は CH 実行：`searchMs=82 expanded=0 engine=ch chQueryMs=0` (`316m`)。`FLAT` は A\* 実行：`searchMs=23 expanded=17 engine=astar chQueryMs=-1` (`316m`)。 | `22_route_preview_lawson.png`, `22_route_logcat.txt`, `23_route_flat.png`, `24_nav_logcat.txt` |
| 11 | アクティブナビ・立ち寄り追加・終了 | ナビ開始 → コンビニ `経由地に追加` → コンパクト切替 → 終了 | `GUIDANCE_START` 後に立ち寄り追加で多区間A\*再探索（`leg=0` 22ms + `leg=1` 18ms, `1656m`）。ナビHUDで `速度 0` のみ上寄せ、`立ち寄り` FAB（高 `127px`）がパネル上端にクリップ。終了操作は正常復帰。 | `24_nav_active.png`, `24_nav_active.xml`, `25_nav_stopover.png`, `26_nav_with_stopover.png`, `28_nav_waypoint_added.png`, `28_waypoint_logcat.txt`, `29_nav_compact.png`, `30_after_end_nav.png` |
| 12 | オフライン多文字種検索＆スポット詳細 | 空検索、ローマ字 `iwakuni`、ひらがな `いわくに`、漢字 `岩国` → 詳細表示 | 機内モード下で山口県POI（`岩国`, `岩国港`, `岩国駅`）を検索成功、SQLite/FTSエラーゼロ。最上段カード（`岩国港` `[36,50][1044,236]`）がステータスバー裏にクリップ、近接重複表示、詳細チップ `"駅・港"` の暗背景低コントラストを確認。 | `31_search_initial.png`, `32_search_hiroshima_romaji.png`, `35_search_iwakuni.png`, `36_search_iwakuni_kanji.png`, `36_search_iwakuni_kanji.xml`, `37_iwakuni_spot.png` |
| 13 | 県跨ぎポータルルーティング | 機内モードで広島県（井口）→ 山口県（`岩国`）を探索 | オフラインで県跨ぎ探索成功：`cross loadMs=279 portalMs=177 aMs=2191 bMs=1143 totalMs=5189`, `via=広島県>山口県`, `routeDistanceM=32095` (`32.1km`), `routeNodeCount=1109`。ただし logcat 上で `yamaguchi.graph` が3回mmapされた。 | `38_cross_pref_route.png`, `38_cross_pref_route.xml`, `38_cross_pref_logcat.txt`, `39_cross_pref_done.png` |
| 14 | 設定画面＆ライト/ダークテーマ切替 | 設定画面をスクロールし、Dark → Light → Dark へ切替 | 全項目へアクセスでき、テーマ切替も正常反映。 | `40_settings_top.png`, `41_settings_display.png`, `41b_settings_light.png`, `42_settings_bottom.png` |
| 15 | ライセンス情報ダイアログ | ドロワー → `ライセンス情報` | OSM / Overture / GSI / MLIT 表記をスクロール可能に表示。 | `46_license.png`, `46_license.xml` |
| 16 | 開発者オプション＆ベンチマーク | 開発者オプション → ルーティングベンチマーク実行 | 完走・ログ保存（`0.064s / 0.7MB / 45.1km / expanded=0`）するが、CH結果（`expanded=0`）を `"A*"` と誤表記。テスト用 `.log` は確認後削除済み。 | `47_dev_options.png`, `48_dev_benchmark.png`, `48_benchmark_logcat.txt` |
| 17 | 3D地形ビュー | ドロワー → `3D地形ビュー` | `"3D Simulation"` 英語ラベルで静的サンプルジオラマ（`山と川・ログハウス`, `雪山ジオラマ`）を表示。下端ヒント（`[128,2263][952,2340]`）クリップ、上端 `y=0..115` に地図が透ける。 | `49_3d_terrain_view.png`, `49_3d_terrain_view.xml` |
| 18 | サイコンモード | `サイコンモード` に切替・放置後、通常モードへ復帰 | 屋内机上でGPSドリフトにより `走行距離 62 m`, `平均 3.1 km/h`, `最高 18.0 km/h` を積算。解除直後に地図が白紙ベージュになり、現在地再中心（`surfaceCreated`）まで描画されなかった。 | `43_cycle_computer_mode.png`, `44_after_recenter.png`, `50_cycle_computer.png`, `50_cycle_computer.xml` |
| 19 | 横画面（Landscape）表示 | `user_rotation 1` で横向き回転 | 横並びコントロール（`[981,306][2304,462]`）が `"GPS信号が途絶えました"` バナーとカテゴリチップ列に直接重なり、`"盤面に戻る"` CTAも画面外に消失。 | `51_landscape.png`, `51_landscape.xml` |
| 20 | バックグラウンド退避 → フォアグラウンド復帰 | `KEYCODE_HOME` で2秒待機後、`am start` で復帰 | 同一PID (`4346`) で復帰し、MapLibre `SurfaceView` も空白にならず `Hiroshima.search.db exists=true` を維持、クラッシュ/ANRゼロ。 | `52_resume_foreground.png`, `52_resume_foreground.xml`, `52_full_app_logcat.txt` |

### 4.3 主要 Logcat 観測結果
- **起動・MapLibreアセット (`04_head_logcat.txt`)**:
  - `157: I Choreographer: Skipped 36 frames! The application may be doing too much work on its main thread.`
  - `184-185: E Mbgl : {clemap.test.win}[Style]: Failed to load sprite: Could not read asset`
  - `278: E Mbgl : {RenderThread 98}[Style]: Failed to load glyph range 65024-65279 for font stack Noto Sans Regular: Could not read asset`
  - `MapLibreGLSurfaceView` インスタンス `182711773` が `18:28:58.959` に生成・`18:28:59.327` に破棄、`123382370` が `18:28:59.416` に再生成。
- **ルーティング計測 (`22_route_logcat.txt`, `24_nav_logcat.txt`, `38_cross_pref_logcat.txt`)**:
  - 単県 CH: `ROUTE_TIMING leg=0 searchMs=82 expanded=0 engine=ch chQueryMs=0`
  - 単県 A\*: `ROUTE_TIMING leg=0 searchMs=23 expanded=17 engine=astar chQueryMs=-1`
  - 県跨ぎ: `ROUTE_TIMING cross loadMs=279 portalMs=177 aMs=2191 bMs=1143 totalMs=5189`, `ROUTE_NOTICE kind=cross_route_used from=広島県 to=山口県 via=広島県>山口県`, `ROUTE_RESULT pref=FLAT ... routeNodeCount=1109 routeDistanceM=32095`（1回の探索中に `yamaguchi.graph` が `18:42:04.026`, `18:42:05.263`, `18:42:08.926` の計3回mmapされた）。
  - カメラアニメーション時の過剰ログ (`24_nav_logcat.txt:25-130`): 約0.85秒間に `CycleMapMap: TILE_CENTER` が約105回出力。
- **クラッシュ・エラー不在確認 (`52_full_app_logcat.txt` および `01`..`52` 全ログ)**:
  - 全セッションを通じ、未捕捉例外・FATAL・ANR・`Fts`/`SQLiteException` は **0件**。

### 4.4 証跡ディレクトリ・ファイル一覧と端末クリーンアップ状態
- **証跡保存先ディレクトリ**: `C:\Users\user\.gemini\antigravity\scratch\teamwork\415461bc-bbfb-482b-b199-102c80e586c7\.agents\teamwork\teamwork_worker_device\`
- **保存済み証跡ファイル一覧**（全106ファイル：スクリーンショット `.png` 47件、UI Automator XML `.xml` 37件、logcat 抽出 `.txt` 18件、調査作業状態 `.md` 4件）:
  - 起動・基本メニュー系: `01_initial_launch.{png,xml}`, `01_{app,launch}_logcat.txt`, `02_menu.{png,xml}`, `03_version_info.{png,xml}`, `04_head_{launch.png,logcat.txt}`, `05_head_version.png`, `06_offline_pill.{png,xml}`, `06b_refocus.xml`, `07_offline_tiles_tab.png`, `08_weather_tap.png`
  - 地図・レイヤー・エリアDL・記録系: `09_expand_btn.{png,xml}`, `10_layer_switcher.{png,xml}`, `11_gsi_standard.png`, `12_vector_3d.png`, `13_area_download.{png,xml}`, `14_area_drag.{png,xml}`, `15_saved_tab.png`, `16_record_tab.png`, `17_ride_history.{png,xml}`, `18_ride_detail.{png,xml}`, `18b_after_delete.{png,xml}`
  - スポット・お気に入り・単県ナビ系: `19_saved_spots.{png,xml}`, `20_quick_convenience.{png,xml}`, `21_quick_toilet.png`, `22_route_preview_lawson.{png,xml}`, `22_route_logcat.txt`, `23_route_flat.png`, `24_nav_active.{png,xml}`, `24_nav_logcat.txt`, `25_nav_stopover.{png,xml}`, `26_nav_with_stopover.png`, `26_stopover_logcat.txt`, `27_after_end_nav.png`, `28_nav_waypoint_added.png`, `28_waypoint_logcat.txt`, `29_nav_compact.png`, `30_after_end_nav.{png,xml}`
  - 検索・県跨ぎ系: `31_search_initial.{png,xml}`, `32_search_hiroshima_romaji.{png,xml}`, `32_search_logcat.txt`, `33_search_hiroshima.{png,xml}`, `33_search_logcat.txt`, `34_clean_home.xml`, `35_search_iwakuni.{png,xml}`, `35_search_logcat.txt`, `36_search_iwakuni_kanji.{png,xml}`, `37_iwakuni_spot.{png,xml}`, `38_cross_pref_route.{png,xml}`, `38_cross_pref_logcat.txt`, `39_cross_pref_done.png`, `39b_after_close_preview.xml`
  - 設定・開発者・3D・サイコン・横画面・復帰・タブ再現系: `40_settings_top.{png,xml}`, `41_settings_display.{png,xml}`, `41b_settings_light.png`, `42_settings_bottom.{png,xml}`, `43_cycle_computer_mode.{png,xml}`, `44_after_recenter.png`, `44_recenter_logcat.txt`, `45_cycle_computer_or_full.{png,xml}`, `46_license.{png,xml}`, `47_dev_options.png`, `48_dev_benchmark.png`, `48_benchmark_logcat.txt`, `49_3d_terrain_view.{png,xml}`, `49_3d_logcat.txt`, `49b_settings_check.xml`, `50_cycle_computer.{png,xml}`, `51_landscape.{png,xml}`, `52_resume_foreground.{png,xml}`, `52_{filtered_errors_and_tags,final_logcat_summary,full_app_logcat,key_logcat}.txt`, `53_convenience_list.{png,xml}`, `53b_before_conv.xml`, `54_conv_opened.{png,xml}`, `55_spot_tab_after_conv.{png,xml}`, `56_saved_added.{png,xml}`, `57_restored_home.png`
- **テスト後の端末クリーンアップ**: `accelerometer_rotation=1` 復元、テスト用GPX記録・お気に入り・ベンチマーク `.log` ファイルを削除し、アプリを `HomeDashboard` 初期状態へ戻したことを確認済み。

---

## 第5章: 次期開発・改修計画（優先度別ロードマップ）

以下の4フェーズ構成で、データ破壊・重大UX崩壊の即時遮断から基盤拡張・全国運用の完成へと段階的に進めることを推奨する。

### Phase 1: 即時対応（Critical / P0 — 次のフィールドテスト・配布前に必須）
1. **地図ライフサイクルとカメラ制御の安定化**
   - `APP-R1-01`: `MapViewsContainer.kt:254` から `tileKey` を外し `onVectorMapDestroyed` を追加（起動時二重生成・県境リセット解消）。
   - `APP-R1-02`: `VectorMapView.kt:564-566` の `moveCamera` による `animateCamera` 中断を防止。
2. **横画面＆主要ナビ衝突の解消**
   - `DEV-R3-01`: 横画面時の `LandscapeControlRow`・バナー・チップ列・`LandscapeNavHudBar` 重なり解消、重複 `"終了"` ボタン整理、横画面からの `"盤面に戻る"` 手段確保。
3. **データ破壊・法的リスクの遮断（パイプライン即時修正）**
   - `PIPE-R1-01`: `merge.py:207` の市区町村除去を撤廃し Pass 3 にカテゴリ互換＋500m距離上限を追加。
   - `PIPE-R1-02`: `build_terrain.py:146-149` の `403` 空ファイルキャッシュを廃止（既存の0バイトキャッシュ清掃手順も実施）。
   - `PIPE-R1-03`: `config.py:213-263` のポリゴン穴対応と最短ポリゴン距離フォールバック実装。
   - `PIPE-R1-04` + `PIPE-R2-01` + `PIPE-R2-02`: Overture `operating_status` フィルタ・`sources[].dataset` 追跡・OSM線状 `'named'` 除外・`idx_places_search_text` 追加・動的ライセンス生成を入れ、**47県 `.search.db` の一括再エクスポート**を実施（Phase 2のスタイルパッチと組み合わせると効果最大）。

### Phase 2: 短期（High / P1 — コア品質・ルーティング精度・UI整合・運用自動化）
1. **Android 状態同期と安全・信頼性修正**
   - `APP-R1-03`〜`APP-R1-08`: 到着時 `StopNavigation`、ミュート同期、代替ルートラスター更新、`startMarkerState` 引継ぎ、`destination` ロールバック、`RoutePreference` の代替候補反映、距離計算統一、GPX書き込み完了待ち `stopSelf()`、1Hz POI/住所デバウンス改善、`downloadTiles` の `try/finally`＋UIスレッド化。
2. **実機確認UI欠陥の解消**
   - `DEV-R3-02`〜`DEV-R3-05`: 上部バナー高さに応じたFAB動的配置、`onSelectCategory` 時の `保存済み` タブリセット、`isAreaSelectMode` と `mapExpanded` 状態分離、`地理院標準` の白ステータスバー対策＋地図隅帰属表示＋ズーム維持。
   - `APP-R3-01`〜`APP-R3-02`: ネストスクロール解消、確認ダイアログ・プレビューのスクロール確保、下部ボタン折り返し改善。
3. **自転車ルーティング・インポータの中核修正**
   - `CORE-R1-01`〜`CORE-R1-03`: `OsmImporter` での `junction=roundabout` / `oneway:bicycle=no` 解釈追加、`BicycleCost.kt` への `cycleway`/`trunk`/`*_link`/`living_street`/`footway` 専用係数追加、`CYCLEMAP_BIKEINFO_V1` / `.bikerules` の標準ビルド組込み（＋全県 `.graph.ch` 再ビルド）。
   - `CORE-R2-01`〜`CORE-R2-03`: 路面/自転車道属性、フェリー・短距離押し歩き特例、`FLAT` 用 `.flat.graph.ch` と県跨ぎ代替ルート。
4. **パイプライン／ツール／スタイルの整備**
   - `DATA-R3-01`〜`03`: `patch_map_style.py` の `PoiCategory` v3 完全同期と `roads_shields` 重なり抑止。
   - `PIPE-R1-05`〜`13` & `TOOL-R3-01` & `PIPE-R2-03`〜`04`: `gui.py` 0バイトクラッシュ修正・SQL `LIMIT` 化、純Python `push_data.py` 導入（`test_push_data.py` 15件失敗の恒久解消＋旧ファイル整理）、`refresh_data.py` `--within` バグ修正、`data_studio.py` への `search_db` 統合、島嶼部分割（`tokyo.pmtiles` 削減）、全47県行政界生成（`PIPE-R2-05`）。
   - `APP-R2-01`〜`04`: パッケージDL＆SHA-256検証UI、勾配考慮ETA＆車種設定、自宅/職場登録、GPXインポート補完。

### Phase 3: 中期（Medium / P2 — 詳細ポリッシュ・堅牢化・機能拡張）
1. **実機・コード解析の中優先度バグ解消**
   - `DEV-R3-06`〜`11`: サイコン静止ドリフト抑制＋退出時白紙対策、3D地形ビュー余白・表現整理、ナビHUD数値整列＋`立ち寄り` FAB配置、ドロワー `navigationBarsPadding` 修正、検索トップクリップ＋詳細チップコントラスト改善、オフラインデータTab 1検索/フィルタ追加。
   - `APP-R1-09`〜`14` & `APP-R3-03`〜`06`: `WeatherRepository.kt:242` `coerceIn`、センチネル `cityHint` 除外、検索スキーマキャッシュ無効化、構造化コルーチン化、500msポーリング廃止、TTS整理＆音声窓改善、48dpタップ領域確保、デッドUI削除、ロケール指定 `String.format`、強制終了 `exit(0)` 廃止。
   - `APP-R3-08`（県跨ぎ3回mmap解消、詳細エラーメッセージ、案内分岐・ミニラウンドアバウト改善、`application/docs/` 整理）。
2. **コア＆パイプラインの堅牢化・機能拡張**
   - `CORE-R1-04`〜`10`: `RoadRuleMatcher` 並走抑制改善、`EndpointTrim` 距離/経由地修正、`TurnClassifier` IDベース分岐特定、`SearchIndexWriter` 同期、`OsmImporter` バッファ書込み・バリデーション強化、`LazyMappedRoadGraph` 境界検査。
   - `CORE-R2-04`〜`08`: 右左折禁止/バリア対応、エッジスナップ仮想ノード、CHコスト定数ハッシュ埋め込み（v3）、経由地最適化＆ループ生成。
   - `PIPE-R1-14`〜`23` & `PIPE-R2-06`〜`08`: `build_prefectures.py` / `export.py` / `overlay.py` / `validate.py` / `build_ch.py` の各種エッジケース修正、サイクリストPOI拡充、`data_manifest.json` 配信インデックス構築。
   - `APP-R2-05`〜`09`: 全県検索＆営業中/地図中心フィルタ、計画GPX共有強化・自動一時停止、ベクター主軸タイルDL整理、復元確認UX。

### Phase 4: 長期（Low / P3 — 配布最適化・長期保守）
- `APP-R1-15`: リリースビルド設定（`applicationId` 分離・R8有効化・`allowBackup` 除外ルール）。
- `DEV-R3-12`〜`13`, `APP-R3-07`: アクセシビリティラベル網羅、表記統一、起動フレーム最適化・`Mbgl` アセット警告解消・`TILE_CENTER` ログ削減。
- `APP-R2-10`: 多言語（`values-en`）・単位系切替。
- `CORE-R1-11`〜`12`, `CORE-R2-09`: Gradle 9対応（`providers.exec`, `jvmToolchain(11)`）、2GiB超チャンク設計、`DeadReckoner` 最適化。
- `PIPE-R1-24`〜`30`, `PIPE-R2-09`〜`10`: 補助スクリプト群統一、回廊タイル自動抽出、OSM差分更新・CI合成フィクスチャ検証、県境POIオーバーラップ。

---

*本レポートは `teamwork_explorer_app`、`teamwork_explorer_routing`、`teamwork_explorer_pipeline`、`teamwork_worker_device` の4系統調査結果および対象ソースファイルの直接照合に基づき作成した（2026-11-18）。*
