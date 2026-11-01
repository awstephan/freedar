# Freedar: agreed product design

Status: product decisions agreed during the design interview; technical validation remains incomplete. No application has been implemented.

Published specification: https://github.com/awstephan/freedar/issues/1

## Purpose and audience

A beautiful, uncluttered Android map focused on precipitation radar. Map/radar only is a lasting product boundary: no weather dashboard, forecasts, alerts, notifications, or accounts. Satellite and additional radar providers are deferred.

Initially for the owner, friends, and family, with source likely published on GitHub and potentially public APK downloads. Initial recurring service budget: $0. The developer may register for service accounts; users should not need accounts or keys.

Worldwide exploration and city/town search; all 50 US states are the initial validation priority. Coverage is determined by the provider, not an artificial geographic restriction.

## Map and presentation

- Edge-to-edge, restrained map colors and readable roads/place labels; radar is the visual focus.
- Flat, north-up map; pan and pinch zoom, no rotation or tilt.
- Permit zoom beyond radar native resolution by enlarging radar tiles. Never imply this adds precipitation detail.
- Portrait and landscape; rotation preserves map and playback state.
- Theme follows the system by default, with Light and Dark overrides.
- Remember map center, zoom, and settings between launches.
- First launch shows an overview encompassing all 50 states without prompting for location permission.
- Controls appear initially and on a map tap, then hide after five seconds of inactivity.
- Frame time, important stale/failure status, and required attribution remain visible when controls hide.
- Keep controls visible when the user disables auto-hide or Android touch exploration is enabled.
- Compact playback strip: Play/Pause, scrubbable timeline, frame time, and Latest action. Small Locate, Search, Legend, and Settings controls; no permanent layer selector is needed for the single-layer release.

## Radar and time

- Initial provider: RainViewer. Keep its integration replaceable; no provider-switching UI initially.
- Observed historical radar only, using approximately the last two hours available.
- Open on the newest frame, paused. This latest view follows newly available frames automatically.
- Scrubbing pauses and retains the selected historical time. Updates must not silently replace it with the latest frame.
- Play starts at the oldest available frame and loops, briefly holding at the newest frame.
- Default speed: two frames/second. Settings also offer Slow (one) and Fast (four); newest-frame hold is one second.
- Latest returns to the newest frame, paused and following updates.
- Skip unavailable frames rather than blocking fresh data. Prioritize newest imagery, keep the last displayed image until its replacement is ready, and mark missing frames on the timeline.
- Timestamp must always match the image displayed. RainViewer supplies composite frame-generation time, not exact local observation time; label accordingly.
- Use the phone's time zone and 12/24-hour preference, with a time-zone label.
- Flag the feed as stale when its newest available frame is older than 20 minutes, provisionally subject to provider validation. Selecting an old frame is not feed staleness.
- Refresh failures retain previously loaded imagery with its timestamp, a persistent compact failure/age notice, and Retry.
- With no radar loaded, keep the base map usable and explicitly show radar unavailable. Do not equate blank imagery with clear weather.
- Show precipitation types only where supported; RainViewer currently documents rain/snow, not a separate mixed category. Legend must match actual provider colors and intensity meaning.
- Legend opens as a compact overlay and remains until dismissed.

## Navigation and lifecycle

- Locate requests foreground location permission on demand, centers once, and zooms to a useful local-area view showing surrounding weather.
- Search selection uses the same local-area framing.
- Both actions reset to newest radar, paused and following updates.
- Ordinary panning/zooming preserves timeline state and never triggers recentering.
- Locate has Cancel. Panning, zooming, or choosing a search result cancels pending location work; late results must not move the map.
- Denied permission or failed location leaves the existing view usable with an explanation and Search available.
- No background location or radar polling. Check freshness on return.
- After five minutes in the background, return to latest radar, paused, preserving map position. Shorter interruptions preserve the selected frame. Whether active playback resumes after a short interruption remains a detail to settle during validation.

## Offline search and storage

- Bundle a worldwide city/town database derived initially from GeoNames cities500. No streets/business search.
- Results identify region and country. Search executes on keyboard submission, not each keystroke.
- No online fallback: absent places yield a clear no-results message; users can navigate manually.
- GeoNames source ZIP is about 13 MB; final indexed database and APK size must be measured. Dataset includes places above 500 population and specified administrative centers, not every settlement.
- Preserve recently viewed map/radar imagery across launches where provider rules allow. Offline imagery retains its real frame time and offline/stale status.
- Downloaded imagery cache limit: 100 MB, least-recently-used eviction, also enforcing provider retention limits.
- Bundled search data is separate from that cache.

## Settings

- Radar opacity slider: immediate preview with map partly visible, remembered value, reset to default.
- Theme: System (default), Light, Dark.
- Keep controls visible: off by default, overridden for touch exploration.
- Playback speed: Slow, Normal (default), Fast; remembered.
- Clear downloaded data: removes cached maps/radar but preserves settings, map position, and bundled search data.
- About: app version, GitHub link, and source credits including GeoNames. Required map attribution stays on-map.

## Technical direction

- Kotlin and Jetpack Compose, embedding a dedicated map renderer.
- Target Android 8.0/API 26 and newer, subject to dependency/device validation.
- No Google Play Services or Play Store dependency. Use platform location and Android accessibility APIs.
- Candidate renderer: MapLibre Native. Candidate base map: current CARTO vector maps, subject to current terms and key setup.
- Build the real map/radar foundation as the first implementation slice, validating animation, overzoom, request budgeting, accessibility, and Google-free operation within it. A separate throwaway spike is not required.

See [VALIDATION.md](VALIDATION.md) for evidence, limitations, and unresolved checks.
