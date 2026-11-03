package com.freedar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Packaged notices work offline and remain separate from the map controls. */
@Composable
internal fun LicenseNotices(dismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    val paragraphs by produceState<List<String>>(listOf("Loading notices…"), assets) {
        value = withContext(Dispatchers.IO) {
            try {
                assets.open("open-source-notices.txt").bufferedReader().use { it.readText() }
                    .split(Regex("\\n\\s*\\n"))
            } catch (_: IOException) {
                listOf("Could not load bundled notices. Source: github.com/awstephan/freedar")
            }
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Software licenses") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(paragraphs) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close licenses") } },
    )
}
