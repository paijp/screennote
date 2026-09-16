package jp.pai.screennote.browser

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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

    /**
     * The page's size while the activity had the whole screen. The WebView keeps being laid
     * out at this size in PiP — see [PipScale] for why — so it has to be remembered from
     * before the transition, when it is still the container's own size.
     */
    private var fullWidth = 0
    private var fullHeight = 0

    /**
     * Set between asking for PiP and being told it happened.
     *
     * The container is relaid out at the small size during that gap, and
     * `isInPictureInPictureMode` is not reliably true yet — so without this the layout
     * listener would record the PiP window as the full-screen size and the page would be
     * scaled against itself from then on.
     */
    private var enteringPip = false

    /**
     * Set between leaving PiP and the window being full size again.
     *
     * Restoring MATCH_PARENT the moment PiP ends lays the page out against a window that is
     * still small — measured at 160x217 CSS pixels on the way out of a 411x526 page, which is
     * small enough that responsive sites switch layout and every coordinate moves. It is
     * brief, so nothing looks wrong on screen; an agent reading during that window silently
     * gets the wrong page. The pinned size is held until the window has caught up.
     */
    private var leavingPip = false

    private val probeHandler = Handler(Looper.getMainLooper())
    private var probing = false
    private val probeTick = object : Runnable {
        override fun run() {
            DomProbe.run(binding.webView, if (isInPictureInPictureMode) "pip" else "full")
            probeHandler.postDelayed(this, DomProbe.INTERVAL_MS)
        }
    }

    private val pipSupported: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        prefs = Prefs(this)
        palette = Palette.of(prefs.nightMode, resources.configuration)
        applyPalette()

        // Control is never on across a cold start: it means "I have just handed this page
        // over", and a flag left set from days ago is not that. A recreation (theme change,
        // low memory) passes a savedInstanceState and keeps it.
        if (savedInstanceState == null) {
            prefs.agentControl = false
        }

        configureWebView()
        configureUrlBar()
        configureAgentControl()

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
     * rasteriser, which the app cannot reach directly. The available raster paths differ in what
     * they cost, and which one this device needs is a question only the device can answer, so the
     * choice is offered rather than imposed. See [RenderMode].
     */
    private fun applyRenderingMode() {
        val mode = RenderMode.from(prefs.renderMode)
        mode.applyTo(binding.webView)
        DebugLog.log("view", "render=${mode.storedValue}")
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

    // ── Agent control / Picture in Picture ──────────────────────────────────────────────
    //
    // On a phone there is only one screen, so handing the page to an agent means the user
    // leaves for another app — and a stopped activity stops laying its WebView out. The page
    // keeps running its timers, but every element measures zero by zero, which reads as a
    // page that loaded and turned out to be empty. PiP is the cheap way out: a PiP activity
    // is paused but never stopped, so layout and drawing carry on.

    private fun configureAgentControl() {
        binding.webViewContainer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (isInPictureInPictureMode) {
                applyPipScale(v.width, v.height)
            } else if (leavingPip) {
                // Still shrinking back. Releasing the pinned size now would lay the page out
                // at whatever intermediate size this pass has, so wait for the window to be
                // itself again — and do not mistake an intermediate size for the real one.
                if (v.width >= fullWidth && v.height >= fullHeight) {
                    leavingPip = false
                    v.post { unpinWebViewSize() }
                }
            } else if (!enteringPip && v.width > 0 && v.height > 0) {
                // The only place the full-screen size is known for certain.
                fullWidth = v.width
                fullHeight = v.height
            }
        }
        applyAgentControlIndicator()
    }

    private fun applyAgentControlIndicator() {
        binding.agentIndicator.visibility =
            if (prefs.agentControl) View.VISIBLE else View.GONE
    }

    private fun toggleAgentControl() {
        if (!prefs.agentControl && !pipSupported) {
            Toast.makeText(this, R.string.agent_control_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        prefs.agentControl = !prefs.agentControl
        applyAgentControlIndicator()
        DebugLog.log("agent", "control=${prefs.agentControl}")
        Toast.makeText(
            this,
            if (prefs.agentControl) R.string.action_agent_control_on
            else R.string.action_agent_control_off,
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * Called as the user leaves for another app. Note that on this platform version the
     * recents button does not always route through here, only Home reliably does — so if the
     * page stops when switching apps one way but not the other, this is why. The log line is
     * there to tell those two cases apart.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        DebugLog.log("agent", "userLeaveHint control=${prefs.agentControl}")
        if (!prefs.agentControl || !pipSupported || isInPictureInPictureMode) return

        // Last moment at which the container is still full screen.
        binding.webViewContainer.let {
            if (it.width > 0 && it.height > 0) {
                fullWidth = it.width
                fullHeight = it.height
            }
        }
        enteringPip = true
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .build()
            )
        }.onFailure {
            enteringPip = false
            DebugLog.log("agent", "enterPip failed: $it")
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        enteringPip = false
        DebugLog.log("agent", "pip=$isInPictureInPictureMode full=${fullWidth}x$fullHeight")

        // The chrome is not useful at this size, and giving its space to the page keeps the
        // scale factor as large as it can be.
        val chrome = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        binding.toolbar.visibility = chrome

        if (isInPictureInPictureMode) {
            leavingPip = false
            // Pin the WebView to the size it had on screen. Letting it shrink to the PiP
            // window would change the viewport, and responsive sites would re-lay out for a
            // 300dp screen — taking every element's coordinates with them.
            if (fullWidth > 0 && fullHeight > 0) {
                val lp = binding.webView.layoutParams
                lp.width = fullWidth
                lp.height = fullHeight
                binding.webView.layoutParams = lp
            }
            binding.webView.pivotX = 0f
            binding.webView.pivotY = 0f
        } else {
            // Drop the transform straight away — it is only a drawing matrix, so nothing has
            // to be laid out for it — but keep the size pinned until the window is full again.
            // The layout listener releases it, and until then the page never sees a viewport
            // it did not already have.
            binding.webView.scaleX = 1f
            binding.webView.scaleY = 1f
            binding.webView.translationX = 0f
            binding.webView.translationY = 0f
            leavingPip = fullWidth > 0 && fullHeight > 0
            if (!leavingPip) unpinWebViewSize()
        }

        // Straight after the transition is exactly when the "laid out or not" question gets
        // answered, so record it whether or not the repeating probe is running.
        binding.webView.post {
            DomProbe.run(binding.webView, if (isInPictureInPictureMode) "pip-enter" else "pip-exit")
        }
    }

    /** Hand sizing back to the layout now that the window is the size the page expects. */
    private fun unpinWebViewSize() {
        val lp = binding.webView.layoutParams
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT) return
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        binding.webView.layoutParams = lp
    }

    private fun applyPipScale(windowWidth: Int, windowHeight: Int) {
        val fit = PipScale.fit(windowWidth, windowHeight, fullWidth, fullHeight)
        binding.webView.scaleX = fit.scale
        binding.webView.scaleY = fit.scale
        binding.webView.translationX = fit.translationX
        binding.webView.translationY = fit.translationY
    }

    private fun toggleProbe() {
        probing = !probing
        probeHandler.removeCallbacks(probeTick)
        if (probing) probeHandler.post(probeTick)
        Toast.makeText(
            this,
            if (probing) R.string.dom_probe_on else R.string.dom_probe_off,
            Toast.LENGTH_SHORT,
        ).show()
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
        menu.findItem(R.id.action_agent_control)?.isChecked = prefs.agentControl
        menu.findItem(R.id.action_dom_probe)?.isChecked = probing
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_agent_control -> {
            toggleAgentControl()
            item.isChecked = prefs.agentControl
            true
        }
        R.id.action_dom_probe -> {
            toggleProbe()
            item.isChecked = probing
            true
        }
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
        R.id.action_render_mode -> {
            showRenderModeChooser()
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

    private fun showRenderModeChooser() {
        val modes = RenderMode.entries
        val current = modes.indexOf(RenderMode.from(prefs.renderMode))
        AlertDialog.Builder(this)
            .setTitle(R.string.action_render_mode)
            .setSingleChoiceItems(
                modes.map { getString(it.labelRes) }.toTypedArray(),
                current,
            ) { dialog, which ->
                prefs.renderMode = modes[which].storedValue
                applyRenderingMode()
                dialog.dismiss()
                // The raster path changes what is already on screen, so redraw from scratch.
                binding.webView.reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        probeHandler.removeCallbacks(probeTick)
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
