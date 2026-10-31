# Issue #5 implementation validation

Scope: [Clean-map controls and accessible layout](https://github.com/awstephan/freedar/issues/5).
Review baseline: `c6823b1`. Session: 2026-10-31.

## Agreed seams and behavior

The maintainer approved controlled-time app-level controls tests, production Compose
UI with local radar fixtures, and native Android accessibility/layout/lifecycle
checks. A minimal Settings surface exposes the remembered **Keep controls visible**
preference. Locate/Search/Legend have ordered action slots for later integration.
The shared overlay hold is available for those future surfaces; Settings exercises
it now, including dismissal and recreation while open.

Controls appear on opening/foregrounding and map interaction. Five seconds of
inactivity hides Settings and the playback strip. Pointer-down holds visibility
through release/cancellation; keyboard interaction also holds it. Ending an
interaction or override starts a fresh five-second interval. Playback and radar
updates do not reset this timer. Timer checks stop with the activity lifecycle.

Touch exploration uses Android AccessibilityManager and its state-change listener,
with no Google/TalkBack dependency. It overrides hiding even when enabled while the
activity is already open. Settings remains open until explicitly dismissed, and
its preference survives activity recreation.

Frame time and age, active-notice summaries, Retry, coverage explanation and linked
attribution remain outside the hiding controls. Credits are anchored separately
from scrolling controls. At large text sizes, verbose provider diagnostics can
scroll while the frame and active-notice summary remain anchored. Portrait stacks
the overlay regions; landscape uses two columns. The map remains edge-to-edge
behind safe-drawing-inset controls.

The timeline exposes available-frame steps and a localized frame-time description
using Android's time format. Gap and unavailable-frame markers use the same
frame-index coordinates as the slider. Saved selection matching uses the frame's
tile template, avoiding timestamp precision differences after restoration.

## Focused verification and review

- Red/green JVM cycles covered the exact five-second boundary and reset, every
  visibility hold, overlapping holds, and a fresh interval after release.
- Native UI cycles covered timeout, map pointer input held beyond five seconds,
  persistent frame/status/credits, Settings lifetime and saved preference.
- Local tile fixtures exercised real map camera, selection and playback retention
  through portrait/landscape changes, plus Play/scrub/Latest at 180% font scale.
  Tests assert the activity's effective font scale, not only the requested setting.
- Layout checks exercised system insets, a 48dp Settings target, and access to
  controls in both orientations. Post-timeout checks assert displayed frame time,
  coverage explanation and credits with populated radar at large text.
- Platform accessibility checks requested touch exploration through UiAutomation,
  inspected exported node order (status action before Settings), and performed
  native accessibility focus and click actions on Settings and Done. Disabling
  exploration restored timeout; enabling it again revealed controls.
- Existing focused freshness/lifecycle tests passed after integration.

Independent Standards and Spec reviews compared the working tree to `c6823b1`.
Standards suggestions led to shared device-check helpers and removal of unused
overlay states. Spec findings led to anchored credits/frame/notice summaries,
aligned timeline markers, and stronger populated large-text/accessibility checks.
Re-review found no remaining actionable standards or product-spec findings.

Early native accessibility attempts encountered an emulator System UI ANR dialog
instead of the app's active accessibility window. Restarting the emulator was
insufficient until that system dialog was dismissed; one attempted installation
also ran before boot completed. These attempts failed, and were not counted as
passing evidence. Subsequent native focus/click and final-suite checks passed.

## Final check

The single final full-suite command passed:

```sh
set -a && source .env && set +a
JAVA_HOME=/tmp/opencode/jdk-17.0.21+9 \
ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_SERIAL=emulator-5554 \
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest
```

- **32 distinct JVM tests:** 30 unchanged radar tests (Gradle up-to-date results),
  plus two controls tests passing for both debug and release variants.
- **12 deterministic Android tests passed**, zero failures; the opt-in live CARTO
  test was skipped. The XML report records 13 total tests and one skip.
- Debug/release Kotlin compilation and both APK builds passed.
- Lint: zero errors, ten warnings—nine existing dependency/manifest notices and
  one stylistic suggestion to use the SharedPreferences KTX edit extension.
- `git diff --check` passed.

The ignored `.env` was exported for Android builds; credentials are not committed.
All device operations explicitly selected the Google-free API-26 `emulator-5554`;
the connected physical device was not used.

## Evidence limits

Accessibility coverage verifies the exported tree and Android accessibility actions,
not spoken output or gesture traversal in a particular screen-reader application.
It does not establish physical-device ergonomics, all font scales/window sizes,
or newer-Android inset behavior. Local fixtures do not establish live provider
availability; earlier live CARTO/GrapheneOS evidence remains in `issue-3.md`.
