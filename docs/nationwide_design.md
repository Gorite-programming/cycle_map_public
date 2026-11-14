# 全国対応 (47 都道府県) 設計 — 7-1 (2026-11-11、Claude の夜間自律作業)

目的: 現在の「中国 5 県」から、全国の県で経路探索・検索・地図表示・県またぎを使えるようにする。
**この文書は設計のみ。全 47 県の一括生成は、朝のあなたの許可を得てから行う。**

## 1. 現状 (コードの事実)

| 要素 | 現状 | 全国化で必要な変更 |
|---|---|---|
| 県の一覧・外接矩形 | `Prefectures.kt` の `PrefectureData.ALL` が **既に 47 県** を持つ (タイルのダウンロード用) | 不要 |
| 県の判定 (現在地→県) | `findPrefectureName` は `ChugokuBoundaries.kt` (5 県のポリゴン) と `AdministrativeBoundaries.kt` (市区町村) を使う | 47 県のポリゴンが必要。`build_prefectures.py` が使う `prefectures.geojson` (jp-prefectures、47 県入り) から生成スクリプトを作る (`scripts/generate_admin_boundaries.py` が既にある。流用) |
| グラフ選択 | `RoutingGraphSelector.SUPPORTED_FILES` の 5 行 (県 ID→ファイル名) | 47 行。ファイル名の大文字小文字不整合 (yamaguchi.graph) があるため、**パイプラインの出力名を `<romaji>.graph` に統一**し、解決は存在確認ベースにする (既存の `resolveGraphFiles` が吸収) |
| 隣接表 | `CrossRoutePlanning.kt` の `PREFECTURE_NEIGHBORS` は 7 組の静的リスト | 47 県の陸続き隣接 (約 90 組) を静的表にする。島・海峡 (北海道-本州、本州-四国、本州-九州、沖縄) は **隣接として入れない** (道路が繋がらない)。しまなみ海道・瀬戸大橋・明石海峡大橋・関門橋は道路で繋がるため入れる (通り口の共通ノードがあるので探索で確認できる)。通り口が 0 件の組は探索側が自然に失敗する (既存実装は `portals.size == 0` で failed) |
| 同時に開くグラフ | チェーンは最大 2 グラフ保持 (3 県以上は `routeAcrossChain` が 1 つずつ開閉) | 変更不要。ただし **3 県チェーンの計算は実機で約 11 秒 (山口>広島>岡山)**。4 県以上は線形に伸びるため、上限を **4 県 (約 15〜20 秒)** とし、超える場合は「データの切替が多いため時間がかかります」と案内する |
| データ生成 | `build_prefectures.py` の `PREFECTURES` は 5 件 (name / jp / code 31..35)。県ごとに `osmium extract --polygon` → importer → 検索 DB → ZIP | `PREFECTURES` を 47 件 (JIS コード 01..47) に拡張。境界は同じ geojson。**1 県ずつ失敗を記録して続行**する (既に 1 県単位) |
| 地図タイル (pmtiles) / 地形 | `scripts/build_tiles.py` (Protomaps の `pmtiles extract`)、`scripts/build_terrain.py` (GSI DEM) | 県の bbox を `PrefectureData.ALL` と共有して 47 県に拡張。地形は 1 県あたり約 2k タイルのダウンロードで時間がかかる |
| 検索 DB | 現在地の県の DB だけを引く (`SearchDbSelector`) | 県境付近で隣県の施設が出ない。**隣接県の DB も引く**拡張を 7-3 で検討 (最初は現状維持) |
| 配布 | adb push (`scripts/push_data.sh`) のみ。アプリ内にデータ取得の導線は無い | 全国分を端末に常駐させない設計 (下記 3) |

## 2. サイズ見積もり (中国 5 県の実績からの外挿)

実績 (Mac の `packages/`): 5 県 ZIP の合計 約 392MB (Tottori 42 / Shimane 62 / Yamaguchi 75 / Okayama 102 / Hiroshima 111 MB)。pmtiles 約 100MB/県、等高線+陰影 約 80MB/県。
→ 1 県あたり平均 **約 80MB (ZIP) + 100MB (pmtiles) + 80MB (地形) ≈ 260MB**。
- 全国 47 県: 面積・道路密度により 0.7〜1.5 倍のばらつき。概算 **12〜16GB** (北海道・東京・大阪・愛知・福岡は大きい側)。
- 作業領域: 5 県分の `work/` が 4.3GB。47 県を `--keep-work` なしで 1 県ずつ回し、完了ごとに消せば **ピーク 2〜3GB 増**。
- **ディスク**: Mac の空きは 2026-11-11 時点で **32GiB (93% 使用)**、既存の `packages/` 7.2GB・`data/japan-latest.osm.pbf` 2.4GB・`work/` 4.3GB を含む。全国分 (約 14GB) + 作業領域 + 既存で足りるが **余裕は約 10GB**。生成前に不要な `packages/_prev` 等の整理 (要許可) か外付けが望ましい。端末 (S26) の空きは 70GB (十分)。
- 端末に全国を常駐 (約 14GB) させるのは可能だが、アプリ内のデータ管理 UI が必要 (3 参照)。

## 3. 配布・端末ストレージ

1. 利用者が使う県だけ端末に置く。今の「adb push」方式を、**アプリ内のデータ管理画面 (県一覧・状態・追加/削除)** へ置き換えるのが本命だが、配信元 (サーバー/GitHub Releases/外部ストレージ) の決定が前提で、**要あなたの判断**。
2. 当面は「データがある県だけ動く」前提で、無い県は既存の「データ未配置」案内を使う (`RoutingGraphSelection.Unavailable`)。
3. 県またぎは、両県のデータが端末にあるときだけ。片方しか無い場合は「隣県のデータがありません」と表示 (既存の警告文の拡張)。

## 4. 段階計画 (QUEUE 7-2〜7-4、各段階を別 commit)

- **7-2 データ生成の自動化**: `PREFECTURES` を 47 件に (JIS コード・romaji・日本語名)。`--only` で 1〜2 県 (推奨: 兵庫、愛媛。兵庫=岡山/鳥取と陸続き、愛媛=しまなみで広島と道路接続) を通し、所要時間とサイズを記録。出力名は `<Romaji>.graph` 統一。全県一括は許可後。
- **7-3 アプリ側**: (a) `SUPPORTED_FILES` を 47 件のデータ駆動に (実在チェック)、(b) 47 県の陸続き隣接表と **整合テスト** (対称・自己ループなし・島を除く全県が連結・北海道/沖縄が孤立でも落ちない)、(c) 県判定ポリゴンの 47 県化、(d) 県数が増えても起動時の `findFirstAvailableGraph` が遅くならないこと。
- **7-4 検証**: 追加 2 県 + 既存 5 県で、岡山→兵庫 (2 県)、広島→愛媛 (しまなみ、通り口あり)、山口→兵庫 (4 県: 山口>広島>岡山>兵庫) の実機計測 (所要時間・ヒープ)。4 県チェーンの時間が許容でなければ、探索の最適化 (計画 P4/P5: 双方向 A* / Contraction Hierarchies) を先にやる。

## 5. リスクと判断が必要な点

1. **ディスクの余裕が約 10GB** と少ない。全国生成の前に整理の許可が必要 (`packages/_prev`、`work/` の古い中間ファイル)。
2. **配信方法** (アプリ内ダウンロードの配信元) は未決定。ここは設計だけでは進められない。
3. **4 県以上のチェーン時間**: 3 県で約 11 秒。5 県以上 (例: 山口→東京) は 30 秒級になる可能性。CH (Contraction Hierarchies) の導入 (大規模改修) を検討するか、上限を設けるか。
4. ライセンス表記: 県が増えても OSM/Overture/国土数値情報の表記は同じ。国土数値情報 (医療機関・学校) は全国版を使う。
5. 検索 DB の全国版 (Overture + MLIT + OSM 統合) の生成時間は未計測 (中国 5 県で数十分)。

## 6. 次の一手 (自律作業で進めるもの)
7-2 の前半 (47 件の `PREFECTURES` 定義と出力名の統一、愛媛・兵庫の 1 県生成の試行) は、ディスクとデータ量の都合で **生成の実行は朝の許可後**。先に **7-3 (b) 隣接表の 47 県化とその整合テスト** を進める (データ不要で、既存 5 県の動作を変えない)。

## 7. 進捗 (2026-11-14)

**7-3 (a) アプリ側の 47 県化**
- `RoutingGraphSelector` / `SearchDbSelector` / `TileSourceSelector` の対応表を、手書きの 8 県から `PrefectureData.ALL` 由来の 47 県に変えた (`<Romaji>.graph`、`<Romaji>.search.db`、`<id>.pmtiles`。山口だけ既配備名を先頭候補)。ここに載っていても端末にファイルがあるとは限らず、実在は呼び出し側が `resolveGraphFiles` などで確かめる。端末にグラフの無い県では現在地更新の先読みをせず、経路を求めたときに「ルートデータが端末に入っていません」と案内する。
- `MultiLegRoutePlanner` に固定されていた中国 5 県のリストをやめ、端末にグラフのある県だけをチェーンの候補にした。山口→広島→岡山→兵庫の 4 県チェーンの計画は単体テストで確認済み。

**7-4 実機計測 (Galaxy S26、Windows 署名の debug ビルドを `.win` の別パッケージで検証)**
- 岡山駅付近→姫路 (岡山>兵庫): 90.0km、CH、約 3.9 秒 (初回、グラフ読み込み 0.5 秒を含む)。
- 広島駅付近→姫路 (広島>岡山>兵庫): 251.6km、約 14.5〜17 秒。**CH ではなく A\* で解いている**。兵庫グラフの CH (約 150MB) を取り付ける際に `OutOfMemoryError` (ヒープ上限 512MB) が出て CH が使えなくなるため。
- 山口→姫路 (4 県): **OOM で失敗** (`CROSS_PREFECTURE fallback reason=OutOfMemoryError`)。県内だけの探索に落ちて「到達不能」になる。`device_regression.sh` では `HYOGO4=1` のときだけ走らせる (既知の課題)。
- 兵庫・岡山などグラフが大きい県が増えると、3 県以上のチェーンは同時に開くグラフと CH のヒープで頭打ちになった。→ 下の「3 県以上チェーンのヒープ超過 (修正)」で解消。

**見つけて直したバグ**
- 県またぎの始点・終点を「最寄りのノード」で固定していたため、駅前広場・公園など孤立した小さな道に当たると探索が数ノードで尽き、県またぎ全体が失敗して県内だけの経路 (49km で途切れる) にすり替わっていた。`nearestWellConnectedNodeIndex` (最寄りから順に 5000 ノード以上につながる候補を選ぶ) を `routing-core` に足し、`MultiLegRoutePlanner` の始点・終点に使った。最寄りが孤立していないときの結果は変わらない。

**3 県以上チェーンのヒープ超過 (修正、2026-11-14)**
- 原因: CH 本体 (`.graph.ch`) は mmap でヒープを使わないが、`ContractionHierarchy` がノード数に比例する作業配列を持っていた。`QueryWorkspace` (32 バイト/ノード) はコンストラクタで即確保、`ManyWorkspace` (48 バイト/ノード) は最初の `searchMany` で確保して CH が生きている間ずっと保持。兵庫 (183 万ノード) で約 59MB + 88MB、「CH 約 150MB」はこれ。常駐する借用ジョブ用グラフの分と重なり、ヒープ上限 512MB に届いていた。
- 直し (`ContractionHierarchy.kt` / `CrossChainChRouting.kt`): `QueryWorkspace` を `route()` の初回まで確保しない。`ManyWorkspace` の後ろ向き配列 (12 バイト/ノード、旧実装 `searchManyDijkstra` 専用) を必要になるまで確保しない。`releaseWorkspaces()` を足し、チェーンの各段を終えるたびに (借用グラフも含めて) 手放す。コスト定義・CH の結果は変えていない。
- JVM での計測 (`ChainHeapBench.kt`、アプリと同じ構成 = 先頭県は借用ジョブ用グラフ、他は都度 open/close。実機から pull したデータ): 広島>岡山>兵庫は -Xmx512m でピーク約 488MB → 約 230MB に低下し、-Xmx320m でも通る (修正前は 320m で OOM)。山口>広島>岡山>兵庫は -Xmx320m で通る。4 県の 3 OD を A\* のチェーン (-Xmx1500m) と突き合わせ、コスト・距離が一致。
- 実機 (Galaxy S26、`.win` ビルド、`HYOGO=1 HYOGO4=1 device_regression.sh --timeout 150`): 全 7 ケース OK。広島>岡山>兵庫は `CROSS_ENGINE ch graphs=3`、251.6km (A\* 時と同距離)、約 6.2 秒 (A\* だった 14.5〜17 秒から短縮。1 回目の計測値)。山口>広島>岡山>兵庫は `CROSS_ENGINE ch graphs=4`、425.2km、約 6.9 秒で、OOM せず成功 (修正前は到達不能)。
- 残り: 端末に `Okayama__Hyogo.portals` が無く、その段は ID 照合 (`CHAIN_PORTALS stage=1 source=match`)。`scripts/build_portals.py` で作って配備すれば、さらに短くなる見込み。

**7-3 (c) 県判定ポリゴンの 47 県化 (2026-11-14)**
- ポリゴンを Kotlin の定数 (8 県分で 250KB、1 定数 64KB の上限もある) から、`application/app/src/main/resources/prefecture_polygons/<id>.txt` (47 県、計 1.5MB) に移した。`PrefectureBoundaries` は必要な県のファイルだけを遅延で読む。生成は `scripts/build_prefecture_polygons.py` (`--check` で `boundaries/` との一致を確認)。既存 8 県の中身は数値として完全一致。
- テスト: 47 県の県庁所在地がそれぞれ自県に解決する、外接矩形が重なる県境 (姫路、川崎、生駒、淀川区)、`scripts/tests` に生成物と `boundaries/` の一致確認。実機 (`.win` パッケージ) でも、赤穂 (兵庫)・福山 (広島)・京都・札幌・那覇が正しい県に解決。
- 判定ロジック (`findPrefectureName`) は変更なし。外接矩形が 1 県だけの点はポリゴンを見ずにその県とする (海上など)。
