package com.freedar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saved above the orientation-dependent layout so rotation cannot restore an older query. */
class PlaceSearchState {
    var query by mutableStateOf("")
    var submitted by mutableStateOf<String?>(null)
    var submission by mutableIntStateOf(0)

    companion object {
        val Saver = listSaver<PlaceSearchState, String>(
            save = { listOf(it.query, it.submitted.orEmpty(), it.submission.toString()) },
            restore = { values -> PlaceSearchState().apply {
                query = values[0]
                submission = values[2].toInt()
                submitted = values[1].takeIf { submission > 0 }
            } },
        )
    }
}

@Composable
fun PlaceSearchSurface(state: PlaceSearchState, select: (Place) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val keyboard = LocalSoftwareKeyboardController.current
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var message by remember { mutableStateOf("Enter a city or town, then press Search on the keyboard.") }
    var loading by remember { mutableStateOf(false) }
    fun close() { keyboard?.hide(); dismiss() }
    BackHandler { close() }
    LaunchedEffect(state.submission) {
        val text = state.submitted ?: return@LaunchedEffect
        loading = true
        results = emptyList()
        message = "Searching offline…"
        try {
            results = withContext(Dispatchers.IO) { PlaceSearch(context).use { it.search(text) } }
            message = if (results.isEmpty()) "No places found. GeoNames cities500 covers many cities and towns, " +
                "not every settlement, address or business. Try another spelling or close search to explore the map manually."
            else "${results.size} results for “$text”" + if (results.size == PlaceSearch.RESULT_LIMIT)
                " (first ${PlaceSearch.RESULT_LIMIT}; refine the name)." else "."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "Offline place database unavailable. Submit again to retry, or close search to explore the map manually."
        } finally { loading = false }
    }
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Row {
                Text("Places", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = ::close) { Text("Close search") }
            }
            OutlinedTextField(state.query, onValueChange = { state.query = it.take(200) },
                label = { Text("City or town") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    state.submitted = state.query
                    state.submission++
                    keyboard?.hide()
                }))
            Column {
                Text(message, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                results.forEach { place ->
                    TextButton(onClick = { select(place); close() }, enabled = !loading,
                        modifier = Modifier.fillMaxWidth()) {
                        Text("${place.name} · ${place.region}, ${place.country} (${place.countryCode})",
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                Text(buildAnnotatedString {
                    append("Offline GeoNames cities500 · ")
                    withLink(LinkAnnotation.Url("https://creativecommons.org/licenses/by/4.0/")) { append("CC BY 4.0") }
                    append(" · names normalized for search.")
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
