# 履歴の書き換え (2026-11-14)

端末のシリアル番号・自宅 Wi-Fi の SSID / BSSID・MAC アドレス・IP アドレス・logcat を含む検証記録 6 ファイルを、
全ブランチの履歴から取り除いた (`git filter-branch`)。ファイルが入った `3b6055d` 以降のコミットは、内容は同じでハッシュが変わっている。
`VERSIONS.md` や各文書に書かれた古いハッシュは、下の表で新しいハッシュに読み替える。

取り除いたファイル (すべて `Verification/evidence/20261018-chatgpt-run1/` の下):

- `preflight/adb-devices.txt`
- `preflight/connectivity-before.txt`
- `preflight/cyclemap-process-logcat.txt`
- `cyclemap-offline/connectivity-before.txt`
- `final-state/device-state.txt`
- `implementation/device-state-before-gpx-empty.txt`

同じ種類のファイル (`adb devices`、`dumpsys connectivity` / `wifi`、logcat の丸ごとの出力) は、今後リポジトリに入れない。

別の環境 (Mac など) の作業コピーは、古い履歴を持っている。そこから push すると消したファイルが戻るので、取り直す (`git clone` し直すか、
`git fetch origin && git reset --hard origin/main`。未 push の変更があれば先に退避する)。

| 旧 | 新 | コミット |
|---|---|---|
| `e6f109e` | `8a84d5f` | fix(security): 検証で使う段階でも閉じておくべき穴をふさぐ (診断 M1〜M3・L2・L3) |
| `c8371be` | `f21e3a6` | feat(app): 端末に保存した県データを取り込む機能と、並べて入れられる検証版ビルドを追加 |
| `de61d7e` | `27abbb0` | fix(map): Google マップとの見比べで見つかった地図の見た目と駅データの問題を直す |
| `a8849df` | `cbee0bc` | fix(push_data): adb で作った転送先ディレクトリをアプリから読めるよう chmod 777 する |
| `5412d84` | `d5a3847` | docs: POI ソース合算時のライセンス・帰属表示・スキーマ・重複統合の互換性調査 |
| `f656c58` | `c4c2c5b` | fix(platform): Windows で osmium.EXE がサブコマンドと誤認される問題を、拡張子を小文字にそろえて回避 |
| `6766d67` | `c3e8649` | refactor: ChugokuBoundaries を PrefectureBoundaries に置き換え、47 都道府県対応に合わせ |
| `62fc8a9` | `51ae7b4` | docs: 引き継ぎメモの古い記述を修正し、pmtiles バイナリを gitignore に追加 |
| `46b4b4b` | `1a4abeb` | docs: CLAUDE.md を整理し、日付つきの引き継ぎメモを docs/ に移す |
| `7af6e19` | `be347c0` | Merge branch 'perf/batch-osmium-extract': osmium extract を全県まとめて1回のスキャ |
| `eead09a` | `d4543da` | perf(build_prefectures): osmium extract を全県まとめて1回のスキャンにする |
| `0db1199` | `477dc5a` | Merge branch 'fix/ci-47-prefectures-test': 47 都道府県化に合わせてテストの前提を直す |
| `d0ed8c4` | `bc90263` | fix(data-tool): 47 都道府県化に合わせてテストの前提を直す |
| `cdeb509` | `c26c425` | Merge branch 'fix/importer-output-encoding': osm-importer の出力が Shift-J |
| `080bb26` | `764634c` | fix(build_prefectures): osm-importer の出力が Shift-JIS でもクラッシュしないようにする |
| `eb0b48b` | `022acdf` | Merge branch 'auto/nationwide-prep': 全県生成の準備 (47 県対応・Windows 対応) |
| `f08fd85` | `baf4a16` | feat(scripts): 全県生成の準備として 47 県対応と Windows 対応を入れる |
| `edc4f86` | `97c32e0` | Merge branch 'auto/20261113': Codex 委譲ダッシュボード、47県の境界、作業中の変更 |
| `dbb84d9` | `20286e3` | chore: 未コミットだった作業中の変更をまとめて取り込む |
| `58492fc` | `b4fa6ea` | data: 47都道府県の境界 GeoJSON を追加 (全国対応の準備) |
| `8a05c0a` | `152f9ef` | feat(scripts): Codex 委譲の実行一覧とダッシュボードを追加 |
| `631d9e2` | `ebad58c` | Merge branch 'theme/sumi-shu': 墨と朱のテーマ、下寄せの検索、計器盤の待ち受け |
| `f1710e4` | `66073c2` | feat(home): 地図画面の待ち受けを計器盤にする (UI 総合版の「弐」) |
| `b2239a4` | `4cc6bed` | feat(search): 検索画面を親指で使う下寄せの形にする (UI 総合版の「肆」) |
| `b59081c` | `d084d8d` | feat(ui): ダークモードを独自の「墨と朱」テーマにする (試験版) |
| `4711825` | `0f14453` | Merge pull request #1 from Gorite-programming/auto/20261110 |
| `d4d03cc` | `34e28f3` | docs: 状態の集約 (5c) の対応状況を revision/code.md に追記 |
| `44dc070` | `b13ab24` | fix(route): 起動時のグラフ読み込み中に頼まれたルートが計算されないままになる不具合を修正 |
| `2721c38` | `94b4440` | refactor(ui): 案内中の状態を MapViewModel に移し、案内中に裏で終了しても案内を再開する (状態の集約 3/3) |
| `489a416` | `cde61a7` | refactor(ui): 目的地・経由地・計算したルートの状態を MapViewModel に移し、裏で終了しても戻せるようにする (状態 |
| `f4b19b1` | `a3d4795` | refactor(ui): 検索・スポット一覧・POI 選択の状態を MapViewModel に移す (状態の集約 1/3) |
| `1523e50` | `75e6333` | docs: コードレビューの指摘への対応状況を revision/code.md に追記 |
| `960ec2c` | `b2fd5d6` | fix(ui): MapViewModel をアクティビティの ViewModelStore から取る |
| `93478e3` | `af8e5e7` | refactor(routing): 自転車のコスト係数を BicycleCost の 1 か所にまとめ、使われていない MappedRoa |
| `f609d1c` | `14636a1` | fix: 握りつぶしていた例外にログと理由を残す。cancelAndJoin を裏のスレッド専用と明示 |
| `b1c5372` | `22a9339` | refactor(ui): 開く手段のないルートのパネル (架空の保存済みルート) と経路選択シートを削除 |
| `dc67959` | `ce28293` | chore: 検証用の受信口 VerificationReceiver を debug 版だけに移し、作業ブランチ auto/** の pu |
| `f94da69` | `451f74b` | fix(search): 「コンビニ」「近くのトイレ」など種類名だけの検索語を、その種類の近い順の一覧にする |
| `851c1a9` | `63d59c3` | docs: テスト・CI の結果とコードレビュー (4 段階評価) を revision/code.md に記録 |
| `5cadc49` | `5c0f88f` | docs: 実機での UI 評価を revision/ui.md に記録 |
| `6962813` | `8f6864d` | docs: CLAUDE.md / AGENTS.md を現状に合わせて更新 (CH・通り口・県またぎ、CI と同じ確認の手順、引き継ぎメモ |
| `a29f62a` | `bdfaab4` | feat(ui): 保存/解除のトースト、ルートデータ関連エラーの平易化、現在地未取得時の出発地の明示 |
| `54cdab6` | `442352a` | fix(map): POI選択時の通常ピン非表示を、名前+約100m丸めの座標で判定(タップ座標のずれ対策) |
| `0d6b6be` | `4e9428d` | fix(map): POI選択時に通常ピンを隠し、強調ピンとの二重表示を解消 |
| `b2a72d8` | `0abc5c7` | feat(ui): アクセシビリティ — 下部タブに選択状態(Role.Tab)、小さい文字(9〜10sp)を11spへ |
| `aab3f2f` | `201744f` | feat(ui): ナビ終了の確認、ドロワー/設定の整理(開発者向けはdebugのみ・POIカテゴリ折りたたみ)、オフラインデータの状況表示 |
| `e006aef` | `1e22515` | feat(ui): タブのシートに閉じる/タブバー、レイヤー選択メニュー、ルートプレビューの小型化 |
| `344dbe2` | `b2f7aef` | perf(routing): 県またぎの通り口を事前計算してファイル化 (<A>__<B>.portals) |
| `deb6c3e` | `f0e2371` | feat(pipeline): CH の生成を build_ch.py に追加し refresh_data に組み込み (S4)。5県分を生 |
| `30e502f` | `18c65f6` | perf(routing): チェーンで ID 索引を段またぎで再利用 (通り口照合のソート1回減)。実機 3県 約3.0〜3.5秒。doc |
| `31491c5` | `ae1d676` | perf(routing): 県またぎの I/O 削減 — CH の距離計算で全辺の種別表を作らない(1.3秒→0.02秒)、チェーン用グラ |
| `40bd680` | `887a35c` | perf(routing): searchMany を RPHAST 方式に (通り口ごとの後ろ向き探索を一括の掃引へ)。実機の中間県探索  |
| `e2abed0` | `d9a14db` | chore(routing): searchMany の計測ログ (CHAIN_DETAIL) |
| `d69d7d8` | `94e106a` | feat(routing): CH S3 — 県またぎを CH の多対多(searchMany)で探索 (routeAcrossChainC |
| `55ab879` | `4f37816` | feat(routing): CH S2 — mmap 読み込み、同県探索への組み込み (CH優先・A*フォールバック)、初回問い合わせの裏 |
| `4303947` | `ac3c9a3` | perf(routing): CH ファイルを graph 比 約0.73倍に削減 (辺16B、平行辺除去、距離は展開後に計算、graph識 |
| `371b6b8` | `af3aafe` | feat(routing): Contraction Hierarchies の JVM 試作(S1)。前処理・双方向問い合わせ・A*との突 |
| `599745a` | `8a18d21` | docs: Contraction Hierarchies 導入設計 (S1〜S4 の段階と go/no-go 基準) |
| `aed4143` | `def13e3` | fix(ui): 地図を動かした先に検索データが無いとき、前の場所の地名を残さず「名称なし」にする (実機確認) |
| `b075ce0` | `455850b` | fix(map): 地図データの無い県(例: 兵庫)を見ているとき、灰色のまま黙らず案内バナーを出す。データのある県へ戻ると消える (実機確 |
| `f0041d1` | `b4393ad` | fix(map): 手動スクロールで別の県へ移動したとき、地図中心の県のタイルへ切り替える (現在地基準のみで、岡山へ動かすとベクター地図が |
| `86c6a72` | `6a96ae9` | feat(route): 県またぎの隣接表を47都道府県に拡張 (橋で繋がる組を含む、北海道・沖縄は孤立)と整合テスト。既存5県の隣接は不変 |
| `7651ee3` | `96f455b` | docs: 全国対応(47県)の設計書 (7-1) |
| `8599116` | `7186aa6` | fix(ui): 地図タイル保存ダイアログの表記を整理 (タイルのみ保存・経路/検索データは中国5県のみ対応と明記) |
| `d2bafb0` | `d35fc01` | fix(download): タイル取得で HTTP 200 以外のとき試行回数が進まず無限再接続していた不具合を修正。オフライン動作の点検 |
| `4d8b1b5` | `daa23c4` | fix(ui): プレビューの下余白に経由地の行数ぶん(60dp/件)を加算 (テスト通過、実機の見え方は未確認) |
| `a3e8f5f` | `39fddab` | feat(ui): ルートプレビューに経由地リスト(↑↓並べ替え・×削除)を追加。削除で再計算され元の2県ルートと同一結果に戻ることを実機で |
| `123c6ef` | `216f647` | feat(ui): ルートプレビューの fit に最小スパン(400m)と画面高に応じた余白を追加。実機 335km で全ルートがシート上に |
| `543f2a0` | `0afb918` | feat(scripts): 実機の経路回帰スクリプト (同県/2県/3県の結果表、失敗で非0終了) |
| `5b8d3b3` | `31ff887` | perf(route): 3県チェーンの経路復元で再探索をやめ、出口の通り口ごとに入口 seed を記録して単一始点 A* で復元。実機 1 |
| `eb894aa` | `586b171` | chore(route): 3県チェーンの段階別時間ログ (CHAIN_STAGE) を追加。実機: 経路復元 5.0秒が最大 (中間探索  |
| `3210b83` | `966dbe3` | perf(routing-core): グラフ索引をmmap直読みにして Java ヒープを削減、隣県の共通ノード照合はID索引のみ読む。実 |
| `a528132` | `ee36770` | perf(route): 3県チェーンの作業領域・親スナップショットを削減、OOM時は『メモリ不足』と警告 (JVMピーク 418→344M |
| `5b9d8c6` | `75de008` | fix(route): 県グラフ切替で中止された経路計算を1回だけ再実行、長距離案内の消し忘れ、プレビュー標高軸の単位切れ |
| `926d1b2` | `870a565` | fix(ui): ナビの到着時刻の折返し、県またぎ警告文、住所の重複、星の二重表示、表記の整理、スポット行タップで詳細を開く |
| `22b2f00` | `4bef6a4` | docs: 経由地つき県またぎ・リルートの実機確認を追記 |
| `c683323` | `a13df99` | feat(route): 経由地つき・3県以上の県またぎ経路をアプリに配線 (山口→岡山 335.7km を実機 9.5 秒) |
| `3c745a5` | `b4919be` | feat(routing-core): 3県以上をまたぐチェーン探索 routeAcrossChain を追加 (山口→岡山 335.7km |
| `d7a2f83` | `fb7a4f2` | docs: 県またぎ経路の拡張設計 (経由地つき・3県以上) |
| `7c8efd6` | `c97480e` | feat(ui): 設定に「オフラインについて」を追加、経路が見つからないときの案内を具体化。報告書に追記16 |
| `ae3149c` | `b0fa181` | fix(route): 最寄りノードが孤立した道(公園内など)で到達不能になるとき、次に近い候補で引き直す(平和公園周辺の実データで 192 |
| `afa5bb8` | `9f896cc` | feat(route): 計画ルートのGPX書き出しと、GPXの読み込み(ルートとして表示・ナビ開始)を追加 |
| `15a975b` | `1f1dcd4` | feat(ui): 地図データ未導入のバナーを、必要なファイル・置き場所・手順を示す案内に作り替える(対応外の県は対応県を案内) |
| `90bf751` | `eb054ea` | chore: 未使用スタイル3つと実験ルーター(HSA*/ALT)を削除、位置座標のログをデバッグビルド限定にする |
| `0758672` | `95d0687` | docs: Kotlin CI 失敗の原因と解決を引き継ぎメモに記録 |
| `5ef0a01` | `8a842d8` | fix(ci): gradle.properties から Mac 専用の org.gradle.java.home を外す (CI の J |
| `5f79875` | `1d9066b` | fix(ci): gradle-wrapper.jar を追跡する (.gitignore の *.jar で除外され、CI の ./gra |
| `e311b30` | `929f5e6` | fix(nav): 道路から離れた位置で再計算が繰り返される問題を、前回の再計算地点から30m以内では再要求しないことで止める |
| `8ee7796` | `f6943df` | docs: 県またぎ経路の実機結果を追記15と引き継ぎメモに記録 |
| `3df351d` | `058f416` | feat(route): 県をまたぐ経路をアプリで引いて表示する (広島 + 岡山、グラフは分けたまま) |
| `a669736` | `2597e35` | feat(routing-core): グラフを分けたまま県をまたぐ経路を引く探索を追加 |
| `8e04fbd` | `59e032c` | feat(route): 県をまたぐ長距離の計算中案内と「県またぎ未対応」の警告を出す |
| `7838cf4` | `023c14c` | perf(route): 代替ルートを主経路の表示後に計算する (表示まで 13.2 秒 → 1.1 秒) |
| `1bc2372` | `84eea5f` | docs: A* 最適化プランと Codex 委譲の運用を追加、報告書に追記13 |
| `14d0265` | `32fd73c` | docs: Windows への移行の取りやめに合わせて引き継ぎメモの表現を直す |
| `365c444` | `78e3005` | perf(routing): A* と経路の後処理の高速化 (途中)、Windows 移行のため未完了を記録 |
| `0619363` | `3cd2e61` | fix: 実機確認で見つかった検索・標識の問題を修正、報告書に追記11 |
| `939d17a` | `e120cb7` | feat: 検索・地図・案内・基盤の改善 (Google マップとの差の解消、18項目のうちコード分) |
| `863e920` | `eb466ca` | feat(place): 場所の詳細に営業時間・電話・Web・車椅子を表示 |
| `4349761` | `30c6484` | fix(importer): トンネル・橋の途中のノードの標高を地表ではなく両端から補間する |
| `4579c62` | `197180f` | feat(map): 案内中の現在地マーカーを進行方向を向く青い矢じり形に変更(GPS状態で色替え) |
| `6d2514e` | `d82e1d4` | feat(route): 代替ルートの選択を配線(候補チップ、選んだ経路で案内を作り直し) |
| `8324462` | `a1e855b` | fix(map): 灰色の代替ルートが経路クリア・ナビ開始後も残る不具合を修正 |
| `a74b35e` | `aa63e20` | docs: テスト名の誤記を修正 |
| `bde73ab` | `b379cbe` | feat(routing,elevation): 平坦優先の上りペナルティを強化、標高プロファイルをノードの絶対標高から直接作成 |
| `a4e3851` | `6cfc0a8` | feat(elevation): ノードの絶対標高を.graph.idx末尾に持たせ、標高プロファイルを絶対標高で表示 |
| `f9aea9e` | `d40d479` | feat(elevation): 標高プロファイルと獲得標高をグラフの勾配から算出(オフライン対応、架空値の廃止) |
| `8bde554` | `73819e7` | feat(importer): 経路の実際の登り量を方針別に測るAscentBenchmarkを追加、実機確認の結果を報告に追記 |
| `42ac23d` | `b57e62c` | docs: 勾配のグラフ書き込みと新graphの実測・実機転送を報告に追記 |
| `be79b26` | `2f02079` | feat(importer): 標高タイル(GSI dem_png)から辺の勾配を計算してグラフに書き込む |
| `0954d66` | `6ecb1dc` | docs: 外出中セッションの報告を書き直し(実行ログ混入の修正、実機への意図しない転送を記録) |
| `bdc0232` | `f070b8c` | feat(routing): 実グラフのベンチ基盤(GraphBenchmarkRunner)と外出中セッションの報告 |
| `e5e97b0` | `9eae2f5` | refactor(map): POI取得範囲とカメラ停止時の再取得判断をVectorMapLogicに抽出しテストを追加(挙動不変) |
| `81deb88` | `20f1589` | chore: CI(GitHub Actions)とcheck_all.shを追加、未参照コード(TopSearchBar, RealRer |
| `15319a0` | `033a142` | feat(data): データ更新の一括パイプライン refresh_data.py を追加(ステージング・検証・昇格) |
| `ba4bc9d` | `4de16ea` | feat(search): 営業時間パーサを実データ向けに強化(後ろの規則が前を上書き、規則間カンマ、25時表記、全角・ダッシュ正規化) |
| `3a6fd13` | `2288f24` | feat(data-tool): 検索DBに営業時間・電話・Web・車椅子を追加 (Stage 4 データ生成側) |
| `e42fb2f` | `c5a1f43` | docs: α0.9.8-unified のAPK SHA-256(0e89f67763c9)を台帳に記録 |
| `9edb84e` | `5f94530` | feat(map): POIピンと国道・県道標識の見た目を磨き込み、駅・温泉・パンのアイコンを描き直し |
| `5e95451` | `01cf341` | chore: versionName α0.9.8-unified / versionCode 10 |
| `d053e9e` | `f3013a0` | docs: α0.9.7-unified のAPK SHA-256(afa794c8a21c)を台帳に記録 |
| `2226ee0` | `2395320` | chore: versionName α0.9.7-unified / versionCode 9 |
| `e84c5dc` | `d78a92c` | feat(map): POIの出し分けとカテゴリ拡充 (Stage 3)、等高線・陰影起伏の地形表現 (Stage 2-5) |
| `ed6d003` | `c533602` | feat(map): 土地利用を森林・農地・墓地・住宅地・商業地・運動場・軍用地に塗り分け、水域を濃く (Stage 2-4) |
| `c58c01a` | `4bbfe8f` | docs: α0.9.6-unified のAPK SHA-256(20c8bfe15c3a)を台帳に記録 |
| `81ac6bd` | `ba1baee` | chore: versionName α0.9.6-unified / versionCode 8 |
| `68f6731` | `8b01837` | feat(map): 地名ラベル・国道県道の標識・鉄道ゼブラ表示で地図の情報量を拡充 (Stage 2-1〜2-3) |
| `2ca1ed4` | `f3654fc` | feat(map): 県境でタイルを切替、debug用カメラ指定と基準線撮影スクリプトを追加 |
| `2c3637a` | `fb58ce5` | docs: α0.9.5-unified のAPK SHA-256(c06957a418fe)を台帳に記録 |
| `8135a7e` | `906d082` | chore: versionName α0.9.5-unified / versionCode 7 |
| `1816ef6` | `b51095a` | feat(tiles): 5県のベクタータイル(PMTiles)取得を本流に統合 |
| `4641bca` | `efa1cf3` | docs: 現在の実機表記を Galaxy S21 から Galaxy S26 (SC-51G) に修正 |
| `bd95ba9` | `5e81296` | docs: α0.9.4-unified のAPK SHA-256(36f6464cca28)を台帳に記録 |
| `d4af480` | `2e3b666` | chore: versionName α0.9.4-unified / versionCode 6 |
| `a42fb93` | `eb39bb5` | fix: 精査で見つけた不具合9件を修正 (access/vehicle制限とbicycle明示許可、GPS外挿の二重先読み、距離表示、坂予 |
| `1c72444` | `5443608` | docs: α0.9.3-unified のAPK SHA-256(3891fb304afc)を台帳に記録 |
| `1af1cc6` | `3ca3737` | chore: versionName α0.9.3-unified / versionCode 5 |
| `3c30b43` | `a2d6da1` | feat(nav): 案内画面をC案(サイクコン)に刷新し、現在地・回転・ズームを滑らかに |
| `5b1f710` | `c9f54da` | docs: α0.9.2-unified のAPK SHA-256(e3c0ba0a8a45)を台帳に記録 |
| `ecf9c3c` | `2312d0d` | feat(data-tool): 検索DB一括再生成スクリプトと再生成・転送の一括実行スクリプトを追加 |
| `e3a5e65` | `c424de0` | fix: 経路探索・検索・地点詳細の不具合6件を修正 (歩道/階段の除外、勾配コスト同期、LIKEエスケープ、同名地点解決、mmap上限チェ |
| `847866b` | `3ac0bbe` | docs: α0.9.1-unified のAPK SHA-256を61d50d3df55cに更新 |
| `412c933` | `707a630` | fix: versionNameをα0.9.1-unifiedに更新し、push_data.shの転送先を.testパッケージに修正 |
| `b4a2df2` | `6244978` | docs: α0.9.1-unified のAPK SHA-256(7ec86416c403)を台帳に記録 |
| `cd9166b` | `09baa3f` | docs: READMEの全面刷新とVERSIONS.mdへのα0.9.1-unified行の追加 |
| `3b6055d` | `3331abc` | fix: 全28件のバグ・UI/UX懸念点の解消 (CRIT-01~04, HIGH-01~08, MED-01~13, LOW-01~03 |
