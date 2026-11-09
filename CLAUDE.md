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
./gradlew :app:assembleDebug                     # APK
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
./scripts/push_data.sh --dry-run                                      # then without the flag: adb push + SHA-256 verification (--tiles-only / --db-only)
```

Toolchain: AGP 8.10.1, Kotlin 2.0.21, compileSdk/target 35, minSdk 33, Java 11 target. On Windows use Android Studio's bundled JDK 21 (system Java 25 is incompatible with Gradle 8.11.1). `application/local.properties` (`sdk.dir`) is machine-specific and not committed.

## Architecture

- **Routing data and display data are strictly separate.** Routing uses the custom `.graph` + `.graph.idx` binary built from OSM. Display uses GSI tiles / MapLibre PMTiles. Never mix raster tile data into the graph. Keep the OSM and GSI attribution visible, even offline.
- **`:routing-core`** is pure JVM. Production routing is A\* only (`AStarRouter` + `CyclingCostModel`). `HsaRouting.kt` and `AltRouting.kt` are experimental. Graphs are read through mmap (`MappedRouting.kt`, `LazyMappedRouting.kt`).
  - `CyclingCostModel` excludes `motorway`. Its minimum multiplier of 0.90 is the A\* heuristic lower bound, so keep the JVM implementation and `LazyMappedRoadGraph` in sync.
  - Limits: `LazyMappedRouting` reads with `Int` offsets, so `load` rejects graph files over 2 GiB with an explicit error (the per-prefecture graphs fit). `nearestNodeIndex` uses a spatial grid and falls back to a linear scan only when the grid finds nothing. Check `Archives/application/bugs.md` and `git -C application log` before "fixing" an old bug, because most Critical/High items are already fixed.
- **`:app`** (package `com.gorite.cyclemap`) uses MVI/UDF with Compose. `MainActivity.kt` is the entry point. The `contract/` package defines the interfaces (`RoutePlanner`, `ReroutePolicy`, `PlaceDetailProvider`, `LaneGuidanceProvider`) and models. The `real/` package holds the implementations (`RealRoutePlanner`, etc.). Other packages: `navigation`, `search`, `tracking`, `weather` (JMA), `speech`, `power`, `net`.
- **Data is never bundled in the APK.** The app reads `getExternalFilesDir(DOCUMENTS)/CycleMap/` (`<name>.graph`, `<name>.graph.idx`, `<name>.search.db`, `<name>.pmtiles`, `tiles/cache.db`). The vector tiles are a separate artifact from the 5-file ZIP, produced by `scripts/build_tiles.py` from the Protomaps public build. `RoutingGraphSelector` and `SearchDbSelector` (in `Prefectures.kt`) pick the first file by name at startup, then switch prefecture automatically once the current location is known.
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

## 引き継ぎメモ (2026-11-09 時点)

### 全体像
Google Maps に近い情報量をオフラインで実現する取り組みと、経路探索の高速化を進めている。経緯と数値はすべて `docs/away_session_report_20261108.md` (追記1〜12) にある。まずその **追記12 の「未完了」** を読むこと。

### 現在の状態
- 18 項目の改善 (検索の並び順・別名・読み・住所、名所・史跡のカテゴリ、道路番号の標識、画面中心の地名、案内の道路名・車線、CI、refresh_data の回帰チェックなど) は commit 済み (`939d17a`, `0619363`)。実機で確認済み (追記11)。
- A* の高速化は **P0〜P3 完了** (追記13、計画は `docs/astar_optimization_plan.md`)。変更前後の突き合わせは 1000 行で完全一致、実機 (広島 約 40km) の表示までは 13.2 秒 → 1.1 秒。代替ルートは主経路の表示後に裏で計算する。
- データ: `packages/` (5 県の ZIP・タイル) と `sandbox/data-tool/out/` は Mac にだけある (git 管理外)。別の環境で作り直すなら `scripts/refresh_data.py` (OSM 取得〜昇格、約 1.5 時間)。実機 (Galaxy S26) には 2026-11-08 夜の最新データが転送済み。
- 実機の APK: 代替ルート後回し版 (2026-11-10 の debug ビルド、`ROUTE_TIMING firstDisplayMs` 入り) が入っている。

### Codex への委譲 (Claude が親、Codex が子)
- Claude が設計・タスク分割・検証を行い、実装とテストの実行を Codex に委譲する。指示文 (Markdown: 背景 / 触ってよいファイル / 守ること / 受け入れ条件 / 実行するテスト) を `work/codex/` に書き、`scripts/delegate_codex.sh <指示文>` で渡す (`--readonly` で読み取り専用)。出力は画面とログ (`work/codex/*.log`) に流れるので、Terminal パネルで進行を見られる。
- Codex は同じ作業ツリーで作業する。commit / push / adb / 履歴を壊す git 操作は禁止 (ラッパーの前置きで指示済み)。Gradle は動くようサンドボックスに network と `~/.gradle` の書き込みを許可している。
- Claude は返ってきた diff を必ず読み、テストを自分でも実行してから commit する。実機検証は Claude が必要と判断したときだけ行い (UI の出方・待ち時間・スレッド競合に関わる変更など)、Codex には触らせない。

### 次にやること (優先順)
1. 探索がまだ必要なら、計画の P4 (双方向 A*) → P5 (Contraction Hierarchies、おすすめの方針 1 つ)。HSA* は止める。
2. Kotlin の CI の失敗原因 (GitHub のログ)。data-tool と scripts のジョブは通っている。
3. 公園内からの reroute が「到達不能」になる件 (最寄りノードが孤立した道に当たると推測)。

### 検証の道具 (debug ビルドのみ)
- カメラ移動: `adb shell am broadcast -a com.gorite.cyclemap.test.CAMERA -p com.gorite.cyclemap.test --ed lat <緯度> --ed lon <経度> --ed zoom <z>`
- 模擬位置 (案内・リルートの確認。端末設定は変えない): `adb shell am broadcast -a com.gorite.cyclemap.test.SIMLOC -p com.gorite.cyclemap.test --ed lat <緯度> --ed lon <経度> [--ef speed 5] [--ef bearing 90]`
- 経路探索の結果の突き合わせ: `./gradlew :routing-core:routeDump -Pgraph=<.graph> -Pout=<file>`、ベンチ: `:routing-core:benchmarkRealGraph`、登り量: `:osm-importer:benchmarkAscent`
- **実機の操作の注意**: 戻るボタンで CycleMap が閉じると、ほかのアプリに入力が入る (2026-11-08 に X アプリへ誤操作した)。入力の前に `adb shell dumpsys window | grep mCurrentFocus` で CycleMap が前面か確かめる。日本語の検索語は Simeji のローマ字入力 (`adb shell input text sutaba` など) で入れる。

### 運用上の注意 (引き継ぎ先へ)
- **Git**: 本ファイル冒頭のとおり、commit/push の自律判断は Claude Code にだけ許可されている(2026-11-08)。別エージェントは変更後に報告して承認を待つこと。`push --force`・履歴の書き換え・ブランチ削除は誰であっても事前承認が必要。ユーザーは別途「破壊しない限りほぼ全ての操作を許可」と述べているが、これも Claude Code への許可。
- 実機は **Galaxy S26**(SC-51G、Android 17)。`AGENTS.md` などに残る S21 の記述は過去の検証記録。端末はロック・画面消灯で `adb screencap` が真っ黒になる。短い `adb input tap` はアプリの長押し式ズームボタンに届かないので、`scripts/capture_baseline.sh`(debug ビルド専用の `CameraDebugReceiver` でカメラを動かす)を使う。
- 他社地図(Google Maps)のスクリーンショットはリポジトリに入れない(Public リポジトリ)。比較は `docs/offline_map_baseline.md` に文字で記録。
- スタイルの CycleMap 独自調整は `scripts/patch_map_style.py`(冪等)が正。スタイルを作り直したら再実行する。色の定義を変えたら `VectorMapThemeHelper.vectorMapAreaFillColors` も同値にそろえる(`StyleJsonTest` が検証)。
- タイルは `scripts/build_tiles.py`(Protomaps 公開ビルドを `pmtiles extract`)、地形は `scripts/build_terrain.py`(GSI 標高タイル)。`osmdata.openstreetmap.de` は到達できず、planetiler による自前生成は断念した。
