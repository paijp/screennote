package jp.pai.screennote.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
