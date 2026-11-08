# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`AGENTS.md` is the shared, authoritative project guide (written in Japanese, for all AI agents). Read it first; this file only summarizes the essentials and does not replace it.

## Git policy (important)

Never run `git add` / `git commit` / `git push` on your own. Report a summary, diff and test results, then wait for the human (Gorite) to approve. Run `git pull` before starting work, since development is synced between Windows and Mac via GitHub.

## What this is

CycleMap: a fully offline Android cycling navigation app. Three layers:

1. `application/` — the Gradle project (run `./gradlew` from here). Modules: `:app`, `:routing-core`, `:osm-importer`.
2. Root Python pipeline — `build_prefectures.py` turns `data/japan-latest.osm.pbf` into per-prefecture packages (`packages/<name>.zip`) for the 5 Chugoku prefectures.
3. `sandbox/data-tool/` — Python/Streamlit tool that builds the integrated POI search DB (Overture + MLIT + OSM).

`Archives/` is old code. Do not edit it, and never delete `Archives/application/.git.bak`.

## Commands

```bash
cd application
./gradlew test                                   # all unit tests
./gradlew :routing-core:test :osm-importer:test  # JVM core only
./gradlew :routing-core:test --tests '<fully.qualified.TestClass>'   # single test class
./gradlew :app:testDebugUnitTest --tests '<fully.qualified.TestClass>'
./gradlew :app:assembleDebug                     # APK
./gradlew :osm-importer:installDist              # build the importer CLI
JAVA_OPTS="-Xmx6g" osm-importer/build/install/osm-importer/bin/osm-importer --bbox none <in.osm.pbf> <out.graph>
```

```bash
# From the repo root. Always use venv/bin/python (system python has no osmium); the `osmium` CLI must be on PATH.
venv/bin/python build_prefectures.py --only Yamaguchi --keep-work     # add --force to rebuild
venv/bin/python application/osm-importer/build_search_db.py --bbox none <in.osm.pbf> <out.search.db>
python3 scripts/build_tiles.py --only Yamaguchi                       # vector tiles -> packages/<name>.pmtiles (needs network; pmtiles CLI in sandbox/vector_test/pipeline/)
./scripts/push_data.sh --dry-run                                      # then without the flag: adb push + SHA-256 verification (--tiles-only / --db-only)
```

Toolchain: AGP 8.10.1, Kotlin 2.0.21, compileSdk/target 35, minSdk 33, Java 11 target. On Windows use Android Studio's bundled JDK 21 (system Java 25 is incompatible with Gradle 8.11.1). `application/local.properties` (`sdk.dir`) is machine-specific and not committed.

## Architecture

- **Routing data and display data are strictly separate.** Routing uses the custom `.graph` + `.graph.idx` binary built from OSM. Display uses GSI tiles / MapLibre PMTiles. Never mix raster tile data into the graph. Keep the OSM and GSI attribution visible, even offline.
- **`:routing-core`** is pure JVM. Production routing is A\* only (`AStarRouter` + `CyclingCostModel`). `HsaRouting.kt` and `AltRouting.kt` are experimental. Graphs are read through mmap (`MappedRouting.kt`, `LazyMappedRouting.kt`).
  - `CyclingCostModel` excludes `motorway`. Its minimum multiplier of 0.90 is the A\* heuristic lower bound, so keep the JVM implementation and `LazyMappedRoadGraph` in sync.
  - Limits: `LazyMappedRouting` reads with `Int` offsets, so `load` rejects graph files over 2 GiB with an explicit error (the per-prefecture graphs fit). `nearestNodeIndex` uses a spatial grid and falls back to a linear scan only when the grid finds nothing. Check `Archives/application/bugs.md` and `git -C application log` before "fixing" an old bug, because most Critical/High items are already fixed.
- **`:app`** (package `com.gorite.cyclemap`) uses MVI/UDF with Compose. `MainActivity.kt` is the entry point. The `contract/` package defines the interfaces (`RoutePlanner`, `ReroutePolicy`, `PlaceDetailProvider`, `LaneGuidanceProvider`) and models. The `real/` package holds the implementations (`RealRoutePlanner`, etc.). Other packages: `navigation`, `search`, `tracking`, `weather` (JMA), `speech`, `power`, `net`.
- **Data is never bundled in the APK.** The app reads `getExternalFilesDir(DOCUMENTS)/CycleMap/` (`<name>.graph`, `<name>.graph.idx`, `<name>.search.db`, `<name>.pmtiles`, `tiles/cache.db`). The vector tiles are a separate artifact from the 5-file ZIP, produced by `scripts/build_tiles.py` from the Protomaps public build. `RoutingGraphSelector` and `SearchDbSelector` (in `Prefectures.kt`) pick the first file by name at startup, then switch prefecture automatically once the current location is known.
- **Search DB must work without FTS5.** Android's stock SQLite often lacks FTS5, so each DB also stores a `places.search_text` column (separate from the display `name`) for LIKE queries. See `Fts5SupportDetector.kt`.
- **`build_prefectures.py` invariants:**
  - Extract with `osmium extract --polygon <boundary> --strategy complete_ways`.
  - Call the importer with `--bbox none`.
  - Each ZIP holds exactly five files: `<name>.search.db`, `<name>.graph`, `<name>.graph.idx`, `manifest.json`, `LICENSE.txt`.
  - A valid ZIP is skipped unless `--force` is given.
  - Failed work directories are kept for debugging.
  - The integrated DB from `sandbox/data-tool/out/` is validated (integrity_check, required columns, indexes, row counts) before it is used.

## Do not touch

`data/japan-latest.osm.pbf` (about 2.5 GB; the root `japan-latest.osm.pbf` is a symlink to it), `work/` and `venv/` are large and must not be committed or moved.

## Releases

Build release APKs only from a clean HEAD (a `-dirty` APK is for dev checks only). Record the first 12 characters of the APK SHA-256 in `VERSIONS.md` in a separate commit. The debug keystore (`~/.android/debug.keystore`, with the Khadas Edge 2 copy as the original) is never committed. Builds can be offloaded to the Khadas Edge 2 SBC (see `AGENTS.md`).
