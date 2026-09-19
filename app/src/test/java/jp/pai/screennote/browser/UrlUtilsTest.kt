package jp.pai.screennote.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun `bare host gets an https scheme`() {
        assertEquals("https://example.com", UrlUtils.normalizeInput("example.com"))
    }

    @Test
    fun `full url is left alone`() {
        assertEquals("http://example.com/a", UrlUtils.normalizeInput("http://example.com/a"))
    }

    @Test
    fun `free text becomes a search`() {
        assertTrue(UrlUtils.normalizeInput("hello world").startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `pdf is detected from the path only`() {
        assertTrue(UrlUtils.looksLikePdf("https://example.com/doc.pdf"))
        assertTrue(UrlUtils.looksLikePdf("https://example.com/doc.PDF?x=1"))
        assertFalse(UrlUtils.looksLikePdf("https://example.com/search?q=doc.pdf"))
        assertFalse(UrlUtils.looksLikePdf("https://example.com/page"))
    }

    @Test
    fun `pdf mime type tolerates parameters`() {
        assertTrue(UrlUtils.isPdfMimeType("application/pdf"))
        assertTrue(UrlUtils.isPdfMimeType("application/pdf; charset=binary"))
        assertFalse(UrlUtils.isPdfMimeType("text/html"))
        assertFalse(UrlUtils.isPdfMimeType(null))
    }

    @Test
    fun `host is read without the scheme, port, credentials or path`() {
        assertEquals("www.digikey.jp", UrlUtils.hostOf("https://www.digikey.jp/products/en"))
        assertEquals("example.com", UrlUtils.hostOf("https://example.com:8443/a?b=c"))
        assertEquals("example.com", UrlUtils.hostOf("https://user:pw@example.com/"))
        assertEquals("example.com", UrlUtils.hostOf("https://EXAMPLE.com"))
    }

    @Test
    fun `a URL with no scheme has no host to read`() {
        assertNull(UrlUtils.hostOf("example.com/page"))
        assertNull(UrlUtils.hostOf(""))
        assertNull(UrlUtils.hostOf(null))
    }

    @Test
    fun `a page is told apart from what it loads`() {
        // The case this exists for: one third-party certificate the platform does not know
        // must not be reported as the page itself having failed.
        assertFalse(
            UrlUtils.sameHost("https://www.digikey.com/x.png", "https://www.digikey.jp/")
        )
        assertTrue(
            UrlUtils.sameHost("https://www.digikey.jp/x.png", "https://www.digikey.jp/products")
        )
    }

    @Test
    fun `an unknown host matches nothing, including another unknown one`() {
        assertFalse(UrlUtils.sameHost(null, "https://example.com/"))
        assertFalse(UrlUtils.sameHost("https://example.com/", null))
        assertFalse(UrlUtils.sameHost("not a url", "also not a url"))
    }
}
