package com.mofy.app.watchtogether.webrtc

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WtWebRtcConfigTest {
    @Test
    fun `STUN_URLS non-empty and all start with stun`() {
        assertTrue(WtWebRtcConfig.STUN_URLS.isNotEmpty())
        WtWebRtcConfig.STUN_URLS.forEach { url ->
            assertTrue(url.startsWith("stun:"), url)
        }
    }
}
