package jp.pai.screennote.browser

import android.webkit.WebView
import jp.pai.screennote.DebugLog

/**
 * Counts the page's interactive elements and reports the first one's rectangle.
 *
 * This exists to answer one question, on which the whole idea of driving this browser from
 * an agent rests: **does a WebView still lay out while the activity is in Picture in
 * Picture?** A view that is not laid out keeps running its JavaScript timers but reports
 * every element as zero by zero, so `getBoundingClientRect()` comes back empty and an agent
 * sees a loaded page with nothing on it — a failure with no error attached to it.
 *
 * The probe therefore reports `visible` (elements with a non-zero box) separately from
 * `total`. Equal numbers before and after the PiP transition is the result we want; a
 * `visible` that drops to zero is the failure this is looking for.
 */
object DomProbe {

    /** Interval between probes while the probe is running. */
    const val INTERVAL_MS = 3_000L

    private const val JS = """
        (function () {
          try {
            var els = [].slice.call(document.querySelectorAll(
                'a,button,input,textarea,select,[role="button"]'));
            var visible = 0;
            var first = null;
            for (var i = 0; i < els.length; i++) {
              var r = els[i].getBoundingClientRect();
              if (r.width > 0 && r.height > 0) {
                visible++;
                if (first === null) {
                  first = els[i].tagName + ' ' + Math.round(r.width) + 'x' + Math.round(r.height)
                      + ' @' + Math.round(r.left) + ',' + Math.round(r.top);
                }
              }
            }
            return JSON.stringify({
              total: els.length, visible: visible, first: first,
              iw: window.innerWidth, ih: window.innerHeight,
              dpr: window.devicePixelRatio, sy: window.scrollY
            });
          } catch (e) {
            return JSON.stringify({ error: String(e) });
          }
        })()
    """

    /**
     * Run one probe and write the result to the debug log, tagged with [context] so the
     * lines can be told apart once the log is copied off the device.
     */
    fun run(webView: WebView, context: String) {
        webView.evaluateJavascript(JS) { result ->
            // evaluateJavascript hands back a JSON-encoded value, so the JSON string the
            // script returns arrives quoted and escaped. Unwrapping it keeps the log
            // readable; the exact shape is not worth a parser here.
            val text = result
                ?.removeSurrounding("\"")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?: "null"
            DebugLog.log("probe", "$context $text")
        }
    }
}
