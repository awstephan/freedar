package com.freedar

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.SymbolLayer

/** Explicit opt-in: checks the deployed provider contract, never part of offline CI. */
@RunWith(AndroidJUnit4::class)
class LiveCartoTest {
    @Test fun currentCartoLabelsRenderInTheRealApp() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCarto") == "true")
        assertTrue("Build with the developer's CARTO key", BuildConfig.CARTO_API_KEY.isNotBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        val glyphLoads = AtomicInteger()
        val glyphErrors = AtomicInteger()
        lateinit var map: MapLibreMap
        lateinit var view: MapView
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = requireNotNull(findMap(activity.window.decorView))
                view.addOnGlyphsLoadedListener { _, _, _ -> glyphLoads.incrementAndGet() }
                view.addOnGlyphsErrorListener { _, _, _ -> glyphErrors.incrementAndGet() }
                view.getMapAsync { map = it; ready.countDown() }
            }
            assertTrue("Native map initialized", ready.await(30, TimeUnit.SECONDS))
            var styled = false
            val styleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (!styled && System.nanoTime() < styleDeadline) {
                instrumentation.runOnMainSync { styled = map.style?.isFullyLoaded == true }
                if (!styled) Thread.sleep(250)
            }
            assertTrue("CARTO style loaded", styled)
            // The all-state overview is below CARTO's country-label minzoom (2).
            // A regional view must show real place labels, not merely coastline geometry.
            instrumentation.runOnMainSync {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.73, -73.94), 7.0))
            }
            var labels = emptyList<String>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (labels.isEmpty() && System.nanoTime() < deadline) {
                instrumentation.runOnMainSync {
                    val ids = map.style!!.layers.filterIsInstance<SymbolLayer>().map { it.id }
                    labels = map.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                        *ids.toTypedArray()).mapNotNull { feature ->
                        when {
                            feature.hasProperty("name:en") -> feature.getStringProperty("name:en")
                            feature.hasProperty("name") -> feature.getStringProperty("name")
                            else -> null
                        }
                    }
                }
                if (labels.isEmpty()) Thread.sleep(500)
            }
            assertTrue("Rendered named features from CARTO symbol layers", labels.isNotEmpty())
            assertTrue("Native glyph assets loaded", glyphLoads.get() > 0)
            assertEquals("No native glyph failures", 0, glyphErrors.get())
            Thread.sleep(1000) // Allow label placement fades before the manual screenshot review.
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            val destination = File(instrumentation.targetContext.cacheDir, "live-carto.png")
            destination.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            println("CARTO native glyph loads=${glyphLoads.get()}, rendered labels=${labels.distinct().take(20)}")
        }
    }

    private fun findMap(view: View): MapView? {
        if (view is MapView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findMap(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
