package jp.pai.screennote

import android.app.Application
import java.io.File

class ScreennoteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Cached PDFs and downloaded APKs are disposable; clear them on a cold start so a
        // long-lived install does not accumulate them.
        clearCacheDir("pdf")
        clearCacheDir("updates")
    }

    private fun clearCacheDir(name: String) {
        runCatching { File(cacheDir, name).listFiles()?.forEach { it.delete() } }
    }
}
