package app.telno

import app.telno.domain.Reachability
import app.telno.domain.TelnyxAccount
import app.telno.store.AccountLoadResult
import app.telno.store.AccountStore
import java.io.IOException
import java.security.ProviderException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeStore(
    var stored: TelnyxAccount? = null,
    var failNextSave: Boolean = false,
    var failNextSaveWithProvider: Boolean = false,
    var unreadable: Boolean = false,
) : AccountStore {
    override fun save(account: TelnyxAccount) {
        if (failNextSave) throw IOException("disk full")
        if (failNextSaveWithProvider) throw ProviderException("keystore broke")
        stored = account
    }

    override fun load(): AccountLoadResult = when {
        unreadable -> AccountLoadResult.Unreadable
        stored != null -> AccountLoadResult.Loaded(stored!!)
        else -> AccountLoadResult.Empty
    }

    override fun clear() {
        stored = null
    }
}

/**
 * Dispatcher whose dispatched blocks run only when the test releases them, so
 * out-of-order I/O completion — a save finishing before an older, slower load —
 * is expressed explicitly rather than raced (AGENTS.md: make ordering
 * explicit, never sleep/retry).
 */
private class GateDispatcher : CoroutineDispatcher() {
    private val blocks = mutableListOf<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        blocks.add(block)
    }

    /** Runs the queued block at [index] on the caller's thread. */
    fun release(index: Int = 0) {
        blocks.removeAt(index).run()
    }
}

/** Robolectric only for android.util.Log in the save-failure path. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `fresh install derives NOT_SET_UP after the store read`() = runTest(dispatcher) {
        val viewModel = AppViewModel(FakeStore(), dispatcher)
        assertNull(viewModel.uiState.value.reachability)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(Reachability.NOT_SET_UP, viewModel.uiState.value.reachability)
    }

    @Test
    fun `stored credentials derive UNREACHABLE until a push binding exists`() = runTest(dispatcher) {
        val store = FakeStore(stored = TelnyxAccount("example-user", "example-pass"))
        val viewModel = AppViewModel(store, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(Reachability.UNREACHABLE, viewModel.uiState.value.reachability)
    }

    @Test
    fun `save persists, returns home, and clears the password from UI state`() = runTest(dispatcher) {
        val store = FakeStore()
        val viewModel = AppViewModel(store, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openSetup()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.setSetupUsername(" example-user ")
        viewModel.setSetupPassword("example-pass")
        viewModel.saveSetup()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(TelnyxAccount("example-user", "example-pass"), store.stored)
        assertEquals(Screen.HOME, state.screen)
        assertEquals(Reachability.UNREACHABLE, state.reachability)
        assertEquals("", state.setupPassword)
        assertNull(state.setupError)
    }

    @Test
    fun `blank fields are refused before touching the store`() = runTest(dispatcher) {
        val store = FakeStore()
        val viewModel = AppViewModel(store, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openSetup()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.setSetupUsername("   ")
        viewModel.setSetupPassword("")
        viewModel.saveSetup()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SetupError.BLANK_FIELDS, viewModel.uiState.value.setupError)
        assertNull(store.stored)
        assertEquals(Screen.SETUP, viewModel.uiState.value.screen)
    }

    @Test
    fun `a failed save stays on setup with a visible error, never silent`() = runTest(dispatcher) {
        val store = FakeStore(failNextSave = true)
        val viewModel = AppViewModel(store, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openSetup()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.setSetupUsername("example-user")
        viewModel.setSetupPassword("example-pass")
        viewModel.saveSetup()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SetupError.SAVE_FAILED, state.setupError)
        assertEquals(Screen.SETUP, state.screen)
        assertEquals(false, state.setupSaving)
        assertNull(store.stored)
    }

    @Test
    fun `a Keystore provider failure surfaces as a save error, not a crash`() = runTest(dispatcher) {
        val store = FakeStore(failNextSaveWithProvider = true)
        val viewModel = AppViewModel(store, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openSetup()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.setSetupUsername("example-user")
        viewModel.setSetupPassword("example-pass")
        viewModel.saveSetup()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(SetupError.SAVE_FAILED, state.setupError)
        assertEquals(false, state.setupSaving)
        assertNull(store.stored)
    }

    @Test
    fun `an unreadable store surfaces distinctly, never as a fresh install`() = runTest(dispatcher) {
        val viewModel = AppViewModel(FakeStore(unreadable = true), dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(true, state.accountUnreadable)
        // Reachability is honestly not-reachable, but the UI layer renders the
        // distinct needs-attention card from the flag, and setup stays open as
        // the recovery path.
        assertEquals(Reachability.NOT_SET_UP, state.reachability)
    }

    @Test
    fun `saving over an unreadable store clears the needs-attention state`() = runTest(dispatcher) {
        val store = FakeStore(unreadable = true)
        val viewModel = AppViewModel(store, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openSetup()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.setSetupUsername("example-user")
        viewModel.setSetupPassword("example-pass")
        store.unreadable = false
        viewModel.saveSetup()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.uiState.value.accountUnreadable)
        assertEquals(Reachability.UNREACHABLE, viewModel.uiState.value.reachability)
    }

    @Test
    fun `reopening setup preserves a transient unreadable result`() = runTest(dispatcher) {
        val store = FakeStore(stored = TelnyxAccount("example-user", "example-pass"))
        val viewModel = AppViewModel(store, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        // The account loaded fine at startup, then the store degrades before
        // the user reopens setup: the distinct failure state must survive.
        store.unreadable = true
        viewModel.openSetup()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(Screen.SETUP, state.screen)
        assertEquals(true, state.accountUnreadable)
        assertEquals("", state.setupUsername)
    }

    @Test
    fun `a save that finishes before a slow setup load wins over the stale result`() = runTest(dispatcher) {
        val io = GateDispatcher()
        val store = FakeStore(unreadable = true)
        val viewModel = AppViewModel(store, io)
        dispatcher.scheduler.advanceUntilIdle()
        io.release() // Startup read completes: unreadable account surfaces.
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.accountUnreadable)

        viewModel.openSetup() // Queues a re-read of the (still corrupt) record.
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.setSetupUsername("example-user")
        viewModel.setSetupPassword("example-pass")
        viewModel.saveSetup() // Queues the save behind the pending read.
        dispatcher.scheduler.advanceUntilIdle()

        // The save completes while the old read is still decrypting…
        io.release(index = 1)
        dispatcher.scheduler.advanceUntilIdle()
        // …then the stale read resolves Unreadable. It must be discarded, not
        // reapplied over the account the user just saved.
        io.release()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(Screen.HOME, state.screen)
        assertEquals(false, state.accountUnreadable)
        assertEquals(Reachability.UNREACHABLE, state.reachability)
    }

    @Test
    fun `reopening setup prefills the username but never the password`() = runTest(dispatcher) {
        val store = FakeStore(stored = TelnyxAccount("example-user", "example-pass"))
        val viewModel = AppViewModel(store, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.openSetup()
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("example-user", state.setupUsername)
        assertEquals("", state.setupPassword)
    }
}
