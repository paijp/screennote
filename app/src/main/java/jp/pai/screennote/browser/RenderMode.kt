package jp.pai.screennote.browser

import android.view.View
import android.webkit.WebView
import jp.pai.screennote.R

/**
 * How the WebView's output reaches the screen.
 *
 * On this project's target device, WebView's GPU tile rasteriser leaves white blocks over page
 * content while zoomed, and the same artefacts reach the app's own chrome — WebView draws through
 * the host app's hardware-accelerated canvas rather than owning its surface, which is why a full
 * Chromium browser on the same device is unaffected.
 */
enum class RenderMode(val storedValue: String, val labelRes: Int) {

    /** WebView's default: rasterised on the GPU, composited into the app's canvas. */
    GPU("gpu", R.string.render_mode_gpu),

    /**
     * Rasterises into an offscreen buffer first. Documented for WebViews inside scrolling or
     * animated containers, and it changes the raster path without giving up the GPU — so unlike
     * SOFTWARE it costs memory rather than smoothness.
     */
    OFFSCREEN("offscreen", R.string.render_mode_offscreen),

    /** Draws into a software layer, bypassing the GPU raster path entirely. Slow but reliable. */
    SOFTWARE("software", R.string.render_mode_software);

    fun applyTo(webView: WebView) {
        webView.settings.offscreenPreRaster = this == OFFSCREEN
        webView.setLayerType(
            if (this == SOFTWARE) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE,
            null,
        )
    }

    companion object {
        fun from(storedValue: String?): RenderMode =
            entries.firstOrNull { it.storedValue == storedValue } ?: GPU
    }
}
