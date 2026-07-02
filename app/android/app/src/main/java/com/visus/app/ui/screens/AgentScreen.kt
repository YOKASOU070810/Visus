package com.visus.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.visus.app.data.AuthState
import com.visus.app.network.SocialApiClient
import com.visus.app.network.MapApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class ChatBubble(val isUser: Boolean, val text: String)

@Composable
fun AgentScreen(
    onNavigate: (String, Map<String, String>) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<ChatBubble>() }
    val listState = rememberLazyListState()
    var isListening by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("点击下方麦克风说话") }
    var hasGreeted by remember { mutableStateOf(false) }

    // Quick commands
    val quickCommands = listOf(
        "🏥 去附近医院" to "去附近的医院",
        "🚶 开启辅助出行" to "开启摄像头辅助出行",
        "👥 查看好友" to "查看好友状态",
        "📍 我在哪里" to "我在哪里"
    )

    // TTS
    val tts = remember {
        TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) statusText = "语音引擎初始化失败"
        }
    }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    fun speak(text: String) {
        tts.language = java.util.Locale.CHINESE
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "agent_${System.currentTimeMillis()}")
    }

    // Greet on first open
    LaunchedEffect(Unit) {
        if (!hasGreeted) {
            delay(500)
            val greeting = "Visus在，有什么需要帮助的？"
            messages.add(ChatBubble(false, greeting))
            speak(greeting)
            hasGreeted = true
        }
    }

    // Record audio permission
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            statusText = "请说话..."
            isListening = true
        } else {
            statusText = "需要录音权限才能使用语音"
            // Fallback: use text input
            isListening = true // simulate
        }
    }

    fun sendCommand(text: String) {
        if (text.isBlank()) return
        messages.add(ChatBubble(true, text))
        statusText = "Visus 思考中..."
        isListening = false

        scope.launch {
            try {
                val token = AuthState.token.value ?: return@launch
                val body = JSONObject().apply {
                    put("text", text)
                    put("user_id", AuthState.currentUserId.value)
                    put("lat", 0.0) // TODO: get real GPS
                    put("lng", 0.0)
                    put("city", "")
                }
                val resp = SocialApiClient.post("/api/agent/command", body, token)
                val data = resp.optJSONObject("data") ?: return@launch
                val action = data.optString("action", "chat")
                val reply = data.optString("reply_text", "收到")
                val params = data.optJSONObject("params") ?: JSONObject()
                val extra = data.optJSONObject("extra")

                // Show reply
                messages.add(ChatBubble(false, reply))
                speak(reply)
                listState.animateScrollToItem(messages.size - 1)

                // Execute action
                when (action) {
                    "navigate" -> {
                        val dest = params.optString("destination", "")
                        if (dest.isNotBlank()) {
                            onNavigate("navigate", mapOf("destination" to dest,
                                "distance" to (extra?.optString("distance", "") ?: ""),
                                "duration" to (extra?.optString("duration", "") ?: "")))
                        }
                    }
                    "send_sos" -> {
                        SocialApiClient.triggerEmergency("voice_trigger",
                            lat = 0.0, lng = 0.0,
                            description = params.optString("message", text))
                    }
                    "start_assist" -> onNavigate("start_assist", emptyMap())
                    "check_friends" -> onNavigate("friends", emptyMap())
                    "where_am_i" -> {
                        val addr = extra?.optString("address", "") ?: ""
                        if (addr.isNotBlank()) {
                            messages.add(ChatBubble(false, "你现在在: $addr"))
                            speak("你在: $addr")
                        }
                    }
                    "search_nearby" -> {
                        val results = extra?.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val names = (0 until results.length()).map {
                                val o = results.getJSONObject(it)
                                "${o.optString("name")}(${o.optString("distance")}米)"
                            }.joinToString("，")
                            val msg = "附近的${params.optString("keywords")}有: $names"
                            messages.add(ChatBubble(false, msg))
                            speak(msg)
                        }
                    }
                    else -> { /* chat or other - reply already spoken */ }
                }
            } catch (e: Exception) {
                val err = "抱歉，AI服务暂不可用: ${e.message}"
                messages.add(ChatBubble(false, err))
                speak(err)
            } finally {
                statusText = "点击下方麦克风说话"
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFE8F0FE), Color(0xFFF5F5F5))
                    )
                )
        ) {
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🤖 Visus AI 助手", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(statusText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }

            // Chat messages
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages.toList()) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            modifier = Modifier.widthIn(max = 300.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.isUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ),
                            onClick = { if (!msg.isUser) speak(msg.text) }
                        ) {
                            Text(
                                text = msg.text,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            // Quick commands
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickCommands.forEach { (label, cmd) ->
                    AssistChip(
                        onClick = { sendCommand(cmd) },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Mic button
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) Color(0xFFE63946)
                            else MaterialTheme.colorScheme.primary
                        )
                        .clickable {
                            if (isListening) {
                                // Stop recording -> process (simulate with text input for now)
                                isListening = false
                                statusText = "处理中..."
                            } else {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                    != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    // In real app: start recording, ASR -> text
                                    // For now: show a text input dialog
                                    isListening = true
                                    statusText = "正在聆听...（请说话）"
                                    // Simulate after 3 seconds
                                    scope.launch {
                                        delay(3000)
                                        if (isListening) {
                                            isListening = false
                                            // Use a text dialog fallback for demo
                                            statusText = "请说出指令（演示模式：请在输入框输入）"
                                        }
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isListening) "⏹" else "🎤",
                        fontSize = 36.sp
                    )
                }
            }

            // Text input fallback
            var textInput by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("输入文字指令...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { sendCommand(textInput); textInput = "" }) {
                    Text("发送")
                }
            }
        }
    }
}
