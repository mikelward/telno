package app.telno.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.telno.R
import app.telno.SetupError

/**
 * The Telnyx account form (SPEC "UI architecture"): SIP connection username +
 * password, saved through the encrypted store. State is hoisted so the screen
 * is a pure function of its inputs and screenshot-testable.
 */
@Composable
fun AccountSetupScreen(
    username: String,
    password: String,
    saving: Boolean,
    accountUnreadable: Boolean,
    error: SetupError?,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // System Back must follow the same rules as the visible Back button: route
    // through setup navigation (never finish the activity and discard the
    // draft), and hold still while a save is in flight (Codex on PR #3).
    BackHandler {
        if (!saving) onBack()
    }
    // Scrollable + IME padding so Save and Back stay reachable above the
    // keyboard in landscape, split-screen, and other short windows.
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        if (accountUnreadable) {
            Text(
                text = stringResource(R.string.setup_unreadable_note),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.setup_username_label)) },
            singleLine = true,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.setup_password_label)) },
            singleLine = true,
            enabled = !saving,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (error != null) {
            Text(
                text = stringResource(
                    when (error) {
                        SetupError.BLANK_FIELDS -> R.string.setup_error_blank
                        SetupError.SAVE_FAILED -> R.string.setup_error_save
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(stringResource(if (saving) R.string.setup_saving else R.string.setup_save))
        }
        TextButton(
            onClick = onBack,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.setup_back))
        }
    }
}
