package jp.pai.screennote.browser

import java.net.URLEncoder

/**
 * Pure string helpers, deliberately free of `android.net.Uri` so they can be covered by plain JVM
 * unit tests.
 */
object UrlUtils {

    private const val SEARCH_PREFIX = "https://duckduckgo.com/?q="
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /**
     * Turns whatever the user typed into something loadable: a bare host becomes an https URL,
     * anything that does not look like an address becomes a search query.
     */
    fun normalizeInput(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return trimmed
        if (SCHEME.containsMatchIn(trimmed)) return trimmed

        val looksLikeHost = !trimmed.contains(' ') &&
            trimmed.substringBefore('/').substringBefore('?').contains('.')
        return if (looksLikeHost) {
            "https://$trimmed"
        } else {
            SEARCH_PREFIX + URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
        }
    }

    /**
     * Best-effort guess at whether a URL points at a PDF, used to route navigations into the
     * built-in viewer. Only the path is inspected — query strings routinely contain ".pdf".
     */
    fun looksLikePdf(url: String): Boolean =
        pathOf(url).endsWith(".pdf", ignoreCase = true)

    fun isPdfMimeType(mimeType: String?): Boolean =
        mimeType?.substringBefore(';')?.trim()?.equals("application/pdf", ignoreCase = true) == true

    /**
     * The host part of a URL, or null when there is not one to read.
     *
     * Used to tell the page apart from what it loads. WebView reports a certificate failure
     * without saying which frame it belongs to, and a page routinely pulls in a dozen other
     * hosts — so without this a single bad third-party certificate looks exactly like the page
     * itself failing.
     */
    fun hostOf(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        val withoutScheme = SCHEME.find(url)?.let { url.substring(it.value.length) } ?: return null
        val authority = withoutScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.lowercase().ifEmpty { null }
    }

    /** Whether two URLs are served by the same host. Null hosts never match. */
    fun sameHost(a: String?, b: String?): Boolean {
        val hostA = hostOf(a) ?: return false
        return hostA == hostOf(b)
    }

    private fun pathOf(url: String): String {
        val withoutScheme = SCHEME.find(url)?.let { url.substring(it.value.length) } ?: url
        val authorityStripped = withoutScheme.substringAfter('/', missingDelimiterValue = "")
        return authorityStripped.substringBefore('#').substringBefore('?')
    }
}
