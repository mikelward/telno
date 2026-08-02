package app.telno.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.telno.SetupError
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Telnyx account form (SPEC "UI architecture"): empty, filled, and
 * failed-save states. Fixture values are obviously fake, never anyone's real
 * credentials (AGENTS.md Privacy).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountSetupScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun setup_empty() {
        setSetup(username = "", password = "", error = null)
        composeRule.onNodeWithText("Telnyx account").assertExists()
        captureSnapshot("setup_empty.png")
    }

    @Test
    fun setup_filled() {
        setSetup(username = "example-user", password = "example-pass", error = null)
        captureSnapshot("setup_filled.png")
    }

    @Test
    fun setup_unreadableAccount() {
        setSetup(username = "", password = "", error = null, accountUnreadable = true)
        composeRule.onNodeWithText("Your saved credentials can't be read. Saving replaces them.").assertExists()
        captureSnapshot("setup_unreadable.png")
    }

    @Test
    fun setup_saveFailed() {
        setSetup(username = "example-user", password = "example-pass", error = SetupError.SAVE_FAILED)
        composeRule.onNodeWithText("Couldn't save. Try again.").assertExists()
        captureSnapshot("setup_save_failed.png")
    }

    private fun setSetup(
        username: String,
        password: String,
        error: SetupError?,
        accountUnreadable: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    AccountSetupScreen(
                        username = username,
                        password = password,
                        saving = false,
                        accountUnreadable = accountUnreadable,
                        error = error,
                        onUsernameChange = {},
                        onPasswordChange = {},
                        onSave = {},
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
