package io.github.l0gic0l.andslice

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Starts the app with its OpenGL ES view and walks through the main screens. */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun waitForText(text: String, timeoutMillis: Long = 10_000) =
        compose.waitUntil(timeoutMillis) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun walksThroughMainScreens() {
        waitForText("The plate is empty", timeoutMillis = 30_000)
        compose.onNodeWithText("Import model").assertExists()

        // Preview has its own place in the navigation bar; with nothing to slice it says so.
        compose.onNodeWithText("Preview").performClick()
        waitForText("Add a model to the plate first.")

        // Settings show PrusaSlicer's options on its own pages, and search covers all of them.
        compose.onNodeWithText("Settings").performClick()
        waitForText("Layer height")
        compose.onNodeWithText("Infill").performClick()
        waitForText("Fill density")
        compose.onNodeWithTag("settings-search").performTextInput("fill_density")
        waitForText("Fill density")
        compose.onNodeWithText("Plate").performClick()

        // The printer screen asks for an OctoPrint or Moonraker connection.
        waitForText("The plate is empty")
        compose.onNodeWithText("Printer").performClick()
        waitForText("Connect your printer")
        compose.onNodeWithText("Plate").performClick()

        // App settings are in the overflow menu on phones.
        waitForText("The plate is empty")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("App settings").performClick()
        waitForText("Theme")
    }
}
