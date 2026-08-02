package app.telno.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
 * The home/status surface (SPEC "UI architecture"): the app's name and the
 * reachability state, rendered from in-memory state so the first frame is the
 * real content. Until setup exists the only state a fresh install can be in is
 * [Reachability.NOT_SET_UP].
 */
@Composable
fun HomeScreen(reachability: Reachability, modifier: Modifier = Modifier) {
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
                        when (reachability) {
                            Reachability.NOT_SET_UP -> R.string.home_status_not_set_up
                            Reachability.UNREACHABLE -> R.string.home_status_unreachable
                            Reachability.REACHABLE -> R.string.home_status_reachable
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        when (reachability) {
                            Reachability.NOT_SET_UP -> R.string.home_body_not_set_up
                            Reachability.UNREACHABLE -> R.string.home_body_unreachable
                            Reachability.REACHABLE -> R.string.home_body_reachable
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
