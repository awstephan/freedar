package com.freedar.radar

import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** RainViewer's manifest is the authority for both the host and opaque frame path. */
class RainViewer(
    private val client: OkHttpClient,
    private val manifestUrl: HttpUrl = "https://api.rainviewer.com/public/weather-maps.json".toHttpUrl(),
) : RadarProvider {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun latest(): RadarFrame = history().last()

    override suspend fun history(): List<RadarFrame> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(manifestUrl).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val frame = response.use { parse(it) }
                    if (continuation.isActive) continuation.resume(frame)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun parse(response: Response): List<RadarFrame> {
        check(response.isSuccessful) { "Radar service returned HTTP ${response.code}" }
        val manifest = json.decodeFromString<Manifest>(requireNotNull(response.body).string())
        require(manifest.radar.past.isNotEmpty()) { "No historical radar frames" }
        val host = manifest.host.toHttpUrl()
        require(host.isHttps && host.encodedPath == "/" && host.query == null &&
            host.fragment == null && host.username.isEmpty() && host.password.isEmpty())
        return manifest.radar.past.map { frame ->
            require(frame.time > 0 && frame.path.startsWith("/") && !frame.path.startsWith("//") &&
                !frame.path.contains("..") && frame.path.none { it in "?#\\{}" || it.isWhitespace() })
            RadarFrame(Instant.ofEpochSecond(frame.time),
                "${manifest.host.trimEnd('/')}${frame.path}/256/{z}/{x}/{y}/2/1_1.png")
        }.distinctBy { it.time }.sortedBy { it.time }
    }
}

@Serializable private data class Manifest(val host: String, val radar: Radar)
@Serializable private data class Radar(val past: List<Frame>)
@Serializable private data class Frame(val time: Long, val path: String)
