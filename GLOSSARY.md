# Weather Radar

Language for a map-focused precipitation viewer.

## Language

**Basemap**:
The geographic background showing land, water, roads and place names beneath weather imagery.

**Radar layer**:
Weather-radar imagery showing estimated precipitation location and intensity over the map. Radar measurements do not guarantee precipitation is reaching the ground.
_Avoid_: Satellite layer

**Observation time**:
The time represented by a radar observation, distinct from when the app downloads or displays it.
_Avoid_: Download time, live time

**Radar timeline**:
The sequence of available radar frames ordered by frame time, ending at the latest available frame.

**Radar frame**:
A precipitation image for a particular frame time, potentially composed from multiple radar observations taken at different times.

**Frame time**:
The time assigned to a radar image by its provider. For a composite image, this is not necessarily the observation time of every contributing radar.
_Avoid_: Live time, download time

**Stale radar feed**:
A radar feed whose newest available frame exceeds the acceptable freshness age. Viewing an intentionally selected older frame does not itself make the feed stale.

**Refresh failure**:
An unsuccessful attempt to retrieve updated radar data. A refresh failure is distinct from staleness: previously loaded data may still be recent.

**Satellite imagery**:
Weather imagery showing clouds as observed from satellites, distinct from ground photography and radar precipitation estimates.
