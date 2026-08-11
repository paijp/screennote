package jp.pai.screennote.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import jp.pai.screennote.DebugLog
import jp.pai.screennote.Palette
import jp.pai.screennote.Prefs
import jp.pai.screennote.R
import jp.pai.screennote.databinding.ActivityPdfBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class PdfActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfBinding
    private lateinit var scaleDetector: ScaleGestureDetector

    private lateinit var palette: Palette
    private var document: PdfDocument? = null
    private var baseWidth = 0
    private var zoom = 1f

    /** Set while a pinch is in progress, so the list does not scroll under the gesture. */
    private var scaling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        palette = Palette.of(Prefs(this).nightMode, resources.configuration)
        palette.apply(this, binding.toolbar)

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
        scaleDetector = ScaleGestureDetector(this, ZoomListener())

        load(uri)
    }

    /**
     * Handled at the window level so the gesture does not have to be threaded through the
     * scroller and the list. Multi-touch events are swallowed: without that, the second finger
     * makes the list fling while the pinch is being interpreted.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaling || event.pointerCount > 1) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // See Palette: the configuration is not a reliable source once a WebView has been created
        // in this process, so the chrome is repainted from the stored preference.
        palette.apply(this, binding.toolbar)
    }

    private fun load(uri: Uri) {
        binding.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val file = PdfSource.resolve(this@PdfActivity, uri)
                val opened = withContext(Dispatchers.IO) { PdfDocument.open(file) }
                document = opened
                binding.loading.visibility = View.GONE
                baseWidth = binding.pagesScroller.width.takeIf { it > 0 }
                    ?: resources.displayMetrics.widthPixels
                applyZoom()
                updateIndicator()
            } catch (t: Throwable) {
                DebugLog.log("pdf", "open failed: $t")
                showError(getString(R.string.pdf_error, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    /**
     * Lays the page column out at the zoomed width. Pages are rasterised at that width only up to
     * [MAX_RENDER_WIDTH] — past it the bitmap is scaled up instead, which costs some sharpness at
     * high zoom but keeps a full-page bitmap within reach on a low-memory device.
     */
    private fun applyZoom() {
        val doc = document ?: return
        val width = (baseWidth * zoom).roundToInt().coerceAtLeast(1)
        binding.pages.layoutParams = binding.pages.layoutParams.apply { this.width = width }
        binding.pages.requestLayout()
        binding.pages.adapter = PdfPageAdapter(
            document = doc,
            scope = lifecycleScope,
            renderWidth = width.coerceAtMost(MAX_RENDER_WIDTH),
        )
    }

    private inner class ZoomListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        private var pending = 1f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            pending = zoom
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pending = (pending * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            // Re-rasterising every frame of the pinch would be far too slow, so the new scale is
            // applied once the gesture settles.
            if (pending != zoom) {
                zoom = pending
                DebugLog.log("pdf", "zoom=$zoom")
                applyZoom()
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
        private const val MAX_RENDER_WIDTH = 2160
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 5f

        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, PdfActivity::class.java).putExtra(EXTRA_URI, uri.toString())
    }
}
