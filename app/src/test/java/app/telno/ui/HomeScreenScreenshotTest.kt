package app.telno.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.telno.domain.Reachability
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the home/status surface in each reachability state (SPEC "UI
 * architecture"). Uses MaterialTheme rather than TelnoTheme: dynamic color
 * reads device palettes, which would make snapshots environment-dependent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun home_notSetUp() {
        setHome(Reachability.NOT_SET_UP)
        composeRule.onNodeWithText("Not set up").assertExists()
        captureSnapshot("home_not_set_up.png")
    }

    @Test
    fun home_unreachable() {
        setHome(Reachability.UNREACHABLE)
        composeRule.onNodeWithText("Can't receive calls").assertExists()
        captureSnapshot("home_unreachable.png")
    }

    @Test
    fun home_reachable() {
        setHome(Reachability.REACHABLE)
        composeRule.onNodeWithText("Ready").assertExists()
        captureSnapshot("home_reachable.png")
    }

    private fun setHome(reachability: Reachability) {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    HomeScreen(reachability)
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
