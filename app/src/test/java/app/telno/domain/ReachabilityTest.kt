package app.telno.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reachability derivation as a table (SPEC "Reachability"): every input
 * combination has a defined outcome, and anything not known-good reads as not
 * reachable — the pessimism is the point, because the failure this surface
 * exists to catch is a phone that silently stops ringing.
 */
class ReachabilityTest {

    @Test
    fun `every input combination derives the documented state`() {
        val table = listOf(
            Triple(false, false, Reachability.NOT_SET_UP),
            Triple(false, true, Reachability.NOT_SET_UP),
            Triple(true, false, Reachability.UNREACHABLE),
            Triple(true, true, Reachability.REACHABLE),
        )
        for ((credentials, token, expected) in table) {
            assertEquals(
                "credentialsConfigured=$credentials tokenBound=$token",
                expected,
                deriveReachability(
                    ReachabilityInputs(
                        credentialsConfigured = credentials,
                        tokenBound = token,
                    ),
                ),
            )
        }
    }

    @Test
    fun `defaults are fully pessimistic`() {
        // A fresh install with nothing proven derives NOT_SET_UP, never a
        // hopeful default.
        assertEquals(Reachability.NOT_SET_UP, deriveReachability(ReachabilityInputs()))
    }
}
