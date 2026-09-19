package jp.pai.screennote.agent

import jp.pai.screennote.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A pairing: what the relay hands back when agent control is switched on. */
data class Pairing(
    /** Authenticates this device. Never shown, never leaves the app. */
    val browserToken: String,
    /** Shown to the user to paste into the conversation. Eight characters. */
    val agentToken: String,
    val sessionId: Int,
)

/** One instruction from the relay, and the id the answer has to quote. */
data class Command(val id: Int, val request: JSONObject)

/**
 * The phone's side of the relay protocol. See `server/browser.php`.
 *
 * One URL and one POST per operation, with the operation named in the body, so nothing here
 * depends on how the server maps paths.
 *
 * [baseUrl] is the relay's prefix — `https://host/rbmcp` — from which both endpoints follow.
 * The MCP endpoint is derived only to show the user which connector the token belongs to;
 * this app never calls it.
 */
class Relay(private val baseUrl: String) {

    val browserEndpoint: String get() = "${baseUrl.trimEnd('/')}/browser"
    val mcpEndpoint: String get() = "${baseUrl.trimEnd('/')}/mcp"

    suspend fun pair(deviceId: String): Pairing {
        val body = post(JSONObject().put("op", "pair").put("device_id", deviceId))
        return Pairing(
            browserToken = body.getString("browser_token"),
            agentToken = body.getString("agent_token"),
            sessionId = body.optInt("session_id"),
        )
    }

    /**
     * Report that this browser is alive and what it is showing, and collect the next command.
     *
     * The state travels on every poll so the relay can answer `browser_status` out of its own
     * database. That makes the status tool free, and — more to the point — able to say
     * something useful at the moment the phone has stopped answering.
     */
    suspend fun poll(browserToken: String, url: String?, title: String?): Command? {
        val state = JSONObject()
        url?.let { state.put("url", it) }
        title?.let { state.put("title", it) }
        val body = post(
            JSONObject()
                .put("op", "poll")
                .put("browser_token", browserToken)
                .put("state", state)
        )
        val command = body.optJSONObject("command") ?: return null
        return Command(command.getInt("id"), command.getJSONObject("request"))
    }

    suspend fun result(browserToken: String, commandId: Int, response: JSONObject) {
        post(
            JSONObject()
                .put("op", "result")
                .put("browser_token", browserToken)
                .put("command_id", commandId)
                .put("response", response)
        )
    }

    /** End the session now rather than letting it time out. Failure is not worth reporting. */
    suspend fun bye(browserToken: String) {
        runCatching { post(JSONObject().put("op", "bye").put("browser_token", browserToken)) }
    }

    private suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(browserEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                // The relay's refusals are meaningful — an unknown token means this pairing is
                // over — so the body goes into the exception rather than just the status.
                throw RelayException(code, text.take(200))
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}

class RelayException(val status: Int, val body: String) :
    IOException("relay HTTP $status: $body") {

    /**
     * True when the relay has stopped recognising this device.
     *
     * Not an error to retry through: the session is gone, and the only way back is a new
     * pairing, which only the user can start.
     */
    val isSessionOver: Boolean get() = status == 401

    init {
        DebugLog.log("relay", "HTTP $status $body")
    }
}
