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

    data class Entry(val seq: Long, val time: String, val area: String, val message: String) {
        override fun toString(): String = "$time [$area] $message"
    }

    private val entries = ArrayDeque<Entry>()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var nextSeq = 1L

    @Synchronized
    fun log(area: String, message: String) {
        val entry = Entry(nextSeq++, timestamp.format(Date()), area, message)
        Log.d(TAG, entry.toString())
        entries.addLast(entry)
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    /**
     * A point in the log to read forward from.
     *
     * Taken when agent control is switched on, so that what an agent can read starts where the
     * user handed the browser over. Everything before that is the user's own browsing —
     * including every address they visited — and handing over one page is not handing over
     * that.
     */
    @Synchronized
    fun mark(): Long = nextSeq

    /**
     * Entries from [since] onwards, most recent [limit] of them.
     *
     * Page-authored entries are not returned unless asked for by name: `console` is written by
     * whatever the page chooses to log, so it is both the bulkiest area and the one that can
     * be aimed at whoever reads it.
     */
    @Synchronized
    fun since(since: Long, areas: Set<String>?, limit: Int): List<Entry> =
        entries.asSequence()
            .filter { it.seq >= since }
            .filter { if (areas == null) it.area != "console" else it.area in areas }
            .toList()
            .takeLast(limit.coerceIn(1, 500))

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
