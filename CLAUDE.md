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
scripts/data_studio.cmd                                              # GUI (Streamlit): pick prefectures + steps (graph/tiles/terrain/CH/portals/push), runs them in parallel within CPU/RAM budgets (docs/data_studio.md)
python3 scripts/build_prefecture_polygons.py                          # boundaries/*.geojson -> app/src/main/resources/prefecture_polygons/ (47-prefecture polygons for prefecture detection; --check to verify)
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

## Windows での開発

- リポジトリは **ASCII パス** (`C:\dev\cycle_map`) に置く。日本語を含むパスだと、Gradle がテスト用クラスパスの一時ファイルを ANSI で読み違え、全テストが「クラスが見つからない」で落ちる (ジャンクションでは回避できない)。
- JDK は Android Studio 同梱の 21 を使う (システムの Java 25 は Gradle 8.11.1 と非互換)。
- `osm-importer` の標準出力は Windows では Shift-JIS になり得る。Python 側で読むときは `errors="replace"` を付ける (`run_live()`)。
- Windows/Mac の差 (gradlew.bat、pmtiles/osmium・JDK の探索) は `scripts/_platform.py` に集約済み。
- Windows のサンドボックスでは `pmtiles.exe` (`sandbox/vector_test/pipeline/`) は`.gitignore` 済みのバイナリ。

## 三者の合同作業 (Claude が親、Codex と Antigravity が子)

2026-11-15 から Claude (Opus 5.5)・Codex (GPT-6.1 Sol)・Antigravity (Gemini 4 Hydrogen) の三者で作業する。Claude が親で、子には CLI で指示を出す。

- **Claude**: 設計・分割・検証・commit/push・実機検証。子の diff を読み、テストを自分でも実行してから commit する。
- **Codex**: 実装とテスト実行 (主担当)。`scripts/delegate_codex.sh`。
- **Antigravity**: 別の目でのレビュー・調査、触るファイルが Codex と重ならない作業の並行実装。`scripts/delegate_agy.sh [--readonly] [--model <id>] <指示文>` (既定モデル `gemini-4-hydrogen-high`、`--readonly` は plan モード、ログは `work/agy/*.log`)。無人で動かすため `--dangerously-skip-permissions` で全コマンドを許可している (2026-11-15 Gorite 承認)。禁止事項は前置きの文でしか伝わらないので、終わったら `git status` と diff を必ず確かめる。
- 二者に同時に書かせるときは、触ってよいファイルを指示文で分け、重ならないようにする。

### Mac での分担と受け渡し (2026-11-23 から)

Mac で作業するときは、次の四者で分担する。

- **Claude**: 設計・タスク分割・指示文の作成・検証・commit/push。
- **Antigravity** (Orchestrator、Gemini 4 Neon): バックエンドの実装。`:routing-core`、`:osm-importer`、Python のデータ生成 (`scripts/`、`sandbox/data-tool/`)。
- **Codex** (GPT-6.2 Sol): フロントエンドの実装。`:app` の `ui/` と Compose の画面。
- **OpenCode** (DeepSeek V4.1 Flash Free): テストの追加・実行と、失敗の切り分け。

受け渡しの手順:

- **通常は Gorite が渡す。** Claude は指示文 (背景 / 触ってよいファイル / 守ること / 受け入れ条件 / 実行するテスト) を `work/<宛先>/` に Markdown で書き、宛先とパスを伝えて止まる。Gorite がそのパスを各エージェントに渡す。
- **CLI (`scripts/delegate_*.sh`) で Claude が直接起動するのは、Gorite が「自律的に進めて」と頼んだときだけ。**
- `:app` の `navigation/`・`search/`・`tracking/` のように、バックエンドとフロントエンドの境目が曖昧なものは、指示文ごとに宛先を明記する。
- 同時に複数のエージェントが動くときは、触ってよいファイルを指示文で分けて重ならないようにする。
- 返ってきた diff は Claude が読み、テストも自分で実行してから commit する。OpenCode の「全件成功」という報告だけで合格にしない。
- OpenCode の無料モデルは、送った内容が提供元の学習に使われ得る。非公開の情報 (端末・ネットワークの情報、本名入りのパスなど) を含むファイルは渡さない。

### Codex への委譲

Claude が設計・分割・検証を行い、実装とテスト実行を Codex に委譲する。指示文 (背景 / 触ってよいファイル / 守ること / 受け入れ条件 / 実行するテスト) を `work/codex/` に書き、`scripts/delegate_codex.sh <指示文>` で渡す (`--readonly` で読み取り専用、ログは `work/codex/*.log`)。Codex は同じ作業ツリーで動くが commit / push / adb / 履歴を壊す git 操作は禁止。Claude は返ってきた diff を読み、テストを自分でも実行してから commit する。実機検証は Claude が必要と判断したときだけ行う。

## 検証の道具 (debug ビルドのみ)
- **Windows の debug 鍵は端末の既存アプリと署名が合わない** (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`)。アンインストールすると端末のデータが消えるので、`./gradlew :app:assembleDebug -Pcyclemap.appIdSuffix=.win` で別パッケージ (`com.gorite.cyclemap.test.win`) として入れ、`adb shell cp` で端末内の `.test` のデータ置き場からコピーする。回帰は `CYCLEMAP_PACKAGE_NAME=com.gorite.cyclemap.test.win scripts/device_regression.sh` (検証用ブロードキャストの action 名は `com.gorite.cyclemap.test.*` のまま)。新規インストールでは位置情報の権限が無く SIMLOC が効かないので、`adb shell pm grant <pkg> android.permission.ACCESS_FINE_LOCATION` で付ける。Git Bash で adb のパスを渡すときは `MSYS_NO_PATHCONV=1`、Gradle の JAVA_HOME は Windows 形式 (`C:\Program Files\...`) で渡す (`MSYS_NO_PATHCONV=1` と併用しない)。

- カメラ移動: `adb shell am broadcast -a com.gorite.cyclemap.test.CAMERA -p com.gorite.cyclemap.test --ed lat <緯度> --ed lon <経度> --ed zoom <z>`
- 模擬位置 (案内・リルート確認。端末設定は変えない): `adb shell am broadcast -a com.gorite.cyclemap.test.SIMLOC -p com.gorite.cyclemap.test --ed lat <緯度> --ed lon <経度> [--ef speed 5] [--ef bearing 90]`
- 経路結果の突き合わせ: `./gradlew :routing-core:routeDump -Pgraph=<.graph> -Pout=<file>`。ベンチ: `:routing-core:benchmarkRealGraph`、`:osm-importer:benchmarkAscent`、`:routing-core:benchmarkCh -Pgraph=<.graph> -Pch=<.graph.ch>`、`:routing-core:benchmarkChainCh -Pgraphs=A.graph,B.graph -Pchs=A.graph.ch,B.graph.ch` (引数は `routing-core/build.gradle.kts` のコメント)。
- 実機は Galaxy S26。画面消灯で `adb screencap` が真っ黒になる。短い `adb input tap` は長押し式ズームボタンに届かないので `scripts/capture_baseline.sh` を使う。入力前に `adb shell dumpsys window | grep mCurrentFocus` で CycleMap が前面か確認する (戻るボタンで閉じると他アプリに入力が入る)。日本語検索語は Simeji のローマ字入力で入れる。

## 運用メモ

- 他社地図のスクリーンショットはリポジトリに入れない (Public)。比較は `docs/offline_map_baseline.md` に文字で記録。
- スタイルの独自調整は `scripts/patch_map_style.py` (冪等) が正。色の定義を変えたら `VectorMapThemeHelper.vectorMapAreaFillColors` も同値にそろえる (`StyleJsonTest` が検証)。
- タイルは `scripts/build_tiles.py`、地形は `scripts/build_terrain.py`。planetiler による自前生成は断念した。

## 経緯・引き継ぎ

日付つきの経過 (全国対応の試験生成、見つけたバグ、未解決事項、次にやること) は [docs/handoff_20261113.md](docs/handoff_20261113.md)。 2026-11-20 の自律作業 (新機能・修正・実機で確認してほしいこと・未修正の棚卸し) は [docs/session_20261120.md](docs/session_20261120.md)。設計は `docs/ch_design.md`、`docs/cross_prefecture_design.md`、`docs/nationwide_design.md`、数値は `docs/away_session_report_20261108.md`。

2026-11-14 に、端末・自宅ネットワークの情報を含む検証記録を履歴から取り除いた。`3b6055d` 以降のコミットはハッシュが変わっている (新旧の対応と、別環境の作業コピーの取り直し方は [docs/history_rewrite_20261114.md](docs/history_rewrite_20261114.md))。`adb devices`・`dumpsys connectivity`/`wifi`・logcat の丸ごとの出力はリポジトリに入れない。

公開用リポジトリ `Gorite-programming/cycle_map_public` は、この (非公開の) 開発リポジトリから `scripts/publish_public.sh --push` で作る。Google マップの画像・20MB 超のファイル・本名入りのパス・端末のシリアル・作者のメールを除いた履歴で、コミットのハッシュは開発側と違う (対応は [docs/public_repository_20261114.md](docs/public_repository_20261114.md))。公開側には直接コミットしない。
