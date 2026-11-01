# Issue #7 implementation validation

Scope: [Offline worldwide place search](https://github.com/awstephan/freedar/issues/7).
Review baseline: `f73b0f6`. Session: 2026-10-31.

## Agreed seams and implementation

The maintainer approved four seams before coding: reproducible generator/package,
production offline query interface on API 26, production Search UI through native
map/radar with controlled positioning, and native accessibility/layout checks.
The initial database test failed on the absent search interface; the initial UI
test failed on the absent Search action. Vertical implementation made them pass.
Focused checks and Kotlin compilation ran throughout.

The bundled GeoNames dataset, source snapshot, normalization/alternate-name policy,
license, reproduction command, versioning, and storage boundary are documented in
[place-search.md](../place-search.md). Generation was reproduced byte-for-byte using
`python3 tools/places/check_generation.py`.

Search is in existing MapActions, runs only on keyboard submission, and keeps the
overlay hold until selection/dismissal. It supports dismissal by Close search or
Back and preserves typed/submitted state across recreation. Frame/status, coverage
guidance, and linked attribution remain outside the scrollable search region.
Selection invokes navigation cancellation before zoom-9 framing and existing Latest.
Search does not depend on base-style readiness or imagery downloads. The startup
overview is suppressed after selection, including a style that finishes later.

## Device measurements

Measured on **AOSP Android 8.0 / API 26**, `emulator-5554`, x86_64, 2 GiB RAM,
1080×1920 display (420 dpi). This is an emulator compatibility/performance sample,
not a claim about all older physical phones.

| Item | Measured bytes |
| --- | ---: |
| Database asset in release APK (already gzip-compressed, ZIP stored) | 18,239,849 |
| Installed read-only SQLite database | 41,472,000 |
| Combined asset + extracted database, excluding APK directory overhead | 59,711,849 |
| Whole unsigned universal release APK | 75,811,671 |

The database contributes about **17.4 MiB to APK download**, and its extracted copy
uses **39.6 MiB**. Whole-APK size includes existing native renderer libraries and
all packaged ABIs; it is not a Play Store split-download estimate. These are measured
artifact sizes, not the upstream cities500 ZIP size. Device `run-as ... stat` confirmed
41,472,000 bytes for `no_backup/place-search/places-v1.sqlite`.

After a fresh install, opening/extracting the database took **3,905 ms**. The UI does
this on an IO worker with “Searching offline…” feedback and remains dismissible.
On the following offline measurement: reopen **16 ms**, first Reykjavík query
**94 ms**; 70 queries (10 repetitions of Springfield, Munich, 東京, Twizel, an absent
name, `a`, and `san`) had **24 ms median, 763 ms p95, 1,565 ms maximum**. The full-suite
sample was 22 / 849 / 1,637 ms respectively. Single-letter prefixes are intentionally
included as expensive cases. Measurements use elapsed realtime around the public
search interface, consume all returned rows, and exclude rendering. OS page cache
was not flushed; this is not a cold-storage benchmark. The test's 2-second ceiling
is a regression budget for this emulator, not a universal product guarantee.

## Behavioral evidence

- Duplicate Springfield results include Illinois and Massachusetts with country
  names/codes; result IDs are deduplicated across aliases.
- API-26 searches verify München/Munich, 東京/Tokyo, Москва/Moscow, São Paulo/sao paulo,
  Reykjavík/Reykjavik, and punctuation in St. Ives. Prefix, whitespace-only,
  punctuation-only, absent-name, and SQL-like input behavior is checked.
- Small-town checks verify St Ives, Cornwall (5,410 source population), Twizel,
  Canterbury (1,780), and Quorn, South Australia (1,190).
- The offline test disables Wi-Fi/mobile data with emulator-root commands, asserts
  no active network, removes Android cache storage, searches, removes cache again,
  and reopens/searches successfully. Radio settings are restored in `finally`.
  On devices without emulator root, that radio-isolation test is explicitly skipped.
  Current app imagery persistence is disabled; no future Clear downloaded data UI
  exists yet. The storage contract keeps its future scope separate from bundled data.
- The production UI test starts historical radar and pending Locate, types without
  executing a query, holds Search open beyond five seconds, submits via IME Search,
  selects Reykjavík, and verifies zoom/coordinates, newest timeline index, paused
  Following state, dismissal, and rejection of a late location callback.
- Large-text checks at 180% in portrait/landscape verify Search survives its timeout,
  empty-result limitations/manual-navigation guidance, close/reopen, query restoration
  on rotation, and displayed frame/coverage/linked GeoNames credits. A final focused
  rerun also taps the field, waits for actual IME window insets, and verifies the
  field remains displayed before typing/submitting in both orientations.
- Native refreshed accessibility nodes support Search focus/click and Close search
  focus/click alongside Locate/Cancel and Settings/Done. Touch-exploration override,
  persistent-controls preference, timeout, and real window-routed map pan/zoom remain
  covered by the existing tests.

## Failures found and resolved

Early failed attempts are not counted as passing evidence:

- AAPT special-cases `.gz` assets: use `.gzip` plus explicit no-compress packaging.
  A larger gzip input buffer and avoiding nested compression reduced first-use work.
- Aggregating name matches before joining full place rows reduced the broad-prefix
  query maximum from an observed 3,054 ms to below the 2-second regression budget.
- Search's overlay state was originally read only inside a child composition and
  SideEffect. The parent now reads the hold value during composition, so Search
  alone reliably prevents timeout (pending Locate had masked the first version).
- Search saved state now sits above the orientation-dependent layout, avoiding an
  older landscape query being restored after newer portrait edits.
- One scroll region replaces nested search scrolling; the license link lives inside
  Search while persistent GeoNames credits remain compact enough for large text.
- Initial small-town assertions had incorrect manually supplied GeoNames IDs; the
  corrected IDs were verified against original source records. API-26 shell Wi-Fi
  control requires root; ordinary shell commands did not establish offline state.

## Full-suite result

```sh
set -a && source .env && set +a
JAVA_HOME=/tmp/opencode/jdk-17.0.21+9 \
ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_SERIAL=emulator-5554 \
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  :app:connectedDebugAndroidTest
```

Passed: **35 distinct JVM tests**, debug/release Kotlin compilation and APK builds,
lint with zero errors (existing warnings), and **21 deterministic Android tests**.
The Android XML records 22 tests, zero failures, one opt-in live CARTO skip. Gradle's
console printed an extra progress count; the XML testcase list is the evidence.
`git diff --check` passed. A subsequent focused three-test database run measured
fresh extraction and installed size; all three passed.

All native checks explicitly targeted `emulator-5554`; boot completion was `1` and
initial window inspection showed no System UI ANR. The physical device was not used.
The ignored `.env` was exported for builds. Pre-existing design/agent documents are
excluded from implementation commits.

## Review against f73b0f6

Independent Standards and Spec reviewers examined the working-tree diff and new
files against `f73b0f6`. Standards found no documented breaches and one maintainability
finding: SQL and UI duplicated the result-limit policy. Both now use the search
module's `RESULT_LIMIT` constant; targeted Standards re-review confirmed resolution.
Spec reported no actionable findings. The review
also inspected source/artifact provenance and size evidence. Compilation was rerun
after the constant extraction, along with the focused controls JVM tests; it does
not change the tested search behavior. The strengthened real-keyboard large-text
check passed, and the final release APK was rebuilt and remeasured (same byte size).

## Evidence limits

Native accessibility checks verify exported nodes/actions, not spoken output.
Queries use the snapshot's names and do not establish exhaustive worldwide coverage
or equivalence for every Unicode version. Live map/radar services, newer Android,
and physical-device ergonomics were not retested. Offline search provides coordinates;
it does not create offline map/radar imagery.
