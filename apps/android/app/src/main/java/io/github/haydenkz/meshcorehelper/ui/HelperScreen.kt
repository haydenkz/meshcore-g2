package io.github.haydenkz.meshcorehelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.haydenkz.meshcorehelper.R
import io.github.haydenkz.meshcorehelper.HelperSnapshot
import io.github.haydenkz.meshcorehelper.HelperUiState
import io.github.haydenkz.meshcorehelper.NearbyRadio
import io.github.haydenkz.meshcorehelper.InboxUiState
import io.github.haydenkz.meshcorehelper.Conversation

private val ForestColors = darkColorScheme(
    primary = Color(0xFFB0DEB5), onPrimary = Color(0xFF12351E),
    primaryContainer = Color(0xFF294D34), onPrimaryContainer = Color(0xFFC7ECCC),
    secondaryContainer = Color(0xFF2D3F32), onSecondaryContainer = Color(0xFFD3E6D5),
    secondary = Color(0xFFB8CCBA), background = Color(0xFF101813),
    surface = Color(0xFF101813), surfaceContainer = Color(0xFF1B261F),
    surfaceContainerHigh = Color(0xFF263129), onSurface = Color(0xFFE1EBDF),
    onSurfaceVariant = Color(0xFFB9C6B9), outline = Color(0xFF526154),
)

@Composable
fun MeshCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ForestColors, content = content)
}

@Composable
private fun RadioMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color, radius = 2.5.dp.toPx(), center = center)
        for (fraction in listOf(0.45f, 0.85f)) {
            val diameter = size.minDimension * fraction
            val start = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            drawArc(color, -55f, 110f, false, start, Size(diameter, diameter), style = stroke)
            drawArc(color, 125f, 110f, false, start, Size(diameter, diameter), style = stroke)
        }
    }
}

@Composable
fun HelperScreen(
    state: HelperUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (NearbyRadio) -> Unit,
    onDisconnect: () -> Unit,
    onCopyKey: () -> Unit,
    onDismissNotice: () -> Unit,
    inbox: InboxUiState = InboxUiState(),
    notificationsEnabled: Boolean = false,
    onNotifications: () -> Unit = {},
    onOpenConversation: (Conversation) -> Unit = {},
    onCloseConversation: () -> Unit = {},
    onOlderMessages: () -> Unit = {},
    onSendMessage: (Conversation, String) -> String? = { _, _ -> "Connect a radio to send messages." },
) {
    var destination by rememberSaveable { mutableStateOf("Home") }
    val homeList = rememberLazyListState()
    val chatStates = rememberSaveableStateHolder()
    val tabs = listOf("Home", "Channels", "DMs", "Logs")
    val focus = LocalFocusManager.current
    // Keep the browsing order stable while new messages reorder the inbox.
    var chatOrder by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val openChat: (Conversation) -> Unit = { selected ->
        chatOrder = (if (selected.kind == "channel") inbox.channels else inbox.chats).map { it.id }
        focus.clearFocus()
        onOpenConversation(selected)
    }
    val closeChat: () -> Unit = { focus.clearFocus(); onCloseConversation() }
    val selectTab: (String) -> Unit = { focus.clearFocus(); onCloseConversation(); destination = it }
    val conversation = inbox.conversation
    LaunchedEffect(conversation?.kind, conversation?.id) {
        if (conversation != null) {
            destination = if (conversation.kind == "channel") "Channels" else "DMs"
            if (conversation.id !in chatOrder) chatOrder = (if (conversation.kind == "channel") inbox.channels else inbox.chats).map { it.id }
        }
    }
    val peers = if (conversation?.kind == "channel") inbox.channels else inbox.chats
    val orderedPeers = chatOrder.mapNotNull { id -> peers.find { it.id == id } }
    val chatIndex = orderedPeers.indexOfFirst { it.id == conversation?.id }
    val previousChat = orderedPeers.getOrNull(chatIndex - 1)?.takeIf { chatIndex >= 0 }
    val nextChat = orderedPeers.getOrNull(chatIndex + 1)?.takeIf { chatIndex >= 0 }
    val previousAction: (() -> Unit)? = previousChat?.let { { focus.clearFocus(); onOpenConversation(it) } }
    val nextAction: (() -> Unit)? = nextChat?.let { { focus.clearFocus(); onOpenConversation(it) } }
    BackHandler(enabled = conversation != null || destination != "Home") {
        if (conversation != null) closeChat() else selectTab("Home")
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().testTag("helper-header").statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (conversation != null) {
                    IconButton(onClick = closeChat) { Icon(painterResource(R.drawable.ic_back), "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(conversation.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (chatIndex >= 0) "${chatIndex + 1} of ${orderedPeers.size} · ${if (conversation.kind == "channel") "Channels" else "Direct messages"}" else if (conversation.kind == "channel") "Channel" else "Direct message",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (orderedPeers.size > 1) {
                        IconButton(onClick = { previousAction?.invoke() }, enabled = previousAction != null) { Icon(painterResource(R.drawable.ic_previous), "Previous chat") }
                        IconButton(onClick = { nextAction?.invoke() }, enabled = nextAction != null) { Icon(painterResource(R.drawable.ic_next), "Next chat") }
                    }
                } else {
                    Image(painterResource(R.drawable.meshcore_g2), contentDescription = "MeshCore G2 logo", modifier = Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)))
                    Text("MeshCore G2", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        bottomBar = {
            if (conversation == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf("Home" to R.drawable.ic_home, "Channels" to R.drawable.ic_channels, "DMs" to R.drawable.ic_messages, "Logs" to R.drawable.ic_logs).forEach { (name, icon) ->
                    NavigationBarItem(
                        selected = destination == name,
                        onClick = { selectTab(name) },
                        icon = { Icon(painterResource(icon), contentDescription = null) },
                        label = { Text(name) },
                    )
                }
            }
        },
    ) { insets ->
        val content = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
        // Animate a snapshot: an outgoing chat must never render the next chat's messages.
        AnimatedContent(
            targetState = destination to inbox,
            contentKey = { (tab, snapshot) -> snapshot.conversation?.let { "chat:${it.kind}:${it.id}" } ?: tab },
            transitionSpec = { (fadeIn(tween(180, delayMillis = 70)) togetherWith fadeOut(tween(120))).using(null) },
            modifier = content,
            label = "screen fade",
        ) { (tab, snapshot) ->
            val activeChat = snapshot.conversation
            val screenKey = activeChat?.let { "chat:${it.kind}:${it.id}" } ?: tab
            chatStates.SaveableStateProvider(screenKey) {
                if (activeChat != null) {
                    ConversationScreen(activeChat, snapshot, state, onOlderMessages, onSendMessage,
                        Modifier.fillMaxSize().imePadding(),
                        onPrevious = previousAction, onNext = nextAction)
                } else {
                    val tabIndex = tabs.indexOf(tab)
                    val tabContent = Modifier.fillMaxSize().swipeNavigation(
                        tabs.getOrNull(tabIndex - 1)?.let { { selectTab(it) } },
                        tabs.getOrNull(tabIndex + 1)?.let { { selectTab(it) } },
                    ).testTag("tab-content")
                    when (tab) {
                        "Channels", "DMs" -> ConversationsScreen(if (tab == "Channels") "channel" else "direct", snapshot, openChat, tabContent.imePadding())
                        "Logs" -> LogsScreen(state, snapshot, tabContent)
                        else -> HomeScreen(state, onScan, onStopScan, onConnect, onDisconnect, onCopyKey, onDismissNotice, tabContent, homeList, notificationsEnabled, onNotifications)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: HelperUiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (NearbyRadio) -> Unit,
    onDisconnect: () -> Unit,
    onCopyKey: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier,
    homeList: androidx.compose.foundation.lazy.LazyListState,
    notificationsEnabled: Boolean,
    onNotifications: () -> Unit,
) {
    val connected = state.radio.state() == "connected"
    val connecting = state.radio.state() in listOf("pairing", "connecting", "discovering", "subscribing", "initializing")
    LazyColumn(
        state = homeList,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (state.notice != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.notice, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismissNotice) { Text("Dismiss") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("YOUR RADIO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
                            Text(if (connected) "Connected" else if (connecting) "Connecting" else if (state.radio.state() == "error") "Needs attention" else "Disconnected", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(state.radio.name().ifBlank { "Find your companion" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    if (!connected) Text(state.radio.detail(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (connected || connecting) {
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text(if (connecting) "Cancel connection" else "Disconnect radio") }
                    } else {
                        Button(onClick = if (state.scanning) onStopScan else onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (state.scanning) "Stop scanning" else "Find a radio")
                        }
                    }
                }
            }
        }
        if (!connected && !connecting) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Nearby radios", style = MaterialTheme.typography.titleMedium)
                    if (state.scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = onScan, enabled = !connecting) { Text("Scan again") }
                }
            }
            if (state.devices.isEmpty()) item {
                Text(if (state.scanning) "Looking for MeshCore companions nearby…" else "Power on your companion and disconnect it from other MeshCore apps before scanning.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.devices, key = { it.address }) { radio ->
                OutlinedCard(onClick = { onConnect(radio) }, enabled = !connecting, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RadioMark(Modifier.size(32.dp))
                        Column(Modifier.weight(1f)) {
                            Text(radio.name, style = MaterialTheme.typography.titleSmall)
                            Text(radio.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${radio.rssi} dBm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Message notifications", style = MaterialTheme.typography.titleSmall)
                    Text(if (notificationsEnabled) "Channel and direct message alerts are on" else "Enable alerts for new messages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onNotifications) { Text(if (notificationsEnabled) "Settings" else "Enable") }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Even glasses", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.hudLinked) "●  Plugin linked" else "○  Waiting for the Even plugin", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (!state.hudLinked) {
                        Text("Open MeshCore G2 in the Even App. Paste the connection key there once to remember this phone helper.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = onCopyKey, modifier = Modifier.fillMaxWidth()) { Text("Copy connection key") }
                    }
                }
            }
        }
    }
}

@Preview(widthDp = 412, heightDp = 915, showBackground = true)
@Composable
private fun ConnectedPreview() {
    MeshCoreTheme {
        HelperScreen(HelperUiState(radio = HelperSnapshot("connected", "BLE companion connected.", "Trail companion", 8, 3840), running = true, hudLinked = true), {}, {}, {}, {}, {}, {})
    }
}
