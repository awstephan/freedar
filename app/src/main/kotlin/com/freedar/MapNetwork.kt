package com.freedar

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

/** Direct device requests, bounded concurrency, no disk cache or automatic retry loops. */
fun configureMapNetwork(context: Context, cartoKey: String): OkHttpClient {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(TileRequests(cartoKey))
        .build()
    client.dispatcher.maxRequests = 8
    client.dispatcher.maxRequestsPerHost = 4
    HttpRequestUtil.setOkHttpClient(client)
    HttpRequestUtil.setLogEnabled(false)
    // Persistence permission is unresolved; enforce zero native ambient disk cache,
    // not just an absent OkHttp cache. Current viewport textures remain in memory.
    OfflineManager.getInstance(context).setMaximumAmbientCacheSize(0, null)
    return client
}

private class TileRequests(private val cartoKey: String) : Interceptor {
    private val requests = ArrayDeque<Long>()
    private var blockedUntil = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val carto = original.url.host == "cartocdn.com" || original.url.host.endsWith(".cartocdn.com")
        val radar = !carto && original.url.encodedPath.endsWith(".png")
        if (radar && !reserve()) {
            return Response.Builder().request(original).protocol(Protocol.HTTP_1_1).code(429)
                .message("Radar request budget exhausted; retry in one minute")
                .body(ByteArray(0).toResponseBody()).build()
        }
        val request = if (carto) original.newBuilder().url(original.url.newBuilder()
            .setQueryParameter("key", cartoKey).build()).build() else original
        val response = chain.proceed(request)
        if (radar && response.code == 429) synchronized(this) {
            val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 3600) ?: 60
            blockedUntil = SystemClock.elapsedRealtime() + seconds * 1000
        }
        return response.newBuilder().header("Cache-Control", "no-store").build()
    }

    @Synchronized private fun reserve(): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (requests.isNotEmpty() && requests.first() <= now - 60_000) requests.removeFirst()
        if (now < blockedUntil || requests.size >= 90) return false
        requests.addLast(now)
        return true
    }
}
