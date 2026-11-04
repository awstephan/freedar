# Current-view imagery loading (#14)

Parent: [#11](https://github.com/awstephan/freedar/issues/11).

## Implementation

- One native observer tracks basemap and radar sources through startup, navigation,
  frame preparation, and presentation. Only tiles intersecting the current north-up
  viewport at the source's current tile zoom contribute. Fallback cache probes,
  coverage, markers, and background revalidation of usable tiles are excluded.
- Basemap and displayed-radar work feed the existing shared 200 ms indicator alongside
  request-owned frame preparation. Source completion releases only that contribution.
  Returning to a ready view does not wait for abandoned requests.
- Native tile reconstruction invalidates remembered readiness. MapLibre 11.13.5's
  `TileLoader` emits `RequestedFromCache` when constructing a new tile, whereas network
  revalidation of the existing tile does not repeat that construction event. A cache
  probe itself is not counted as pending work.
  Source contract: [MapLibre TileLoader 11.13.5](https://github.com/maplibre/maplibre-native/blob/android-v11.13.5/src/mbgl/tile/tile_loader_impl.hpp).
- Candidate readiness is source-specific. Navigation during the final presentation
  phase puts the candidate back behind the basemap and restores the preceding radar
  until the replacement is ready for the new view. Frame-time commit remains owned
  by the existing presentation contract.
- Failure, cooldown, cancellation, and 30-second timeout stop their own contribution.
  Deadlines belong to tile requests, so small camera movements cannot reset the timeout
  or revive timed-out work. Foreground collection and native deadline scheduling stop
  in the background. Existing failure and cooldown feedback is retained.

## Validation environment

Pixel 9 Pro XL, Android 17. A temporary Gradle init script assigns the debug build
`com.freedar.loadingtest`, preserving the installed release and its data:

```groovy
gradle.projectsEvaluated {
    rootProject.project(':app').android.buildTypes.debug.applicationIdSuffix = '.loadingtest'
}
```

The available API-26 emulator could not start because `/dev/kvm` is unavailable.
Initial physical-device attempts were interrupted by the lockscreen. After unlocking,
USB stay-awake was temporarily enabled for the suite; its original setting was `0`.

## Checks

The owner confirmed the `ImageryLoading` public interface and native Android map/UI
integration seams. Focused checks exercise concurrent source loading, stale completion,
rapid pan/zoom replacement, returning to cached imagery with abandoned requests pending,
frame changes while navigating, native tile eviction, failure, cooldown, and timeout
during continued small camera movements. Existing checks cover selection/playback
pixels and frame labels, startup, panel suppression, touch passthrough, both themes,
and disabled system animations.

Device-driven corrections included handling terminal tile events immediately (a
failed request need not produce another rendered frame), retaining timeout ownership
through small camera movements, and rejecting stale render acknowledgments after
camera movement at the presentation boundary. The deterministic boundary test failed
before the readiness correction and passed afterward. The timeout fixture extends
its HTTP timeout so it actually reaches the native observer's 30-second deadline;
cooldown uses the application's shared limiter so the observed countdown is genuine.

Final full run (2026-11-03):

```sh
JAVA_HOME=/tmp/opencode/java ANDROID_HOME=/tmp/opencode/android-sdk \
  ./gradlew --continue -I /tmp/opencode/freedar-fixture.gradle \
  test :app:lintDebug :app:connectedDebugAndroidTest
```

- **53 radar JVM tests, 19 app debug JVM tests, and 19 app release JVM tests passed.**
- Production/instrumentation Kotlin compilation, APK builds, and debug lint passed.
- Device XML: **32 passed, 2 failed, 4 skipped** (38 cases). Every loading-related
  check passed, including all six new viewport scenarios and the strengthened
  playback presentation-boundary scenario. The Gradle progress counter additionally
  counts assumption skips; the XML case totals above are authoritative.
- The two failures match the documented pre-#14 baseline in `issue-12.md` and
  `issue-13.md`: `MapControlsRenderingTest.kt:36` (large-text control-height assertion)
  and `OfflineRadarRenderingTest.kt:102` (obsolete expected status text). The full
  device suite is therefore not entirely green. Four opt-in live-provider/search
  benchmark cases were skipped.
- Full output: `/tmp/opencode/issue-14-full-suite.log`; retained device XML:
  `/tmp/opencode/issue-14-final-device-results.xml`.
- The original USB stay-awake setting (`0`) was restored after validation. The
  isolated test package preserved the installed release and its data.

## Review

Standards review found no documented-rule violations and suggested simplifying the
shared test scenario. Scenarios now have explicit names and cannot form unsupported
flag combinations; fixture networking and cleanup remain shared.

Spec review identified native-cache eviction, camera-reset timeout deadlines, and
the final presentation-phase navigation race. These were corrected before final
validation. Follow-up review found no additional concrete code-level violation and
requested deterministic coverage of navigation after the presentation gate opens.
The playback fixture now moves at that exact render boundary and checks loading,
retained pixels, and eventual replacement. The scenario-body extraction suggestion
remains optional and nonblocking. Final spec follow-up found no remaining concrete
code issue or targeted-test gap: **0 unresolved spec findings; 1 optional standards
maintainability suggestion; 0 documented-standard violations**.

## Visual tuning and limitations

The 48 dp, four-drop, 60%-opacity presentation is unchanged. This change concerns
loading ownership; no visual tuning was needed. Controlled local fixtures exercise
the real renderer on one physical device. They do not establish API-26 behavior,
live-provider performance, or contrast across every real basemap/radar combination.
Prior appearance evidence is recorded in `issue-12.md` and `issue-13.md`.
