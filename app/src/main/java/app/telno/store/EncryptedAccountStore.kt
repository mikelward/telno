package app.telno.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import app.telno.domain.TelnyxAccount
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [AccountStore] that encrypts the account at rest with an AES-256-GCM key
 * held in the Android Keystore, so the raw password never touches disk in the
 * clear. The ciphertext and its per-save random IV are stored in the
 * `telno_account` SharedPreferences file (`telno_account.xml`), which
 * `res/xml/data_extraction_rules.xml` / `backup_rules.xml` exclude from cloud
 * backup and device transfer. Mirrors Phomo's proven store.
 *
 * The key is hardware-backed on devices with a StrongBox / TEE. No per-use
 * user authentication is required: login happens at call time or push wake and
 * must never block on a biometric prompt (the account is still protected by
 * the Keystore and the device lock screen).
 *
 * The Keystore and Cipher paths cannot run under Robolectric — this class is
 * verified on a device, not by the JVM unit tests. The serialization it wraps
 * ([AccountCodec]) is unit-tested separately.
 */
class EncryptedAccountStore(context: Context) : AccountStore {

    private val appContext = context.applicationContext

    override fun save(account: TelnyxAccount) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        } catch (e: InvalidKeyException) {
            // The stored key exists but is unusable (e.g. permanently
            // invalidated by a lock-screen change). The user is explicitly
            // saving replacement credentials, and the old ciphertext is
            // unreadable with a dead key anyway — so replace the key rather
            // than failing every save until app data is cleared (Codex on
            // PR #3). One retry only: a second failure propagates to the
            // caller's visible save-error state.
            Log.w(TAG, "Replacing unusable Keystore key: ${e.javaClass.simpleName}")
            keyStore().deleteEntry(KEY_ALIAS)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val ciphertext = cipher.doFinal(AccountCodec.encode(account).toByteArray(Charsets.UTF_8))
        // commit() (synchronous, returns success) rather than apply(): callers run
        // save() off the main thread, and the UI must not report "saved" until the
        // credential is durably on disk (principle 2: never lose the user's work).
        val committed = prefs().edit()
            .putString(KEY_IV, encodeBase64(cipher.iv))
            .putString(KEY_DATA, encodeBase64(ciphertext))
            .commit()
        if (!committed) throw IOException("Failed to persist the Telnyx account")
    }

    override fun load(): AccountLoadResult {
        val ivB64: String?
        val dataB64: String?
        try {
            ivB64 = prefs().getString(KEY_IV, null)
            dataB64 = prefs().getString(KEY_DATA, null)
        } catch (e: ClassCastException) {
            // A corrupted or schema-drifted prefs file can hold these keys with
            // a non-string type; load() promises never to throw, so this too is
            // the visible Unreadable state rather than a crash or a stuck
            // "Checking…".
            Log.w(TAG, "Account unreadable: stored record has wrong types")
            return AccountLoadResult.Unreadable
        }
        if (ivB64 == null && dataB64 == null) return AccountLoadResult.Empty
        if (ivB64 == null || dataB64 == null) {
            // Exactly one half of the record survived: that is corruption, not
            // a fresh install — surface it rather than inviting an overwrite.
            Log.w(TAG, "Account unreadable: partial stored record")
            return AccountLoadResult.Unreadable
        }
        // From here on, stored data exists: any failure is Unreadable, never
        // Empty — a fresh-install answer would invite the user to overwrite an
        // account that might be recoverable, and would hide the failure
        // (principles 1 and 2). Each path logs a sanitized reason: the failure
        // mode, never the credentials.
        return try {
            val key = existingKey()
            if (key == null) {
                Log.w(TAG, "Account unreadable: ciphertext present but the Keystore key is gone")
                return AccountLoadResult.Unreadable
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, decodeBase64(ivB64)))
            val plaintext = cipher.doFinal(decodeBase64(dataB64))
            val account = AccountCodec.decode(String(plaintext, Charsets.UTF_8))
            if (account == null) {
                Log.w(TAG, "Account unreadable: decrypted blob failed to decode")
                AccountLoadResult.Unreadable
            } else {
                AccountLoadResult.Loaded(account)
            }
        } catch (e: GeneralSecurityException) {
            // Key invalidated (e.g. lock screen removed) or tampered ciphertext.
            Log.w(TAG, "Account unreadable: ${e.javaClass.simpleName}")
            AccountLoadResult.Unreadable
        } catch (e: IllegalArgumentException) {
            // Corrupt Base64 in the stored blob.
            Log.w(TAG, "Account unreadable: corrupt stored encoding")
            AccountLoadResult.Unreadable
        } catch (e: IOException) {
            // Keystore unavailable / unreadable (e.g. KeyStore.load I/O error).
            Log.w(TAG, "Account unreadable: ${e.javaClass.simpleName}")
            AccountLoadResult.Unreadable
        } catch (e: ProviderException) {
            // AndroidKeyStore surfaces hardware/service failures as this
            // RuntimeException; load() promises never to throw, so it becomes
            // the same visible Unreadable state.
            Log.w(TAG, "Account unreadable: ${e.javaClass.simpleName}")
            AccountLoadResult.Unreadable
        }
    }

    override fun clear() {
        prefs().edit().clear().apply()
        try {
            keyStore().deleteEntry(KEY_ALIAS)
        } catch (e: GeneralSecurityException) {
            // The account data itself is already gone (prefs cleared above), so
            // the clear still achieved its purpose; the orphaned key is inert
            // without ciphertext and gets replaced on the next save. Logged so
            // an incomplete cleanup is diagnosable rather than silent.
            Log.w(TAG, "Account key not deleted: ${e.javaClass.simpleName}")
        } catch (e: IOException) {
            Log.w(TAG, "Account key not deleted: ${e.javaClass.simpleName}")
        }
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private companion object {
        const val PREFS_NAME = "telno_account"
        const val KEY_ALIAS = "telno_account_key"
        const val KEY_IV = "iv"
        const val KEY_DATA = "data"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val TAG = "TelnoStore"
    }
}
