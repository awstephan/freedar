# Issue #9: two-hour offline radar follow-up

Baseline: `087a1aa`. Session: 2026-11-01. Continues the original four approved seams.

## Permission decision

The maintainer confirmed caching/preloading/distribution approval with no additional
restrictions beyond RainViewer's published site terms. This was recorded on #9
before coding. No provider correspondence or its original approval date was supplied;
the claim is attributed to the maintainer. Public/free upstream sources alone are
not treated as a grant. This supersedes the earlier unresolved-permission blocker.
The maintainer selected two hours of radar retention; the implementation measures
that from **original frame time**, not download/access time. See
[downloaded-imagery.md](../downloaded-imagery.md) for the current contract and sources.

## Implementation and focused evidence

- Shared 100 MB storage now has per-entry absolute deadlines and reads the prior
  CARTO format without granting a new retention lease. Tests verify a radar frame
  already 110 minutes old expires ten minutes after receipt while base-map data
  remains, actual unrequested-file deletion, and old-format upgrade preservation.
- Persisted individual frame descriptors preserve opaque provider templates and
  original times. They and radar pixels share the existing aggregate budget. Imagery
  is evicted in LRU order before descriptor index metadata. Exact template
  matching, individual expiry, reopening, clearing, and in-flight manifest invalidation
  are tested through controlled provider/clock/disk interfaces.
- The HTTP test proves a reopened client/store reuses radar pixels before making a
  second request, and cannot reuse them at the two-hour deadline. Existing restricted
  response, cancellation/transport-fallback and provider-boundary tests remain.
- Offline startup tries saved frames newest-first. The controlled display rejects
  the newest viewport, then accepts the preceding image: displayed age remains 35
  minutes, newest-feed age remains 25 minutes, and saved-history, offline, stale and
  refresh-failure state coexist. Recovery clears saved-history/failure state. Expired
  catalogs cannot label an image that was never rendered.
- A production Activity/native renderer test downloads blue tiles, closes the
  activity, stops the server, rebuilds the provider/archive/client, and verifies
  actual blue pixels with the identical displayed timestamp and saved/stale notices.
  Panning to an undownloaded Japanese viewport reports unavailable tiles. Settings
  Clear removes disk state; another offline launch has no frame and reports unavailable.
  Its initial attempt closed before viewport replacement completed; waiting for the
  persistent loading state to finish fixed the fixture, without changing renderer code.
- The same test passed across **actual process restart**: seed and verify phases in
  distinct instrumentation processes separated by `adb force-stop com.freedar`.
  Verify checks a different process UUID, the original timestamp/pixels, missing-region
  status and Clear. Both commands reported `OK (1 test)`:

  ```sh
  adb -s emulator-5554 shell am instrument -w -e class com.freedar.OfflineRadarRenderingTest \
    -e radarProcessCheck seed com.freedar.test/androidx.test.runner.AndroidJUnitRunner
  adb -s emulator-5554 shell am force-stop com.freedar
  adb -s emulator-5554 shell am instrument -w -e class com.freedar.OfflineRadarRenderingTest \
    -e radarProcessCheck verify com.freedar.test/androidx.test.runner.AndroidJUnitRunner
  ```

  Install debug/test APKs manually first; Gradle connected-test cleanup removes app
  data. With neither argument, the same test runs both activity phases in the full suite.

TDD first failed for the missing deadline, archive, interceptor and offline-provider
interfaces. Focused JVM tests and Kotlin compilation ran between implementation slices.
Native checks explicitly select Google-free AOSP API-26 `emulator-5554`; boot was `1`
and window inspection showed no System UI ANR. The ignored `.env` was exported for
Android builds; credentials are not committed.

## Review and full verification

Parallel Standards and Spec reviews examined the working tree/new files against
`087a1aa`. Standards found no hard violations and three interface-clarity/coupling
suggestions: saved-only mode and tile authorization now have explicit names, and the
archive owns its clear-generation capture. Spec found descriptor eviction could leave
retained pixels undiscoverable after restart. A capacity-pressure/reopen test failed
before correction; eviction now removes LRU imagery before index metadata, with all
bytes still counted toward the hard cap. Focused re-reviews confirmed these fixes.

The final full suite **passed in 14m14s**:

```sh
set -a && source .env && set +a
JAVA_HOME=/tmp/opencode/jdk-17.0.21+9 \
ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_SERIAL=emulator-5554 \
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest
```

- **52 distinct JVM tests:** 32 radar and 20 app tests (app debug/release variants),
  zero failures. The seven added cases cover expiry/upgrade, HTTP radar reuse,
  descriptor fidelity/capacity, offline behavior and manifest-clear invalidation.
- **27 deterministic Android tests passed.** XML records 30 cases, zero failures/errors,
  and three opt-in skips (two live CARTO checks and the previously separate camera
  process-restart check). The new offline-radar test runs normally in the suite and
  also passed its separate seed/force-stop/verify phases above.
- Debug/release Kotlin compilation and APK builds passed.
- Lint: **zero errors, 14 warnings**, the existing categories plus the remembered-view
  KTX suggestion already present in the baseline; existing primitive-state advice remains.
- `git diff --check` passed. No credentials or `.env` changes are included.

The native test uses controlled provider and HTTP boundaries; production composition
of `RainViewer`/`RetainedRadarProvider`/the shared client was inspected in review.

## Limits

Offline availability is only for retained, successfully downloaded viewport tiles,
not complete geographic coverage or guaranteed full two-hour playback. No bulk
prefetcher was added. Static coverage stays memory-only and never establishes live
completeness. Android may delay background cleanup; expired bytes are rejected and
deleted on access/startup, with active and best-effort background sweeps. A powered-off
or force-stopped app cannot physically delete files at an exact wall-clock instant.
Current rendered textures can outlive disk deletion while retaining their actual time.
Native fixtures are not a new live-service or physical-device coverage claim. Integrated
release validation and source/public-release decisions remain in #10.
