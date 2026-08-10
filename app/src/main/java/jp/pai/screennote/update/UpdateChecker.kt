package jp.pai.screennote.update

import jp.pai.screennote.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Release(
    val tag: String,
    val versionName: String,
    val apkName: String,
    val apkUrl: String,
    val notes: String,
)

object UpdateChecker {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /**
     * Reads the newest published release from GitHub. Returns null when the repository has no
     * release yet, or the release carries no APK asset.
     */
    suspend fun fetchLatest(repo: String = BuildConfig.GITHUB_REPO): Release? =
        withContext(Dispatchers.IO) {
            val url = URL("https://api.github.com/repos/$repo/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Screennote/${BuildConfig.VERSION_NAME}")
            }
            try {
                // 404 simply means "nothing released yet" — not an error worth surfacing.
                if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
                if (connection.responseCode !in 200..299) {
                    throw IOException("HTTP ${connection.responseCode}")
                }
                parse(JSONObject(connection.inputStream.bufferedReader().readText()))
            } finally {
                connection.disconnect()
            }
        }

    private fun parse(json: JSONObject): Release? {
        val tag = json.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotEmpty() }
                ?: continue
            return Release(
                tag = tag,
                versionName = tag.removePrefix("v"),
                apkName = name,
                apkUrl = downloadUrl,
                notes = json.optString("body"),
            )
        }
        return null
    }

    /** True when [candidate] is strictly newer than [current] under dotted-numeric comparison. */
    fun isNewer(candidate: String, current: String): Boolean =
        compareVersions(candidate, current) > 0

    internal fun compareVersions(a: String, b: String): Int {
        val left = numericParts(a)
        val right = numericParts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        // Equal numerically: a pre-release suffix ("1.2.0-rc1") sorts below the plain version.
        val leftPre = a.contains('-')
        val rightPre = b.contains('-')
        return when {
            leftPre == rightPre -> 0
            leftPre -> -1
            else -> 1
        }
    }

    private fun numericParts(version: String): List<Int> =
        version.removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
