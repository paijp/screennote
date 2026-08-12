package jp.pai.screennote

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class Prefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("screennote", Context.MODE_PRIVATE)

    var desktopSite: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_SITE, false)
        set(value) = prefs.edit().putBoolean(KEY_DESKTOP_SITE, value).apply()

    /** Stored value of a `RenderMode`; see that type for what the choices mean. */
    var renderMode: String?
        get() = prefs.getString(KEY_RENDER_MODE, null)
            // Carried over from when this was a plain software-rendering switch.
            ?: if (prefs.getBoolean(KEY_SOFTWARE_RENDERING, false)) "software" else null
        set(value) = prefs.edit().putString(KEY_RENDER_MODE, value).apply()

    /** One of the `AppCompatDelegate.MODE_NIGHT_*` constants. */
    var nightMode: Int
        get() = prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_NIGHT_MODE, value).apply()

    private companion object {
        const val KEY_DESKTOP_SITE = "desktop_site"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_SOFTWARE_RENDERING = "software_rendering"
        const val KEY_RENDER_MODE = "render_mode"
    }
}
