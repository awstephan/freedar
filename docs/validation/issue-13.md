# Frame preparation feedback (#13)

Parent spec: [#11](https://github.com/awstephan/freedar/issues/11).

## Implementation

- Radar state exposes a request-owned frame preparation identity independently of
  aggregate loading. Native readiness ends preparation before the presentation
  deadline; the previous image and frame time remain until presentation completes.
- Selection, Latest, and playback participate in the existing shared 200 ms
  indicator. Replacement selections release the old contribution and start a new
  delay. Readiness callbacks from superseded requests cannot change current state.
- Cancellation, failure, native timeout, backgrounding, and cooldown release the
  contribution. Cooldown retains the retry countdown and retries with a fresh delay.
- Routine refresh with displayed imagery and newest-frame background preparation
  remain quiet. Basemap work can independently keep the single indicator visible.
- The existing presentation, panel suppression, themes, reduced motion, and
  non-live-region accessible description are reused.
- Candidate-source tile events restart preparation during a held presentation
  gate. Parsed candidate tiles end that contribution independently of unrelated
  basemap/coverage rendering; the existing presentation commit gate is preserved.

## Validation

The owner confirmed the LatestRadar/ImageryLoading public-state and Android
rendering seams. Red/green development covered slow selection, scheduling, routine
refresh exclusion, and the shared controller-to-indicator integration. Additional
focused tests cover stale completion, delay boundaries, concurrent basemap work,
cached playback, newest-frame hold, cooldown/retry, failures/timeouts, cancellation,
and backgrounding.

Production and instrumentation Kotlin compilation and focused JVM tests pass.
Android fixtures now cover delayed selection and playback with retained native
image pixels and frame labels, native readiness before scheduled presentation,
stale native completions, and cooldown termination.

Final automated run:

```sh
JAVA_HOME=/tmp/opencode/java ANDROID_HOME=/tmp/opencode/android-sdk \
  ./gradlew test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Passed: 52 radar JVM tests, 19 app JVM tests in each of debug and release,
debug lint, and both debug APK builds.

## Review

### Standards

No documented-standard violations. Two nonblocking naming/type observations were
addressed with a named startup identity and typed terminal-outcome test scenarios.

### Spec

Review identified delayed reporting of candidate tile loads during the presentation
gate and preparation coupled to unrelated basemap rendering. Candidate-source
tracking and focused native fixtures address both; follow-up review found no
further concrete implementation issue. Device follow-up below resolved the missing
runtime evidence and caught a native cache-probe tracking defect.

The initial review had 0 unresolved standards findings and 1 outstanding
spec-validation finding (device evidence unavailable), subsequently addressed below.

### Initial device limitation

`adb devices` returned no connected devices during this implementation. The new
instrumentation checks have been compiled but have not run on-device. Device
evidence for selection/playback, failure/countdown feedback, and actual warm-cache
indicator behavior is pending; no visual tuning was performed. Existing startup,
theme, reduced-motion, and touch-passthrough evidence is in `issue-12.md`.
The full connected-suite attempt with the isolated application ID built successfully
but failed at execution with `DeviceException: No connected devices!`.

When running on a personal device, use the isolated `.validation` application ID
described in `issue-12.md` to preserve the installed release and its data.

## Physical-device follow-up (2026-11-03)

Device: Pixel 9 Pro XL, Android 17. All runs used the isolated
`com.freedar.validation` application ID; the installed release was preserved.

The first run exposed a real readiness defect: MapLibre emits
`RequestedFromCache` for speculative fallback ancestors without a matching parse,
network request, or cancellation. Counting these probes left preparation pending
after usable candidate imagery was ready. A targeted tile-event trace reproduced
this in the basemap-independence fixture. Preparation now tracks actual network
requests and parsing, excluding speculative cache probes. The existing native
basemap-independence and held-gate-pan tests failed before the fix and pass after it.
Temporary diagnostic logging was removed.

The first startup UI checks were interrupted by the device locking, which stopped
the activity and removed its Compose hierarchy. After the owner unlocked the phone,
the USB stay-awake setting was temporarily enabled for validation.

All seven focused tests passed together:

- `FrameLoadingRenderingTest`: delayed selection and playback show the indicator,
  retain the preceding native image pixels and frame label, and dismiss on readiness.
- `PlaybackRenderingTest`: cold/warm reuse, ready candidate waiting quietly for its
  presentation deadline, new loading/readiness during a held gate after pan, retained
  image/time, and stale canceled completion.
- `RadarCooldownRenderingTest`: real native 429 handling, no preparation contribution
  during cooldown, retained image/time, and automatic recovery.
- Both `InitialBasemapRenderingTest` cases: independent basemap/radar readiness in
  either completion order.
- Both `InitialLoadingRenderingTest` cases: centered description without a live
  region, touch passthrough, panel suppression/reopening, lifecycle/failure behavior,
  and four stationary reduced-motion drops in both themes.

Focused run: **7 tests, 0 failures, 0 skipped**, 1m32s including build/install.
The XML result was retained locally at
`/tmp/opencode/issue-13-focused-device-results.xml`.

The full-suite run additionally exposed an order-dependent fixture assumption:
the preceding theme test leaves the camera at zoom 6, producing 17 radar tile
downloads. At the real 750 ms pacing, the new UI fixture's 10-second readiness
deadline expired while imagery was genuinely still loading. The failure reproduced
with only the theme test followed by `FrameLoadingRenderingTest`. The fixture now
uses a fixed local zoom-10 view and restores the previous saved camera afterward.
That two-test reproduction passes after the fixture correction; production pacing
and readiness deadlines were not changed.

Final full-suite result: **26 passed, 2 failed, 4 skipped** (32 XML cases).
All seven loading-related checks passed within that run. Remaining failures match
the already documented baseline in `issue-12.md`:

- `MapControlsRenderingTest.kt:36`: large-text corner-control pixel-height assertion.
- `OfflineRadarRenderingTest.kt:102`: obsolete expected text “Saved radar · original
  frame time, not live data”.

The four opt-in live-provider/search-benchmark cases were skipped. JVM tests and
debug lint passed. The full device suite is therefore **not entirely green**, but
has no remaining #13 failures. Final XML is retained locally at
`/tmp/opencode/issue-13-final-device-results.xml`.

```sh
JAVA_HOME=/tmp/opencode/java ANDROID_HOME=/tmp/opencode/android-sdk \
  ./gradlew --continue -I /tmp/opencode/freedar-validation.gradle \
  test :app:lintDebug :app:connectedDebugAndroidTest
```

Follow-up Standards and Spec reviews found no blocking issues. A minor test-storage
coupling observation follows existing fixture conventions. Temporary diagnostic
logging was removed, and the phone's original stay-awake setting (`0`) was restored.
The device-validation acceptance step for #13 is complete.

No visual tuning was needed for this state-tracking change. These are controlled
local-fixture checks on one physical device; they do not establish API-26 rendering
or live-provider performance. Startup animation visual evidence remains in
`issue-12.md`; ordinary current-viewport tracking continues in #14.
