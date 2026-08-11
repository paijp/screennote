package jp.pai.screennote

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar

/**
 * The colours the app paints its own chrome with, held as literals rather than as `-night`
 * resources.
 *
 * Below API 29 initialising a WebView resets the application's [Configuration] — `uiMode`
 * included — to the system default, without any configuration-change callback. Anything resolved
 * from a qualified resource after that point comes back with the wrong palette, which is how a
 * dark-themed window ends up with a white status bar and a white-on-black URL field on screen at
 * the same time. Resolving these values ourselves takes the resource system out of the loop.
 */
enum class Palette(
    val surface: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
) {
    LIGHT(
        surface = 0xFFFFFBFE.toInt(),
        onSurface = 0xFF1C1B1F.toInt(),
        onSurfaceVariant = 0xFF49454F.toInt(),
    ),
    DARK(
        surface = 0xFF1C1B1F.toInt(),
        onSurface = 0xFFE6E1E5.toInt(),
        onSurfaceVariant = 0xFFCAC4D0.toInt(),
    );

    /** A light surface needs dark status bar icons, which is what this system flag requests. */
    private val wantsDarkStatusBarIcons: Boolean get() = this == LIGHT

    /**
     * Paints the window and the given views. Safe to call repeatedly — it is applied again on
     * resume precisely because something else may have changed the resolved configuration in the
     * meantime.
     */
    fun apply(activity: Activity, toolbar: Toolbar?, vararg texts: TextView) {
        activity.window.statusBarColor = surface
        activity.window.decorView.let { decor ->
            val flag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            decor.systemUiVisibility = if (wantsDarkStatusBarIcons) {
                decor.systemUiVisibility or flag
            } else {
                decor.systemUiVisibility and flag.inv()
            }
        }
        toolbar?.setBackgroundColor(surface)
        texts.forEach { text ->
            text.setTextColor(onSurface)
            text.setHintTextColor(onSurfaceVariant)
        }
    }

    companion object {
        /**
         * [nightMode] is one of the `AppCompatDelegate.MODE_NIGHT_*` constants, stored by the app
         * itself. Only "follow system" consults the configuration, and callers should resolve that
         * once at startup rather than re-reading it later.
         */
        fun of(nightMode: Int, configuration: Configuration): Palette = when (nightMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> DARK
            AppCompatDelegate.MODE_NIGHT_NO -> LIGHT
            else -> {
                val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
            }
        }
    }
}
