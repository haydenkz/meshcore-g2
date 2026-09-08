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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.haydenkz.meshcorehelper.*
import io.github.haydenkz.meshcorehelper.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DetectionClock = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss", Locale.getDefault())
private fun contactType(type: Int) = if (type == 1) "User" else ContactInfo.typeName(type)
private fun contactIcon(type: Int) = when (type) {
    1 -> R.drawable.ic_person
    2 -> R.drawable.ic_repeater
    3 -> R.drawable.ic_server
    4 -> R.drawable.ic_sensor
    else -> R.drawable.ic_node
}

@Composable
internal fun ContactsScreen(helper: HelperUiState, inbox: InboxUiState, onProfile: () -> Unit, modifier: Modifier = Modifier) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    val query = search.trim()
    val contacts = inbox.contacts.filter {
        (filter == 0 || if (filter == -1) it.type() !in 1..4 else it.type() == filter) &&
            (it.name().contains(query, ignoreCase = true) || it.publicKey().contains(query, ignoreCase = true))
    }
    val list = rememberLazyListState()
    val multipleRadios = inbox.contacts.map { it.radio() }.distinct().size > 1
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Contacts", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("${inbox.contacts.size} discovered", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().testTag("contacts-search"),
            singleLine = true, placeholder = { Text("Search names or public keys") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) }, shape = RoundedCornerShape(24.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "All", 1 to "Users", 2 to "Repeaters", 4 to "Sensors", 3 to "Room servers", -1 to "Unknown").forEach { (type, title) ->
                FilterChip(selected = filter == type, onClick = { filter = type }, label = { Text(title) })
            }
        }
        Text(if (helper.radio.state() == "connected") "From your radios · last detected first" else "Saved contacts · last detected first",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("contacts-list"),
            contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            inbox.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (inbox.contacts.isEmpty()) item {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (inbox.loading) "Loading contacts…" else if (helper.radio.state() == "connected") "Contacts appear as your radio shares them." else "Connect a radio to discover users, repeaters, sensors, and room servers.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (helper.radio.state() != "connected") FilledTonalButton(onClick = onProfile) { Text("Connect a radio") }
                }
            } else if (contacts.isEmpty()) item {
                Text("No contacts match your search or filter.", Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyMedium)
            }
            items(contacts, key = { "${it.radio()}:${it.publicKey()}" }) { contact ->
                Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Icon(painterResource(contactIcon(contact.type())), contactType(contact.type()), Modifier.padding(10.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(contact.name().ifBlank { "Unnamed ${contactType(contact.type()).lowercase(Locale.getDefault())}" }, style = MaterialTheme.typography.titleSmall)
                                Text(contactType(contact.type()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Public key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(contact.publicKey(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        }
                        Text(if (contact.detectedAt() > 0) "Last detected ${DetectionClock.format(Instant.ofEpochMilli(contact.detectedAt()).atZone(ZoneId.systemDefault()))}" else "Detection time unavailable",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (multipleRadios) Text("Via companion ${contact.radio().take(12)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
