# Issue #4 implementation validation

Scope: [Honest freshness and lifecycle behavior](https://github.com/awstephan/freedar/issues/4).
Review baseline: `8ec6cea`. Session: 2026-10-31.

## Agreed behavior and seams

The maintainer approved controlled app/provider/display/time/connectivity/lifecycle
tests, local HTTP fixtures, and native Android integration. Short interruptions
(less than five minutes) retain the selection and automatically resume previously
active playback. At five minutes or longer, return to newest paused/following
without moving the map. This decision is also recorded on #4.

Freshness uses newest frame time, never manifest generation/download time. The stale
threshold is strictly greater than 20 minutes. Displayed-frame time/age remains
separate from newest-feed time/age. Offline, refresh failure, image failure, loading,
and stale notices coexist; successful historical presentation cannot clear a
manifest failure. Explicit Retry rechecks selected viewport imagery.

## Coverage presentation

The [provider's documented mask](https://www.rainviewer.com/api/weather-maps-api.html)
uses black outside the footprint and transparency inside it, and is infrequently
updated. Black pixels are shown as 22% shading. Persistent text says that inside
the footprint live availability is unknown and blank radar does not mean clear
weather. Loading/failure notices explicitly identify unknown footprint areas.

Coverage fetches run independently of radar rendering, sequentially, through the
shared HTTP client, 90-request budget, backoff and bounded memory byte cache. Only
decoded bitmaps enter native image sources; a stalled coverage HTTP request cannot
hold radar's fully-rendered presentation gate. Work is limited to 16 viewport tiles
at z0–7, obsolete images are removed on camera changes, and requests cancel on stop.
No persistent/offline-completeness claim is made.

## Focused checks

TDD cycles exercised status retention, explicit selected-viewport Retry, and the
foreground/background interface. Focused JVM checks include 20-minute and
five-minute boundaries, fresh manifest generation with old frames, delayed retries,
offline/stale/loading/failure coexistence, selection retention, and saved absence.
Existing playback race, delayed presentation, HTTP cancellation, empty/unusable
manifest, budget and backoff tests remain part of the final suite.

Native checks use local fixtures and explicitly selected `emulator-5554`, the
Google-free AOSP API-26 emulator. They exercise actual shaded/transparent pixels,
coverage failure/retry, healthy radar with stalled coverage, real activity
stop/return/recreation, short-return playback resumption, five-minute reset,
save-before-stop restoration, camera preservation, and delayed refresh cancellation.

JDK: `/tmp/opencode/jdk-17.0.21+9`; SDK: `/tmp/opencode/android-sdk`.
The ignored `.env` was exported before APK builds; credentials are not committed.
Two initial compile-only Android checks ran without the environment key; subsequent
builds and device checks used the exported key. One focused native command timed
out at the shell's 120-second limit after reporting its test passed; later focused
runs used a longer command timeout and completed successfully.

## Review

Independent Standards and Spec reviews examined the working tree against `8ec6cea`.

- Standards: no documented-standard violations. Replaced a UI-string-driven coverage
  state machine with typed status; extracted the repeated native coverage test
  harness and ensured teardown runs after failed assertions.
- Spec: isolated coverage loading from radar readiness; saved absence time even
  before stop on Android 8; strengthened native coverage/lifecycle checks; added
  explicit selected-viewport retry so renderer-only tile errors can recover.

Final targeted re-reviews found no remaining actionable Standards or Spec finding.

## Final checks

The final full check passed: **30 JVM tests, 7 deterministic Android tests**, with
zero failures. The eighth Android test is the opt-in live CARTO check and was
intentionally skipped. Debug/release Kotlin compilation, lint, and both APK builds
passed. Lint reported zero errors and nine warnings (dependency-version notices,
the existing backup-rule notice, and the existing missing-icon notice).

```sh
set -a && source .env && set +a
JAVA_HOME=/tmp/opencode/jdk-17.0.21+9 \
ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_SERIAL=emulator-5554 \
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest
```

This was the single final full-suite run, following focused checks and review
remediation. `git diff --check` also passed. Live compatibility was not rerun in
this session; the earlier keyed live CARTO evidence remains in `issue-3.md`.

## Evidence limits

Deterministic native checks use local masks/tiles; they do not establish live radar
completeness, worldwide provider coverage accuracy, physical-device smoothness or
API-29+ lifecycle behavior. The maintainer's GrapheneOS confirmation belongs to #3
and is recorded separately in `issue-3.md`. Live compatibility checks remain opt-in
and separate from deterministic checks. No physical device was used for #4.
