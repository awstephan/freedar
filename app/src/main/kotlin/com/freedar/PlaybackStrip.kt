package com.freedar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.freedar.radar.RadarFrame
import com.freedar.radar.RadarState
import com.freedar.radar.TimelineMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackStrip(state: RadarState, frameLabel: (RadarFrame) -> String,
    togglePlayback: () -> Unit, select: (RadarFrame) -> Unit, latest: () -> Unit) {
    val frames = state.frames
    if (frames.isEmpty()) return
    val span = (frames.size - 1).coerceAtLeast(1).toFloat()
    val selected = state.selection ?: state.frame ?: frames.last()
    val selectedIndex = frames.indexOf(selected).coerceAtLeast(0)
    val displayed = state.frame
    val displayedFraction = frames.indexOf(displayed).coerceAtLeast(0) / span
    val interactions = remember { MutableInteractionSource() }
    val dragging by interactions.collectIsDraggedAsState()
    val showTime = displayed != null && (dragging || state.mode == TimelineMode.PLAYING || displayed != frames.last())
    val ink = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.surface.copy(alpha = .8f)
    val gap = MaterialTheme.colorScheme.error
    val mode = when (state.mode) {
        TimelineMode.FOLLOWING -> "Following latest"
        TimelineMode.SELECTED -> "Selected frame"
        TimelineMode.PLAYING -> "Playing"
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            if (showTime) {
                // Keep the label centered over the displayed frame, clamped at the track edges.
                Layout(content = {
                    Surface(shape = MaterialTheme.shapes.small, color = track, contentColor = ink) {
                        Text(frameLabel(requireNotNull(displayed)), Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            .semantics { contentDescription = "Frame time" },
                            style = MaterialTheme.typography.labelSmall)
                    }
                }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
                    val label = measurables.single().measure(constraints.copy(minWidth = 0))
                    layout(constraints.maxWidth, label.height) {
                        val x = (12.dp.toPx() + (constraints.maxWidth - 24.dp.toPx()) * displayedFraction - label.width / 2)
                            .roundToInt().coerceIn(0, (constraints.maxWidth - label.width).coerceAtLeast(0))
                        label.placeRelative(x, 0)
                    }
                }
            }
            Slider(value = selectedIndex.toFloat(), onValueChange = {
                select(frames[it.roundToInt().coerceIn(frames.indices)])
            }, valueRange = 0f..span, steps = (frames.size - 2).coerceAtLeast(0), enabled = frames.size > 1,
                interactionSource = interactions,
                thumb = { Box(Modifier.size(16.dp).background(ink, CircleShape)) },
                track = {
                    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                        drawLine(track, Offset(0f, center.y), Offset(size.width, center.y), 6.dp.toPx())
                        drawLine(ink.copy(alpha = .65f), Offset(0f, center.y), Offset(size.width, center.y), 2.dp.toPx())
                        frames.indices.forEach { index ->
                            drawCircle(if (frames[index] in state.unavailable) gap else ink, 2.dp.toPx(),
                                Offset(index / span * size.width, center.y))
                        }
                        state.gaps.forEach { (from, to) ->
                            drawLine(gap, Offset(frames.indexOf(from) / span * size.width, center.y),
                                Offset(frames.indexOf(to) / span * size.width, center.y), 3.dp.toPx())
                        }
                    }
                }, modifier = Modifier.fillMaxWidth().height(48.dp).semantics {
                    contentDescription = "Radar history; scrubbing pauses playback"
                    stateDescription = "Frame ${selectedIndex + 1} of ${frames.size}, ${frameLabel(selected)}. $mode. " +
                        "${state.unavailable.size} unavailable frames, ${state.gaps.size} missing intervals"
                })
        }
        // Reserve the slot so starting a scrub never resizes the track beneath the finger.
        Box(Modifier.size(48.dp)) {
            if (state.mode != TimelineMode.FOLLOWING) MapIconButton(MapIcon.LATEST, "Latest", latest)
        }
        MapIconButton(if (state.mode == TimelineMode.PLAYING) MapIcon.PAUSE else MapIcon.PLAY,
            if (state.mode == TimelineMode.PLAYING) "Pause" else "Play", togglePlayback)
    }
}
