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
 *
 * Entries are kept apart rather than pre-formatted so the reader can narrow them down. That
 * matters more than it sounds: a single page can emit hundreds of console warnings — one
 * Cloudflare challenge did — and without a way to filter, the lines worth reading are both
 * buried and, once the buffer wraps, gone.
 */
object DebugLog {

    private const val TAG = "Screennote"

    /**
     * Deliberately generous. The buffer is read after something has gone wrong, by which point
     * a chatty page may have pushed the interesting lines out; a few hundred short strings cost
     * nothing next to that.
     */
    private const val CAPACITY = 1500

    data class Entry(val time: String, val area: String, val message: String) {
        override fun toString(): String = "$time [$area] $message"
    }

    private val entries = ArrayDeque<Entry>()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(area: String, message: String) {
        val entry = Entry(timestamp.format(Date()), area, message)
        Log.d(TAG, entry.toString())
        entries.addLast(entry)
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    /** The areas currently present, in the order they are usually wanted. */
    @Synchronized
    fun areas(): List<String> = entries.map { it.area }.distinct().sorted()

    /** Every entry, or only those from [areas] when it is not null. */
    @Synchronized
    fun snapshot(areas: Set<String>? = null): String {
        val wanted = entries.filter { areas == null || it.area in areas }
        return if (wanted.isEmpty()) "(empty)" else wanted.joinToString("\n")
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
