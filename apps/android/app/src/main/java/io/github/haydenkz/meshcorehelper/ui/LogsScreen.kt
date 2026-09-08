package io.github.haydenkz.meshcorehelper.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.haydenkz.meshcorehelper.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val LogClock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)

@Composable
internal fun LogsScreen(helper: HelperUiState, inbox: InboxUiState, modifier: Modifier = Modifier) {
    var adverts by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("All") }
    // Pause only freezes this view; packet history continues saving new arrivals.
    var frozen by remember { mutableStateOf<List<PacketStore.SavedPacket>?>(null) }
    val packets = frozen ?: inbox.packets
    val visible = packets.filter { when (filter) {
        "Messages" -> it.log().type() in listOf("Direct message", "Channel message", "Channel data")
        "Adverts" -> it.log().type() == "Advertisement"
        "Other" -> it.log().type() !in listOf("Direct message", "Channel message", "Channel data", "Advertisement")
        else -> true
    } }
    val packetList = rememberLazyListState()
    val advertList = rememberLazyListState()
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Radio logs", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (!adverts) TextButton(onClick = { frozen = if (frozen == null) inbox.packets.toList() else null }) { Text(if (frozen == null) "Pause" else "Resume") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !adverts, onClick = { adverts = false }, label = { Text("Packets") })
            FilterChip(selected = adverts, onClick = { adverts = true }, label = { Text("Recent adverts") })
        }
        if (!adverts) {
            Text("${if (frozen != null) "Paused" else if (helper.radio.state() == "connected") "Live" else "History"} · ${packets.size} / ${PacketStore.LIMIT} packets · saved on phone",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Messages", "Adverts", "Other").forEach { name ->
                    FilterChip(selected = filter == name, onClick = { filter = name }, label = { Text(name) })
                }
            }
        } else Text("Saved adverts · newest first", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = if (adverts) advertList else packetList, modifier = Modifier.weight(1f).testTag("radio-log-list"),
            contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (adverts) {
                inbox.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (inbox.adverts.isEmpty()) item { EmptyLog("No adverts received yet.") }
                items(inbox.adverts, key = { "advert:${it.id}" }) { AdvertRow(it) }
            } else {
                (helper.packetError ?: inbox.packetError)?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (visible.isEmpty()) item { EmptyLog(if (packets.isNotEmpty()) "No packets match this filter." else if (frozen != null) "Paused with no packets. Resume to see new arrivals." else if (helper.radio.state() == "connected") "Waiting for radio packets…" else "Connect a radio to see its logs.") }
                items(visible, key = { "packet:${it.log().id()}" }) { RadioLogRow(it) }
            }
        }
    }
}

@Composable
private fun EmptyLog(text: String) {
    Text(text, Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RadioLogRow(packet: PacketStore.SavedPacket) {
    val log = packet.log()
    var expanded by rememberSaveable(log.id()) { mutableStateOf(false) }
    val details = log.details()
    Card(onClick = { expanded = !expanded }, shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.testTag("packet-${log.id()}").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(log.type(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(LogClock.format(Instant.ofEpochMilli(log.receivedAt()).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("RX · ${log.route()} · ${log.bytes()} B" + if (details.pathCount() >= 0) " · ${details.pathCount()} path entries" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${log.rssi()} dBm RSSI · ${log.snr()} dB SNR", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (expanded) "Less −" else "Details +", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .25f))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Packet #${log.id()} · ${chatTime(log.receivedAt(), includeDate = true)}", style = MaterialTheme.typography.bodySmall)
                        if (packet.radio().isNotEmpty()) Text("Companion: ${packet.radio().take(12)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text("Header 0x${String.format(Locale.ROOT, "%02X", details.header())} · payload v${details.version() + 1}", style = MaterialTheme.typography.bodySmall)
                        if (details.payloadBytes() >= 0) Text("Payload ${details.payloadBytes()} B · path hashes ${details.hashBytes()} B each", style = MaterialTheme.typography.bodySmall)
                        if (details.pathCount() >= 0) Text("Path: ${details.path().ifBlank { "Empty" }}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        if (details.transportCodes().isNotEmpty()) Text("Transport codes: ${details.transportCodes()}", style = MaterialTheme.typography.bodySmall)
                        if (details.note().isNotEmpty()) Text(details.note(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Text("Raw radio packet · hex", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(details.rawHex(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
