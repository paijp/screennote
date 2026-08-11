package jp.pai.screennote.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentsTest {

    private val webViewUa =
        "Mozilla/5.0 (Linux; Android 8.1.0; BBF100-9 Build/OPM1.171019.026; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/138.0.7204.179 " +
            "Mobile Safari/537.36"

    @Test
    fun `desktop agent keeps the webview chrome version`() {
        assertTrue(UserAgents.desktop(webViewUa).contains("Chrome/138.0.7204.179"))
    }

    @Test
    fun `desktop agent drops the mobile markers`() {
        val ua = UserAgents.desktop(webViewUa)
        assertFalse(ua.contains("Android"))
        assertFalse(ua.contains("wv"))
        assertFalse(ua.contains("Mobile"))
    }

    @Test
    fun `desktop agent falls back when no chrome version is present`() {
        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            UserAgents.desktop("something/1.0"),
        )
    }
}
