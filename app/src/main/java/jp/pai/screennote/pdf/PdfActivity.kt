package jp.pai.screennote.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import jp.pai.screennote.R
import jp.pai.screennote.databinding.ActivityPdfBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfBinding
    private var document: PdfDocument? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: intent.data
        if (uri == null) {
            showError(getString(R.string.pdf_error, "no document"))
            return
        }
        binding.toolbar.title = uri.lastPathSegment ?: getString(R.string.pdf_viewer_title)
        binding.toolbar.subtitle = uri.host

        binding.pages.layoutManager = LinearLayoutManager(this)
        binding.pages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateIndicator()
        })

        load(uri)
    }

    private fun load(uri: Uri) {
        binding.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val file = PdfSource.resolve(this@PdfActivity, uri)
                val opened = withContext(Dispatchers.IO) { PdfDocument.open(file) }
                document = opened
                binding.loading.visibility = View.GONE
                // Render at the view's own width, capped so a tablet-sized page cannot allocate
                // an unreasonable bitmap on a low-memory device.
                val width = binding.pages.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                binding.pages.adapter = PdfPageAdapter(
                    document = opened,
                    scope = lifecycleScope,
                    renderWidth = width.coerceAtMost(MAX_RENDER_WIDTH),
                )
                updateIndicator()
            } catch (t: Throwable) {
                showError(getString(R.string.pdf_error, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun updateIndicator() {
        val pageCount = document?.pageCount ?: return
        val manager = binding.pages.layoutManager as? LinearLayoutManager ?: return
        val first = manager.findFirstVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION) return
        binding.pageIndicator.visibility = View.VISIBLE
        binding.pageIndicator.text = getString(R.string.page_indicator, first + 1, pageCount)
    }

    private fun showError(message: String) {
        binding.loading.visibility = View.GONE
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = message
    }

    override fun onDestroy() {
        // The adapter's render jobs live on lifecycleScope and are cancelled before this runs.
        binding.pages.adapter = null
        document?.close()
        document = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URI = "jp.pai.screennote.extra.URI"
        private const val MAX_RENDER_WIDTH = 1440

        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, PdfActivity::class.java).putExtra(EXTRA_URI, uri.toString())
    }
}
