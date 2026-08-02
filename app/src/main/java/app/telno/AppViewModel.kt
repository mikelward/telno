package app.telno

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.telno.domain.Reachability
import app.telno.domain.ReachabilityInputs
import app.telno.domain.TelnyxAccount
import app.telno.domain.deriveReachability
import app.telno.store.AccountLoadResult
import app.telno.store.AccountStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which screen the single-activity UI shows. */
enum class Screen { HOME, SETUP }

/**
 * The app's whole UI state; the screens render from it and never touch the
 * store directly. `reachability == null` only during the initial store read —
 * the home screen still appears immediately with a checking placeholder
 * (principle 5: the frame is never delayed).
 */
data class AppUiState(
    val screen: Screen = Screen.HOME,
    val reachability: Reachability? = null,
    /**
     * True when stored credentials exist but can't be read (Keystore loss,
     * corruption): shown distinctly from a fresh install so the user knows
     * their saved account needs attention rather than being silently invited
     * to overwrite it.
     */
    val accountUnreadable: Boolean = false,
    val setupUsername: String = "",
    val setupPassword: String = "",
    val setupSaving: Boolean = false,
    val setupError: SetupError? = null,
)

enum class SetupError { BLANK_FIELDS, SAVE_FAILED }

class AppViewModel(
    private val store: AccountStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    /**
     * Guards in-flight store reads against applying stale state: each read
     * captures the generation at launch and applies its result only if still
     * current. Bumped by every newer read and by a successful save, so a slow
     * decrypt of the old record can't overwrite the account the user just
     * replaced with a stale unreadable/loaded result (Codex on PR #3). Only
     * touched on the main dispatcher, so no synchronization is needed.
     */
    private var loadGeneration = 0

    init {
        val generation = ++loadGeneration
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { store.load() }
            if (generation != loadGeneration) {
                // Deliberately dropped: a newer read or a save superseded this
                // result while the store was being read.
                Log.d(TAG, "Discarded a superseded account read")
                return@launch
            }
            _uiState.update { it.applyLoadResult(result) }
        }
    }

    fun openSetup() {
        // Navigate immediately — a slow Keystore read must not make the tap
        // look ignored (principle 5); the screen appears at once and fills in.
        _uiState.update {
            it.copy(screen = Screen.SETUP, setupUsername = "", setupPassword = "", setupError = null)
        }
        // Then prefill the username so editing doesn't force retyping; the
        // password is never echoed back out of the store into the UI. The load
        // result is applied to state, so a transient Unreadable here is
        // preserved — the setup screen shows its replace-warning instead of
        // posing as a blank fresh install (Codex on PR #3).
        val generation = ++loadGeneration
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { store.load() }
            if (generation != loadGeneration) {
                Log.d(TAG, "Discarded a superseded account read")
                return@launch
            }
            val username = (result as? AccountLoadResult.Loaded)?.account?.username.orEmpty()
            _uiState.update { state ->
                val withResult = state.applyLoadResult(result)
                // Don't clobber anything the user typed while the read ran,
                // and don't touch the field after they navigated away.
                if (withResult.screen == Screen.SETUP && withResult.setupUsername.isEmpty()) {
                    withResult.copy(setupUsername = username)
                } else {
                    withResult
                }
            }
        }
    }

    fun closeSetup() {
        _uiState.update { it.copy(screen = Screen.HOME, setupPassword = "", setupError = null) }
    }

    fun setSetupUsername(value: String) {
        _uiState.update { it.copy(setupUsername = value, setupError = null) }
    }

    fun setSetupPassword(value: String) {
        _uiState.update { it.copy(setupPassword = value, setupError = null) }
    }

    fun saveSetup() {
        val state = _uiState.value
        val account = TelnyxAccount(
            username = state.setupUsername.trim(),
            password = state.setupPassword,
        )
        if (!account.isComplete()) {
            _uiState.update { it.copy(setupError = SetupError.BLANK_FIELDS) }
            return
        }
        _uiState.update { it.copy(setupSaving = true, setupError = null) }
        viewModelScope.launch {
            val saved = try {
                withContext(ioDispatcher) { store.save(account) }
                true
            } catch (e: IOException) {
                // Sanitized: the operation, never the credentials (AGENTS Privacy).
                Log.w(TAG, "Account save failed: ${e.javaClass.simpleName}")
                false
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, "Account encryption failed: ${e.javaClass.simpleName}")
                false
            } catch (e: ProviderException) {
                // AndroidKeyStore surfaces provider/hardware failures (e.g. from
                // key generation) as this RuntimeException; without this catch it
                // would crash the app and strand setupSaving. Cancellation still
                // propagates — CancellationException is not a ProviderException.
                Log.w(TAG, "Keystore provider failed: ${e.javaClass.simpleName}")
                false
            }
            if (saved) {
                // The account on disk just changed: supersede any store read
                // still in flight so its stale result can't land on top of the
                // fresher state below. A failed save bumps nothing — the store
                // is unchanged, so a pending read is still the truth (and may
                // be what fills in reachability).
                loadGeneration++
            }
            _uiState.update {
                if (saved) {
                    it.copy(
                        screen = Screen.HOME,
                        reachability = reachabilityFor(account),
                        accountUnreadable = false,
                        setupSaving = false,
                        setupPassword = "",
                    )
                } else {
                    it.copy(setupSaving = false, setupError = SetupError.SAVE_FAILED)
                }
            }
        }
    }

    private fun AppUiState.applyLoadResult(result: AccountLoadResult): AppUiState = when (result) {
        is AccountLoadResult.Loaded -> copy(
            reachability = reachabilityFor(result.account),
            accountUnreadable = false,
        )
        AccountLoadResult.Empty -> copy(
            reachability = reachabilityFor(null),
            accountUnreadable = false,
        )
        // Unreadable is NOT a fresh install: surface it (the home card says the
        // saved account needs attention) instead of quietly offering to
        // overwrite whatever might be recoverable.
        AccountLoadResult.Unreadable -> copy(
            reachability = reachabilityFor(null),
            accountUnreadable = true,
        )
    }

    private fun reachabilityFor(account: TelnyxAccount?): Reachability =
        deriveReachability(
            ReachabilityInputs(
                credentialsConfigured = account?.isComplete() == true,
                // No push binding exists yet (TODO.md Phase 3); credentials alone
                // honestly read as "can't receive calls".
                tokenBound = false,
            ),
        )

    private companion object {
        const val TAG = "TelnoApp"
    }
}
