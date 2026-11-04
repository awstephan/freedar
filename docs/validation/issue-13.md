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
further concrete implementation issue. On-device acceptance remains pending.

Summary: 0 unresolved standards findings; 1 outstanding spec-validation finding
(device evidence unavailable).

### Device limitation

`adb devices` returned no connected devices during this implementation. The new
instrumentation checks have been compiled but have not run on-device. Device
evidence for selection/playback, failure/countdown feedback, and actual warm-cache
indicator behavior is pending; no visual tuning was performed. Existing startup,
theme, reduced-motion, and touch-passthrough evidence is in `issue-12.md`.
The full connected-suite attempt with the isolated application ID built successfully
but failed at execution with `DeviceException: No connected devices!`.

When running on a personal device, use the isolated `.validation` application ID
described in `issue-12.md` to preserve the installed release and its data.
