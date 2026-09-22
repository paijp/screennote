package jp.pai.screennote.browser

import android.webkit.WebView
import jp.pai.screennote.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Moving between pages, as something the app does rather than something injected JavaScript does.
 *
 * This exists because injecting `location.href` is not a way to navigate — it is a way to *ask the
 * current page* to navigate, and a page is free to refuse. Two ordinary pages already have:
 *
 * - a document served with `Content-Security-Policy: sandbox` (GitHub's raw file host) has no
 *   JavaScript context at all, so nothing injected runs, including the escape route;
 * - a URL that resolves to a PDF opens the built-in viewer, leaving the WebView on the page
 *   before it, which then looks from the outside like a navigation that did nothing.
 *
 * Both left the browser somewhere an agent could not get out of, and both needed a person to
 * press Back. Going through `WebView.loadUrl` instead is immune to what the page thinks.
 *
 * Navigating also has to answer with the state it *arrived* at. An eval that navigates destroys
 * its own result — the value it would return lives on the page being replaced — so a caller that
 * navigates and then asks separately is racing the load it just started.
 */
class Navigation(
    private val webView: WebView,
    /** How the app loads a URL: the same path the address bar takes, PDF routing included. */
    private val load: (String) -> Unit,
) {

    private var waiter: CompletableDeferred<String>? = null

    /** Called by the WebViewClient when a main-frame load finishes. */
    fun onPageFinished(url: String) {
        waiter?.complete(url)
        waiter = null
    }

    suspend fun navigate(url: String): JSONObject {
        val target = UrlUtils.normalizeInput(url)
        if (target.isEmpty()) {
            return state().put("error", "bad_url").put("message", "No address was given.")
        }
        // The viewer is a separate screen, so no page load follows and waiting would only time
        // out. Saying so is also the only way a caller learns the browser is no longer in front.
        if (UrlUtils.looksLikePdf(target)) {
            withContext(Dispatchers.Main) { load(target) }
            return state()
                .put("opened", "pdf_viewer")
                .put("message", "Opened in the built-in PDF viewer, which is now the visible screen.")
        }
        return act("navigate $target") { load(target); true }
    }

    suspend fun back(): JSONObject =
        act("back") { if (webView.canGoBack()) { webView.goBack(); true } else false }

    suspend fun forward(): JSONObject =
        act("forward") { if (webView.canGoForward()) { webView.goForward(); true } else false }

    suspend fun reload(): JSONObject = act("reload") { webView.reload(); true }

    /**
     * Start something that loads a page, then answer with where it ended up.
     *
     * A load that never finishes is reported as `loaded: false` rather than as an error: the page
     * is genuinely there and often usable, and calling it a failure would be a worse lie than
     * saying it is still going.
     */
    private suspend fun act(what: String, start: () -> Boolean): JSONObject {
        DebugLog.log("nav", "agent $what")
        val settled = CompletableDeferred<String>()
        val begun = withContext(Dispatchers.Main) {
            waiter = settled
            start()
        }
        if (!begun) {
            withContext(Dispatchers.Main) { if (waiter === settled) waiter = null }
            return state()
                .put("error", "not_possible")
                .put("message", "There is nowhere to go in that direction.")
        }
        val arrived = withTimeoutOrNull(LOAD_TIMEOUT_MS) { settled.await() }
        return state().put("loaded", arrived != null)
    }

    /** Where the browser is now, and where it could go from here. */
    suspend fun state(): JSONObject = withContext(Dispatchers.Main) {
        JSONObject()
            .put("url", webView.url ?: "")
            .put("title", webView.title ?: "")
            .put("can_go_back", webView.canGoBack())
            .put("can_go_forward", webView.canGoForward())
    }

    private companion object {
        /** Comfortably inside the relay's wait, so a slow page reports back rather than timing out. */
        const val LOAD_TIMEOUT_MS = 20_000L
    }
}
