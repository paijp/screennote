package jp.pai.screennote.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.autofill.AutofillManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
        }
        binding.webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
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
        binding.webView.loadUrl(url)
    }

    private fun openPdf(url: String) {
        startActivity(PdfActivity.intent(this, Uri.parse(url)))
    }

    private fun openExternally(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // Nothing on the device can handle it; silently ignore rather than crash.
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.browser, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_reload -> {
            binding.webView.reload()
            true
        }
        R.id.action_check_update -> {
            UpdateFlow.check(this, silent = false)
            true
        }
        else -> super.onOptionsItemSelected(item)
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
                else -> false
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // Leaving a page ends any form the user was filling in. Committing here is what
            // makes the system's "save password?" prompt appear for WebView content.
            runCatching { getSystemService(AutofillManager::class.java)?.commit() }
            binding.urlBar.setText(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            binding.urlBar.setText(url)
            binding.swipeRefresh.isRefreshing = false
            binding.progress.visibility = View.GONE
        }
    }

    companion object {
        private const val HOME_URL = "https://duckduckgo.com/"
    }
}
