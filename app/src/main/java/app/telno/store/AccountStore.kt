package app.telno.store

import app.telno.domain.TelnyxAccount

/** The three ways a load can end; callers must treat each distinctly. */
sealed interface AccountLoadResult {
    /** A stored account was read successfully. */
    data class Loaded(val account: TelnyxAccount) : AccountLoadResult

    /** Nothing has ever been stored (fresh install, or cleared). */
    data object Empty : AccountLoadResult

    /**
     * Stored data exists but can't be read — Keystore unavailable, key
     * invalidated (e.g. lock screen removed), or corrupt ciphertext. Distinct
     * from [Empty] so the UI can say "your saved account needs attention"
     * instead of silently presenting a fresh-install setup that would
     * overwrite whatever is recoverable (principles 1 and 2).
     */
    data object Unreadable : AccountLoadResult
}

/**
 * Persists the single Telnyx account Telno logs in with. The credentials never
 * leave the device by default: they're encrypted at rest (see
 * [EncryptedAccountStore]) and the backing file is excluded from cloud backup
 * and device-to-device transfer (see `res/xml/data_extraction_rules.xml`) — a
 * silently restored credential on another device would also silently steal the
 * ring (SPEC "Persistence").
 */
interface AccountStore {
    /** Persists [account], replacing any previously stored one. */
    fun save(account: TelnyxAccount)

    /** Reads the stored account; never throws — failures come back as [AccountLoadResult.Unreadable]. */
    fun load(): AccountLoadResult

    /** Removes the stored account and its encryption key. */
    fun clear()
}
