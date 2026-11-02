# Radar legend and appearance

## Provider semantics (validated 2026-11-01)

Primary RainViewer sources:

- [Weather Maps API](https://www.rainviewer.com/api/weather-maps-api.html):
  radar tiles are composite reflectivity; options are `{smooth}_{snow}`.
  Freedar requests `/2/1_1.png`: Universal Blue, smoothing, separate snow colors.
- [API color schemes](https://www.rainviewer.com/api/color-schemes.html):
  scheme 2 is Universal Blue; the scale is **dBZ**, with **Rain** and **Snow** columns.
- [Provider CSV](https://www.rainviewer.com/files/rainviewer_api_colors_table.csv):
  SHA-256 `e4438ddbff99e5d800b71357f691fc74cc4f6cb2f30ae1c244e33097ea320e96`.
  Universal Blue is column 3 (zero-based). The first 128 records are rain, the next
  128 snow: the provider page's `fillTable` explicitly pairs row `t` with `t+128`.

The compact legend shows the following **samples**, not categorical intensity bands.
Values below are provider HEX **RGBA**, converted to Compose ARGB without changing
channels. The swatches themselves use provider colors at full opacity; the legend
explains that map opacity and smoothing alter their appearance on the map.

| dBZ | Rain RGBA | Snow RGBA |
| --- | --- | --- |
| 15 | `88ddeeff` | `9fdfffff` |
| 25 | `0077aaff` | `5f9fffff` |
| 35 | `ffee00ff` | `3f7fffff` |
| 45 | `ff4400ff` | `1f5fffff` |
| 55 | `ffaaffff` | `003fffff` |
| 65 | `ffffffff` | `001fffff` |
| 75 | `00ff00ff` | `0000ffff` |

Higher dBZ means stronger radar echoes. It is **not** a rainfall/snowfall rate or a
guarantee of ground precipitation. There is no invented mixed-precipitation category,
mm/h conversion, or named light/moderate/heavy threshold. The provider table also
includes fainter echoes below 15 dBZ (including translucent gray/brown rain colors),
and saturates rain to green and snow to blue from 75 through 95 dBZ. The UI labels
its selected samples and explains that weaker echoes may be faint. Missing imagery
does not establish clear weather; the Info panel contains the coverage guidance.

## Defaults and effects

- **Radar opacity: 65%.** This retains geographic context while showing precipitation.
  The slider ranges from 0% (radar invisible) to 100%, updates native raster opacity
  immediately, and persists every change. Reset restores and persists 65%.
  Incoming frames and failed-replacement recovery use the current choice.
- **Theme: System.** Follows Android night configuration; Light uses CARTO Positron
  and Material light controls; Dark uses CARTO Dark Matter and Material dark controls.
  Changing the preference recreates the activity through its existing saved camera,
  timeline and overlay restoration path. Pending native requests and coverage are
  disposed before the replacement style starts; unavailable styles retain honest
  loading/failure diagnostics and Retry. The fallback map also follows the theme.
  Dark Matter's background and land fills use charcoal `#333333`, water `#222222`,
  and park fills `#303830`, matching the brighter QuickWeather-inspired direction.
- Radar and coverage are inserted below CARTO transport layers and labels. Dark
  transport fills are brightened to `#c3c9d1`, retaining dark casings: unmodified
  Dark Matter roads had weak contrast against the 50-dBZ red fixture during review.
- **Playback: Normal, 2 fps.** Slow is 1 fps and Fast is 4 fps. A speed change affects
  the next scheduled interval without restarting history. The newest frame always
  holds for one second. Loading can reduce achieved frame rate; these are targets.
- **Controls always visible.** Small corner icons replace the large cards and auto-hide
  setting. Info contains status, Retry/Refresh, coverage guidance, legend and credits;
  Info/Settings/Search use bounded scrolling panels that survive rotation. A red Info
  icon with a warning badge and accessible status indicates stale/failed data.
- **Time tag:** above the thin slider at the displayed frame, visible during playback,
  scrubbing, and paused history. It hides when paused at the latest image. Latest has
  a reserved slot beside the slider so showing the action does not resize an active scrub.

About includes the build version, GitHub project and RainViewer, OpenStreetMap,
CARTO, MapLibre and GeoNames credits, including the GeoNames license. Info also contains
linked credits. The owner requested collapsed attribution; the conflict with CARTO's
currently published visible-credit requirement is recorded in [VALIDATION.md](../VALIDATION.md#carto).

## Downloaded-imagery integration

Settings provides **Clear downloaded data**, backed by the shared bounded map/radar imagery
store. See [downloaded-imagery.md](downloaded-imagery.md) for permission, retention,
capacity and offline limits. Radar expires two hours after its original frame time. Clear does not
target preferences, remembered view, the whole app cache, or `noBackupFilesDir`.

The bundled GeoNames asset and `noBackupFilesDir/place-search/places-v1.sqlite` are
application resources, separate from imagery storage. See [place-search.md](place-search.md).
