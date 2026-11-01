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
- [RainViewer API terms](https://www.rainviewer.com/api.html) still describe
  personal/educational use, availability limits and attribution. They do **not**
  establish the requested persistent-cache/preload permission for Freedar's intended
  distribution. **No inquiry has been sent. Permission remains unresolved.**
  Radar manifests, tiles and coverage are not written to this store; no new
  RainViewer preloading capability is enabled. Issue #9 remains partially blocked.

## Storage boundary

`DownloadedImagery` owns `cacheDir/downloaded-imagery` exclusively. Its aggregate
limit is **100,000,000 bytes**, including serialized metadata and staging files.
Each downloaded resource is limited to 8 MB for buffering; larger responses remain
network-only. Entries have SHA-256 filenames, original receipt time, content type
and decoded response bytes. Access updates LRU order, not receipt time. Replacement
uses an atomic rename. Incomplete staging files are deleted on reopening.

The production HTTP boundary permits only HTTPS `cartocdn.com` and subdomains.
Online requests consult the provider; on transport failure, a retained resource
may supply offline geographic context. HTTP failures such as 404 are not masked.
`no-store`, `no-cache`, `must-revalidate`, and unsupported `Vary` responses are not
retained, and a newly restrictive response removes any older offline copy.
Redirect destinations must also be permitted. This is bounded reuse of
viewed resources, not an offline-region downloader or a completeness guarantee.

Expiry at **29 days** deletes the actual file, including entries never requested again,
leaving a one-day scheduling margin before CARTO's 30-day ceiling.
Cleanup runs when opening/accessing the store, every 30 seconds while the activity
is started, and in a persisted six-hour Android JobScheduler task. Clock rollback
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

Persistent offline radar remains **blocked**. Existing in-session retained radar
keeps its provider frame time and separate offline/stale notices; download time
never replaces frame time. Missing imagery remains explicitly unavailable and the
persistent coverage explanation continues to say blank radar is not clear weather.
