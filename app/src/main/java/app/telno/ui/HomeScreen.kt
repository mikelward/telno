package app.telno.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.telno.R
import app.telno.domain.Reachability

/**
 * The home/status surface (SPEC "UI architecture"): the app's name, the
 * reachability state, and the way into setup. `reachability == null` covers
 * only the initial store read — the frame still appears at once with a
 * checking placeholder (principle 5), then fills in.
 */
@Composable
fun HomeScreen(
    reachability: Reachability?,
    accountUnreadable: Boolean,
    onSetUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Edge-to-edge is enforced at targetSdk 36: consume the safe-drawing
    // insets here so content clears the status bar, cutout, and gesture nav.
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(
                        when {
                            accountUnreadable -> R.string.home_status_account_error
                            reachability == null -> R.string.home_status_checking
                            reachability == Reachability.NOT_SET_UP -> R.string.home_status_not_set_up
                            reachability == Reachability.UNREACHABLE -> R.string.home_status_unreachable
                            else -> R.string.home_status_reachable
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                val bodyRes = when {
                    accountUnreadable -> R.string.home_body_account_error
                    reachability == Reachability.NOT_SET_UP -> R.string.home_body_not_set_up
                    reachability == Reachability.UNREACHABLE -> R.string.home_body_unreachable
                    reachability == Reachability.REACHABLE -> R.string.home_body_reachable
                    else -> null
                }
                if (bodyRes != null) {
                    Text(
                        text = stringResource(bodyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        // Setup stays reachable after credentials exist — a mistyped password
        // must be fixable without clearing app data (principle 2; Codex on
        // PR #3). The label shifts from the call to action to a neutral one.
        if (reachability != null || accountUnreadable) {
            Button(
                onClick = onSetUp,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) {
                Text(
                    stringResource(
                        if (reachability == Reachability.NOT_SET_UP || accountUnreadable) {
                            R.string.home_set_up
                        } else {
                            R.string.home_account
                        },
                    ),
                )
            }
        }
    }
}
