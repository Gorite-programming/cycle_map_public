# 交差点名・方面を案内に含める設計 (2026-11-26)

## 1. 目的

今の案内は「300 m 先を右折です」で止まり、どの交差点で曲がるのかが分からない。[引き継ぎ.md](引き継ぎ.md) でも、Google マップとの使いやすさの差のうち最大のものとして挙げている。

この設計では、OSM にある交差点名を案内に出す。表示は「本町一丁目 を右折」のようにする。OSM に方面の情報があるときは「宇部方面」のように出す。

### 今回やらないこと
- 経路探索への影響。費用・経路・CH はいっさい変えない。
- 交差点名の読み上げ。音声は録音済みの MP3 (VOICEVOX 四国めたん) だけで、任意の地名は読めないため、§6 で別に決める。
- 名前の無い交差点に名前を推測して付けること。たとえば交わる道の名前から作ることはしない。

## 2. 今の仕組み (調べた結果)

| 箇所 | 今の動き |
|---|---|
| `osm-importer` の `OsmImporter.kt` | 1 回目の読み込みで自転車が通れる way を集める。2 回目は**ノードの座標だけ**を読み、ノードのタグは捨てている。 |
| 索引 `<name>.graph.idx` の末尾 | 追加区間を「目印の文字列 + 中身」の形で順に並べる。今ある区間は `CYCLEMAP_ELEVATION_V1` → `CYCLEMAP_ROADNAME_V1` → `CYCLEMAP_TURNLANES_V1` で、ここまでは importer が書く。最後の `CYCLEMAP_BIKEINFO_V1` は `BikeInfoReindex.kt` が後から追記する。 |
| 読み込み (`LazyMappedRouting.readTrailingSections`) | **知らない目印に当たると、そこで読むのをやめる** (`else -> return`)。 |
| `BikeInfoReindex.validateBikeInfoInput` | 知らない区間があると**エラーで止まる** (`Unknown index section`)。 |
| 案内の生成 (`NavigationHelper.buildRouteInstructions`) | `TurnClassifier` の結果に、`outgoingRoadName` (曲がった先の道路名) と `incomingTurnLanes` (曲がる手前の車線) を付けている。 |
| 表示 | 縦画面のバナー (`NavTopBarC`) と横画面のバー (`LandscapeNavHudBar`) は、案内文と道路名の行 (`roadLabel` / `streetName`) を出す。曲がり角の一覧 (`StepListDialog`) は、ラベルと道路名を出す。 |
| 県をまたぐ経路 | `CrossRouteMerging.kt` が、つなぎ目 (portal) での案内を作り、`outgoingRoadName` を付けている。 |
| 音声 | `VoiceTriggerEvaluator.buildCue` が、決まった文言の MP3 を選ぶ。TTS は「万一の安全網」としてしか使っていない。 |

`.graph` 本体の形式は変えない。そのため、CH の指紋 (graph の大きさと先頭/末尾 1 MB の CRC) も、県境のつなぎ目のファイル (`.portals`) も、作り直す必要はない。

## 3. OSM のデータ量 (実測)

手元にある抽出データで、名前付きのタグの数を数えた (pyosmium、2026-11-26)。中国 5 県の抽出データは手元に無かったので、実装の最初に数え直す (§8 の段階 1)。

| データ | 信号 (`highway=traffic_signals`) | そのうち名前付き | `junction=yes` + 名前 | 道路の way | そのうち `destination` あり | `destination:ref` あり |
|---|---|---|---|---|---|---|
| 関門 (`work/kanmon`) | 131 | 40 (31%) | 0 | 2,286 | 17 | 7 |
| 大阪府 (`work/metro-build/Osaka`) | 13,934 | 4,723 (34%) | 254 | 365,727 | 306 (0.08%) | 209 |

中国 5 県 (2026-11-26、`data/japan-latest.osm.pbf` から `boundaries/<県>.geojson` で切り出し。Antigravity が集計し、スクリプトは `work/agy/junction_stats/`):

| 県 | 信号 | そのうち名前付き | 名前付きのうち読みあり | `junction=yes` + 名前 | 道路の way のうち `destination*` あり |
|---|---|---|---|---|---|
| 鳥取 | 790 | 399 (50.5%) | 0 | 5 | 24 / 91,095 (0.03%) |
| 島根 | 328 | 79 (24.1%) | 2 | 0 | 23 / 106,770 (0.02%) |
| 岡山 | 3,680 | 886 (24.1%) | 43 | 14 | 450 / 241,745 (0.19%) |
| 広島 | 4,486 | 3,623 (80.8%) | 83 | 8 | 647 / 237,015 (0.27%) |
| 山口 | 2,268 | 583 (25.7%) | 54 | 1 | 616 / 149,767 (0.41%) |
| 計 | 11,552 | 5,570 (48.2%) | 182 (3.3%) | 28 | 1,760 / 826,392 (0.21%) |

ここから言えること (中国 5 県の数も同じ傾向。広島は名前付きが 8 割と特に多い):

- **交差点名は、信号のノードの `name` が主な情報源になる。** 名前付きの信号はおよそ 3 割あり、主要な交差点ほど名前が付いている。日本の OSM では、名前は信号機の標識の表記をそのまま入れるのが慣例 (例: 「○○北」「○○2」)。
- `junction=yes` + `name` も、少ないが使う。
- **方面 (`destination`) は、ほぼ高速道路や国道の主要分岐にしか無い。** 自転車の経路で出せる場面は少ないので、段階 3 に回す。
- 名前の読み (`name:ja-Hira` など) は、バス停などを含めると多い。交差点の信号に付いている割合は、段階 1 で数える。読み上げをするなら使える (§6)。

## 4. データの作り方 (importer)

### 4.1 集めるノード
`OsmImporter` の 2 回目の読み込み (座標を読む所) で、グラフに入るノードのうち、次の条件を満たすものの名前を記録する。

1. `highway=traffic_signals` で `name` がある (種別 1)。
2. `junction=yes` で `name` がある (種別 2)。

バス停・横断歩道 (`crossing`)・高速の出入口 (`motorway_junction`) は含めない。横断歩道の名前は交差点名ではないことが多い。高速の出入口は自転車の経路に出てこない。

記録するのは名前付きのノードだけ (大阪で数千件) なので、メモリはほとんど増えない。

### 4.2 名前の整え方 (`RoadNames.kt` に `junctionDisplayName(tags)` を足す)
- 前後の空白を除く。`;` で区切られていれば最初の 1 つだけを使う。
- 末尾の「交差点」は除く。表示のときに付け方をそろえるため。
- 次のものは捨てる。
  - 空になったもの
  - 数字・英字・記号だけのもの (管理番号らしいもの)
  - 25 文字を超えるもの
- 読み (`name:ja-Hira`、無ければ `name:ja_kana`) があれば一緒に持つ。段階 1 では表示に使わず、§6 の読み上げのために残しておく。

### 4.3 索引の新しい区間 `CYCLEMAP_JUNCTIONNAME_V1`

```
writeUTF("CYCLEMAP_JUNCTIONNAME_V1")
writeInt(nameCount)           // 名前の表 (重複を除く)
repeat(nameCount) writeUTF(name)
writeInt(readingCount)        // 読みの表 (0 件でもよい)
repeat(readingCount) writeUTF(reading)
writeInt(entryCount)          // 名前付きノードの数。nodeIndex の昇順
repeat(entryCount) {
  writeInt(nodeIndex)         // graph 内のノード番号
  writeInt(nameId)
  writeInt(readingId)         // 無ければ -1
  writeByte(kind)             // 1 = 信号, 2 = junction=yes
}
```

今の道路名の区間は「全部の辺に 1 つずつ番号を持つ」形だが、新しい区間はそうしない。名前付きのノードはグラフ全体のごく一部なので、**名前付きのノードだけを並べる**。

### 4.4 区間の並び順と互換性 (大事)

古いアプリは、知らない目印に当たると、その先の区間を読まない。だから新しい区間を `BIKEINFO` より前に置くと、**古いアプリでは自転車の通行ルール (`BIKEINFO`) が効かなくなる**。そこで:

- **`JUNCTIONNAME` は常に索引の最後に置く。**
- `writeGraphIndex` は、今の 3 区間の後に `JUNCTIONNAME` を書く。
- `BikeInfoReindex` を直す。
  - `validateBikeInfoInput` が `JUNCTIONNAME` を読み飛ばせるようにする。
  - `writeBikeInfoIndex` は、入力に `JUNCTIONNAME` があれば、その直前に `BIKEINFO` を差し込む。差し込む位置は、`validateBikeInfoInput` が区間の境目として返す。
  - 入力に `JUNCTIONNAME` が無ければ、今までどおり末尾に足す。
- 結果の並びは `ELEVATION → ROADNAME → TURNLANES → BIKEINFO → JUNCTIONNAME` になる。

互換性は次のとおり。

| 組み合わせ | 動き |
|---|---|
| 古いアプリ + 新しいデータ | `JUNCTIONNAME` の手前で読むのをやめる。今までと同じ動き。 |
| 新しいアプリ + 古いデータ | 区間が無いので、名前は null になる。今までと同じ表示。 |

ZIP の 5 ファイル構成は変わらない (中身の `.graph.idx` が変わるだけ)。

## 5. アプリでの使い方

### 5.1 読み込み (`:routing-core`)
- `readTrailingSections` に `JUNCTIONNAME_SECTION_MAGIC` の分岐を足す。件数が合わない・途中で切れているなど、壊れていれば読まずに戻る (ほかの区間と同じ扱い)。
- 中身は件数が小さいので、mmap にせず配列として持つ (`IntArray` × 4 と、名前の表)。
- 次の関数を足す。

```kotlin
data class JunctionName(val name: String, val reading: String?, val kind: Int, val nodeIndex: Int, val distanceMeters: Double)
/** [index] のノードから [radiusMeters] 以内にある、最も近い名前付きの交差点。 */
fun junctionNameNear(index: Int, radiusMeters: Double = 35.0): JunctionName?
```

- 探し方: 名前付きのノードを緯度経度の小さな格子に分けておき、近くの格子だけを調べる。大阪の規模なら全件を線形に調べても間に合うが、全国対応を見込んで格子にする。

### 5.2 曲がり角に名前を付ける (`NavigationHelper.buildRouteInstructions`)
`RouteInstruction` に `junctionName: String?` と `junctionReading: String?` を足す。`routing-core/.../Instruction.kt` の欄で、既定値は null とする。名前は次の順に探す。

1. 曲がり角のノード自身に名前があれば、それを使う。
2. 無ければ、`junctionNameNear(曲がり角, 35 m)` で探す。上下線が分かれた道路の交差点では、信号が別の車線側のノードに付いていることが多いためである。
3. 候補が複数あるときは、近い順に選ぶ。距離が同じなら信号 (種別 1) を先にする。
4. 次の場合は付けない。
   - 案内の種類が、直進・合流・車線の増減・脇道 (`isMinorForDisplay`)
   - 名前が曲がった先の道路名 (`outgoingRoadName`) と同じ
   - ひとつ前の曲がり角と同じ名前で、距離が 60 m 以内 (同じ交差点の中で 2 回曲がるとき)

県をまたぐ経路のつなぎ目 (`CrossRouteMerging.classifyCrossPortal`) でも、そのノードを持つ側のグラフで同じように探す。

半径 35 m は仮の値で、段階 1 の実機確認で決める。広い交差点で名前が付かないなら広げる。隣の交差点の名前が付くなら狭める。

### 5.3 表示の文言
距離帯で文言を入れ替える方式は、今でも「案内文が境界付近でちらつく」という既知の問題がある。交差点名の有無で、さらに文言が切り替わらないようにする。名前があるときは、**動作の後ろに名前を付けた形**に固定する。

| 場所 | 名前なし (今) | 名前あり |
|---|---|---|
| 縦画面バナーの案内文 (`maneuverGuidanceText`) | `右折です` / `まもなく右折です` | `本町一丁目 を右折` / `まもなく 本町一丁目 を右折` |
| 縦画面バナーの道路名の行 (`roadLabel`) | `県道41号` | 今のまま `県道41号` |
| 横画面のバー (`streetName`) | `県道41号` | 1 行しか無いので `本町一丁目 → 県道41号`。長い名前は省略記号で切る。 |
| 曲がり角の一覧 (`StepListDialog`) | 太字 `右折`、下に `県道41号` | 太字 `右折 本町一丁目`、下に `県道41号` |
| 出発前のプレビューの一覧 | 同上 | 同上 |

- 名前と動作の間には半角の空白を入れる。名前の末尾が「北」「2」などで終わることが多く、続けると読みにくいため。
- 名前は 12 文字を超えたら省略記号で切る。バナーの幅は実機で確かめて決める。
- 文言を組み立てる処理は、`NavigationHelper` の中に書き足さない。`navigation/GuidanceText.kt` (新設) に純粋な関数として切り出す。こうしておけば、引き継ぎに残っている「案内文生成の整理」の最初の一歩にもなる。

### 5.4 音声
段階 1 では変えない。今の MP3 の案内をそのまま流す (§6)。

## 6. 読み上げ (段階 2、決めること)

音声は VOICEVOX 四国めたんの録音済み MP3 だけで、任意の交差点名は読めない。考えられる案は 3 つある。

| 案 | 内容 | 良い点 | 悪い点 |
|---|---|---|---|
| A. 名前の MP3 を事前に作る | データ生成時に、交差点名ごとに VOICEVOX で「〇〇を」の短い MP3 を作る。任意のファイル `<name>.junction_voice.zip` として配り、案内の MP3 の前に続けて流す。 | 声がそろう。オフラインで動く。 | データ生成に VOICEVOX エンジンが要る。容量は 1 県あたり数 MB〜十数 MB の見込み (未測定)。地名を読み違える (読みの欄があれば使えるが、多くは無い)。VOICEVOX とキャラクターの利用規約を、配布の形まで含めて確かめる必要がある。 |
| B. 端末の TTS で名前だけ読む | 名前の部分だけを Android の TTS で読む。 | 作るものが少ない。 | 声が混ざる。端末に日本語のオフライン音声が無いと読めない。今の「TTS は全廃」の方針に反する。 |
| C. 読まない | 表示だけにする。 | 何も増えない。 | 画面を見ないと分からない。 |

おすすめは、**段階 1 を実機で使ってから決める**こと。まず C の状態で出して、名前の付く割合と読み違いの程度を見る。読み上げる価値があると分かったら A に進む。B は方針に反するので採らない。

## 7. 方面 (段階 3)

- 曲がった先の way の `destination` (向き付きの `destination:forward` / `destination:backward` を優先) と、`destination:ref` を、辺ごとの文字列の区間 `CYCLEMAP_DESTINATION_V1` として持つ。形は道路名の区間と同じ。これも §4.4 の並び順のとおり、最後に置く。
- 表示は道路名の行に `宇部方面 (国道2号)` のように出す。複数あるときは `;` で区切られた最初の 2 つまで。
- 大阪では、道路の way の 0.08% にしか付いていない。中国 5 県の数を見て、少なすぎれば作らない。

## 8. 段階と分担

| 段階 | 中身 | 触るファイル | 担当の目安 |
|---|---|---|---|
| 1a. データ | 中国 5 県のタグの数を数える。交差点名の収集と整え方。`JUNCTIONNAME` 区間の書き出し。`BikeInfoReindex` の並び順の対応。 | `osm-importer/` (`OsmImporter.kt`、`RoadNames.kt`、`BikeInfoReindex.kt`)、それぞれのテスト | バックエンド (Mac では Antigravity、Windows では Codex) |
| 1b. 読み込み | 区間を読む。`junctionNameNear`。`RouteInstruction.junctionName`。 | `routing-core/` (`LazyMappedRouting.kt`、`Instruction.kt`)、テスト | バックエンド |
| 1c. 案内と表示 | 名前を付ける処理。文言の関数 (`GuidanceText.kt`)。バナー・横画面のバー・一覧。 | `app/.../navigation/` (`NavigationHelper.kt`、`CrossRouteMerging.kt`、新しい `GuidanceText.kt`)、`app/.../ui/` (`MapTopOverlays.kt`、`StepListDialog.kt`、`NavigationHudViews.kt`)、テスト | フロントエンド (Codex)。`navigation/` も指示文で Codex 宛てと明記する。 |
| 1d. データの作り直し | 5 県の graph を作り直し、ZIP を作り直し、端末に転送する。 | `build_prefectures.py --force`、`scripts/push_data.sh` | Claude |
| 2. 読み上げ | §6 の決定次第。 | `speech/`、データ生成 | 未定 |
| 3. 方面 | §7 | `osm-importer/`、`routing-core/`、`app/` | 未定 |

依存の順は 1a → 1b → 1c。1b と 1c は、`RouteInstruction.junctionName` の欄だけ先に決めておけば並行できる。

## 9. テスト

- **importer**
  - `RoadNamesTest` に、名前の整え方の場合分けを足す: 「交差点」を除く、`;` で区切られた名前、数字だけの名前、長すぎる名前、読みあり・なし。
  - 合成した小さな PBF から、区間が正しく書き出されることを確かめる。
- **`BikeInfoReindexTest`**
  - 入力に `JUNCTIONNAME` があるとき、`BIKEINFO` がその前に入る。
  - 入力に無いときは、今までどおり末尾に入る。
  - 二重に追記しようとすると、今までどおりエラーになる。
- **routing-core**
  - 区間を書いて読むと同じ中身になる。
  - 件数の不一致・途中で切れたファイルでは区間を無視し、ほかの区間は読める。
  - **区間を最後に置いた索引を、古い読み方 (知らない目印で読むのをやめる) で読んでも、`BIKEINFO` まで読める。**
  - `junctionNameNear` の半径の境界と、距離が同じときの順位。
- **app**
  - `NavigationHelperInstructionTest`: 曲がり角のノード自身の名前、近くのノードの名前、付けない条件 (直進・道路名と同じ・同じ交差点で 2 回)。
  - `NavGuidanceDisplayTest`: 名前あり・なしの文言。距離が境界付近で、文言が切り替わり続けないこと。
  - 県をまたぐ経路のつなぎ目に名前が付くこと (`CrossRouteMergingTest`)。
- **通しの検査**: `scripts/check_all.sh` が通ること。CH の結果は変わらないはずなので、`benchmarkCh` で A\* と同じ結果になることも一度確かめる。
- **実機**
  - `scripts/device_regression.sh` が 4/4 通ること。
  - 名前付きの信号がある交差点を 3〜5 か所選び、SIMLOC で近づいて、バナー・横画面のバー・一覧の表示を見る。場所は段階 1a で、データから選ぶ。
  - 広い交差点で半径 35 m が足りるかを見る。

## 10. 決めておきたいこと

1. 表示の形を「本町一丁目 を右折」にするか、「本町一丁目交差点を右折」にするか。この設計は前者 (短い方) で書いている。
2. 段階 2 (読み上げ) の扱い。この設計のおすすめは、段階 1 を使ってから A か C を決めること。
3. 段階 3 (方面) をやるか。中国 5 県の数を見て決める、で良いか。
