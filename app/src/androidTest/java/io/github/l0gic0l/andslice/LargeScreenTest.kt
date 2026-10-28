package io.github.l0gic0l.andslice

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.l0gic0l.andslice.ui.AndSliceApp
import io.github.l0gic0l.andslice.ui.theme.AndSliceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app laid out as on an unfolded foldable, whatever the test device's screen. */
@RunWith(AndroidJUnit4::class)
class LargeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) =
        compose.waitUntil(timeoutMillis) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun halfPanelShowsSettingsBesideThePlate() {
        val models = compose.runOnUiThread { ViewModelProvider(compose.activity) }
        val slicer = compose.runOnUiThread { models[SlicerViewModel::class.java] }
        val printer = compose.runOnUiThread { models[PrinterViewModel::class.java] }
        val prefs = compose.runOnUiThread { models[PreferencesViewModel::class.java].apply { sidePanel = PanelMode.Half } }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(900.dp, 800.dp))) {
                AndSliceTheme { AndSliceApp(slicer, printer, prefs) }
            }
        }

        waitForText("The plate is empty", timeoutMillis = 30_000)
        // The navigation rail, with the app preferences at its bottom.
        compose.onNodeWithText("Preferences").assertExists()
        // The slicing settings fill the other half of the plate screen.
        compose.onNodeWithTag("settings-search").assertExists()
        compose.onNodeWithText("Infill").performClick()
        waitForText("Fill density")
    }
}
