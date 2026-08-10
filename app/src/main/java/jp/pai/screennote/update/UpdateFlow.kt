package jp.pai.screennote.update

import android.app.ProgressDialog
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.pai.screennote.BuildConfig
import jp.pai.screennote.R
import kotlinx.coroutines.launch

/**
 * Start-up update check: ask GitHub for the newest release, and offer to download and install its
 * APK when it is newer than the running build.
 *
 * With [silent] set, nothing is shown unless an update is actually available — that is the mode
 * used on launch. The menu entry uses [silent] = false so the user gets feedback either way.
 */
object UpdateFlow {

    fun check(activity: AppCompatActivity, silent: Boolean) {
        activity.lifecycleScope.launch {
            val release = try {
                UpdateChecker.fetchLatest()
            } catch (t: Throwable) {
                if (!silent) {
                    toast(activity, activity.getString(R.string.update_check_failed, t.message ?: ""))
                }
                return@launch
            }

            if (release == null || !UpdateChecker.isNewer(release.versionName, BuildConfig.VERSION_NAME)) {
                if (!silent) {
                    toast(activity, activity.getString(R.string.update_none, BuildConfig.VERSION_NAME))
                }
                return@launch
            }

            AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(
                    activity.getString(
                        R.string.update_available_message,
                        release.versionName,
                        BuildConfig.VERSION_NAME,
                    )
                )
                .setPositiveButton(R.string.update_install) { _, _ -> downloadAndInstall(activity, release) }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }

    private fun downloadAndInstall(activity: AppCompatActivity, release: Release) {
        if (!UpdateInstaller.canInstall(activity)) {
            toast(activity, activity.getString(R.string.update_needs_permission))
            UpdateInstaller.requestInstallPermission(activity)
            return
        }

        @Suppress("DEPRECATION") // Adequate for a single modal download on this minSdk.
        val progress = ProgressDialog(activity).apply {
            setMessage(activity.getString(R.string.update_downloading))
            setCancelable(false)
            show()
        }

        activity.lifecycleScope.launch {
            try {
                val apk = UpdateInstaller.download(activity, release)
                progress.dismiss()
                UpdateInstaller.install(activity, apk)
            } catch (t: Throwable) {
                progress.dismiss()
                toast(activity, activity.getString(R.string.update_download_failed, t.message ?: ""))
            }
        }
    }

    private fun toast(activity: AppCompatActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
