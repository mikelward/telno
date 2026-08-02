package app.telno.domain

/**
 * The Telnyx SIP connection credentials the user supplies at setup (SPEC
 * "Product shape": bring your own Telnyx account). The password is sensitive:
 * it is stored only through the encrypted store and must never appear in logs,
 * errors, or test fixtures with real values.
 */
data class TelnyxAccount(
    val username: String,
    val password: String,
) {
    /** True when both fields are usable; blank credentials can never log in. */
    fun isComplete(): Boolean = username.isNotBlank() && password.isNotBlank()
}
