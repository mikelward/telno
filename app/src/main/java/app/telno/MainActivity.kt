package app.telno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.telno.domain.ReachabilityInputs
import app.telno.domain.deriveReachability
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
            TelnoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // No credential store exists yet, so a fresh install is the
                    // only state there is; real inputs arrive with Phase 2.
                    HomeScreen(deriveReachability(ReachabilityInputs()))
                }
            }
        }
    }
}
