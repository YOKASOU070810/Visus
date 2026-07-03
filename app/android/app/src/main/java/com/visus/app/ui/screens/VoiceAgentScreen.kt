package com.visus.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.visus.app.data.AuthState
import com.visus.app.data.SocialState
import com.visus.app.network.SocialApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.*

data class VoiceMessage(val id: Long = System.currentTimeMillis(), val isUser: Boolean, val text: String)

@Composable
fun VoiceAgentScreen(onNavigate: (String, Map<String, String>) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("轻触麦克风") }
    var partialText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<VoiceMessage>()) }
    val listState = rememberLazyListState()

    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(ctx) }
    val recognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var greetingDone by remember { mutableStateOf(false) }

    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(800, easing = EaseInOutCubic), RepeatMode.Reverse), label = "pulse"
    )

    fun scrollToBottom() {
        scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    }

    // ── Helpers (defined first for closure capture) ──
    fun doStartListening() {
        if (!isListening && !isThinking && !isSpeaking) {
            try { recognizer.startListening(recognizerIntent) } catch (_: Exception) { statusText = "请授予麦克风权限" }
        }
    }

    fun doStopListening() {
        try { recognizer.stopListening() } catch (_: Exception) {}
        isListening = false
    }

    fun handleUserInput(text: String) {
        messages = messages + VoiceMessage(isUser = true, text = text)
        scrollToBottom()
        isThinking = true; statusText = "Visus 思考中…"

        scope.launch {
            try {
                val token = AuthState.token.value ?: return@launch
                val body = JSONObject().apply {
                    put("text", text); put("user_id", AuthState.currentUserId.value)
                    put("lat", 0.0); put("lng", 0.0); put("city", "")
                }
                val resp = SocialApiClient.post("/api/agent/command", body, token)
                val data = resp.optJSONObject("data")
                val action = data?.optString("action", "chat") ?: "chat"
                val replyText = data?.optString("reply_text", "") ?: "抱歉，我没理解你的意思"
                val params = data?.optJSONObject("params")
                val extra = data?.optJSONObject("extra")

                isThinking = false
                messages = messages + VoiceMessage(isUser = false, text = replyText)
                scrollToBottom()

                when (action) {
                    "navigate" -> {
                        (params?.optString("destination", "") ?: "").takeIf { it.isNotBlank() }
                            ?.let { onNavigate("navigate", mapOf("destination" to it)) }
                    }
                    "send_sos" -> SocialState.triggerEmergency("manual_sos", params?.optString("message", text) ?: text)
                    "start_assist" -> onNavigate("start_assist", emptyMap())
                    "check_friends" -> SocialState.loadFriends()
                    "search_nearby" -> {
                        extra?.optJSONArray("results")?.let { results ->
                            if (results.length() > 0) {
                                val sb = StringBuilder("附近找到：\n")
                                for (i in 0 until minOf(results.length(), 3))
                                    sb.append("${results.getJSONObject(i).optString("name")}，${results.getJSONObject(i).optString("address")}\n")
                                messages = messages + VoiceMessage(isUser = false, text = sb.toString())
                            }
                        }
                    }
                }

                val p = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "r${System.currentTimeMillis()}") }
                tts?.speak(replyText, TextToSpeech.QUEUE_ADD, p, "r${System.currentTimeMillis()}")

                scope.launch {
                    delay(1200); while (isSpeaking) { delay(200) }; delay(600)
                    if (!isThinking && !isListening) doStartListening()
                }
            } catch (e: Exception) {
                isThinking = false
                messages = messages + VoiceMessage(isUser = false, text = "抱歉，AI暂不可用：${e.message}")
                statusText = "轻触麦克风"; scrollToBottom()
                scope.launch { delay(1500); doStartListening() }
            }
        }
    }

    // ── RecognitionListener ──
    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { isListening = true; statusText = "正在听…"; partialText = "" }
            override fun onBeginningOfSpeech() { statusText = "正在听…" }
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false; statusText = "正在理解…" }
            override fun onError(error: Int) {
                isListening = false
                statusText = when (error) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音，请重试"
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一次"
                    else -> "轻触麦克风"
                }
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    scope.launch { delay(800); if (!isThinking && !isSpeaking) doStartListening() }
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim() ?: return
                if (t.isBlank()) return
                partialText = ""
                handleUserInput(t)
            }
            override fun onPartialResults(pr: Bundle?) {
                pr?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { partialText = it }
            }
            override fun onEvent(e: Int, p: Bundle?) {}
        }
        recognizer.setRecognitionListener(listener)
        onDispose { recognizer.setRecognitionListener(null) }
    }

    // ── TTS init ──
    LaunchedEffect(Unit) {
        tts = TextToSpeech(ctx) { s -> if (s == TextToSpeech.SUCCESS) { tts?.language = Locale.CHINESE; ttsReady = true } }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { isSpeaking = true; statusText = "Visus 正在回复…" }
            override fun onDone(id: String?) { isSpeaking = false; statusText = "轻触麦克风" }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { isSpeaking = false; statusText = "轻触麦克风" }
        })
    }

    // ── Greeting ──
    LaunchedEffect(ttsReady) {
        if (ttsReady && !greetingDone) {
            greetingDone = true; delay(500)
            val g = "Visus在，有什么需要帮助的吗？"
            messages = messages + VoiceMessage(isUser = false, text = g)
            val p = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "greeting") }
            tts?.speak(g, TextToSpeech.QUEUE_FLUSH, p, "greeting")
        }
    }

    // ── Auto-start listening after greeting ──
    LaunchedEffect(greetingDone, isSpeaking) {
        if (greetingDone && !isSpeaking && !isThinking && !isListening) { delay(2200); doStartListening() }
    }

    // ── Cleanup ──
    DisposableEffect(Unit) {
        onDispose { doStopListening(); recognizer.destroy(); tts?.stop(); tts?.shutdown() }
    }

    val hasMicPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ═══════════════ UI ═══════════════
    Box(Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF0F172A)))
    )) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                Text("🤖 Visus AI", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(8.dp).clip(CircleShape).background(
                    when { isListening -> Color(0xFF22C55E); isThinking -> Color(0xFFF59E0B); isSpeaking -> Color(0xFF6366F1); else -> Color(0xFF64748B) }
                ))
                Spacer(Modifier.width(6.dp))
                Text(when { isListening -> "正在听"; isThinking -> "思考中"; isSpeaking -> "回复中"; else -> "就绪" }, color = Color(0xFF94A3B8), fontSize = 12.sp)
            }
            Text(statusText, color = Color(0xFF94A3B8), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))

            // Messages
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (partialText.isNotBlank()) {
                    item("partial") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Card(Modifier.widthIn(max = 280.dp), shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF6366F1).copy(alpha = 0.6f))) {
                                Text(partialText, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(12.dp), fontStyle = FontStyle.Italic)
                            }
                        }
                    }
                }
                items(messages, key = { it.id }) { msg ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start) {
                        if (!msg.isUser) {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)))), contentAlignment = Alignment.Center) { Text("🤖", fontSize = 16.sp) }
                            Spacer(Modifier.width(8.dp))
                        }
                        Card(Modifier.widthIn(max = 260.dp),
                            shape = RoundedCornerShape(if (msg.isUser) 16.dp else 4.dp, 16.dp, 16.dp, if (msg.isUser) 4.dp else 16.dp),
                            colors = CardDefaults.cardColors(containerColor = if (msg.isUser) Color(0xFF6366F1) else Color(0xFF1E293B))) {
                            Text(msg.text, color = if (msg.isUser) Color.White else Color(0xFFE2E8F0), fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.padding(12.dp))
                        }
                        if (msg.isUser) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF475569)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                if (isThinking) {
                    item("thinking") {
                        Row(Modifier.fillMaxWidth().padding(start = 40.dp)) {
                            Card(shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (i in 0..2) {
                                        val a by rememberInfiniteTransition("td$i").animateFloat(0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 300), RepeatMode.Reverse), "da$i")
                                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF6366F1).copy(alpha = a)))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Quick actions
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("🏥" to "附近医院", "🚶" to "辅助出行", "📍" to "我在哪", "🆘" to "紧急求助").forEach { (emoji, label) ->
                    AssistChip(onClick = {
                        if (!isThinking && !isSpeaking) { doStopListening(); handleUserInput(label) }
                    }, label = { Text("$emoji $label", fontSize = 11.sp, color = Color(0xFFCBD5E1)) },
                        modifier = Modifier.height(32.dp), shape = RoundedCornerShape(16.dp),
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFF334155)))
                }
            }
            Spacer(Modifier.height(16.dp))

            // Mic button
            Box(Modifier.size(if (isListening || isThinking) 88.dp else 80.dp)
                .then(if (isListening || isThinking) Modifier.scale(pulseScale) else Modifier)
                .shadow(if (isListening) 16.dp else 8.dp, CircleShape).clip(CircleShape)
                .background(Brush.radialGradient(colors = when {
                    isListening -> listOf(Color(0xFF22C55E), Color(0xFF16A34A))
                    isThinking -> listOf(Color(0xFFF59E0B), Color(0xFFD97706))
                    isSpeaking -> listOf(Color(0xFF6366F1), Color(0xFF4F46E5))
                    else -> listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                }))
                .clickable(enabled = !isThinking && !isSpeaking) {
                    if (!hasMicPermission) statusText = "请授予麦克风权限"
                    else if (isListening) doStopListening() else doStartListening()
                }, contentAlignment = Alignment.Center
            ) {
                Icon(if (isListening) Icons.Default.Mic else if (isThinking) Icons.Default.HourglassTop else if (isSpeaking) Icons.Default.VolumeUp else Icons.Default.Mic,
                    null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when { isListening -> "正在听…"; isThinking -> "思考中…"; isSpeaking -> "回复中…"; !hasMicPermission -> "需要麦克风权限"; else -> "轻触说话" },
                color = Color(0xFF64748B), fontSize = 13.sp, modifier = Modifier.padding(bottom = 32.dp)
            )
        }
    }
}
