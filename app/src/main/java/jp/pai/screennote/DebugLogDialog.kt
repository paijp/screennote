package jp.pai.screennote

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * The debug log, with a way to cut it down.
 *
 * Reading the log is how anything on this device gets diagnosed, and by the time it is worth
 * reading it is usually thousands of lines — a page under a bot check emitted hundreds of
 * console warnings on its own. So the areas are offered as buttons: everything is shown by
 * default, "All" turns the lot on or off, and from there one tap isolates what is wanted.
 *
 * The text is selectable, and Copy takes whatever is currently on screen rather than the whole
 * buffer, so a filtered view is also the way to copy just part of it.
 */
object DebugLogDialog {

    fun show(activity: Activity) {
        val areas = DebugLog.areas()
        val shown = areas.toMutableSet()

        val text = TextView(activity).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8))
        }
        fun render() {
            text.text = DebugLog.snapshot(if (shown.size == areas.size) null else shown)
        }
        render()

        val filters = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 8), 0)
        }
        val buttons = mutableMapOf<String, Button>()

        fun repaint() {
            buttons.forEach { (area, button) ->
                button.alpha = if (area.isEmpty() || area in shown) 1f else 0.35f
            }
            render()
        }

        // Leading "All": the quickest route to one area is to clear everything, then pick it.
        filters.addView(
            tagButton(activity, activity.getString(R.string.debug_log_all)) {
                if (shown.size == areas.size) shown.clear() else shown.addAll(areas)
                repaint()
            }.also { buttons[""] = it }
        )
        areas.forEach { area ->
            filters.addView(
                tagButton(activity, area) {
                    if (!shown.remove(area)) shown.add(area)
                    repaint()
                }.also { buttons[area] = it }
            )
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                HorizontalScrollView(activity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(filters)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                ScrollView(activity).apply { addView(text) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        repaint()

        AlertDialog.Builder(activity)
            .setTitle(R.string.action_debug_log)
            .setView(content)
            .setPositiveButton(R.string.debug_log_copy) { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("screennote log", text.text))
                Toast.makeText(activity, R.string.debug_log_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.debug_log_clear) { _, _ -> DebugLog.clear() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun tagButton(activity: Activity, label: String, onClick: () -> Unit): Button =
        Button(activity, null, android.R.attr.buttonStyleSmall).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
            setOnClickListener { onClick() }
        }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
