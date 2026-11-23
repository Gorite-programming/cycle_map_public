# バッテリー過剰消費 調査結果と修正案 (2026-11-22)

`application/app` および `application/routing-core` の実装を調査し、バッテリー・発熱の過剰消費につながり得る箇所（10 項目）と、その具体的な修正案・設計上の注意点・段階的導入計画をまとめた。

## 全体サマリー

| ID | 影響度 | 主な消費リソース | 主な対象ファイル | 修正の要点 | 推奨フェーズ |
|---|---|---|---|---|---|
| #1 | 極大 | GPU / CPU / Flash I/O | `VectorMapView.kt`, `NavigationHelper.kt`, `MapScreen.kt`, `MapCameraCenter.kt` | 案内中追従の FPS 上限化（20〜30fps）と `OnCameraIdleListener` 連鎖抑止 | Phase 1 |
| #2 | 極大 | センサー / CPU / GPU / I/O | `NavigationHelper.kt`, `MapTrackingEffects.kt`, `MapScreen.kt`, `MapScreenScaffold.kt`, `MapViewsContainer.kt`, `VectorMapView.kt` | コンパス有効条件の限定、高頻度 State 購読の局所化、未メモ化 I/O 排除 | Phase 1 |
| #3 | 極大 | Flash I/O (`fsync`) / CPU | `GpxRecorder.kt`, `GpxJournal.kt`, `LocationTrackingService.kt`, `LocationDiagLog.kt` | GPX ジャーナルの非同期バッチ書き込み、診断ログの条件限定、監視タイマー最適化 | Phase 1 |
| #4 | 極大 | GNSS モデム / GPU / CPU | `LocationTrackingService.kt`, `MapLifecycleEffects.kt`, `GpsHealthMonitor.kt` | 状態別 `LocationRequest` 動的切替、停止時ラスター再描画抑止、`START_NOT_STICKY` | Phase 2 |
| #5 | 高 | セルラー / Wi-Fi モデム | `WeatherRepository.kt`, `JmaWeatherClient.kt`, `MapScreen.kt` | 気象庁 API 失敗時の指数バックオフ・重複抑止、`currentArea == null` 時のデバウンス | Phase 2 |
| #6 | 高 | CPU / mmap I/O | `LazyMappedRouting.kt`, `RoadRuleMatcher.kt`, `MapScreen.kt` | `nearestNodeCandidates` での `SpatialGrid.findWithin` 直接利用と非同期化 | Phase 1 |
| #7 | 高 | Display (高リフレッシュ固定) / GPU | `NextFlowAnimation.kt`, `NavigationHudViews.kt`, `MapTopOverlays.kt`, `Isometric3dSimulationView.kt` | 無限アニメーション廃止または非ループ化・`graphicsLayer` 化・省電力連動 | Phase 3 |
| #8 | 高 | Display (常時点灯) | `KeepScreenOnEffect.kt`, `CycleComputerHost.kt`, `MapContentLayout.kt` | `RideState.IDLE` 時消灯、長時間停車・単体 GPX 記録時の常時点灯見直し | Phase 2 |
| #9 | 中 | CPU / SQLite / センサー | `MapLifecycleEffects.kt`, `CycleComputerHost.kt`, `MapScreen.kt`, `DeveloperSensors.kt`, `MainActivity.kt` | バックグラウンド（非 `RESUMED`）時のループ・DB 検索・センサー自動停止 | Phase 2 |
| #10 | 中 | GPU / CPU / センサー / GNSS | `BatteryMonitor.kt`, `VectorMapView.kt`, `NavigationPanelC.kt` | `isLowPower` の全サブシステム連動と `ACTION_BATTERY_CHANGED` 受信の一本化 | Phase 3 |

---

## 詳細調査と修正案

### #1 ナビ案内中の無制限 60〜120fps カメラ追従と `OnCameraIdleListener` 連鎖発火（影響度：極大）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/ui/mapview/VectorMapView.kt`: L148-L155, L218-L226, L302-L341, L455-L477, L537-L574
- `application/app/src/main/java/com/gorite/cyclemap/navigation/NavigationHelper.kt`: L540-L632（`MapOrientationAnimator`）
- `application/app/src/main/java/com/gorite/cyclemap/ui/screens/MapScreen.kt`: L616-L624
- `application/app/src/main/java/com/gorite/cyclemap/ui/mapview/MapCameraCenter.kt`: L18-L20
- `application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapLifecycleEffects.kt`: L314

#### 原因と消費メカニズム
1. `VectorMapView` の案内中ループ（`while (isActive) { withFrameNanos { ... } }`）が `LocationGlide` の補間位置を使って、`updateLocationPoint()`（GeoJSON 再構築＋`setGeoJson`）と `map.moveCamera()` を**フレームレート上限なし**（120Hz 端末なら最大 120fps）で実行し続ける。`MapView.setMaximumFps()` も未設定。
2. `MapOrientationAnimator` も別系統の `Choreographer.FrameCallback` で毎フレーム `vMap.cameraPosition = ...`（`moveCamera` 相当）を実行する。
3. MapLibre の `moveCamera()` は即時遷移のため、1 フレームごとに `OnCameraMoveListener` と **`OnCameraIdleListener` の両方が発火**する。その結果、毎秒 60〜120 回以下の連鎖が発生する：
   - `MapScreen.kt` L616-L624 の `OnCameraIdleListener` が毎フレーム `prefs.edit().putString("view_lat", ...).putString("view_lon", ...).apply()` を呼び、`cyclemap_prefs.xml` のシリアライズ・ディスク書込＋`fsync` を連打する。
   - `VectorMapView.kt` L303 が `MapCameraCenter.update()` を呼び、`VectorMapView.kt` L457-L476 が `Dispatchers.Main` 上で毎フレーム `resolveOfflinePmtilesPath()`（行政界ポリゴンの ray-casting 判定 `findPrefectureName` ＋ `getExternalFilesDir` ＋ `mkdirs()` ＋ `File.isFile`/`length()`）を実行する。同時に `MapLifecycleEffects.kt` L314 側も再コンポーズを受ける。
   - 案内中は `VectorMapView.kt` L219-L226 の `triggerFetchPois()` が早期 return し `lastPoiFetchCenter` が更新されないため、20m 以上移動後は毎フレーム `shouldSkipPoiRefetch == false` となり、空の `FeatureCollection` を毎フレーム `setGeoJson` し続ける。
   - `OnCameraMoveListener`（L318-L323）が `currentVectorTiltState` / `currentZoomLevelState` / `currentVectorBearingState` を毎フレーム更新し、画面全体の再コンポーズ（#2）を増幅する。

#### 具体的な修正案
1. **描画・更新 FPS の上限設定と間引き**:
   - `VectorMapView` の `MapView` 初期化時または状態連動で `mapView.setMaximumFps(...)` を設定する（例：通常 30fps、低電力時 15fps、またはユーザー操作中は 60fps・案内追従中は 20〜30fps）。
   - `VectorMapView.kt` L537-L574 の `withFrameNanos` ループおよび `MapOrientationAnimator` の `onFrame` に最小フレーム間隔（通常 33〜50ms ≒ 20〜30fps、省電力時は補間自体を停止し 1Hz 更新のみ）を導入する。
2. **プログラム追従による `OnCameraIdleListener` 連鎖の遮断**:
   - プログラムによるカメラ追従（`moveCamera` / `MapOrientationAnimator`）発火前後を示すガードフラグ（または `isNavigationActive && currentFollowLocation` 判定）を設け、追従中は以下を抑止する：
     - `MapScreen.kt` L616-L624 の `SharedPreferences` 保存（`onDispose` と「追従解除中の手動操作停止後（例：2 秒以上デバウンス）」のみ保存に変更。または少なくとも数秒間隔＋座標変化閾値で間引く）。
     - `VectorMapView.kt` L303 の `MapCameraCenter.update()`（県境タイル切替は `VectorMapView.kt` L444-L451 の `locationPrefecture` 側で維持される）。
3. **案内中 POI クリアの 1 回化**:
   - `VectorMapView.kt` L302-L315 の `OnCameraIdleListener` では `!currentPoiVisible || currentNavigationActive` のときに `triggerFetchPois(map)` を呼ばない。POI の消去は既存の `LaunchedEffect(poiVisible, isNavigationActive, poiSelected, triggerPoisRef)`（L663-L665）に集約する。
4. **`MapCameraCenter` からのタイル解決の最適化**:
   - `VectorMapView.kt` L455-L476 の `resolveOfflinePmtilesPath()` は `Dispatchers.IO` 上で実行し、前回チェック座標から一定距離（例：200m 以上）動いた場合のみ再判定する。

#### 注意点・副作用対策
- `CameraAnimationGate`（`CameraAnimationGate.kt`）による `animateCameraExclusive` 保護（ナビ開始時ズームや現在地ボタン復帰が追従 `moveCamera` で打ち切られない仕組み）を壊さないこと。
- 手動スクロール時（`followLocation == false`）の `MapCameraCenter` 経由の地名表示更新・他県タイル切替は維持すること。

#### 検証方法
- `adb shell dumpsys gfxinfo <package> framestats` および `adb logcat` で、ナビ案内中（走行・停車）のフレーム描画頻度・`OnCameraIdleListener` 発火時の `SharedPreferences` 書込回数（StrictMode の `DiskWriteViolation` が発生しないこと）を確認する。

---

### #2 コンパスセンサー常時稼働と Compose 最上位での高頻度 State 読み取り＋未メモ化 I/O（影響度：極大）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapTrackingEffects.kt`: L414-L426
- `application/app/src/main/java/com/gorite/cyclemap/navigation/NavigationHelper.kt`: L431-L527（`CompassUpdates`）
- `application/app/src/main/java/com/gorite/cyclemap/ui/map/MapState.kt`: L73-L74
- `application/app/src/main/java/com/gorite/cyclemap/ui/screens/MapScreen.kt`: L755-L774
- `application/app/src/main/java/com/gorite/cyclemap/ui/layout/MapScreenScaffold.kt`: L166-L171, L203-L209
- `application/app/src/main/java/com/gorite/cyclemap/ui/layout/MapViewsContainer.kt`: L229-L263
- `application/app/src/main/java/com/gorite/cyclemap/ui/mapview/VectorMapView.kt`: L407-L414

#### 原因と消費メカニズム
1. `CompassUpdates` が `appLifecycleState == AppLifecycleState.RESUMED` の間、**`TYPE_ROTATION_VECTOR`（加速度＋ジャイロ＋地磁気フュージョン）を `SENSOR_DELAY_UI`（約 15〜20Hz）で常時登録**している。サイコンモード中（地図非表示）・検索／設定画面表示中・5km/h 以上の走行中（地図回転は GPS 方位を使用）・ノースアップ（北固定）時も停止しない。また `onSensorChanged` ごとに `FloatArray(9)` × 2、`FloatArray(3)`、`getSystemService(WindowManager)` を呼び出している。
2. `MapState.kt` L73-L74 のコメントでは「MapScreen 全体を再コンポーズさせない」方針とされているが、実際には `MapScreen` / `MapScreenScaffold` が `headingDegreesState.floatValue`・`currentLocationState.value`・`currentZoomLevelState.doubleValue`・`currentVectorTiltState.doubleValue`・`currentVectorBearingState.doubleValue` を直接値で読み出して子 Composable へ渡している。このためコンパス（15〜20Hz）・GPS（1Hz）・カメラ移動（最大 120Hz）のたびに**画面全体が再コンポーズ**される。
3. `MapViewsContainer.kt` L229-L242 の `TileSourceSelector.select(tileLat, tileLon)` および `tileSignature()`（`cycleMapDataDir(context)` + `File.isFile`/`length()`/`lastModified()`）が `remember` なしで Compose 本体に書かれているため、再コンポーズのたびにメインスレッドで**県ポリゴン判定 2 回＋外部ストレージ `stat()`** が走る。さらに `MapViewsContainer.kt` L263 の `navigationRoute.map { GeoPoint(...) }` や `MapScreenScaffold.kt` L167-L168 の `alternativeRoutes` 変換も毎回リストを再割り当てしている。
4. 非案内中の `VectorMapView.kt` L407-L414 でも `bearing` が変わるたびに `updateLocationPoint()` → `setGeoJson()` が呼ばれるため、静止待機中でも 15〜20fps で MapLibre の GPU 再描画が回り続ける。

#### 具体的な修正案
1. **コンパスセンサーの稼働条件とサンプリングレートの最適化**:
   - `CompassUpdates` の `enabled` 条件を「`appLifecycleState == RESUMED` かつ 地図が表示されている（`displayMode != CYCLE_COMPUTER`、検索／設定等の全画面モーダル非表示）かつ 低速/静止時（`speedKmh < SPEED_HEADING_THRESHOLD_KMH` などのヒステリシス付き）」に限定する。
   - **ノースアップ時の扱い**:
     - **案 A（推奨）**: ノースアップ時（`!isHeadingUp`）はコンパスを停止し、走行中は GPS 方位、停止時はドット表示にする。
     - **案 B（前方ビームを維持したい場合）**: ノースアップ時は `SensorManager.SENSOR_DELAY_NORMAL`（約 5Hz）＋ `maxReportLatencyUs` バッチング＋デッドバンドを `2.0〜3.0°` に引き上げ、`isLowPowerMode` 時は停止する。
   - `CompassUpdates` 内の `rotationMatrix` / `remappedMatrix` / `orientation` 配列および `WindowManager` 参照をコールバック外に 1 回だけ確保して再利用する。
2. **高頻度 State の直接読み取り排除（Recomposition スコープ局所化）**:
   - `headingDegrees` / `currentVectorBearing` / `currentVectorTilt` / `currentZoomLevel` を `MapScreenScaffold` / `MapContentLayout` で値渡しせず、`() -> Float` / `() -> Double` などの安定ラムダ（または `State<T>` / `MapViewModel.locationState`）として渡し、実際に値を表示・利用する末端 Composable（コンパスボタン等）の中だけで読み取る。
3. **未メモ化 I/O・座標リスト変換のキャッシュ**:
   - `MapViewsContainer.kt` L229-L242 の `TileSourceSelector.select(...)` は `remember(tileLat, tileLon)` または `lat/lon` が大きく動いた時のみ再計算し、`tileSignature()` の計算とファイルアクセスは `DisposableEffect` 内から `Dispatchers.IO` へ逃がす（Compose 実行中の同期ファイル `stat()` を根絶する）。
   - `MapViewsContainer.kt` L263 の `navigationRoute.map { GeoPoint(...) }` と `MapScreenScaffold.kt` L167-L168 の `alternativeRoutes` 変換を `remember(navigationRoute)` / `remember(options, selectedIdx, isNavigationActive)` でメモ化する。
4. **非案内中の前方ビーム GeoJSON 更新のデバウンス**:
   - `VectorMapView.kt` L407-L414 の `updateLocationPoint` は、位置・モード・GPS 状態が変わっていない場合に `bearing` の変化が閾値（例：2.5°）未満または前回更新から一定時間（例：120ms）未満ならスキップする。

#### 注意点・副作用対策
- 高速走行と低速の境界（5km/h 付近）でセンサー登録／解除が頻繁に繰り返されないよう、ヒステリシス（例：6km/h 超で解除、3km/h 未満で再登録、または数秒遅延）を入れる。

#### 検証方法
- Android Studio Layout Inspector（Recomposition counts）または一時的な Compose ログで、静止待機中に `MapScreen` / `MapViewsContainer` が毎秒・毎センサーイベントで再コンポーズされないことを確認する。
- `adb shell dumpsys sensorservice` でサイコン画面中・バックグラウンド中・高速移動中に `ROTATION_VECTOR` リスナーが解除されていることを確認する。

---

### #3 GPX 記録中の毎秒 `RandomAccessFile` 開閉＋`fd.sync()`（`fsync`）連打（影響度：極大）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/tracking/GpxRecorder.kt`: L29-L37, L40-L53, L72-L79
- `application/app/src/main/java/com/gorite/cyclemap/tracking/GpxJournal.kt`: L11-L30
- `application/app/src/main/java/com/gorite/cyclemap/tracking/LocationTrackingService.kt`: L69-L80, L352-L366, L426
- `application/app/src/main/java/com/gorite/cyclemap/tracking/LocationDiagLog.kt`: L40-L43

#### 原因と消費メカニズム
1. `LocationTrackingService` はメインスレッド（`Looper.getMainLooper()`）で測位コールバックを受け取り、GPX 記録中は毎回 `recorder?.addPoint(newLocation)` を呼ぶ。
2. `GpxRecorder.addPoint()` は 1 点追加するごとに即座に `flush()` → `GpxJournal.append()` を実行する。
3. `GpxJournal.append()` は 1 行（数十バイト）を書くたびに **`RandomAccessFile(file, "rw")` を open → seek → read → write → `output.fd.sync()` (`fsync`) → close** を行う。1 時間の記録で約 3,600 回の `fsync` が発生し、UFS/eMMC ストレージの省電力移行を妨げるうえ、メインスレッドをブロックしてジャンクの原因にもなる。
4. `LocationTrackingService.healthCheckRunnable`（L69-L80）はバックグラウンド記録中も 1 秒おきにメインスレッドを起動し、`LocationDiagLog`（L352-L366）もリリースビルドを含め全測位を CSV へ書き込み 10 行ごとに flush している。

#### 具体的な修正案
1. **ジャーナルの非同期バッチフラッシュ化**:
   - `GpxRecorder.addPoint()` ごとの即時 `flush()` を廃止し、メモリ上の `pending` に溜める。
   - 一定件数（例：10〜15 点）または一定時間（例：15〜30 秒）ごとに、`saveExecutor`（単一 I/O スレッド）上へスナップショットを渡して非同期で `journal.append()` + `fd.sync()` を実行する。
   - `pause()`・`resume()`・`stopRecording()`・`onDestroy()` では従来通り全未書込分を確実に `flush()` してデータ保全を保証する。
   - （補足）テスト互換性を保つため、`GpxRecorder` のデフォルト挙動またはコンストラクタ引数でテスト用の即時フラッシュ／バッチ閾値を切り替えられるようにすると既存ユニットテストが壊れにくい。
2. **`LocationDiagLog` の条件付き有効化**:
   - 常時 CSV 出力を `BuildConfig.DEBUG` または開発者オプションの「位置診断ログを記録」トグルが ON のときのみに限定する（またはバッファサイズ拡大・終了時のみフラッシュ）。
3. **`healthCheckRunnable` の 1 秒ポーリング廃止**:
   - 毎秒 `postDelayed(this, 1_000L)` で起床する代わりに、`onValidFix()` / `onAccuracyDegraded()` / `reset()` 時に次回状態遷移（`weakTimeoutMs = 5s`、`lostTimeoutMs = 15s`）までの残余時間で単発の `postDelayed` を予約する方式にする。

#### 注意点・副作用対策
- ジャーナルのバッチ間隔を長くしすぎると OS 強制終了時に直近数点（例：15〜30 秒分）を失う可能性があるため、最大でも 15〜30 秒・10〜15 点程度に抑え、アプリがバックグラウンドへ移るタイミングや一時停止時は即時フラッシュする。

#### 検証方法
- `GpxRecorder` / `GpxJournal` のユニットテスト（`:app:testDebugUnitTest`）で、追記・クラッシュ復旧 (`restore()`)・`pause`/`resume`/`stop` 時のデータ欠損がないことを検証する。
- StrictMode（`detectDiskWrites`）および実機で、GPX 記録中にメインスレッドのファイル I/O 違反が出ないこと、`fsync` 回数が 1/10〜1/30 に削減されることを確認する。

---

### #4 GPS 高頻度固定リクエスト・停止中の無意味な再配信／ラスター再描画・`START_STICKY`（影響度：極大）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/tracking/LocationTrackingService.kt`: L105-L125, L177-L182, L323-L336
- `application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapLifecycleEffects.kt`: L71-L129
- `application/app/src/main/java/com/gorite/cyclemap/tracking/GpsHealthMonitor.kt`: L18-L59
- `application/app/src/main/java/com/gorite/cyclemap/tracking/LocationQualityGate.kt`: L110

#### 原因と消費メカニズム
1. `LocationTrackingService.startTracking()` が常に `Priority.PRIORITY_HIGH_ACCURACY`・`interval = 1_000L`・`minUpdateIntervalMillis = 250L`（最大 4Hz）・`maxUpdateDelayMillis = 1_000L`・最小移動距離 0m で固定されており、待ち受け閲覧・長時間停止中・バックグラウンド GPX 記録のみ・低バッテリー時でも GNSS ハードウェアをフル稼働させ続ける。
2. 停止判定（`GateVerdict.STATIONARY`）時も `holdPositionWhileStationary()` が毎回新しい `Location(previous)` インスタンスを生成してリスナーへ流すため、Compose の `currentLocationState` が毎秒変化とみなされる。特に**ラスター地図（GSI/OSM/TERRAIN）選択時**は、`MapLifecycleEffects.kt` L104-L128 に `start == target`（移動なし）判定がないため、**完全静止中でも毎秒 900ms の 30fps `withFrameNanos` アニメーションが無限に連続実行され、`view.invalidate()` を毎秒約 27 回呼び続ける**。
3. `onStartCommand` が `START_STICKY` を返し、`intent == null` で `else -> ensureTracking()` に落ちる。記録一時停止（`recoveryPending`）やアプリ強制終了後の OS 再起動時、UI クライアントがバインドされていない状態で高精度 1Hz 位置追跡がバックグラウンドで復活・維持され続ける恐れがある。

#### 具体的な修正案
1. **状態別 `LocationRequest` プロファイルによる動的更新 (`updateLocationRequest`)**:
   - 利用状況に応じて `fusedClient.requestLocationUpdates(...)` を再設定する：
     - **ナビ案内中（走行中）**: `PRIORITY_HIGH_ACCURACY`, interval `1_000〜2_000ms`, minUpdate `500ms`, maxDelay `1_000ms`
     - **GPX 記録のみ（バックグラウンド等）**: `PRIORITY_HIGH_ACCURACY`, interval `2_000〜3_000ms`, minUpdate `1_000ms`, maxDelay `4_000〜6_000ms`（位置チップ／FLPB バッチングで CPU 起床削減）, `minUpdateDistanceMeters = 2〜3f`
     - **待ち受け地図閲覧（非ナビ・非記録）**: `PRIORITY_HIGH_ACCURACY`（または `PRIORITY_BALANCED_POWER_ACCURACY` 選択可）, interval `3_000〜5_000ms`, minUpdate `2_000ms`, `minUpdateDistanceMeters = 3〜5f`
     - **長時間停止検出後（例：30 秒以上 `STATIONARY` 継続）または `isLowPower` 時**: interval `5_000〜10_000ms`, `minUpdateDistanceMeters = 5f`
2. **静止保持時・ラスター地図の無意味な再描画抑止**:
   - `MapLifecycleEffects.kt` L95-L105 で、`start == target`（または 2 点間距離が 0.1m 未満）の場合は 900ms の `withFrameNanos` ループを開始せず即 return する。
   - `holdPositionWhileStationary()` で前回の通知から既に停止状態（速度 0 かつ座標同一・`HEALTHY`）であれば、毎回下流の地図・ViewModel へ再通知しない（または UI 側で座標変化・精度変化・ステータス変化のみ反映する）。
3. **サービス再起動ポリシーの見直し**:
   - `onStartCommand()` で `intent == null`（OS による `START_STICKY` 再起動）かつ「`recording` 状態でもアクティブなナビ中でもない」場合は `stopSelf()` して即終了する（または `START_NOT_STICKY` に統一し、必要に応じて `START_REDELIVER_INTENT` や明示的な復元条件でのみ再開する）。

#### 注意点・副作用対策
- **重要**: 静止時や低電力時に GPS 間隔を 5 秒以上に緩める場合、`GpsHealthMonitor`（現在 `weakTimeoutMs = 5_000L` / `lostTimeoutMs = 15_000L`）および `LocationQualityGate`（`MAX_JUMP_WINDOW_SECONDS = 10.0`）のタイムアウト判定が「間隔が伸びただけなのに GPS 途絶（WEAK/LOST）」と誤検知しないよう、プロファイルに応じて閾値（例：`weakTimeoutMs = max(5_000L, currentIntervalMs * 2 + 1_000L)`）を動的に調整するか、バッチ受信時刻・静止判定を考慮する。

#### 検証方法
- `GpsHealthMonitor` / `LocationQualityGate` のユニットテストで、長間隔プロファイル時に誤って `WEAK`/`LOST` にならないことを検証する。
- ラスター地図＋室内静止状態で、`MapLifecycleEffects` の `withFrameNanos` ループが回らないこと（CPU/GPU 使用率がほぼ 0% になること）を確認する。

---

### #5 通信失敗時の気象庁 API 毎秒リトライ暴走（影響度：高）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/ui/screens/MapScreen.kt`: L806-L811
- `application/app/src/main/java/com/gorite/cyclemap/weather/WeatherRepository.kt`: L73-L149
- `application/app/src/main/java/com/gorite/cyclemap/weather/JmaWeatherClient.kt`: L17-L61

#### 原因と消費メカニズム
1. `MapScreen` L806-L811 が `state.currentLocationState.value` の変化（毎秒）ごとに `weatherRepo.updateLocation(...)` を呼ぶ。
2. `WeatherRepository.updateLocation()` の位置デバウンス（1000m 未満移動の早期 return）は、`currentArea != null` かつ（`Fresh` 有効期限内 または オフラインかつ `Stale`）のときしか成立しない。
3. そのため、オンライン判定（`isOnline == true`）かつ通信エラー・タイムアウト（接続 10 秒＋読取 15 秒）・JMA 障害等で `_uiState` が `Error` や `Stale` になると、**次の 1 秒後の GPS 更新でも早期 return を素通りし、リトライ制限やバックオフなしに延々と HTTP 接続を積み上げ続ける**。結果、セルラー／Wi-Fi モデムが常時高電力状態になる。また `currentArea == null` の場合も毎回 `Dispatchers.IO` で `findPrefectureName` を再実行する。

#### 具体的な修正案
1. **失敗時の指数バックオフ・クールダウン導入**:
   - `WeatherRepository` に `consecutiveFailures`、`lastAttemptElapsedMs`、`isFetching` を保持し、`updateLocation()` からの自動取得では失敗回数に応じた待機時間（例：60 秒 → 3 分 → 10 分 → 最大 30 分）が経過するまで `fetchForecast()` を呼ばない。
   - `fetchMutex` にリクエストを待機列で積まないよう、`inFlight` 中（`fetchMutex.isLocked` 等）の自動更新はスキップする。
   - `refresh(isOnline)`（ユーザー手動更新）およびネットワークが `Offline -> Online` に復帰したタイミングではバックオフをリセットして即時取得を許可する。
2. **`currentArea == null` 時の位置デバウンス**:
   - 位置距離（1000m 未満）と `cityHint`/`isOnline` が変化していない場合、`currentArea == null` でも短時間内の再解決をスキップする。

#### 注意点・副作用対策
- ユーザーが天気チップをタップして手動リフレッシュ（`onRefreshWeather`）した場合は、バックオフ中でも即座に再取得できるようにする。

#### 検証方法
- `WeatherRepository` のユニットテスト（モック `JmaWeatherClient` で失敗を返した状態で `updateLocation` を 10 回連続呼び出し、`fetchForecastJson` が 1 回しか呼ばれないこと）を追加・実行する。

---

### #6 県またぎナビ中／グラフ未導入県での毎秒メインスレッド全ノード線形走査（影響度：高）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/ui/screens/MapScreen.kt`: L683-L708
- `application/routing-core/src/main/kotlin/com/gorite/cyclemap/routing/RoadRuleMatcher.kt`: L19-L29
- `application/routing-core/src/main/kotlin/com/gorite/cyclemap/routing/LazyMappedRouting.kt`: L328-L390, L1264-L1363
- `application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapGraphEffects.kt`: L201-L202

#### 原因と消費メカニズム
1. `MapScreen.kt` L683-L708 が走行中（`speed >= 1.5 m/s`）、毎秒メインスレッドで `graph.roadCandidatesNear(loc.latitude, loc.longitude)` を実行する（`roadRuleEnabledState` は既定 `true`）。
2. `LazyMappedRoadGraph.nearestNodeCandidates(..., radiusMeters = 150.0)`（L343-L367）は、`spatialGrid.findWithin()` を直接呼ばず、まず半径無制限の `nearestNodeIndex()` を呼ぶ。
3. **県またぎナビ中**（`MapGraphEffects` がナビ中のグラフ切替を禁止しているため、出発県のグラフのまま隣県を走行する）や**グラフ未導入県での走行時**は、`SpatialGrid.findNearest()` が 16 リング外側または bbox 上限で `-1` を返し、そのまま **`nearestNodeIndexLinear()` が mmap グラフ全ノード（数十万〜百万ノード）を毎秒 2 パス全走査**したあとで、`> 150.0` 判定により結果を空で捨てる。画面オフのバックグラウンド走行中でも毎秒実行される。

#### 具体的な修正案
1. **`nearestNodeCandidates` で `SpatialGrid.findWithin` を直接利用**:
   - `LazyMappedRoadGraph.nearestNodeCandidates()`（L343-L367）において `spatialGrid != null` の場合は `nearestNodeIndex()` を先に呼ばず、直接 `grid.findWithin(latitude, longitude, radiusMeters, maxCount, latitudes, longitudes)` を呼ぶ（`findWithin` は半径 `radiusMeters` 以内のセルのみを走査し距離昇順で返すため、範囲外であれば即座に空配列を返し、全ノード線形走査を完全に回避できる。範囲内にノードがある場合も先頭要素は `nearestNodeIndex` と一致する）。
   - `grid.findWithin` 内の `(maxGy - minGy + 1) * (maxGx - minGx + 1) > 4096` ガードにより、極端に遠方・異常入力でも安全に空を返す。
2. **道路ルール照合のバックグラウンドスレッド化・非表示時停止**:
   - `MapScreen.kt` L683-L708 の `roadCandidatesNear` + `ExcludedRoadIndex.candidatesNear` + `roadRuleMatcher.update` を `Dispatchers.Default` 上（`Channel.CONFLATED` 等で最新 1 件のみ処理）へ移し、メインスレッドの計算・ロック取得を廃止する。
   - 画面が `RESUMED` でない場合（バッジ表示が見えないバックグラウンド中）は照合をスキップする（音声通知と連動させる場合のみ残す設計も可だが、現行は UI バッジ `roadRuleKindState` のみ）。

#### 注意点・副作用対策
- `nearestNodeCandidates` 変更時は既存の `LazyMappedRoutingTest` / `SnapFallbackRoutingTest` / `RoadRuleMatcherTest` がすべて通ることを確認し、単体テストに「グラフ範囲外座標で `nearestNodeIndexLinear` を呼ばず即空配列を返すこと」のテストを追加する。

#### 検証方法
- `:routing-core:test` で県外座標に対する `nearestNodeCandidates` の高速終了と、範囲内座標の既存結果一致を確認する。

---

### #7 横画面ナビ中の常時 120fps 無限アニメーション（`NextFlowAnimation` / `Isometric3dSimulationView`）（影響度：高）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/ui/map/NextFlowAnimation.kt`: L95-L155
- `application/app/src/main/java/com/gorite/cyclemap/ui/layout/MapTopOverlays.kt`: L265-L281
- `application/app/src/main/java/com/gorite/cyclemap/ui/map/NavigationHudViews.kt`: L84-L88, L311-L314
- `application/app/src/main/java/com/gorite/cyclemap/ui/simulation/Isometric3dSimulationView.kt`: L113-L140

#### 原因と消費メカニズム
1. 横画面ナビ中に表示される `LandscapeNavHudBar`（および `GoogleNavHeaderV2`）内の `NextFlowAnimation` が、`rememberInfiniteTransition` で 2.6 秒周期の無限アニメーションを案内終了まで休みなく回し続ける。
2. `NextFlowAnimation.kt` L96, L111-L125 では `progress` をコンポジションフェーズで読み取り `Modifier.offset(x = slideX.dp)` へ渡しているため、**停車中でも毎フレーム（最大 120fps）再コンポーズと再レイアウトが発生**し、LTPO OLED 端末のリフレッシュレート自動低下（1〜10Hz）を阻害する。
3. 同様に `Isometric3dSimulationView` も 3 本の `rememberInfiniteTransition` を常時回しており、`LocalAnimationEnabled` やバックグラウンド状態を考慮していない。

#### 具体的な修正案
1. **`NextFlowAnimation` の無限ループ停止と省電力化**:
   - **案 A（推奨）**: 無限ループを廃止し、案内ステップが変わったタイミング（`LaunchedEffect(stepKey)`）だけ 1 回アニメーション再生して静止表示する（または `isLowPowerMode` / 停車時は完全静止）。
   - **案 B（ループを維持する場合）**: 移動処理を `Modifier.offset(x = ...)` ではなく `Modifier.graphicsLayer { translationX = ... }` ラムダ内へ移して再コンポーズ・再レイアウトを無くし、かつ `isLowPowerMode` や停車中（`speedKmh < 1.5`）は `animationEnabled = false` に落とす。
2. **`Isometric3dSimulationView` のアニメ制御**:
   - `LocalAnimationEnabled.current` と `Lifecycle.State.RESUMED` を見ない限り無限アニメを止める。

#### 注意点・副作用対策
- アニメーション無効設定（`animation_enabled == false` または OS 側 `animator_duration_scale == 0`）時は既に `NextFlowAnimation(animationEnabled = false)` で静止表示になる仕様のため、それを省電力時・ステップ外にも自然に拡張する形になる。

#### 検証方法
- 横画面ナビ中の `dumpsys gfxinfo <pkg>` でアイドル時の連続フレーム発行が止まること（または 0fps に落ちること）を確認する。

---

### #8 画面常時点灯（`FLAG_KEEP_SCREEN_ON`）の過剰適用（影響度：高）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/ui/layout/MapContentLayout.kt`: L180-L182
- `application/app/src/main/java/com/gorite/cyclemap/ui/effects/KeepScreenOnEffect.kt`: L17-L25
- `application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerHost.kt`: L76-L82

#### 原因と消費メカニズム
1. ディスプレイバックライト／OLED 発光はスマートフォンの消費電力の中で最も大きい要因の 1 つだが、`shouldKeepScreenOnInCycleComputer()`（`KeepScreenOnEffect.kt` L17-L25）は `state != RideState.PAUSED` なら常に `true` を返す。そのため、**まだ走り出していない `RideState.IDLE` の状態でサイコン画面を開いたまま放置しても、永久に画面が消灯しない**。
2. 通常地図側（`MapContentLayout.kt` L180-L182）も `isNavigationActive || isRecording` の間は無条件に `FLAG_KEEP_SCREEN_ON` を付与する。ポケットやハンドルバッグに入れて画面消灯したい「ルートなし GPX 記録中（`!isNavigationActive && isRecording`）」や、ナビ中の長時間休憩（数分以上の停車）でも画面が自動消灯しない（手動で電源ボタンを押さない限り点灯し続ける）。

#### 具体的な修正案
1. **サイコン画面の `IDLE` 時常時点灯解除**:
   - `shouldKeepScreenOnInCycleComputer()` で `RideState.IDLE` は `false`（または画面を開いてから 60 秒間操作・走行開始が無ければ `false`）にする。
2. **記録中・停車中の自動消灯ポリシー導入**:
   - `!isNavigationActive && isRecording`（単なる GPX バックグラウンド記録）では、設定でオンにしない限り `FLAG_KEEP_SCREEN_ON` を外し、OS の画面消灯タイムアウトに任せる。
   - `isNavigationActive` 中も、長時間の停止（例：速度 0.5m/s 未満が 120 秒以上継続、かつユーザー操作なし）で一時的に `FLAG_KEEP_SCREEN_ON` を解除し、再走行検知または画面タップ時に再付与する（あるいは設定で「停車中自動消灯」を選択可能にする）。

#### 注意点・副作用対策
- `KeepScreenOnEffect` の挙動を変える場合、既存の `shouldKeepScreenOnInCycleComputer` ユニットテストを仕様に合わせて更新する。
- 信号待ち（10〜40 秒程度）で画面が消えてしまうとサイクリングの視認性を損ねるため、停車消灯の猶予は 90〜120 秒以上に設定する。

#### 検証方法
- `KeepScreenOnEffect` のユニットテストおよび実機で、`RideState.IDLE` や単体記録時に OS の画面消灯タイムアウトどおり消灯することを確認する。

---

### #9 バックグラウンド（画面オフ）中も止まらない定期ループ・SQLite クエリ・センサー（影響度：中）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapLifecycleEffects.kt`: L132-L246, L249-L310
- `application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerHost.kt`: L52-L64
- `application/app/src/main/java/com/gorite/cyclemap/ui/screens/MapScreen.kt`: L645-L649
- `application/app/src/main/java/com/gorite/cyclemap/ui/DeveloperSensors.kt`: L116-L193
- `application/app/src/main/java/com/gorite/cyclemap/MainActivity.kt`: L52-L57

#### 原因と消費メカニズム
画面オフ（`PAUSED_OR_STOPPED`）でバックグラウンド記録・ナビを実行している間も、以下の UI 向け処理が止まらず動き続けている：
1. **逆ジオコーディング定期ループ**（`MapLifecycleEffects.kt` L249-L310）：`appLifecycleState` を見ていないため、画面オフ走行中も 1 秒ごとに起床し、50〜100m 移動するたびに `SQLiteDatabase.openDatabase()` → 半径 30km クエリ → `AdministrativeBoundaries` ポリゴン判定 → クローズを繰り返す。さらにナビ中は上部の地名自体が表示されていないにも関わらず実行される。
2. **ラスター POI 取得ループ**（`MapLifecycleEffects.kt` L132-L246）：`appLifecycleState` を見ていないため、ラスター地図選択時のバックグラウンド記録中も 500ms ごとにカメラ中心をポーリングし、50m 移動ごとに SQLite 検索・Bitmap 生成・`view.invalidate()` を行う。
3. **サイコン画面のバックグラウンド動作**（`CycleComputerHost.kt` L52-L64、`MapScreen.kt` L645-L649）：1 秒時計更新ループが `PAUSED_OR_STOPPED` 中も毎秒再コンポーズを発生させ、15 秒保存ループが `PAUSED`（データ変化なし）でも 15 秒ごとに `SharedPreferences` (`cyclemap_prefs.xml`) を上書きし続ける。`PressureReader`（気圧計）もサイコン画面のまま画面オフ・未走行（`IDLE`）の状態で止まらない。
4. **開発者オプションのセンサー・GNSS リーク**（`DeveloperSensors.kt` L116-L193）：`LifecycleEventObserver` が無いため、画面を開いたままスリープ／バックグラウンド化すると **9 種類のハードウェアセンサー（`SENSOR_DELAY_UI`）＋ `GPS_PROVIDER` (1s) ＋ `NETWORK_PROVIDER` (2s) ＋ `GnssStatus`** がすべて稼働し続ける。
5. **昼夜判定 60 秒タイマー**（`MainActivity.kt` L52-L57）：`ThemeMode.LIGHT` / `DARK` のときやバックグラウンド中も 60 秒ごとに `timeTick` を更新して再評価している。

#### 具体的な修正案
1. **`MapLifecycleEffects` のループに `appLifecycleState` ガードを追加**:
   - 逆ジオコーディング（L249）は `appLifecycleState == AppLifecycleState.RESUMED && !isNavigationActive`（または地名表示 UI が出ている状態）のときだけ稼働させる。
   - ラスター POI 取得（L132）は `appLifecycleState == AppLifecycleState.RESUMED` を `LaunchedEffect` のキーに加え、非表示時はループを停止する。
2. **`CycleComputerHost` と `PressureReader` のライフサイクル連動**:
   - 1 秒時計更新ループは `appLifecycleState == AppLifecycleState.RESUMED` のときだけ実行する。
   - 15 秒ごとの `store.save(rideSession)` は「`RideState.RUNNING` の間のみ（`PAUSED` は変化がないため `onStartPause` 時と `onDispose` 時の 1 回で十分）」に制限する。
   - `PressureReader` は「サイコン表示中かつ（`RESUMED` または `RideState.RUNNING` / GPX 記録中）」のみ `start()` し、バックグラウンドかつ非記録・一時停止時は `stop()` する。
3. **`DeveloperSensors` への `LifecycleEventObserver` 追加**:
   - `ON_RESUME` で登録し、`ON_PAUSE` / `ON_STOP` で全 9 センサー・`LocationManager`・`GnssStatus` を確実に解除する。
4. **`MainActivity` 昼夜判定タイマーの最適化**:
   - `ThemeMode.AUTO` かつフォアグラウンド時のみ実行し、60 秒ポーリングではなく `SolarCalculator` で求めた「次の日の出／日没時刻までの残りミリ秒（＋数秒のマージン）」を 1 回 `delay` する方式に変更する（復帰時は `ON_RESUME` で即時再評価）。

#### 注意点・副作用対策
- 逆ジオコーディングを `RESUMED` 限定にした場合、バックグラウンドから復帰した直後に現在地の地名を最新化できるよう、`LaunchedEffect` キーに `appLifecycleState` を入れて再開時に 1 回走るようにする。

#### 検証方法
- 画面オフ状態で 10 分間記録した `adb shell dumpsys batterystats` で CPU Wakelock 時間とセンサー稼働時間を確認する。

---

### #10 `BatteryMonitor.isLowPower` の省電力連携拡大と `BroadcastReceiver` 重複解消（影響度：中）

#### 対象箇所
- `application/app/src/main/java/com/gorite/cyclemap/power/BatteryMonitor.kt`: L21-L22
- `application/app/src/main/java/com/gorite/cyclemap/ui/mapview/VectorMapView.kt`: L326, L509-L520
- `application/app/src/main/java/com/gorite/cyclemap/ui/map/NavigationPanelC.kt`: L759-L774

#### 原因と消費メカニズム
1. `BatteryMonitor` はバッテリー残量 20% 以下（未充電）または OS 省電力モードを `isLowPower` として検知しているが、実際に制御されているのは `VectorMapView` の 3D 建物押し出し（`buildings-3d`）の抑制のみ。
2. `NavigationPanelC.kt` L759-L774 の `rememberBatteryPercent()` が既存の `BatteryMonitor` を使わず、`ACTION_BATTERY_CHANGED` の `BroadcastReceiver` を独自に重複登録している。

#### 具体的な修正案
1. **`isLowPower` の全サブシステム連動**:
   - `isLowPower == true` のとき、以下の省電力プロファイルを自動で有効化する：
     - MapLibre 最大 FPS 低下（例：15〜20fps）および `LocationGlide` 補間ループの停止（1Hz スナップ更新へ切替）。
     - コンパスセンサー（`CompassUpdates`）を停止または大幅間引き。
     - `LocationTrackingService` の GPS 間隔を緩和プロファイルへ切替（#4）。
     - `NextFlowAnimation` 等の装飾アニメーション停止（#7）。
2. **`BroadcastReceiver` の一本化**:
   - `NavigationPanelC.kt` の `rememberBatteryPercent()` を廃止し、`MapScreenScaffold` から `batteryStatus.levelPercent` を `NavBottomPanelC` へ渡すように統一する。

#### 注意点・副作用対策
- 設定画面（`SettingsPanelSheet.kt`）のステータス表示と同様に、現在省電力抑制が働いている項目がユーザーに分かるようにしておくと混乱がない。

#### 検証方法
- `BatteryMonitor` のテストおよび、バッテリー 20% 以下／OS 省電力モード ON 時に 3D・グライド・FPS・アニメが連動して省電力状態へ切り替わることを実機で確認する。

---

## 推奨実装ロードマップ

### Phase 1（即効性・最大の消費削減）
1. **#1**: `VectorMapView` の `setMaximumFps`＋追従フレーム間引き＋`OnCameraIdleListener` 連鎖（`SharedPreferences` 保存・`MapCameraCenter`・POI 空クリア）の遮断
2. **#2**: `CompassUpdates` の有効条件・レート最適化、高頻度 State 購読の局所化、`MapViewsContainer` の `tileSignature` / `routePoints` メモ化
3. **#3**: `GpxRecorder` / `GpxJournal` の非同期バッチフラッシュ化、`LocationDiagLog` のデバッグ限定化、`healthCheckRunnable` の単発予約化
4. **#6**: `LazyMappedRoadGraph.nearestNodeCandidates` の `SpatialGrid.findWithin` 直接利用と道路ルール照合のバックグラウンド化

### Phase 2（長時間ライド・バックグラウンドの安定化）
5. **#4**: `LocationTrackingService` の状態別 `LocationRequest` 切替・ラスター停止時再描画抑止・`START_NOT_STICKY` 化（`GpsHealthMonitor` 閾値連動含む）
6. **#5**: `WeatherRepository` の指数バックオフ・重複防止
7. **#8**: `KeepScreenOnEffect` の `IDLE` 時消灯・記録単独時／長時間停車時の消灯ポリシー整備
8. **#9**: `MapLifecycleEffects`・`CycleComputerHost`・`PressureReader`・`DeveloperSensors`・`MainActivity` のバックグラウンド停止

### Phase 3（仕上げ・低残量時の延命）
9. **#7**: `NextFlowAnimation`・`Isometric3dSimulationView` の無限ループ停止／省電力化
10. **#10**: `BatteryMonitor.isLowPower` の全サブシステム連動と `rememberBatteryPercent()` 統合

---

## 検証・計測手順

### 1. JVM ユニットテスト（各 Phase 完了ごと）
```bash
cd application
./gradlew :routing-core:test :app:testDebugUnitTest
```
- 特に `LazyMappedRoutingTest`, `SnapFallbackRoutingTest`, `GpxRecorderTest` 等の既存テストに加え、バックオフ（#5）・`nearestNodeCandidates` 範囲外挙動（#6）・`KeepScreenOn` 判定（#8）・`GpsHealthMonitor` 長間隔耐性（#4）の回帰テストを追加する。

### 2. 実機計測（Galaxy S26 等）
1. **バッテリー・センサー・Wakelock 計測**:
   - `adb shell dumpsys batterystats --reset` 実行後、以下の 3 シナリオを各 15〜30 分実施し、`adb shell dumpsys batterystats` でアプリの消費量・GPS 時間・センサー時間・Wakeup 回数を比較する：
     - シナリオ A: 待ち受け静止表示（15 分）
     - シナリオ B: 模擬位置または実走でのナビ案内中（画面 ON、30 分）
     - シナリオ C: 画面オフでの GPX 記録＋県またぎ模擬走行（30 分）
2. **フレームレート・UI 再コンポーズ確認**:
   - `adb shell dumpsys gfxinfo <package> reset` → 停車中／横画面ナビ中に `dumpsys gfxinfo <package> framestats` を取得し、静止時に不要なフレーム描画が止まっていることを確認する。
3. **メインスレッド I/O・通信リトライ検出**:
   - 開発ビルドで `StrictMode.ThreadPolicy`（`detectDiskReads` / `detectDiskWrites`）を有効化し、`OnCameraIdle` 毎フレーム書込（#1）や `MapViewsContainer` 同期 `stat()`（#2）、`GpxJournal` 同期 `fsync`（#3）が消滅したことを確認する。
   - 機内モードまたは不安定回線下で Logcat を確認し、気象庁 API への毎秒リトライ（#5）が発生しないことを確認する。
