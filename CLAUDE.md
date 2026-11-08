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

## 引き継ぎメモ (2026-11-08 時点、Claude Code から別エージェントへ)

### 全体像
Google Maps に近い情報量をオフラインで実現する取り組みを段階的に進めている。Stage 0〜3、土地利用の塗り分け、地形(等高線・陰影起伏)、アイコン仕上げは完了し、**α0.9.8-unified**(versionCode 10、APK SHA-256 先頭12桁 `0e89f67763c9`)として実機(Galaxy S26 / SC-51G、adb `RFGL626QR3N`)に入っている。台帳は `VERSIONS.md`、現状と効果の記録は `docs/offline_map_baseline.md`。

### 進行中の作業 (「外出中に進める大規模改善」A/B/C/D)
外出のため端末が手元に無い前提で、JVM・Python・データだけで検証できる範囲に限って進めていた。ユーザーの指示で途中停止している。

| 区分 | 内容 | 状態 |
|---|---|---|
| **A** 場所の詳細情報 | 検索DBに営業時間・電話・Web・車椅子を追加。データ生成(`sandbox/data-tool`)と営業時間パーサ(`OpeningHoursParser`)を強化 | **コミット・プッシュ済み**(`3a6fd13`, `ba4bc9d`)。ただし **5県の検索DBは未再生成**(下の D で一緒に作る) |
| **D** データ更新の一括化 | `scripts/refresh_data.py`(最新 OSM 取得 → 検索DB → graph → タイルをステージングに作り、検証を通した県だけ `packages/` に昇格) | **スクリプトは作成済みで未コミット。本実行は一度もしていない**(`--dry-run` のみ確認) |
| **B** 品質基盤 | CI(`.github/workflows/ci.yml`)、`scripts/check_all.sh`、未参照コードの除去、`VectorMapView.kt` の純粋ロジック抽出+テスト | 未着手 |
| **C** ルーティング品質 | 実グラフでのベンチ基盤(`routing-core`)、フラグ付きのコストモデル試作、比較表 | 未着手 |

### 未コミットの変更 (ワークツリーに残っている)
- `build_prefectures.py`: `--source-pbf` / `--output-dir` / `--work-dir` を追加(ステージング用。既定の動作は不変)
- `scripts/build_tiles.py`: `--out` を追加
- `scripts/refresh_data.py`: 新規(下記)
- `CLAUDE.md`: この引き継ぎメモ

### A で入れたもの (コミット済み)
- `sandbox/data-tool/tools/core/contact.py`: 電話(`phonenumbers` で国内表記 `082-208-2822` に整形)・Web(http/https)・営業時間(空白整形のみ)・車椅子の正規化。依存は `requirements.txt` に追記済み(`venv/bin/pip install phonenumbers` 済み)。
- OSM の `opening_hours` / `phone` / `contact:phone` / `website` / `contact:website` / `wheelchair` を取り込み、Overture の `phones[1]` / `websites[1]` を補完に使う。`master.db` の `poi` に `hours/phone/website/wheelchair` を追加(旧DBは `ALTER` で移行)、統合時は勝者の空欄を敗者から補完。
- 検索DBの `places` に `opening_hours, phone, website, wheelchair` を追加、`meta.schema_version` は `"2"`。`search_text` は不変(FTS5 非対応端末の LIKE 検索を壊さない)。アプリ側(`real/RealPlaceDetailProvider.kt`)は列があれば読む作りで、旧DBにも対応済み。
- `OpeningHoursParser`: 後ろの規則が前を上書きする OSM の意味、規則間のカンマ、`PH10:30-23:00`、25時表記(`18:00-27:30`)、全角・ダッシュ正規化に対応。月・第n曜日・日の出などを含む文字列は誤判定を避けて `Unknown`。実データ(5県 4,550件・1,124種)の対応率は 97.8%。テストは `OpeningHoursRealWorldTest` と `src/test/resources/opening_hours_real_world.txt`。
- 取れ高(広島、名称付き約7.3万件): 営業時間 約3,100 / 電話 約1,400 / Web 約1,200 / 車椅子 約800。Overture(広島市中心 28,631件)は電話 77%・Web 61% なので、電話・Web は主に Overture から入る。営業時間は OSM のみ。
- テスト: `cd sandbox/data-tool && ../../venv/bin/python -m pytest` は 107 件全通過。Kotlin は `OpeningHours*` のみ再実行済み(全体の `./gradlew test` は α0.9.8 のリリース時に全通過、その後のパーサ変更後は未実行)。

### D: `scripts/refresh_data.py` の設計と注意
流れ: 事前確認 → 現行成果物を `packages/_prev/<日時>/` に退避 → Geofabrik の `chugoku-latest.osm.pbf`(到達確認済み。2026-11-07 版、手元の `data/japan-latest.osm.pbf` は 2026-09-19 時点)を取得 → `rebuild_all.py --extract-from` → `build_prefectures.py` をステージング(`work/refresh/<日時>/packages`)へ → `build_tiles.py --out` → 県ごとの検証(検索DBの整合と前回比 0.8〜1.3 倍の件数、ZIP の5点構成、graph サイズ前回比 0.7〜1.4 倍、タイルの `pmtiles verify`)→ 通った県だけ昇格、通らない県は検索DBも退避から戻す → `packages/data_manifest.json` と `report.md`。実機転送はしない。
- 実行は `venv/bin/python scripts/refresh_data.py`(`--dry-run`、`--only`、`--skip download,search,graph,tiles`、`--overture-release` あり)。
- **重い処理は同時に走らせない**: Mac は RAM 8GB。`osm-importer` が `-Xmx5g`〜6g を使う間は Gradle を回さない。ディスク空きは約53GB。
- 経路回帰チェック(C のベンチを使う)は未実装のフックのみ。
- 初回の本実行は未実施。`rebuild_all.py` が `sandbox/data-tool/out/*.search.db` と `master.db` を書き換える(自動バックアップと `_prev` への退避あり)。

### 残りの予定 (元の計画。詳細は `~/.claude/plans/googlemap-swirling-aurora.md` だが、リポジトリ外なのでここに要点を書く)
1. **D の本実行**(バックグラウンド、約1〜1.5時間)。完了後に `report.md` を確認し、営業時間・電話・Web の充足率を記録。
2. **B**: `.github/workflows/ci.yml`(JDK 21 の `./gradlew test`、data-tool の pytest、`python3 scripts/patch_map_style.py` 後の `git diff --exit-code`、`bash -n scripts/*.sh`)と、同じ手順の `scripts/check_all.sh`。未参照コードの除去(参照集計で `ui/cycling/TopSearchBar.kt` は参照0、`real/RealReroutePolicy.kt` はテストからのみ参照、`ui/cycling/CyclingPanels.kt` など)は、1ファイルずつ `:app:compileDebugKotlin` とテストで確認してから。`HsaRouting.kt` / `AltRouting.kt` は `MapRoutingActions.kt` から参照があるので触らない。`VectorMapView.kt` の判断ロジック(追従カメラの再センタリング、POI 取得範囲、タイル切替)を `VectorMapLogic.kt` に挙動不変で抽出しテストを付ける。
3. **C**: `RoutingBenchmark.kt` の型を使う実行ランナー(`packages/<県>.zip` の graph を `LazyMappedRoadGraph` で読み、固定シードで OD 200組)。コストモデルの改善はフラグ付きの試作に留め、既定は現行のまま。`CyclingCostModel.minimumCostMultiplier()=0.90` の下限(A\* ヒューリスティックの許容性)を必ず保つ。
4. 最終報告を `docs/away_session_report_<日付>.md` に書き、commit/push。

### 戻ってから人間が行うこと
実機を接続して `scripts/push_data.sh` で新しい検索DB・タイルを転送し、場所の詳細に営業時間・電話・Web が出ることを確認。APK をクリーン HEAD からビルドしてインストールし、`VERSIONS.md` に SHA-256 を記録。

### 運用上の注意 (引き継ぎ先へ)
- **Git**: 本ファイル冒頭のとおり、commit/push の自律判断は Claude Code にだけ許可されている(2026-11-08)。別エージェントは変更後に報告して承認を待つこと。`push --force`・履歴の書き換え・ブランチ削除は誰であっても事前承認が必要。ユーザーは別途「破壊しない限りほぼ全ての操作を許可」と述べているが、これも Claude Code への許可。
- 実機は **Galaxy S26**(SC-51G、Android 17)。`AGENTS.md` などに残る S21 の記述は過去の検証記録。端末はロック・画面消灯で `adb screencap` が真っ黒になる。短い `adb input tap` はアプリの長押し式ズームボタンに届かないので、`scripts/capture_baseline.sh`(debug ビルド専用の `CameraDebugReceiver` でカメラを動かす)を使う。
- 他社地図(Google Maps)のスクリーンショットはリポジトリに入れない(Public リポジトリ)。比較は `docs/offline_map_baseline.md` に文字で記録。
- スタイルの CycleMap 独自調整は `scripts/patch_map_style.py`(冪等)が正。スタイルを作り直したら再実行する。色の定義を変えたら `VectorMapThemeHelper.vectorMapAreaFillColors` も同値にそろえる(`StyleJsonTest` が検証)。
- タイルは `scripts/build_tiles.py`(Protomaps 公開ビルドを `pmtiles extract`)、地形は `scripts/build_terrain.py`(GSI 標高タイル)。`osmdata.openstreetmap.de` は到達できず、planetiler による自前生成は断念した。
