package jp.pai.screennote.agent

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The agent token, made on the phone, and the two things derived from it.
 *
 * The relay never sees the token. It gets the [sessionKey] — a hash — to route commands by,
 * and every command it hands over must carry a [sign]ature only the token's holder can make.
 * So a relay that is not the one the user meant (a mistyped address, a domain that changed
 * hands) can watch a session exist but cannot get the phone to do anything.
 *
 * Must match `relay_session_key()` and `relay_sign()` in server/relay.php byte for byte.
 */
object AgentToken {

    /** Crockford base32: no I, L, O or U, so nothing in a token reads as something else. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 160 bits. Long, but the user copies it rather than reading it, so length costs nothing. */
    const val LENGTH = 32

    private const val SESSION_KEY_PREFIX = "screennote-session:"

    fun generate(random: SecureRandom = SecureRandom()): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun sessionKey(token: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest((SESSION_KEY_PREFIX + normalise(token)).toByteArray()))

    fun sign(token: String, commandId: Int, requestJson: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(normalise(token).toByteArray(), "HmacSHA256"))
        return hex(mac.doFinal("$commandId\n$requestJson".toByteArray()))
    }

    /** Constant-time, so a relay probing signatures learns nothing from how long a refusal took. */
    fun verify(token: String, commandId: Int, requestJson: String, signature: String): Boolean =
        MessageDigest.isEqual(
            sign(token, commandId, requestJson).toByteArray(),
            signature.lowercase().toByteArray(),
        )

    private fun normalise(token: String) = token.trim().uppercase()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
