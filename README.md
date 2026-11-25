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
6. **Clouds (optional).** An hourly forecast (Open-Meteo) gives cloud cover on pressure levels
   from about 150 m to 11.5 km, for a few places along the route; nothing else (no temperature,
   rain or wind) is requested. At the hiker's altitude and time, the sky is clear, thin high
   cloud, partly cloudy, overcast, or in cloud or fog. Combined with the terrain, a sunny step
   stays **sun** under a clear or thin sky, becomes **sun and clouds** under broken cloud, and
   is **behind clouds** under overcast or in fog. Shade and darkness don't depend on the sky.
   Start times and days are ranked by sun, with sun between clouds counted half.

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
| `fastlane/` | Store texts, icon and screenshots (English and German), in the layout F-Droid reads. |

A built-in OsmAnd plugin (the same analysis inside OsmAnd itself, kept as patches against an
OsmAnd checkout) was built as well. It is paused and lives on the `builtin-plugin` branch.

## Building

Gradle 9.8 needs Java 17+, but this machine's default `java` is 8, so set `JAVA_HOME` first:

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
./gradlew :core:jvmTest                        # engine unit tests
./gradlew :companion-app:testDebugUnitTest     # app unit tests (saved plan, forecast cache, pace presets)
./gradlew :companion-app:lintRelease           # Android lint
./gradlew :cli:installDist                     # CLI -> cli/build/install/ridgelight/bin/ridgelight
./gradlew :companion-app:assembleRelease       # APK for phones -> companion-app/build/outputs/apk/release/
./gradlew :companion-app:assembleDebug         # APK -> companion-app/build/outputs/apk/debug/
```

Put the release APK on phones. It is shrunk with R8. The debug build runs the analysis 3–5×
slower, because Android deoptimises debuggable apps.

**Release signing.** Release builds are signed with the key described in a properties file
outside the repository: `~/.android/ridgelight-release.properties` by default, or the path in the
Gradle property `ridgelight.signing` (`-Pridgelight.signing=…`). It holds `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`. Without it, release builds fall back to the debug
key, with a warning, so anyone can build. The key and its properties are never committed; every
update of the published app must be signed with the same key.

The Android modules need `local.properties` with `sdk.dir=…` and compile against SDK 36.

## CLI examples

```sh
R=cli/build/install/ridgelight/bin/ridgelight
# The Rigi sample, offline with the prototype's bundled tiles, plus a departure-time comparison
$R analyse "Ridge Light/assets/rigi.gpx" --date 2026-12-20 --start 10:00 --tz Europe/Zurich \
   --dem-dir "Ridge Light/assets/dem" --sweep --out /tmp/rigi-out
# Light over one day at one point, and sunless weeks over a winter
# A 45-minute break at km 2.5, terrain and forecast clouds combined
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

Open a track in OsmAnd, then **Share → Ridge Light**, or use "Open GPX" in the app. The last
track and its plan come back when the app is opened again.

The main page shows:
- **Plan:** one row with date, start and return, pace and breaks. Tap a cell to change it.
  Pace offers five presets (Fast, Brisk, Hiking signs, Relaxed, Leisurely) with the resulting
  hike time, and a slider for anything in between.
- **Sun:** the time in sun and the **Clouds** switch.
- **Profile:** coloured by the light at the time you get there, with a Terrain and a Clouds
  bar underneath. Drag along it to see where you are when, and in what light; near a break,
  the cursor snaps to it. Hour marks underneath show where you are at each full hour.
- **Tiles** for two pages: **Start times** (sun for each start through the day) and **Which
  day?** (your plan on each forecast day; needs Clouds). Tap a row there to use it.

Explanations (colours, bars, pace, clouds, what a page compares) are behind ⓘ buttons. The page
looks the same with Clouds on or off; only the contents change.

The ⋮ menu has the time zone and **About** (how it works, limits, privacy, data sources).

- **Breaks.** "Break here" adds one at the profile cursor; the Breaks cell lists them and adds
  one at a time of the current plan. A break stays at its place when start time or pace change,
  and its light counts in the totals.
- **Clouds.** Off by default; the first time it's switched on, the app says what is sent. While
  on, the forecast loads by itself (it sends up to six points of the route to Open-Meteo) and
  is kept for three hours, in memory and on disk, so switching off and on, changing the date
  or reopening the app does not fetch again; "Refresh" in the Clouds ⓘ does. Then:
  - the profile shows terrain and clouds combined, the Clouds bar fills in, and the forecast
    cloud is drawn faintly behind the profile;
  - start times are compared with the clouds;
  - "Which day?" works;
  - the Clouds ⓘ shows fog along the route, time above a sea of cloud, and how far to trust
    the forecast.
- **Show on OsmAnd's map** imports one track per kind of light, coloured, with waypoints where
  the light changes and at breaks. They go to OsmAnd's `tracks/ridgelight-*.gpx` and are
  replaced on each run.

**Languages.** English and German; the app follows the phone's language, and on Android 13+ it
can be set per app (Settings → Apps → Ridge Light → Language). All text, including the names of
the tracks and waypoints sent to OsmAnd, is in `companion-app/src/main/res/values*/strings.xml`.

**Accessibility.** Every control has a label for screen readers, durations are read as words
("2 hours 40 minutes"), and the profile is an adjustable control, like a slider: it moves in
steps of 5 % of the hike, says where the cursor is, and has actions that jump to the next change
of light or break. Text
reaches at least 4.5:1 contrast in light and dark mode, touch targets are at least 48 dp, and
with large fonts the plan row becomes a 2×2 grid and lines wrap instead of being cut off.

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

## Licence

Ridge Light is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [LICENSE](LICENSE).

`osmand-aidl/` is a copy of OsmAnd's app-interface definitions, which OsmAnd offers for any use
(see its README). The elevation and weather data keep their own terms (see Data above).
