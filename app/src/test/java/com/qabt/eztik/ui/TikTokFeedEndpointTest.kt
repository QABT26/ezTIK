package com.qabt.eztik.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TikTokFeedEndpointTest {
    @Test fun defaultDebugStillUsesAdbReverseLab() {
        assertEquals(
            "http://127.0.0.1:39117/tiktok/trending?count=16",
            TikTokLabFeed.endpoint(debug = true, baseUrl = "")
        )
    }

    @Test fun configuredHttpsGatewayUsesNormalizedRoute() {
        assertEquals(
            "https://feed.example.com/v1/guest-feed",
            TikTokLabFeed.endpoint(debug = false, baseUrl = "https://feed.example.com/")
        )
    }

    @Test fun releaseWithNoEndpointFailsClosed() {
        assertThrows(IllegalStateException::class.java) {
            TikTokLabFeed.endpoint(debug = false, baseUrl = "")
        }
    }

    @Test fun refusesInsecureOrCredentialedEndpoints() {
        for (url in listOf(
            "http://feed.example.com",
            "https://user:pass@feed.example.com",
            "https://feed.example.com:8080",
            "https://feed.example.com/unsafe",
            "https://feed.example.com?key=1"
        )) {
            assertThrows(IllegalStateException::class.java) {
                TikTokLabFeed.endpoint(debug = true, baseUrl = url)
            }
        }
    }
}
