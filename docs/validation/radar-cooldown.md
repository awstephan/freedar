# Radar cooldown recovery — 2026-11-02

Phone logs showed repeated radar tile HTTP 429s. The old local limiter also generated
429s after 90 requests in a rolling minute, and playback treated every tile failure
as an unavailable frame. During cooldown it could mark the remaining timeline
unavailable rather than wait and retry.

## Change

- Space uncached radar/coverage tile starts 750 ms apart (80/minute).
- Serve cached tiles before pacing/backoff; cancel obsolete queued requests.
- Respect provider Retry-After seconds or HTTP dates; invalid/missing values default
  to one minute. The limiter remains application-scoped across rotation.
- Carry provider cooldown into the timeline as a recoverable condition, retain the
  displayed image/time, show a countdown, and retry automatically.
- Recover paused-frame viewport requests after a pan as well as frame selections.
- Cancel foreground retries on backgrounding and resume pending work on return.

## Verification

- App/radar unit suites and Android lint pass; app and instrumentation APKs build.
- Playback regressions first failed against the old behavior, then passed with
  cooldown recovery, cached-selection cancellation, and foreground restoration.
- Request-policy tests cover pacing, cache bypass, queued cancellation, provider
  backoff, HTTP-date Retry-After, and missing/malformed/negative header fallback.
- `RadarCooldownRenderingTest` passes on the connected Pixel / Android 17. A local
  HTTP fixture injects 429s for a selected frame and a panned paused viewport.
  Native snapshots verify the preceding green image remains during selection
  cooldown and the recovered blue image is rendered afterward.
- Native rendering tests require an awake, unlocked display; attempts while the
  phone was dozing could not complete rendering. The awake run passed.

The provider's shared-IP limit still applies across multiple devices. Pacing reduces
bursts; it does not guarantee that a new viewport's entire history loads immediately.
