package jp.pai.screennote.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.autofill.AutofillManager
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import jp.pai.screennote.BuildConfig
import jp.pai.screennote.DebugLog
import jp.pai.screennote.R
import jp.pai.screennote.databinding.ActivityBrowserBinding
import jp.pai.screennote.pdf.PdfActivity
import jp.pai.screennote.update.UpdateFlow

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        configureWebView()
        configureUrlBar()

        DebugLog.log("app", "start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        DebugLog.log("app", "ua=${binding.webView.settings.userAgentString}")

        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            val initial = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
            loadUrl(initial ?: HOME_URL)
        }

        // Silent: only speaks up when a newer release actually exists.
        UpdateFlow.check(this, silent = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.dataString?.let(::loadUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }

        // Credentials are delegated to the system autofill service (Google Password Manager,
        // Bitwarden, ...). Screennote never reads or stores passwords itself.
        binding.webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES

        binding.webView.webViewClient = ScreennoteWebViewClient()
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                DebugLog.log(
                    "console",
                    "${message.messageLevel()} ${message.message()} " +
                        "(${message.sourceId()}:${message.lineNumber()})",
                )
                return true
            }
        }
        binding.webView.setDownloadListener { url, _, contentDisposition, mimeType, size ->
            DebugLog.log("download", "url=$url mime=$mimeType size=$size")
            if (UrlUtils.isPdfMimeType(mimeType) ||
                UrlUtils.looksLikePdf(url) ||
                contentDisposition?.contains(".pdf", ignoreCase = true) == true
            ) {
                openPdf(url)
            } else {
                openExternally(url)
            }
        }
    }

    private fun configureUrlBar() {
        binding.urlBar.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrl(UrlUtils.normalizeInput(view.text.toString()))
                view.clearFocus()
                getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(view.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun loadUrl(url: String) {
        if (UrlUtils.looksLikePdf(url)) {
            openPdf(url)
            return
        }
        DebugLog.log("nav", "load $url")
        clearLoadError()
        binding.webView.loadUrl(url)
    }

    private fun openPdf(url: String) {
        DebugLog.log("nav", "pdf $url")
        startActivity(PdfActivity.intent(this, Uri.parse(url)))
    }

    private fun openExternally(url: String) {
        DebugLog.log("nav", "external $url")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device can handle it; silently ignore rather than crash.
        }
    }

    /**
     * Replaces the blank page a failed navigation would otherwise leave behind. Without this the
     * only symptom of a TLS or network failure is that the page never appears.
     */
    private fun showLoadError(summary: String, url: String) {
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = getString(R.string.load_error, summary, url)
        binding.progress.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
    }

    private fun clearLoadError() {
        binding.errorText.visibility = View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.browser, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> {
            clearLoadError()
            binding.webView.reload()
            true
        }
        R.id.action_debug_log -> {
            showDebugLog()
            true
        }
        R.id.action_check_update -> {
            UpdateFlow.check(this, silent = false)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showDebugLog() {
        val text = DebugLog.snapshot()
        AlertDialog.Builder(this)
            .setTitle(R.string.action_debug_log)
            .setMessage(text)
            .setPositiveButton(R.string.debug_log_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("screennote log", text))
                Toast.makeText(this, R.string.debug_log_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.debug_log_clear) { _, _ -> DebugLog.clear() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }

    private inner class ScreennoteWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when {
                UrlUtils.looksLikePdf(url) -> {
                    openPdf(url)
                    true
                }
                request.url.scheme !in setOf("http", "https") -> {
                    openExternally(url)
                    true
                }
                else -> {
                    DebugLog.log("nav", "navigate $url main=${request.isForMainFrame}")
                    false
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // Leaving a page ends any form the user was filling in. Committing here is what
            // makes the system's "save password?" prompt appear for WebView content.
            runCatching { getSystemService(AutofillManager::class.java)?.commit() }
            DebugLog.log("nav", "started $url")
            clearLoadError()
            binding.urlBar.setText(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            DebugLog.log("nav", "finished $url")
            binding.urlBar.setText(url)
            binding.swipeRefresh.isRefreshing = false
            binding.progress.visibility = View.GONE
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            DebugLog.log(
                "error",
                "net ${error.errorCode} ${error.description} " +
                    "main=${request.isForMainFrame} ${request.url}",
            )
            if (request.isForMainFrame) {
                showLoadError("${error.errorCode} ${error.description}", request.url.toString())
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            DebugLog.log(
                "error",
                "http ${errorResponse.statusCode} ${errorResponse.reasonPhrase} " +
                    "main=${request.isForMainFrame} ${request.url}",
            )
            if (request.isForMainFrame) {
                showLoadError(
                    "HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase}",
                    request.url.toString(),
                )
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val reason = sslErrorName(error.primaryError)
            DebugLog.log("error", "ssl $reason url=${error.url}")
            DebugLog.log("error", "ssl cert=${error.certificate}")
            // Never proceed past a certificate the platform rejected.
            handler.cancel()
            showLoadError("SSL: $reason", error.url)
        }

        private fun sslErrorName(code: Int): String = when (code) {
            SslError.SSL_NOTYETVALID -> "certificate not yet valid"
            SslError.SSL_EXPIRED -> "certificate expired"
            SslError.SSL_IDMISMATCH -> "hostname mismatch"
            SslError.SSL_UNTRUSTED -> "untrusted certificate authority"
            SslError.SSL_DATE_INVALID -> "invalid certificate date"
            SslError.SSL_INVALID -> "invalid certificate"
            else -> "unknown ($code)"
        }
    }

    companion object {
        private const val HOME_URL = "https://duckduckgo.com/"
    }
}
