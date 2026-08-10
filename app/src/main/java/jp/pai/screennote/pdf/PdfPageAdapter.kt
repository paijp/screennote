package jp.pai.screennote.pdf

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import jp.pai.screennote.databinding.ItemPdfPageBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PdfPageAdapter(
    private val document: PdfDocument,
    private val scope: CoroutineScope,
    private val renderWidth: Int,
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    // Roughly an eighth of the heap; enough for a handful of pages on the low-memory devices
    // this app targets, and rendering is cheap enough to redo on a miss.
    private val cache = object : LruCache<Int, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 8) / 1024).toInt().coerceAtLeast(8 * 1024)
    ) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun getItemCount(): Int = document.pageCount

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder =
        PageViewHolder(ItemPdfPageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) = holder.bind(position)

    override fun onViewRecycled(holder: PageViewHolder) = holder.recycle()

    inner class PageViewHolder(
        private val binding: ItemPdfPageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var job: Job? = null

        fun bind(position: Int) {
            job?.cancel()
            val cached = cache.get(position)
            if (cached != null) {
                binding.pageImage.setImageBitmap(cached)
                return
            }
            binding.pageImage.setImageDrawable(null)
            job = scope.launch {
                val bitmap = runCatching { document.renderPage(position, renderWidth) }.getOrNull()
                    ?: return@launch
                cache.put(position, bitmap)
                // The holder may have been rebound to another page while rendering.
                if (bindingAdapterPosition == position) {
                    binding.pageImage.setImageBitmap(bitmap)
                }
            }
        }

        fun recycle() {
            job?.cancel()
            job = null
            binding.pageImage.setImageDrawable(null)
        }
    }
}
