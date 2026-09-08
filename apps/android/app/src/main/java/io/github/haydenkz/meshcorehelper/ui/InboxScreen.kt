package io.github.haydenkz.meshcorehelper.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.haydenkz.meshcorehelper.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ChatClock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val ChatDate = DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale.ENGLISH)
internal fun chatTime(timestamp: Long, includeDate: Boolean = false): String =
    (if (includeDate) ChatDate else ChatClock).format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

@Composable
internal fun ConversationsScreen(kind: String, inbox: InboxUiState, onOpen: (Conversation) -> Unit, modifier: Modifier = Modifier) {
    var search by rememberSaveable(kind) { mutableStateOf("") }
    val title = if (kind == "channel") "Channels" else "Direct messages"
    val all = if (kind == "channel") inbox.channels else inbox.chats
    val conversations = all.filter { it.name.contains(search, ignoreCase = true) }
    Column(modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(if (kind == "channel") "Search channels" else "Search chats") }, shape = RoundedCornerShape(18.dp))
        inbox.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (inbox.loading && all.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (conversations.isEmpty()) item {
                Text(if (search.isNotBlank()) "No matches." else if (kind == "channel") "Connect a radio to load its channels." else "No chats yet. Connect a radio to load its contacts.",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(conversations, key = { it.id }) { conversation ->
                OutlinedCard(onClick = { onOpen(conversation) }, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Text(if (kind == "channel") "#" else conversation.name.take(1).uppercase(Locale.ROOT), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(conversation.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                if (conversation.updatedAt > 0) Text(chatTime(conversation.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(conversation.preview.ifBlank { "Start a conversation" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConversationScreen(
    conversation: Conversation,
    inbox: InboxUiState,
    helper: HelperUiState,
    onOlder: () -> Unit,
    onSend: (Conversation, String) -> String?,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var scrollAfterSend by rememberSaveable { mutableStateOf<Long?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(inbox.messages, scrollAfterSend) {
        val previousNewest = scrollAfterSend ?: return@LaunchedEffect
        if (inbox.messages.any { it.outgoing && it.id > previousNewest }) {
            // The shared history refresh is asynchronous. Wait for the new
            // outgoing row before snapping to the bottom of this reversed list.
            list.scrollToItem(0)
            scrollAfterSend = null
        }
    }
    val canSend = helper.radio.state() == "connected" && helper.radioId == conversation.id.substringBefore(':')
    val limit = if (conversation.kind == "channel") helper.channelMessageLimit else helper.directMessageLimit
    val bytes = draft.trim().toByteArray(Charsets.UTF_8).size
    Column(modifier) {
        inbox.error?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(state = list, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth().testTag("message-history"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (inbox.messages.isEmpty()) item {
                Text(if (inbox.loading) "Loading messages…" else "No messages yet.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(inbox.messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
                    Surface(shape = RoundedCornerShape(20.dp), color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.widthIn(max = 320.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!message.outgoing && conversation.kind == "channel") Text(message.sender.ifBlank { "Unknown sender" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                            Text(chatTime(message.sentAt, includeDate = true) + if (message.outgoing) " · ${deliveryLabel(message.delivery)}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (message.outgoing && message.delivery in listOf("failed", "unconfirmed")) TextButton(onClick = { draft = message.text; error = null }) { Text("Edit and retry") }
                        }
                    }
                }
            }
            if (inbox.hasOlder) item { TextButton(onClick = onOlder, modifier = Modifier.fillMaxWidth()) { Text("Older messages") } }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val detail = error ?: if (!canSend) "Connect this conversation's radio to send." else if (bytes > limit) "Message is too long: $bytes / $limit bytes." else null
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (error != null || canSend && bytes > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(draft, { draft = it; error = null }, Modifier.weight(1f).testTag("message-composer"), placeholder = { Text("Message") }, maxLines = 4, shape = RoundedCornerShape(22.dp))
                Button(onClick = {
                    val previousNewest = inbox.messages.firstOrNull()?.id ?: 0
                    error = onSend(conversation, draft)
                    if (error == null) {
                        draft = ""
                        scrollAfterSend = previousNewest
                    }
                }, enabled = canSend && bytes in 1..limit && draft.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text("Send") }
            }
        }
    }
}

private fun deliveryLabel(state: String) = when (state) {
    "queued" -> "Queued"
    "sending" -> "Sending"
    "awaiting_ack", "sent" -> "Sent"
    "delivered" -> "Delivered"
    "failed" -> "Not sent"
    "unconfirmed" -> "No confirmation"
    else -> state
}

@Composable
internal fun AdvertRow(advert: RecentAdvert) {
    OutlinedCard(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(advert.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text("${advert.nodeType} · ${chatTime(advert.receivedAt, includeDate = true)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(advert.publicKeyPrefix, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
