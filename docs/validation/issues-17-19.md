# Large-text controls and offline-radar validation (#17, #19)

Session: **2026-11-07**. Baseline: `8cf37b5`, following release `v0.1.1`.
Parent: [#1](https://github.com/awstephan/freedar/issues/1).
Follow-ups: [#17](https://github.com/awstephan/freedar/issues/17),
[#19](https://github.com/awstephan/freedar/issues/19).

## Environment and reproduction

Pixel 9 Pro XL, Android 17/API 37; JDK 17 and SDK 35. No Android device was
initially connected; the first connected-test attempt compiled successfully but
reported `No connected devices`. The owner then connected the phone.

All installations used **`com.freedar.validation`**, including the first attempt.
The release application and its data were not uninstalled or overwritten. The
external Gradle init script contains:

```groovy
allprojects {
    plugins.withId('com.android.application') {
        android.buildTypes.debug.applicationIdSuffix = '.validation'
    }
}
```

The original focused invocation, repeated with only the two failing methods,
reproduced both failures:

```sh
JAVA_HOME=/tmp/opencode/java ANDROID_HOME=/tmp/opencode/android-sdk \
  ANDROID_SERIAL=<selected-device> ./gradlew \
  -I /tmp/opencode/freedar-validation.gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.freedar.MapControlsRenderingTest,com.freedar.OfflineRadarRenderingTest
```

- Controls: `MapControlsRenderingTest.kt:36`, the 48 dp height comparison.
- Offline: a 30-second timeout awaiting the restored frame at the old line 100,
  **before** the obsolete status-text assertion. This reproduces the earlier
  baseline timeout in [#12's record](issue-12.md), not just the obsolete wording.

## #17: pixel rounding, not an undersized control

A diagnostic assertion measured the Attributions target in landscape:
`Rect.fromLTRB(169, 81, 291, 203)`, **122 × 122 pixels**, density **2.55**.
The previous assertion required `48 * 2.55 = 122.399994` pixels. Compose rounds
layout dimensions to integer pixels, so a correctly rendered 48 dp control failed.

The test now compares against `48.dp.roundToPx()` using the Compose density and
checks **both width and height**, clickable semantics and all four system-inset
bounds. This is the platform's dimensional rounding, not an arbitrary tolerance or
a reduced target requirement. The production `MapIconButton` is unchanged.

At 180% text, both landscape and portrait pass for Attributions, Info, Locate,
Search and Settings. The panel interactions additionally use real touch input
near the left/right midpoints of the target, outside the 28 dp visual background.
An exploratory tap at the extreme square corner was outside Material IconButton's
circular hit shape; midpoint edge taps test its invisible 48 dp target without
requiring a different button shape. Search keyboard and direct panel-switch/toggle
checks remain in the same scenario. The separate accessibility focus/click test
also passes. These checks do not constitute spoken screen-reader validation (#18).

## #19: seed completion and current status semantics

Temporary fixture-only HTTP tracing showed that three tiles required by the
restored viewport were absent from storage, despite successful earlier rendering
of the center pixel. The seed moved from its initial view to zoom 6, refreshed,
then waited for the **aggregate radar frame-loading text** to disappear. That
signal does not represent ordinary current-viewport tile downloads. Closing the
activity canceled the remaining downloads. On offline restart, missing tiles were
correctly unavailable; a partial seed was being treated as complete.

A trial wait on an unqualified native `fully rendered` callback also accepted a
render from before the new viewport completed. It was discarded. The final test
uses the current-view loading UI delivered by #11: close Info, move the camera,
observe loading appear, then wait for it to finish before closing the activity.
The initial fixture camera is explicitly zoom 10 at 40 N / 100 W, so a preceding
test cannot leave it already at the zoom-6 destination and bypass the loading
transition. No public provider is involved; the existing paced local fixture is
retained. Production cache, renderer and request pacing were not changed.

After this change the restored frame/time assertion passed and the test reached
the obsolete saved-radar text. The assertion now requires both **Saved radar** and
**retained imagery is not live**, allowing the independently reported Offline
prefix. Stale-feed, original time/pixels, latest-following, camera, missing-region,
Clear and real bundled-search assertions remain intact. Diagnostic tracing was
removed.

## Focused and separate-process results

The original focused command now passes **3 tests, 0 failures, 0 skips**.

The debug and instrumentation APKs were then installed manually with `adb install
-r` into the isolated package, because Gradle's connected-test cleanup removes its
test installation/data. Separate instrumentation processes exercise actual restart:

```sh
adb -s <selected-device> shell am instrument -w \
  -e class com.freedar.OfflineRadarRenderingTest -e radarProcessCheck seed \
  com.freedar.validation.test/androidx.test.runner.AndroidJUnitRunner
adb -s <selected-device> shell am force-stop com.freedar.validation
adb -s <selected-device> shell am instrument -w \
  -e class com.freedar.OfflineRadarRenderingTest -e radarProcessCheck verify \
  com.freedar.validation.test/androidx.test.runner.AndroidJUnitRunner
```

- Seed: **OK (1 test)**, 14.594 seconds.
- Verify: **OK (1 test)**, 3.584 seconds; checks a different process UUID,
  identical original frame label/blue radar pixels, remembered 40 N / 100 W / zoom
  6, latest-following, saved/stale notices, missing-region unavailability, Fast
  preference and real Tokyo search surviving Clear, and no post-Clear restoration.

## Integrated checks and limits

The full run passed in **4m49s**:

```sh
JAVA_HOME=/tmp/opencode/java ANDROID_HOME=/tmp/opencode/android-sdk \
  ANDROID_SERIAL=<selected-device> ./gradlew --continue \
  -I /tmp/opencode/freedar-validation.gradle test :app:lintDebug :app:connectedDebugAndroidTest
```

- Device XML: **34 passed, 0 failures/errors, 4 skipped** (38 cases). Both repaired
  scenarios pass within the full suite, alongside every loading-related test.
  The four explicit opt-ins are the static-footprint survey, two live CARTO checks
  and the search timing benchmark. Gradle's progress counter includes extra skip
  reporting; XML totals are authoritative.
- JVM: **91 passing case results** (53 radar results reused as up-to-date; 19 app
  debug and 19 app release cases executed). This is 72 distinct cases across the
  two modules, with the app cases run in both variants.
- Debug lint: **0 errors, 14 warnings, 1 informational finding**. Production and
  instrumentation compilation/builds passed; no signed release was rebuilt.
- `git diff --check` passed; temporary diagnostic instrumentation was removed.
- USB stay-awake was restored to its original value **0** after the full suite.
  The installed release and its data remained separate from the validation app.

Local diagnostic logs are `/tmp/opencode/freedar-followup-{repro,diagnostic,viewport,focused,full}.log`;
separate-process logs are `/tmp/opencode/freedar-offline-{seed,verify}.log`.
These local artifacts are not published evidence URLs; the observed outcomes and
reproduction commands are captured here and in the issue completion comments.

No API-26/older physical-hardware run, audible screen-reader session, live-provider
performance check or attribution-permission resolution is claimed here. Those
remain #16, #18 and #15 respectively; #1 remains open.
