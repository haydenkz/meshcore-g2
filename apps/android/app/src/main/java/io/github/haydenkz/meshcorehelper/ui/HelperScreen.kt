package io.github.haydenkz.meshcorehelper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import io.github.haydenkz.meshcorehelper.RadioLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import io.github.haydenkz.meshcorehelper.HelperSnapshot
import io.github.haydenkz.meshcorehelper.HelperUiState
import io.github.haydenkz.meshcorehelper.NearbyRadio
import io.github.haydenkz.meshcorehelper.InboxUiState
import io.github.haydenkz.meshcorehelper.Conversation

private val ForestColors = darkColorScheme(
    primary = Color(0xFFB0DEB5), onPrimary = Color(0xFF12351E),
    primaryContainer = Color(0xFF294D34), onPrimaryContainer = Color(0xFFC7ECCC),
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
    onOpenConversation: (Conversation) -> Unit = {},
    onCloseConversation: () -> Unit = {},
    onOlderMessages: () -> Unit = {},
    onSendMessage: (Conversation, String) -> String? = { _, _ -> "Connect a radio to send messages." },
) {
    val connected = state.radio.state() == "connected"
    val connecting = state.radio.state() in listOf("pairing", "connecting", "discovering", "subscribing", "initializing")
    var destination by rememberSaveable { mutableStateOf("Home") }
    val homeList = rememberLazyListState()
    val logsList = rememberLazyListState()
    val chatStates = rememberSaveableStateHolder()
    var showAdverts by rememberSaveable { mutableStateOf(false) }
    val conversation = inbox.conversation?.takeIf { destination == if (it.kind == "channel") "Channels" else "DMs" }
    BackHandler(enabled = conversation != null || destination != "Home") {
        if (conversation != null) onCloseConversation() else destination = "Home"
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().testTag("helper-header").statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (conversation != null) {
                    TextButton(onClick = onCloseConversation) { Text("Back") }
                    Text(conversation.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                } else {
                    Image(painterResource(R.drawable.meshcore_g2), contentDescription = "MeshCore G2 logo", modifier = Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)))
                    Text("MeshCore G2", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        bottomBar = {
            if (conversation == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf("Home" to R.drawable.ic_home, "Channels" to R.drawable.ic_channels, "DMs" to R.drawable.ic_messages, "Logs" to R.drawable.ic_logs).forEach { (name, icon) ->
                    NavigationBarItem(
                        selected = destination == name,
                        onClick = { onCloseConversation(); destination = name },
                        icon = { Icon(painterResource(icon), contentDescription = null) },
                        label = { Text(name) },
                    )
                }
            }
        },
    ) { insets ->
        val content = Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
        if (conversation != null) {
            chatStates.SaveableStateProvider("${conversation.kind}:${conversation.id}") {
                ConversationScreen(conversation, inbox, state, onOlderMessages, onSendMessage, content.imePadding())
            }
            return@Scaffold
        }
        if (destination == "Channels" || destination == "DMs") {
            ConversationsScreen(if (destination == "Channels") "channel" else "direct", inbox, onOpenConversation, content.imePadding())
            return@Scaffold
        }
        if (destination == "Logs") {
            LazyColumn(
                state = logsList,
                modifier = Modifier.fillMaxSize().padding(insets),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Radio logs", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showAdverts, onClick = { showAdverts = false }, label = { Text("Packets") })
                            FilterChip(selected = showAdverts, onClick = { showAdverts = true }, label = { Text("Recent adverts") })
                        }
                        Text(if (showAdverts) "All saved adverts, newest first." else "Live packets received by your radio.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (showAdverts) {
                    inbox.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                    if (inbox.adverts.isEmpty()) item { Text("No adverts received yet.", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(inbox.adverts, key = { "advert:${it.id}" }) { AdvertRow(it) }
                } else {
                    if (state.logs.isEmpty()) item {
                        Text(if (connected) "Waiting for radio packets…" else "Connect a radio to see its logs.", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(state.logs, key = { it.id() }) { RadioLogRow(it) }
                }
            }
            return@Scaffold
        }
        LazyColumn(
            state = homeList,
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
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
                Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("YOUR RADIO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Surface(color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
                                Text(if (connected) "Connected" else if (connecting) "Connecting" else if (state.radio.state() == "error") "Needs attention" else "Disconnected", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(state.radio.name().ifBlank { "Find your companion" }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
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
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
}

@Preview(widthDp = 412, heightDp = 915, showBackground = true)
@Composable
private fun ConnectedPreview() {
    MeshCoreTheme {
        HelperScreen(HelperUiState(radio = HelperSnapshot("connected", "BLE companion connected.", "Trail companion", 8, 3840), running = true, hudLinked = true), {}, {}, {}, {}, {}, {})
    }
}

private val LogTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

@Composable
private fun RadioLogRow(log: RadioLog) {
    OutlinedCard(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RX · ${log.type()}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(LogTime.format(Instant.ofEpochMilli(log.receivedAt()).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${log.route()} · ${log.bytes()} bytes", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("RSSI ${log.rssi()} dBm · SNR ${log.snr()} dB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
