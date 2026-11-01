package com.freedar

import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ImageryRequestsTest {
    @Test fun `connection loss after response headers can still reuse retained imagery`() {
        val root = Files.createTempDirectory("imagery-http").toFile()
        MockWebServer().use { server ->
            try {
                val client = OkHttpClient.Builder().addInterceptor(ImageryRequests(
                    DownloadedImagery(root), permitted = { true })).build()
                val request = Request.Builder().url(server.url("/tile")).build()
                server.enqueue(MockResponse().setBody("retained"))
                client.newCall(request).execute().close()
                server.enqueue(MockResponse().setBody("x".repeat(10000))
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                client.newCall(request).execute().use { assertEquals("retained", it.body!!.string()) }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun `new provider restriction revokes older offline copy`() {
        val root = Files.createTempDirectory("imagery-http").toFile()
        val server = MockWebServer()
        server.start()
        try {
            val store = DownloadedImagery(root)
            val client = OkHttpClient.Builder().addInterceptor(ImageryRequests(store, permitted = { true })).build()
            val request = Request.Builder().url(server.url("/tile")).build()
            server.enqueue(MockResponse().setBody("old"))
            client.newCall(request).execute().close()
            assertTrue(store.size() > 0)
            server.enqueue(MockResponse().setHeader("Cache-Control", "no-store").setBody("restricted"))
            client.newCall(request).execute().close()
            assertEquals(0L, store.size())
            server.shutdown()
            assertThrows(java.io.IOException::class.java) { client.newCall(request).execute() }
        } finally { server.close(); root.deleteRecursively() }
    }

    @Test fun `provider no-store and unknown variation never become offline downloads`() {
        val root = Files.createTempDirectory("imagery-http").toFile()
        val server = MockWebServer()
        server.start()
        try {
            val store = DownloadedImagery(root)
            val client = OkHttpClient.Builder().addInterceptor(ImageryRequests(store, permitted = { true })).build()
            for ((header, value) in listOf("Cache-Control" to "no-store", "Vary" to "Authorization")) {
                server.enqueue(MockResponse().setHeader(header, value).setBody("not retained"))
                client.newCall(Request.Builder().url(server.url("/$header")).build()).execute().close()
            }
            assertEquals(0L, store.size())
        } finally { server.close(); root.deleteRecursively() }
    }

    @Test fun `only approved provider resources survive client recreation offline`() {
        val root = Files.createTempDirectory("imagery-http").toFile()
        val server = MockWebServer()
        server.start()
        try {
            fun client() = OkHttpClient.Builder().addInterceptor(ImageryRequests(
                DownloadedImagery(root), permitted = { it.encodedPath.startsWith("/carto/") })).build()
            val carto = Request.Builder().url(server.url("/carto/style.json")).build()
            val radar = Request.Builder().url(server.url("/radar/123.png")).build()
            server.enqueue(MockResponse().setBody("map").setHeader("Content-Type", "application/json")
                .setHeader("Vary", "Accept-Encoding"))
            server.enqueue(MockResponse().setBody("radar"))
            client().newCall(carto).execute().use { assertEquals("map", it.body!!.string()) }
            client().newCall(radar).execute().close()
            server.shutdown()
            client().newCall(carto).execute().use {
                assertEquals("map", it.body!!.string())
                assertEquals("no-store", it.header("Cache-Control"))
            }
            assertThrows(java.io.IOException::class.java) { client().newCall(radar).execute() }
        } finally { server.close(); root.deleteRecursively() }
    }
}
