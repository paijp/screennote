package jp.pai.screennote

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

class ScreennoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        AppCompatDelegate.setDefaultNightMode(prefs.nightMode)
        // Cached PDFs and downloaded APKs are disposable; clear them on a cold start so a
        // long-lived install does not accumulate them.
        clearCacheDir("pdf")
        if (installFinished(prefs)) {
            clearCacheDir("updates")
            prefs.pendingInstallAt = 0L
        }
    }

    /**
     * Whether the downloaded APK is safe to delete.
     *
     * An install the user is still working through — the package installer's own screens, the
     * Play Protect warning, the Settings trip to allow unknown sources — outlives this
     * process, and the installer reads the file at the end of all that. Deleting it here on
     * the way back into the app leaves an install that quietly does nothing, which is very
     * hard to read as a missing file. A pending install is therefore left alone, but only for
     * a day, so an abandoned one does not keep its APK forever.
     */
    private fun installFinished(prefs: Prefs): Boolean {
        val startedAt = prefs.pendingInstallAt
        if (startedAt == 0L) return true
        val age = System.currentTimeMillis() - startedAt
        return age !in 0..PENDING_INSTALL_GRACE_MS
    }

    private fun clearCacheDir(name: String) {
        runCatching { File(cacheDir, name).listFiles()?.forEach { it.delete() } }
    }

    private companion object {
        const val PENDING_INSTALL_GRACE_MS = 24L * 60 * 60 * 1000
    }
}
