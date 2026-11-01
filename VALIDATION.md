# Initial technical validation

This records documentation/source inspection and a RainViewer public manifest check, not a working Android prototype.

## Supported by evidence

### RainViewer

- [API terms](https://www.rainviewer.com/api.html): free personal/educational use; introduction also mentions small-scale community use. Image modification allowed; linked attribution requested.
- [Weather Maps API](https://www.rainviewer.com/api/weather-maps-api.html): two hours at ten-minute intervals, 256/512-pixel tiles, native zoom maximum 7. Use returned host and frame path, not constructed timestamp paths.
- [Public manifest](https://api.rainviewer.com/public/weather-maps.json) inspected during validation contained 13 historical frames spaced 600 seconds apart, spanning 120 minutes. Do not hard-code frame count.
- Frame `time` means composite frame-generation time. Individual radar observations can be older. Manifest `generated` is a different timestamp and must not substitute for image freshness.
- [Transition summary](https://www.rainviewer.com/api/transition-faq.html): 100 requests/IP/minute; only Universal Blue remains; nowcast and satellite IR discontinued.
- [Color documentation](https://www.rainviewer.com/api/color-schemes.html): Universal Blue ID 2 has rain/snow support. Separate mixed precipitation is not documented. Exact swatches must be checked against imagery/legend data before shipping.
- Coverage mask is updated infrequently. It describes a coverage footprint, not per-frame completeness or current radar health.

### Request budget

For 13 frames and T tiles per frame, cold-loading history costs approximately 13T radar requests before coverage, metadata, and retries. Four tiles/frame costs 52 requests; eight costs 104. Devices sharing a public IP may share the limit.

Playback must reuse downloaded imagery, prioritize latest data, bound concurrency/prefetch, and back off on throttling. Smooth 2 fps playback is a rendering goal, not a request frequency. A per-device limiter cannot guarantee compliance across all devices sharing an IP.

### MapLibre

- [Raster source specification](https://maplibre.org/maplibre-style-spec/sources/): source maxzoom permits overzoom by reusing highest-zoom tiles. Set radar source maxzoom to 7, not the camera maximum or layer visibility maximum.
- [Android build configuration](https://github.com/maplibre/maplibre-native/blob/main/platform/android/MapLibreAndroid/build.gradle.kts) inspected declares minSdk 23; API 26 target meets this floor.
- [Android artifacts](https://maplibre.org/maplibre-native/android/examples/artifacts/): default Vulkan rendering has hardware requirements. Evaluate explicit OpenGL artifact for broad older-device compatibility.
- Core renderer does not require Play Services; final dependency graph and location integration still need verification.

### CARTO

- [Basemap terms](https://carto.com/legal/basemap-terms/) reviewed permit app-user display and noncommercial free usage up to five million tile requests/month, with a developer key.
- [Basemap FAQ](https://docs.carto.com/faqs/carto-basemaps): vector endpoints may work keylessly, but terms require the customer's own key. Do not treat working anonymous URLs as the permission model.
- Device caching permitted up to 30 days; no server-side cache/proxy or bulk extraction under the reviewed terms. Cache must enforce deletion, not merely stale HTTP metadata.
- Required visible linked credit: OpenStreetMap contributors and CARTO.
- QuickWeather's CARTO v1 tiles are legacy and no longer updated. Use current styles/schema rather than copying its old style unchanged.

### GeoNames

- [Download directory and readme](https://download.geonames.org/export/dump/): cities500 source archive approximately 13 MB; includes population >500 or administrative seats down to PPLA4. CC BY 4.0 attribution required; completeness is not guaranteed.
- Final search database, alternate-name strategy, region display, APK size, and performance have not been measured.

## Unresolved checks

1. **RainViewer permission:** clarify free public APK distribution and on-device persistent caching/prefetch with support@rainviewer.com. No message has been sent. Draft: “I am building a free, ad-free, open-source Android radar viewer for myself, friends and family, potentially distributed as a public GitHub APK. Does personal/small-scale community use cover this? May it preload frames for animation and retain recently viewed radar tiles on-device between launches? What retention/prefetch limits apply?”
2. **Honest coverage:** static coverage cannot establish live completeness. Define UI for covered-but-unknown availability without asserting clear conditions. Validate Alaska, Hawaii and representative international areas.
3. **Radar rendering:** device-test overzoom request URLs, frame readiness, rapid scrubbing/panning, missing tiles, throttling and stale responses. No on-device smoothness result exists yet.
4. **Legend:** verify exact RainViewer swatches and supported type semantics. Do not invent mixed precipitation or exact local observation times.
5. **Base map:** obtain project CARTO key, pin appropriate current style/assets, verify credits and cache deletion behavior.
6. **Offline search:** generate and measure indexed GeoNames subset; test ambiguous names, alternate scripts and small-town omissions.
7. **Android integration:** test API 26/older hardware, Google-free location cancellation, screen-reader focus, large text, landscape, lifecycle and final dependencies.

## Validation outcome

The proposed stack is plausible and the main data/zoom capabilities are documented. Public distribution/caching permission and runtime quality are not yet established. Following review of QuickWeather as an existing reference, validate runtime behavior within the first real map/radar implementation slice rather than requiring a separate throwaway spike. These open items are not completed tests.
