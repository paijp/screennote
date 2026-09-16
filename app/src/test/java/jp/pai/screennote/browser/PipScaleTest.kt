package jp.pai.screennote.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PipScaleTest {

    @Test
    fun `fits by the tighter axis`() {
        // A tall page in a short, wide window: height is what runs out first.
        val fit = PipScale.fit(windowWidth = 600, windowHeight = 300, fullWidth = 1080, fullHeight = 1800)
        assertEquals(300f / 1800f, fit.scale, 1e-6f)
    }

    @Test
    fun `centres what is left over`() {
        val fit = PipScale.fit(windowWidth = 600, windowHeight = 300, fullWidth = 1080, fullHeight = 1800)
        // Scaled width is 1080 * 300/1800 = 180, so 420 of slack, halved.
        assertEquals(210f, fit.translationX, 1e-3f)
        assertEquals(0f, fit.translationY, 1e-3f)
    }

    @Test
    fun `an exactly matching window needs no transform`() {
        val fit = PipScale.fit(1080, 1800, 1080, 1800)
        assertEquals(1f, fit.scale, 1e-6f)
        assertEquals(0f, fit.translationX, 1e-3f)
        assertEquals(0f, fit.translationY, 1e-3f)
    }

    @Test
    fun `an unknown size is the identity, not a collapse`() {
        // Layout runs before PiP reports its window size. Scaling by zero there would leave
        // a blank window that no later pass would obviously fix.
        assertEquals(1f, PipScale.fit(0, 0, 1080, 1800).scale, 1e-6f)
        assertEquals(1f, PipScale.fit(600, 300, 0, 0).scale, 1e-6f)
    }
}
