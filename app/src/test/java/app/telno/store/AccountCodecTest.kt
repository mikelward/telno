package app.telno.store

import app.telno.domain.TelnyxAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountCodecTest {

    @Test
    fun `round-trips ordinary credentials`() {
        val account = TelnyxAccount(username = "example-user", password = "example-pass")
        assertEquals(account, AccountCodec.decode(AccountCodec.encode(account)))
    }

    @Test
    fun `round-trips hostile values`() {
        // Newlines, spaces, Unicode, and the format's own delimiters must all
        // survive — Base64 is what makes escaping unnecessary.
        val account = TelnyxAccount(
            username = "user name\nwith newline",
            password = "p√ass\r\nwörd/1==\n-+",
        )
        assertEquals(account, AccountCodec.decode(AccountCodec.encode(account)))
    }

    @Test
    fun `decode fails closed on garbage`() {
        assertNull(AccountCodec.decode(""))
        assertNull(AccountCodec.decode("not-the-header\nAAAA\nBBBB"))
        assertNull(AccountCodec.decode("telno-account/1\n!!!not-base64!!!\nAAAA"))
        assertNull(AccountCodec.decode("telno-account/1\nAAAA"))
    }

    @Test
    fun `decode rejects other versions rather than misreading them`() {
        assertNull(AccountCodec.decode("telno-account/2\nAAAA\nBBBB"))
    }
}
