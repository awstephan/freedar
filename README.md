# Freedar

A native Android precipitation-radar app with an uncluttered, full-screen map,
animated historical radar, offline city search and one-shot location. No user
account or Google Play Services is required.

## Download and install

**[Download Freedar v0.1.0](https://github.com/awstephan/freedar/releases/tag/v0.1.0)**
for **Android 8.0 or newer**. The release includes a signed universal APK
(`freedar-0.1.0.apk`, approximately 76 MB) and a `SHA256SUMS` checksum file.

1. Download the APK to your Android device.
2. Open it and allow installation from your browser or file manager when Android asks.
3. Launch Freedar. No account, API-key setup, Play Store, or Google Play Services is needed.

If you previously installed a **debug build**, uninstall it first: its signing
certificate differs from the release certificate. Uninstalling removes local
settings and cached imagery.

**Updates:** download a newer signed APK from [Releases](https://github.com/awstephan/freedar/releases)
and install it over the existing release to retain your settings. There is no
automatic updater.

Release downloads follow repository visibility: while this repository is private,
GitHub requires repository access; once public, these same links work for everyone.

## Screenshots

| Dark radar | Light radar | Offline search |
| :---: | :---: | :---: |
| [<img src="docs/screenshots/radar-dark.png" width="250" alt="Dark-theme precipitation radar over Austin, Texas">](docs/screenshots/radar-dark.png) | [<img src="docs/screenshots/radar-light.png" width="250" alt="Light-theme precipitation radar over Austin, Texas">](docs/screenshots/radar-light.png) | [<img src="docs/screenshots/search.png" width="250" alt="Offline city search showing results for Denver">](docs/screenshots/search.png) |

Select a screenshot to view it at full resolution.

<details>
<summary>More screenshots</summary>

| Info and legend | Settings | Attributions |
| :---: | :---: | :---: |
| [<img src="docs/screenshots/info.png" width="250" alt="Radar freshness, rain and snow legend, and coverage information">](docs/screenshots/info.png) | [<img src="docs/screenshots/settings.png" width="250" alt="Radar opacity, theme, and playback speed settings">](docs/screenshots/settings.png) | [<img src="docs/screenshots/attributions.png" width="250" alt="Map and data provider attribution panel">](docs/screenshots/attributions.png) |

</details>

## Features

- **Radar:** approximately two hours of RainViewer history, playback, scrubbing,
  Latest, freshness warnings, a rain/snow legend and static coverage shading.
- **Navigation:** worldwide map exploration, bundled offline city/town search,
  one-shot Locate and a remembered map view. The first-use overview shows the
  contiguous USA; Alaska, Hawaii and international locations remain navigable.
- **Appearance:** System/Light/Dark themes, adjustable radar opacity and 1/2/4-fps
  playback settings, with persistent controls in portrait and landscape.
- **Offline use:** recently viewed map/radar resources share a bounded 100 MB
  device store. Radar expires two hours after its original frame time; this is
  reuse of viewed imagery, not a complete offline map download.

Unit tests, lint, APK builds and native AOSP Android 8.0 emulator checks are
recorded in the [integrated validation](docs/validation/issue-10.md) and the later
[compact-map validation](docs/validation/compact-map.md). These are recorded
testing results, not physical-device performance guarantees.

Release APKs use a maintainer-owned signing key. Freedar's source is licensed under
BSD 3-Clause. The [current design](DESIGN.md) records the implemented experience
and revisions to the [original specification](https://github.com/awstephan/freedar/issues/1).

### Limitations

- Radar shows historical precipitation, not forecasts or weather alerts.
- Initial history loading can be slow under provider request limits. Devices sharing
  an IP address share RainViewer's allowance.
- Offline use reuses recently viewed imagery; it does not download complete maps.
- The compact attribution layout has an unresolved CARTO visible-credit requirement;
  see [provider attribution](#carto-provisioning-and-cost).

Kotlin/Compose hosts MapLibre Native **OpenGL ES**, with current CARTO Positron/Dark Matter
vector maps and RainViewer radar. Android 8.0 (API 26) is the minimum; compile and
target SDK are 35. There is no Play Services dependency. Foreground location
permission is requested only when you explicitly choose **Locate**.

## Using the map

| Control | Behavior |
| --- | --- |
| **Attributions (©)**, top-left | One linked provider name per line. |
| **Info**, bottom-left, immediately right of Settings | Compact radar status with Frame/feed age and Retry/Refresh, legend, and coverage explanations. A red icon with a warning badge indicates stale/offline/failed data. |
| **Locate**, top-right | Requests permission when needed, obtains one fresh fix and centers at zoom 9. Becomes Cancel while waiting. |
| **Search**, top-right | Searches the bundled city/town database when you submit using the keyboard's Search action. Selecting a result centers at zoom 9. |
| **Settings**, bottom-left | Theme, radar opacity, playback speed, Clear downloaded data and About. |
| **Timeline and Play/Pause**, bottom-right | Scrub to a historical frame or animate available history. Latest returns to newest imagery, paused and following updates. |

Panel icons toggle their panels closed on a second tap; opening another panel replaces the current one.
Icons are 24 dp with 28 dp rounded backgrounds and invisible 48 dp touch targets.

Search selection and successful location reset radar to Latest. Ordinary pan/zoom
preserves the selected time. Cold launches attempt location only if permission is
already granted, with the remembered view as fallback. There is no ongoing tracking
or background radar polling.

A blue point marks your latest successful location fix; a purple point marks the
selected search result. Both have white outlines and stay on the map while you
pan, zoom, rotate, or switch themes. Locate updates the blue point; selecting a
new search result replaces the purple point. Points last for the current session.

The app focuses on historical precipitation radar: forecasts, weather dashboards,
alerts, notifications, satellite imagery and provider switching are not implemented.

## Build and run

Requirements: JDK **17**, Android SDK platform **35**, build tools **35.0.0**, and
platform-tools. Install these with Android Studio or `sdkmanager`, accept the SDK
licenses, and set `JAVA_HOME` and `ANDROID_HOME` to those installations.
The checked-in Gradle 8.13 wrapper verifies its distribution checksum.

1. Provision the developer-owned CARTO key as described below.
2. Put it in the ignored `local.properties`:

   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   CARTO_API_KEY=your-project-key
   ```

   Alternatively export `CARTO_API_KEY` in your shell (takes precedence).
   If it is stored in the ignored `.env` file, export it before invoking Gradle:

   ```sh
   set -a
   source .env
   set +a
   ```

   Gradle reads the environment; it does not load `.env` automatically.
3. Build and install on a connected device/emulator:

   ```sh
   ./gradlew :app:assembleDebug
   adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s emulator-5554 shell am start -n com.freedar/.MainActivity
   ```

   Replace `emulator-5554` with the intended device serial from `adb devices` when
   installing elsewhere. The debug APK is signed and installable for testing;
   upgrades require the same signing certificate. See the [artifact/signing record](docs/validation/issue-10.md#testing-artifact-and-installation).

Without a key, debug builds remain usable for fixture testing and show an explicit
base-map configuration message over a neutral background. They do not silently
use anonymous CARTO access. The key is embedded in the installed app, so users
never need a key/account. Mobile client keys are extractable from APKs; never use
a private backend credential here. Build outputs and `local.properties` are ignored.

### Signed release builds

Release signing uses the JKS keystore at
`~/.local/share/freedar/signing/freedar-release.jks`, alias `freedar`, with the same
password for the store and key. Override the path with `FREEDAR_KEYSTORE_PATH`
if needed. Keep the keystore backed up and its password in your password manager.

In an interactive Bash terminal, with JDK 17 and the Android SDK configured:

```bash
(
  set -e
  # Load the project's CARTO client key, if kept in .env.
  set -a
  source .env
  set +a
  read -r -s -p 'Freedar signing password: ' FREEDAR_KEY_PASSWORD
  printf '\n'
  export FREEDAR_KEY_PASSWORD
  ./gradlew --no-daemon --no-configuration-cache :app:assembleRelease
)
```

The subshell keeps the password out of the parent shell after the build. If the
CARTO key is already exported or in `local.properties`, omit the `.env` loading
lines. Do not enable shell tracing when entering or using signing credentials.

The signed artifact is `app/build/outputs/apk/release/app-release.apk`. Release
builds fail if the keystore, password or CARTO key is missing. Debug builds and
fixture tests do not require these release credentials.

Verify the APK using the Android SDK build tools:

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Compare the signer's SHA-256 certificate digest with the release-key fingerprint
recorded during setup. Existing debug installations must be uninstalled before
installing this differently signed release, which removes their local app data.
Future updates must use this release key and a higher `versionCode`.
Signed APKs and checksums are distributed through [GitHub Releases](https://github.com/awstephan/freedar/releases).

## CARTO provisioning and cost

- The maintainer requests a project-specific key at
  <https://carto.com/basemaps/apikey/>. Use the noncommercial free tier for this
  personal/community project; no paid plan or recurring service is required.
- As reviewed on 2026-10-30, noncommercial use is free up to **5 million tile
  requests/calendar month**, aggregated across the owner's keys and raster/vector
  services. Monitor the dashboard and contact CARTO about a higher noncommercial
  allowance rather than enabling a paid plan. Recheck terms before publishing.
- Use a key suitable for native app requests, without browser Referer/Origin
  restrictions. The app does **not** send CARTO's optional mobile-app restriction
  headers; do not enable that restriction until package/certificate headers are
  implemented and tested. Keep the key specific to Freedar.
- The app requests the **current hosted Positron or Dark Matter style**, not legacy v1 assets.
  CARTO's style/glyph/sprite/tile URLs can change as they update their maps; the
  network adapter attaches the project key only to `cartocdn.com` and subdomains.
- The top-left **Attributions (©)** button opens linked credits for **OpenStreetMap contributors**, **CARTO**,
  **RainViewer**, **MapLibre**, and **GeoNames**. This owner-requested collapsed layout conflicts
  with CARTO's published visible-credit wording; see [the attribution finding](VALIDATION.md#carto).

## Behavior and boundaries

- **Info** includes the legend, which explains RainViewer Universal Blue rain/snow reflectivity with validated
  dBZ color samples. **Settings** offers immediate radar-opacity preview (65% default
  and reset), System/Light/Dark for map and controls, and
  Slow 1 / Normal 2 / Fast 4 fps playback. Choices persist. About includes version,
  project and source links. Controls stay visible, with compact corner icons and a thin bottom timeline.
  Dark land is charcoal (#333333) and water #222222, preserving radar colors and readable roads.
  See [appearance semantics and storage boundary](docs/radar-appearance.md).
- **Locate** uses Android's platform GPS/network providers for a one-shot fix,
  waiting at most 30 seconds after permission. No cached last-known position is used;
  updates older than 30 seconds are ignored. Success centers at **zoom 9** and
  returns radar to newest paused/following. This local-area framing is intended
  to show nearby approaching precipitation; ground span varies with latitude
  and viewport size, and zoom adds no radar detail. There is no subsequent tracking.
  **Cancel** remains visible while waiting. Pan/zoom, search-style navigation and
  backgrounding invalidate the request, including late permission/fix callbacks.
  Denial or failure leaves camera/timeline unchanged and explains the outcome.
  `MainActivity.navigationStarted()` is the shared entry point search
  selection must call before moving the camera. Requests do not survive recreation.
  Cold launches also attempt one fix if location permission is already granted; no automatic
  permission prompt occurs. A missing permission/fix leaves the remembered view usable.
- **Search** finds cities and towns worldwide using a bundled GeoNames cities500
  database, entirely offline. Submit a place name using the keyboard's Search action;
  results identify region and country. Selection cancels pending Locate, centers at
  zoom 9, and returns radar to newest paused/following. Search stays open until
  selection or dismissal. It does not search addresses/businesses or guarantee every
  settlement; alternate names and normalized prefixes help with spelling variants.
  See [dataset provenance, reproduction, matching and storage](docs/place-search.md).
- `radar/` is an Android-free behavioral module. `LatestRadar` exposes Refresh,
  Play, Pause, Select and Latest commands and observable state. `RadarProvider`,
  `Clock`, coroutine scheduling and `RadarDisplay` control responses, time and
  actual image presentation. Commands run on the main thread; their caller owns
  lifecycle cancellation. No generalized provider registry is introduced.
- `RainViewer` returns ordered historical frames using the returned HTTPS host
  and opaque paths. The timeline uses the two-hour window ending at the greatest
  returned frame time, without assuming a frame count. Manifest `generated` and
  future/nowcast entries never determine frame time.
- Opening presents newest imagery before loading history. Play starts at oldest,
  advances at a default 2 fps (slower when waiting for imagery), loops, and holds
  the newest available image for one second. Pause retains the displayed frame.
  Scrubbing pauses and retains selection, even across manifest updates; selecting
  the newest point is still a fixed selection. **Latest** returns to paused,
  following mode. The timeline uses ordered frame positions, with markers for
  unavailable frames and intervals exceeding the provider's ten-minute cadence.
  During playback, newly announced newest imagery preempts history loading and
  is prepared invisibly first. The displayed historical frame and its timestamp
  remain unchanged while that preparation runs.
- **Frame time means composite generation time**, not exact observation time at
  each location. The display uses the phone's clock format/time zone and includes
  a date. A small tag follows the displayed frame above the slider during playback/scrubbing
  and when paused on history; it hides when paused at the latest frame. Full time/age details
  remain available in Info. A feed is marked stale after its newest-known frame exceeds **20 minutes**,
  independently of the retained displayed image's age if replacement tiles fail.
- `RadarMap` owns the native renderer. It retains the previous frame while a
  candidate loads beneath the opaque base-map background, commits only after
  native tile parsing and full rendering, and removes failed/cancelled candidates.
  Transport layers and labels are above radar. Obsolete scrub requests are cancelled and serialized;
  the timestamp changes only after the replacement is presented. Pan/zoom changes
  neither timeline mode nor selection and never recenters the map.
  Playback loads its one requested next frame under the background, then waits
  at a presentation gate for the chosen speed's interval or one-second endpoint hold. Camera
  changes during that wait require readiness for the new viewport before exposure.
  Native tile errors remain visible; blank pixels are never called clear weather.
- Native radar source maxzoom is **7**; camera maxzoom is **18**. Zooming closer
  magnifies the same radar pixels. Pan/pinch remain available during loading and
  failures; rotation and tilt are disabled. The initial overview frames the contiguous USA,
  with worldwide panning (including Alaska and Hawaii).
- Opening/foreground return, explicit Refresh/Retry and a five-minute foreground
  timer refresh the manifest. Following mode updates imagery; a historical
  selection stays selected. Backgrounding suspends playback and cancels outstanding
  commands and footprint downloads; there is no background polling or location.
  Returns under five minutes retain selection and automatically resume previously
  active playback. At five minutes or longer, return resets to newest paused/following,
  preserving the camera. Saved state includes absence time even on Android 8's
  save-before-stop lifecycle ordering. History loading is
  demand-driven with **zero speculative prefetch**. Map state is restored through
  Android activity recreation, along with timeline selection and playback mode.
- Offline, loading, manifest refresh failure, imagery failure, and stale-feed notices
  are independent. A successful historical presentation cannot dismiss a failed
  manifest refresh. Retry reloads a selected viewport without switching its time.
  A red Info icon with a warning badge indicates stale/offline/failed data; its panel contains
  displayed-frame age, newest-feed age, diagnostics and Retry/Refresh. It also contains the
  coverage explanation and radar legend.
- RainViewer's infrequently updated coverage mask is a **static footprint**, not
  live completeness. Black mask pixels shade known outside-footprint areas; inside
  the footprint live availability remains unknown, and blank radar never means clear
  weather. Missing/loading footprint areas are explicitly unknown. Coverage uses
  returned provider host and the documented `/v2/coverage/0/…/0/0_0.png` endpoint.
  At most 16 viewport tiles (z0–7) are fetched sequentially through the shared bounded
  client/cache/budget. Decoded images enter native image sources, keeping stalled
  coverage HTTP requests out of radar's native presentation-ready gate. Viewport
  changes discard obsolete footprint sources; no history prefetch or disk cache.
- Concurrent refreshes are coalesced. HTTP calls have timeouts, native tile calls
  use at most four requests per host. New radar/coverage tile downloads are spaced
  at least 750 ms apart (80 per minute); cached tiles bypass pacing and cooldown.
  A provider 429 blocks new downloads according to numeric or HTTP-date `Retry-After`
  (one minute if absent/invalid). Multiple devices behind one IP can still
  exhaust RainViewer's shared 100-request/minute allowance. A cold sequence of
  13 frames × 8 tiles needs 104 requests even before metadata/retries, so it cannot
  all load within one minute. Throttled frames retain their timeline slots: the last
  image stays visible with a retry countdown, then the pending frame retries automatically.
  Selecting a cached frame cancels the pending retry and can display immediately.
  Actual missing/failed frames are still skipped until refresh/retry.
  The client and limiter live at application scope, preserving backoff on rotation.
- CARTO resources and radar share a 100 MB LRU device store with retention deletion
  and Settings → Clear downloaded data. See [downloaded imagery](docs/downloaded-imagery.md).
  OkHttp has no second disk cache; native responses are marked no-store and
  MapLibre's ambient cache maximum is zero. In-memory
  reuse is bounded to a 24 MiB application-lifetime LRU of radar tile bytes,
  expiring after 15 minutes (individual entries capped at 512 KiB). The renderer
  holds only the visible frame and one candidate, rather than a source per history
  frame. Warm playback reuses downloaded bytes, not a freshly downloaded sequence.
  Radar pixels and frame descriptors expire two hours after original frame time;
  saved-history fallback preserves actual time and failed-refresh/offline/stale notices.
  Fresh launches restore remembered center/zoom as a fallback, attempt current
  location when already permitted, and start latest paused/following; theme and
  orientation recreation preserve the existing timeline behavior.

## Tests and checks

The agreed seams are app-level radar behavior, HTTP request/reuse/backoff behavior,
and native Android map integration with local fixtures. Routine tests need no provider credentials
or public network access (after dependency download).

```sh
# Typechecking / focused behavior test during development
./gradlew :radar:compileKotlin :app:compileDebugKotlin
./gradlew :radar:test --tests com.freedar.radar.LatestRadarTest
./gradlew :radar:test --tests com.freedar.radar.PlaybackTest
./gradlew :radar:test --tests com.freedar.radar.TileLoadingTest

# Native integration on an already booted device
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.freedar.MapRenderingTest

# Full checks
./gradlew test :app:lintDebug :app:assembleDebug
# With release credentials configured as above:
./gradlew --no-daemon --no-configuration-cache :app:lintRelease :app:assembleRelease
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

For a Google-free API-26 test device, install the SDK image
`system-images;android-26;default;x86_64` (**default**, not google_apis or Play Store),
create an AVD, and wait for `adb shell getprop sys.boot_completed` to print `1`.
Use hardware acceleration when available. The instrumentation test renders a
known blue PNG through the real native renderer, asserts snapshot pixels and
records actual HTTP paths at camera zoom 10; testing a source configuration alone
would not establish overzoom behavior. Debug-only cleartext access supports the
local fixture; release builds disallow it.

To repeat the **opt-in live CARTO check**, build with the developer key and run:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.freedar.LiveCartoTest \
  -Pandroid.testInstrumentationRunnerArguments.liveCarto=true
```

This launches the real app, moves to a regional New York viewport, verifies native
glyph loading and named rendered symbol features, and captures a screenshot in
the app's cache. Without the `liveCarto=true` argument this live-service test is
skipped, keeping routine fixture tests independent of provider access.

### Recorded validation

Start with the [compact-map checks](docs/validation/compact-map.md) for the latest
UI/startup changes and [integrated testing results](docs/validation/issue-10.md)
for cross-feature, geographic and process-restart evidence. APK hashes and sizes
in the integrated record identify that earlier build, not every subsequent build.

Earlier feature evidence covers [map rendering](docs/validation/issue-2.md),
[playback](docs/validation/issue-3.md), [freshness](docs/validation/issue-4.md),
[coverage](docs/validation/issue-5.md), [positioning](docs/validation/issue-6.md),
[search](docs/validation/issue-7.md), [appearance](docs/validation/issue-8.md),
[map storage](docs/validation/issue-9.md) and
[offline radar](docs/validation/issue-9-radar.md).

## Project structure and documentation

| Path | Purpose |
| --- | --- |
| `app/` | Android/Compose UI, MapLibre rendering, platform location, search, networking and device storage; unit and native instrumentation tests. |
| `radar/` | Android-free Kotlin radar behavior, provider integration, timeline/playback state and behavioral tests. |
| `tools/` | Bundled place-search dataset generation tooling. |
| [DESIGN.md](DESIGN.md) | Agreed product design, including the retained compact-map revisions. |
| [docs/place-search.md](docs/place-search.md) | GeoNames provenance, dataset reproduction and search behavior. |
| [docs/radar-appearance.md](docs/radar-appearance.md) | Legend, theme, opacity and playback settings. |
| [docs/downloaded-imagery.md](docs/downloaded-imagery.md) | Cache boundaries, provider retention, expiry and offline behavior. |
| [docs/validation/](docs/validation/) | Dated implementation and test evidence, with limitations. |

[VALIDATION.md](VALIDATION.md) is the initial technical investigation, with a later
attribution finding. Its original unresolved-check list predates implementation;
use the dated feature and integrated records above for current evidence.

## Provider contracts and reference

- [RainViewer Weather Maps](https://www.rainviewer.com/api/weather-maps-api.html),
  [transition limits](https://www.rainviewer.com/api/transition-faq.html),
  [palette](https://www.rainviewer.com/api/color-schemes.html),
  [terms](https://www.rainviewer.com/api.html).
  Universal Blue (2), smoothing and snow enabled (`1_1`) are used. No mixed
  precipitation classification or rainfall/snowfall-rate conversion is claimed.
- [CARTO FAQ](https://docs.carto.com/faqs/carto-basemaps),
  [basemap terms](https://carto.com/legal/basemap-terms/),
  [attribution](https://carto.com/attribution/).
- [MapLibre Android artifacts](https://maplibre.org/maplibre-native/android/examples/artifacts/),
  [raster source specification](https://maplibre.org/maplibre-style-spec/sources/).
- [QuickWeather WeatherMapView](https://github.com/TylerWilliamson/QuickWeather/blob/master/app/src/main/java/com/ominous/quickweather/view/WeatherMapView.java)
  was inspected as a lifecycle/raster-layer reference. No source was copied. Its
  legacy style, hardcoded radar host, camera zoom cap, and transparent-success
  substitution on HTTP failures are not used here.

**Permission record:** On 2026-11-01 the maintainer confirmed approval for RainViewer
caching, preloading and distribution subject to the published site terms, with no
additional restrictions. Radar storage uses a two-hour frame-based retention window.
This is maintainer-confirmed permission, not independently inspected correspondence;
see [downloaded imagery](docs/downloaded-imagery.md). CARTO retains its separate
device-cache limits. The earlier [release decisions and limits](docs/validation/issue-10.md#release-decisions-and-limits)
record the pre-release testing context; current download and signing instructions
are above.

## License

Freedar's original source code is licensed under the [BSD 3-Clause License](LICENSE).
Third-party libraries and bundled data retain their respective licenses; the
bundled GeoNames place data is licensed under CC BY 4.0. Map and radar services
remain subject to their providers' terms. The project license does not grant
rights to those services, credentials, or third-party content.

Settings → About → **Software licenses (offline)** displays the license texts and
notices packaged in the APK, including GeoNames attribution and modification
details. See [third-party notices](docs/third-party-notices.md) for the dependency
audit, regeneration command, and APK verification step.
