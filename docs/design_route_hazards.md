# 設計: ナビ中の「注意地点」(踏切・橋) の表示

2026-11-21 に Gorite の要望で起こした設計。**状態: 設計のみ (未実装)**。実装は下の 3 段階に分けて進める。

## 目的
ナビ中に、これから通る **踏切** と **橋** を、アイコンと距離つきで知らせる。
例: 「踏切 あと 300 m」「橋 (錦帯橋) あと 800 m」。曲がり角の案内とは別の、小さなチップで出す。

決まっていること (Gorite の回答):
- 対象は **踏切 + 橋**。トンネル、急な登り・下り坂は今回は対象外 (後で足せるように作る)。
- 出し方は **ナビの上部にアイコンと距離**。地図上の経路にアイコンを重ねる表示と、音声案内は、今回は入れない。

## 方針 (なぜ新しいデータファイルを作るか)
- いまの経路データ (`.graph`) には、踏切・橋・トンネルの情報が入っていない (辺の道路種別・勾配・道路名・自転車情報のみ)。
- グラフに足すと、全県の作り直し (約 1.5 時間 + CH + 転送) になる。そこで、**グラフとは別の小さなファイルを OSM から作り、経路と照合する。**
- ファイルが無いとき、読めないときは、何も出さずに今までどおり動く (CH・通り口・等高線と同じ、任意の成果物)。
- 全国を 1 ファイルにする (`packages/hazards.bin`)。県またぎの経路でも通り口の処理が要らない。

## データファイル `hazards.bin` (版 1)
リトルエンディアン。サイズの見込みは、全国で数 MB〜十数 MB (踏切 約 3 万 + 橋 十数万本)。

```
header   : magic "CMHZ" (4 byte) | version u16 = 1 | reserved u16 = 0
counts   : crossingCount u32 | bridgeCount u32 | nameTableBytes u32
crossings: crossingCount 個 × ( latE7 i32 | lonE7 i32 )            緯度・経度を 1e7 倍した整数
bridges  : bridgeCount 個 × ( nameOffset u32 | pointCount u16 | reserved u16 | pointCount 個 × ( latE7 i32 | lonE7 i32 ) )
           nameOffset は名前表の先頭からのバイト位置。名前が無いときは 0xFFFFFFFF。pointCount >= 2
names    : nameTableBytes バイト。UTF-8、各名前は NUL (0x00) で終わる
trailer  : crc32 u32   (header から names の終わりまでの CRC-32。java.util.zip.CRC32 / zlib.crc32 と同じ)
```
- 読み手は、magic・version・長さ・CRC のどれかが合わなければ、ファイル全体を無かったものとして扱う (例外は投げず null)。
- 緯度経度は、国内の範囲 (緯度 20〜46、経度 122〜154) に収まっているものだけを入れる。

## 抽出の規則 (OSM → hazards.bin)
- **踏切**: タグ `railway=level_crossing` を持つノード。近接 (30 m 以内) の重複は 1 つにまとめる (複線の踏切)。
- **橋**: タグ `bridge` が `no` 以外で、`highway` が次のいずれかのウェイ。
  `trunk, primary, secondary, tertiary, unclassified, residential, living_street, service, track, cycleway, path` と、それぞれの `_link` (`motorway`・`motorway_link`・`steps`・`footway`・`pedestrian` は入れない)。
  - 長さ (頂点間の距離の合計) が **30 m 以上**のものだけ。ただし、名前 (`name` または `bridge:name`) があるものは 15 m 以上。
  - 名前は `name`、なければ `bridge:name`。日本語名があれば `name:ja` より `name` を優先する。
  - 同じ橋が複数のウェイに分かれている場合は、そのまま別の橋として入れてよい (照合側で、近接する同種の注意地点をまとめる)。
- 川・谷の小さな暗渠 (`tunnel=culvert`) は橋ではないので入れない (`bridge` タグのものだけを対象にする)。

## 経路との照合 (アプリ)
入力: 経路の座標列 (`RoutePoint` のリスト。接続線を含む表示用の線) と、`hazards.bin` を読んだ索引。出力: 経路の始点からの距離つきの注意地点のリスト。

```
RouteHazard(kind: CROSSING | BRIDGE, name: String?, startMeters: Double, endMeters: Double)
```
- 索引は、緯度経度のグリッド (0.01° ≒ 1 km 角) で作り、経路が通る格子とその隣だけを引く。全国を毎回走査しない。
- **踏切**: 経路の線までの距離が 12 m 以内のものを採用。`startMeters = endMeters` = 経路上の射影位置。
- **橋**: 橋の線の頂点のうち、経路の線までの距離が 10 m 以内のものが 2 つ以上あり、経路上でその範囲の長さが 20 m 以上のものを採用。`startMeters`・`endMeters` は、その範囲の始点・終点。
- 同じ種類で、前後が 40 m 以内に近接するものは 1 つにまとめる (複線の踏切、分割された橋)。
- 結果は始点からの距離の昇順。経路が変わる (リルート・差し替え) たびに計算し直す。計算は UI スレッド以外で行う。

## 表示 (アプリ、第 3 段階)
- ナビの上部のカード (`NavTopBarC` など) の下に、**注意地点のチップを 1 つだけ**出す。曲がり角の案内の表示は変えない。
- 出す条件: 走行済み距離 `traveled` に対して、まだ通り終えていない (`endMeters > traveled`) 最初の注意地点。
  - 踏切: `startMeters - traveled <= 500 m` のとき表示。「踏切 あと 300 m」。
  - 橋: `startMeters - traveled <= 1000 m` のとき表示。名前があれば「橋 (錦帯橋) あと 800 m」、なければ「橋 あと 800 m」。橋の上 (`startMeters <= traveled < endMeters`) では「橋 (名前) 残り 120 m」。
- 距離の表記は、既存の `formatDistance` (50 m 刻みなど、既存の規則) にそろえる。
- アイコンは、新しいベクター画像 2 つ (踏切、橋)。文字だけでなく、`contentDescription` も付ける (読み上げ用)。
- ナビ中でないとき、ファイルが無いとき、注意地点が無いときは、何も出さない。
- 注意: 参考表示であり、安全を保証しない (OSM のタグの付き方に依存する)。踏切は一時停止・安全確認が必要なので、「あと ○○ m」の表示を、他の案内より目立たせすぎない。

## 実装の段階
1. **段階 1 (データ作成、Python)**: `scripts/build_hazards.py`、テスト、`push_data.sh` への組み込み、見本ファイル。指示書: `work/codex/hazards-phase1.md`。
2. **段階 2 (アプリの核、Kotlin)**: 読み込み・索引・経路との照合・テスト。UI には触らない。指示書: `work/codex/hazards-phase2.md`。
3. **段階 3 (表示)**: チップ・アイコン・`MapScreen` までの配線。
   他の担当の未コミットの変更が入っているファイル (`MapScreen.kt`、`MapContentLayout.kt`、`MapBottomOverlays.kt` など) を触るので、**それらがコミットされてから始める。**

## 今回やらないこと
- トンネル、急な登り・下り坂の注意。
- 地図上の経路へのアイコン表示、音声案内。
- グラフ (`.graph`) へのフラグの追加と、全県の作り直し。

## 確認の仕方
- 段階 1: 小さな見本の OSM (数件の踏切・橋を含む) で、出力を Python で読み戻してテスト。広島・山口で件数を出して、既知の橋 (錦帯橋など) が含まれることを確認。
- 段階 2: 合成した小さなファイルと、段階 1 の見本ファイルを、Kotlin のテストで読む。経路との照合は、座標を手で組んだ経路でテスト。
- 実機: 今回の対象は広島→山口の経路 (錦帯橋に橋が出るか、国道 2 号の踏切が出るか) で見る。
