package com.freedar

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Navigation support and provider footprint are deliberately separate observations. */
class GeographicValidationTest {
    private data class Sample(val query: String, val countryCode: String, val region: String? = null)

    private val samples = listOf(
        "Alabama" to "Montgomery", "Alaska" to "Juneau", "Arizona" to "Phoenix",
        "Arkansas" to "Little Rock", "California" to "Sacramento", "Colorado" to "Denver",
        "Connecticut" to "Hartford", "Delaware" to "Dover", "Florida" to "Tallahassee",
        "Georgia" to "Atlanta", "Hawaii" to "Honolulu", "Idaho" to "Boise",
        "Illinois" to "Springfield", "Indiana" to "Indianapolis", "Iowa" to "Des Moines",
        "Kansas" to "Topeka", "Kentucky" to "Frankfort", "Louisiana" to "Baton Rouge",
        "Maine" to "Augusta", "Maryland" to "Annapolis", "Massachusetts" to "Boston",
        "Michigan" to "Lansing", "Minnesota" to "Saint Paul", "Mississippi" to "Jackson",
        "Missouri" to "Jefferson City", "Montana" to "Helena", "Nebraska" to "Lincoln",
        "Nevada" to "Carson City", "New Hampshire" to "Concord", "New Jersey" to "Trenton",
        "New Mexico" to "Santa Fe", "New York" to "Albany", "North Carolina" to "Raleigh",
        "North Dakota" to "Bismarck", "Ohio" to "Columbus", "Oklahoma" to "Oklahoma City",
        "Oregon" to "Salem", "Pennsylvania" to "Harrisburg", "Rhode Island" to "Providence",
        "South Carolina" to "Columbia", "South Dakota" to "Pierre", "Tennessee" to "Nashville",
        "Texas" to "Austin", "Utah" to "Salt Lake City", "Vermont" to "Montpelier",
        "Virginia" to "Richmond", "Washington" to "Olympia", "West Virginia" to "Charleston",
        "Wisconsin" to "Madison", "Wyoming" to "Cheyenne",
        "Alaska" to "Utqiagvik", "Alaska" to "Unalaska",
    ).map { (region, query) -> Sample(query, "US", region) } + listOf(
        Sample("Tokyo", "JP"), Sample("London", "GB"), Sample("Sydney", "AU"),
    )

    private fun places(): List<Place> = PlaceSearch(InstrumentationRegistry.getInstrumentation().targetContext).use { search ->
        samples.map { sample ->
            search.search(sample.query).firstOrNull {
                it.countryCode == sample.countryCode && (sample.region == null || it.region == sample.region)
            } ?: error("Bundled search missing representative $sample")
        }
    }

    @Test fun allFiftyStatesAndInternationalPlacesCanBeNavigatedOnTheNativeMap() {
        val places = places()
        ActivityScenario.launch(MapTestActivity::class.java).use { scenario ->
            val ready = CountDownLatch(1)
            var failure: Throwable? = null
            lateinit var map: RadarMap
            try {
                scenario.onActivity { activity ->
                    map = RadarMap(activity)
                    activity.setContentView(map.view)
                    map.view.onCreate(null); map.view.onStart(); map.view.onResume()
                    CoroutineScope(Dispatchers.Main).launch {
                        try { map.loadStyle(null) } catch (error: Throwable) { failure = error }
                        finally { ready.countDown() }
                    }
                }
                assertTrue("Native map ready", ready.await(30, TimeUnit.SECONDS))
                failure?.let { throw AssertionError("Map failed", it) }
                for (place in places) scenario.onActivity {
                    map.centerLocal(place.position)
                    val camera = map.nativeMap.cameraPosition
                    assertEquals(place.name, place.position.latitude, camera.target!!.latitude, .001)
                    assertEquals(place.name, place.position.longitude, camera.target!!.longitude, .001)
                    assertEquals(9.0, camera.zoom, .001)
                }
            } finally { scenario.onActivity { map.view.onPause(); map.view.onStop(); map.view.onDestroy() } }
        }
    }

    /** Opt-in, at most 55 unique z5 tiles + one manifest; no retries or live radar claims. */
    @Test fun recordRepresentativeStaticProviderFootprint() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveCoverage") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FreedarApplication
        val client = app.httpClient.newBuilder().retryOnConnectionFailure(false).build()
        fun bytes(url: String): ByteArray = client.newCall(Request.Builder().url(url).build()).execute().use {
            check(it.isSuccessful) { "Survey stopped at HTTP ${it.code}" }
            requireNotNull(it.body).bytes()
        }
        val manifest = JSONObject(String(bytes("https://api.rainviewer.com/public/weather-maps.json")))
        val host = manifest.getString("host")
        check(host.startsWith("https://"))
        val tiles = mutableMapOf<Pair<Int, Int>, android.graphics.Bitmap>()
        try {
            for (place in places()) {
                val p = place.position
                val x = (p.longitude + 180) / 360 * 32
                val radians = Math.toRadians(p.latitude)
                val y = (1 - ln(tan(radians) + 1 / cos(radians)) / Math.PI) / 2 * 32
                val tile = floor(x).toInt() to floor(y).toInt()
                val bitmap = tiles.getOrPut(tile) {
                    Thread.sleep(1100) // Sequential bounded survey, never a throttling experiment.
                    val data = bytes("$host/v2/coverage/0/256/5/${tile.first}/${tile.second}/0/0_0.png")
                    requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size))
                }
                val pixel = bitmap.getPixel(((x - floor(x)) * bitmap.width).toInt(), ((y - floor(y)) * bitmap.height).toInt())
                val footprint = when {
                    Color.alpha(pixel) == 0 -> "inside static footprint; live unknown"
                    Color.red(pixel) == 0 && Color.green(pixel) == 0 && Color.blue(pixel) == 0 -> "outside static footprint"
                    else -> "unknown mask pixel"
                }
                android.util.Log.i("CoverageSurvey", "${place.name}, ${place.region}, ${place.countryCode}: $footprint (z5 ${tile.first}/${tile.second})")
            }
            android.util.Log.i("CoverageSurvey", "55 points; ${tiles.size} unique mask requests + 1 manifest; generated=${manifest.optLong("generated")}")
        } finally { tiles.values.forEach { it.recycle() } }
    }
}
