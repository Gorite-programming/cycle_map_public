# CycleMap α — 完全オフライン自転車ナビゲーション

**CycleMap** は、電波の届かない山間部やロングライドでも単体で動作する、Android 向けの**オフライン特化・自転車専用ナビ**です。地図描画、自転車向けコストモデルによる経路探索、施設(POI)のあいまい検索をすべて端末内で完結します。データは APK に同梱せず、事前生成したデータパッケージを端末に配置して使います。

- 現在の対象エリア: 中国5県(鳥取・島根・岡山・広島・山口)
- 最新バージョン: **α0.9.8-unified**(Git `5e95451`、POIピン・国道県道標識・駅温泉パンのアイコンの仕上げ、APK SHA-256 先頭12桁 `0e89f67763c9`)
- 1つ前のビルド: **α0.9.7-unified**(Git `2226ee0`、土地利用の塗り分け・POIの出し分け・等高線と陰影起伏、APK SHA-256 先頭12桁 `afa794c8a21c`)
- ビルド台帳: [VERSIONS.md](VERSIONS.md) / [GitHub Releases](https://github.com/Gorite-programming/cycle_map/releases)

---

## 主な機能

| 領域 | 内容 |
| :--- | :--- |
| **ルーティング** | A\* による自転車専用探索。`motorway` を除外し、路面・道路種別・勾配(下り優遇、急坂・上りにペナルティ)を考慮します。コースアウト時は 6 秒間隔で自動リルートします。 |
| **グラフ形式** | OSM PBF を独自バイナリ `.graph` と空間インデックス `.graph.idx` に変換し、mmap で読み込みます。メモリの少ない端末でも高速に起動します。 |
| **地図表示** | MapLibre Native の 3D ベクター(PMTiles)を使い、osmdroid をフォールバックにしています。日の出・日の入りに連動して昼夜スタイルを自動で切り替えます。 |
| **POI 検索** | Overture Maps、国土数値情報(医療機関・学校)、OSM を統合し、重複排除しています。ひらがな・カタカナ・ローマ字の表記ゆれを吸収し、カテゴリ重みと距離でランキングします。 |
| **天気** | 気象庁(JMA)のアメダス観測値(降水量・風・気温)をナビ HUD と地図に表示します。 |
| **UI/UX** | Jetpack Compose の MVI/UDF 構成です。Google Maps 準拠の検索カプセルと全画面検索、5 km/h 以上で GPS 進行方向に追従するヘディングアップ、フルスクリーン HUD を備えます。 |
| **省電力** | 待機時の描画停止、GPS サンプリング抑制、2D/3D の動的フォールバックを行います。 |
| **動的県切替** | 現在地から県を判定し、対応するグラフと検索 DB に自動で切り替えます(`RoutingGraphSelector` / `SearchDbSelector`)。 |

---

## 全体構成

```text
cycle_map/
├── build_prefectures.py        # 県別データ生成パイプライン(PBF切出し → search.db + graph → ZIP)
├── boundaries/                 # 行政界ポリゴン(SHA-256検証付きで自動ダウンロード)
├── scripts/
│   ├── push_data.sh            # 実機へ ADB 一括転送 + SHA-256 検証(--dry-run 対応)
│   ├── download_data.ps1       # Windows 用データ取得
│   └── generate_admin_boundaries.py
├── application/                # 正系統 Android Gradle プロジェクト(gradlew はここで実行)
│   ├── app/                    # :app — Compose UI(MVI/UDF)、MapLibre + osmdroid、位置情報、JMA 天気
│   ├── routing-core/           # :routing-core — 純粋JVM: RoadGraph, AStarRouter, CyclingCostModel, mmap リーダー
│   └── osm-importer/           # :osm-importer — JVM CLI: .osm.pbf → .graph / .graph.idx
├── sandbox/data-tool/          # 検索DB生成ツール(Python + Streamlit): Overture + 国交省 + OSM を統合
├── packages/                   # 生成済み県別 ZIP(Tottori / Shimane / Okayama / Hiroshima / Yamaguchi)
├── data/                       # japan-latest.osm.pbf(約2.5GB)ほか ※コミット・移動禁止
├── Archives/                   # 旧系統コード(旧 .git 履歴を含むため削除禁止)
├── bugs/, Verification/, docs/ # バグ記録、実機検証レポート・証跡、仕様資料
├── AGENTS.md                   # AI エージェント向けルール・開発体制・Git運用
└── VERSIONS.md                 # APK ビルド台帳
```

`app` 内の主なパッケージ(`com.gorite.cyclemap`)は `ui`(screens/map/mapview/components/theme など)、`routing`、`navigation`、`search`、`data`、`weather`、`tracking`、`power`、`speech`、`net` です。

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
                                     ▼  scripts/push_data.sh
          端末: getExternalFilesDir(DOCUMENTS)/CycleMap/  → アプリが起動時に自動探索
```

ルーティング用データ(OSM `.graph`)と表示用データ(地理院タイルなど)は厳密に分離しています。ラスターデータをグラフに混ぜることはありません。

---

## 動作環境・ツールチェイン

- 対象: Android 13 以上(minSdk 33 / targetSdk・compileSdk 35)。実機検証は Samsung Galaxy S26(SC-51G / Android 17)。
- Kotlin 2.0.21、AGP 8.10.1、Java 11 ターゲット。Gradle は JDK 17 または 21 で実行します(Windows では Android Studio 付属の JDK 21 を使い、システムの Java 25 は Gradle 8.11.1 非対応です)。
- ビルド可能な環境: macOS / Windows / Linux。Khadas Edge 2(ARM64 SBC)へのビルドオフロード環境があります(詳細は [AGENTS.md](AGENTS.md))。
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

### データ生成(ルート直下)

```bash
# 県別パッケージの生成(再ビルドは --force、失敗時の work は残る)
venv/bin/python build_prefectures.py --only Yamaguchi --keep-work

# 単体で検索DB / グラフを作る場合
venv/bin/python application/osm-importer/build_search_db.py --bbox none <in.osm.pbf> <out.search.db>
JAVA_OPTS="-Xmx6g" application/osm-importer/build/install/osm-importer/bin/osm-importer --bbox none <in.osm.pbf> <out.graph>
```

`sandbox/data-tool/out/` にある統合検索 DB は、スキーマ検証(`PRAGMA integrity_check`、必須列、インデックス、件数)を通れば自動で取り込まれます。

### 実機へのデータ配備

```bash
./scripts/push_data.sh --dry-run   # 転送計画の確認
./scripts/push_data.sh             # 転送 + SHA-256 検証
```

機内モードで動作を検証します。山口の z15–z16 全域タイル(約 8.9 万枚、1 GB 超)は取得せず、経路回廊のみ取得する方針です。

---

## 設計上の注意点・既知の課題

- **コストモデル**: `CyclingCostModel` の最小倍率 0.90 は A\* ヒューリスティックの下限です。JVM 側と `LazyMappedRoadGraph` で値を同期させてください。
- **実験的コード**: 本番のルーティングは A\* のみです。`HsaRouting.kt` と `AltRouting.kt` は実験用です(詳細は [HSA_STAR_EXTERNAL_REVIEW_REQUEST.md](application/HSA_STAR_EXTERNAL_REVIEW_REQUEST.md))。
- **mmap の制限**: `LazyMappedRouting.readEdge()` は 2 GB 超でオーバーフローします(`offset.toInt()`)。`nearestNodeIndex` は O(n) の線形走査です。
- **FTS5 非対応端末**: Android 標準 SQLite は FTS5 に対応しない端末が多いため、検索 DB は LIKE 検索用の `places.search_text` 列も持ちます(アプリ側は `Fts5SupportDetector.kt`)。
- **旧バグ集**: `Archives/application/bugs.md` と `bugs/bugs.md` を参照します。大半は修正済みなので、着手前に `git log` と現行コードで再確認してください。
- **将来対応**: `app/build.gradle.kts` の `project.exec` は Gradle 9 で廃止予定のため、`providers.exec` への移行が必要です。

---

## 開発・リリース運用

- 開発は複数の AI エージェントと人間(Gorite)が連携して進めます。Windows / MacBook 間は GitHub 経由で同期するため、作業前に `git pull` してください。
- **Git 操作は承認制**です。`commit` と `push` は人間の明示的な指示があるときだけ行います。
- 配布・記録用 APK は、**クリーンな HEAD** からビルドします。SHA-256 先頭12桁を [VERSIONS.md](VERSIONS.md) に別コミットで記録してください。
- 署名鍵(`debug.keystore`)はコミットしません。Edge 2 の `~/.android/debug.keystore` が正本で、紛失すると `adb install -r` で更新できなくなるため、別途バックアップします。

---

## ライセンスと出典

アプリ内の「地図情報・ライセンス」ダイアログと地図隅の帰属表示は、オフラインでも維持します。

| データ | 提供元 | ライセンス | 用途 |
| :--- | :--- | :--- | :--- |
| 道路ネットワーク | OpenStreetMap (Geofabrik) | [ODbL 1.0](https://opendatacommons.org/licenses/odbl/) | ルーティンググラフ生成 |
| 施設 (POI) | Overture Maps Foundation | [CDLA-Permissive-2.0](https://cdla.dev/permissive-2-0/) | 検索 DB |
| 医療機関・学校 | 国土交通省 国土数値情報 | [PDL 1.0](https://www.geospatial.jp/content/pdl1-0/) | 検索 DB |
| 背景地図・標高 | 国土地理院(地理院タイル / DEM10B) | [国土地理院利用規約](https://maps.gsi.go.jp/development/ichiran.html) | タイル表示・標高プロファイル |
| 行政界ポリゴン | maderaojen (GitHub) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | 県境クリッピング |

詳細は [application/DISTRIBUTION_NOTES.md](application/DISTRIBUTION_NOTES.md) を参照してください。

---

## 関連ドキュメント

- [AGENTS.md](AGENTS.md) — エージェント向けルール、SBC ビルド環境、Git 運用
- [VERSIONS.md](VERSIONS.md) — APK ビルド台帳
- [application/README.md](application/README.md) — Android プロジェクトの設計指針
- [sandbox/data-tool/README.md](sandbox/data-tool/README.md) — 検索 DB 生成ツール
- [Verification/VERIFICATION_REPORT.md](Verification/VERIFICATION_REPORT.md) — 実機検証レポート
