package io.github.l0gic0l.andslice

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Starts the app with its OpenGL ES view and checks the main and settings screens come up. */
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun showsEmptyPlateAndSettings() {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("The plate is empty").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Slice").assertExists()

        // The settings screen lists PrusaSlicer's own option definitions.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Search settings").performTextInput("fill_density")
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Fill density").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
