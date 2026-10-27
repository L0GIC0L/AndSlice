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

        // Settings list PrusaSlicer's own option definitions.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settings-search").performTextInput("fill_density")
        waitForText("Fill density")
        compose.onNodeWithContentDescription("Back").performClick()

        // The printer screen asks for an OctoPrint connection.
        waitForText("The plate is empty")
        compose.onNodeWithContentDescription("Printer").performClick()
        waitForText("Connect to OctoPrint")
    }
}
