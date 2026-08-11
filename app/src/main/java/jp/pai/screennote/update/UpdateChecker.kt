package jp.pai.screennote.update

import jp.pai.screennote.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Release(
    val versionName: String,
    val apkName: String,
    val apkUrl: String,
    /** Lowercase hex SHA-256 of the APK, or null when the manifest omits it. */
    val sha256: String?,
)

/**
 * Reads the update manifest committed to `release/` on the repository's default branch, served
 * over raw.githubusercontent.com.
 *
 * This is preferred over the Releases API because it needs no token, is not subject to the API's
 * per-IP rate limit, and lets the manifest carry a checksum for the APK.
 */
object UpdateChecker {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private val baseUrl: String
        get() = "https://raw.githubusercontent.com/${BuildConfig.GITHUB_REPO}/" +
            "${BuildConfig.RELEASE_BRANCH}/${BuildConfig.RELEASE_DIR}"

    /** Returns null when nothing has been published to `release/` yet. */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        // raw.githubusercontent.com is CDN-cached for a few minutes; the timestamp defeats a
        // stale edge copy without relying on the CDN honouring Cache-Control.
        val url = URL("$baseUrl/latest.json?t=${System.currentTimeMillis()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "Screennote/${BuildConfig.VERSION_NAME}")
        }
        try {
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
        val versionName = json.optString("versionName").takeIf { it.isNotEmpty() } ?: return null
        val apkName = json.optString("apk").takeIf { it.isNotEmpty() } ?: return null
        return Release(
            versionName = versionName,
            apkName = apkName,
            apkUrl = "$baseUrl/$apkName",
            sha256 = json.optString("sha256").takeIf { it.isNotEmpty() }?.lowercase(),
        )
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
