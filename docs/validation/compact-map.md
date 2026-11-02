# Compact map UI validation — 2026-11-01

Implements the owner's QuickWeather-inspired layout interview recorded in DESIGN.md.

## Workflow and retention decision

The session was **grilling with docs**: clarify the design, capture settled decisions,
and wait for explicit confirmation before implementation. The agent incorrectly
treated the owner's design answers and additional startup-location requirement as
authorization to code, then changed application code, tests and documentation,
built APKs and installed the debug build on the emulator.

After the owner identified that workflow error, the agent acknowledged it and offered
to revert the implementation or leave it paused. The owner explicitly instructed:
“Keep the new changes. Update the docs to reflect.”

The implementation and test changes are therefore retained by the owner's subsequent
decision. This does not imply that implementation was authorized when it began, or
that the owner has personally validated the resulting APK. The retained behavior is
recorded in [DESIGN.md](../../DESIGN.md) and [radar appearance](../radar-appearance.md).
Further design-interview answers should not be treated as permission to implement;
obtain explicit authorization before starting additional implementation work.

## Verified

- JDK 17, Android SDK 35: unit tests (`test`), `:app:lintDebug`, debug APK and
  unsigned release APK builds succeeded. The ignored developer `.env` supplies
  the existing CARTO key; no key was copied into source or this record.
- Native API-26 emulator checks cover persistent corner controls, 1.8× text in
  both orientations, accessible warning status/activation, switching panels,
  historical timestamp visibility, playback through rotation, first-open contiguous-US
  framing, permission-gated location on subsequent cold launch, no location retry on
  recreation, actual pan/double-tap cancellation, late fixes, search, settings,
  theme, offline radar and downloaded-data clearing.
- Full native suite initially found one obsolete assertion expecting Alaska/Hawaii
  inside the startup overview. Updated it to contiguous-US corners; the focused
  native rendering/overzoom test then passed. The other fixture tests passed in
  the full run. Opt-in live footprint survey was not repeated.
- Both opt-in live CARTO tests passed: current map labels/glyphs, roads and labels
  above strong radar echoes, opacity affecting rendered pixels, and charcoal land
  `#333333` / water `#222222` applied to actual hosted style layers.
- Manual emulator visual check of the real app: compact corner placement, thin
  slider, moving time tag and brighter dark map. The check caught dark-mode time-tag
  text inheriting a black default and system bars following the system rather than
  the app's theme override; both were corrected.

## Review

Standards and spec reviews identified slider resizing during scrub, a location/latest
intent lost while style loading, competing panel-open flags, stale appearance docs,
and weakened gesture/playback-rotation checks. Fixed each; Latest reserves its slot,
location intent survives initialization/recreation, and persistent icons switch panels.

## Attribution decision

Links are inside Info as explicitly requested. CARTO currently requires linked
credits visible without a click; this implementation does not establish compliance.
See [the primary-source finding](../../VALIDATION.md#carto). No provider exception
has been obtained or implied.

## Artifact

Installable testing APK: `app/build/outputs/apk/debug/app-debug.apk`.
Release output is unsigned. The owner subsequently authorized committing and pushing
the retained source, tests and documentation; APK publication was not requested.
