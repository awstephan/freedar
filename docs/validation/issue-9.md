# Issue #9 implementation validation

**Historical initial delivery:** this note records the work through `087a1aa`.
The subsequent maintainer permission confirmation and radar-persistence completion
are documented in [issue-9-radar.md](issue-9-radar.md), which supersedes the blocked
status recorded below.

Scope: [Remembered view and bounded offline imagery](https://github.com/awstephan/freedar/issues/9).
Review baseline: `620564b`. Session: 2026-11-01.

## Approved seams and scope

The maintainer approved remembered-view/lifecycle, controlled disk/clock storage,
provider/native storage boundaries, and production Settings/native lifecycle seams
before coding. Primary provider sources and implementation limits are recorded in
[downloaded-imagery.md](../downloaded-imagery.md).

**Partial delivery:** RainViewer persistence/preloading permission remains unresolved;
no inquiry has been sent. Offline persistent radar and its future cached-frame
acceptance criteria are blocked. This work must not close the whole issue.

## Focused evidence

- TDD first failed compilation for missing storage/network boundaries, then passed.
  Oversized writes exposed unwanted eviction; rejection now preserves useful entries.
  Reopen tests retain bytes without extending retention; maintenance deletes even
  unrequested expired files. Aggregate LRU, rollback, clear generation, and neighboring
  place-data preservation use controlled disk and clocks.
- HTTP fixtures prove disk reuse after client/store reconstruction with the server
  stopped, exclusion of radar, and provider no-store/variation restrictions. A live
  CARTO response revealed `Vary: Accept-Encoding`; its fixture failed until transparent
  decoding was supported. No live credentials or response bodies are committed.
- The production Settings/lifecycle test first failed on a fresh launch returning to
  the overview. It now restores 40 N / 100 W / zoom 6, resets historical radar to
  Following, and clears actual imagery while preserving camera preferences,
  appearance selection and a file beside the place-search database. An old-process
  marker in Android's saved Bundle exercises process-restored task behavior.
- Existing theme tests pass for native/Compose themes, camera, historical selection,
  and restored open Settings. Device checks wait for frame readiness and use
  `revealMapControls()`.
- The native disk test renders blue fixture pixels, reconstructs the store/client/map
  offline, renders them again, clears storage, and verifies another native renderer
  cannot recover the image. Its first attempt accidentally classified local CARTO
  PNG fixtures as radar, hitting the pre-existing memory cache. The fixture now
  mirrors production CARTO's exclusion from radar memory reuse; the corrected test passes.
- A separate seed/force-stop/verify check passed in two actual instrumentation
  processes on `emulator-5554`. The second process verified a different application
  session UUID, remembered 41 N / 101 W / zoom 7, and Following latest instead of the
  seed's historical selection. Both phases reported `OK (1 test)`. Run with manually
  installed debug/test APKs (Gradle connected-test cleanup removes app data):

  ```sh
  adb -s emulator-5554 shell am instrument -w \
    -e class com.freedar.ControlsPlaybackTest#rememberedViewAcrossRealProcessRestart \
    -e processCheck seed com.freedar.test/androidx.test.runner.AndroidJUnitRunner
  adb -s emulator-5554 shell am force-stop com.freedar
  adb -s emulator-5554 shell am instrument -w \
    -e class com.freedar.ControlsPlaybackTest#rememberedViewAcrossRealProcessRestart \
    -e processCheck verify com.freedar.test/androidx.test.runner.AndroidJUnitRunner
  ```

  The persisted cleanup job also accepted a forced run through
  `adb -s emulator-5554 shell cmd jobscheduler run -f com.freedar 9`.

All native runs explicitly select `emulator-5554` (AOSP API 26). Boot completion
was `1`; window inspection showed no System UI ANR. Builds export the ignored `.env`
and use the supplied JDK 17 / Android SDK paths.

## Final verification and review

Parallel Standards and Spec reviews ran against `620564b`, with focused follow-up
reviews confirming remediation. No unresolved actionable finding remains in the
permitted implementation; the RainViewer permission dependency remains open.

Initial parallel review findings were addressed: Standards identified stale README
claims and duplicated overview/eviction logic plus unclear content-type/job-ID names.
Spec identified the six-hour cleanup interval potentially exceeding day 30 and stale
copies surviving a newly restrictive provider response. Deletion now starts at day
29, and restrictions invalidate older copies. Regression tests failed for both
behaviors before those corrections.
The follow-up Spec review found mid-body transport failure skipped offline fallback;
a disconnecting HTTP fixture reproduced that failure and the interceptor now shares
fallback for eligible header/body transport failures while respecting cancellation.

The first full run failed two existing native controls tests. Logs showed application-
lifetime 429 budgeting bleeding across independent local servers; each controls
fixture now gets its own native request budget while keeping it across recreation.
The other failure minimized to Search followed by large-text playback: requesting
slider value zero while already at zero is a Compose no-op, not a scrub. An explicit
action-result assertion reproduced it. The test now chooses a distinct endpoint
within one UI action (waiting before scrolling still raced playback). The minimized
pair passes. These were test isolation/input issues; production budgets and playback
semantics are unchanged.

The second full run passed those tests but failed the native accessibility order
assertion. Awaiting the exported Retry node and dumping the fresh tree exposed a
System UI ANR dialog covering the app, not a Freedar ordering regression. The dialog
was dismissed on the emulator; window inspection then confirmed launcher focus and
no ANR. The focused accessibility check passed afterwards. Earlier failed runs are
not counted as passing full-suite evidence.

The final full command **passed in 13m54s**:

```sh
set -a && source .env && set +a
JAVA_HOME=/tmp/opencode/jdk-17.0.21+9 \
ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_SERIAL=emulator-5554 \
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest
```

- **45 distinct JVM tests**: 32 radar, 13 app (app debug/release variants), zero failures.
  Unchanged JVM tasks reused passing results from the initial full command.
- **26 deterministic Android tests passed**. XML records 29 cases, zero failures/errors,
  three opt-in skips: two live CARTO checks and the separately executed process check.
  Console progress counts above 29 are not additional tests.
- Debug/release Kotlin compilation and APK builds passed.
- Lint: **zero errors, 14 warnings** (existing categories plus the new remembered-view
  SharedPreferences KTX suggestion), and the existing primitive-state suggestion.
- `git diff --check` passed. No credential files are included.

## Evidence limits

Native imagery fixtures establish actual disk reuse and native-cache exclusion, not
worldwide offline coverage. Old task-Bundle behavior is controlled in-process; the
separate force-stop/relaunch check proves actual process independence but does not
simulate Android low-memory eviction restoring a task. Android job scheduling may
delay background deletion; expired files are never served after the store's expiry
check. RainViewer disk reuse and relabeling tests cannot substantiate a capability
that remains intentionally blocked. Existing freshness tests cover original frame
timestamps for in-session retention. No physical device was used.
