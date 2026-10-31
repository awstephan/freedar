# Issue #3 implementation validation

Scope: [Historical playback and scrubbing](https://github.com/awstephan/freedar/issues/3).
Run on 2026-10-31. Baseline: `6e751d3`, confirmed by the maintainer for review.

## Agreed test seams and loading policy

The maintainer approved app-level behavior with controlled provider/time/display,
HTTP fixtures with controlled time, and real MapLibre Android integration with
local tiles. History is demand-driven, with no speculative prefetch, bounded
in-memory reuse and initial newest-frame presentation ahead of history.

## Build and behavioral checks

Used JDK 17.0.21 and the existing Android SDK 35 / Gradle 8.13 toolchain.
`CARTO_API_KEY` was exported from ignored `.env` before Android builds; credentials
were not logged or committed. The host's default Java 25 failed Gradle setup;
using the documented JDK 17 resolved that environment mismatch.

Red/green cycles and focused tests covered:

- Ordered returned history within two hours of newest; opaque paths and no
  nowcast/manifest-time substitution; newest displayed before history loads.
- Oldest-first Play, 500 ms steps, one-second newest hold, looping and Pause.
- Scrubbing pauses and preserves selection across refresh; Latest resumes following.
- Timestamp publication waits for presentation, superseded work is cancelled,
  and obsolete completions cannot overwrite the newer selection.
- Unavailable frames are skipped and marked without retrying on every loop;
  refresh can recover a failed historical selection without switching to latest.
- Pausing during loading retains the last image and its selected time.
- Memory LRU reuse/eviction, shared-IP HTTP 429 backoff and numeric Retry-After,
  serving warm imagery during backoff, and a 90-request rolling-minute budget.

The full check passed: **19 JVM tests, 3 Android tests, no skips**, debug/release
Kotlin compilation, Android lint and both APK builds:

```sh
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.liveCarto=true
```

## Actual native rendering and request counts

The physical device was disconnected before this run. Native checks therefore ran
on the existing Google-free AOSP API-26 emulator (1080 × 1920, software acceleration,
ANGLE/SwiftShader). The earlier GrapheneOS report belongs to #2, not this playback build.

`PlaybackRenderingTest` uses real native snapshots and local red/blue tile fixtures,
with delayed responses for the superseded frame:

- At camera zoom 10, newest imagery made **2** requests: native z7 plus z3 fallback.
- Loading the older frame brought the cold two-frame total to **4** requests.
- Returning to newest and then older made **0 additional network requests**.
- Red/blue snapshot pixels matched the controller's published frame times.
- Pending delayed imagery retained the red image and its original timestamp.
- Panning from (40, -100) to (41, -96.1), while increasing camera zoom to 11 and
  superseding the delayed selection, retained timeline mode and camera position.
  The late response did not overwrite the newer blue frame.
- The full scenario made **12** requests, including new tile columns after panning:
  z7 x28/y48 initially; z7 x29/y47 and x29/y48 after panning, plus z3 fallbacks.
  **No radar request exceeded z7.**

The existing `MapRenderingTest` also passed, including unavailable replacement
retention. The opt-in `LiveCartoTest` passed with one native glyph-load event,
zero glyph errors, and named rendered symbols including New York, Bridgeport,
White Plains and New Haven. Live service traffic was limited to that smoke check;
budget/backoff failures were exercised only against local fixtures.

## Resource and lifecycle policy

- At most one displayed source and one candidate; candidates load beneath the
  opaque background before being moved into the radar slot, with no raster fade.
- Four native HTTP calls per host, eight overall; no history prefetch queue.
- Application-lifetime 24 MiB radar-byte LRU, 15-minute entry lifetime, 512 KiB
  maximum retained tile. No disk tile caching or persistent offline claim.
- 90 cold radar requests per rolling minute. A 429 backs off for at least 60 seconds,
  honoring longer numeric Retry-After up to one hour. Shared IPs can throttle sooner.
  13 frames × 8 tiles = 104 requests before metadata or retries; cold playback is
  readiness-paced and cannot promise an immediate full-history loop.
- Foreground metadata refresh every five minutes; paused historical selections
  remain selected. Backgrounding pauses playback and cancels commands/polling.

## Limits of this evidence

The software emulator does not establish physical-device playback smoothness,
GPU behavior, accessibility or live worldwide radar coverage. This fixture measures
a small viewport, not a claim that every viewport uses two tiles. Actual request
counts depend on viewport and native fallback behavior. In-session memory reuse
does not resolve public distribution or persistent caching permission questions.

Review results are recorded after the independent standards/spec review.
