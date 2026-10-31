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
- The keyed CARTO acceptance gap remains explicit; review did not waive it.

## Required live follow-up

- **Developer CARTO key not provided.** Provision it via the README, install a
  keyed build, and verify current Positron vectors, fonts/sprites, readable labels,
  and all linked credits. No anonymous key or borrowed QuickWeather credential
  was used. This part of issue acceptance remains pending.
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
