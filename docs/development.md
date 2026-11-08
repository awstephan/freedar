# Development

[Back to Freedar](../README.md) · [Using the app](using-freedar.md) · [Providers](providers.md)

Kotlin/Compose hosts MapLibre Native **OpenGL ES**, with current CARTO
Positron/Dark Matter vector maps and RainViewer radar. Android 8.0 (API 26) is
the installation minimum; compile and target SDK are 35. Supported versions follow
the [Android support policy](android-support.md), independently of `minSdk`.
There is no Play Services dependency.

## Build and run

Requirements: JDK **17**, Android SDK platform **35**, build tools **35.0.0**, and
platform-tools. Install these with Android Studio or `sdkmanager`, accept the SDK
licenses, and set `JAVA_HOME` and `ANDROID_HOME` to those installations.
The checked-in Gradle 8.13 wrapper verifies its distribution checksum.

1. Provision the developer-owned CARTO key using the [provider guide](providers.md#carto-provisioning-and-cost).
2. Put it in the ignored `local.properties`:

   ```properties
   sdk.dir=/absolute/path/to/Android/Sdk
   CARTO_API_KEY=your-project-key
   ```

   Alternatively export `CARTO_API_KEY` in your shell (takes precedence).
   If it is stored in the ignored `.env` file, export it before invoking Gradle:

   ```sh
   set -a
   source .env
   set +a
   ```

   Gradle reads the environment; it does not load `.env` automatically.
3. Build and install on a connected device/emulator:

   ```sh
   ./gradlew :app:assembleDebug
   adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
   adb -s emulator-5554 shell am start -n com.freedar/.MainActivity
   ```

   Replace `emulator-5554` with the intended device serial from `adb devices` when
   installing elsewhere. The debug APK is signed and installable for testing;
   upgrades require the same signing certificate. See the [artifact/signing record](validation/issue-10.md#testing-artifact-and-installation).

Without a key, debug builds remain usable for fixture testing and show an explicit
base-map configuration message over a neutral background. They do not silently
use anonymous CARTO access. The key is embedded in the installed app, so users
never need a key/account. Mobile client keys are extractable from APKs; never use
a private backend credential here. Build outputs and `local.properties` are ignored.

### Signed release builds

Release signing uses the JKS keystore at
`~/.local/share/freedar/signing/freedar-release.jks`, alias `freedar`, with the same
password for the store and key. Override the path with `FREEDAR_KEYSTORE_PATH`
if needed. Keep the keystore backed up and its password in your password manager.

In an interactive Bash terminal, with JDK 17 and the Android SDK configured:

```bash
(
  set -e
  # Load the project's CARTO client key, if kept in .env.
  set -a
  source .env
  set +a
  read -r -s -p 'Freedar signing password: ' FREEDAR_KEY_PASSWORD
  printf '\n'
  export FREEDAR_KEY_PASSWORD
  ./gradlew --no-daemon --no-configuration-cache :app:assembleRelease
)
```

The subshell keeps the password out of the parent shell after the build. If the
CARTO key is already exported or in `local.properties`, omit the `.env` loading
lines. Do not enable shell tracing when entering or using signing credentials.

The signed artifact is `app/build/outputs/apk/release/app-release.apk`. Release
builds fail if the keystore, password or CARTO key is missing. Debug builds and
fixture tests do not require these release credentials.

Verify the APK using the Android SDK build tools:

```sh
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Compare the signer's SHA-256 certificate digest with the release-key fingerprint
recorded during setup. Existing debug installations must be uninstalled before
installing this differently signed release, which removes their local app data.
Future updates must use this release key and a higher `versionCode`.
Signed APKs and checksums are distributed through [GitHub Releases](https://github.com/awstephan/freedar/releases).

## Tests and checks

The agreed seams are app-level radar behavior, HTTP request/reuse/backoff behavior,
and native Android map integration with local fixtures. Routine tests need no provider credentials
or public network access (after dependency download).

```sh
# Typechecking / focused behavior test during development
./gradlew :radar:compileKotlin :app:compileDebugKotlin
./gradlew :radar:test --tests com.freedar.radar.LatestRadarTest
./gradlew :radar:test --tests com.freedar.radar.PlaybackTest
./gradlew :radar:test --tests com.freedar.radar.TileLoadingTest

# Native integration on an already booted device
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.freedar.MapRenderingTest

# Full checks
./gradlew test :app:lintDebug :app:assembleDebug
# With release credentials configured as above:
./gradlew --no-daemon --no-configuration-cache :app:lintRelease :app:assembleRelease
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
```

For a Google-free API-26 test device, install the SDK image
`system-images;android-26;default;x86_64` (**default**, not google_apis or Play Store),
create an AVD, and wait for `adb shell getprop sys.boot_completed` to print `1`.
Use hardware acceleration when available. The instrumentation test renders a
known blue PNG through the real native renderer, asserts snapshot pixels and
records actual HTTP paths at camera zoom 10; testing a source configuration alone
would not establish overzoom behavior. Debug-only cleartext access supports the
local fixture; release builds disallow it. API-26 runs are optional legacy
compatibility evidence, not the supported-version release gate.

For physical-device validation, use an isolated debug application ID to preserve
the installed release and its data. Save this as an external Gradle init script:

```groovy
allprojects {
    plugins.withId('com.android.application') {
        android.buildTypes.debug.applicationIdSuffix = '.validation'
    }
}
```

Pass `-I /path/to/validation.gradle` to every build/test invocation and set
`ANDROID_SERIAL` to the intended device. Keep the phone unlocked during native
checks. See [#16's record](validation/issue-16.md) for older-device results.
Keep normal device connectivity for native HTTP fixtures: on the Pixel 4 XL,
disabling all networks also prevented the native renderer from loading loopback
tiles. Run the offline-search benchmark separately from the rendering suite.

The existing offline-search benchmark can also run on an unrooted physical
device. Disconnect its networks first (record and restore their original state),
then run the single method with
`-Pandroid.testInstrumentationRunnerArguments.searchBenchmarkOffline=true` and
`-Pandroid.testInstrumentationRunnerArguments.class=com.freedar.PlaceSearchTest#offlineSearchSurvivesCacheRemovalAndReportsDeviceTimings`.
The opt-in does not change radio settings; it asserts there is no active network.
On a rooted AOSP emulator, use `searchBenchmarkRooted=true` instead to let the
benchmark disable and restore radios itself. Neither mode runs by default;
ordinary suites skip the benchmark before attempting any root probe.
Logs tagged `PlaceSearchBenchmark` distinguish first installation from an existing
index. For a first-use measurement clear **only the isolated validation package**
before a manual instrumentation invocation; never clear `com.freedar`.

To repeat the **opt-in live CARTO check**, build with the developer key and run:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.freedar.LiveCartoTest \
  -Pandroid.testInstrumentationRunnerArguments.liveCarto=true
```

This launches the real app, moves to a regional New York viewport, verifies native
glyph loading and named rendered symbol features, and captures a screenshot in
the app's cache. Without the `liveCarto=true` argument this live-service test is
skipped, keeping routine fixture tests independent of provider access.

### Recorded validation

The [#17/#19 follow-up](validation/issues-17-19.md) records the current large-text
controls and offline-restoration test corrections, separate-process validation,
and a full physical-device suite with 34 passes, no failures and four opt-in skips.

Start with the [compact-map checks](validation/compact-map.md) for the latest
UI/startup changes and [integrated testing results](validation/issue-10.md)
for cross-feature, geographic and process-restart evidence. APK hashes and sizes
in the integrated record identify that earlier build, not every subsequent build.

Earlier feature evidence covers [map rendering](validation/issue-2.md),
[playback](validation/issue-3.md), [freshness](validation/issue-4.md),
[coverage](validation/issue-5.md), [positioning](validation/issue-6.md),
[search](validation/issue-7.md), [appearance](validation/issue-8.md),
[map storage](validation/issue-9.md) and
[offline radar](validation/issue-9-radar.md).

## Project structure and documentation

| Path | Purpose |
| --- | --- |
| `app/` | Android/Compose UI, MapLibre rendering, platform location, search, networking and device storage; unit and native instrumentation tests. |
| `radar/` | Android-free Kotlin radar behavior, provider integration, timeline/playback state and behavioral tests. |
| `tools/` | Bundled place-search dataset generation tooling. |
| [DESIGN.md](../DESIGN.md) | Agreed product design, including the retained compact-map revisions. |
| [docs/place-search.md](place-search.md) | GeoNames provenance, dataset reproduction and search behavior. |
| [docs/radar-appearance.md](radar-appearance.md) | Legend, theme, opacity and playback settings. |
| [docs/downloaded-imagery.md](downloaded-imagery.md) | Cache boundaries, provider retention, expiry and offline behavior. |
| [docs/validation/](validation/) | Dated implementation and test evidence, with limitations. |

[VALIDATION.md](../VALIDATION.md) is the initial technical investigation, with a later
attribution finding. Its original unresolved-check list predates implementation;
use the dated feature and integrated records above for current evidence.
