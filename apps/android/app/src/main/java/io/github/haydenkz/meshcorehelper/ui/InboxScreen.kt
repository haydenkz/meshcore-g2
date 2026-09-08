package io.github.haydenkz.meshcorehelper.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
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
import io.github.haydenkz.meshcorehelper.R
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${all.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp)) },
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text(if (kind == "channel") "Search channels" else "Search chats") }, shape = RoundedCornerShape(24.dp),
            colors = chatFieldColors())
        inbox.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (inbox.loading && all.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(state = rememberLazyListState(), contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.testTag("conversation-list")) {
            if (conversations.isEmpty()) item {
                Text(if (search.isNotBlank()) "No matches." else if (kind == "channel") "Connect a radio to load its channels." else "No chats yet. Connect a radio to load its contacts.",
                    Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(conversations, key = { it.id }) { conversation ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(conversation) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                Text(if (kind == "channel") "#" else conversation.name.take(1).uppercase(Locale.ROOT), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(conversation.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                if (conversation.updatedAt > 0) Text(conversationTime(conversation.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(conversation.preview.ifBlank { "Start a conversation" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .18f))
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
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var scrollAfterSend by rememberSaveable { mutableStateOf<Long?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val awayFromLatest by remember { derivedStateOf { list.firstVisibleItemIndex > 1 } }
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
        Box(Modifier.weight(1f).fillMaxWidth().swipeNavigation(onPrevious, onNext)) {
            if (inbox.loading && inbox.messages.isEmpty()) {
                Text("Loading messages…", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize().testTag("message-history"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (inbox.messages.isEmpty()) item {
                    Text(if (inbox.loading) "Loading messages…" else "No messages yet.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(inbox.messages, key = { _, message -> message.id }) { index, message ->
                    val older = inbox.messages.getOrNull(index + 1)
                    val newDay = older == null || messageDay(older.sentAt) != messageDay(message.sentAt)
                    val showSender = newDay || older?.outgoing != message.outgoing || older.sender != message.sender
                    Column {
                        if (newDay) Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(dayLabel(message.sentAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.fillMaxWidth().padding(top = if (showSender && !newDay) 8.dp else 0.dp), horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
                            Surface(shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = if (message.outgoing) 4.dp else 16.dp, bottomStart = if (message.outgoing) 16.dp else 4.dp), color = if (message.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                                Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    if (showSender && !message.outgoing && conversation.kind == "channel") Text(message.sender.ifBlank { "Unknown sender" }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                    SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp)) }
                                    Text(chatTime(message.sentAt) + if (message.outgoing) " · ${deliveryLabel(message.delivery)}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (message.outgoing && message.delivery in listOf("failed", "unconfirmed")) TextButton(onClick = { draft = message.text; error = null }) { Text("Edit and retry") }
                                }
                            }
                        }
                    }
                }
                if (inbox.hasOlder) item { TextButton(onClick = onOlder, modifier = Modifier.fillMaxWidth()) { Text("Older messages") } }
            }
            if (awayFromLatest) FilledTonalButton(onClick = { scope.launch { list.animateScrollToItem(0) } }, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)) { Text("Latest messages") }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val detail = error ?: if (!canSend) "Connect this conversation's radio to send." else if (bytes > limit) "Message is too long: $bytes / $limit bytes." else null
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (error != null || canSend && bytes > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(draft, { draft = it; error = null }, Modifier.weight(1f).testTag("message-composer"), placeholder = { Text("Message") }, textStyle = MaterialTheme.typography.bodyMedium, maxLines = 4, shape = RoundedCornerShape(24.dp), colors = chatFieldColors())
                FilledIconButton(onClick = {
                    val previousNewest = inbox.messages.firstOrNull()?.id ?: 0
                    error = onSend(conversation, draft)
                    if (error == null) {
                        draft = ""
                        scrollAfterSend = previousNewest
                    }
                }, enabled = canSend && bytes in 1..limit && draft.isNotBlank(), modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_send), "Send", Modifier.size(22.dp)) }
            }
            if (draft.isNotEmpty()) Text(if (limit > 0) "$bytes / $limit bytes" else "$bytes bytes", Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall,
                color = if (bytes > limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
    OutlinedCard(shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(advert.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text("${advert.nodeType} · ${chatTime(advert.receivedAt, includeDate = true)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(advert.publicKeyPrefix, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun messageDay(timestamp: Long) = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
private fun dayLabel(timestamp: Long): String {
    val day = messageDay(timestamp)
    val today = java.time.LocalDate.now()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEE, d MMM" else "d MMM yyyy", Locale.ENGLISH))
    }
}
private fun conversationTime(timestamp: Long): String = if (messageDay(timestamp) == java.time.LocalDate.now()) chatTime(timestamp)
    else messageDay(timestamp).format(DateTimeFormatter.ofPattern(if (messageDay(timestamp).year == java.time.LocalDate.now().year) "d MMM" else "d MMM yy", Locale.ENGLISH))

@Composable
private fun chatFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    unfocusedBorderColor = Color.Transparent,
    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .5f),
)
