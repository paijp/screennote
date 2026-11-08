package jp.pai.screennote.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTokenTest {

    private val token = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val request = """{"op":"eval","js":"1+1"}"""

    // Both vectors were computed with PHP's hash() / hash_hmac(), the functions relay.php uses.
    // If these drift, the phone and the relay stop agreeing and every command is refused.

    @Test fun sessionKeyMatchesTheRelay() =
        assertEquals("932856d736c3f3d01d374a2e380e81db4882e30f34048ac66e335a47ab6818f5", AgentToken.sessionKey(token))

    @Test fun signatureMatchesTheRelay() =
        assertEquals("65cc6a8de29344c388f9c6fb8e71f98fd7aa2c1782e5b3ba19e2aec041a651de", AgentToken.sign(token, 7, request))

    @Test fun pastedTokenIsNormalised() =
        assertEquals(AgentToken.sessionKey(token), AgentToken.sessionKey("  " + token.lowercase() + "\n"))

    @Test fun verifyAcceptsTheRightSignatureOnly() {
        val sig = AgentToken.sign(token, 7, request)
        assertTrue(AgentToken.verify(token, 7, request, sig))
        assertFalse(AgentToken.verify(token, 8, request, sig))                 // replayed under another id
        assertFalse(AgentToken.verify(token, 7, request.replace("1+1", "2"), sig)) // altered body
        assertFalse(AgentToken.verify("Z" + token.drop(1), 7, request, sig))   // made by someone else
    }

    @Test fun generatedTokensAreLongAndUnambiguous() {
        val t = AgentToken.generate()
        assertEquals(32, t.length)
        assertTrue(t.none { it in "ILOU" || it.isLowerCase() })
    }
}
