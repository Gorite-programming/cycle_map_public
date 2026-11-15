# AGENTS.md — CycleMap

完全オフラインの自転車ナビ。3層構成: ルート直下の Python データパイプライン + `application/` Android Gradle プロジェクト(正系統) + `sandbox/data-tool/` 検索DB生成ツール(Python/Streamlit)。git リポジトリは `cycle_map/` ルート直下に統合済み(GitHub: Gorite-programming/cycle_map, Public)。旧コード(`Archives/application/` および `Archives/test/`)は `Archives/` 配下に完全退避・アーカイブ済み(`Archives/application/.git.bak` に旧・独立リポジトリ時代の全履歴を保存、削除禁止)。ルートの `japan-latest.osm.pbf` はシンボリックリンク(実体は `data/japan-latest.osm.pbf`)。

## 構成

- `application/` — 正系統 Gradle プロジェクト(`:app`、`:routing-core`、`:osm-importer`)。`gradlew` はすべて `application/` から実行する。
  - `app/` — Android UI(MVI/UDF設計、Compose、MapLibre 3Dベクター+osmdroid 6.1.20ハイブリッド、play-services-location)。エントリーポイントは `MainActivity.kt`。
  - `routing-core/` — 純粋 JVM ライブラリ:`RoadGraph`、`AStarRouter`、`CyclingCostModel`、mmap リーダー(`LazyMappedRouting.kt`。使われていなかった `MappedRoadGraph` は削除)。**基準のルーターは A\*。任意の `<name>.graph.ch` (Contraction Hierarchies、`docs/ch_design.md`) があれば「おすすめ×自転車」で CH を優先し、無ければ A\* に戻る。** 県またぎはグラフを結合せず通り口 (共通 OSM ノード) で繋ぐ (`docs/cross_prefecture_design.md`)。実験用だった HSA\*/ALT は削除済み (`90bf751`)。
  - `osm-importer/` — JVM CLI(`OsmImporterKt`):OSM `.pbf` → 独自バイナリ `.graph` + サイドカー `.graph.idx`。
- `Archives/` — 過去系統アーカイブ(`Archives/application/`, `Archives/test/`)。
- ルートパイプライン:`build_prefectures.py`(中国5県:鳥取・島根・岡山・広島・山口)→県ごとに `<name>.osm.pbf` + `<name>.search.db` + `<name>.graph` / `.idx` + `manifest.json` + `LICENSE.txt` → `packages/<name>.zip`。`sandbox/data-tool/out/` にあるOverture+国交省+OSM統合検索DBをスキーマ検証（PRAGMA integrity_check、必須列、インデックス、件数）の上で自動取り込み。`build_search_db.py` はフォールバック用（`application/osm-importer/build_search_db.py`）。`boundaries/` は行政界ポリゴン(SHA-256 検証付きで自動ダウンロード)。
- `scripts/build_tiles.py` — 県別ベクタータイル(Protomaps Basemap v4 スキーマ、既存スタイルと同一)を Protomaps の公開ビルド(`build.protomaps.com`)から `pmtiles extract --bbox`(県境の外接矩形+0.02°)で取得し `packages/<県小文字>.pmtiles` に置く。ネット接続が必要。ZIP の5点不変条件とは別成果物で、`TileSourceSelector`(`PrefectureSelectors.kt`)が探すファイル名と一致する(旧 `Hiroshima_osm.pmtiles` も互換候補で解決)。
- `scripts/build_terrain.py` — 県別の地形データを国土地理院の標高タイル(dem_png z14、`work/terrain/dem14/` にキャッシュ)から生成する。`packages/<県小文字>.contours.pmtiles`(等高線 MVT z12-14、z14 は10m間隔・50mごとにindex)と `packages/<県小文字>.hillshade.pmtiles`(陰影起伏 PNG z9-13、半透明オーバーレイ)。依存は `venv/bin/pip install numpy pillow contourpy shapely mapbox-vector-tile pmtiles`。アプリは県のタイルと同じ階層で同名の派生ファイルを探し、無い県ではその表現だけ出さない(`VectorMapTerrain.kt`)。
- `scripts/patch_map_style.py` — 同梱スタイル(protomaps_light/dark.json)へのCycleMap独自調整(国道・県道の標識、鉄道ゼブラ、土地利用の塗り分け、タイルPOIの重複除去)。冪等で、スタイルを作り直したら再実行する。
- `scripts/push_data.sh` — 実機（Galaxy S26等）へのオフラインデータ一括転送・SHA-256整合性検証スクリプト（`--dry-run` / `--tiles-only` / `--db-only` 対応。タイルは `packages/<県小文字>.pmtiles` を転送）。
- `sandbox/data-tool/` — 検索DB生成ツール(Python + Streamlit)。以前は独立Gitだったが現在は `cycle_map` リポジトリに統合済み。OSM PBFを道路基盤とし、POI（コンビニ・飲食店・病院・観光施設等）は Overture Maps Foundation (Placesテーマ、CDLA-Permissive-2.0) および 国土数値情報（医療機関第3.0版・学校第2.0版、PDL1.0）を統合・重複排除（同一敷地・同一住所マージ）して高精度化するパイプライン。表記ゆれ対応（ひらがな・カタカナ・ローマ字）も実装済み。ライセンス表記・出典規定の詳細は `application/DISTRIBUTION_NOTES.md` およびアプリ内「地図情報・ライセンス」ダイアログを参照。**重要**: Android 標準 SQLite は多くの実機で FTS5 非対応(`no such module: fts5`)のため、検索DBは `places.search_text` 列(LIKE検索用、表示名の `name` 列とは別)も保持して互換性を確保している(アプリ側の対応は `Fts5SupportDetector.kt`)。
- 大容量データ(コミット・移動禁止):`data/japan-latest.osm.pbf`(約2.5 GB。ルート直下の `japan-latest.osm.pbf` はシンボリックリンク)、`work/` 中間生成物、`venv/`。

## コマンド

```bash
cd application
./gradlew test                                     # 全ユニットテスト (:routing-core, :osm-importer, :app)
./gradlew :routing-core:test :osm-importer:test    # JVM コアユニットテストのみ
./gradlew :app:assembleDebug                       # APK
./gradlew :osm-importer:installDist                # osm-importer CLI をビルド
JAVA_OPTS="-Xmx6g" osm-importer/build/install/osm-importer/bin/osm-importer --bbox none <in.osm.pbf> <out.graph>  # .graph + .graph.idx を生成
```

```bash
# ルートパイプライン — venv の python を必ず使うこと(system python には osmium がない):
venv/bin/python application/osm-importer/build_search_db.py --bbox none <in.osm.pbf> <out.search.db>   # --bbox: japan|yamaguchi|none
venv/bin/python build_prefectures.py --only Yamaguchi --keep-work             # 再ビルドは --force を追加。PATH に `osmium` CLI が必要
./scripts/push_data.sh --dry-run                                              # 実機データ配置計画の確認
./scripts/push_data.sh                                                        # 実機へデータ転送＋SHA-256検証
```

ツールチェイン:AGP 8.10.1、Kotlin 2.0.21、compileSdk/target 35、minSdk 33、Java 11。`local.properties` の `sdk.dir` はマシン固有(`.gitignore`で除外済み、コミットされない) — 新しい環境(Windows含む)では Android Studio の初回起動時に自動生成されるか、手動で `sdk.dir=<自分のAndroid SDKパス>` を記載すること。

## 注意点

- ルーティング用データ(OSM `.graph`)と表示用データ(地理院タイル)は厳密に分離。Google/OSM ラスターデータをグラフに混ぜない。OSM + 地理院の帰属表示(地図隅 + ライセンス画面)はオフラインでも表示を維持する。
- データは APK に同梱しない。アプリはアプリ専用外部ストレージ `getExternalFilesDir(DOCUMENTS)/CycleMap/` を見る:`<name>.graph`、`<name>.graph.idx`、`tiles/cache.db`。県の動的切替は対応済み(`RoutingGraphSelector` / `SearchDbSelector` を `Prefectures.kt` に実装)。起動時は配置済みファイルから名前昇順で最初のものを自動探索し、現在地が確定したら対応する県のグラフ/検索DBに自動切替する。
- `build_prefectures.py` の不変条件:`osmium extract --polygon <boundary> --strategy complete_ways` を使う。importer 呼び出しは `--bbox none`。ZIP 内容は `<name>.search.db` + `<name>.graph` + `<name>.graph.idx` + `manifest.json` + `LICENSE.txt` の5点。有効な ZIP があれば `--force` なしではスキップ。失敗時の作業ディレクトリはデバッグ用に残す。
- `CyclingCostModel` は `motorway` を除外。自転車の道路の係数・「おすすめ」の勾配の係数・最小倍率 0.90 (A\* ヒューリスティックの下限) は `BicycleCost.kt` の 1 か所にまとめ、`CyclingCostModel` と `LazyMappedRoadGraph` が共有する。変えるときはそこだけを変え、CH (`.graph.ch`) を作り直す (グラフの指紋は係数を含まない)。
- `LazyMappedRouting` は `Int` オフセットで読むため、2 GiB 超の `.graph` はロード時に明示エラーで拒否する(県別グラフは収まる)。`nearestNodeIndex` は空間グリッドを使い、見つからない場合のみ線形走査にフォールバックする。既知バグ集（旧系統の改修履歴）は `Archives/application/bugs.md` — 修正前に必ず確認すること。ただし Critical/High の大半はコミット `3a6faf2` で修正済みのため、着手前に `git -C application log --oneline` と現行コードで再確認する(盲目的な再修正を避ける)。
- 実機手順:Samsung Galaxy S26(SC-51G) + `adb push`、機内モードで検証。山口 z15–z16 全域タイル(約8.9万枚、1 GB 超)は見送り — 全域取得ではなく経路回廊のみ取得する方針。

## 開発体制・Git運用ポリシー（全エージェント共通）

- **開発体制**:
  - 2026-11-15 から、Claude (Opus 5.5) を親、Codex (GPT-6.1 Sol) と Antigravity (Gemini 4 Hydrogen) を子とする三者で作業する。Claude が設計・分割・検証・commit を担い、子には CLI (`scripts/delegate_codex.sh` / `scripts/delegate_agy.sh`) で指示を出す。Codex は実装とテストの主担当、Antigravity はレビュー・調査と、Codex と触るファイルが重ならない作業の並行実装を受け持つ。詳細は CLAUDE.md の「三者の合同作業」。
  - 子として呼ばれたエージェントは、指示文の「触ってよいファイル」以外を編集せず、commit / push / adb / 履歴を壊す git 操作をしない。最後に「変更したファイル」「実行したテストと結果」「未解決の点」を報告する。
  - 以前は OpenCode・Kiro なども交代で参加していた。
- **クロス環境（Windows / MacBook）と同期**:
  - Windows 環境と MacBook 環境の双方で作業を進めるため、GitHub（`Gorite-programming/cycle_map`）を経由して同期を行っています。
  - 作業着手前や時間が空いた場合は、必ず `git pull` を実行して最新のコード・状況を取り込んでください。
  - 作業完了・コミット後は、別マシンや別エージェントへ速やかに引き継げるよう定期的に `git push` を行います。
- **Git操作は完全承認制(Claude Code のみ例外あり)**:
  - `git commit` / `git push` を含むGit反映操作は、人間（Gorite）の明示的な承認を得てから人間自身が実行する（または明示的な指示のもとで行う）。
  - **例外: Claude Code が操作している場合に限り、`git add` / `git commit` / `git push` を自律的に判断して行ってよい**(2026-11-08 Gorite 許可)。ただし通常のコミット・プッシュに限り、`push --force`・履歴の書き換え・ブランチ削除などの破壊的操作は対象外で、都度承認を得る。実施した場合は、何をコミット・プッシュしたかを必ず報告する。リリース用 APK はクリーンな HEAD からビルドし、ハッシュは台帳に別コミットで記録する(下記「ビルド・リリース運用ルール」)。
  - Antigravity / OpenCode / Kiro / その他のエージェント(Claude Code 以外)は、コード変更後に勝手に `git add`・`git commit`・`git push` を行わない。
  - 各作業の完了時は、変更内容の要約・diff・テスト結果を報告し、コミットやプッシュについての指示を待つ。
  - 「コミットしないでください」という指示が無くても、これがデフォルトの動作とする。

## ビルドオフロード環境（Khadas Edge 2）

MacBook 単体でのメモリ逼迫対策として、SBC（Khadas Edge 2）へのビルド・コーディング・実機検証オフロード体制を整備済み。

- **ハードウェア・環境情報**:
  - ホスト: Khadas Edge 2（Rockchip RK3588S 8コア / Ubuntu 24.04 noble aarch64）
  - プロジェクト実パス: `/mnt/data/cycle_map`（USB 接続の 57.6GiB ドライブ（/dev/sda1）直下、`~/workspace` からシンボリックリンク）
  - マウント設定: `/etc/fstab` に `UUID=... /mnt/data ext4 defaults,nofail,x-systemd.automount,x-systemd.device-timeout=30 0 2` を設定し自動マウント。/mnt/data が空に見えたら、`lsblk -f` と `findmnt /mnt/data` で状態を確認し、`sudo fsck.ext4 -n /dev/sda1` で診断してから再マウントする。mkfs や初期化はしない。
  - キャッシュ退避: `~/.gradle` は `/mnt/data/.gradle` へシンボリックリンクし、本体 eMMC（残り16GB空き）の枯渇を防止
  - Android SDK: `/mnt/data/android-sdk`（`compileSdk 35`、`build-tools 35.0.0` 導入済み）
  - Gradle メモリ設定: `/mnt/data/.gradle/gradle.properties` に `org.gradle.jvmargs=-Xmx3g`、`org.gradle.workers.max=4` を設定
  - aapt2 エミュレーション: Ubuntu リポジトリの `box64-rk3588` を導入。binfmt_misc により AGP 内蔵の x86_64 版 aapt2 を透過エミュレーションして `:app:assembleDebug` を完全開通

- **ビルド・リリース運用ルール**:
  - **クリーンな HEAD からビルドする**: 作業ツリーが dirty（`-dirty` 付き）の APK は開発・動作確認用にとどめ、配布・正式記録としない。リリース用 APK は、コード修正を承認・コミットした直後のクリーンな HEAD からビルドする。
  - **ハッシュ記録と台帳更新**: クリーンビルドした APK の SHA-256 ハッシュ（先頭12桁）を `VERSIONS.md` に記録し、別コミット（またはリリースコミット）として残す。
  - **Keystore の管理**: リポジトリにはコミットしない。Edge 2 の `~/.android/debug.keystore` が正で、紛失すると `adb install -r` ができなくなるので、別の場所にコピーを保管する。ビルドする機械では、この鍵を使う。Windows では `-Pandroid.injected.signing.store.file` などで指定し、Mac では `~/.android/debug.keystore` に置く。
  - **Windows でのビルド手順**: `JAVA_HOME` は Android Studio 付属の JDK 21。システムの Java 25 は Gradle 8.11.1 に非対応。`application\local.properties` に `sdk.dir` を書く（コミットしない）。
  - **既知の課題**: `app/build.gradle.kts` の `project.exec` は Gradle 9 で廃止予定。Gradle を上げるときに `providers.exec` へ移行する。