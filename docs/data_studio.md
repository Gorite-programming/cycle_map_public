# データ工房 (scripts/data_studio.py)

県ごとのデータ生成 (経路グラフ + ZIP、地図タイル、地形、CH、県またぎの通り口、端末への転送) を、画面から選んで、PC の性能を使い切って流すツール。Streamlit 製。実行は別プロセスなので、画面を閉じても止まらない。

## 起動

```bash
scripts\data_studio.cmd                                  # Windows: ダブルクリックでもよい
venv\Scripts\streamlit run scripts/data_studio.py        # Windows
venv/bin/streamlit run scripts/data_studio.py            # Mac
```

`.streamlit/config.toml` で `127.0.0.1` だけに公開する (画面からジョブを起動できるため、LAN には出さない)。初回は `venv\Scripts\python.exe -m pip install streamlit` が要る。

## 画面

1. **作る**: 県を選ぶ (全国・未生成・地方・個別) → 作る物 (グラフ / タイル / 地形 / CH / 通り口 / 端末へ転送) → 性能プロファイル → 「実行を開始」。47 県の成果物の状況 (`packages/` と検索 DB) も見られる。
2. **実行状況**: ジョブごとの状態・経過・ヒープ・メモ、CPU 枠とメモリ枠の使用、ログ (末尾 80 行)、停止ボタン。
3. **履歴**: `work/studio/<日時>/` の過去の実行。
4. **ほかのツール**: 検索 DB 管理ツール (`sandbox/data-tool`、8502 番) の起動、adb の接続確認。

## 仕組み

- `scripts/studio_plan.py` が「県 × 作る物」から依存つきのジョブ一覧を作り、`work/studio/<日時>/plan.json` に書く。
- `scripts/studio_engine.py` が plan を読み、**CPU 枠・メモリ枠に収まる限り、依存の済んだジョブを同時に走らせる**。状態は `status.json`、ログは `logs/<ジョブ>.log`。`STOP` ファイルで停止 (子プロセスごと)。
- 依存: 標高タイル (勾配のため) → グラフ、タイル → グラフ、グラフ → CH、グラフ全部 → 通り口、各県の工程と通り口 → 転送。標高タイルの取得と adb への転送は同時に 1 つだけ (`lock`)。
- **JVM のメモリ不足は自動で直す**: 最初は小さめのヒープ (既定 3〜4GB) で多数を並列に走らせ、ログに `OutOfMemoryError` が出たジョブだけヒープを倍にして再実行する (上限 12GB)。
- プロファイル: 省電力 (CPU の 1/4)、バランス (1/2)、フルパワー (CPU 全部、メモリは空きから 2.5GB を残す)。枠は「枠を細かく決める」で変えられる。

## 既存スクリプトに足したもの

- `build_prefectures.py --heap <n>g`: osm-importer の JVM ヒープ。
- `scripts/build_terrain.py --dem-only`: 標高タイルの取得だけ (osm-importer が辺の勾配を書き込むのに先に要る。無いと勾配 0 のグラフになる)。
- `build_prefectures.py`: `packages/` にあるタイルのほうが新しいとき、`sandbox/vector_test/data/` の古いタイルで上書きしない。
- `scripts/_platform.py`: Windows で `work/osmium-env` の `osmium.exe` を PATH に無くても見つける。

## 注意

- 全国 47 県を一度に流すと、ディスクを 80GB 前後使う (作業領域 1.5GB/県 + 成果物 0.4GB/県。完了した県の作業領域は消える)。実行前に画面が概算を出す。
- ネットワーク工程 (標高・タイル) は国土地理院と Protomaps に負荷をかける。標高の取得を直列にしているのはそのため。
- 端末へ転送するには、実機が adb でつながっていること。転送先アプリは画面で選ぶ (`.win` など)。
