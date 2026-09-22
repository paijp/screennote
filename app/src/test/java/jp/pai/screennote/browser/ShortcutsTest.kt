package jp.pai.screennote.browser

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutsTest {

    @Test
    fun `ctrl l focuses the address bar`() {
        assertEquals(
            Shortcut.FOCUS_URL_BAR,
            Shortcuts.of(KeyEvent.KEYCODE_L, ctrl = true, alt = false),
        )
    }

    @Test
    fun `reload has both spellings`() {
        assertEquals(Shortcut.RELOAD, Shortcuts.of(KeyEvent.KEYCODE_R, ctrl = true, alt = false))
        assertEquals(Shortcut.RELOAD, Shortcuts.of(KeyEvent.KEYCODE_F5, ctrl = false, alt = false))
    }

    @Test
    fun `alt and an arrow walk the history`() {
        assertEquals(
            Shortcut.BACK,
            Shortcuts.of(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = false, alt = true),
        )
        assertEquals(
            Shortcut.FORWARD,
            Shortcuts.of(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl = false, alt = true),
        )
    }

    @Test
    fun `a bare letter is left to whatever has focus`() {
        assertNull(Shortcuts.of(KeyEvent.KEYCODE_L, ctrl = false, alt = false))
        assertNull(Shortcuts.of(KeyEvent.KEYCODE_R, ctrl = false, alt = false))
    }

    @Test
    fun `an arrow without alt still moves the cursor`() {
        assertNull(Shortcuts.of(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = false, alt = false))
    }

    /**
     * Holding both is how a great many accidental presses arrive, and guessing wrong there means
     * losing whatever the user was typing.
     */
    @Test
    fun `both modifiers means nothing`() {
        assertNull(Shortcuts.of(KeyEvent.KEYCODE_L, ctrl = true, alt = true))
        assertNull(Shortcuts.of(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true, alt = true))
        assertNull(Shortcuts.of(KeyEvent.KEYCODE_ESCAPE, ctrl = true, alt = false))
    }
}
