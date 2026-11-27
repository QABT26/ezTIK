package com.qabt.eztik.ui

import org.json.JSONObject
import java.net.URI

/** Anonymous TikTok guest-feed items; source URLs must belong to TikTok-owned hosts. */
internal data class TikTokFeedClip(val id: String, val title: String, val mediaUrl: String)

internal object TikTokFeedParser {
    private val hosts = listOf(
        "tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com",
        "byteoversea.com", "ibytedtos.com", "musical.ly"
    )

    internal fun allowedMediaUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return hosts.any { host == it || host.endsWith(".$it") }
    }

    private fun playableAddresses(video: JSONObject): Sequence<String> = sequence {
        // Stage 3: bitrateInfo UrlList /aweme/v1/play/ yielded HTTP 206 video/mp4;
        // bare playAddr on the same videos gave CDN HTTP 403.
        val candidates = mutableListOf<String>()
        val bitrates = video.optJSONArray("bitrateInfo")
        if (bitrates != null) for (index in 0 until minOf(bitrates.length(), 6)) {
            val entry = bitrates.optJSONObject(index) ?: continue
            val addr = entry.optJSONObject("PlayAddr") ?: entry.optJSONObject("playAddr")
            val list = addr?.optJSONArray("UrlList") ?: addr?.optJSONArray("urlList")
            if (list != null) for (j in 0 until minOf(list.length(), 4)) {
                candidates.add(list.optString(j))
            }
        }
        val play = video.opt("playAddr")
        when (play) {
            is String -> candidates.add(play)
            is JSONObject -> {
                val list = play.optJSONArray("urlList") ?: play.optJSONArray("UrlList")
                if (list != null) for (index in 0 until minOf(list.length(), 4)) {
                    candidates.add(list.optString(index))
                }
            }
        }
        // Prefer working TikTok media-play endpoints, not prime-CDN URLs.
        for (url in candidates.distinct().sortedWith(compareBy({
            !runCatching { URI(it).path.orEmpty().contains("/aweme/v1/play/") }.getOrDefault(false)
        }, { it.contains("webapp-prime") }))) {
            if (allowedMediaUrl(url)) yield(url)
        }
    }

    fun parse(rawJson: String): List<TikTokFeedClip> {
        require(rawJson.length <= 4_000_000) { "Feed response too large" }
        val root = JSONObject(rawJson)
        val list = root.optJSONArray("itemList") ?: root.optJSONArray("item_list")
            ?: return emptyList()
        val result = mutableListOf<TikTokFeedClip>()
        val seen = HashSet<String>()
        for (i in 0 until minOf(list.length(), 50)) {
            val item = list.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isBlank() || id in seen) continue
            if ((item.optJSONObject("imagePost")?.optJSONArray("images")?.length() ?: 0) > 0) continue
            val video = item.optJSONObject("video") ?: continue
            val url = playableAddresses(video).firstOrNull() ?: continue
            seen.add(id)
            result.add(TikTokFeedClip(
                id,
                item.optString("desc").ifBlank { "TikTok" }.take(160),
                url
            ))
        }
        return result
    }
}
