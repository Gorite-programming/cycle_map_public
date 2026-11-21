# CycleMap α — 完全オフライン自転車ナビゲーション

**CycleMap** は、電波の届かない山間部やロングライドでも単体で動作する、Android 向けの**オフライン特化・自転車専用ナビ**です。地図描画、自転車向けコストモデルによる経路探索、施設(POI)のあいまい検索をすべて端末内で完結します。データは APK に同梱せず、事前生成したデータパッケージを端末に配置して使います。

- 対象エリア: **全 47 都道府県**。県別のデータ (経路グラフ・検索 DB・地図タイル・等高線・陰影・高速化・県またぎの通り口・道路ルール) を 47 県分生成済みで、現在地から県を判定して自動で切り替えます。端末へは必要な県だけを入れます (設計は `docs/nationwide_design.md`)
- 最新リリース: **α0.9.11-unified** (APK SHA-256 先頭12桁 `7e99e067cb6e`)。サイコン・シンプル・利き手モード、道路ルールの通知、一方通行と自転車道の扱い、走行記録の復元、全 47 県データの作り直しなど。詳しくは [VERSIONS.md](VERSIONS.md)
- リリース後の開発中の変更 (台帳には未記録): 出発前のオフライン診断、目的地検索の検索範囲の切り替え、ナビ中の立ち寄り検索を残りの経路に沿わせる、踏切・橋の注意チップ、お気に入り地点 (自宅など) など
- 配布: APK とデータは、まだ公開していない (データは APK に含まれず、事前に端末へ入れる)
- ビルド台帳: [VERSIONS.md](VERSIONS.md)。公開リポジトリの履歴は開発側と別のハッシュで、対応は [docs/public_repository_20261114.md](docs/public_repository_20261114.md)

---

## 主な機能

| 領域 | 内容 |
| :--- | :--- |
| **ルーティング** | A\* による自転車専用探索。`motorway` を除外し、路面・道路種別・勾配(下り優遇、急坂・上りにペナルティ)・自転車道・一方通行を考慮します。コースアウト時は自動でリルートします。係数は `BicycleCost.kt` の 1 か所に集約しています。 |
| **高速探索 (CH)** | 任意のサイドカー `<県>.graph.ch` があれば Contraction Hierarchies で探索します。無い・グラフと合わない場合は A\* に戻り、結果は A\* と一致させます (設計は `docs/ch_design.md`)。 |
| **県またぎ** | グラフは統合せず、県境の共有 OSM ノードを「通り口 (ポータル)」として経由地ごとに区間へ分けて探索します。2 県は直接、3 県以上は連鎖で解きます (設計は `docs/cross_prefecture_design.md`)。 |
| **グラフ形式** | OSM PBF を独自バイナリ `.graph` と空間インデックス `.graph.idx` に変換し、mmap で読み込みます。メモリの少ない端末でも高速に起動します。 |
| **道路ルールの通知** | 自動車専用・一方通行の逆向き・自転車通行不可・押し歩き・歩行者と共用・自転車通行帯などを画面に出します。除外した道の情報は `<県>.bikerules` に持ちます。 |
| **注意地点** | 経路上の踏切・橋をナビ上部のチップで知らせます (`hazards.bin`、設計は `docs/design_route_hazards.md`)。 |
| **地図表示** | MapLibre Native の 3D ベクター(PMTiles)を使い、osmdroid をフォールバックにしています。等高線・陰影 (国土地理院 DEM 由来) を重ねられ、日の出・日の入りに連動して昼夜スタイルを自動で切り替えます。 |
| **POI 検索** | Overture Maps、国土数値情報(医療機関・学校)、OSM を統合し、重複排除しています。ひらがな・カタカナ・ローマ字の表記ゆれを吸収し、カテゴリ重みと距離でランキングします。検索範囲は「現在地周辺・表示中の地図・導入済みの全県」から選べ、ナビ中の立ち寄り検索は残りの経路に沿って探します。 |
| **表示モード** | 標準のほか、サイコンモード (速度・距離・時間・平均/最高速度・気圧計による獲得標高、目的地の方向と残り距離)、シンプルモード (地図と操作ボタンだけ)、利き手 (左手) モードがあります。 |
| **走行記録** | GPX の記録・保存、走行の退避と復元、静止中のぶれの除去を行います。 |
| **音声案内** | 曲がり角の音声案内を、距離帯とリルートに合わせて出します。 |
| **天気** | 気象庁(JMA)のアメダス観測値(降水量・風・気温)をナビ HUD と地図に表示します。 |
| **お気に入り** | 地図上の好きな地点に名前をつけて保存します (自宅など)。 |
| **オフライン診断** | ルート確認で、通る県ごとに必要なデータ (地図・検索・経路) と任意のデータ (高速化・等高線・陰影・通り口) の有無を出発前に確認できます。 |
| **UI/UX** | Jetpack Compose の MVI/UDF 構成です。検索カプセルと全画面検索、5 km/h 以上で GPS 進行方向に追従するヘディングアップ、フルスクリーン HUD、横画面に対応します。 |
| **省電力** | 待機時の描画停止、GPS サンプリング抑制、2D/3D の動的フォールバックを行います。 |
| **動的県切替** | 現在地から県を判定し、対応するグラフと検索 DB に自動で切り替えます(`RoutingGraphSelector` / `SearchDbSelector`)。 |

---

## 全体構成

```text
cycle_map/
├── build_prefectures.py        # 県別データ生成パイプライン(PBF切出し → search.db + graph → ZIP)
├── boundaries/                 # 行政界ポリゴン(SHA-256検証付きで自動ダウンロード)
├── scripts/
│   ├── build_tiles.py          # ベクタータイル → packages/<県>.pmtiles
│   ├── build_terrain.py        # 国土地理院 DEM → 等高線・陰影 (.contours / .hillshade .pmtiles)
│   ├── build_ch.py             # Contraction Hierarchies サイドカー (.graph.ch)
│   ├── build_portals.py        # 県またぎの通り口 (<A>__<B>.portals)
│   ├── build_hazards.py        # 踏切・橋の注意地点データ (hazards.bin)
│   ├── build_prefecture_polygons.py  # 47 県の判定用ポリゴン
│   ├── patch_map_style.py      # 同梱の地図スタイルへの独自調整 (冪等)
│   ├── refresh_data.py         # 最新 OSM から検索 DB・グラフ・タイル・CH・通り口まで一括更新
│   ├── data_studio.py / .cmd   # 上記を画面から選んで並列実行する GUI (docs/data_studio.md)
│   ├── push_data.sh            # 実機へ ADB 一括転送 + SHA-256 検証(--dry-run 対応)
│   ├── device_regression.sh    # 実機での経路回帰テスト
│   ├── check_all.sh            # CI と同じ検証をローカルで実行
│   └── delegate_codex.sh / delegate_agy.sh   # 子エージェント (Codex / Antigravity) への委譲
├── application/                # 正系統 Android Gradle プロジェクト(gradlew はここで実行)
│   ├── app/                    # :app — Compose UI(MVI/UDF)、MapLibre + osmdroid、位置情報、JMA 天気
│   ├── routing-core/           # :routing-core — 純粋JVM: RoadGraph, AStarRouter, CyclingCostModel, CH, 県またぎ, mmap リーダー
│   └── osm-importer/           # :osm-importer — JVM CLI: .osm.pbf → .graph / .graph.idx / 検索 DB
├── sandbox/data-tool/          # 検索DB生成ツール(Python + Streamlit): Overture + 国交省 + OSM を統合
├── packages/                   # 生成済みの県別データ (47 県の ZIP と、タイル・地形・CH・道路ルール・県またぎの通り口)
├── data/                       # japan-latest.osm.pbf(約2.5GB)ほか ※コミット・移動禁止
├── Archives/                   # 旧系統コード(旧 .git 履歴を含むため削除禁止)
├── bugs/, Verification/, docs/ # バグ記録、実機検証レポート・証跡、設計・仕様資料
├── AGENTS.md                   # AI エージェント向けルール・開発体制・Git運用
└── VERSIONS.md                 # APK ビルド台帳
```

`app` 内の主なパッケージ(`com.gorite.cyclemap`)は `contract` (インターフェースとモデル)、`real` (その実装)、`ui`、`routing`、`navigation`、`search`、`data`、`hazard`、`weather`、`tracking`、`power`、`speech`、`net` です。

### データの流れ

```text
OSM PBF (Geofabrik) ──osmium extract(行政界, complete_ways)──▶ 県別 .osm.pbf
                                                                │
                      ┌─────────────────────────────────────────┤
                      ▼                                         ▼
          osm-importer (--bbox none)                  search.db 生成
          → <県>.graph + <県>.graph.idx      (data-tool の統合検索DB、なければ build_search_db.py)
                      └──────────────┬──────────────────────────┘
                                     ▼
              packages/<県>.zip(search.db / graph / graph.idx / manifest.json / LICENSE.txt)

   ZIP とは別の成果物 (どれが無くてもアプリは動く):
     <県>.pmtiles / <県>.contours.pmtiles / <県>.hillshade.pmtiles   地図・等高線・陰影
     <県>.graph.ch                                                    高速探索
     <県>.bikerules                                                   道路ルールの通知
     <A>__<B>.portals                                                 県またぎの通り口 (グラフ再生成のたびに作り直す)
     hazards.bin                                                      踏切・橋

                                     ▼  scripts/push_data.sh
          端末: getExternalFilesDir(DOCUMENTS)/CycleMap/  → アプリが起動時に自動探索
```

ルーティング用データ(OSM `.graph`)と表示用データ(地理院タイルなど)は厳密に分離しています。ラスターデータをグラフに混ぜることはありません。

---

## 動作環境・ツールチェイン

- 対象: Android 13 以上(minSdk 33 / targetSdk・compileSdk 35)。実機検証は Samsung Galaxy S26(SC-51G / Android 17)。
- Kotlin 2.0.21、AGP 8.10.1、Java 11 ターゲット。Gradle は JDK 17 または 21 で実行します(Windows では Android Studio 付属の JDK 21 を使い、システムの Java 25 は Gradle 8.11.1 非対応です)。
- ビルド可能な環境: macOS / Windows / Linux。Windows ではリポジトリを **ASCII のパス** (例: `C:\dev\cycle_map`) に置いてください (日本語を含むパスだと Gradle のテストが全件落ちます)。Khadas Edge 2(ARM64 SBC)へのビルドオフロード環境もあります(詳細は [AGENTS.md](AGENTS.md))。
- `application/local.properties` の `sdk.dir` はマシン固有です。コミットせず、Android Studio の初回起動で生成するか手動で記載します。
- Python パイプラインは、リポジトリ直下の `venv/` の Python を使います(system python には osmium がありません)。また `osmium` CLI が PATH に必要です。

---

## クイックスタート

```bash
git clone https://github.com/Gorite-programming/cycle_map.git
cd cycle_map/application

./gradlew test                                   # 全ユニットテスト
./gradlew :routing-core:test :osm-importer:test  # JVM コアのみ
./gradlew :app:assembleDebug                     # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :osm-importer:installDist              # importer CLI をビルド
```

push 前は、CI と同じ検証 (Gradle テスト、`sandbox/data-tool` と `scripts/tests` の pytest、スタイルパッチの冪等性、シェル・Python の構文) をローカルで流せます。

```bash
scripts/check_all.sh                 # --skip-gradle で Gradle テストを省略
```

### データ生成(ルート直下)

```bash
# 県別パッケージの生成(再ビルドは --force、失敗時の work は残る)
venv/bin/python build_prefectures.py --only Yamaguchi --keep-work

# ZIP 以外の成果物 (県ごと。--only で絞る)
python3 scripts/build_tiles.py --only Yamaguchi     # 地図タイル (要ネットワーク)
python3 scripts/build_terrain.py --only Yamaguchi   # 等高線・陰影 (要ネットワーク、県あたり約 2 千枚)
python3 scripts/build_ch.py --only Yamaguchi        # 高速探索
python3 scripts/build_portals.py                    # 県またぎの通り口 (グラフ再生成後に必ず再実行)

# 最新 OSM から全工程を更新 (約 1.5 時間。端末への転送はしない)
venv/bin/python scripts/refresh_data.py --dry-run

# 単体で検索DB / グラフを作る場合
venv/bin/python application/osm-importer/build_search_db.py --bbox none <in.osm.pbf> <out.search.db>
JAVA_OPTS="-Xmx6g" application/osm-importer/build/install/osm-importer/bin/osm-importer --bbox none <in.osm.pbf> <out.graph>
```

画面から県と工程を選んで並列に流したいときは `scripts/data_studio.cmd` (GUI) を使います ([docs/data_studio.md](docs/data_studio.md))。`sandbox/data-tool/out/` にある統合検索 DB は、スキーマ検証(`PRAGMA integrity_check`、必須列、インデックス、件数)を通れば自動で取り込まれます。

### 実機へのデータ配備

```bash
./scripts/push_data.sh --dry-run   # 転送計画の確認
./scripts/push_data.sh             # 転送 + SHA-256 検証 (--tiles-only / --db-only)
```

機内モードで動作を検証します。タイルは県ごとの PMTiles で持つため、走行中のタイル取得は不要です。

---

## 設計上の注意点・既知の課題

- **コストモデル**: 自転車の道路係数と、推奨プロファイルの勾配ペナルティ、最小倍率 0.90 (A\* ヒューリスティックの下限) は `BicycleCost.kt` に集約しています。変更はここだけで行い、**CH サイドカーを作り直してください** (グラフのフィンガープリントにはコストが含まれません)。
- **県またぎの通り口**: `<A>__<B>.portals` はグラフを作り直すたびに再生成が必要です。使えないファイルは無視され、ノード ID の照合に戻ります。
- **mmap の制限**: `LazyMappedRouting` は `Int` のオフセットで読むため、2 GiB を超えるグラフは明示的なエラーで拒否します(県別グラフは収まります)。
- **FTS5 非対応端末**: Android 標準 SQLite は FTS5 に対応しない端末が多いため、検索 DB は LIKE 検索用の `places.search_text` 列も持ちます(アプリ側は `Fts5SupportDetector.kt`)。
- **旧バグ集**: `Archives/application/bugs.md` と `bugs/bugs.md` を参照します。大半は修正済みなので、着手前に `git log` と現行コードで再確認してください。
- **未解決の主な項目と実機確認待ち**: [docs/session_20261120.md](docs/session_20261120.md) を参照します (サイコン終了後に地図が白紙になる件など)。
- **将来対応**: `app/build.gradle.kts` の `project.exec` は Gradle 9 で廃止予定のため、`providers.exec` への移行が必要です。

---

## 開発・リリース運用

- 開発は Claude (親)・Codex・Antigravity の AI エージェントと人間(Gorite)が連携して進めます。Claude が設計・検証・commit/push を担い、Codex が実装とテスト、Antigravity が別の目でのレビューと調査を担当します。Windows / MacBook 間は GitHub 経由で同期するため、作業前に `git pull` してください。
- **Git 操作**: 通常の `commit` / `push` は Claude Code が自分の判断で行えます。`push --force`・履歴の書き換え・ブランチ削除は、そのつど明示的な承認が必要です。他のエージェントは、人間の承認を待ってください (詳細は [AGENTS.md](AGENTS.md))。
- 配布・記録用 APK は、**クリーンな HEAD** からビルドします。SHA-256 先頭12桁を [VERSIONS.md](VERSIONS.md) に別コミットで記録してください。
- 署名鍵(`debug.keystore`)はコミットしません。Edge 2 の `~/.android/debug.keystore` が正本で、紛失すると `adb install -r` で更新できなくなるため、別途バックアップします。
- 公開用リポジトリ `cycle_map_public` は、この非公開の開発リポジトリから `scripts/publish_public.sh --push` で作ります。公開側には直接コミットしません。

---

## ライセンスと出典

アプリ内の「地図情報・ライセンス」ダイアログと地図隅の帰属表示は、オフラインでも維持します。

| データ | 提供元 | ライセンス | 用途 |
| :--- | :--- | :--- | :--- |
| 道路ネットワーク | OpenStreetMap (Geofabrik) | [ODbL 1.0](https://opendatacommons.org/licenses/odbl/) | ルーティンググラフ生成 |
| 施設 (POI) | Overture Maps Foundation | [CDLA-Permissive-2.0](https://cdla.dev/permissive-2-0/) | 検索 DB |
| 医療機関・学校 | 国土交通省 国土数値情報 | [PDL 1.0](https://www.geospatial.jp/content/pdl1-0/) | 検索 DB |
| 背景地図・標高 | 国土地理院(地理院タイル / DEM10B) | [国土地理院利用規約](https://maps.gsi.go.jp/development/ichiran.html) | タイル表示・等高線・陰影・標高プロファイル |
| ベクタータイル | Protomaps (OpenStreetMap 由来) | [ODbL 1.0](https://opendatacommons.org/licenses/odbl/) | 地図表示 |
| 行政界ポリゴン | maderaojen (GitHub) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | 県境クリッピング |

詳細は [application/DISTRIBUTION_NOTES.md](application/DISTRIBUTION_NOTES.md) を参照してください。

---

## 関連ドキュメント

- [AGENTS.md](AGENTS.md) — エージェント向けルール、SBC ビルド環境、Git 運用
- [VERSIONS.md](VERSIONS.md) — APK ビルド台帳
- [application/README.md](application/README.md) — Android プロジェクトの設計指針
- [sandbox/data-tool/README.md](sandbox/data-tool/README.md) — 検索 DB 生成ツール
- [Verification/VERIFICATION_REPORT.md](Verification/VERIFICATION_REPORT.md) — 実機検証レポート
- 設計: [CH](docs/ch_design.md) / [県またぎ](docs/cross_prefecture_design.md) / [全国対応](docs/nationwide_design.md) / [注意地点](docs/design_route_hazards.md) / [サイコンモード](docs/design_cycle_computer_mode.md) / [シンプルモード](docs/design_simple_mode.md)
- 経緯: [docs/handoff_20261113.md](docs/handoff_20261113.md)、[docs/session_20261120.md](docs/session_20261120.md)
- [docs/data_studio.md](docs/data_studio.md) — データ生成 GUI
