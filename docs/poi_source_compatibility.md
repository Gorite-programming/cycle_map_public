# POI ソース合算時の互換性 (ライセンス・帰属表示・スキーマ・重複統合)

2026-11-14 作成。対象は検索 DB (`sandbox/data-tool/` → `<name>.search.db`) に POI を入れるソース。
既存4ソース (OSM / Overture Places / 国土数値情報 P04・P29) と追加候補7ソースを全部合算する場合を想定する。
ライセンスは各一次情報のページで 2026-11-14 に確認した (出典は末尾)。法的な助言ではない。判断が分かれる点は「要確認」とした。

## 0. 結論

| ソース | ライセンス (確認結果) | 合算 | 条件 |
|---|---|---|---|
| OpenStreetMap | ODbL 1.0 | **可 (基盤)** | 統合 DB 全体が ODbL の派生 DB になる (§1.1) |
| Overture Places | **提供元ごとに別**: Meta・Microsoft など10社は CDLA-Permissive-2.0、Foursquare 由来は Apache 2.0、AllThePlaces は CC0 | **可** | Foursquare 由来の行があるなら Apache 2.0 本文と NOTICE.txt を同梱する。いまの取り込みは提供元を記録していない (§2, §3) |
| 国土数値情報 P04 医療機関 (2020) | オープンデータ (国土数値情報の利用規約。PDL1.0 準拠) | 可 | 出典と「加工して作成」を表示する |
| 国土数値情報 P29 学校 (**2021 版**) | オープンデータ | 可 | 同上。**2013 版は非商用**なので版を変えないこと |
| 医療情報ネット (厚労省) | **PDL1.0** (公共データ利用規約 第1.0版)。e-Gov データポータルのメタデータは「CC BY」のままで、厚労省のページと食い違う | **可** | 出典と加工の表示。座標列の有無と測地系は定義書で要確認 |
| Foursquare OS Places | Apache 2.0 (2025 年に取得方法が登録制のポータルに変わったが、ライセンスは変わらず) | 可 | NOTICE.txt の同梱と変更の明示。Overture 経由の Foursquare 行と重複する |
| 自治体オープンデータ (広島県ポータル) | 広島県は **CC BY 2.1 JP**。市町は個別 | 条件付き | データセットごとにライセンスを確認する。CC BY 系なら帰属表示で可 |
| Wikidata | CC0 | 可 | 義務なし (出典を書くのが望ましい) |
| GTFS-JP (バス) | 事業者ごと (多くは CC BY 4.0) | 条件付き | 事業者ごとに確認する |
| アドレス・ベース・レジストリ | PDL1.0 (地番マスターは法務省の別規約) | 可 (住所→座標の変換用) | 出典と加工の表示。地番マスターは使わない |
| 国土数値情報 **郵便局 P30・警察署 P18・消防署 P17・都市公園 P13・道の駅 P35** | **非商用** (消防署は「有償刊行物を使用して作成したため商用利用不可」) | **不可** | APK とデータを公開で配っているので入れない。前回の候補から外す |

**要点**
1. ライセンスの上では、非商用の国土数値情報5種を除けば全部合算できる。
2. 合算した検索 DB は **ODbL の派生データベース**として扱い、配布する ZIP の LICENSE.txt にそう明記する必要がある (いまは書いていない)。
3. いちばん手間がかかるのは Overture と Foursquare の行ごとの出所の記録と、同じ施設の重複統合。ライセンスそのものより、こちらが作業の本体になる。

## 1. ライセンスの両立性

### 1.1 ODbL と統合 DB (Derivative か Collective か)
- OSMF の Collective Database Guideline / Horizontal Layers Guideline の考え方では、ある**地物の種類 (例: 飲食店) のデータが全部 OSM か全部非 OSM**なら Collective Database になり、share-alike は OSM 部分にしか及ばない。
- CycleMap の統合 DB は、同じ種類 (コンビニ、飲食店、病院など) で OSM と非 OSM を混ぜている。さらに `merge.py` の `apply_merge` は、OSM の行に Overture の住所・カテゴリ・詳細情報を**項目単位でコピー**する。したがって Collective には当たらず、**派生データベース (Derivative Database)** と見るのが妥当 (保守的な判断)。
- 結果として、**`<name>.search.db` を公開で配る以上、ODbL 1.0 で提供する**必要がある。ZIP に DB 本体が入っているので、「機械可読な形で提供する」という義務は満たしている。
- `.graph` / `.graph.idx` も OSM からの派生 DB なので同じ扱い。地図の表示 (タイル) は Produced Work なので、帰属表示だけでよい。
- アプリのコード (Apache や MIT などのソースライセンス) には ODbL は及ばない。

### 1.2 ほかのソースを ODbL の DB に入れてよいか
| 流入元 | 判断 |
|---|---|
| CDLA-Permissive-2.0 | 可。Results (加工結果) は任意の条件で共有してよく、Data をそのまま共有するときにライセンス本文を付ければよい |
| Apache 2.0 | 可。ライセンス本文と NOTICE を残し、変更した旨を明示する |
| CC0 | 可。義務なし |
| PDL1.0 / 国土数値情報のオープンデータ | 可。出典と「加工して作成」を表示する。PDL1.0 は CC BY 4.0 と互換とされる |
| CC BY 2.1 JP / CC BY 4.0 | 可 (要確認)。帰属表示を保てば問題ないと考える。ただし ODbL は「平行配布をすれば技術的保護手段を許す」ので、CC BY の DRM 禁止条項との関係は厳密には論点が残る。CycleMap は DRM を使わないので実害はない |
| 非商用 (国土数値情報5種) | **不可** |

### 1.3 配布物ごとに必要なこと
- **APK (Public リポジトリ・配布):** データは入っていない。アプリ内のクレジットに全ソースの帰属を表示する (§2)。Apache 2.0 のデータを使うなら NOTICE の内容もアプリ内に表示する。
- **データパッケージ (`packages/*.zip` と付属ファイル):** LICENSE.txt に次を書く。
  - 「この DB は ODbL 1.0 で提供する」
  - 各ソースの帰属
  - CDLA 本文の参照
  - Apache 2.0 本文と NOTICE.txt
  - 加工したことの明示

## 2. 帰属表示: いまの状態と追加が必要なもの

いまの表示箇所:
- `build_prefectures.py` の `PACKAGE_LICENSE_TEXT` (ZIP の LICENSE.txt)。OSM / Overture (CDLA のみ) / 国土数値情報 / Protomaps
- `build_prefectures.py` の manifest の `sources` (172〜182 行あたり)
- `sandbox/data-tool/tools/core/config.py` の `KOKUDO_LICENSE_LINES` (DB の meta)
- アプリの `ui/components/MapDialogs.kt` (236〜267 行あたり)。OSM / 地理院 / Overture (CDLA) / 国土数値情報 / Protomaps

足りないもの:
1. **統合 DB を ODbL で提供するという明記** (LICENSE.txt と manifest)。
2. **Overture の複数ライセンス化への追従**。Overture Places は Foursquare (Apache 2.0) と AllThePlaces (CC0) を含むようになったが、表示は CDLA だけになっている。取り込んだ行に Foursquare 由来があれば、Apache 2.0 本文と Foursquare の NOTICE が必要になる。
3. 新しいソースを足すたびに、出典文を config・LICENSE.txt・manifest・MapDialogs の4か所でそろえる必要がある。**1か所 (config) に定義を集め、ほかはそこから生成する**ようにしたい。
4. PDL1.0 系 (医療情報ネット、アドレス・ベース・レジストリ) の書式は「出典：『〈タイトル〉』(〈府省〉) (URL)」+ 加工した旨。

## 3. スキーマの互換性

| ソース | ID | 座標 | 住所 | カテゴリ | 営業時間・電話・Web | 鮮度 |
|---|---|---|---|---|---|---|
| OSM | type+id (編集で変わり得る) | WGS84 | `addr:*` (11% のみ) | タグ | `opening_hours`・`phone`・`website` タグ (少ない) | 随時 |
| Overture | GERS ID。`sources[]` に提供元 dataset と record_id | WGS84 (`bbox` から取得) | `addresses[]` (97%) | `taxonomy` / `basic_category` | `phones[]`・`websites[]` (営業時間はない) | 月次。`confidence`、`operating_status` |
| Foursquare OS | `fsq_place_id` | WGS84 | 住所の項目あり | FSQ カテゴリ ID | tel・website (時間の有無は要確認) | `date_refreshed`・`date_closed` |
| 国土数値情報 P04/P29 | なし (コード類) | JGD2011 (POI の精度では WGS84 と同じに扱ってよい) | あり | 分類コード | なし | 2020 / 2021 時点 |
| 医療情報ネット | 医療機関 ID | 座標列がある (測地系は要確認) | あり | 種別・診療科 | **診療時間票**・案内用の Web | 年次程度 (最新は 2026-06-01 時点) |
| 自治体 OD | 自治体ごと | 主に緯度経度 | あり | 標準データセットの項目 | 一部あり | 不定 |
| Wikidata | QID (OSM 側に `wikidata` タグで相互参照がある) | WGS84 | 少ない | P31 (instance of) | Web | 随時 |
| GTFS-JP | `stop_id` | WGS84 | なし | バス停 | なし | 改正ごと |

現行の `places` テーブル (`export.py`) との対応:
- 非 OSM の行は `osm_type='ext'` で入っていて、**ソース側の ID を持っていない**。次の版の取り込み・取り消し・相互参照ができないので、`source_id` (例: `overture:<GERS>`, `fsq:<id>`, `mhlw:<医療機関ID>`) を追加する。
- `opening_hours / phone / website / wheelchair` の列は `export.py` にもうある。ただし `out/hiroshima.search.db` (11-03 生成) にはこの列がなく、`out/fukuoka.search.db` (11-13 生成) は列があっても**全件空**。取り込みから出力までのどこかで値が落ちている (要調査)。
- 足したい列:
  - `source_id`
  - `source_dataset` (Overture の `sources[].dataset`。ライセンス判定に使う)
  - `license`
  - `updated_at`
  - `opening_hours_source` (どのソースの時間か)
- 医療情報ネットの診療時間は「曜日×時間帯」の表なので、アプリの `OpeningHoursParser` が読める OSM の `opening_hours` 形式へ変換する。

## 4. 重複統合 (conflation) の方針

いまの仕組み (`merge.py`):
- 優先度は manual 100 > kokudo 50 > csv 40 > osm 30 > overture 20。
- 候補は「互換カテゴリ・50〜100m・名前の類似」で検出し、勝者に住所・詳細・カテゴリを補完して、敗者に `merged_into` を付ける。
- それでも広島 DB には、同じ名前が 50m 以内にある組が約 1.9 万組残っている。統合が自動ですべての県に適用されていないか、条件が狭すぎる可能性がある。

追加ソースに向けた方針:
1. **ID による相互参照を先に使う。** OSM の `wikidata` タグ ↔ Wikidata、Overture の `sources[]` (Foursquare の record_id) ↔ FSQ OS、医療情報ネットの ID ↔ (住所の正規化を介して) P04。距離と名前で判定するのはその後にする。
2. **名前の正規化:** `normalize.py` の NFKC・かな変換に加えて、チェーン名と店名 (「〇〇店」) を分けて比べる。
3. **距離の閾値をカテゴリごとに変える:** 駅や大学など広い施設は大きく、コンビニは小さくする。
4. **属性ごとの優先順位:**
   - 名前・カテゴリ: 公的データ > OSM > Overture/FSQ
   - 住所: Overture/公的データ > OSM
   - 診療時間: 医療情報ネットだけ
   - 店舗の営業時間: OSM > FSQ
   - 電話・Web: 公的データ > Overture/FSQ > OSM
5. **閉店の除外:** Overture の `operating_status`、FSQ の `date_closed` を見て除外する。
6. **ライセンス上の注意:** 項目単位で混ぜた時点で ODbL の派生 DB になる (§1.1)。そのため「どの項目がどのソースか」を `*_source` 列で残し、帰属と取り消しをできるようにする。

## 5. 導入の順番・工数感・リスク

| 順 | 作業 | 工数感 | 効果 |
|---|---|---|---|
| 1 | 帰属表示の修正: ODbL の明記、Overture の複数ライセンス、出典定義の一元化 | 小 (半日) | 法的リスクを下げる。**最優先** |
| 2 | 詳細列が空になる原因の調査と修正 (既存の Overture の電話・Web を出力に通す) | 小〜中 | 新しいソースなしで電話・Web が埋まる |
| 3 | Overture の `sources[].dataset` と GERS ID の記録 (`source_id` 列) | 小 | ライセンス判定と相互参照の土台 |
| 4 | 重複統合の強化 (§4 の 1〜3)、全県への自動適用、残った重複の再測定 | 中 (2〜3日) | 約 1.9 万組の重複の解消、ピンの密集の緩和 |
| 5 | 医療情報ネットの取り込み (診療時間を opening_hours に変換、P04 と対応づけ) | 中 | 医療機関の営業時間を初めて実現 |
| 6 | Overture の `named` 26,518 件のカテゴリ対応表の拡張 | 小〜中 | 種類のない POI が減る |
| 7 | Foursquare OS Places (Overture 経由の FSQ 行と重複除去してから) | 中〜大 | 個人店の網羅率。閉店データの混入に注意 |
| 8 | 自治体 OD (トイレ・AED・避難所)、Wikidata (名所の別名)、GTFS (バス停の読み) | 県・事業者ごとに小 | 自転車用途に効く施設の補強 |

リスク:
- 非商用データの混入。国土数値情報は**版ごとに**区分が違う (例: P29 は 2013 版が非商用で 2021 版がオープン)。版を上げる・下げるときは毎回確認する。
- ライセンスの変更。Overture は提供元が増えるたびに条件が変わる。取り込み時に `sources[].dataset` を集計し、未知の dataset があれば止める仕組みが望ましい。
- FSQ / Overture の閉店済み・座標ずれ (駅の位置ずれは `drop_mislocated_overture_stations` で一部対処済み)。

## 6. 要確認事項
1. 医療情報ネット: 定義書で座標列の測地系と更新頻度を確認する。e-Gov の「CC BY」表記と厚労省の「PDL1.0」の食い違い (厚労省の表記を正とする)。
2. Overture: スキーマ上 `sources[].dataset` で提供元を識別できるか、行ごとの `license` 項目があるかを、使っているリリース (`2026-09-23.1`) で確認する。そのリリースに Foursquare 由来の行が広島県内で何件あるか。
3. CC BY 系のデータを ODbL の DB に入れたときの解釈 (§1.2)。
4. 市町のオープンデータと GTFS-JP の、データセットごとのライセンス。
5. `opening_hours/phone/website` が出力で空になる原因 (§3)。
6. CycleMap の配布形態 (無償か有償か、ストアに出すか)。非商用データは「無償なら可」とは読まず、除外を続ける前提。

## 出典 (2026-11-14 閲覧)
- OSMF Community Guidelines: https://osmfoundation.org/wiki/Licence/Community_Guidelines 、Horizontal Layers: https://osmfoundation.org/wiki/Community_Guidelines/Horizontal_Map_Layers_-_Guideline 、Collective Database: https://wiki.openstreetmap.org/wiki/Open_Data_License/Community_Guidelines
- Overture 帰属・ライセンス: https://docs.overturemaps.org/attribution/ 、Places ガイド: https://docs.overturemaps.org/guides/places
- Foursquare OS Places: https://docs.foursquare.com/data-products/docs/fsq-places-open-source 、方針変更: https://foursquare.com/resources/blog/data/evolving-fsq-open-source-places/
- 医療情報ネット オープンデータ: https://www.mhlw.go.jp/stf/seisakunitsuite/bunya/kenkou_iryou/iryou/newpage_43373.html 、e-Gov: https://data.e-gov.go.jp/data/ja/dataset/iryou_teikyouseido_mhlw
- 国土数値情報: P04 https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P04-v3_0.html 、P29 https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P29-v2_0.html 、P30 https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P30.html 、P18 https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P18.html 、P17 https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P17.html 、P13 https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P13.html 、P35 https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-P35.html
- アドレス・ベース・レジストリ利用規約: https://www.digital.go.jp/policies/base_registry_address_tos
- 広島県オープンデータ: https://www.pref.hiroshima.lg.jp/soshiki/266/opendata.html (ポータル https://hiroshima-opendata.dataeye.jp/)
