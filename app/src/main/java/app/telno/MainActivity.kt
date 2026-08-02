package app.telno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.telno.store.EncryptedAccountStore
import app.telno.ui.AccountSetupScreen
import app.telno.ui.HomeScreen
import app.telno.ui.theme.TelnoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Styles system-bar icons to match the resolved light/dark theme; the
        // platform theme alone would leave light icons over a light surface in
        // light mode (Codex on PR #2).
        enableEdgeToEdge()
        setContent {
            val viewModel: AppViewModel = viewModel {
                AppViewModel(EncryptedAccountStore(applicationContext))
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            TelnoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (state.screen) {
                        Screen.HOME -> HomeScreen(
                            reachability = state.reachability,
                            accountUnreadable = state.accountUnreadable,
                            onSetUp = viewModel::openSetup,
                        )
                        Screen.SETUP -> AccountSetupScreen(
                            username = state.setupUsername,
                            password = state.setupPassword,
                            saving = state.setupSaving,
                            accountUnreadable = state.accountUnreadable,
                            error = state.setupError,
                            onUsernameChange = viewModel::setSetupUsername,
                            onPasswordChange = viewModel::setSetupPassword,
                            onSave = viewModel::saveSetup,
                            onBack = viewModel::closeSetup,
                        )
                    }
                }
            }
        }
    }
}
