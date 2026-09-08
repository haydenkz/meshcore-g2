package io.github.haydenkz.meshcorehelper

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.haydenkz.meshcorehelper.ui.HelperScreen
import io.github.haydenkz.meshcorehelper.ui.MeshCoreTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class InboxScreenTest {
    @get:Rule val compose = createComposeRule()
    private val radio = "a".repeat(64)
    private val publicChannel = Conversation("$radio:0", "channel", "Public", "Alice: Meet at the trailhead", 1788891900000)
    private val alice = Conversation("$radio:112233445566", "direct", "Alice", "See you there", 1788891960000)
    private val helper = mutableStateOf(HelperUiState(radio = HelperSnapshot("connected", "", "Trail companion", 8, 3840), running = true,
        radioId = radio, channelMessageLimit = 140, directMessageLimit = 159))
    private val inbox = mutableStateOf(InboxUiState(channels = listOf(publicChannel, publicChannel.copy(id = "$radio:1", name = "Trail crew", preview = "Bob: Clear skies at the summit"),
        publicChannel.copy(id = "$radio:2", name = "Local", preview = "Anyone out riding today?")), chats = listOf(alice, alice.copy(id = "$radio:aabbccddeeff", name = "Bob", preview = "I have the spare battery")), adverts = listOf(
        RecentAdvert(1, "Hill repeater", "112233445566", "Repeater", 1788891960000),
    ), loading = false))
    private val sends = mutableListOf<Pair<Conversation, String>>()
    private fun screen(sendError: String? = null) {
        compose.setContent {
            MeshCoreTheme {
                HelperScreen(helper.value, {}, {}, {}, {}, {}, {}, inbox = inbox.value,
                    onOpenConversation = { inbox.value = inbox.value.copy(conversation = it, messages = listOf(
                        ChatMessage(2, "You", "On my way", 1788891960000, true, "delivered"),
                        ChatMessage(1, "Alice", "Meet at the trailhead", 1788891900000, false, "received"),
                    )) },
                    onCloseConversation = { inbox.value = inbox.value.copy(conversation = null) },
                    onSendMessage = { conversation, text -> sends.add(conversation to text); sendError },
                )
            }
        }
    }
    @Test fun opensTheRightConversationAndKeepsDraftsAcrossNavigation() {
        screen()
        compose.onNode(hasText("Channels") and hasClickAction()).performClick()
        compose.onNodeWithText("Public").assertIsDisplayed()
        screenshot("channels")
        compose.onNodeWithText("Public").performClick()
        compose.onNodeWithText("Meet at the trailhead").assertIsDisplayed()
        compose.onAllNodes(hasText("Delivered", substring = true)).assertCountEquals(1)
        screenshot("conversation")
        compose.onNodeWithTag("message-composer").performTextInput("Draft for Public")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("DMs") and hasClickAction()).performClick()
        compose.onNodeWithText("Alice").performClick()
        compose.onNodeWithTag("message-composer").assert(hasText("Draft for Public").not())
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNode(hasText("Channels") and hasClickAction()).performClick()
        compose.onNodeWithText("Public").performClick()
        compose.onNodeWithTag("message-composer").assertTextContains("Draft for Public")
        compose.onNodeWithTag("message-composer").performTextReplacement("Ready")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf(publicChannel to "Ready"), sends)
        compose.onNodeWithTag("message-composer").assert(hasText("Ready").not())
    }
    @Test fun sendsToTheSelectedChatAndRetainsTheDraftIfTheRadioRejectsIt() {
        screen(sendError = "Radio is busy")
        compose.onNode(hasText("DMs") and hasClickAction()).performClick()
        compose.onNodeWithText("Alice").performClick()
        compose.onNodeWithTag("message-composer").performTextInput("Hello Alice")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf(alice to "Hello Alice"), sends)
        compose.onNodeWithTag("message-composer").assertTextContains("Hello Alice")
        compose.onNodeWithText("Radio is busy").assertIsDisplayed()
        compose.runOnIdle { helper.value = helper.value.copy(radioId = "b".repeat(64)) }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }
    @Test fun logsShowsTheSameNamedAdvertsAsTheSharedHistory() {
        screen()
        compose.onNode(hasText("Logs") and hasClickAction()).performClick()
        compose.onNodeWithText("Recent adverts").performClick()
        compose.onNodeWithText("Hill repeater").assertIsDisplayed()
        compose.onNodeWithText("112233445566").assertIsDisplayed()
        screenshot("adverts")
        compose.onNodeWithText("Packets").performClick()
        compose.onNodeWithText("Hill repeater").assertDoesNotExist()
        compose.onNodeWithText("Waiting for radio packets…").assertIsDisplayed()
    }
    @Test fun sendingFromOlderHistorySnapsToTheNewMessageAfterHistoryRefreshes() {
        screen()
        compose.onNode(hasText("Channels") and hasClickAction()).performClick()
        compose.onNodeWithText("Public").performClick()
        compose.runOnIdle {
            inbox.value = inbox.value.copy(messages = (60L downTo 1L).map {
                ChatMessage(it, "Alice", "Older message $it", 1788891900000 + it * 1000, false, "received")
            })
        }
        compose.onNodeWithTag("message-history").performScrollToNode(hasText("Older message 10"))
        compose.onNodeWithText("Older message 10").assertIsDisplayed()
        compose.onNodeWithTag("message-composer").performTextInput("New message")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf(publicChannel to "New message"), sends)
        // Simulate the database refresh arriving after the send callback returns.
        compose.runOnIdle {
            inbox.value = inbox.value.copy(messages = listOf(
                ChatMessage(61, "You", "New message", 1788892000000, true, "queued"),
            ) + inbox.value.messages)
        }
        compose.onNodeWithText("New message").assertIsDisplayed()
        compose.onNodeWithText("Older message 10").assertIsNotDisplayed()
    }
    @Test fun swipesTabsAndChatsWithoutLosingDraftsOrChangingOrderOnArrival() {
        screen()
        compose.onNodeWithTag("tab-content").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Public").performClick()
        compose.onNodeWithTag("message-composer").performTextInput("Keep this draft")
        compose.onNodeWithTag("message-history").performTouchInput { swipeLeft() }
        assertEquals("Trail crew", inbox.value.conversation?.name)
        compose.onNodeWithTag("message-composer").assert(hasText("Keep this draft").not())
        compose.runOnIdle { inbox.value = inbox.value.copy(channels = inbox.value.channels.reversed()) }
        compose.onNodeWithTag("message-history").performTouchInput { swipeRight() }
        assertEquals("Public", inbox.value.conversation?.name)
        compose.onNodeWithTag("message-composer").assertTextContains("Keep this draft")
        compose.onNodeWithContentDescription("Previous chat").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("tab-content").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Alice").assertIsDisplayed()
        compose.onNodeWithTag("tab-content").performTouchInput { swipeRight() }
        compose.onNodeWithText("Public").assertIsDisplayed()
    }
    @Test fun packetDetailsFiltersAndPauseKeepInspectionStableAsPacketsArrive() {
        val packet = RadioLog.parse(1, 1788891960000, byteArrayOf(0x88.toByte(), 10, -100, 0x15, 2, 0xAB.toByte(), 0xCD.toByte(), 1, 2, 3))!!
        compose.runOnIdle { helper.value = helper.value.copy(logs = listOf(packet)) }
        screen()
        compose.onNode(hasText("Logs") and hasClickAction()).performClick()
        compose.onNodeWithTag("packet-1").performClick()
        compose.onNodeWithText("Path: AB → CD").assertIsDisplayed()
        screenshot("logs")
        compose.onNodeWithText("Pause").performClick()
        compose.runOnIdle { helper.value = helper.value.copy(logs = listOf(RadioLog.parse(2, 1788891960010, byteArrayOf(0x88.toByte(), 8, -80, 0x11, 0, 1))) + helper.value.logs) }
        compose.onNodeWithTag("packet-2").assertDoesNotExist()
        compose.onNodeWithText("Resume").performClick()
        compose.onNodeWithTag("packet-2").assertIsDisplayed()
        compose.onNodeWithText("Messages").performClick()
        compose.onNodeWithTag("packet-2").assertDoesNotExist()
        compose.onNodeWithTag("packet-1").assertIsDisplayed()
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        // Platform ripple rendering uses wall time, independently of Compose's test clock.
        android.os.SystemClock.sleep(650)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir("screenshots"), "inbox-$name.png")
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
