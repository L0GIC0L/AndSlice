package io.github.l0gic0l.andslice

import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.l0gic0l.andslice.ui.AndSliceApp
import io.github.l0gic0l.andslice.ui.theme.AndSliceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** The app laid out as on an unfolded foldable, whatever the test device's screen. */
@RunWith(AndroidJUnit4::class)
class LargeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) =
        compose.waitUntil(timeoutMillis) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    private fun startApp(): SlicerViewModel {
        val models = compose.runOnUiThread { ViewModelProvider(compose.activity) }
        // The plate of the last run would come back; start from an empty one.
        File(compose.activity.filesDir, "plate/plate.3mf").delete()
        val slicer = compose.runOnUiThread { models[SlicerViewModel::class.java] }
        val printer = compose.runOnUiThread { models[PrinterViewModel::class.java] }
        val prefs = compose.runOnUiThread { models[PreferencesViewModel::class.java].apply { sidePanel = PanelMode.Half } }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(900.dp, 800.dp))) {
                AndSliceTheme { AndSliceApp(slicer, printer, prefs) }
            }
        }
        waitForText("The plate is empty", timeoutMillis = 30_000)
        return slicer
    }

    @Test
    fun halfPanelShowsSettingsBesideThePlate() {
        startApp()
        // The navigation rail, with Preview and the app settings at its bottom.
        compose.onNodeWithText("Preview").assertExists()
        compose.onNodeWithContentDescription("App settings").assertExists()
        // The side panel: the plate's profiles and quick settings, then every slicing setting on its own tab.
        waitForText("Quick settings")
        compose.onNodeWithText("Add a model to slice").assertExists()
        compose.onNodeWithTag("panel-settings").performClick()
        compose.onNodeWithTag("settings-search").assertExists()
        compose.onNodeWithText("Infill").performClick()
        waitForText("Fill density")
    }

    @Test
    fun sidePanelResizesByDraggingItsEdge() {
        startApp()
        val prefs = compose.runOnUiThread { ViewModelProvider(compose.activity)[PreferencesViewModel::class.java] }
        compose.runOnUiThread {
            prefs.resizableSidePanel = true
            prefs.sidePanelWidth = 0f
        }
        compose.onNodeWithContentDescription("Side panel width. Drag to resize, double tap to reset.")
            .performTouchInput { swipe(center, center - Offset(150f, 0f), durationMillis = 400) }
        // Dragged to the left, the panel gets wider than half of the window beside the rail.
        compose.waitUntil(5_000) { prefs.sidePanelWidth > 0f }
        compose.onNodeWithContentDescription("Side panel width. Drag to resize, double tap to reset.")
            .performTouchInput { doubleClick() }
        compose.waitUntil(5_000) { prefs.sidePanelWidth == 0f }
    }

    @Test
    fun movesByStepsAndPaints() {
        val slicer = startApp()
        val stl = File(compose.activity.cacheDir, "cube.stl").apply {
            writeText(
                "solid c\n" + listOf(
                    "0 0 0|10 10 0|10 0 0", "0 0 0|0 10 0|10 10 0", "0 0 10|10 0 10|10 10 10", "0 0 10|10 10 10|0 10 10",
                    "0 0 0|10 0 0|10 0 10", "0 0 0|10 0 10|0 0 10", "10 0 0|10 10 0|10 10 10", "10 0 0|10 10 10|10 0 10",
                    "10 10 0|0 10 0|0 10 10", "10 10 0|0 10 10|10 10 10", "0 10 0|0 0 0|0 0 10", "0 10 0|0 0 10|0 10 10",
                ).joinToString("") { f ->
                    "facet normal 0 0 0\nouter loop\n" + f.split('|').joinToString("") { "vertex $it\n" } + "endloop\nendfacet\n"
                } + "endsolid c\n"
            )
        }
        compose.runOnUiThread { slicer.importModels(compose.activity, listOf(Uri.fromFile(stl))) }
        // Imported objects are selected, which shows their toolbar.
        compose.waitUntil(30_000) { slicer.selectedObject != null }
        val x = slicer.selectedObject!!.offset[0]

        // One step of the default 1 mm to the right.
        compose.onNodeWithContentDescription("Move, rotate, scale").performClick()
        compose.onNodeWithContentDescription("X +").performClick()
        compose.waitUntil(10_000) { abs((slicer.selectedObject?.offset?.get(0) ?: x) - (x + 1.0)) < 0.01 }
        assertEquals(x + 1.0, slicer.selectedObject!!.offset[0], 0.01)
        compose.onNodeWithContentDescription("Close").performClick()

        // The brush paints supports, seams or fuzzy skin; the Kobra 3 has one filament.
        compose.onNodeWithContentDescription("Paint").performClick()
        compose.onNodeWithTag("paint-supports").performClick()
        waitForText("Paint supports")
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(10_000) { slicer.paintMode == null }
    }
}
