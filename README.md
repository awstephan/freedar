# Freedar

A native Android map with historical precipitation radar and a scrubbable playback
strip. [Issue #4](https://github.com/awstephan/freedar/issues/4) adds honest freshness
and lifecycle behavior to playback from [#3](https://github.com/awstephan/freedar/issues/3), under
[spec #1](https://github.com/awstephan/freedar/issues/1).

Kotlin/Compose hosts MapLibre Native **OpenGL ES**, with current CARTO Positron
vector maps and RainViewer radar. Android 8.0 (API 26) is the minimum; compile and
target SDK are 35. There is no Play Services dependency. Foreground location
permission is requested only when you explicitly choose **Locate**.

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
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n com.freedar/.MainActivity
   ```

Without a key, debug builds remain usable for fixture testing and show an explicit
base-map configuration message over a neutral background. They do not silently
use anonymous CARTO access. The key is embedded in the installed app, so users
never need a key/account. Mobile client keys are extractable from APKs; never use
a private backend credential here. Build outputs and `local.properties` are ignored.

`./gradlew :app:assembleRelease` builds an unsigned release APK. Signing and public
distribution are not configured or authorized by this slice.

## CARTO provisioning and cost

- The maintainer requests a project-specific key at
  <https://carto.com/basemaps/apikey/>. Use the noncommercial free tier for this
  personal/community project; no paid plan or recurring service is required.
- As reviewed on 2026-10-30, noncommercial use is free up to **5 million tile
  requests/calendar month**, aggregated across the owner's keys and raster/vector
  services. Monitor the dashboard and contact CARTO about a higher noncommercial
  allowance rather than enabling a paid plan. Recheck terms before publishing.
- Use a key suitable for native app requests, without browser Referer/Origin
  restrictions. This slice does **not** send CARTO's optional mobile-app restriction
  headers; do not enable that restriction until package/certificate headers are
  implemented and tested. Keep the key specific to Freedar.
- The app requests the **current hosted Positron style**, not legacy v1 assets.
  CARTO's style/glyph/sprite/tile URLs can change as they update their maps; the
  network adapter attaches the project key only to `cartocdn.com` and subdomains.
- Visible on-map links credit **OpenStreetMap contributors**, **CARTO**,
  **RainViewer**, **MapLibre**, and **GeoNames**. They remain outside system-bar insets.

## Behavior and boundaries

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
  following mode. The timeline is positioned by frame time, with markers/text for
  unavailable frames and intervals exceeding the provider's ten-minute cadence.
  During playback, newly announced newest imagery preempts history loading and
  is prepared invisibly first. The displayed historical frame and its timestamp
  remain unchanged while that preparation runs.
- **Frame time means composite generation time**, not exact observation time at
  each location. The display uses the phone's clock format/time zone and includes
  a date. A feed is marked stale after its newest-known frame exceeds **20 minutes**,
  independently of the retained displayed image's age if replacement tiles fail.
- `RadarMap` owns the native renderer. It retains the previous frame while a
  candidate loads beneath the opaque base-map background, commits only after
  native tile parsing and full rendering, and removes failed/cancelled candidates.
  Labels are above radar. Obsolete scrub requests are cancelled and serialized;
  the timestamp changes only after the replacement is presented. Pan/zoom changes
  neither timeline mode nor selection and never recenters the map.
  Playback loads its one requested next frame under the background, then waits
  at a presentation gate for the 500 ms step or one-second endpoint hold. Camera
  changes during that wait require readiness for the new viewport before exposure.
  Native tile errors remain visible; blank pixels are never called clear weather.
- Native radar source maxzoom is **7**; camera maxzoom is **18**. Zooming closer
  magnifies the same radar pixels. Pan/pinch remain available during loading and
  failures; rotation and tilt are disabled. The initial overview includes Hawaii,
  northern Alaska and the Aleutians across the antimeridian, with worldwide panning.
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
  Displayed-frame age and newest-feed age stay visible independently of controls.
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
  use at most four requests per host, and the local radar budget is 90 requests
  per rolling minute. A 429 blocks new radar requests for at least one minute
  (longer for numeric `Retry-After`). Multiple devices behind one IP can still
  exhaust RainViewer's shared 100-request/minute allowance. A cold sequence of
  13 frames × 8 tiles needs 104 requests even before metadata/retries, so it cannot
  all load within one minute. Failed frames are skipped until refresh/retry;
  loaded frames can continue playing through backoff.
  The client and limiter live at application scope, preserving backoff on rotation.
- No downloaded-data persistence is offered: OkHttp has no disk cache, responses
  are marked no-store, and MapLibre's ambient cache maximum is zero. In-memory
  reuse is bounded to a 24 MiB application-lifetime LRU of radar tile bytes,
  expiring after 15 minutes (individual entries capped at 512 KiB). The renderer
  holds only the visible frame and one candidate, rather than a source per history
  frame. Warm playback reuses downloaded bytes, not a freshly downloaded sequence.
  Persistent offline retention awaits permission and a later bounded-cache implementation.

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
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
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

See [foundation validation](docs/validation/issue-2.md) and
[playback validation](docs/validation/issue-3.md), and
[freshness validation](docs/validation/issue-4.md), and
[positioning validation](docs/validation/issue-6.md), and
[offline search validation](docs/validation/issue-7.md) for actual results, limitations
and the remaining live-service/device checks.

## Provider contracts and reference

- [RainViewer Weather Maps](https://www.rainviewer.com/api/weather-maps-api.html),
  [transition limits](https://www.rainviewer.com/api/transition-faq.html),
  [palette](https://www.rainviewer.com/api/color-schemes.html),
  [terms](https://www.rainviewer.com/api.html).
  Universal Blue (2), smoothing and snow enabled (`1_1`) are used. No mixed
  precipitation classification or exact intensity legend is claimed.
- [CARTO FAQ](https://docs.carto.com/faqs/carto-basemaps),
  [basemap terms](https://carto.com/legal/basemap-terms/),
  [attribution](https://carto.com/attribution/).
- [MapLibre Android artifacts](https://maplibre.org/maplibre-native/android/examples/artifacts/),
  [raster source specification](https://maplibre.org/maplibre-style-spec/sources/).
- [QuickWeather WeatherMapView](https://github.com/TylerWilliamson/QuickWeather/blob/master/app/src/main/java/com/ominous/quickweather/view/WeatherMapView.java)
  was inspected as a lifecycle/raster-layer reference. No source was copied. Its
  legacy style, hardcoded radar host, camera zoom cap, and transparent-success
  substitution on HTTP failures are not used here.

**Open permission questions:** RainViewer's introduction mentions small-scale
community use while the terms emphasize personal/educational use. Public APK
distribution and persistent caching/preloading need confirmation from
`support@rainviewer.com`; no inquiry has been sent. CARTO permits limited device
caching under its terms, but this implementation deliberately uses none. A build
or rendering test cannot resolve those permission questions.
