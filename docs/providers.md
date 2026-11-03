# Map and radar providers

[Back to Freedar](../README.md) · [Development](development.md)

## CARTO provisioning and cost

- The maintainer requests a project-specific key at
  <https://carto.com/basemaps/apikey/>. Use the noncommercial free tier for this
  personal/community project; no paid plan or recurring service is required.
- As reviewed on 2026-10-30, noncommercial use is free up to **5 million tile
  requests/calendar month**, aggregated across the owner's keys and raster/vector
  services. Monitor the dashboard and contact CARTO about a higher noncommercial
  allowance rather than enabling a paid plan. Recheck terms before publishing.
- Use a key suitable for native app requests, without browser Referer/Origin
  restrictions. The app does **not** send CARTO's optional mobile-app restriction
  headers; do not enable that restriction until package/certificate headers are
  implemented and tested. Keep the key specific to Freedar.
- The app requests the **current hosted Positron or Dark Matter style**, not legacy v1 assets.
  CARTO's style/glyph/sprite/tile URLs can change as they update their maps; the
  network adapter attaches the project key only to `cartocdn.com` and subdomains.
- The top-left **Attributions (©)** button opens linked credits for **OpenStreetMap contributors**, **CARTO**,
  **RainViewer**, **MapLibre**, and **GeoNames**. This owner-requested collapsed layout conflicts
  with CARTO's published visible-credit wording; see [the attribution finding](../VALIDATION.md#carto).

## Provider contracts and reference

- [RainViewer Weather Maps](https://www.rainviewer.com/api/weather-maps-api.html),
  [transition limits](https://www.rainviewer.com/api/transition-faq.html),
  [palette](https://www.rainviewer.com/api/color-schemes.html),
  [terms](https://www.rainviewer.com/api.html).
  Universal Blue (2), smoothing and snow enabled (`1_1`) are used. No mixed
  precipitation classification or rainfall/snowfall-rate conversion is claimed.
- [CARTO FAQ](https://docs.carto.com/faqs/carto-basemaps),
  [basemap terms](https://carto.com/legal/basemap-terms/),
  [attribution](https://carto.com/attribution/).
- [MapLibre Android artifacts](https://maplibre.org/maplibre-native/android/examples/artifacts/),
  [raster source specification](https://maplibre.org/maplibre-style-spec/sources/).
- [QuickWeather WeatherMapView](https://github.com/TylerWilliamson/QuickWeather/blob/master/app/src/main/java/com/ominous/quickweather/view/WeatherMapView.java)
  was inspected as a lifecycle/raster-layer reference. No source was copied. Its
  legacy style, hardcoded radar host, camera zoom cap, and transparent-success
  substitution on HTTP failures are not used here.

**Permission record:** On 2026-11-01 the maintainer confirmed approval for RainViewer
caching, preloading and distribution subject to the published site terms, with no
additional restrictions. Radar storage uses a two-hour frame-based retention window.
This is maintainer-confirmed permission, not independently inspected correspondence;
see [downloaded imagery](downloaded-imagery.md). CARTO retains its separate
device-cache limits. The earlier [release decisions and limits](validation/issue-10.md#release-decisions-and-limits)
record the pre-release testing context. Current download instructions are in the
[README](../README.md#download-and-install); signing instructions are in the
[development guide](development.md#signed-release-builds).
