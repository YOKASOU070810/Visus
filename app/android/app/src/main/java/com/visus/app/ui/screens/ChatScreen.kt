package com.visus.app.ui.screens

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.AuthState
import com.visus.app.network.SocialApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

data class PrivateMsg(
    val id: Int, val senderId: Int, val content: String,
    val msgType: String, val createdAt: String?, val isRead: Boolean,
    val senderName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(friendId: Int, friendName: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<PrivateMsg>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val myId = AuthState.currentUserId.value
    val ctx = LocalContext.current
    val tts = remember { TextToSpeech(ctx) { } }

    fun load() {
        scope.launch {
            try {
                val token = AuthState.token.value ?: return@launch
                val resp = SocialApiClient.get("/api/messages/$friendId?limit=100", token)
                val arr = resp.optJSONObject("data")?.optJSONArray("messages") ?: JSONArray()
                messages = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    val s = o.optJSONObject("sender")
                    PrivateMsg(o.optInt("id"), o.optInt("sender_id"), o.optString("content"),
                        o.optString("msg_type"), o.optString("created_at"), o.optBoolean("is_read"),
                        s?.optString("first_name", "") ?: "")
                }
                listState.animateScrollToItem(messages.size - 1)
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun send() {
        if (input.isBlank()) return
        scope.launch {
            try {
                val token = AuthState.token.value ?: return@launch
                SocialApiClient.post("/api/messages/send", JSONObject().apply {
                    put("receiver_id", friendId); put("content", input.trim())
                    put("msg_type", "text")
                }, token)
                input = ""
                load()
            } catch (e: Exception) { /* ignore */ }
        }
    }

    LaunchedEffect(friendId) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(friendName, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f).padding(12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(messages) { msg ->
                    val isMe = msg.senderId == myId
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                    ) {
                        if (!isMe) Text(msg.senderName, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                        Card(
                            modifier = Modifier.widthIn(max = 280.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = if (isMe) Color(0xFFDCF8C6) else Color.White),
                            onClick = {
                                tts.language = Locale.CHINESE
                                tts.speak("${msg.senderName}说: ${msg.content}", TextToSpeech.QUEUE_FLUSH, null, "pm_${msg.id}")
                            }
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(msg.content, fontSize = 15.sp)
                                if (msg.msgType == "location") Text("📍 位置", fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                        if (isMe) Text(msg.createdAt?.take(19)?.replace("T", " ") ?: "", fontSize = 10.sp, color = Color.LightGray)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("消息...") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { send() }) { Icon(Icons.Default.Send, "发送", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
