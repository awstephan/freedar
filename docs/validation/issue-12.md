# Initial imagery loading feedback (#12)

Parent spec: [#11](https://github.com/awstephan/freedar/issues/11).

## Implementation

- One operation-owned indicator appears after 200 ms of continuous qualifying work
  and disappears immediately after its last contribution ends. A repeated/stale
  completion cannot release another operation.
- Basemap startup includes style loading and native source parsing/rendering, with
  tile failures and a 30-second readiness timeout. Its source-filtered observer is
  armed before style loading; radar can start as soon as the style is ready, and
  cannot hold the basemap contribution open. Native recovery clears initial errors.
- Radar exposes first-image preparation separately from its aggregate busy flag.
  Initial metadata and imagery count; cooldown, cancellation, terminal errors and
  refreshes with a displayed image do not. Existing Info feedback is retained.
- Four 5.5 dp teardrops occupy a centered 48 dp square, travel 24 dp over 900 ms,
  and use staggered phases with endpoint fades. White/dark and black/light are
  capped at 60% opacity. There are no input handlers, background or visible label.
- Panels suppress presentation without resetting elapsed loading time. Backgrounding
  removes the composable and stops its animation. Disabled system animations select
  stationary staggered drops; changes to the system setting are observed.
- The accessible description is “Loading map imagery”, without a live region.

## Checks on 2026-11-03

Confirmed test seams with the owner: radar controller, shared indicator state, and
Android UI/native renderer. The new controller and delay tests were developed with
red/green checks. Focused JVM tests and production/instrumentation Kotlin compilation
pass.

Final automated run:

```sh
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Passed: 45 radar JVM tests and 19 app JVM tests (the app tests run for both debug
and release), debug lint, and both debug APK builds. Following a cleanup-method
rename, the connected-test attempt rebuilt production and instrumentation Kotlin
and APKs successfully, then failed because no device was connected.

## Review

### Standards

No documented-standard violations. One cleanup naming observation was addressed
by introducing explicit renderer `dispose()` rather than hiding basemap teardown
inside `stopCoverage()`.

### Spec

Review caught basemap readiness blocking radar startup and permanently retained
basemap failure feedback. Both were corrected: independent source-filtered loading,
and observer-owned errors cleared on recovery. Follow-up review found no further
concrete implementation violations. Device validation remains outstanding.

Instrumentation fixtures cover delayed basemap tile readiness independently of
concurrent radar loading, centered accessible
startup feedback, touch passthrough, all four panels, foreground return, failure,
and static reduced-motion drops in both themes. These tests **compile but have not
run successfully in this session**.

## Device validation limitation

No connected Android device is available. The API-26 Google-free emulator cannot
start with acceleration because `/dev/kvm` is absent; a software-only attempt
segfaulted before boot. The focused connected-test command failed with “device
offline”; the final full connected-test attempt failed with “No connected devices”.
No screenshots, visual tuning, TalkBack listening check or on-device
motion/contrast result is claimed. Run `InitialLoadingRenderingTest` and
`InitialBasemapRenderingTest` on a working device, then visually inspect startup
over real map content in both themes with animations enabled/disabled.

Frame-selection loading and current-view pan/zoom ownership are the subsequent
implementation tickets #13 and #14 under the same parent specification.
