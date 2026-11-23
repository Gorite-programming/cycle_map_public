# CycleMap コード・UIレビュー

調査日 2026-11-18。対象はローカル `main` の `52355e1`。サブエージェント3名とルーティング、データ生成・配布、Android UI を分担した。

最優先は、**通行方向に反する経路、経由地で途切れる経路、県境での不完全な再探索、サイコンで隠れる通行警告**。いずれも走行中の判断に影響する。

`git pull` は最初に `.git/FETCH_HEAD` の権限エラーとなり、再試行も完了しなかった。リモートの最新状態は未確認。実機・エミュレーター・ADB は操作していない。調査中に追跡対象のソースは変更していない。

## 優先度高のバグと修正案

| 問題 | 発生条件と影響 | 修正・回帰テスト |
| --- | --- | --- |
| **環状交差点と自転車の一方通行を誤って経路化する** | 合成 PBF を現行 CLI で変換すると、`oneway` 未指定の環状交差点に逆向きの辺が生成された。`oneway:bicycle=yes` 単独でも双方向になり、`oneway=yes` と `oneway:bicycle=no` の組み合わせでは自転車が通れる逆方向が失われる。 | 経路生成と通行警告用の方向判定を共通化し、graph・idx・CH・県境接続データを再生成する。明示上書きも含め、合成 PBF から A* と CH の両方向の到達可否を検証する。[OsmImporter.kt:317](../application/osm-importer/src/main/kotlin/com/gorite/cyclemap/importer/OsmImporter.kt#L317) [BicycleInfrastructure.kt:104](../application/routing-core/src/main/kotlin/com/gorite/cyclemap/routing/BicycleInfrastructure.kt#L104) |
| **経由地の前後が別の道路に接続される** | 合成グラフで、前区間が `[1,2]`、後区間が別ノードにスナップして `[3,4]` となった。アプリと同じ連結処理では `[1,2,4]` となり、出辺のないノード2から道路のない区間をつなぐ。 | 前後の両区間が通れる共通ノードを選び、接続できない場合は該当する経由地を示す。一方通行の袋小路と平行道路を使い、経路線・案内・標高が同じ連続経路を参照することを検証する。[SnapFallbackRouting.kt:61](../application/routing-core/src/main/kotlin/com/gorite/cyclemap/routing/SnapFallbackRouting.kt#L61) [MapRouteCalculation.kt:336](../application/app/src/main/java/com/gorite/cyclemap/navigation/MapRouteCalculation.kt#L336) |
| **県またぎの自動再探索が、案内を無警告で部分経路に置き換え得る** | 県またぎ探索が失敗すると、出発県のグラフで再探索する。合成グラフでは半径400mを指定しても範囲外の最寄りノードが候補に入り、経度1度先の目的地が到達可能扱いになった。自動再探索で警告を抑えたまま経路を更新する分岐もコードで確認した。 | 完全経路と部分経路を結果の型で区別する。完全経路が得られなければ既存の案内を保持し、必要な県データを示す。隣県データの欠落と県境上の目的地を回帰テストにする。[LazyMappedRouting.kt:342](../application/routing-core/src/main/kotlin/com/gorite/cyclemap/routing/LazyMappedRouting.kt#L342) [MapRouteCalculation.kt:270](../application/app/src/main/java/com/gorite/cyclemap/navigation/MapRouteCalculation.kt#L270) [結果の反映](../application/app/src/main/java/com/gorite/cyclemap/navigation/MapRouteCalculation.kt#L501) |
| **サイコンで自転車通行禁止・逆走の警告が隠れる** | 同じ `Box` 内で警告の後に不透明な全画面 `Surface` を描画する。通常地図で警告が出る状況でも、サイコンでは画面の背後に隠れる構造。 | サイコン内に警告の専用領域を設ける。縦横の表示、速度表示との重なり、TalkBack での読み上げを確認する。[CycleComputerHost.kt:92](../application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerHost.kt#L92) [CycleComputerScreen.kt:43](../application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerScreen.kt#L43) |
| **県データ更新の失敗で新旧ファイルが混在する** | アプリの ZIP 取り込みは各ファイルを順に反映する。graph の置換後に idx が破損していると、新 graph と旧 idx が残り得る。配備スクリプトにも展開エラーを無視する箇所があり、SHA-256 の照合は本番名への書き込み後。 | 県データ一式を一時領域に置き、ZIP の CRC・ハッシュ・graph/idx の組み合わせを検証してから切り替える。ZIP 後半の破損、容量不足、転送中断を注入し、旧版で経路を引けることを確認する。[OfflineDataImporter.kt:58](../application/app/src/main/java/com/gorite/cyclemap/data/OfflineDataImporter.kt#L58) [転送処理](../scripts/push_data.sh#L311) [ZIP 展開処理](../scripts/push_data.sh#L365) |

## 走行記録・検索・更新で見つかった問題

- **到着音声と到着カードが食い違う。** 経路残り0m、実目的地まで200mの合成入力で、JVM の到着判定は未到着、音声判定は到着になった。GPS 途絶中の推測位置でも音声判定を呼ぶ。カード・ヘッダー・音声を同じ到着状態に接続し、測位1件、測位2件、推測位置のみ、目的地と経路終端の乖離をテストする。[NavigationStats.kt:150](../application/routing-core/src/main/kotlin/com/gorite/cyclemap/routing/NavigationStats.kt#L150) [VoiceGuidance.kt:343](../application/app/src/main/java/com/gorite/cyclemap/speech/VoiceGuidance.kt#L343) [MapTrackingEffects.kt:380](../application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapTrackingEffects.kt#L380)
- **サイコンの表示更新が有効な GPS 点を捨てる。** 同じ2測位でも、遅れて届く2点目の前に画面更新を挟むと、JVM 再現で積算距離が **2.224m から 0m** になった。表示用スナップショットを読み取り専用にし、測位の順序は測位の経過時刻で独立して管理する。[RideSession.kt:44](../application/app/src/main/java/com/gorite/cyclemap/tracking/RideSession.kt#L44) [CycleComputerHost.kt:48](../application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerHost.kt#L48)
- **位置権限がなくてもサイコンが走行中になる。** 画面が先に走行状態を更新し、サービス側も測位開始に失敗した後に GPX 記録開始へ進む。サービスの開始成功を確認して走行状態を更新し、権限拒否時は案内を出して空の記録を残さないようにする。[CycleComputerHost.kt:105](../application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerHost.kt#L105) [LocationTrackingService.kt:88](../application/app/src/main/java/com/gorite/cyclemap/tracking/LocationTrackingService.kt#L88)
- **近くのトイレや自転車店がスポット検索から漏れる。** 全カテゴリで近い60件を取ってからカテゴリを絞るため、SQLite の合成データでは約556m先のトイレが0件になった。カテゴリ条件を `LIMIT` より前に適用すると1件だった。同じ検索経路では破損 DB の例外が画面処理まで伝播する箇所もある。カテゴリ条件を SQL に移し、DB 異常は修復先を示せる結果として返す。[MapSearchEffects.kt:69](../application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapSearchEffects.kt#L69) [SearchNearbyHelper.kt:121](../application/app/src/main/java/com/gorite/cyclemap/search/SearchNearbyHelper.kt#L121)
- **データソースを個別更新すると、別ソースに残っている施設も検索から消える。** 国土数値情報の病院に OSM の行を統合後、国土数値情報側だけを削除すると、OSM 行の `merged_into` が削除済み ID を参照したままとなり、再マージ後も検索対象が0件だった。更新対象県の統合関係と補完値を元データから再計算する。[masterdb.py:123](../sandbox/data-tool/tools/core/masterdb.py#L123) [検索対象の抽出](../sandbox/data-tool/tools/core/masterdb.py#L151)
- **検索DB検証が `#` を含むパスで失敗する。** SQLite の読み取り専用 URI にパスを直接埋め込むため。Windows で再現し、`Path.resolve().as_uri()` でエスケープした URI では同じ DB を開けた。県パッケージ生成・検索DB検証・更新スクリプトの URI 組み立てを揃える。[build_prefectures.py:110](../build_prefectures.py#L110) [validate.py:37](../sandbox/data-tool/tools/core/validate.py#L37) [refresh_data.py:229](../scripts/refresh_data.py#L229)
- **データ更新前後の経路ベンチマークで、同じ出発地・目的地を比較できない。** 同じ乱数シードでも、抽選対象は各グラフ固有のノード配列。固定座標または共通 OSM ノード ID の組を両方へ渡し、経路の到達可否・距離・所要時間を同じ地点で比較する。[refresh_data.py:346](../scripts/refresh_data.py#L346) [GraphBenchmarkRunner.kt:37](../application/routing-core/src/main/kotlin/com/gorite/cyclemap/routing/GraphBenchmarkRunner.kt#L37)

## UI上の懸念

| 画面・操作 | 懸念と確認条件 |
| --- | --- |
| **オフライン地図の範囲選択** | 左上と右下の2点だけを逆投影するため、地図を回転すると選択範囲の一部が外れる。45°の幾何モデルで、2点の範囲が一方向に潰れることを確認した。四隅から範囲を計算し、3D の傾きがある場合も確認する。[MapViewsContainer.kt:333](../application/app/src/main/java/com/gorite/cyclemap/ui/layout/MapViewsContainer.kt#L333) |
| **地図の拡大・縮小** | 読み上げ名はあるが、操作がタッチジェスチャーに依存し、アクセシビリティのクリックアクションやキー操作がない。押し続ける操作に加え、通常のクリック、TalkBack、キーボードでも拡大・縮小できることを確認する。[MapControlsOverlay.kt:482](../application/app/src/main/java/com/gorite/cyclemap/ui/layout/MapControlsOverlay.kt#L482) |
| **目的地の入力直後** | 250ms のデバウンスと検索処理の間、前の候補が押せるため、別の地点を目的地に選び得る。表示中の候補を検索条件とひも付け、入力変更後は新しい結果が届くまで旧候補を無効にする。[DestinationSearchDialog.kt:187](../application/app/src/main/java/com/gorite/cyclemap/ui/components/DestinationSearchDialog.kt#L187) |
| **サイコンの小画面・文字拡大** | 124sp の速度表示、3段の計器、複数の操作を非スクロールの縦領域に置いている。低い画面や文字200%で操作が見切れる懸念がある。開始・一時停止・通常モードへの復帰がすべて表示され、操作できることを受け入れ条件にする。画面上の見切れ自体は未確認。[CycleComputerScreen.kt:78](../application/app/src/main/java/com/gorite/cyclemap/ui/cycling/CycleComputerScreen.kt#L78) |

## 今後追加すべき機能

1. **出発前のオフライン診断。** 出発地・経由地・目的地で必要な県データを表示し、地図・検索・経路・地形・通行ルールごとに利用可、未導入、検証失敗を示す。地図や検索の空状態から該当県の管理画面に移動でき、更新中断後の再開と旧版への復帰もできる構成を優先する。
2. **残りの経路に沿った立ち寄り検索。** 現在は現在地の県内・8km以内の候補を、走行済み区間も含む全経路への距離で並べる。進行方向の県境先も検索し、水・トイレ・修理を優先して、上位候補に実際の迂回距離と追加時間を出す。[MapSearchEffects.kt:118](../application/app/src/main/java/com/gorite/cyclemap/ui/effects/MapSearchEffects.kt#L118) [QuickSpotSheet.kt:148](../application/app/src/main/java/com/gorite/cyclemap/ui/cycling/QuickSpotSheet.kt#L148)
3. **出発前に使える検索範囲の切り替え。** 「現在地周辺」「表示中の地図」「導入済みの全県」を選べるようにし、地図を移動した先の施設を名前で検索できるようにする。[DestinationSearchDialog.kt:121](../application/app/src/main/java/com/gorite/cyclemap/ui/components/DestinationSearchDialog.kt#L121)
4. **走行記録のモード横断復元。** GPX とサイコンの距離・時間・獲得標高を同じ走行IDで保存し、通常画面やサイコンへの切り替え、プロセス終了後も一貫した状態に戻す。現行の統計退避はサイコン表示中に限定する設計。[design_cycle_computer_mode.md:97](../docs/design_cycle_computer_mode.md#L97)

## 検証結果

- `gradlew --offline test` は成功。Android の3バリアント、routing-core、osm-importer の結果に失敗はなかった。
- 検索DBツールは **118件成功**。県パッケージ・更新関連のスクリプトは対象を絞って **73件成功**。
- スクリプト全体は Windows の既定環境で失敗があり、Git Bash での再試行も完走していない。
- 合成 PBF、JVM の合成グラフと測位、SQLite の合成データで、各項目に記載した再現を確認した。画面描画、位置権限、読み上げに関する項目はコードレビューの結果。
