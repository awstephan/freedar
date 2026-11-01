# Remembered view and downloaded imagery

## Provider permission (revalidated 2026-11-01)

- [CARTO Basemaps Terms](https://carto.com/legal/basemap-terms/), updated
  September 29, 2026, §9.c prohibits device/browser caching beyond 30 days,
  retaining content after ceasing service use, bulk extraction, and server-side
  proxying/caching. This implementation only retains responses to ordinary map
  requests on the end user's device. Clear downloaded data removes those copies.
- A keyed Positron style HEAD request returned `Cache-Control: public,
  max-age=86400` and `Vary: Accept-Encoding`. The interceptor supports transparent
  gzip decoding without confusing that variation with user-specific responses.
- **Maintainer confirmation, 2026-11-01:** caching, preloading and distribution have
  been approved with no restrictions beyond RainViewer's published site terms.
  This records the maintainer's confirmation, not independently inspected provider
  correspondence (none was supplied). Public/free upstream data alone is not the
  permission basis. The earlier unresolved-permission status is superseded.
  Radar retention is **two hours from original provider frame time**, never
  extended by download or access.
- [RainViewer API terms](https://www.rainviewer.com/api.html) still describe
  personal/educational use, availability limits and attribution. The maintainer's
  confirmation is subject to those published terms. The [Weather Maps API](https://www.rainviewer.com/api/weather-maps-api.html)
  documents a two-hour past window, opaque host/path values, frame generation times,
  and maximum native zoom 7. Existing request budgets/backoff and attribution remain.
  Permission does not authorize this agent to publish an APK or settle source licensing;
  integrated release decisions remain in #10. There is no bulk history downloader.

## Storage boundary

`DownloadedImagery` owns `cacheDir/downloaded-imagery` exclusively. Its aggregate
limit is **100,000,000 bytes**, including serialized metadata and staging files.
Each downloaded resource is limited to 8 MB for buffering; larger responses remain
network-only. Entries have SHA-256 filenames, original receipt time, an absolute
deletion deadline, content type and decoded bytes. Frame descriptors share the same
budget as map/radar pixels. Capacity eviction removes **LRU imagery first, catalog
descriptors last**, so surviving pixels stay discoverable after restart; metadata is
never outside the aggregate cap. Access updates imagery recency, never the deadline. Replacement
uses an atomic rename. Incomplete staging files are deleted on reopening. The versioned
format reads existing CARTO entries without resetting their original receipt time.

The production base-map boundary permits only HTTPS `cartocdn.com` and subdomains.
Base-map requests consult the provider; on transport failure, a retained resource
may supply offline geographic context. HTTP failures such as 404 are not masked.
`no-store`, `no-cache`, `must-revalidate`, and unsupported `Vary` responses are not
retained, and a newly restrictive response removes any older offline copy.
Redirect destinations must also be permitted. This is bounded reuse of
viewed resources, not an offline-region downloader or a completeness guarantee.

Radar tile URLs must match a frame template received from the validated RainViewer
manifest (including its returned host and opaque path). `RadarArchive` retains each
descriptor with its original frame time and an individual two-hour deadline. The
interceptor does not guess a timestamp from the URL. Unknown templates and static
coverage are not persisted. Immutable radar tiles reuse retained bytes **before**
the in-session request budget/network; missing saved tiles fail immediately when
operating from the retained catalog. Redirected radar bodies are not persisted under
a different frame identity. Neither download nor repeated viewing grants a fresh
two-hour lease. The manifest's generation/download time never labels a radar image.

Base-map expiry at **29 days** leaves a one-day scheduling margin before CARTO's
30-day ceiling. Radar pixels and their frame descriptors expire at frame time plus
two hours. Expiry deletes actual files, including entries never requested again.
Cleanup runs when opening/accessing the store, every 30 seconds while the activity
is started, and in a persisted fifteen-minute Android JobScheduler task (upgraded
from the previous six-hour schedule). Clock rollback
past receipt discards the entry. Android can delay background jobs, and no app can
delete files while its device is powered off or the app is force-stopped; startup
and access enforce expiry before reuse. Background deletion is best effort under
those OS restrictions, not an exact wall-clock deletion guarantee.

OkHttp has no second disk cache. MapLibre's ambient cache remains at **zero**, with
`no-store` responses at the native boundary. The native rendering test reconstructs
the client/store/map, renders retained bytes with the server stopped, then proves
Clear prevents another native map from recovering those bytes.

Clear increments a store generation before deleting files so already-running
downloads cannot repopulate it. New requests after Clear may download again. Visible
textures and the pre-existing bounded in-session radar memory cache are not disk
downloads; currently displayed imagery may remain until the map reloads. Settings
explains this and reports deletion success or failure through a live-region label.

Preferences, remembered view, the bundled GeoNames asset, and
`noBackupFilesDir/place-search` are outside this store. Clear never targets the
whole cache directory or app data directory.

## Launch and radar semantics

`RememberedView` stores center/zoom in separate preferences on camera idle and
activity pause. Missing/invalid preferences select the first-use US overview.
Camera initialization precedes style loading, including offline style failure;
a delayed style cannot reset a later Search or Locate selection.

Each application process has a random session marker. Saved activity state from
that same process preserves the existing configuration/theme recreation behavior.
A fresh process (even with an old task Bundle) restores remembered center/zoom but
starts radar latest paused/following. Elapsed-realtime absence timestamps and old
historical selections are not carried into a new process.

On manifest failure, the app reads still-valid saved descriptors and tries frames
newest-first until one can actually be rendered. This remains a failed refresh, not
a successful live response: “Saved radar · original frame time, not live data” and
refresh-failure notices coexist with connectivity and stale-feed status. Displayed
frame age is separate from the newest known feed age. Successful provider recovery
clears saved-history status. No usable saved imagery yields explicit radar unavailable.

Current in-session imagery also keeps its original frame time; textures can remain
visible after disk deletion. Panning into a region that was not downloaded reports
missing radar tiles. The coverage explanation continues to say blank radar is not
clear weather. Static footprint failures remain independent of radar readiness.
