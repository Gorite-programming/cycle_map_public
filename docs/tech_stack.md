# 技術スタック (2026-11-14 時点)

全国対応を進める前の整理。リポジトリの実ファイル (`libs.versions.toml`、各 `build.gradle.kts`、`scripts/`、CI) から拾った事実のみを書く。設計の理由は各設計書 (`docs/*.md`) を参照。

## 1. 全体像

完全オフラインの Android 自転車ナビ。データは PC で作り、`adb push` かアプリの取り込みで端末に置く (APK に同梱しない)。

| 層 | 場所 | 役割 |
|---|---|---|
| アプリ | `application/app` | UI・地図表示・ナビ・検索・音声案内・天気 |
| 経路エンジン | `application/routing-core` | 純 JVM。A\*、Contraction Hierarchies (CH)、県またぎ |
| 取り込み | `application/osm-importer` | OSM PBF → 独自グラフ (`.graph` / `.graph.idx`) と検索 DB |
| データ生成 | ルート直下 `build_prefectures.py`、`scripts/` | 県ごとの ZIP・タイル・地形・CH・通り口を作る (Python) |
| 検索 DB ツール | `sandbox/data-tool` | Overture + 国土数値情報 + OSM を統合した検索 DB (Python / Streamlit) |

規模 (行数): Kotlin はアプリ約 4.0 万、routing-core 約 0.8 万、osm-importer 約 0.2 万。Python 約 0.9 万。

## 2. アプリ (`:app`、パッケージ `com.gorite.cyclemap`)

- 言語・ビルド: Kotlin 2.0.21、AGP 8.10.1、Gradle 8.11.1、compileSdk / targetSdk 35、minSdk 33、Java 11 ターゲット。ビルドは JDK 21 (Android Studio 同梱。システムの Java 25 は Gradle 8.11.1 と非互換)。
- UI: Jetpack Compose (BOM 2024.09.00)、Material 3、Activity Compose 1.10.0、Lifecycle 2.8.7。構造は MVI / UDF。
- 地図: **MapLibre Native Android 11.8.0** (ベクター地図の本線)。スタイルは Protomaps Basemap 系 (`assets/styles/protomaps_*.json`)。独自調整は `scripts/patch_map_style.py` (冪等) が正。`osmdroid 6.1.20` は標準地図 (地理院・OSM のラスター) 用に残っている。
- 位置: Google Play Services Location 21.3.0。
- そのほか: 音声案内 (`speech`、Android TextToSpeech)、天気 (`weather`、気象庁の区域データを `assets/jma` に持つ)、GPX 記録 (`tracking`)、電力 (`power`)。標高は国土地理院の DEM タイル。
- 構成: `contract/` にインターフェース (`RoutePlanner`、`ReroutePolicy` など)、`real/` に実装、`navigation/` (多県経路の計画 `MultiLegRoutePlanner` など)、`search/`、`data/` (県定義・データ選択)、`ui/`。
- ビルド種別: `debug` (applicationId `com.gorite.cyclemap.test`)、`field` (`.field` の別 ID)、`release`。`-Pcyclemap.appIdSuffix=.win` で debug を別パッケージにできる (署名鍵の違う既存アプリと共存)。検証用ブロードキャスト (`CAMERA` / `SIMLOC` / `VERIFY`) は debug のみ。

## 3. 経路エンジン (`:routing-core`)

- 純 JVM (Android 非依存)。テストは `kotlin("test")`。
- **独自バイナリグラフ**: `<名前>.graph` + `<名前>.graph.idx`。実行時は mmap で遅延読み込み (`LazyMappedRouting.kt`)。`Int` オフセットのため 1 ファイル 2GiB まで。
- 探索: A\* (`AStarRouter` + `CyclingCostModel`)。コスト (道路種別の係数、勾配の罰則、最小係数 0.90) は `BicycleCost.kt` の一箇所。
- **CH** (`<名前>.graph.ch`、任意): おすすめ × 自転車のときだけ使う。グラフの指紋 (サイズ + 先頭 / 末尾 1MB の CRC) が合わなければ A\* に戻る。結果は A\* と同一であること。
- **県またぎ**: グラフは結合せず、共有する OSM ノード (通り口) でつなぐ。2 県 (`CrossGraphRouting`)、3 県以上 (`CrossChainRouting`、CH 版 `CrossChainChRouting`)。通り口の事前計算 `<A>__<B>.portals` は任意。
- 補助: `SnapFallbackRouting` (孤立した道に当たったら次の候補で引き直す。県またぎの端点は `nearestWellConnectedNodeIndex`)。
- 計測用の CLI (`benchmarkCh`、`benchmarkChainCh`、`routeDump`、`buildPortals` など) が `build.gradle.kts` にある。

## 4. データ生成 (Python と取り込み)

- 取り込み: `:osm-importer` (osmosis-pbf 0.48.3、sqlite-jdbc 3.46.1.0) → `.graph` / `.graph.idx`、検索 DB。
- `build_prefectures.py`: `osmium extract --polygon <県境> --strategy complete_ways` (複数県は `-c` で一括) → 取り込み → ZIP (5 ファイル: `.search.db` / `.graph` / `.graph.idx` / `manifest.json` / `LICENSE.txt`)。
- `scripts/`: `build_tiles.py` (Protomaps 公開ビルドから `pmtiles extract`)、`build_terrain.py` (地理院 DEM → 等高線・陰影 PMTiles)、`build_ch.py`、`build_portals.py`、`refresh_data.py` (全体更新、約 1.5 時間)、`push_data.sh` (adb 転送 + SHA-256 検証)、`device_regression.sh` (実機の経路回帰)。47 県の定義は `scripts/prefectures.py` に一本化。Windows / Mac の差は `scripts/_platform.py`。
- 検索 DB ツール `sandbox/data-tool`: `osmium` (PyOsmium)、`duckdb` (Overture Maps)、`streamlit`、`pykakasi` (読み)、`phonenumbers`、`pytest`。検索 DB は SQLite で、FTS5 が無い端末向けに `places.search_text` の LIKE 検索でも動く。
- 外部ツール: `osmium` CLI、`pmtiles` CLI。

## 5. データ形式と置き場

端末の `Documents/CycleMap/` (`getExternalFilesDir`) に置く。無くても動く任意物は CH・通り口・地形。

| ファイル | 内容 |
|---|---|
| `<Romaji>.graph` / `.graph.idx` | 経路グラフ (山口だけ既配備の `yamaguchi.graph` も解決) |
| `<Romaji>.search.db` | 施設検索 (SQLite) |
| `<id>.pmtiles` | ベクター地図 (Protomaps、県の外接矩形で切り出し) |
| `<Romaji>.graph.ch` | CH サイドカー |
| `<A>__<B>.portals` | 県またぎの通り口 |
| `<id>.contours.pmtiles` / `<id>.hillshade.pmtiles` | 等高線・陰影 |
| `tiles/cache.db` | 旧ラスタータイルのキャッシュ |

県の選択: アプリは現在地 (または地図の中心) から県を判定して、その県のファイルを使う。県の表は `PrefectureData.ALL` (47 県) から作る。

## 6. データソースとライセンス

- OpenStreetMap (ODbL): 経路グラフ・検索・地図の元。表示は常に出す (オフラインでも)。
- Protomaps Basemap: ベクター地図の元 (OSM 派生)。低ズーム (z5 以下) は粗い陸地ポリゴンのみで、道路・地名は z6 以上。
- 国土地理院: 標高 DEM、地理院地図 (ラスター)。
- Overture Maps / 国土数値情報 (MLIT): 検索 DB の補完 (医療機関・学校など)。
- 気象庁: 天気。
- 他社地図のスクリーンショットはリポジトリに入れない (公開リポジトリのため)。

## 7. 品質保証

- JVM: `./gradlew test` (JUnit 4.13.2 / kotlin.test)。`:app:testDebugUnitTest`、`:routing-core:test`、`:osm-importer:test`。
- Python: `sandbox/data-tool` と `scripts/tests` を pytest。
- CI (GitHub Actions、`.github/workflows/ci.yml`): Linux の Java 21 で Gradle テスト、Python 3.12 で pytest・スタイルパッチの冪等性・シェル / Python の構文確認。**main への push と PR のみ**で、作業ブランチへの push は対象外。
- ローカルの一括確認: `scripts/check_all.sh`。
- 実機: Galaxy S26 (Android 17)。経路回帰は `scripts/device_regression.sh`。

## 8. 開発環境と運用

- 開発機: Windows (`C:\dev\cycle_map`、ASCII パス必須) と Mac。GitHub で同期。ビルドの一部は Khadas Edge 2 (SBC) に逃がせる。
- リポジトリ: 非公開の開発リポジトリ + 公開用 `cycle_map_public` (`scripts/publish_public.sh` で履歴を作り直して公開。公開側には直接コミットしない)。
- AI 運用: `AGENTS.md` が共通の正。Claude Code は通常の commit / push を自律判断できる (破壊的操作は都度承認)。実装とテストの一部は `scripts/delegate_codex.sh` で Codex に委譲できる。
- 触らないもの: `data/japan-latest.osm.pbf` (約 2.5GB)、`work/`、`venv/`、`Archives/`。

## 9. 全国対応の観点で効いてくる点

実測と設計書から。

1. **ヒープ**: Android のヒープ上限は 512MB。多県チェーンは CH の作業配列がヒープを占めていたため遅延確保・段ごとの解放に直した (`865cdfa`)。兵庫を含む 4 県が通る。5 県以上、北海道・東京などの大きい県は未計測。
2. **容量**: 全国で約 12〜16GB (`docs/nationwide_design.md`)。端末側のデータ管理画面と配信方法は未決定。
3. **地図の低ズーム**: ズーム 6 未満は粗いポリゴン。ルートプレビューは 6 で止める (`ded7ac8`)。
4. **県境**: 県判定は外接矩形 + ポリゴン (現状 8 県分のみ)。47 県のポリゴンは未対応。
5. **県またぎの端点**: 孤立した道に当たると失敗していた (修正済み)。通り口は無くても動くが、事前計算があると速い。
6. **離島・広域県**: 北海道・沖縄・東京 (離島) は試験生成が未実施。タイル取得の分割が要る。
7. **CI の死角**: 作業ブランチへの push は CI の対象外。
8. **Windows の署名**: debug 鍵が端末の既存アプリと合わない。`.win` の別パッケージで検証する。
