package app.telno.store

import app.telno.domain.TelnyxAccount
import java.util.Base64

/**
 * Serializes a [TelnyxAccount] to and from a compact string, so the encrypted
 * store only deals with opaque bytes.
 *
 * The format is a versioned, newline-separated list of Base64-encoded fields.
 * Base64 keeps every value — passwords with newlines, spaces, or arbitrary
 * Unicode — round-trip-safe without escaping, and the leading magic/version
 * lets the format evolve. [decode] fails closed: any malformed or
 * wrong-version input returns `null` (treated as "no stored account") rather
 * than throwing, so a corrupted store can never crash account load.
 *
 * Pure and framework-independent — unit-tested without a device. (The
 * encryption around it lives in the Keystore-backed store and is device-only.)
 */
object AccountCodec {

    private const val HEADER = "telno-account/1"
    private val b64Encoder: Base64.Encoder = Base64.getEncoder()
    private val b64Decoder: Base64.Decoder = Base64.getDecoder()

    fun encode(account: TelnyxAccount): String = buildString {
        appendLine(HEADER)
        appendLine(enc(account.username))
        append(enc(account.password))
    }

    fun decode(text: String): TelnyxAccount? {
        val lines = text.split('\n')
        if (lines.size < 3 || lines[0].trimEnd('\r') != HEADER) return null
        return try {
            TelnyxAccount(
                username = dec(lines[1]),
                password = dec(lines[2]),
            )
        } catch (_: IllegalArgumentException) {
            // Bad Base64 — treat the store as empty rather than crash.
            null
        }
    }

    private fun enc(value: String): String = b64Encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun dec(value: String): String = String(b64Decoder.decode(value.trimEnd('\r')), Charsets.UTF_8)
}
