package jp.pai.screennote

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer mirrored to logcat.
 *
 * The app is used on a device that is not usually attached to adb, so the buffer is also readable
 * from the browser's overflow menu.
 */
object DebugLog {

    private const val TAG = "Screennote"
    private const val CAPACITY = 400

    private val entries = ArrayDeque<String>()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(area: String, message: String) {
        val line = "${timestamp.format(Date())} [$area] $message"
        Log.d(TAG, line)
        entries.addLast(line)
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = if (entries.isEmpty()) "(empty)" else entries.joinToString("\n")

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
