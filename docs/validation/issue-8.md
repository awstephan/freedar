# Issue #8 implementation validation

Scope: [Radar legend and appearance settings](https://github.com/awstephan/freedar/issues/8).
Review baseline: `53b6628`. Session: 2026-11-01.

## Agreed seams

The maintainer approved controlled-clock playback, production Settings-to-native-map
effects/persistence, Legend/overlay integration, and native readability/accessibility
checks before coding. TDD red/green cycles covered missing speed choices, missing
opacity UI, missing Legend and theme UI, and roads obscured by radar. Kotlin
compilation and focused tests ran between slices.

[radar-appearance.md](../radar-appearance.md) records primary RainViewer sources,
exact RGBA samples, interpretation limits, defaults and the reserved imagery-storage
boundary. Settings exposes information about unavailable downloads, not a fake Clear
action. Bundled GeoNames data remains separate.

## Behavioral and native evidence

- Controlled-clock playback verifies 1/2/4 fps, default 2 fps, changing speed while
  playing without restarting history, and the exact one-second newest-frame hold.
- Production UI changes opacity to 30%, observes the native raster's actual opacity,
  recreates, confirms persisted opacity/speed, resets native opacity to 65%, and
  starts playback with the persisted Fast selection. Incoming/recovery layers use
  the current value; a separate native snapshot check proves rendered pixels change
  at 0%, 65%, and 100%.
- Production theme selection verifies native background color and actual Compose
  screenshot pixels for Light/Dark, retained camera at 40 N / 100 W / zoom 6,
  retained historical selection, persisted radio choices and restored open Settings.
  System is selected by default. Changing Android night mode to yes/no while System
  is selected updates both native map and Compose controls; original mode is restored.
- At 180% text size, Legend survives timeout and portrait/landscape recreation.
  Rain and Snow rows remain reachable; screenshot pixels of the 35-dBZ swatches
  match the primary table. Frame time, coverage explanation and linked GeoNames
  credits remain displayed. Settings/About/version/project/source links are reachable.
- Refreshed native accessibility nodes support focus/click on Legend/Close legend,
  Search/Close search, Locate/Cancel and Settings/Done. Touch-exploration override,
  persistent controls, interaction holds and timeout behavior pass existing checks.
- Existing Search tests still verify actual IME visibility, rotation state, offline
  queries, selection cancelling Locate and returning to Latest. Real window-routed
  map pan/zoom still rejects late location callbacks.

## Heavy-radar readability

The opt-in native test loads **live keyed CARTO Positron and Dark Matter** at
40.73 N / 73.94 W, zoom 11, with a local opaque `#c10000` tile: RainViewer Universal
Blue's **50-dBZ rain** color. It verifies actual rendered named features, layer order
protecting roads/symbols, and changing native snapshot pixels at three opacity levels.
The final focused live check passed separately from the offline full suite.

Rendered snapshots were inspected at 65% in both themes, and 100% dark during the
initial comparison. Major roads, their casings and place/water labels are visible.
Dark Matter's unmodified road fills had weak contrast against red; brightening dark
transport fills while keeping their casings made major routes clearer. The final
dark 65% snapshot was inspected after that change. This is a stress sample, not a
claim about every color, zoom, place or physical screen. Live RainViewer weather was
not needed to fabricate or interpret the heavy fixture.

Repeat the live check with `LiveCartoTest` and `-Pandroid.testInstrumentationRunnerArguments.liveCarto=true`.
Snapshots are written into the app cache. To retrieve them, run instrumentation on
manually installed debug/test APKs: Gradle's connected-test cleanup uninstalls the
app and removes its cache. No screenshots containing credentials are committed.

## Full suite

```sh
set -a && source .env && set +a
JAVA_HOME=/tmp/opencode/jdk-17.0.21+9 \
ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_SERIAL=emulator-5554 \
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest
```

Passed in 12m47s:

- **37 distinct JVM tests**: 32 radar and five app tests (app debug/release variants).
- **24 deterministic Android tests**; XML records 26 cases, zero failures/errors,
  two opt-in live CARTO skips. The console's extra progress counts are not extra tests.
- Debug/release Kotlin compilation and APK builds.
- Lint: zero errors, 13 warnings (existing dependency/manifest notices and four
  SharedPreferences KTX suggestions), plus a primitive-state allocation suggestion.
- `git diff --check` passes.

All native operations selected **emulator-5554**, AOSP Android 8.0/API 26. Boot
completion was `1`; window inspection before accessibility checks showed no System
UI ANR. The connected physical device was not used. The ignored `.env` was exported
for builds and excluded from commits along with pre-existing design/agent files.

## Failures caught during development

The first preview implementation read state only in SideEffect, so the parent did
not recompose and the actual raster stayed at 65%. The production boundary test
caught this; opacity and speed are now read during parent composition before applying
effects. Overlay state follows the same rule, above orientation-dependent layouts.

Other failed attempts included an ambiguous duplicate coverage-text assertion and
MockWebServer URL construction triggering DNS on the UI thread; these were corrected.
A combined focused run exceeded the shell's ten-minute limit after 13 checks had
completed; its remaining live check passed separately. The final full suite used a
longer timeout and completed normally. Failed/interrupted attempts are not presented
as full passing runs.

## Evidence limits

Accessibility checks establish exported node properties/actions, not spoken output.
Native effects run on API 26; newer Android and physical-device ergonomics were not
retested. Theme switching deliberately uses activity recreation and can briefly show
loading while restoring imagery; native state and camera restoration are checked.

## Review against 53b6628

Independent Standards and Spec subagents reviewed the uncommitted diff and new files
against the requested baseline. Standards found no documented breaches and one
low-priority duplication: CARTO transport-layer name matching appeared in both
contrast styling and layer ordering. Both now share one predicate. Spec reported no
actionable findings or scope creep. Kotlin compilation and the focused playback
suite were checked again after the semantics-preserving extraction.
