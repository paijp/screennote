package jp.pai.screennote.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper around [PdfRenderer], which exists since API 21 and therefore works on Android 8.1
 * without bundling a JavaScript PDF engine.
 *
 * [PdfRenderer] allows only one open page at a time and is not thread-safe. A plain monitor is used
 * rather than a coroutine mutex so that [close] blocks until any in-flight render has finished —
 * tearing the native renderer down underneath a running render would crash the process.
 */
class PdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    private val lock = Any()
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /** Renders page [index] scaled to [targetWidth] pixels wide, preserving aspect ratio. */
    suspend fun renderPage(index: Int, targetWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(!closed) { "document closed" }
            renderer.openPage(index).use { page ->
                val scale = targetWidth.toFloat() / page.width
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
                // PdfRenderer composites onto the existing pixels; pages are drawn on white.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        fun open(file: File): PdfDocument {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfDocument(descriptor, PdfRenderer(descriptor))
            } catch (t: Throwable) {
                descriptor.close()
                throw t
            }
        }
    }
}
