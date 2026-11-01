# Issue #10: Integrated Android release validation

Session: **2026-11-01**. Baseline: **`36db1fd`**, branch `master`.
Scope: [#10](https://github.com/awstephan/freedar/issues/10), parent [Spec #1](https://github.com/awstephan/freedar/issues/1).
This delivers a testing APK and integrated evidence, not public release authorization.

## Evidence reconciliation and agreed seams

Read Spec #1, #10, README, storage/search/appearance contracts and validation notes
#2–#9 before changing tests. Prior slice evidence remains applicable; it is not
silently relabelled as a new physical-device or live-provider run.
The maintainer approved these seams before coding:

1. Production Activity/native offline-radar seed/verify phases, adding actual bundled
   search queries and remembered settings across Clear and process restart.
2. Bundled search plus native camera navigation for representative states/international
   points; a separately opt-in, bounded live static-footprint survey.
3. Existing controlled-clock, HTTP and native behavior checks; regression-first fixes
   if an integration defect appeared.

The TDD skill was loaded. Added validation checks passed against the existing
implementation; no production defect or red/green production fix is claimed.
Kotlin compilation and focused Android files passed between checks. The completed
features were not rebuilt.

| #10 acceptance criterion | Evidence / disposition |
| --- | --- |
| Integrated compact actions, no placeholders/auto-hide races | `ControlsPlaybackTest`, `MapControlsRenderingTest`; production MapChrome/MainActivity inspection. Search, Locate/Cancel, Settings, Legend and Clear are active controls. Overlay holds are read in parent composition. |
| Search/Locate Latest and cancellation; pan/zoom retain time | Production Search selection with late Locate callback, Locate success/failure and real window-routed pan/double-tap checks; native playback timestamp/camera checks. See [#6](issue-6.md), [#7](issue-7.md). |
| Settings/view/Clear/offline/process/background integration | Extended `OfflineRadarRenderingTest` passes normally and in distinct seed/verify processes; actual search index queried before restart and after Clear, Fast remains selected, saved camera/time/pixels restore. Existing settings/theme and five-minute lifecycle tests pass. |
| API 26, Google-free, foreground behavior, layout/accessibility | AOSP API-26 emulator; resolved runtime graph and manifest audit below; portrait/landscape at 180% text, real IME visibility, refreshed native accessibility focus/click, touch-exploration override. |
| Real playback, URLs/counts, gaps/failures/time fidelity | Native local-fixture run below plus controlled-clock playback/gap/backoff tests. 429/503/delays are local, never induced on public services. |
| All-state and international coverage distinction | 55 native navigation samples and 19 live static-mask tiles; results below. No live-completeness assertion. |
| Legend/credits/key/search sizes/quotas/retention | Existing validated palette/linked credits and heavy-radar CARTO evidence in [#8](issue-8.md); current package measurements and audit below. |
| Reproducible installable testing APK | Signed debug APK, SHA-256, toolchain and installation below; no end-user keys. |
| Explicit permission/licensing/release decisions | Maintainer-confirmed RainViewer approval supersedes old blocker; source license, public publication and release signing remain explicit unresolved maintainer decisions. |
| Spec findings and independent routine tests | This matrix, Spec story map below, issue completion comments; live survey defaults to skipped. |

## Geographic navigation and provider footprint

`GeographicValidationTest` resolves **all 50 state capitals**, plus Utqiagvik,
Unalaska, Tokyo, London and Sydney through the packaged search interface. It centers
the real MapLibre camera at every result and verifies latitude/longitude and zoom 9.
No state restriction was found. This establishes navigation, not imagery availability.
The complete sample list is in the test; initial US extent/antimeridian checks remain
in `MapRenderingTest`.

The separate live survey on 2026-11-01 fetched the manifest (generated epoch
`1793564128`) and **19 unique z5 mask tiles**, sequentially, with a 1.1-second minimum
delay before each new tile. No retries, full-history download or rate-limit experiment.
It sampled one pixel at each place coordinate, using the provider's returned host:
`/v2/coverage/0/256/5/{x}/{y}/0/0_0.png`.

| Samples | Static-mask observation |
| --- | --- |
| All 50 capital samples, including Juneau and Honolulu | Transparent: inside static footprint; live availability unknown |
| Utqiagvik, northern Alaska | Black: outside static footprint, z5 2/6 |
| Unalaska, Aleutians | Black: outside static footprint, z5 1/10 |
| Tokyo, London, Sydney | Transparent: inside static footprint; live availability unknown |

A capital is a representative point, not the whole state. Mask resolution and its
infrequent updates limit this survey. Inside-footprint blank imagery cannot establish
clear weather, current radar operation or complete observations. Outside-footprint
places remain searchable/navigable. No live per-frame completeness survey was performed.

Reproduce separately from routine tests (after exporting the build environment):

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.freedar.GeographicValidationTest \
  -Pandroid.testInstrumentationRunnerArguments.liveCoverage=true
adb -s emulator-5554 logcat -d -s CoverageSurvey:I
```

## Native playback, process and storage evidence

The final native run recorded:

- Newest first: **2 requests**, z7 plus z3 fallback, at camera zoom 10.
- Cold two-frame playback: **4 requests total**; warm return: **0 additional**.
- Newly announced newest preparation: **2 additional**, while the historical image
  and its timestamp remain visible until presentation.
- Full pan/superseded-load scenario: **14 requests**. Initial z7 `28/48`, then
  `29/47` and `29/48`; z3 fallbacks; no requested zoom above 7. Actual paths include
  `/new/256/7/28/48/2/1_1.png` and `/old/256/3/1/3/2/1_1.png`.
- Snapshots verify red/blue pixels match published frame time. Delayed/failed
  replacement retains prior pixels/time; gaps/unavailable frames are covered at the
  controlled app seam. Native unavailable replacement retains blue center `-12566022`.
- 1/2/4-fps targets and the one-second newest hold are controlled-clock assertions,
  not measured physical-device frame-rate guarantees. Cold loading can slow playback.

Extended offline checks passed both in one instrumentation run and as separate
processes (`OK (1 test)` each) using the seed/force-stop/verify commands in
[#9 radar validation](issue-9-radar.md). The seed installs/queries real Reykjavík
search data and selects Fast. Verify proves a different process UUID, remembered
40 N / 100 W / zoom 6, Following latest, identical original radar timestamp/blue
pixels, saved/stale notices, missing Japanese-region status, and Settings Clear.
Fast survives Clear and a real Tokyo query still resolves from bundled data.
Existing tests separately cover historical startup reset, all appearance settings,
no-active-network search, refresh-failure/age separation and background return.

`cmd jobscheduler run -f com.freedar 9` accepted the persisted cleanup job. Inspection
confirms it only prunes storage: no location or radar requests. It is scheduled every
15 minutes; foreground cleanup runs every 30 seconds, and startup/access enforce
expiry. Android deferral, force-stop and power-off prevent exact wall-clock physical
deletion guarantees. See [storage contract](../downloaded-imagery.md).

## Dependency, service and platform audit

- Runtime: AOSP Android 8.0/API 26 `emulator-5554`, OpenGL ES via software emulator.
  Boot completion was `1`; initial window inspection showed launcher focus and no
  System UI ANR. Native accessibility checks passed in the full run.
- `pm list packages com.google.android.gms` and `com.android.vending` returned no
  packages. `:app:dependencies --configuration releaseRuntimeClasspath` contains no
  Play Services/Firebase. Gson and Guava listenablefuture are Java libraries, not GMS.
  MapLibre is `android-sdk-opengl:11.13.5`; min/target SDK in APK are 26/35.
- Merged APK permissions: Internet, network/Wi-Fi state, boot-completed, coarse/fine
  location and AndroidX's private dynamic-receiver permission. No background-location
  permission. Platform positioning is user-triggered/one-shot; activity stop cancels
  it, manifest polling, playback and footprint work. Existing lifecycle tests verify
  cancellation/no stopped polling. Native in-flight transport teardown is not a packet
  capture proving zero bytes after the instant of backgrounding.
- Only persisted background app job is storage deletion. OkHttp has no disk cache;
  MapLibre ambient cache is zero. Shared store counts metadata/staging toward
  **100,000,000 bytes**; LRU imagery goes before descriptor metadata. Radar expires at
  original frame + two hours, CARTO deletion starts at day 29. Cache hits precede
  request budgets; unknown templates/static masks are not persisted. Clear generation
  blocks pre-clear writes. Focused storage/HTTP regression evidence remains in #9.
- RainViewer: documented **100 requests/IP/minute**, app budget 90/rolling minute,
  shared-IP caveat, four concurrent native requests/host, backoff for 429. Thirteen
  frames × eight tiles = 104 requests before metadata/retries; no instant cold-history
  promise. No speculative prefetch. CARTO: noncommercial **5 million tile requests
  per calendar month**, aggregated across owner keys/services; dashboard monitoring
  and terms recheck remain maintainer operations, not an automatic paid upgrade.
- Developer-owned CARTO key comes from ignored `.env`/environment or local.properties;
  end users never provision keys. Native app keys are extractable from APKs. Existing
  host restriction limits key attachment to CARTO domains. No credentials were added
  to source. Current key provisioning details and quota sources are in README.
- Legend uses validated Universal Blue rain/snow dBZ samples, not rainfall rates or
  invented mixed precipitation. On-map credits link OSM, CARTO, RainViewer, MapLibre,
  GeoNames; About supplies version/project/source/license links. Existing keyed CARTO
  label/contrast evidence is [#8](issue-8.md), not a new live CARTO run in this session.

## Testing artifact and installation

Requirements: JDK 17 (session **17.0.21**), Android SDK platform 35, build tools
35.0.0, platform-tools; Gradle wrapper 8.13, AGP 8.9.2, Kotlin 2.1.20. Accept SDK
licenses. From repository root, with the maintainer's ignored `.env`:

```sh
export JAVA_HOME=/tmp/opencode/jdk-17.0.21+9
export ANDROID_HOME=/tmp/opencode/android-sdk
set -a && source .env && set +a
./gradlew :app:assembleDebug :app:assembleRelease
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.freedar/.MainActivity
```

Use your own toolchain paths and intended `adb devices` serial elsewhere. USB debugging
must be authorized for adb installation; direct APK opening requires allowing that
installer's unknown-app source. The existing app must use the same signing certificate
for `-r` upgrades. A different certificate requires an intentional uninstall (losing
app data) or the original signing key. No end-user provider key is requested.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 78,876,204 | `78c414ccec1e844642fd37739a0790f8c8cf493a495929692b007aa20b9f99ee` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 75,844,555 | `8050e1c6ee64e05461fe6d4b515d88c2db1e99e8b7e84eb8e2a3e8c758036d89` |

Debug is the **installable testing artifact**, version 0.1.0/code 1, universal
arm64-v8a/armeabi-v7a/x86/x86_64. Manual installation succeeded for the process checks.
`apksigner verify --verbose --print-certs` verifies APK v2 signing with Android Debug
certificate SHA-256 `61d9f3de0e248c94642869c6fc50e4547bccbb0fbe7f816ab1eafc7d1b254a6d`.
Release is unsigned and needs maintainer-controlled signing before installation.
Debug keys, provider key, build outputs and credentials are not committed.
Hashes identify these local artifacts, not a promise of byte-identical builds with
different keys/toolchains or VCS metadata. The steps reproduce the build/install process.

Search asset in each APK: **18,239,849 bytes**, ZIP stored; installed SQLite measured
with device `run-as ... stat`: **41,472,000 bytes**, combined **59,711,849 bytes** before
APK overhead. Source archive/tools are not packaged. The unchanged pinned generator
and prior byte-identical reproduction are documented in [place-search](../place-search.md).
Final API-26 offline query sample: reopen 25 ms, first query 73 ms, 70 queries with
17 ms median / 816 ms p95 / 1,368 ms max. This is an emulator sample with warm OS cache;
first-use extraction timing remains the prior #7 measurement, not a new cold benchmark.

## Final verification

```sh
set -a && source .env && set +a
JAVA_HOME=/tmp/opencode/jdk-17.0.21+9 \
ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_SERIAL=emulator-5554 \
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest
```

Passed in **12m52s**. **52 distinct JVM cases** (32 radar, 20 app; debug/release app
variants) retain passing Gradle up-to-date results. **28 deterministic Android tests
passed**: XML records 32 cases, zero failures/errors and four explicit opt-in skips
(two CARTO checks, static survey, separate remembered-camera process check). Console
progress overcounts are not extra cases. The static survey and extended offline
process phases passed separately above. Kotlin/debug/release builds pass. Lint:
**zero errors, 14 existing warnings**. `git diff --check` passes.

## Review against 36db1fd

Independent parallel Standards and Spec reviewers inspected the staged changes
against `36db1fd` (HEAD was still the baseline, so the review included new staged
files rather than an empty HEAD-to-baseline diff). Standards found no documented
violations and one low-priority clarity issue: state/country sample identity was
inferred from string length. Samples now have explicit country/optional-region
fields. Spec reported no actionable findings. A focused post-review Kotlin compile
and geographic navigation test passed; its opt-in live survey was skipped. This
test-only clarification does not change the APK or prior footprint observations.
The focused run replaces Gradle's connected-test report directory; the full-run
totals above were read from its XML before that replacement.

## Release decisions and limits

- **Permission resolved by maintainer confirmation:** RainViewer caching/preloading/
  distribution approval subject to published terms, no additional restrictions.
  Provider correspondence was not independently inspected. Older notes/spec text
  calling this an unresolved blocker are superseded by #9's confirmation.
- **Source license unresolved:** maintainer must select/document a license before
  presenting the project as publicly licensed source. Third-party data attribution
  does not license Freedar source. No license is chosen by this ticket.
- **Public release not authorized:** publication/store submission, production signing
  identity and final distribution decision remain with the maintainer. This APK is
  for testing; no release/store upload was made. Before a future distribution, resolve
  source licensing and review bundled dependency license/notice obligations, including
  the current packaging exclusions; this validation is not a redistribution clearance.
- **Device/evidence limits:** no new physical-device or newer-Android run; prior
  GrapheneOS reports are maintainer-supplied. Accessibility verifies exported
  nodes/actions and touch exploration, not spoken screen-reader traversal. No exact
  background deletion timing, live completeness or physical-GPU smoothness claim.

Spec #1 story reconciliation: #2/#3/#4 cover stories 1–8, 15–30, 51–55;
#5 covers 9–14; #6 covers 31–37; #7 covers 38–42; #9 covers 7 and 43–46;
#8 covers 30 and 47–50. Their integrated behavior is exercised by the final suite
and the new geographic/process checks above. #10's testing-delivery criteria are
satisfied within these explicit evidence limits. Parent Spec #1 retains the source
licensing/public-release decisions; testing completion must not imply public readiness.
