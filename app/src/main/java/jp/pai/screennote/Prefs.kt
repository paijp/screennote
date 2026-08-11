package jp.pai.screennote

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class Prefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("screennote", Context.MODE_PRIVATE)

    var desktopSite: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_SITE, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_SITE, value).apply()

    /**
     * Renders the page into a software layer instead of a GPU-backed one. Slower to scroll, but
     * it bypasses the tile rasteriser that produces white blocks over content on this device.
     */
    var softwareRendering: Boolean
        get() = prefs.getBoolean(KEY_SOFTWARE_RENDERING, false)
        set(value) = prefs.edit().putBoolean(KEY_SOFTWARE_RENDERING, value).apply()

    /** One of the `AppCompatDelegate.MODE_NIGHT_*` constants. */
    var nightMode: Int
        get() = prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_NIGHT_MODE, value).apply()

    private companion object {
        const val KEY_DESKTOP_SITE = "desktop_site"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_SOFTWARE_RENDERING = "software_rendering"
    }
}
