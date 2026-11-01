package com.freedar

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Provider-gated device reuse; no downloads are initiated by the store. */
class ImageryRequests(private val store: DownloadedImagery,
    private val permitted: (HttpUrl) -> Boolean = { it.isCarto() },
    private val radar: RadarArchive? = null) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val radarExpiry = radar?.retentionDeadlineForRegisteredTile(request.url)
        val allowed = request.method == "GET" && (permitted(request.url) || radarExpiry != null) &&
            request.header("Accept-Encoding") == null
        val generation = store.generation()
        // Immutable radar frame URLs reuse bytes before request budgets or network I/O.
        if (allowed && radarExpiry != null && !chain.call().isCanceled()) retained(request)?.let { return it }
        if (allowed && radarExpiry != null && radar?.retainedOnly == true) {
            throw java.io.IOException("Radar viewport was not downloaded. Missing imagery does not mean clear weather.")
        }
        // Online requests always consult the provider. On transport failure, recently
        // viewed bytes may supply geographic context, never a claim of current weather.
        val response = try { chain.proceed(request) } catch (failure: java.io.IOException) {
            if (chain.call().isCanceled()) throw failure
            if (allowed) retained(request)?.let { return it }
            throw failure
        }
        val retainable = (if (radarExpiry != null) response.request.url == request.url else permitted(response.request.url)) &&
            !response.cacheControl.noStore && !response.cacheControl.noCache && !response.cacheControl.mustRevalidate &&
            response.headers.values("Vary").flatMap { it.split(',') }
                .all { it.trim().equals("Accept-Encoding", ignoreCase = true) }
        if (allowed && !retainable) {
            try { store.remove(request.url.toString()) }
            catch (failure: java.io.IOException) { response.close(); throw failure }
        }
        if (allowed && retainable && response.code == 200) {
            // Bound individual buffering as well as aggregate disk usage. Larger responses
            // remain streaming/network-only. peekBody leaves the caller's body intact.
            val bytes = try { response.peekBody(8_000_001).bytes() }
                catch (failure: java.io.IOException) {
                    response.close()
                    if (!chain.call().isCanceled()) retained(request)?.let { return it }
                    throw failure
                }
            if (bytes.size <= 8_000_000) {
                try { store.put(request.url.toString(), response.body?.contentType()?.toString().orEmpty(), bytes, generation,
                    expiresAtMillis = radarExpiry ?: Long.MAX_VALUE) }
                catch (_: java.io.IOException) { /* Disk full: network imagery is still usable. */ }
            }
        }
        // Native ambient storage must never independently retain these or radar responses.
        return response.newBuilder().header("Cache-Control", "no-store").build()
    }

    private fun retained(request: Request): Response? = store.get(request.url.toString())?.let {
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("Device imagery").header("Cache-Control", "no-store")
            .body(it.bytes.toResponseBody(it.contentType.toMediaTypeOrNull())).build()
    }
}

internal fun HttpUrl.isCarto() = isHttps && (host == "cartocdn.com" || host.endsWith(".cartocdn.com"))
