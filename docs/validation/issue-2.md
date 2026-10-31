# Issue #2 implementation validation

Run on 2026-10-30 (host local date; later artifacts use 2026-10-31 UTC).
Scope: [Working Android map with latest radar](https://github.com/awstephan/freedar/issues/2).

## Build and JVM evidence

- JDK 17.0.21, Gradle 8.13, Android SDK 35/build tools 35.0.0, Kotlin 2.1.20,
  AGP 8.9.2, MapLibre `android-sdk-opengl:11.13.5`.
- `:radar:compileKotlin`, `:app:compileDebugKotlin`, and
  `:app:compileDebugAndroidTestKotlin` pass.
- Eight `LatestRadarTest` cases pass at the app-level seam: greatest historical
  time with provider host/path; malformed/empty/unsafe manifests; retained frame
  and retry recovery; stale boundary at 1,200/1,201 seconds; timestamp waits for
  presentation; overlapping refresh coalescing; prompt cancellation of an actual
  pending HTTP request on foreground exit; fresh feed vs retained old imagery
  after a renderer failure. Provider tests use local MockWebServer.
- Android lint passes with zero errors and eight warnings (dependency updates,
  backup-rule guidance and a missing custom launcher icon). The resolved
  `debugRuntimeClasspath` was inspected:
  no `com.google.android.gms` or Firebase artifacts. Gson/Guava are Java libraries,
  not Play Services.

## Native Android evidence

Environment: AOSP `system-images;android-26;default;x86_64`, emulator 37.2.13,
1080 × 1920, 1,536 MB RAM, CPU acceleration off. No `/dev/kvm` is available.
`adb shell pm list packages com.google.android.gms` and `com.android.vending`
both return no packages. The app uses MapLibre's OpenGL artifact; the emulator
provides GLES through ANGLE/SwiftShader (`-gpu swangle`).

`MapRenderingTest` ran against the real native map, local PNG HTTP responses and
native snapshots. Its successful run established:

- The initial overview contains representative extrema for northern Alaska,
  Hawaii, the eastern mainland, and the Aleutians across the antimeridian.
- Camera zoom 10 requested `/radar/256/7/28/48/2/1_1.png`, plus a lower-resolution
  fallback `/radar/256/3/1/3/2/1_1.png`. **No radar request zoom exceeded 7.**
- The snapshot's center was `-11381768` (ARGB integer), visibly blue relative to
  the neutral background: actual raster rendering, not just source configuration.
- Pan/zoom APIs operated on the Google-free API-26 runtime.
- The final test adds a newer candidate whose tile requests return HTTP 503.
  Presentation fails and the subsequent native snapshot still contains the
  previous blue frame. It also runs through the production application-level
  network client and zero-cache configuration.

Final command passed (8 JVM tests, 1 Android integration test, debug/release
compilation, lint and both APK builds):

```sh
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease :app:connectedDebugAndroidTest
```

An additional manual install/launch succeeded with no app crash in AndroidRuntime
logs. Live RainViewer radar rendered on the neutral, explicitly unconfigured base
map. The UI showed **Oct 31, 2026, 12:00 AM CDT**, eight minutes old. The live
manifest had newest historical `time=1793422800` (**05:00:00 UTC**) and
`generated=1793423128` (**05:05:28 UTC**), confirming the displayed distinction.
The frame path was opaque (`/v2/radar/752f2d0573ad`), as expected. A manual swipe
was accepted without an app crash. This is a smoke check, not a coverage survey.

The unaccelerated emulator displayed a **System UI** not-responding dialog during
manual launch; choosing Wait recovered. Therefore these results do not establish
interactive performance on physical older hardware.

The fixture does not establish live coverage, real CARTO label quality, physical
GPU compatibility, accessibility quality or gesture responsiveness. Those need
the follow-up checks below.

### Failures found and resolved during validation

1. The emulator's direct `-gpu swiftshader` backend crashed before tests with
   SIGSEGV following Fedora SELinux `execheap` denials in RenderThread. Switching
   to `-gpu swangle` booted successfully without changing host security policy.
2. The fixture initially constructed a MockWebServer URL on Android's main
   thread, causing reverse DNS and `NetworkOnMainThreadException`. Fixture URLs
   are now constructed from the loopback address/port outside the UI callback.
3. Native snapshots initially contained the background after the candidate was
   parsed. MapLibre's default opacity transition meant a full render callback did
   not establish the final paint value. Radar opacity transitions are now zero,
   tile fading is zero, and publication waits for the presentation render.
   The original pixel assertion then passed.

## Review and remediation

Two independent code-review agents reviewed the staged implementation against
Git's empty tree, the user-approved baseline for this initially unborn repository.

- Standards: corrected feed staleness to use newest-known provider time separately
  from displayed-image age; made the display boundary mandatory; moved the HTTP
  client and limiter to application lifetime so rotation retains rate-limit state.
- Spec: candidate tile errors now fail both loading and final presentation phases.
  Immediate native 503 failure/retention is covered. The narrow race of a tile
  failing after readiness while panning is not independently forced by the test.
- The initial review left the keyed CARTO acceptance gap explicit; the subsequent
  keyed-device check below resolves that gap.

## Keyed CARTO follow-up — 2026-10-31

The maintainer supplied `CARTO_API_KEY` in `.env`. The file is now ignored, and its
value was exported into the build environment without logging or committing it.
The keyed debug APK built, installed and launched on the same Google-free API-26
AOSP emulator.

- The current hosted style identified itself as **Positron (Protomaps)**, with 95
  layers including 28 symbol layers. It referenced assets under release
  `20261026-01`. Keyed font PBF, sprite JSON and sprite PNG requests returned
  HTTP 200 (32,752, 81 and 176 bytes respectively for the sampled assets).
- The all-state overview rendered current vector geography with live RainViewer
  precipitation. No missing-key or base-map error message appeared.
- The initial absence of overview labels was traced to CARTO's style definition:
  its primary country-label layer has `minzoom: 2`. This was not a failed font
  request. Regional zoom 7 rendered readable New York, White Plains, Bridgeport,
  New Haven, Poughkeepsie and other place labels over muted roads and land colors.
- The opt-in `LiveCartoTest` passed on the real app: at least one native glyph-load
  event, zero glyph-error events, and a named feature (`Brookhaven`) returned by
  querying rendered symbol layers. A subsequent screenshot showed the broader
  set of labels listed above. This test does not run live unless explicitly enabled.
- Required CARTO and OpenStreetMap credits remained visible. Tapping them emitted
  Android VIEW intents for `https://carto.com/attribution/` and
  `https://www.openstreetmap.org/copyright`, respectively.
- Screenshots inspected locally: `/tmp/opencode/freedar-carto-overview.png` and
  `/tmp/opencode/freedar-carto-regional.png`. These temporary artifacts contain no
  key and are not committed. The software emulator again needed Wait on a System
  UI responsiveness dialog; this does not establish physical-device performance.

Follow-up checks passed with the key exported: the JVM test task (eight existing
cases, up to date), Android lint, and **both** connected Android tests (the live
CARTO check and fixture rendering/overzoom/failure-retention check), with no skips:

```sh
./gradlew test :app:lintDebug :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.liveCarto=true
```

## Maintainer device confirmation — 2026-10-31

The maintainer reports successfully installing and testing the keyed APK on
GrapheneOS. This is physical-device confirmation supplied by the maintainer;
the device model, OS version and individual test steps were not recorded.

## Issue #2 acceptance disposition

All nine acceptance criteria are satisfied by the implementation and evidence
above: API-26+ Compose/OpenGL foundation and reproducible builds; keyed current
CARTO maps and linked credits; provider-derived latest historical frame; north-up
navigation and verified radar overzoom; all-state initial extent and regional
labels; retained imagery/error behavior; controlled app-level boundaries;
behavioral and Google-free native tests; and documented references, permission
questions and absence of background polling/location. The keyed CARTO follow-up
and maintainer's GrapheneOS confirmation complete the outstanding acceptance
bookkeeping. Broader validation and external permissions below remain follow-up
work rather than unmet issue #2 acceptance criteria.

## Broader live follow-up

- Survey live RainViewer coverage in the contiguous US, Alaska, Hawaii and an
  international viewport. The smoke check above validates frame-time handling,
  not completeness of geographical coverage.
- Check portrait/landscape, pan/pinch while requests fail, large fonts and screen
  reader focus, actual older GLES hardware, and a newer Android release.
- Public distribution and persistent radar caching/preloading permission are
  unresolved; no support inquiry was sent. No public APK distribution is authorized.

## Reproduction

Use the commands and provisioning notes in [README](../../README.md). Wait for
`adb shell getprop sys.boot_completed` to print `1` before connected tests. Reports
are generated under `radar/build/reports/tests/`, `app/build/reports/androidTests/`
and `app/build/reports/lint-results-debug.html`; generated artifacts are ignored.
The pre-existing root `VALIDATION.md` records the earlier documentation-only
investigation and is not a record of these implementation checks.
