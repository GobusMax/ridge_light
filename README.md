# Ridge Light

Estimates how much direct sun a hike gets: when you will be where, and whether the terrain hides
the sun at that moment. Built to work with [OsmAnd](https://osmand.net).

The model in short:

1. **Route.** The GPX is resampled into ~25 m steps.
2. **Timing.** Each step gets a walking time from the DIN 33466 hiking-time standard (4 km/h on
   the flat, 300 m/h up, 500 m/h down, larger + ½ smaller), scaled by a pace factor. Recorded
   tracks can replay their own timestamps instead. Planned breaks sit at a place on the route
   and delay everything after them; their light is sampled every minute.
3. **Sun.** The sun's apparent position (NOAA/Meeus, with refraction) is computed for the moment
   each step is reached.
4. **Terrain.** A ray is traced from eye height toward the sun through a digital elevation model,
   out to 45 km, with Earth curvature and terrestrial refraction. Fine tiles (z13, ~13 m in the
   Alps) are used near the route and coarse ones (z11) for distant ridges.
5. **Result.** Each step is direct sun, terrain shade, below the horizon, or unknown (missing
   terrain data).
6. **Sky (on request).** An hourly forecast (Open-Meteo) gives cloud cover on pressure levels
   from about 150 m to 11.5 km, for a few places along the route. For each stretch, at the
   hiker's altitude and time, the sky is clear, thin high cloud, partly cloudy, overcast, or in
   cloud or fog; a cloud layer just below marks "above the clouds". Terrain sun stays the
   headline number; the sky is shown beside it, never multiplied into it.

Trees, buildings and snow are not modelled. Forecasts cover 16 days ahead, and models place
low fog less reliably than other cloud.

## Layout

| Path | What |
|---|---|
| `core/` | The engine. Kotlin Multiplatform `commonMain`, no I/O and no dependencies, so it can move into OsmAnd's `OsmAnd-shared` module unchanged. |
| `cli/` | JVM command line: `analyse`, `horizon`, `season`, `bench`. Used for the dev loop and validation. |
| `dem-android/` | Android Terrarium tile cache: download, LRU disk cache in app files (works offline once fetched), PNG decoding. |
| `osmand-aidl/` | Verbatim copy of OsmAnd's AIDL API definitions (see its README). |
| `companion-app/` | Android app (Compose). Opens a GPX (OsmAnd → track → Share → Ridge Light), shows the analysis and pushes coloured tracks back into OsmAnd over AIDL. |
| `tools/webapp-reference.mjs` | Runs the original web prototype's model headless in Node, for comparison. |
| `Ridge Light/` | The original web prototype (reference only; its Rigi GPX and DEM tiles are used as test fixtures). |

A built-in OsmAnd plugin (the same analysis inside OsmAnd itself, kept as patches against an
OsmAnd checkout) was built as well. It is paused and lives on the `builtin-plugin` branch.

## Building

Gradle 9.8 needs Java 17+, but this machine's default `java` is 8, so set `JAVA_HOME` first:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew :core:jvmTest                        # engine unit tests
./gradlew :cli:installDist                     # CLI -> cli/build/install/ridgelight/bin/ridgelight
./gradlew :companion-app:assembleRelease       # APK for phones -> companion-app/build/outputs/apk/release/
./gradlew :companion-app:assembleDebug         # APK -> companion-app/build/outputs/apk/debug/
```

Put the release APK on phones. It is shrunk with R8 and signed with the local debug key, so it
installs over a debug build. The debug build runs the analysis 3–5× slower, because Android
deoptimises debuggable apps.

The Android modules need `local.properties` with `sdk.dir=…` and compile against SDK 36.

## CLI examples

```sh
R=cli/build/install/ridgelight/bin/ridgelight
# The Rigi sample, offline with the prototype's bundled tiles, plus a departure-time comparison
$R analyse "Ridge Light/assets/rigi.gpx" --date 2026-12-20 --start 10:00 --tz Europe/Zurich \
   --dem-dir "Ridge Light/assets/dem" --sweep --out /tmp/rigi-out
# Light over one day at one point, and sunless weeks over a winter
# A 45-minute break at km 2.5, and the forecast sky over each stretch
$R analyse "Ridge Light/assets/rigi.gpx" --date 2026-11-24 --start 09:00 --tz Europe/Zurich \
   --break 2.5:45 --weather --sweep
$R horizon --lat 46.0513 --lon 8.1933 --date 2026-12-21 --tz Europe/Rome --profile
$R season  --lat 46.0513 --lon 8.1933 --from 2026-10-28 --to 2027-02-20 --every 4 --tz Europe/Rome
# Replay the app's open / change start / change pace / change date steps, with timings and a
# result checksum (identical checksums = identical results, e.g. before and after an optimisation)
$R bench "Ridge Light/assets/rigi.gpx" --dem-dir "Ridge Light/assets/dem" --date 2026-11-22 --start 09:00 \
   --tz Europe/Zurich --threads 8 [--dump results.txt]
```

Tiles are cached in `~/.cache/ridgelight/terrarium`.

## Companion app

Open a track in OsmAnd, then **Share → Ridge Light**, or use "Open GPX" in the app.

The overview fits on one screen: a row with date, start and return, pace and breaks (tap one to
change it), the time in direct sun, the sky in two lines, and the elevation profile. Drag along
the profile (or the timeline under it) to see where you are when, and in what light. Three tiles
preview the detail pages:

- **Stretches:** every stretch of sun or shade, under the profile. Dragging highlights the
  stretch, and tapping a stretch moves the cursor to it.
- **Start times:** sun for each start through the day. Tap one to use it.
- **Which day?:** your plan on each forecast day. Tap one to use it.

- **Breaks.** "Break here" adds one at the profile cursor; the Breaks cell lists them and adds
  one at a time of the current plan. A break stays at its place when start time or pace change,
  and its light counts in the totals.
- **Sky.** Switched on or off in the menu (⋮), and kept that way. When on, nothing is sent
  until you tap "Check the sky" (the request sends a few points of the route to Open-Meteo).
  You then get:
  - forecast cloud drawn on the elevation profile, at the heights and times you pass;
  - each stretch labelled with its sky. The sky page adds a summary: terrain sun by sky, time
    in cloud or fog, time above the clouds, and temperature, rain and wind at your altitudes;
  - a sky icon on each start time (the ranking stays by terrain sun);
  - the "Which day?" page.
- **Show on OsmAnd's map** imports one track per kind of light, coloured, with waypoints where
  the light changes and at breaks. They go to OsmAnd's `tracks/ridgelight-*.gpx` and are
  replaced on each run.

OsmAnd keeps newly connected apps disabled. The first time, enable Ridge Light under OsmAnd →
Menu → Plugins (it is listed as a third-party app). Newer OsmAnd builds show a permission dialog
instead. The app explains either case.

## Validation so far

- **Solar position.** Matches the NREL SPA worked example: azimuth within 0.05°, altitude
  within 0.03°.
- **Synthetic terrain.** A plane, a wall and a valley give the expected horizon angles and
  classifications. DIN 33466 times match hand calculations.
- **Rigi sample vs. the web prototype** (same tiles, same old pace rule). Sun/shade stretches
  agree to about 0.1 km, and sun shares are 80/80 %, 66/63 % and 60/60 %. Walking times differ
  because the prototype used noisy terrain elevations. The engine prefers the GPX's own
  elevations when every point has one (Rigi: ↑22 m ↓226 m instead of ↑130 m ↓301 m).
- **On an Android 14 emulator.** The companion app, the CLI and the built-in plugin (branch
  `builtin-plugin`) give identical results for Rigi on 22 Nov, 09:00: 1h 41m direct sun
  (83 %), 21 min terrain shade, same stretches. So Android's PNG decoding matches ImageIO. Both OsmAnd integrations
  were exercised end to end:
  - **Companion app:** with released OsmAnd+ 5.4.11 over AIDL.
  - **Built-in plugin:** in an OsmAnd master build, from track → Options and from the
    My Places selection menu.
- **Viganella (IT).** It is famous for 83 sunless days, about 11 Nov – 2 Feb. A valley-floor
  point (46.0513, 8.1933) gives no direct sun from about 9 Nov until 5 Feb. The result is very
  sensitive to the exact point.

## Data

- **Elevation:** [Terrain Tiles](https://registry.opendata.aws/terrain-tiles/) (Mapzen/Tilezen on
  AWS Open Data). Sources include SRTM, GMTED, ETOPO1 and NED. See
  [attribution](https://github.com/tilezen/joerd/blob/master/docs/attribution.md).
- **Weather:** [Open-Meteo](https://open-meteo.com) forecast API (free, no key, CC BY 4.0),
  using each region's best available models. Only the standard pressure levels are requested:
  "best match" fills the in-between ones from another model, which makes profiles zig-zag.
