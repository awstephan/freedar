package com.freedar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp

enum class MapIcon { INFO, LOCATE, SEARCH, SETTINGS, PLAY, PAUSE, LATEST, CLOSE }

/** Small visual footprint with a full 48dp accessible touch target. */
@Composable
fun MapIconButton(icon: MapIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, warning: Boolean = false, status: String? = null) {
    val color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .88f)) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)
            .semantics {
                contentDescription = label
                if (status != null) { stateDescription = status; liveRegion = LiveRegionMode.Polite }
            }) {
            Box {
                Canvas(Modifier.size(24.dp)) {
                    val ink = color.copy(alpha = if (enabled) 1f else .38f)
                    scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                        fun line(x: Float, y: Float, xx: Float, yy: Float) =
                            drawLine(ink, Offset(x, y), Offset(xx, yy), 2f)
                        when (icon) {
                            MapIcon.INFO -> {
                                drawCircle(ink, 9f, Offset(12f, 12f), style = Stroke(2f))
                                drawCircle(ink, 1.3f, Offset(12f, 7f))
                                line(12f, 11f, 12f, 18f)
                            }
                            MapIcon.LOCATE -> {
                                drawCircle(ink, 7f, Offset(12f, 12f), style = Stroke(2f))
                                drawCircle(ink, 2f, Offset(12f, 12f))
                                line(12f, 1f, 12f, 5f); line(12f, 19f, 12f, 23f)
                                line(1f, 12f, 5f, 12f); line(19f, 12f, 23f, 12f)
                            }
                            MapIcon.SEARCH -> {
                                drawCircle(ink, 6.5f, Offset(10f, 10f), style = Stroke(2f))
                                line(15f, 15f, 22f, 22f)
                            }
                            MapIcon.SETTINGS -> {
                                for ((y, x) in listOf(5f to 8f, 12f to 16f, 19f to 8f)) {
                                    line(2f, y, 22f, y)
                                    drawCircle(ink, 3f, Offset(x, y))
                                }
                            }
                            MapIcon.PLAY -> drawPath(Path().apply {
                                moveTo(6f, 3f); lineTo(21f, 12f); lineTo(6f, 21f); close()
                            }, ink)
                            MapIcon.PAUSE -> {
                                drawRect(ink, Offset(5f, 3f), Size(5f, 18f))
                                drawRect(ink, Offset(14f, 3f), Size(5f, 18f))
                            }
                            MapIcon.LATEST -> {
                                drawPath(Path().apply {
                                    moveTo(3f, 5f); lineTo(15f, 12f); lineTo(3f, 19f); close()
                                }, ink)
                                line(19f, 4f, 19f, 20f)
                            }
                            MapIcon.CLOSE -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
                        }
                    }
                }
                if (warning) Text("!", Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-8).dp),
                    color = color, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
