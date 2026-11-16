# MapLibre Native への報告の下書き (根室半島の地図の崩れ)

GitHub の `maplibre/maplibre-native` に出す英語の下書き。**2026-11-16 に出した**: https://github.com/maplibre/maplibre-native/issues/5017 (Android 用のバグ報告のテンプレート。下は出す前の下書きで、実際の本文は欄に合わせて分けてある)。

- 新しいイシューを立てる場合は https://github.com/maplibre/maplibre-native/issues/new/choose の「Bug report」を選び、下の「題名」と「本文」を貼る。
- 近い既存イシュー: [#1843](https://github.com/maplibre/maplibre-native/issues/1843) (Wrong rendering of island/areas near water ... "native と JS で違う"、未解決)。新規でなくそこへのコメントでもよい。
- 添付するファイル (`work/nemuro_test/report/`。`work/` は git 管理外): `nemuro_repro_tiles.pmtiles` (1.5 MB)、`native_android_z10.4_z9.3_z8.5.png` (実機の画面。自分のアプリの画面なので、気になれば地図の部分だけに切り直す)。
- 本文にアプリ名・個人情報・端末のシリアルは入れていない。

## 題名

Android: fill polygons of one Protomaps basemap tile region render as large wedge-shaped artifacts at z8–z10 (same tiles render fine in MapLibre GL JS)

## 本文

### Description

On Android (MapLibre Native), a few low-zoom tiles of a Protomaps basemap extract render land/ocean as large straight-edged wedges instead of the real coastline. The same tiles, with the same style, render correctly in MapLibre GL JS 3.6.2 / pmtiles 3.0.6, so the tile data seems fine and the difference is in the native rendering path. It looks similar to #1843 (native differs from JS for some polygons).

The affected tiles are all around the Nemuro peninsula, Hokkaido, Japan: `8/231/93`, `9/463/187`, `10/926/374`. Neighbouring tiles at the same zoom, and the same area at z11+, render correctly.

### Reproduce

1. Get the tiles (a ~1.5 MB extract, z0–z11 around the area; or use the attached `nemuro_repro_tiles.pmtiles`):

   ```
   pmtiles extract https://build.protomaps.com/20261110.pmtiles nemuro.pmtiles --bbox=145.0,43.0,146.2,43.9 --maxzoom=11
   ```

   (Tiles are © OpenStreetMap contributors / Protomaps basemap v4.15, ODbL.)

2. Render it with a Protomaps basemap style (I used the dark flavor of the Protomaps basemap style with the `pmtiles://` source pointing to the file), camera at lat 43.35, lon 145.70, zoom 10.4 (then 9.3 and 8.5).

3. Expected: the Nemuro peninsula as in MapLibre GL JS. Actual (native Android): a large dark wedge with straight edges where sea should be, and part of the land/sea swapped (see the attached screenshot, `native_android_z10.4_z9.3_z8.5.png`).

### Versions / devices tried

| SDK | Backend | Device | Result |
|---|---|---|---|
| org.maplibre.gl:android-sdk 11.8.0 | OpenGL ES | Galaxy S26 | wedge |
| 12.3.1 | OpenGL ES | Galaxy S26 | wedge |
| 13.7.1 | Vulkan | Galaxy S21 (Android 15) | wedge |

The variants below were all run with 11.8.0 on the Galaxy S26.

### What I found (all on device, by re-encoding the three tiles with a Python script and re-running)

The tile data is valid: all `earth`/`water` polygons are valid in shapely, ring orientation is consistent, no duplicate points, and the unmodified bytes (extracted straight from the Protomaps build) reproduce it as well as a decode/encode round trip.

- No effect: removing holes from the `ocean` polygon, clipping it to the tile box, removing only small holes, simplifying it; keeping only the main `earth` ring, only the small rings, splitting the `earth` MultiPolygon into one feature per polygon; removing `earth` from the neighbouring tiles; dropping the `landuse`, `roads`, `places`/`pois`, `buildings`/`boundaries` layers from the three tiles; removing the `earth` style layer altogether (its fill colour equals the background).
- Fixes it at all three zooms: removing all features of the `earth` layer from the three tiles.
- Fixes it at z9 and z10 (not z8): removing the Point features (`bay`, `strait` labels) from the `water` layer of the three tiles.

So the result depends on the content of the tiles in a way I could not reduce to one feature. I did not manage to reduce it to a minimal style or a single polygon; the full Protomaps style was used.

### Expected behaviour

Same as MapLibre GL JS: the coastline polygons are drawn as in the tile data.

### Additional context

`make_variants.py` (attached) is the script I used to cut the region out of the original archive and to write the variants.
