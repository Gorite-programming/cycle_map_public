# 外出中セッションの報告 (2026-11-08)

実施者: Claude Code。実機・APK には触れていない(JVM・Python・データのみ)。

## 実施内容

| 区分 | 内容 | 状態 | commit |
|---|---|---|---|
| A | 検索DBに営業時間・電話・Web・車椅子を追加 / 営業時間パーサ強化 | 完了(5県の DB に反映済み) | 3a6fd13, ba4bc9d |
| D | データ更新の一括パイプライン  と初回の本実行 | 完了(5県とも合格・昇格) | 15319a0 |
| B | CI・== Kotlin/JVM tests ==

> Configure project :app
WARNING: The option setting 'android.overridePathCheck=true' is experimental.
The current default is 'false'.

> Task :routing-core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :routing-core:compileKotlin UP-TO-DATE
> Task :routing-core:compileJava NO-SOURCE
> Task :routing-core:processResources NO-SOURCE
> Task :routing-core:classes UP-TO-DATE
> Task :routing-core:jar UP-TO-DATE
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:buildKotlinToolingMetadata UP-TO-DATE
> Task :app:preReleaseBuild UP-TO-DATE
> Task :app:generateReleaseBuildConfig
> Task :app:checkReleaseAarMetadata UP-TO-DATE
> Task :app:generateReleaseResValues UP-TO-DATE
> Task :app:mapReleaseSourceSetPaths UP-TO-DATE
> Task :app:generateReleaseResources UP-TO-DATE
> Task :app:mergeReleaseResources UP-TO-DATE
> Task :app:packageReleaseResources UP-TO-DATE
> Task :app:parseReleaseLocalResources UP-TO-DATE
> Task :app:createReleaseCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksRelease UP-TO-DATE
> Task :app:processReleaseMainManifest UP-TO-DATE
> Task :app:processReleaseManifest UP-TO-DATE
> Task :app:processReleaseManifestForPackage UP-TO-DATE
> Task :app:processReleaseResources UP-TO-DATE
> Task :app:javaPreCompileRelease UP-TO-DATE
> Task :app:preReleaseUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileReleaseUnitTest UP-TO-DATE
> Task :osm-importer:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :osm-importer:processResources NO-SOURCE
> Task :osm-importer:processTestResources NO-SOURCE
> Task :routing-core:compileTestKotlin UP-TO-DATE
> Task :routing-core:compileTestJava NO-SOURCE
> Task :routing-core:processTestResources NO-SOURCE
> Task :routing-core:testClasses UP-TO-DATE
> Task :routing-core:test UP-TO-DATE
> Task :osm-importer:compileKotlin
> Task :osm-importer:compileJava NO-SOURCE
> Task :osm-importer:classes UP-TO-DATE
> Task :osm-importer:jar UP-TO-DATE
> Task :osm-importer:compileTestKotlin
> Task :osm-importer:compileTestJava NO-SOURCE
> Task :osm-importer:testClasses UP-TO-DATE
> Task :osm-importer:test
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac
> Task :app:processDebugJavaRes UP-TO-DATE

> Task :app:compileReleaseKotlin
w: file:///Users/gorite/Developer/cycle_map/application/app/src/main/java/com/gorite/cyclemap/ui/mapview/VectorMapView.kt:619:17 'fun setPadding(p0: Int, p1: Int, p2: Int, p3: Int): Unit' is deprecated. Deprecated in Java.

> Task :app:compileReleaseJavaWithJavac
> Task :app:processReleaseJavaRes
> Task :app:bundleDebugClassesToCompileJar
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:bundleReleaseClassesToRuntimeJar
> Task :app:bundleReleaseClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:compileReleaseUnitTestKotlin
> Task :app:testDebugUnitTest
> Task :app:compileReleaseUnitTestJavaWithJavac NO-SOURCE
> Task :app:processReleaseUnitTestJavaRes UP-TO-DATE
> Task :app:testReleaseUnitTest
> Task :app:test
[Incubating] Problems report is available at: file:///Users/gorite/Developer/cycle_map/application/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.11.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 5s
57 actionable tasks: 18 executed, 39 up-to-date
== data-tool pytest ==
........................................................................ [ 67%]
...................................                                      [100%]
107 passed in 0.46s
== style patch idempotence ==
== shell / python syntax ==
ALL OK・未参照コード除去・ 抽出 | 完了 | 81deb88, e5e97b0 |
| C | 実グラフのベンチ基盤()と測定 | 基盤と測定は完了。コストモデルの改良は見送り(下記) | 本報告と同じ commit |

## D: データ更新の結果
OSM は Geofabrik chugoku-latest(データ時点 2026-11-07T21:21Z)。旧成果物は  に退避済み。

| 県 | 検索DB件数 (前回) | 営業時間 | 電話 | Web | graph MB |
|---|---:|---:|---:|---:|---:|
| 鳥取 | 39,705 (39,586) | 568 | 9,272 | 7,505 | 72 |
| 島根 | 46,450 (46,379) | 413 | 10,868 | 8,420 | 111 |
| 岡山 | 106,081 (105,887) | 2,563 | 40,948 | 33,172 | 166 |
| 広島 | 120,810 (120,280) | 1,703 | 40,569 | 32,761 | 177 |
| 山口 | 73,798 (73,715) | 624 | 29,395 | 22,606 | 124 |

所要: OSM 取得 3,393 秒 / 検索DB 692 秒 / graph 438 秒 / タイル 317 秒。地形(等高線・陰影)は今回再生成していない。
営業時間は OSM にしか無いため 1〜2% 台。電話・Web は主に Overture 由来。

## C: 実グラフのベンチ
`./gradlew :routing-core:benchmarkRealGraph -Pgraph=<.graph> [-Pod=200] [-Pseed=20261108]`。固定シードで直線 3〜40km の OD を選び、アプリと同じ流れ(探索範囲つき→到達不能なら範囲なしで再試行)で測る。既存の探索フラグだけを切り替え、コストモデル本体と最小倍率 0.90 は変えていない。

### Tottori.graph (OD 200 組)

| 条件 | 到達率 | 範囲外再試行 | 中央値 時間ms | p95 時間ms | 中央値 展開ノード | 中央値 経路距離 | 基準比 距離増加(中央値) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 基準 (おすすめ) | 98.5% | 3 | 33.2 | 129.7 | 59557 | 30310.0m | +0.00% |
| 平坦優先 | 98.5% | 3 | 34.6 | 113.8 | 59557 | 30310.0m | +0.00% |
| 坂道優先 | 98.5% | 3 | 31.5 | 116.9 | 59557 | 30310.0m | +0.00% |
| 幹線ペナルティ 1.25 | 98.5% | 3 | 40.9 | 130.6 | 79022 | 29485.4m | -1.88% |
| 幹線ペナルティ 1.5 | 98.5% | 3 | 48.8 | 154.1 | 85549 | 30062.1m | -1.07% |


### Shimane.graph (OD 200 組)

| 条件 | 到達率 | 範囲外再試行 | 中央値 時間ms | p95 時間ms | 中央値 展開ノード | 中央値 経路距離 | 基準比 距離増加(中央値) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 基準 (おすすめ) | 99.5% | 1 | 35.1 | 101.9 | 69296 | 34597.7m | +0.00% |
| 平坦優先 | 99.5% | 1 | 34.0 | 105.3 | 69296 | 34597.7m | +0.00% |
| 坂道優先 | 99.5% | 1 | 32.3 | 101.5 | 69296 | 34597.7m | +0.00% |
| 幹線ペナルティ 1.25 | 99.5% | 1 | 40.5 | 127.8 | 88254 | 33744.7m | -1.55% |
| 幹線ペナルティ 1.5 | 99.5% | 1 | 49.1 | 163.3 | 95862 | 33983.8m | -1.09% |


### Okayama.graph (OD 200 組)

| 条件 | 到達率 | 範囲外再試行 | 中央値 時間ms | p95 時間ms | 中央値 展開ノード | 中央値 経路距離 | 基準比 距離増加(中央値) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 基準 (おすすめ) | 98.5% | 3 | 52.5 | 150.4 | 86944 | 31339.3m | +0.00% |
| 平坦優先 | 98.5% | 3 | 49.3 | 155.4 | 86944 | 31339.3m | +0.00% |
| 坂道優先 | 98.5% | 3 | 46.3 | 160.6 | 86944 | 31339.3m | +0.00% |
| 幹線ペナルティ 1.25 | 98.5% | 3 | 68.4 | 197.3 | 107341 | 30816.2m | -2.30% |
| 幹線ペナルティ 1.5 | 98.5% | 3 | 69.4 | 201.8 | 120201 | 31043.7m | -1.38% |


### Hiroshima.graph (OD 200 組)

| 条件 | 到達率 | 範囲外再試行 | 中央値 時間ms | p95 時間ms | 中央値 展開ノード | 中央値 経路距離 | 基準比 距離増加(中央値) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 基準 (おすすめ) | 95.5% | 11 | 53.7 | 174.4 | 92270 | 36006.0m | +0.00% |
| 平坦優先 | 95.5% | 11 | 48.9 | 175.9 | 92270 | 36006.0m | +0.00% |
| 坂道優先 | 95.5% | 11 | 52.4 | 184.3 | 92270 | 36006.0m | +0.00% |
| 幹線ペナルティ 1.25 | 95.5% | 11 | 64.9 | 186.2 | 116977 | 34922.4m | -2.03% |
| 幹線ペナルティ 1.5 | 95.5% | 11 | 67.2 | 202.8 | 127388 | 35060.1m | -1.66% |


### Yamaguchi.graph (OD 200 組)

| 条件 | 到達率 | 範囲外再試行 | 中央値 時間ms | p95 時間ms | 中央値 展開ノード | 中央値 経路距離 | 基準比 距離増加(中央値) |
|---|---:|---:|---:|---:|---:|---:|---:|
| 基準 (おすすめ) | 96.0% | 8 | 31.4 | 110.5 | 61884 | 32507.1m | +0.00% |
| 平坦優先 | 96.0% | 8 | 30.5 | 102.9 | 61884 | 32507.1m | +0.00% |
| 坂道優先 | 96.0% | 8 | 31.8 | 106.8 | 61884 | 32507.1m | +0.00% |
| 幹線ペナルティ 1.25 | 96.0% | 8 | 38.8 | 121.4 | 75138 | 31734.9m | -1.53% |
| 幹線ペナルティ 1.5 | 96.0% | 8 | 44.9 | 131.7 | 79986 | 31778.9m | -1.02% |



### 読み取れること
- **平坦優先・坂道優先は、現在のデータでは基準と完全に同じ結果。** 原因は、 が辺に勾配()を一度も設定しておらず、グラフ形式は勾配を持てるのに全辺で空であること( で  に勾配を渡している箇所が無い)。勾配を考慮する経路方針は実質的に効いていない。
- 幹線ペナルティ()を上げると展開ノードは増え(約 +30〜45%)、経路距離は中央値で 1〜2% 短くなる。経路の「質」(自転車で走りやすいか)は距離だけでは判断できない。
- 到達率は 95.5〜99.5%。到達しない組は、ランダムに選んだノードが孤立した小さな連結成分に属する場合が主と推測(未検証)。
- 経路の道路種別の構成比と獲得標高は測れていない(経路結果が道路種別を持たず、勾配も無いため)。

## 保留・見送り
- コストモデルの改良は入れていない。勾配が無い状態で調整しても効果を測れないため。**次の候補は、GSI 標高( のキャッシュ)から辺の勾配をグラフに書き込むこと**(グラフ形式は対応済み、importer の拡張が必要)。路面・自転車道の有無はグラフ形式の変更が要る。
- == Kotlin/JVM tests ==

> Configure project :app
WARNING: The option setting 'android.overridePathCheck=true' is experimental.
The current default is 'false'.

> Task :routing-core:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :routing-core:compileKotlin UP-TO-DATE
> Task :routing-core:compileJava NO-SOURCE
> Task :routing-core:processResources NO-SOURCE
> Task :routing-core:classes UP-TO-DATE
> Task :routing-core:jar UP-TO-DATE
> Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:generateDebugBuildConfig UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:generateDebugResValues UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:bundleDebugClassesToRuntimeJar UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:preDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileDebugUnitTest UP-TO-DATE
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processDebugJavaRes UP-TO-DATE
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest UP-TO-DATE
> Task :app:buildKotlinToolingMetadata UP-TO-DATE
> Task :app:preReleaseBuild UP-TO-DATE
> Task :app:generateReleaseBuildConfig UP-TO-DATE
> Task :app:checkReleaseAarMetadata UP-TO-DATE
> Task :app:generateReleaseResValues UP-TO-DATE
> Task :app:mapReleaseSourceSetPaths UP-TO-DATE
> Task :app:generateReleaseResources UP-TO-DATE
> Task :app:mergeReleaseResources UP-TO-DATE
> Task :app:packageReleaseResources UP-TO-DATE
> Task :app:parseReleaseLocalResources UP-TO-DATE
> Task :app:createReleaseCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksRelease UP-TO-DATE
> Task :app:processReleaseMainManifest UP-TO-DATE
> Task :app:processReleaseManifest UP-TO-DATE
> Task :app:processReleaseManifestForPackage UP-TO-DATE
> Task :app:processReleaseResources UP-TO-DATE
> Task :app:compileReleaseKotlin UP-TO-DATE
> Task :app:javaPreCompileRelease UP-TO-DATE
> Task :app:compileReleaseJavaWithJavac UP-TO-DATE
> Task :app:bundleReleaseClassesToRuntimeJar UP-TO-DATE
> Task :app:bundleReleaseClassesToCompileJar UP-TO-DATE
> Task :app:compileReleaseUnitTestKotlin UP-TO-DATE
> Task :app:preReleaseUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileReleaseUnitTest UP-TO-DATE
> Task :app:compileReleaseUnitTestJavaWithJavac NO-SOURCE
> Task :app:processReleaseJavaRes UP-TO-DATE
> Task :app:processReleaseUnitTestJavaRes UP-TO-DATE
> Task :app:testReleaseUnitTest UP-TO-DATE
> Task :app:test UP-TO-DATE
> Task :osm-importer:checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :osm-importer:compileKotlin UP-TO-DATE
> Task :osm-importer:compileJava NO-SOURCE
> Task :osm-importer:processResources NO-SOURCE
> Task :osm-importer:classes UP-TO-DATE
> Task :osm-importer:jar UP-TO-DATE
> Task :osm-importer:compileTestKotlin UP-TO-DATE
> Task :osm-importer:compileTestJava NO-SOURCE
> Task :osm-importer:processTestResources NO-SOURCE
> Task :osm-importer:testClasses UP-TO-DATE
> Task :osm-importer:test UP-TO-DATE
> Task :routing-core:compileTestKotlin UP-TO-DATE
> Task :routing-core:compileTestJava NO-SOURCE
> Task :routing-core:processTestResources NO-SOURCE
> Task :routing-core:testClasses UP-TO-DATE
> Task :routing-core:test UP-TO-DATE
[Incubating] Problems report is available at: file:///Users/gorite/Developer/cycle_map/application/build/reports/problems/problems-report.html

Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

For more on this, please refer to https://docs.gradle.org/8.11.1/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.

BUILD SUCCESSFUL in 824ms
57 actionable tasks: 57 up-to-date
== data-tool pytest ==
........................................................................ [ 67%]
...................................                                      [100%]
107 passed in 0.37s
== style patch idempotence ==
== shell / python syntax ==
ALL OK と CI()は未実行(GitHub 側の実行結果は未確認)。手元では  と pytest 107 件の通過を確認した。
-  など参照が少ない他のファイルの除去は、今回は見ていない。
- 経路回帰チェック( から C のベンチを呼ぶフック)は未実装。

## 戻ってからの作業
1. 実機を接続し === CycleMap Data Deployment ===
Destination: /sdcard/Android/data/com.gorite.cyclemap.test/files/Documents/CycleMap
Mode: LIVE DEPLOY
Connected device: RFGL626QR3N
Stopping com.gorite.cyclemap.test to safely write files...
Ensuring remote directory exists: /sdcard/Android/data/com.gorite.cyclemap.test/files/Documents/CycleMap

--- Preparing and Transferring Files ---
>> Prefecture: Tottori
  [FILE] Tottori.search.db (11.45 MiB)
         SHA-256: 4ee8fe7ba3f5438adacd4e04eaf2c8ada3082c1272aa998ff8a58c261e2becd4
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Tottori.graph (71.57 MiB)
         SHA-256: 01adb8d9234a3cfb51ef9afc483f582ceef0e5c1d39867d1ab29265de575a42b
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Tottori.graph.idx (17.49 MiB)
         SHA-256: b90236f61a9a314f05705f8846302c2ec97e58ee6bd6d7bcc9bc5668918f0b19
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] tottori.pmtiles (45.35 MiB)
         SHA-256: 6cafea9224a10f477df0ec94480ef23432f07f38876765670e978cfaa3f4417e
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] tottori.contours.pmtiles (17.81 MiB)
         SHA-256: 32fb3f34a71151669c51719a0a6555c611f33518f7fec258d3d3a2fb98d13a1a
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] tottori.hillshade.pmtiles (24.02 MiB)
         SHA-256: 2587443506e9d1b06a50b4d4e98679c4d150b0fb7e4100d1c10011cb75e962e0
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
>> Prefecture: Shimane
  [FILE] Shimane.search.db (13.42 MiB)
         SHA-256: 35a0541d919c0a69eac03fe0d74d06504a53c8ed0bbf4f9cae599dd13ba1069a
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Shimane.graph (111.10 MiB)
         SHA-256: f4794d1f1f63bfb9400ba2b8d3a3ce71cd5f6c7362c23555003bade17c23032b
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Shimane.graph.idx (26.91 MiB)
         SHA-256: 71bcff92e12f1fac57839a81ba5349f552f0e3d47273e290a646bfd91827fa62
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] shimane.pmtiles (108.16 MiB)
         SHA-256: ba70347c1eea1b59d262eb5fbc0e2bb9ac4848f321e5072a5112705b91bd770b
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] shimane.contours.pmtiles (32.53 MiB)
         SHA-256: 280cec7b5ed0b20c7829683fd7ad5e463d858afe656f66f2f96384fc4a578ece
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] shimane.hillshade.pmtiles (44.16 MiB)
         SHA-256: cc52fa1bb4f0b132b8e11eaa78b99445642e05ebde134586e9fa1d5a5a262ae0
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
>> Prefecture: Okayama
  [FILE] Okayama.search.db (33.57 MiB)
         SHA-256: 6d5fdc304331ff1bf894cf6a2eafb6af53e92936fa948cabf336d8c48833c6c7
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Okayama.graph (165.85 MiB)
         SHA-256: 6552d510e448d9857b33011c15f962c37b6b350bdeb0c5deaf0c5106bb2b632c
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Okayama.graph.idx (39.83 MiB)
         SHA-256: 6940500e2ef248cf438a51077416328dbf65db2c03bbfe3f2f7365c2a4b65e21
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] okayama.pmtiles (97.87 MiB)
         SHA-256: ae41427520a14966b539eda5b867f6b387e0aa05667a6b780401e81ccd9d03f5
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] okayama.contours.pmtiles (29.86 MiB)
         SHA-256: c5537508080d47e34d3df2012db44ff2639bbabae2a76c600143bdc0d6385a5a
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] okayama.hillshade.pmtiles (44.76 MiB)
         SHA-256: afdedeadf9ffe1617799ae933e81e62cd6507f55fcd9399fe8b8810cf96ed74a
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
>> Prefecture: Hiroshima
  [FILE] Hiroshima.search.db (38.97 MiB)
         SHA-256: 125ee80e7480ec7ccd781e356d49359e1872f486ae93c446f8c95455559137d5
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Hiroshima.graph (177.41 MiB)
         SHA-256: eec16f0abcff94f613d49572973145d9b563fb859a8146705d0405d7d7bf9091
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Hiroshima.graph.idx (43.15 MiB)
         SHA-256: 56356fed97cb40b1e1146a6d75d0bbf32e99941190502decad780d115c40feb1
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] hiroshima.pmtiles (94.84 MiB)
         SHA-256: 166709ccf3c8c994872136871e8752fb0bdd76528242b131d137429e6f1fd8a4
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] hiroshima.contours.pmtiles (35.83 MiB)
         SHA-256: 1dbd9c9c26868fcbd5baf271c266d6b525beeb6a3ab2078cfee500021575eb89
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] hiroshima.hillshade.pmtiles (55.58 MiB)
         SHA-256: fdefd8f036639ae7f66defdff85543e88296ad05a2cb387f071ce7cccc1cc3a8
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
>> Prefecture: Yamaguchi
  [FILE] Yamaguchi.search.db (23.82 MiB)
         SHA-256: 87e695b9b09e8ca5fd3f5770d5ebb79e84003dac21886fda034369caaac2e707
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Yamaguchi.graph (123.83 MiB)
         SHA-256: a6939ba6679c2245a16c15995601c1188a2930a9443f408b5417a2225fcebbea
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] Yamaguchi.graph.idx (30.01 MiB)
         SHA-256: 52ea49d571a38e80094dcbfe0f3eb36f79a208e8e31254e43a3f4e30275e8d0e
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] yamaguchi.pmtiles (70.32 MiB)
         SHA-256: 49d80702bbed29cfecc84a30712c70742f52124bdd5e0fbd16f3e72c93d3b77c
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] yamaguchi.contours.pmtiles (24.95 MiB)
         SHA-256: 84caf2abd74e3c4a59c1591f98a4a96151608794edf5f07c04efd20f24766b48
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)
  [FILE] yamaguchi.hillshade.pmtiles (36.73 MiB)
         SHA-256: e0208cb903db06afd206db2317c4b8e276faefc790366dd77aacf146d33d119e
         -> Pushing to device...
         -> Verifying on-device SHA-256...
         -> Verified OK (SHA-256 match)

=== Deployment Summary ===
Total files: 30
Total data size: 1.65 GiB
Status: ALL FILES TRANSFERRED AND VERIFIED SUCCESSFULLY. で新しい検索DB・タイル・graph を転送。
2. 場所の詳細に営業時間・電話・Web が出ることを実機で確認。
3. APK をクリーン HEAD からビルドしてインストールし、 に SHA-256 を記録。
4. 勾配をグラフに入れるか判断(上記)。
