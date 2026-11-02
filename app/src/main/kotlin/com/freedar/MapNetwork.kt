package com.freedar

import android.content.Context
import com.freedar.radar.RadarTileRequests
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

/** Direct device requests, bounded concurrency, provider-gated device storage. */
fun configureMapNetwork(context: Context, cartoKey: String, imagery: DownloadedImagery, radar: RadarArchive,
    radarTiles: RadarTileRequests): OkHttpClient {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor(CartoRequests(cartoKey))
        .addInterceptor(ImageryRequests(imagery, radar = radar))
        .addInterceptor(radarTiles)
        .build()
    client.dispatcher.maxRequests = 8
    client.dispatcher.maxRequestsPerHost = 4
    HttpRequestUtil.setOkHttpClient(client)
    HttpRequestUtil.setLogEnabled(false)
    // Only the provider-gated store may persist imagery or native caching would bypass
    // retention and aggregate capacity. Current viewport textures remain in memory.
    OfflineManager.getInstance(context).setMaximumAmbientCacheSize(0, null)
    return client
}

private class CartoRequests(private val cartoKey: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val carto = original.url.isCarto()
        val request = if (carto) original.newBuilder().url(original.url.newBuilder()
            .setQueryParameter("key", cartoKey).build()).build() else original
        return chain.proceed(request)
    }
}
