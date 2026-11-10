# オフライン動作の点検 (2026-11-11、コード調査)

機内モードでの実機確認はしていない (端末設定を変えないため)。コードで、ネットワークを使う箇所と失敗時の挙動を確認した。

| 箇所 | 用途 | 失敗時の挙動 | 判定 |
|---|---|---|---|
| ベクタ地図 (`VectorMapThemeHelper`, `VectorMapTerrain`) | pmtiles・フォント・スプライト | すべて `pmtiles://file://` / `file://` / `asset://` のローカル参照。ネット不要 | 問題なし |
| 経路探索・検索・案内 (`:routing-core`, `search`, `navigation`) | .graph / .search.db を mmap / SQLite | ネット不要 | 問題なし |
| 天気 `JmaWeatherClient` / `WeatherRepository` | 気象庁 JSON | `Result.failure` → キャッシュがあれば Stale(オフライン表示)、なければ Error(メッセージ)。`refresh(isOnline=false)` は通信せずキャッシュ/Unavailable | 問題なし (タイムアウト値は client の既定) |
| 標高タイル `ElevationRepository` | graph に標高が無い旧版のときだけ GSI DEM10B を取得 | 例外は握りつぶして null を返し、近似の標高プロファイルに退避。最大 30 枚を同期取得 (接続 3 秒/読み 4 秒) | 低リスク (通常の graph は標高入りなので通らない)。旧 graph + 回線不安定だと最大で数十秒待つ可能性 |
| 地図タイルのダウンロード `Prefectures.kt` (回廊保存など、2 か所) | GSI/OSM ラスタをキャッシュ DB へ | 例外で再試行 → 失敗数を数えて `onDone(success, fail)` | **不具合を修正**: 1 か所は HTTP 200 以外 (404/503) で試行回数が進まず、同じ URL を間を置かずに無限再接続していた (オフラインの例外では起きない)。`attempt++` をループ末尾に移した |
| osmdroid のラスタタイル源 (`MapUtilities.kt` の GSI/OSM/Relief) | 旧ラスタ表示・回廊ダウンロードの URL | ベクタ表示が主。ラスタを選んだときだけ、キャッシュに無いタイルは空白 (osmdroid 標準の挙動) | 問題なし (仕様) |
| 場所詳細の Web サイト/電話 (`MapSheetContents.kt`) | 外部アプリで開く | `ActivityNotFoundException` を握りつぶす | 問題なし (オフラインのブラウザ側エラー) |
| 出典表示 (`MapDialogs.kt`) | ライセンス文字列に URL が含まれるだけ | 通信なし | 問題なし |
| `AndroidManifest.xml` | INTERNET / ACCESS_NETWORK_STATE | — | 天気・ダウンロード・標高タイル用。経路・地図・検索は不使用 |

## 未確認
- 機内モードでの実機の挙動 (天気表示が Stale になるか、地図ダウンロード画面の案内文)。
- ネット切断中のダウンロード画面の表示 (失敗件数の見せ方)。
