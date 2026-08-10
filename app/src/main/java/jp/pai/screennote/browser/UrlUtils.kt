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

    private fun pathOf(url: String): String {
        val withoutScheme = SCHEME.find(url)?.let { url.substring(it.value.length) } ?: url
        val authorityStripped = withoutScheme.substringAfter('/', missingDelimiterValue = "")
        return authorityStripped.substringBefore('#').substringBefore('?')
    }
}
