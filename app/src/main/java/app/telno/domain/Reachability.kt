package app.telno.domain

/**
 * What the home screen reports about inbound reachability (SPEC "Reachability").
 * The failure mode this surface exists to prevent is a phone that silently
 * stops ringing, so the derivation is deliberately pessimistic: anything not
 * known-good reads as not reachable, with the reason.
 */
enum class Reachability {
    /** No Telnyx credentials configured yet; calls can't work in either direction. */
    NOT_SET_UP,

    /** Credentials exist but the push-token binding is not known to be current. */
    UNREACHABLE,

    /** Credentials configured and the push-token binding believed current. */
    REACHABLE,
}

/** The facts reachability is derived from; each is false until proven true. */
data class ReachabilityInputs(
    val credentialsConfigured: Boolean = false,
    val tokenBound: Boolean = false,
)

fun deriveReachability(inputs: ReachabilityInputs): Reachability = when {
    !inputs.credentialsConfigured -> Reachability.NOT_SET_UP
    !inputs.tokenBound -> Reachability.UNREACHABLE
    else -> Reachability.REACHABLE
}
