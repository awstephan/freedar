package com.freedar

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class LocationMarkersTest {
    @Test fun distinctPointsSurviveStyleReloadAndSearchReplacesOnlyItsOwnPoint() {
        val finished = CountDownLatch(1)
        var failure: Throwable? = null
        ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
            lateinit var map: RadarMap
            scenario.onActivity { activity ->
                map = RadarMap(activity)
                activity.setContentView(map.view)
                map.view.onCreate(null)
                map.view.onStart()
                map.view.onResume()
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val current = LocalPosition(0.0, -.1)
                        val searched = LocalPosition(0.0, .1)
                        // Fixes may arrive before the asynchronous style load finishes.
                        map.showCurrentLocation(current)
                        map.showSearchedLocation(searched)
                        for (dark in listOf(false, true)) {
                            map.loadStyle(null, dark)
                            map.nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 9.0))
                            assertPointsRendered(map, current, searched)
                        }
                        val replacement = LocalPosition(.1, .1)
                        map.showSearchedLocation(replacement)
                        assertPointsRendered(map, current, replacement)
                        val oldPoint = map.nativeMap.projection.toScreenLocation(LatLng(searched.latitude, searched.longitude))
                        assertTrue("Previous search point is removed",
                            map.nativeMap.queryRenderedFeatures(oldPoint, RadarMap.SEARCHED_LOCATION).isEmpty())
                        assertEquals(current, map.currentLocation)
                        assertEquals(replacement, map.searchedLocation)
                        // On the fallback basemap, radar/coverage must still go below markers.
                        val style = map.nativeMap.style!!
                        val layerIds = style.layers.map { it.id }
                        val insertionIndex = layerIds.indexOf(style.radarReferenceLayer())
                        assertTrue("Radar insertion is below both markers", insertionIndex >= 0 &&
                            insertionIndex <= layerIds.indexOf(RadarMap.SEARCHED_LOCATION) &&
                            insertionIndex <= layerIds.indexOf(RadarMap.CURRENT_LOCATION))
                    } catch (error: Throwable) {
                        failure = error
                    } finally {
                        finished.countDown()
                    }
                }
            }
            try {
                assertTrue("Marker rendering completed", finished.await(90, TimeUnit.SECONDS))
                failure?.let { throw AssertionError("Location marker rendering failed", it) }
            } finally {
                scenario.onActivity { map.view.onPause(); map.view.onStop(); map.view.onDestroy() }
            }
        }
    }

    private suspend fun assertPointsRendered(map: RadarMap, current: LocalPosition, searched: LocalPosition) {
        withTimeout(25_000) {
            while (true) {
                val bitmap = suspendCancellableCoroutine<Bitmap> { continuation ->
                    map.nativeMap.snapshot { if (continuation.isActive) continuation.resume(it) }
                }
                fun colorAt(position: LocalPosition): Int {
                    val point = map.nativeMap.projection.toScreenLocation(LatLng(position.latitude, position.longitude))
                    return bitmap.getPixel(point.x.toInt(), point.y.toInt())
                }
                fun near(actual: Int, expected: Int) =
                    kotlin.math.abs(Color.red(actual) - Color.red(expected)) < 15 &&
                        kotlin.math.abs(Color.green(actual) - Color.green(expected)) < 15 &&
                        kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) < 15
                val rendered = near(colorAt(current), Color.rgb(33, 150, 243)) &&
                    near(colorAt(searched), Color.rgb(168, 85, 247))
                bitmap.recycle()
                if (rendered) return@withTimeout
                delay(100)
            }
        }
    }
}
