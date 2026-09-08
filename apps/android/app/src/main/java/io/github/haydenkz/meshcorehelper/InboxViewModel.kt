package io.github.haydenkz.meshcorehelper

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Conversation(val id: String, val kind: String, val name: String, val preview: String, val updatedAt: Long)
data class ChatMessage(val id: Long, val sender: String, val text: String, val sentAt: Long, val outgoing: Boolean, val delivery: String)
data class RecentAdvert(val id: Long, val name: String, val publicKeyPrefix: String, val nodeType: String, val receivedAt: Long)
data class InboxUiState(
    val channels: List<Conversation> = emptyList(),
    val chats: List<Conversation> = emptyList(),
    val adverts: List<RecentAdvert> = emptyList(),
    val conversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val hasOlder: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

/** Reads the same SQLite queries used by the glasses, including while the BLE service is stopped. */
class InboxViewModel(application: Application) : AndroidViewModel(application) {
    private val store = MessageStore(application)
    private val mutableState = MutableStateFlow(InboxUiState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var polling: Job? = null
    private var request: Job? = null
    private var revision = 0
    private var messageLimit = 32

    fun onVisible() {
        visible = true
        polling?.cancel()
        polling = viewModelScope.launch {
            while (true) {
                refresh()
                delay(1500)
            }
        }
    }
    fun onHidden() { visible = false; polling?.cancel(); request?.cancel(); revision++ }
    fun open(conversation: Conversation) {
        revision++
        messageLimit = 32
        mutableState.update { it.copy(conversation = conversation, messages = emptyList(), hasOlder = false, loading = true, error = null) }
        refreshNow()
    }
    fun closeConversation() {
        revision++
        mutableState.update { it.copy(conversation = null, messages = emptyList(), hasOlder = false, error = null) }
        refreshNow()
    }
    fun older() { messageLimit += 32; refreshNow() }
    fun refreshNow() {
        request?.cancel()
        request = viewModelScope.launch { refresh() }
    }
    private suspend fun refresh() {
        if (!visible) return
        val activeRevision = ++revision
        val conversation = state.value.conversation
        val limit = messageLimit
        try {
            val next = withContext(Dispatchers.IO) {
                synchronized(store) {
                    val channels = conversations(store.conversations("channel"))
                    val chats = conversations(store.conversations("direct"))
                    val adverts = JSONObject(store.allAdverts()).getJSONArray("items").objects().map {
                        RecentAdvert(it.getLong("id"), it.getString("name"), it.getString("publicKeyPrefix"), it.getString("nodeType"), it.getLong("receivedAt"))
                    }
                    val history = conversation?.let { JSONObject(store.messages(it.kind, it.id, Long.MAX_VALUE, limit)) }
                    val messages = history?.getJSONArray("items")?.objects()?.map {
                        ChatMessage(it.getLong("id"), it.getString("senderName"), it.getString("text"), it.getLong("sentAt"), it.getString("direction") == "out", it.getString("delivery"))
                    } ?: emptyList()
                    InboxUiState(channels, chats, adverts, conversation?.let { active -> (channels + chats).find { it.id == active.id && it.kind == active.kind } ?: active }, messages, history?.getBoolean("hasMore") ?: false, false)
                }
            }
            if (activeRevision == revision && visible) mutableState.value = next
        } catch (error: android.database.sqlite.SQLiteException) {
            if (activeRevision == revision) mutableState.update { it.copy(loading = false, error = "Could not read saved messages. Check free phone storage.") }
        } catch (error: org.json.JSONException) {
            if (activeRevision == revision) mutableState.update { it.copy(loading = false, error = "Could not read message history.") }
        }
    }
    private fun conversations(json: String) = JSONArray(json).objects().map {
        Conversation(it.getString("id"), it.getString("kind"), it.getString("name"), it.getString("preview"), it.getLong("updatedAt"))
    }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    override fun onCleared() {
        onHidden()
        synchronized(store) { store.close() }
        super.onCleared()
    }
}
