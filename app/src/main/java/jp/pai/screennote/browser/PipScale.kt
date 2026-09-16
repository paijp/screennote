package jp.pai.screennote.browser

/**
 * Where to draw a full-size page inside the small window Picture in Picture gives us.
 *
 * The WebView is deliberately *not* resized to the PiP window. A WebView laid out at
 * 300x170dp reports that as its viewport, and responsive sites switch to a layout meant for
 * a watch. Keeping the view at its full-screen size and shrinking the drawing instead means
 * the page — and every rectangle read out of it — stays exactly what it was on screen.
 *
 * Pure so it can be tested without a device; see PipScaleTest.
 */
data class PipScale(val scale: Float, val translationX: Float, val translationY: Float) {

    companion object {

        /**
         * Fit [fullWidth] x [fullHeight] inside [windowWidth] x [windowHeight], centred.
         *
         * Assumes the view's pivot is its top-left corner, so the translation is applied to
         * the already-scaled box rather than to the original one.
         *
         * Returns an identity transform when either size is not known yet — layout runs
         * before PiP has told anyone how big the window is, and a zero there would otherwise
         * collapse the page to nothing.
         */
        fun fit(
            windowWidth: Int,
            windowHeight: Int,
            fullWidth: Int,
            fullHeight: Int,
        ): PipScale {
            if (windowWidth <= 0 || windowHeight <= 0 || fullWidth <= 0 || fullHeight <= 0) {
                return PipScale(1f, 0f, 0f)
            }
            val scale = minOf(
                windowWidth.toFloat() / fullWidth,
                windowHeight.toFloat() / fullHeight,
            )
            return PipScale(
                scale = scale,
                translationX = (windowWidth - fullWidth * scale) / 2f,
                translationY = (windowHeight - fullHeight * scale) / 2f,
            )
        }
    }
}
