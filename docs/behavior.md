# Behavior and implementation boundaries

[Back to Freedar](../README.md) · [Product design](../DESIGN.md) · [Development](development.md)

- **Info** includes the legend, which explains RainViewer Universal Blue rain/snow reflectivity with validated
  dBZ color samples. **Settings** offers immediate radar-opacity preview (65% default
  and reset), System/Light/Dark for map and controls, and
  Slow 1 / Normal 2 / Fast 4 fps playback. Choices persist. About includes version,
  project and source links. Controls stay visible, with compact corner icons and a thin bottom timeline.
  Dark land is charcoal (#333333) and water #222222, preserving radar colors and readable roads.
  See [appearance semantics and storage boundary](radar-appearance.md).
- **Locate** uses Android's platform GPS/network providers for a one-shot fix,
  waiting at most 30 seconds after permission. No cached last-known position is used;
  updates older than 30 seconds are ignored. Success centers at **zoom 9** and
  returns radar to newest paused/following. This local-area framing is intended
  to show nearby approaching precipitation; ground span varies with latitude
  and viewport size, and zoom adds no radar detail. There is no subsequent tracking.
  **Cancel** remains visible while waiting. Pan/zoom, search-style navigation and
  backgrounding invalidate the request, including late permission/fix callbacks.
  Denial or failure leaves camera/timeline unchanged and explains the outcome.
  `MainActivity.navigationStarted()` is the shared entry point search
  selection must call before moving the camera. Requests do not survive recreation.
  Cold launches also attempt one fix if location permission is already granted; no automatic
  permission prompt occurs. A missing permission/fix leaves the remembered view usable.
- **Search** finds cities and towns worldwide using a bundled GeoNames cities500
  database, entirely offline. Submit a place name using the keyboard's Search action;
  results identify region and country. Selection cancels pending Locate, centers at
  zoom 9, and returns radar to newest paused/following. Search stays open until
  selection or dismissal. It does not search addresses/businesses or guarantee every
  settlement; alternate names and normalized prefixes help with spelling variants.
  See [dataset provenance, reproduction, matching and storage](place-search.md).
- `radar/` is an Android-free behavioral module. `LatestRadar` exposes Refresh,
  Play, Pause, Select and Latest commands and observable state. `RadarProvider`,
  `Clock`, coroutine scheduling and `RadarDisplay` control responses, time and
  actual image presentation. Commands run on the main thread; their caller owns
  lifecycle cancellation. No generalized provider registry is introduced.
- `RainViewer` returns ordered historical frames using the returned HTTPS host
  and opaque paths. The timeline uses the two-hour window ending at the greatest
  returned frame time, without assuming a frame count. Manifest `generated` and
  future/nowcast entries never determine frame time.
- Opening presents newest imagery before loading history. Play starts at oldest,
  advances at a default 2 fps (slower when waiting for imagery), loops, and holds
  the newest available image for one second. Pause retains the displayed frame.
  Scrubbing pauses and retains selection, even across manifest updates; selecting
  the newest point is still a fixed selection. **Latest** returns to paused,
  following mode. The timeline uses ordered frame positions, with markers for
  unavailable frames and intervals exceeding the provider's ten-minute cadence.
  During playback, newly announced newest imagery preempts history loading and
  is prepared invisibly first. The displayed historical frame and its timestamp
  remain unchanged while that preparation runs.
- **Frame time means composite generation time**, not exact observation time at
  each location. The display uses the phone's clock format/time zone and includes
  a date. A small tag follows the displayed frame above the slider during playback/scrubbing
  and when paused on history; it hides when paused at the latest frame. Full time/age details
  remain available in Info. A feed is marked stale after its newest-known frame exceeds **20 minutes**,
  independently of the retained displayed image's age if replacement tiles fail.
- `RadarMap` owns the native renderer. It retains the previous frame while a
  candidate loads beneath the opaque base-map background, commits only after
  native tile parsing and full rendering, and removes failed/cancelled candidates.
  Transport layers and labels are above radar. Obsolete scrub requests are cancelled and serialized;
  the timestamp changes only after the replacement is presented. Pan/zoom changes
  neither timeline mode nor selection and never recenters the map.
  Playback loads its one requested next frame under the background, then waits
  at a presentation gate for the chosen speed's interval or one-second endpoint hold. Camera
  changes during that wait require readiness for the new viewport before exposure.
  Native tile errors remain visible; blank pixels are never called clear weather.
- Native radar source maxzoom is **7**; camera maxzoom is **18**. Zooming closer
  magnifies the same radar pixels. Pan/pinch remain available during loading and
  failures; rotation and tilt are disabled. The initial overview frames the contiguous USA,
  with worldwide panning (including Alaska and Hawaii).
- Opening/foreground return, explicit Refresh/Retry and a five-minute foreground
  timer refresh the manifest. Following mode updates imagery; a historical
  selection stays selected. Backgrounding suspends playback and cancels outstanding
  commands and footprint downloads; there is no background polling or location.
  Returns under five minutes retain selection and automatically resume previously
  active playback. At five minutes or longer, return resets to newest paused/following,
  preserving the camera. Saved state includes absence time even on Android 8's
  save-before-stop lifecycle ordering. History loading is
  demand-driven with **zero speculative prefetch**. Map state is restored through
  Android activity recreation, along with timeline selection and playback mode.
- Offline, loading, manifest refresh failure, imagery failure, and stale-feed notices
  are independent. A successful historical presentation cannot dismiss a failed
  manifest refresh. Retry reloads a selected viewport without switching its time.
  A red Info icon with a warning badge indicates stale/offline/failed data; its panel contains
  displayed-frame age, newest-feed age, diagnostics and Retry/Refresh. It also contains the
  coverage explanation and radar legend.
- RainViewer's infrequently updated coverage mask is a **static footprint**, not
  live completeness. Black mask pixels shade known outside-footprint areas; inside
  the footprint live availability remains unknown, and blank radar never means clear
  weather. Missing/loading footprint areas are explicitly unknown. Coverage uses
  returned provider host and the documented `/v2/coverage/0/…/0/0_0.png` endpoint.
  At most 16 viewport tiles (z0–7) are fetched sequentially through the shared bounded
  client/cache/budget. Decoded images enter native image sources, keeping stalled
  coverage HTTP requests out of radar's native presentation-ready gate. Viewport
  changes discard obsolete footprint sources; no history prefetch or disk cache.
- Concurrent refreshes are coalesced. HTTP calls have timeouts, native tile calls
  use at most four requests per host. New radar/coverage tile downloads are spaced
  at least 750 ms apart (80 per minute); cached tiles bypass pacing and cooldown.
  A provider 429 blocks new downloads according to numeric or HTTP-date `Retry-After`
  (one minute if absent/invalid). Multiple devices behind one IP can still
  exhaust RainViewer's shared 100-request/minute allowance. A cold sequence of
  13 frames × 8 tiles needs 104 requests even before metadata/retries, so it cannot
  all load within one minute. Throttled frames retain their timeline slots: the last
  image stays visible with a retry countdown, then the pending frame retries automatically.
  Selecting a cached frame cancels the pending retry and can display immediately.
  Actual missing/failed frames are still skipped until refresh/retry.
  The client and limiter live at application scope, preserving backoff on rotation.
- CARTO resources and radar share a 100 MB LRU device store with retention deletion
  and Settings → Clear downloaded data. See [downloaded imagery](downloaded-imagery.md).
  OkHttp has no second disk cache; native responses are marked no-store and
  MapLibre's ambient cache maximum is zero. In-memory
  reuse is bounded to a 24 MiB application-lifetime LRU of radar tile bytes,
  expiring after 15 minutes (individual entries capped at 512 KiB). The renderer
  holds only the visible frame and one candidate, rather than a source per history
  frame. Warm playback reuses downloaded bytes, not a freshly downloaded sequence.
  Radar pixels and frame descriptors expire two hours after original frame time;
  saved-history fallback preserves actual time and failed-refresh/offline/stale notices.
  Fresh launches restore remembered center/zoom as a fallback, attempt current
  location when already permitted, and start latest paused/following; theme and
  orientation recreation preserve the existing timeline behavior.
