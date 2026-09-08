package io.github.haydenkz.meshcorehelper

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.haydenkz.meshcorehelper.ui.HelperScreen
import io.github.haydenkz.meshcorehelper.ui.MeshCoreTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Uses the real activity's edge-to-edge window and manifest, with synthetic chat data. */
class KeyboardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val channel = Conversation("${"a".repeat(64)}:0", "channel", "Public", "", 0)
    private val inbox = mutableStateOf(InboxUiState(channels = listOf(channel), loading = false))

    @Before fun screen() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MeshCoreTheme {
                    HelperScreen(HelperUiState(), {}, {}, {}, {}, {}, {}, inbox = inbox.value,
                        onOpenConversation = { inbox.value = inbox.value.copy(conversation = it) },
                        onCloseConversation = { inbox.value = inbox.value.copy(conversation = null) },
                    )
                }
            }
        }
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            compose.activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        compose.onNodeWithText("Channels").performClick()
    }

    @Test fun searchKeyboardKeepsTheBrandHeaderFixed() {
        assertHeaderStaysFixed(compose.onNode(hasSetTextAction()))
    }

    @Test fun composerKeyboardKeepsTheChatHeaderFixed() {
        compose.onNodeWithText("Public").performClick()
        assertHeaderStaysFixed(compose.onNodeWithTag("message-composer"))
    }

    private fun assertHeaderStaysFixed(field: SemanticsNodeInteraction) {
        val before = headerPosition()
        field.performClick()
        compose.waitUntil(5000) { keyboardVisible() }
        field.assertIsDisplayed()
        assertPosition(before, headerPosition())
        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5000) { !keyboardVisible() }
        assertPosition(before, headerPosition())
    }

    private fun keyboardVisible(): Boolean {
        var visible = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return visible
    }

    private fun headerPosition(): FloatArray {
        val bounds = compose.onNodeWithTag("helper-header").fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.decorView.getLocationOnScreen(origin)
        }
        return floatArrayOf(origin[0] + bounds.left, origin[1] + bounds.top, bounds.width, bounds.height)
    }

    private fun assertPosition(expected: FloatArray, actual: FloatArray) {
        expected.indices.forEach { assertEquals("Header coordinate $it changed with the keyboard", expected[it], actual[it], 1f) }
    }
}
