package com.freedar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Exact RGBA samples from RainViewer's Universal Blue table; see docs/radar-appearance.md. */
@Composable
fun LegendContent() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Universal Blue", style = MaterialTheme.typography.titleMedium)
            Text("Reflectivity (dBZ): weaker → stronger radar echoes. Not a rainfall or snowfall rate.",
                style = MaterialTheme.typography.bodySmall)
            Samples("Rain", listOf(0xFF88DDEE, 0xFF0077AA, 0xFFFFEE00, 0xFFFF4400, 0xFFFFAAFF, 0xFFFFFFFF, 0xFF00FF00))
            Samples("Snow", listOf(0xFF9FDFFF, 0xFF5F9FFF, 0xFF3F7FFF, 0xFF1F5FFF, 0xFF003FFF, 0xFF001FFF, 0xFF0000FF))
            Text("Samples at 15, 25, 35, 45, 55, 65 and 75 dBZ. Weaker echoes may be faint. " +
                "Snow uses separate provider colors; type and intensity are estimates, not ground conditions. " +
                "Map opacity and smoothing change the displayed colors. Missing imagery is not evidence of dry weather.",
                style = MaterialTheme.typography.bodySmall)
            SourceLink("RainViewer color table", "https://www.rainviewer.com/api/color-schemes.html")
        }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Samples(type: String, colors: List<Long>) {
    Text("$type · dBZ samples", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEachIndexed { index, color ->
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$type, ${15 + index * 10} dBZ" }) {
                Box(Modifier.size(28.dp, 16.dp).background(Color(color)).border(1.dp, MaterialTheme.colorScheme.outline))
                Text("${15 + index * 10}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
