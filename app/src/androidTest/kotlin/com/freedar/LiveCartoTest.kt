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
import kotlinx.coroutines.launch

/** Explicit opt-in: checks the deployed provider contract, never part of offline CI. */
@RunWith(AndroidJUnit4::class)
class LiveCartoTest {
    @Test fun heavyRadarPreservesCartoRoadsAndLabelsInBothThemesAndOpacityChangesRenderedPixels() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCarto") == "true")
        assertTrue(BuildConfig.CARTO_API_KEY.isNotBlank())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val png = java.io.ByteArrayOutputStream().apply output@{
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                // RainViewer Universal Blue, 50 dBZ rain: opaque #c10000.
                eraseColor(android.graphics.Color.rgb(193, 0, 0))
                compress(Bitmap.CompressFormat.PNG, 100, this@output)
                recycle()
            }
        }.toByteArray()
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "image/png")
                        .setBody(okio.Buffer().write(png))
            }
            server.start()
            val tileTemplate = "${server.url("/")}heavy/256/{z}/{x}/{y}/2/1_1.png"
            for (theme in listOf("positron", "dark-matter")) {
                ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
                    val complete = CountDownLatch(1)
                    var failure: Throwable? = null
                    lateinit var renderer: RadarMap
                    scenario.onActivity { activity ->
                        renderer = RadarMap(activity)
                        activity.setContentView(renderer.view)
                        renderer.view.onCreate(null); renderer.view.onStart(); renderer.view.onResume()
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            try {
                                renderer.loadStyle("https://basemaps.cartocdn.com/gl/$theme-gl-style/style.json", dark = theme == "dark-matter")
                                renderer.nativeMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(40.73, -73.94), 11.0))
                                renderer.show(com.freedar.radar.RadarFrame(java.time.Instant.now(),
                                    tileTemplate))
                            } catch (error: Throwable) { failure = error }
                            finally { complete.countDown() }
                        }
                    }
                    assertTrue(complete.await(90, TimeUnit.SECONDS))
                    failure?.let { throw AssertionError(it) }
                    val map = renderer.nativeMap
                    instrumentation.runOnMainSync {
                        val layers = map.style!!.layers
                        val radarIndex = layers.indexOfFirst { it.id.startsWith("radar-") }
                        assertTrue("Roads must remain above even opaque radar", layers.indexOfFirst { it.id == "road_sec_fill_noramp" } > radarIndex)
                        assertTrue(layers.filterIsInstance<SymbolLayer>().all { layers.indexOf(it) > radarIndex })
                    }
                    var names = emptyList<String>()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
                    while (names.isEmpty() && System.nanoTime() < deadline) {
                        instrumentation.runOnMainSync {
                            val ids = map.style!!.layers.filterIsInstance<SymbolLayer>().map { it.id }
                            names = map.queryRenderedFeatures(RectF(0f, 0f, renderer.view.width.toFloat(), renderer.view.height.toFloat()),
                                *ids.toTypedArray()).mapNotNull { if (it.hasProperty("name")) it.getStringProperty("name") else null }
                        }
                        if (names.isEmpty()) Thread.sleep(250)
                    }
                    assertTrue("Actual labels rendered in $theme", names.isNotEmpty())
                    val pixels = mutableListOf<Int>()
                    for (opacity in listOf(0f, .65f, 1f)) {
                        instrumentation.runOnMainSync { renderer.opacity(opacity) }
                        Thread.sleep(1000)
                        val captured = CountDownLatch(1)
                        instrumentation.runOnMainSync { map.snapshot { image ->
                            pixels += image.getPixel(image.width / 2, image.height / 2)
                            File(instrumentation.targetContext.cacheDir, "$theme-heavy-${(opacity * 100).toInt()}.png")
                                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            captured.countDown()
                        } }
                        assertTrue(captured.await(15, TimeUnit.SECONDS))
                    }
                    assertEquals("Opacity changes actual native pixels", 3, pixels.distinct().size)
                    println("$theme heavy-radar labels=${names.distinct().take(10)} pixels=$pixels")
                    scenario.onActivity { renderer.view.onPause(); renderer.view.onStop(); renderer.view.onDestroy() }
                }
            }
        }
    }

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
