# Freedar: agreed product design

Status: implemented testing build; compact map presentation and startup navigation revised with the owner on 2026-11-01. The owner explicitly chose to retain the resulting changes after implementation began prematurely during the design interview. See the [compact-map record](docs/validation/compact-map.md) for that decision, device evidence and limitations.

Published specification: https://github.com/awstephan/freedar/issues/1

The compact-map and startup-navigation revisions below supersede the original
presentation decisions in that issue. This local document records the revised
behavior; the published issue has not been updated in this session.

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
- First launch shows the contiguous USA without prompting for location permission. On later cold launches, existing location permission triggers one fresh foreground fix; remembered center/zoom is the fallback. Rotation and foreground return preserve the current camera.
- Compact controls are always visible. Top-left: Info; top-right: Locate and Search; bottom-left: Settings; bottom-right: a thin timeline and Play/Pause, with Latest nearby when not following latest.
- Frame time is a small tag above the timeline, positioned at the displayed frame. Show it during playback/scrubbing and when paused on an older image; hide it when paused at the latest image.
- Info turns red and adds a warning badge for stale data or update failures; its accessible description reports the condition. Tapping opens details, Retry/Refresh, coverage explanation, legend and linked source credits. Historical selection alone does not trigger a stale-feed warning.
- No persistent status/coverage/credit cards. Attribution lives inside Info, as requested by the owner; this conflicts with CARTO's currently published visible-credit requirement (see VALIDATION.md).
- Dark basemap uses charcoal land (#333333), darker water (#222222), and readable roads/labels. Radar colors and opacity semantics are unchanged.

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
- Refresh failures retain previously loaded imagery and flag the Info icon. Frame time, newest-feed age, failure details and Retry are available in Info.
- With no radar loaded, keep the base map usable and indicate failure through Info, whose panel explicitly says radar unavailable. Do not equate blank imagery with clear weather.
- Show precipitation types only where supported; RainViewer currently documents rain/snow, not a separate mixed category. Legend must match actual provider colors and intensity meaning.
- Legend is part of the scrollable Info panel, which remains until dismissed.

## Navigation and lifecycle

- Locate requests foreground location permission on demand, centers once, and zooms to a useful local-area view showing surrounding weather. Cold launches use the same one-shot flow only when permission is already granted, never prompting automatically.
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
- Controls are always visible; there is no auto-hide preference.
- Playback speed: Slow, Normal (default), Fast; remembered.
- Clear downloaded data: removes cached maps/radar but preserves settings, map position, and bundled search data.
- About: app version, GitHub link, and source credits including GeoNames. The Info panel also contains linked attribution.

## Technical direction

- Kotlin and Jetpack Compose, embedding a dedicated map renderer.
- Target Android 8.0/API 26 and newer, subject to dependency/device validation.
- No Google Play Services or Play Store dependency. Use platform location and Android accessibility APIs.
- Candidate renderer: MapLibre Native. Candidate base map: current CARTO vector maps, subject to current terms and key setup.
- Build the real map/radar foundation as the first implementation slice, validating animation, overzoom, request budgeting, accessibility, and Google-free operation within it. A separate throwaway spike is not required.

See [VALIDATION.md](VALIDATION.md) for evidence, limitations, and unresolved checks.
