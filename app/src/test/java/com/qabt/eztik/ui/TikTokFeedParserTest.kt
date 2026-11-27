package com.qabt.eztik.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokFeedParserTest {
    @Test fun prefersProvenAwemePlaybackURL() {
        val raw = """
        {"itemList":[{"id":"1","desc":"Video","video":{
            "playAddr":"https://v16-webapp-prime.tiktok.com/a.mp4",
            "bitrateInfo":[{"PlayAddr":{"UrlList":[
                "https://www.tiktok.com/aweme/v1/play/?item=1",
                "https://v16-webapp-prime.tiktok.com/b.mp4"
            ]}}]
        }}]}
        """.trimIndent()
        val parsed = TikTokFeedParser.parse(raw)
        assertEquals(1, parsed.size)
        assertTrue(parsed.single().mediaUrl.contains("/aweme/v1/play/"))
    }

    @Test fun rejectsUnsafeHostsAndPhotoposts() {
        val raw = """
        {"itemList":[
            {"id":"a","video":{"playAddr":"https://tiktok.com.attacker.org/v.mp4"}},
            {"id":"b","video":{"playAddr":"http://www.tiktok.com/v.mp4"}},
            {"id":"c","imagePost":{"images":[{"url":"abc"}]},
                "video":{"playAddr":"https://www.tiktok.com/aweme/v1/play/?item=3"}},
            {"id":"d","video":{"playAddr":"https://www.tiktok.com/aweme/v1/play/?item=4"}}
        ]}
        """.trimIndent()
        assertEquals(listOf("d"), TikTokFeedParser.parse(raw).map { it.id })
    }

    @Test fun emptyFeedDoesNotInventClips() {
        assertTrue(TikTokFeedParser.parse("{}").isEmpty())
    }
}
