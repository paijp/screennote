package jp.pai.screennote.agent

import android.webkit.WebView
import jp.pai.screennote.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The loop that makes this browser reachable from a conversation.
 *
 * It polls the relay, runs whatever comes back against the page, and posts the answer. There
 * is no inbound connection: the phone is behind NAT and the relay cannot call it, so every
 * exchange starts here.
 *
 * The session exists only while it is running. Neither token is written to storage — the
 * browser token because it is the credential that must never outlive the moment the user
 * handed the page over, and the agent token because the user has already pasted it wherever
 * it needs to be.
 */
class AgentSession(
    private val relay: Relay,
    private val pairing: Pairing,
    private val webView: WebView,
    private val onEnded: (String) -> Unit,
) {

    private var job: Job? = null

    /**
     * How long to wait before polling again.
     *
     * Fast while something is happening, slower when nothing is — but never slower than the
     * relay's idea of "connected", or the browser would keep appearing to have gone away
     * between polls. The relay reports that window in its pairing response; this ceiling is
     * comfortably inside the default of fifteen seconds.
     */
    private fun nextDelayMs(idleMs: Long): Long = when {
        idleMs < 10_000 -> 1_000
        idleMs < 60_000 -> 2_000
        else -> 5_000
    }

    fun start(scope: CoroutineScope, page: () -> Pair<String?, String?>) {
        job = scope.launch {
            DebugLog.log("agent", "session ${pairing.sessionId} polling ${relay.browserEndpoint}")
            var lastWorkAt = System.currentTimeMillis()
            while (isActive) {
                val (url, title) = page()
                val command = try {
                    relay.poll(pairing.browserToken, url, title)
                } catch (e: RelayException) {
                    if (e.isSessionOver) {
                        // Nothing to retry: the pairing is gone and only the user can make a
                        // new one. Saying so beats a loop that quietly never works again.
                        end("session_expired")
                        return@launch
                    }
                    null
                } catch (t: Throwable) {
                    // A dropped connection is ordinary on a phone. The relay decides when the
                    // silence has gone on long enough; here it is just another poll.
                    DebugLog.log("agent", "poll failed: $t")
                    null
                }

                if (command != null) {
                    lastWorkAt = System.currentTimeMillis()
                    val response = execute(command)
                    runCatching { relay.result(pairing.browserToken, command.id, response) }
                        .onFailure { DebugLog.log("agent", "result failed: $it") }
                    // Something is going on: come straight back rather than waiting out an
                    // idle interval in the middle of a task.
                    delay(200)
                } else {
                    delay(nextDelayMs(System.currentTimeMillis() - lastWorkAt))
                }
            }
        }
    }

    private suspend fun execute(command: Command): JSONObject {
        val op = command.request.optString("op")
        DebugLog.log("agent", "command ${command.id} $op")
        return when (op) {
            "eval" -> PageScript.run(
                webView,
                command.request.optString("js"),
                command.request.optBoolean("settle", true),
            )
            else -> JSONObject()
                .put("error", "unknown_op")
                .put("message", "This browser does not know how to do '$op'.")
        }
    }

    /** Stop polling and tell the relay, so a stale session is not left to time out. */
    fun stop(scope: CoroutineScope) {
        job?.cancel()
        job = null
        scope.launch { relay.bye(pairing.browserToken) }
    }

    private fun end(reason: String) {
        DebugLog.log("agent", "session ended: $reason")
        job = null
        onEnded(reason)
    }

    val agentToken: String get() = pairing.agentToken
    val mcpEndpoint: String get() = relay.mcpEndpoint
}
