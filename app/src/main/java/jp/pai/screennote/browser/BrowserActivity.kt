package jp.pai.screennote.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
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
import androidx.appcompat.app.AppCompatDelegate
import jp.pai.screennote.BuildConfig
import jp.pai.screennote.DebugLog
import jp.pai.screennote.Palette
import jp.pai.screennote.Prefs
import jp.pai.screennote.R
import jp.pai.screennote.databinding.ActivityBrowserBinding
import jp.pai.screennote.pdf.PdfActivity
import jp.pai.screennote.update.UpdateFlow

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var prefs: Prefs

    /** WebView's own user agent, kept so desktop mode can be turned back off. */
    private lateinit var mobileUserAgent: String

    /**
     * Resolved once, before the WebView exists. After that point the configuration is no longer
     * trustworthy on this platform version, so it is never consulted again.
     */
    private lateinit var palette: Palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        prefs = Prefs(this)
        palette = Palette.of(prefs.nightMode, resources.configuration)
        applyPalette()

        configureWebView()
        configureUrlBar()

        DebugLog.log("app", "start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        DebugLog.log("app", "ua=${binding.webView.settings.userAgentString}")
        logUiMode("create")
        // Re-applied after the WebView is constructed: that is the point at which the resolved
        // configuration can silently change underneath us.
        applyPalette()

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

    /**
     * A theme that flips at runtime shows up as light and dark values on screen at once. Each
     * activity creation and configuration change records which palette was in force, so a repeat
     * of the flicker can be read off the log instead of guessed at.
     */
    private fun logUiMode(reason: String) {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val resolved = when (night) {
            Configuration.UI_MODE_NIGHT_YES -> "night"
            Configuration.UI_MODE_NIGHT_NO -> "day"
            else -> "undefined"
        }
        DebugLog.log(
            "theme",
            "$reason resolved=$resolved painting=${if (::palette.isInitialized) palette.name else "?"} " +
                "pref=${prefs.nightMode} delegate=${AppCompatDelegate.getDefaultNightMode()}",
        )
    }

    private fun applyPalette() {
        palette.apply(this, binding.toolbar, binding.urlBar)
        binding.root.setBackgroundColor(palette.surface)
        binding.progress.setBackgroundColor(palette.surface)
        binding.errorText.setBackgroundColor(palette.surface)
    }

    override fun onResume() {
        super.onResume()
        logUiMode("resume")
        applyPalette()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        logUiMode("configChanged")
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

        mobileUserAgent = binding.webView.settings.userAgentString
        applyViewMode(reload = false)
        applyRenderingMode()

        // Credentials are delegated to the system autofill service (Google Password Manager,
        // Bitwarden, ...). Screennote never reads or stores passwords itself.
        binding.webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES

        binding.webView.webViewClient = ScreennoteWebViewClient()
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
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

    /**
     * Desktop mode is a user agent swap: sites choose their layout from it. `useWideViewPort` and
     * `loadWithOverviewMode` (set once, above) then scale the wider page down to fit the screen
     * rather than letting it overflow.
     */
    private fun applyViewMode(reload: Boolean) {
        val userAgent = if (prefs.desktopSite) UserAgents.desktop(mobileUserAgent) else mobileUserAgent
        binding.webView.settings.userAgentString = userAgent
        DebugLog.log("view", "desktop=${prefs.desktopSite}")
        if (reload) binding.webView.reload()
    }

    /**
     * The white blocks that appear over page content while zoomed come from WebView's own tile
     * rasteriser, which the app cannot reach. Drawing into a software layer sidesteps it entirely
     * at the cost of scrolling smoothness, so it is offered as a choice rather than imposed.
     */
    private fun applyRenderingMode() {
        val layer = if (prefs.softwareRendering) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE
        binding.webView.setLayerType(layer, null)
        DebugLog.log("view", "software=${prefs.softwareRendering}")
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
        binding.progress.visibility = View.INVISIBLE
    }

    private fun clearLoadError() {
        binding.errorText.visibility = View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.browser, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_desktop_site)?.isChecked = prefs.desktopSite
        menu.findItem(R.id.action_software_rendering)?.isChecked = prefs.softwareRendering
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> {
            clearLoadError()
            binding.webView.reload()
            true
        }
        R.id.action_desktop_site -> {
            prefs.desktopSite = !prefs.desktopSite
            item.isChecked = prefs.desktopSite
            applyViewMode(reload = true)
            true
        }
        R.id.action_software_rendering -> {
            prefs.softwareRendering = !prefs.softwareRendering
            item.isChecked = prefs.softwareRendering
            applyRenderingMode()
            binding.webView.reload()
            true
        }
        R.id.action_theme -> {
            showThemeChooser()
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

    /**
     * Android 8.1 has no system-wide dark setting, so "follow system" resolves to light on this
     * device and the choice has to be offered by the app itself.
     */
    private fun showThemeChooser() {
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
        )
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
        )
        val current = modes.indexOf(prefs.nightMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.action_theme)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.nightMode = modes[which]
                AppCompatDelegate.setDefaultNightMode(modes[which])
                dialog.dismiss()
                // The chrome paints from the stored preference, not from the configuration, so
                // the activity has to be rebuilt for the new choice to take effect.
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            binding.progress.visibility = View.INVISIBLE
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
