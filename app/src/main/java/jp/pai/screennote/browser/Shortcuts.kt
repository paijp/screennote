package jp.pai.screennote.browser

import android.view.KeyEvent

/**
 * What a key press means, kept apart from the Activity so it can be tested.
 *
 * The device this is written for has a physical keyboard, which is the whole reason these exist:
 * on a touch-only phone every one of these actions is a tap away, but with a keyboard in front of
 * you, reaching for the address bar is the slowest thing in the browser.
 */
enum class Shortcut {
    FOCUS_URL_BAR,
    RELOAD,
    BACK,
    FORWARD,
    CANCEL,
}

object Shortcuts {

    /**
     * The shortcut a key press stands for, or null when it is an ordinary key.
     *
     * Deliberately matches what desktop browsers do rather than inventing anything: someone who
     * knows Ctrl+L knows it from somewhere else, and a browser that disagrees with that muscle
     * memory is worse than one with no shortcuts at all.
     */
    fun of(keyCode: Int, ctrl: Boolean, alt: Boolean): Shortcut? = when {
        // Escape and F5 carry their meaning on their own; a modifier means something else.
        !ctrl && !alt && keyCode == KeyEvent.KEYCODE_ESCAPE -> Shortcut.CANCEL
        !ctrl && !alt && keyCode == KeyEvent.KEYCODE_F5 -> Shortcut.RELOAD

        ctrl && !alt -> when (keyCode) {
            KeyEvent.KEYCODE_L -> Shortcut.FOCUS_URL_BAR
            KeyEvent.KEYCODE_R -> Shortcut.RELOAD
            else -> null
        }

        alt && !ctrl -> when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> Shortcut.BACK
            KeyEvent.KEYCODE_DPAD_RIGHT -> Shortcut.FORWARD
            else -> null
        }

        else -> null
    }
}
