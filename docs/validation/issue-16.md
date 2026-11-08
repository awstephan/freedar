# Android support and Pixel 4 XL validation (#16)

Session: **2026-11-08**. Parent: [#1](https://github.com/awstephan/freedar/issues/1).
Baseline production source: `8cf37b5d36c2f8ef5585d456ac0455118d4803d4` (v0.1.1).
The working tree includes the pre-existing #17/#19 instrumentation corrections
described in `issues-17-19.md`, plus this task's physical-device search benchmark
opt-in. No production Kotlin or minimum SDK change is part of this task.

## Revised scope

The owner chose upstream Android platform security-fix coverage as the support
reference, with unsupported older versions still allowed to install. The
[support policy](../android-support.md) records Android 14–17 as the reviewed
range; `minSdk = 26` remains the installation floor. The original mandatory
API-26 rerun is superseded by this decision. This physical Android-12 run is
opportunistic compatibility evidence, not an extension of support.

## Device and isolation

- Physical Pixel 4 XL (`coral`), Qualcomm SM8150, 6 GB RAM, arm64-v8a.
- CalyxOS 3.2.0, Android 12 / API 31; build `SQ1D.220205.003.22302000`,
  fingerprint `google/coral/coral:12/SQ1D.220205.003/22302000:user/release-keys`,
  security patch `2022-02-05`.
- Adreno 640, OpenGL ES 3.2 `V@0490.0`, driver date 2020-11-30;
  MapLibre Native OpenGL ES 11.13.5. SurfaceFlinger reported 90 Hz.
- Display 1440 × 3040, density 560 dpi; no resolution/density override.
- CalyxOS's microG `com.google.android.gms` package is installed and enabled,
  version `0.2.24.214816-23 (2e45c20)`. This is not a runtime without a GMS
  replacement. Fixtures use local HTTP and platform APIs; the app has no Play
  Services dependency. No microG packages were disabled or removed.
- JDK 17 (Temurin), Android SDK/build tools 35/35.0.0, Gradle wrapper 8.13.
- Debug application ID **`com.freedar.issue16`**, test ID
  **`com.freedar.issue16.test`**. Existing `com.freedar` v0.1.1 was not overwritten
  or cleared. Its initial install/update time was 2026-11-08 09:33:51.

APK SHA-256 (the debug manifest confirms `minSdk 26`, `targetSdk 35`):

```text
30946ecc7118dae89a3381f0e99db164b22428b254bfa0aad8e35ba8f420857a  app-debug.apk
2377d4bdd3907c25dd061dc431c432bc5a0a2e9529e88e8322490f9227f9add9  app-debug-androidTest.apk (final suite)
2a076d5061c4a29bb206ca417f0f206d07083c6388ed11b2627ad509ac883308  app-debug-androidTest.apk (earlier offline measurements)
```

## Reproduction

Save an external init script as `/tmp/opencode/issue16/validation.gradle`:

```groovy
allprojects {
    plugins.withId('com.android.application') {
        android.buildTypes.debug.applicationIdSuffix = '.issue16'
    }
}
```

Select the phone's serial from `adb devices -l`; keep it unlocked. Commands here
use `ANDROID_SERIAL` so no other connected phone is selected accidentally.

```sh
export JAVA_HOME=/tmp/opencode/java ANDROID_HOME=/tmp/opencode/android-sdk
export ANDROID_SERIAL=<pixel-4-xl-serial>
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.security_patch
adb shell dumpsys SurfaceFlinger | grep 'GLES:'
adb shell wm size
adb shell wm density
adb shell dumpsys package com.google.android.gms | grep versionName
./gradlew -I /tmp/opencode/issue16/validation.gradle \
  :radar:compileKotlin :app:compileDebugKotlin :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.freedar.MapRenderingTest
```

The focused native-renderer test passed. At camera 40 N / 100 W / zoom 10 it
recorded **6 local requests**: one z7 radar tile, one z3 radar fallback, one z7
coverage tile, and three intentionally failing replacement requests (z3, z2,
z7). No tile zoom exceeded 7; the snapshot retained blue radar after HTTP 503.
This checks rendered pixels, actual HTTP paths, and retained imagery, not just
source configuration.

## Offline search measurements

The benchmark's existing bundled-search seam now accepts
`searchBenchmarkOffline=true` on an externally disconnected physical device.
It still asserts `ConnectivityManager.activeNetwork == null`; a deliberate
Wi-Fi-connected invocation failed that assertion before measuring anything.
The rooted-emulator mode is retained via explicit `searchBenchmarkRooted=true`.
Ordinary suites skip before probing root. No production search change was
needed. An initial invocation used the preceding instrumentation APK and was
stopped; only the rebuilt APK's completed runs are reported below.

For the two measured runs, Wi-Fi was disabled with `adb shell svc wifi disable`;
mobile data was already disabled, airplane mode was off, and no active network
was reported. Wi-Fi was restored afterward. Install both isolated APKs with
`adb install -r`, then:

```sh
# First-use run: clear only this task's isolated package.
adb shell pm clear com.freedar.issue16
adb shell am instrument -w \
  -e class 'com.freedar.PlaceSearchTest#offlineSearchSurvivesCacheRemovalAndReportsDeviceTimings' \
  -e searchBenchmarkOffline true \
  com.freedar.issue16.test/androidx.test.runner.AndroidJUnitRunner
# Repeat the instrumentation command without clearing data for the reopen run.
adb logcat -d -s PlaceSearchBenchmark:I
```

Each invocation passed one test and verified search after cache removal.
Times are device monotonic-clock measurements, rounded down to milliseconds:

| Run | Index previously installed | Install/open | First query | Queries | Median | p95 | Maximum |
| --- | --- | --- | --- | --- | --- | --- | --- |
| First use | No | 934 ms | 29 ms | 70 | 2 ms | 360 ms | 363 ms |
| New-process reopen | Yes | 14 ms | 29 ms | 70 | 2 ms | 348 ms | 360 ms |

First query: Reykjavik. The 70 queries are ten repetitions of Springfield,
Munich, 東京, Twizel, a nonexistent name, `a`, and `san`. These measure the search
API and index installation, not end-to-end keyboard/UI latency. This is one
first-use sample and one reopen sample, not a statistically repeated cold-start
benchmark. The 2-second query ceiling passed in both runs.

## Integrated checks

Run the complete default suite with normal device connectivity. Run the
opt-in physical-device offline-search benchmark separately as above:

```sh
./gradlew --continue -I /tmp/opencode/issue16/validation.gradle \
  test :app:lintDebug :app:connectedDebugAndroidTest
```

The existing device fixtures cover these observable behaviors:

| Area | Fixture |
| --- | --- |
| Cold/warm frame selection, newest-first requests, priority preparation, retained image/time during delayed replacement and pan, overzoom | `PlaybackRenderingTest` |
| Actual playback and frame-time UI through delayed tile loads | `FrameLoadingRenderingTest` |
| Current-view pan/zoom loading, canceled/abandoned completions, timeout, failure and cooldown | `ViewportLoadingRenderingTest` |
| Native HTTP 429 retention and automatic recovery | `RadarCooldownRenderingTest` |
| Independent basemap/radar startup readiness | `InitialBasemapRenderingTest` |
| Startup indicator, panels, light/dark themes and disabled system animations | `InitialLoadingRenderingTest` |
| Rotation, large text, persistent controls and remembered settings | `MapControlsRenderingTest`, `ControlsPlaybackTest` |
| Saved frame/time, missing offline region and cache clearing | `OfflineRadarRenderingTest` |

Throttling/failure scenarios are loopback fixtures, never public-provider floods.
Native snapshot assertions check image/time correspondence. Test completion times
include intentional delays and synchronization and are not playback benchmarks.

### Connectivity prerequisite discovered during validation

The first full-suite attempt ran with Wi-Fi off to include the offline-search
benchmark. It was aborted after six rendering/UI timeouts among the first eight
cases; it is not a passing suite. A focused A/B using identical APKs reproduced
`MapRenderingTest` failing with **“Radar tiles timed out”** in 31.346 seconds
offline, then passing in 5.009 seconds after Wi-Fi was restored. The native
HTTP-fixture run therefore requires normal device connectivity on this runtime,
even though its URLs are loopback. This is not evidence of a search failure or
of live-provider requests. The corrected procedure separates the disconnected
search benchmark from the normal rendering suite.

An initial focused rerun was interrupted by Gradle's delayed uninstall cleanup
after the canceled suite. It was discarded; the A/B results above were collected
after reinstalling both isolated APKs once cleanup had completed.

The next, connected suite reached the search benchmark with no failures but
hung in its automatic `su` probe on this unrooted phone. That incomplete run was
stopped. Rooted-emulator benchmarking now requires `searchBenchmarkRooted=true`,
so default runs skip before executing `su`. Physical-device benchmarking uses
the already-verified external-offline opt-in. This fixes test harness selection,
not production search or rendering.

### Final results

- Focused `PlaceSearchTest` after the opt-in correction: **2 passed, 1 skipped**;
  completed in a 23-second Gradle invocation instead of hanging at root detection.
- Final full command: **BUILD SUCCESSFUL in 4m49s**. Device XML has **38 cases:
  34 passed, 0 failures/errors, 4 skipped**. Gradle additionally counts assumption
  events in its progress display; XML totals are authoritative. The skipped cases
  are the static provider survey, two live CARTO checks, and the separately run
  offline-search benchmark. All fixtures listed above passed on this Pixel.
- JVM result XML: **53 radar + 19 app debug + 19 app release passing case
  results**, with zero failures/errors/skips (72 distinct cases, app cases in two
  variants). Final JVM tasks were up-to-date; app debug tests executed in the
  initial attempt, other JVM results were reused from Gradle's cache/up-to-date
  state. Production and instrumentation Kotlin compilation passed.
- Debug lint: **0 errors, 14 warnings, 1 informational finding**.
- Final native map fixture: **4 requests**, all tile zooms at most 7, retained blue
  pixels after failure. Counts differ from the earlier focused run because native
  fallback scheduling is timing-dependent; neither result is a fixed request-count
  contract.
- Final playback fixture at 40 N / 100 W / camera zoom 10: **2 newest-first
  requests**, **3 total** after cold selection of a second frame, **0 additional**
  requests for warm reuse, and **1 additional** for newest-priority preparation.
  Snapshots and state assertions confirm preparing newest imagery does not replace
  the selected historical image/time prematurely. Navigation through longitudes
  −90 and −80, then 41 N / −96.1 / zoom 11, delayed replacement and cancellation
  brought the bounded scenario to **21 requests total**; all tile zooms remained
  at most 7. This is a controlled two-frame/priority scenario, not a live full-history
  download cost estimate.
- Wi-Fi restored to **1**, mobile data retained at **0**, USB stay-awake restored
  to **7**. The installed release remains versionCode 2 with unchanged last-update
  time **2026-11-08 09:33:51**. Only isolated validation-package data was cleared.

Local logs: `/tmp/opencode/issue16/{focused-map,search-cold,search-warm,search-default-fixed,full-suite-final}.log`,
`search-timings.log`, and `final-measurements.log`. Final XML:
`app/build/outputs/androidTest-results/connected/debug/TEST-Pixel 4 XL - 12-_app-.xml`.
These are local artifacts; the measured outcomes and commands are published here
and summarized in the issue completion comment.

## Owner observation

Asked specifically about v0.1.1 playback and pan/zoom on this Pixel 4 XL,
including stalls or blank flashes, the owner reported **“Smooth in my checks.”**
This is a qualitative human observation of the installed release, separate from
the current-source fixture assertions and measured search times. No frame-rate,
jank percentage, or cold/warm playback latency is inferred from that report.

## Review

Final independent standards and specification reviews found **0 actionable
findings on either axis**. The review covered this task's staged changes only;
pre-existing #17/#19 work remains separately owned.

## Limits

These checks use current-source debug APKs and local fixtures, not a rebuilt
signed release or live-provider load test. A single older flagship GPU does not
represent every older or low-end device. Fixture correctness does not establish
live vector-map frame pacing; the owner's smoothness report is uninstrumented. No new API-26
run or current Android-14 minimum-supported-version run is claimed.
