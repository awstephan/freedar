# Using Freedar

[Back to Freedar](../README.md) · [Detailed behavior](behavior.md)

| Control | Behavior |
| --- | --- |
| **Attributions (©)**, top-left | One linked provider name per line. |
| **Info**, bottom-left, immediately right of Settings | Compact radar status with Frame/feed age and Retry/Refresh, legend, and coverage explanations. A red icon with a warning badge indicates stale/offline/failed data. |
| **Locate**, top-right | Requests permission when needed, obtains one fresh fix and centers at zoom 9. Becomes Cancel while waiting. |
| **Search**, top-right | Searches the bundled city/town database when you submit using the keyboard's Search action. Selecting a result centers at zoom 9. |
| **Settings**, bottom-left | Theme, radar opacity, playback speed, Clear downloaded data and About. |
| **Timeline and Play/Pause**, bottom-right | Scrub to a historical frame or animate available history. Latest returns to newest imagery, paused and following updates. |

Panel icons toggle their panels closed on a second tap; opening another panel replaces the current one.
Icons are 24 dp with 28 dp rounded backgrounds and invisible 48 dp touch targets.

Search selection and successful location reset radar to Latest. Ordinary pan/zoom
preserves the selected time. Cold launches attempt location only if permission is
already granted, with the remembered view as fallback. There is no ongoing tracking
or background radar polling.

A blue point marks your latest successful location fix; a purple point marks the
selected search result. Both have white outlines and stay on the map while you
pan, zoom, rotate, or switch themes. Locate updates the blue point; selecting a
new search result replaces the purple point. Points last for the current session.

The app focuses on historical precipitation radar: forecasts, weather dashboards,
alerts, notifications, satellite imagery and provider switching are not implemented.
