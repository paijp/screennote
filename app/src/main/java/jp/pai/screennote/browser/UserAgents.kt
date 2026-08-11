package jp.pai.screennote.browser

object UserAgents {

    private val CHROME_VERSION = Regex("Chrome/[\\d.]+")
    private const val FALLBACK_CHROME = "Chrome/120.0.0.0"

    /**
     * Builds a desktop user agent from the WebView's own, so the Chrome version stays truthful and
     * moves with WebView updates. The mobile markers WebView adds — the `; wv)` token, the Android
     * platform string and `Mobile` — are what make sites serve the mobile layout.
     */
    fun desktop(defaultUserAgent: String): String {
        val chrome = CHROME_VERSION.find(defaultUserAgent)?.value ?: FALLBACK_CHROME
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) $chrome Safari/537.36"
    }
}
