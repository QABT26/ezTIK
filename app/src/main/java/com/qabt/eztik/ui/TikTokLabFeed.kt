package com.qabt.eztik.ui

import com.qabt.eztik.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Debug uses the verified isolated local feed backend via ADB reverse.
 * Release fails closed without a configured HTTPS gateway; no auth cookies passed.
 */
internal object TikTokLabFeed {
    private const val DEBUG_BASE = "http://127.0.0.1:39117"

    internal fun endpoint(debug: Boolean, baseUrl: String): String {
        val origin = baseUrl.trim().trimEnd('/')
        if (origin.isNotEmpty()) {
            val url = origin.toHttpUrlOrNull()
                ?: throw IllegalStateException("Invalid feed gateway URL")
            check(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.port == 443 && url.encodedPath == "/" && url.query == null &&
                url.fragment == null && !url.host.endsWith(".local")) {
                "HTTPS origin required for TikTok feed"
            }
            return "$origin/v1/guest-feed"
        }
        check(debug) { "HTTPS guest-feed gateway not configured" }
        return "$DEBUG_BASE/tiktok/trending?count=16"
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(32, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun load(): List<TikTokFeedClip> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint(BuildConfig.DEBUG, BuildConfig.TIKTOK_FEED_BASE_URL))
            .header("Accept", "application/json")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Backend HTTP " + response.code)
            }
            val body = response.body ?: throw IllegalStateException("Empty response")
            val bytes = body.byteStream().use { stream ->
                // Fail closed instead of allocating unbounded JSON on Android.
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 4_000_000) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > 4_000_000) {
                throw IllegalStateException("Feed payload exceeds 4 MB")
            }
            val text = bytes.toString(Charsets.UTF_8)
            val clips = TikTokFeedParser.parse(text)
            if (clips.isEmpty()) {
                throw IllegalStateException("Backend returned no playable clips")
            }
            clips
        }
    }
}
