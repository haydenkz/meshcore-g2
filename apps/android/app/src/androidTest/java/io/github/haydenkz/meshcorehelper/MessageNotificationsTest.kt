package io.github.haydenkz.meshcorehelper

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Uses synthetic messages and real Android notifications; never sends to the radio. */
class MessageNotificationsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val radio = UUID.randomUUID().toString().replace("-", "").repeat(2)
    private val alice = ReceivedMessage("direct", "112233445566", "", "Notification test for Alice", System.currentTimeMillis())
    private val channel = ReceivedMessage("channel", "0", "Bob", "Notification test for Public", System.currentTimeMillis())
    private lateinit var store: MessageStore

    @Before fun prepare() {
        store = MessageStore(context)
        store.name(radio, "direct", alice.peer(), "Notification Alice")
        store.name(radio, "channel", "0", "Notification Public")
        store.add(radio, alice)
        store.add(radio, channel)
        MessageNotifications.createChannels(context)
        assertTrue("Enable notifications for the development helper before running device notification tests", manager.areNotificationsEnabled())
    }
    @After fun clean() {
        MessageNotifications.visible(context, null, null)
        manager.activeNotifications.filter { it.tag?.contains(radio) == true }.forEach { manager.cancel(it.tag, it.id) }
        for (table in listOf("messages", "names")) store.writableDatabase.delete(table, "radio=?", arrayOf(radio))
        store.close()
    }
    private fun alerts() = manager.activeNotifications.filter { it.tag?.contains(radio) == true }
    private fun post(message: ReceivedMessage, name: String) = MessageNotifications.received(context, radio, message, name)

    @Test fun notificationsKeepChatsSeparateAndTapOpensTheCorrectSavedHistory() {
        post(alice, "Notification Alice")
        post(channel, "Notification Public")
        compose.waitUntil(5000) { alerts().size == 2 }
        val direct = alerts().first { it.notification.channelId == MessageNotifications.DIRECT }
        val public = alerts().first { it.notification.channelId == MessageNotifications.CHANNELS }
        assertNotEquals(direct.notification.contentIntent, public.notification.contentIntent)
        assertEquals(Notification.VISIBILITY_PRIVATE, direct.notification.visibility)
        assertEquals("Notification Alice", NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(direct.notification)!!.messages.last().person!!.name)
        ActivityScenario.launch<MainActivity>(MessageNotifications.openIntent(context, "direct", "$radio:${alice.peer()}")).use { scenario ->
            compose.waitUntil(5000) { compose.onAllNodesWithText("Notification Alice").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("message-composer").assertIsDisplayed()
            compose.onNodeWithText(alice.text()).assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithText(alice.text()).assertIsDisplayed()
            compose.waitUntil(5000) { alerts().size == 1 }
            // A new message in the currently resumed chat must not interrupt reading.
            post(alice.copyText("Another Alice message"), "Notification Alice")
            assertEquals(1, alerts().size)
            public.notification.contentIntent.send()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Notification Public").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(channel.text()).assertIsDisplayed()
            compose.onNodeWithText(alice.text()).assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNode(hasText("Channels") and isSelected()).assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithTag("message-composer").assertDoesNotExist()
        }
    }
    @Test fun groupsRecentMessagesAndDoesNotResurrectDismissedHistory() {
        post(alice, "Notification Alice")
        compose.waitUntil(5000) { alerts().size == 1 }
        post(alice.copyText("Second message"), "Notification Alice")
        compose.waitUntil(5000) { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(alerts().single().notification)!!.messages.size == 2 }
        val current = alerts().single()
        manager.cancel(current.tag, current.id)
        compose.waitUntil(5000) { alerts().isEmpty() }
        post(alice.copyText("After dismissal"), "Notification Alice")
        compose.waitUntil(5000) { alerts().size == 1 }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(alerts().single().notification)!!
        assertEquals(listOf("After dismissal"), style.messages.map { it.text.toString() })
    }
    @Test fun rejectsMalformedNotificationRoutes() {
        assertFalse(MessageNotifications.validConversation("channel", "$radio:999"))
        assertFalse(MessageNotifications.validConversation("direct", "$radio:0"))
        assertFalse(MessageNotifications.validConversation("other", "$radio:0"))
        assertFalse(MessageNotifications.validConversation(null, null))
        assertTrue(MessageNotifications.validConversation("channel", "$radio:255"))
        assertTrue(MessageNotifications.validConversation("direct", "$radio:112233445566"))
    }
    private fun ReceivedMessage.copyText(text: String) = ReceivedMessage(kind(), peer(), sender(), text, sentAt() + 1000)
}
