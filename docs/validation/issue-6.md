# Issue #6 implementation validation

Scope: [One-shot local positioning](https://github.com/awstephan/freedar/issues/6).
Review baseline: `a17c26c`. Session: 2026-10-31.

## Agreed seams and behavior

The maintainer approved controlled JVM coordination tests, production UI/native
map integration with local radar fixtures, and Android platform-provider checks.
The coordinator and initial UI integration were developed through red/green cycles;
Kotlin compilation and focused tests were run throughout implementation and review.

Locate requests foreground coarse/fine permission only after explicit activation.
The platform adapter uses enabled Android GPS/network providers, without Play
Services or background permission. Approximate permission can use the network
provider if available. No last-known fix is read; updates older than 30 seconds
are ignored, and a request times out after 30 seconds. Completion/cancellation
removes updates and the timeout callback.

Success centers once at **zoom 9**, then invokes the existing Latest app command:
newest paused/following. This shows a local region for nearby approaching
precipitation; ground span varies with latitude and viewport dimensions. It is
not a fixed-distance radius, and zoom adds no radar detail. There is no tracking.
Locate is enabled once the map style is ready.

Cancel replaces Locate while waiting. An independent controls hold keeps it
available without interfering with Settings, interaction holds, touch exploration,
or the persistent-controls preference. Location explanations share the scrolling
status diagnostics; frame time and the active-notice summary stay anchored.

Generation-based invalidation rejects late permission and location callbacks after
Cancel, pan/zoom, navigation, backgrounding, and replacement requests. Future search
selection calls `MainActivity.navigationStarted()` **before** changing the camera.
Background invalidation happens at activity stop; the foreground Android permission
dialog can pause the activity without abandoning its permission request. Requests
are not restored after recreation. Failure/denial never issues camera or Latest
commands. Existing radar foreground-return rules remain independent of positioning.

## Focused verification and review

- JVM checks cover no startup permission request, success exactly once, cleanup,
  explicit cancel/navigation/background, late permission and fix callbacks across
  replacement requests, denial, unavailable location, and failures.
- Production UI checks cover Cancel beyond the five-second timeout, historical
  selection preservation, exact success target/zoom and newest/following reset.
- Android window-routed touchscreen events pan and double-tap zoom the real map;
  assertions establish camera movement before stale callbacks are delivered.
  Search-style navigation and actual stop/return also reject stale callbacks.
  Denial/unavailable/failure preserve the camera and historical selection.
- At 180% text size in portrait and landscape, a denial explanation remains
  reachable while frame time, location notice, coverage guidance and credits remain
  displayed.
- Platform accessibility focus/click operates Locate and Cancel, followed by
  Settings/Done. Touch exploration still overrides timeout. The helper refreshes
  exported nodes and parents before inspecting changed labels/focus: API-26
  UiAutomation otherwise returned cached Locate text after Compose showed Cancel.
- `LocationManager` receives a fresh GPS test-provider fix with no further result
  delivery after success/cancellation on the Google-free AOSP API-26 emulator.
  The `removeUpdates` cleanup is confirmed by code inspection, not by inspecting
  platform listener registration in this test.

Independent Standards and Spec reviews examined the working tree and new files
against `a17c26c`. Standards reported no findings. Spec review requested moving
location explanations into scrollable diagnostics and proving window-routed gestures
actually move the camera. Both were fixed and the focused checks passed; re-review
reported no remaining actionable findings.

Initial focused attempts exposed test-harness issues: waiting for transient controls
before radar finished loading, Compose-injected gestures failing to reach the native
map, and stale UiAutomation label/focus properties. Final checks use persistent frame
readiness, Android window input, and refreshed native nodes. Failed attempts are not
counted as passing evidence.

## Final check

The final suite command passed:

```sh
set -a && source .env && set +a
JAVA_HOME=/tmp/opencode/jdk-17.0.21+9 \
ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_SERIAL=emulator-5554 \
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest
```

- **35 distinct JVM tests pass:** 30 radar tests and five app tests. App tests
  passed for debug and release; unchanged JVM results were up-to-date on the
  final repeat.
- **16 deterministic Android tests passed**, zero failures; the opt-in live CARTO
  check was skipped. The XML report records 17 tests and one skip.
- Debug/release Kotlin compilation and both APK builds passed.
- Lint: zero errors, ten existing warnings (dependency/manifest notices and the
  SharedPreferences KTX suggestion). `git diff --check` passed.

The first full-suite attempt found two large-text test failures. Test helpers now
wait for the replacement activity to resume and persistent frame readiness after
rotation. Crucially, a center-coordinate click on the full-screen map node could
hit an overlapping attribution link and leave the app. Control-reveal helpers now
invoke the explicit Show map controls semantics action; pan/zoom checks still use
real window-routed input. Focused large-text checks passed after remediation, then
the full command was repeated successfully. Final review found no test regressions
and clarified the platform cleanup evidence above.

All device operations explicitly select `emulator-5554`. Boot completion was `1`
and window inspection showed no System UI ANR dialog before native checks. The
connected physical device was not used. The ignored `.env` was exported for builds;
credentials and pre-existing untracked design/agent documents are excluded.

## Evidence limits

Platform fixes use a test provider, not outdoor satellite acquisition. Permission
outcomes are controlled at the app boundary; this does not validate every Android
permission-dialog variant. Native accessibility checks establish exported properties
and actions, not spoken output or automatic label-change announcements in a screen
reader. Checks do not establish physical-device ergonomics or API-29+ behavior.
Live CARTO/provider availability was not rerun; earlier evidence remains in #3.
