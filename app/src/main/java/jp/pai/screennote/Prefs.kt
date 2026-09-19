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

    /**
     * Whether leaving the app should keep the page live in Picture in Picture rather than
     * letting it stop. Persisted so it survives the activity being recreated, not so it
     * survives a restart: [BrowserActivity] clears it on a cold start, since control should
     * never be on without the user having just asked for it.
     */
    var agentControl: Boolean
        get() = prefs.getBoolean(KEY_AGENT_CONTROL, false)
        set(value) = prefs.edit().putBoolean(KEY_AGENT_CONTROL, value).apply()

    /**
     * The relay's base URL, e.g. `https://example.com/rbmcp`, or null before it is set.
     *
     * Only the address is kept. The tokens a pairing produces are deliberately not: the
     * browser token must not outlive the moment the user handed the page over, and the agent
     * token has already gone wherever the user pasted it.
     */
    var relayUrl: String?
        get() = prefs.getString(KEY_RELAY_URL, null)
        set(value) = prefs.edit().putString(KEY_RELAY_URL, value).apply()

    /**
     * When an APK was last handed to the package installer, or 0.
     *
     * The installer reads the file only once the user has worked through its own screens,
     * which can take a while — long enough for this process to be killed and started again.
     * A cold start clears the update cache, so without this record the app can delete the APK
     * out from under an install that is still on screen, and the install then does nothing
     * with nothing to explain it.
     */
    var pendingInstallAt: Long
        get() = prefs.getLong(KEY_PENDING_INSTALL_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_PENDING_INSTALL_AT, value).apply()

    /** One of the `AppCompatDelegate.MODE_NIGHT_*` constants. */
    var nightMode: Int
        get() = prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = prefs.edit().putInt(KEY_NIGHT_MODE, value).apply()

    private companion object {
        const val KEY_DESKTOP_SITE = "desktop_site"
        const val KEY_NIGHT_MODE = "night_mode"
        const val KEY_SOFTWARE_RENDERING = "software_rendering"
        const val KEY_RENDER_MODE = "render_mode"
        const val KEY_AGENT_CONTROL = "agent_control"
        const val KEY_PENDING_INSTALL_AT = "pending_install_at"
        const val KEY_RELAY_URL = "relay_url"
    }
}
