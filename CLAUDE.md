# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`AGENTS.md` is the shared, authoritative project guide (written in Japanese, for all AI agents). Read it first; this file only summarizes the essentials and does not replace it.

## Git policy (important)

Claude Code may decide on its own when to `git add` / `git commit` / `git push` (permission granted by Gorite on 2026-11-08; recorded in `AGENTS.md`). This covers ordinary commits and pushes only: destructive operations such as `push --force`, history rewriting or branch deletion still need explicit approval each time. Always report what was committed and pushed. The permission applies only when Claude Code operates; other agents must still wait for approval. Run `git pull` before starting work, since development is synced between Windows and Mac via GitHub.

## What this is

CycleMap: a fully offline Android cycling navigation app. Three layers:

1. `application/` — the Gradle project (run `./gradlew` from here). Modules: `:app`, `:routing-core`, `:osm-importer`.
2. Root Python pipeline — `build_prefectures.py` turns `data/japan-latest.osm.pbf` into per-prefecture packages (`packages/<name>.zip`) for the 5 Chugoku prefectures.
3. `sandbox/data-tool/` — Python/Streamlit tool that builds the integrated POI search DB (Overture + MLIT + OSM).

`Archives/` is old code. Do not edit it, and never delete `Archives/application/.git.bak`.

## Commands

```bash
cd application
./gradlew test                                   # all unit tests
./gradlew :routing-core:test :osm-importer:test  # JVM core only
./gradlew :routing-core:test --tests '<fully.qualified.TestClass>'   # single test class
./gradlew :app:testDebugUnitTest --tests '<fully.qualified.TestClass>'
./gradlew :app:assembleDebug                     # APK (debug applicationId is com.gorite.cyclemap.test)
./gradlew :osm-importer:installDist              # build the importer CLI
JAVA_OPTS="-Xmx6g" osm-importer/build/install/osm-importer/bin/osm-importer --bbox none <in.osm.pbf> <out.graph>
```

```bash
# From the repo root. Always use venv/bin/python (system python has no osmium); the `osmium` CLI must be on PATH.
venv/bin/python build_prefectures.py --only Yamaguchi --keep-work     # add --force to rebuild
venv/bin/python application/osm-importer/build_search_db.py --bbox none <in.osm.pbf> <out.search.db>
venv/bin/python scripts/build_terrain.py --only Yamaguchi             # contours + hillshade from GSI DEM -> packages/<name>.{contours,hillshade}.pmtiles (downloads ~2k tiles/prefecture, cached in work/terrain)
python3 scripts/patch_map_style.py                                    # re-apply CycleMap's own tweaks to the bundled Protomaps styles (idempotent)
python3 scripts/build_tiles.py --only Yamaguchi                       # vector tiles -> packages/<name>.pmtiles (needs network; pmtiles CLI in sandbox/vector_test/pipeline/)
python3 scripts/build_ch.py --only Yamaguchi                          # Contraction Hierarchies sidecar -> packages/<name>.graph.ch (skips when the graph fingerprint matches; --force)
python3 scripts/build_portals.py                                      # cross-prefecture portals -> packages/<A>__<B>.portals (rerun after any graph rebuild)
venv/bin/python scripts/refresh_data.py --dry-run                     # full refresh: latest OSM -> search DB, graph, tiles, CH, portals (about 1.5 h; does not push to the device)
./scripts/push_data.sh --dry-run                                      # then without the flag: adb push + SHA-256 verification (--tiles-only / --db-only)
scripts/device_regression.sh                                          # on-device route regression (in-pref / 2-pref / 3-pref), non-zero exit on failure; needs a debug build over adb
```

Before pushing, run the same checks as CI (`.github/workflows/ci.yml`: Gradle tests, `sandbox/data-tool` pytest, style-patch idempotence, shell/Python syntax, `scripts/tests` pytest):

```bash
scripts/check_all.sh                 # --skip-gradle to skip the Gradle tests (they run with --offline)
(cd sandbox/data-tool && ../../venv/bin/python -m pytest -q path/to/test_file.py::test_name)   # single Python test
```

CI runs only on pushes to `main` and on pull requests, so pushes to a work branch such as `auto/<date>` are not checked until a PR is opened.

Toolchain: AGP 8.10.1, Kotlin 2.0.21, compileSdk/target 35, minSdk 33, Java 11 target. On Windows use Android Studio's bundled JDK 21 (system Java 25 is incompatible with Gradle 8.11.1). `application/local.properties` (`sdk.dir`) is machine-specific and not committed.

## Architecture

- **Routing data and display data are strictly separate.** Routing uses the custom `.graph` + `.graph.idx` binary built from OSM. Display uses GSI tiles / MapLibre PMTiles. Never mix raster tile data into the graph. Keep the OSM and GSI attribution visible, even offline.
- **`:routing-core`** is pure JVM. The baseline router is A\* (`AStarRouter` + `CyclingCostModel`). Graphs are read through mmap (`LazyMappedRouting.kt`; the old non-lazy `MappedRoadGraph` was unused and deleted). The experimental HSA\*/ALT routers were deleted (`90bf751`).
  - **Contraction Hierarchies** (`ContractionHierarchy*.kt`, design in `docs/ch_design.md`): an optional `<name>.graph.ch` sidecar. The app prefers CH only for the recommended profile × bicycle and falls back to A\* when the file is missing or its graph fingerprint (size + CRC of the first/last 1 MB) does not match. CH results must stay identical to A\*; check with `:routing-core:benchmarkCh` / `benchmarkAttach` / `benchmarkChainCh`.
  - **Cross-prefecture routes** never merge graphs. Shared OSM nodes act as portals: `CrossGraphRouting.kt` (2 prefectures), `CrossChainRouting.kt` (3+, A\*), `CrossChainChRouting.kt` (CH many-to-many). `<A>__<B>.portals` (`CrossPortalsFile.kt`) is an optional precomputed portal list; an unusable file returns null and the code falls back to ID matching. On the app side `navigation/MultiLegRoutePlanner.kt` splits a route into legs per stop and prefecture. Design: `docs/cross_prefecture_design.md`.
  - `SnapFallbackRouting.kt` retries with the next-nearest node when the nearest one sits on an isolated way (e.g. inside a park).
  - `CyclingCostModel` excludes `motorway`. The bicycle road multipliers, the "recommended" grade penalty and the minimum multiplier 0.90 (the A\* heuristic lower bound) live in one place, `BicycleCost.kt`, shared by `CyclingCostModel` and `LazyMappedRoadGraph`. Change them only there, and rebuild the CH sidecars afterwards (the graph fingerprint does not cover the costs).
  - Limits: `LazyMappedRouting` reads with `Int` offsets, so `load` rejects graph files over 2 GiB with an explicit error (the per-prefecture graphs fit). `nearestNodeIndex` uses a spatial grid and falls back to a linear scan only when the grid finds nothing. Check `Archives/application/bugs.md` and `git -C application log` before "fixing" an old bug, because most Critical/High items are already fixed.
- **`:app`** (package `com.gorite.cyclemap`) uses MVI/UDF with Compose. `MainActivity.kt` is the entry point. The `contract/` package defines the interfaces (`RoutePlanner`, `ReroutePolicy`, `PlaceDetailProvider`, `LaneGuidanceProvider`) and models. The `real/` package holds the implementations (`RealRoutePlanner`, etc.). Other packages: `navigation`, `search`, `tracking`, `weather` (JMA), `speech`, `power`, `net`.
- **Data is never bundled in the APK.** The app reads `getExternalFilesDir(DOCUMENTS)/CycleMap/` (`<name>.graph`, `<name>.graph.idx`, `<name>.search.db`, `<name>.pmtiles`, `tiles/cache.db`, plus the optional `<name>.graph.ch`, `<A>__<B>.portals`, `<name>.contours.pmtiles`, `<name>.hillshade.pmtiles`). Everything outside the 5-file ZIP (tiles, terrain, CH, portals) is a separate artifact in `packages/`, and the app must keep working when any optional file is absent. `RoutingGraphSelector` and `SearchDbSelector` (in `Prefectures.kt`) pick the first file by name at startup, then switch prefecture automatically once the current location is known.
- **Search DB must work without FTS5.** Android's stock SQLite often lacks FTS5, so each DB also stores a `places.search_text` column (separate from the display `name`) for LIKE queries. See `Fts5SupportDetector.kt`.
- **`build_prefectures.py` invariants:**
  - Extract with `osmium extract --polygon <boundary> --strategy complete_ways`.
  - Call the importer with `--bbox none`.
  - Each ZIP holds exactly five files: `<name>.search.db`, `<name>.graph`, `<name>.graph.idx`, `manifest.json`, `LICENSE.txt`.
  - A valid ZIP is skipped unless `--force` is given.
  - Failed work directories are kept for debugging.
  - The integrated DB from `sandbox/data-tool/out/` is validated (integrity_check, required columns, indexes, row counts) before it is used.

## Do not touch

`data/japan-latest.osm.pbf` (about 2.5 GB; the root `japan-latest.osm.pbf` is a symlink to it), `work/` and `venv/` are large and must not be committed or moved.

## Releases

Build release APKs only from a clean HEAD (a `-dirty` APK is for dev checks only). Record the first 12 characters of the APK SHA-256 in `VERSIONS.md` in a separate commit. The debug keystore (`~/.android/debug.keystore`, with the Khadas Edge 2 copy as the original) is never committed. Builds can be offloaded to the Khadas Edge 2 SBC (see `AGENTS.md`).

## 引き継ぎメモ (2026-11-12 更新)

### 全体像
Google Maps に近い情報量をオフラインで実現する取り組みと、経路探索の高速化を進めている。経緯と数値はすべて `docs/away_session_report_20261108.md` (追記1〜17) にある。それ以降 (CH、通り口、全国対応) は `docs/ch_design.md`、`docs/cross_prefecture_design.md`、`docs/nationwide_design.md` と `git log` が正。作業ブランチは `auto/20261110` (main より先行、未マージ)。

### 現在の状態
- 18 項目の改善 (検索の並び順・別名・読み・住所、名所・史跡のカテゴリ、道路番号の標識、画面中心の地名、案内の道路名・車線、CI、refresh_data の回帰チェックなど) は commit 済み (`939d17a`, `0619363`)。実機で確認済み (追記11)。
- A* の高速化は **P0〜P3 完了** (追記13、計画は `docs/astar_optimization_plan.md`)。変更前後の突き合わせは 1000 行で完全一致、実機 (広島 約 40km) の表示までは 13.2 秒 → 1.1 秒。代替ルートは主経路の表示後に裏で計算する。
- **県またぎ経路** (追記15): 広島+岡山などのグラフを結合せず、共通ノードを通り口にして探索 (`CrossGraphRouting.kt`)。実機で井口→新岡山港 179.6km を 4.1 秒で表示。ナビ中、位置が県境を越えるとグラフは自動で切り替わり、経路・案内は継続する (実機確認済み、県境の連続走行は未確認)。経由地つき・3 県以上も対応済み (追記17、山口→広島→岡山 335.7km)。
- **Contraction Hierarchies** (2026-11-11、S1〜S4 完了): 同県は問い合わせ 約 25〜37ms、3 県は実機で約 3.0〜3.5 秒。結果は A* と同一。通り口の事前計算 (`.portals`) は JVM で確認済み、実機は未確認 (`docs/ch_design.md` 7 章)。
- **全国対応 (47 県)**: 設計は `docs/nationwide_design.md`。隣接表の 47 県化と整合テストまで済み。データ生成 (7-2) はディスク残量の都合でユーザーの許可待ち。
- データ: `packages/` (5 県の ZIP・タイル) と `sandbox/data-tool/out/` は Mac にだけある (git 管理外)。別の環境で作り直すなら `scripts/refresh_data.py` (OSM 取得〜昇格、約 1.5 時間)。実機 (Galaxy S26) には 2026-11-08 夜の最新データが転送済み。
- 実機の APK: 代替ルート後回し版 (2026-11-10 の debug ビルド、`ROUTE_TIMING firstDisplayMs` 入り) が入っている。

### Codex への委譲 (Claude が親、Codex が子)
- Claude が設計・タスク分割・検証を行い、実装とテストの実行を Codex に委譲する。指示文 (Markdown: 背景 / 触ってよいファイル / 守ること / 受け入れ条件 / 実行するテスト) を `work/codex/` に書き、`scripts/delegate_codex.sh <指示文>` で渡す (`--readonly` で読み取り専用)。出力は画面とログ (`work/codex/*.log`) に流れるので、Terminal パネルで進行を見られる。
- Codex は同じ作業ツリーで作業する。commit / push / adb / 履歴を壊す git 操作は禁止 (ラッパーの前置きで指示済み)。Gradle は動くようサンドボックスに network と `~/.gradle` の書き込みを許可している。
- Claude は返ってきた diff を必ず読み、テストを自分でも実行してから commit する。実機検証は Claude が必要と判断したときだけ行い (UI の出方・待ち時間・スレッド競合に関わる変更など)、Codex には触らせない。

### 次にやること (優先順)
1. (完了 2026-11-11) 探索の高速化は CH で実施済み。次は `docs/nationwide_design.md` の 4 章 (7-2 データ生成 → 7-3 アプリ側 → 7-4 検証)。全県の一括生成と `packages/_prev`・`work/` の整理は許可を得てから。
2. (解決済み 2026-11-10) Kotlin の CI の失敗は、`gradle-wrapper.jar` が `.gitignore` の `*.jar` で未追跡だったことと、`gradle.properties` の Mac 専用 `org.gradle.java.home` が原因。両方直して CI は緑。Mac のローカル JDK 指定は `~/.gradle/gradle.properties` に置く。
3. (解決済み 2026-11-10、`ae3149c`) 公園内からの reroute が「到達不能」になる件は、次に近い候補で引き直すようにした (`SnapFallbackRouting.kt`)。

### 検証の道具 (debug ビルドのみ)
- カメラ移動: `adb shell am broadcast -a com.gorite.cyclemap.test.CAMERA -p com.gorite.cyclemap.test --ed lat <緯度> --ed lon <経度> --ed zoom <z>`
- 模擬位置 (案内・リルートの確認。端末設定は変えない): `adb shell am broadcast -a com.gorite.cyclemap.test.SIMLOC -p com.gorite.cyclemap.test --ed lat <緯度> --ed lon <経度> [--ef speed 5] [--ef bearing 90]`
- 経路探索の結果の突き合わせ: `./gradlew :routing-core:routeDump -Pgraph=<.graph> -Pout=<file>`、ベンチ: `:routing-core:benchmarkRealGraph`、登り量: `:osm-importer:benchmarkAscent`、CH と A* の突き合わせ: `:routing-core:benchmarkCh -Pgraph=<.graph> -Pch=<.graph.ch>`、県またぎ: `:routing-core:benchmarkChainCh -Pgraphs=A.graph,B.graph -Pchs=A.graph.ch,B.graph.ch` (引数は `routing-core/build.gradle.kts` のコメント)
- **実機の操作の注意**: 戻るボタンで CycleMap が閉じると、ほかのアプリに入力が入る (2026-11-08 に X アプリへ誤操作した)。入力の前に `adb shell dumpsys window | grep mCurrentFocus` で CycleMap が前面か確かめる。日本語の検索語は Simeji のローマ字入力 (`adb shell input text sutaba` など) で入れる。

### 運用上の注意 (引き継ぎ先へ)
- **Git**: 本ファイル冒頭のとおり、commit/push の自律判断は Claude Code にだけ許可されている(2026-11-08)。別エージェントは変更後に報告して承認を待つこと。`push --force`・履歴の書き換え・ブランチ削除は誰であっても事前承認が必要。ユーザーは別途「破壊しない限りほぼ全ての操作を許可」と述べているが、これも Claude Code への許可。
- 実機は **Galaxy S26**(SC-51G、Android 17)。`AGENTS.md` などに残る S21 の記述は過去の検証記録。端末はロック・画面消灯で `adb screencap` が真っ黒になる。短い `adb input tap` はアプリの長押し式ズームボタンに届かないので、`scripts/capture_baseline.sh`(debug ビルド専用の `CameraDebugReceiver` でカメラを動かす)を使う。
- 他社地図(Google Maps)のスクリーンショットはリポジトリに入れない(Public リポジトリ)。比較は `docs/offline_map_baseline.md` に文字で記録。
- スタイルの CycleMap 独自調整は `scripts/patch_map_style.py`(冪等)が正。スタイルを作り直したら再実行する。色の定義を変えたら `VectorMapThemeHelper.vectorMapAreaFillColors` も同値にそろえる(`StyleJsonTest` が検証)。
- タイルは `scripts/build_tiles.py`(Protomaps 公開ビルドを `pmtiles extract`)、地形は `scripts/build_terrain.py`(GSI 標高タイル)。`osmdata.openstreetmap.de` は到達できず、planetiler による自前生成は断念した。
