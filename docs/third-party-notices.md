# Software and data notices

Settings → About → **Software licenses (offline)** displays
`app/src/main/assets/open-source-notices.txt`. The APK contains the complete text;
reading it requires no browser, provider credentials, or network access.
The bundle includes Freedar's BSD 3-Clause license as well as third-party notices.

## Audit scope (2026-11-02)

The inventory is the **74 external artifacts** resolved by
`:app:releaseRuntimeClasspath`, including transitive runtime dependencies.
Gradle plugins, test dependencies, and Android platform APIs are not shipped
libraries. BOMs and dependency constraints are not counted as packaged artifacts.

- AndroidX/Compose, Kotlin, kotlinx, OkHttp, Okio, Gson, Guava ListenableFuture,
  Timber, JSpecify, JetBrains annotations, and MapLibre Java utilities declare
  Apache 2.0 licenses in their Maven metadata (including inherited POM licenses).
- MapLibre Native OpenGL 11.13.5 and gestures 0.0.4 declare BSD licenses.
  Maven metadata alone does **not** cover their embedded native code: the bundle
  preserves MapLibre's Android combined license file from `android-v11.13.5`,
  supplemented with ICU, nunicode, unordered_dense, PMTiles, jni.hpp, filesystem,
  FreeType, HarfBuzz, and libc++ notices. Native submodule revisions come from
  that MapLibre tag; libc++ uses the LLVM 18 license text. Android native CMake
  links system zlib and bundles public-domain SQLite. The combined upstream
  notice is preserved intact, including its upstream dependency descriptions;
  Freedar's resolved versions are listed separately at the start of the bundle.
- Kotlin's stdlib includes third-party time/math/collection implementations;
  their BSD, Boost, and Apache notices supplement Kotlin's own Apache notice.
- OkHttp's embedded public-suffix data carries an MPL 2.0 notice. The bundle
  includes the notice, MPL text, and source links. This data license does not
  change Freedar's source license.
- The bundled GeoNames database is a modified CC BY 4.0 dataset. Credits include
  GeoNames, source URLs, input date, modification description, and license text.
  Input hashes and regeneration are documented in [place search](place-search.md).
- Map/radar provider service terms and the on-map attribution layout are separate
  from software-license packaging. Existing provider credits remain in the app.

The archive collector preserves LICENSE/NOTICE/COPYRIGHT entries from runtime
JARs/AARs, including `classes.jar` and `libs/*.jar`, before APK packaging exclusions.
Identical texts are grouped with their originating artifact paths. AAR lint JARs
are not runtime code and are excluded. Apache's full text is also included for
artifacts that provide only Maven license metadata.

## Updating and verifying

With the documented JDK/SDK environment configured:

```sh
./gradlew :app:exportLicenseArtifacts
python3 tools/licenses/generate.py --check
```

The check compares exact artifact coordinates and SHA-256 hashes against
`tools/licenses/provenance.json`, checks the notice bundle hash, and verifies
that the current Freedar license is included. It runs offline against the
resolved artifacts. It does not inspect arbitrary new native dependencies.

After dependency changes, review their license metadata, embedded notices, and
native dependencies, update pinned source URLs as needed, then regenerate:

```sh
./gradlew :app:exportLicenseArtifacts
python3 tools/licenses/generate.py
```

Generation needs network access to the source URLs in the script; normal APK
builds package the checked-in bundle without downloading license texts. Source
URLs and downloaded-text hashes are recorded in `provenance.json`. Review and
commit both generated files. A MapLibre version change explicitly requires a
native-notice review. Passing Maven metadata checks alone is not that review.

To verify the actual distribution artifact after building it:

```sh
python3 tools/licenses/generate.py --check \
  --apk app/build/outputs/apk/release/app-release.apk
```

This compares the APK's notice asset byte-for-byte with the checked-in bundle.
Use the debug APK path for development checks. Include this check in release
validation; the signed APK must be rebuilt after a notice change.
