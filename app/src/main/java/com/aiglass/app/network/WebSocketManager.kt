package com.aiglass.app.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ServerConfig(
    val host: String = "10.0.2.2",
    val port: Int = 8081,
    val useTls: Boolean = false
) {
    val baseUrl: String get() = "${if (useTls) "wss" else "ws"}://$host:$port"
}

data class ConnectionState(
    val camera: Boolean = false,
    val audio: Boolean = false,
    val viewer: Boolean = false,
    val ui: Boolean = false
) {
    val isFullyConnected: Boolean get() = camera && audio && viewer && ui
}

class WebSocketManager(
    initialConfig: ServerConfig = ServerConfig()
) {
    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    @Volatile
    var config: ServerConfig = initialConfig
        private set

    private var client: OkHttpClient = buildClient()

    private fun buildClient() = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun updateConfig(newConfig: ServerConfig) {
        if (config == newConfig) return
        val wasConnected = _connectionState.value.isFullyConnected
        disconnectAll()
        config = newConfig
        client = buildClient()
        resetReconnectState()
        if (wasConnected) {
            connectAll()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _asrPartial = MutableStateFlow("")
    val asrPartial: StateFlow<String> = _asrPartial

    private val _asrFinals = MutableStateFlow<List<String>>(emptyList())
    val asrFinals: StateFlow<List<String>> = _asrFinals

    private val _aiResponse = MutableStateFlow<String?>(null)
    val aiResponse: StateFlow<String?> = _aiResponse

    private val _aiSpeechText = MutableStateFlow<String?>(null)
    val aiSpeechText: StateFlow<String?> = _aiSpeechText

    private val _navigationStatus = MutableStateFlow("待机")
    val navigationStatus: StateFlow<String> = _navigationStatus

    private var cameraWs: WebSocket? = null
    private var audioWs: WebSocket? = null
    private var viewerWs: WebSocket? = null
    private var uiWs: WebSocket? = null
    private var audioChunkSendCount = 0L

    // Callbacks
    var onFrameReceived: ((ByteArray) -> Unit)? = null
    var onAiMessage: ((String) -> Unit)? = null
    var onNavigationGuidance: ((String) -> Unit)? = null

    // connectAll / disconnectAll are defined below with reconnect logic

    fun sendFrame(jpegBytes: ByteArray) {
        val ws = cameraWs ?: run {
            Log.w(TAG, "Camera WS not ready, dropping frame")
            return
        }
        if (_connectionState.value.camera) {
            ws.send(jpegBytes.toByteString())
        }
    }

    fun sendAudioChunk(pcmBytes: ByteArray) {
        val ws = audioWs ?: run {
            Log.w(TAG, "Audio WS not ready, dropping ${pcmBytes.size} bytes")
            return
        }
        if (_connectionState.value.audio) {
            audioChunkSendCount += 1
            val sent = ws.send(pcmBytes.toByteString())
            Log.d(TAG, "Audio chunk #$audioChunkSendCount send: ${pcmBytes.size} bytes, success=$sent")
        } else {
            Log.w(TAG, "Audio WS disconnected, dropping ${pcmBytes.size} bytes")
        }
    }

    // ==================== Camera WS (send JPEG frames to server) ====================
    private fun connectCamera() {
        val request = Request.Builder()
            .url("${config.baseUrl}/ws/camera")
            .build()

        cameraWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Camera WS connected")
                _connectionState.value = _connectionState.value.copy(camera = true)
                reconnectCounters.remove(::connectCamera.hashCode().toString())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Camera WS failed: ${t.message}")
                _connectionState.value = _connectionState.value.copy(camera = false)
                scheduleReconnect(::connectCamera)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Camera WS closed: $reason")
                _connectionState.value = _connectionState.value.copy(camera = false)
                if (code != 1000) scheduleReconnect(::connectCamera)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Camera WS text: $text")
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Camera WS is send-only; ignore any binary echo from server.
                // Only Viewer WS updates the displayed frame.
            }
        })
    }

    // ==================== Viewer WS (receive processed frames) ====================
    private fun connectViewer() {
        val request = Request.Builder()
            .url("${config.baseUrl}/ws/viewer")
            .build()

        viewerWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Viewer WS connected")
                _connectionState.value = _connectionState.value.copy(viewer = true)
                reconnectCounters.remove(::connectViewer.hashCode().toString())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Viewer WS failed: ${t.message}")
                _connectionState.value = _connectionState.value.copy(viewer = false)
                scheduleReconnect(::connectViewer)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Viewer WS closed: $reason")
                _connectionState.value = _connectionState.value.copy(viewer = false)
                if (code != 1000) scheduleReconnect(::connectViewer)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                onFrameReceived?.invoke(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Viewer WS text: $text")
            }
        })
    }

    // ==================== Audio WS (send PCM to server for ASR) ====================
    private fun connectAudio() {
        val request = Request.Builder()
            .url("${config.baseUrl}/ws_audio")
            .build()

        audioWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Audio WS connected")
                _connectionState.value = _connectionState.value.copy(audio = true)
                reconnectCounters.remove(::connectAudio.hashCode().toString())
                audioChunkSendCount = 0L
                webSocket.send("START")  // 触发服务器启动 ASR 语音识别
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Audio WS failed: ${t.message}")
                _connectionState.value = _connectionState.value.copy(audio = false)
                scheduleReconnect(::connectAudio)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Audio WS closed: $reason")
                _connectionState.value = _connectionState.value.copy(audio = false)
                if (code != 1000) scheduleReconnect(::connectAudio)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Server sends ASR status via this channel
                Log.d(TAG, "Audio WS text: $text")
                parseAsrMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // Audio response stream
            }
        })
    }

    // ==================== UI WS (receive status, ASR results, AI replies) ====================
    private fun connectUi() {
        val request = Request.Builder()
            .url("${config.baseUrl}/ws_ui")
            .build()

        uiWs = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "UI WS connected")
                _connectionState.value = _connectionState.value.copy(ui = true)
                reconnectCounters.remove(::connectUi.hashCode().toString())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "UI WS failed: ${t.message}")
                _connectionState.value = _connectionState.value.copy(ui = false)
                scheduleReconnect(::connectUi)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "UI WS closed: $reason")
                _connectionState.value = _connectionState.value.copy(ui = false)
                if (code != 1000) scheduleReconnect(::connectUi)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseUiMessage(text)
            }
        })
    }

    // ==================== Message Parsing ====================
    private fun parseUiMessage(text: String) {
        try {
            if (text.trim().startsWith("{")) {
                val json = JSONObject(text)
                if (json.optString("type") == "ai_reply") {
                    val msg = json.optString("text").trim()
                    if (msg.isNotEmpty()) {
                        _aiResponse.value = msg
                        onAiMessage?.invoke(msg)
                        if (json.optBoolean("tts_fallback", false)) {
                            _aiSpeechText.value = null
                            _aiSpeechText.value = msg
                        }
                    }
                    return
                }
            }
            when {
                text.startsWith("INIT:") -> {
                    val json = text.removePrefix("INIT:")
                    // JSON contains partial + finals
                    val partial = extractJsonString(json, "partial")
                    val finals = extractJsonArray(json, "finals")
                    if (partial != null) _asrPartial.value = partial
                    if (finals != null) _asrFinals.value = finals
                }
                text.startsWith("PARTIAL:") -> {
                    _asrPartial.value = text.removePrefix("PARTIAL:").trim()
                }
                text.startsWith("FINAL:") -> {
                    val final = text.removePrefix("FINAL:").trim()
                    _asrFinals.value = _asrFinals.value + final
                    // Check if it's AI or navigation
                    if (final.startsWith("[AI]")) {
                        val msg = final.removePrefix("[AI]").trim()
                        _aiResponse.value = msg
                        onAiMessage?.invoke(msg)
                    } else if (final.startsWith("[导航]")) {
                        val msg = final.removePrefix("[导航]").trim()
                        _navigationStatus.value = msg
                        onNavigationGuidance?.invoke(msg)
                    }
                }
                text.startsWith("STATUS:") -> {
                    _navigationStatus.value = text.removePrefix("STATUS:").trim()
                }
                text == "RESTART" -> {
                    Log.i(TAG, "Server requested ASR restart")
                }
                else -> {
                    // Plain text response
                    _aiResponse.value = text
                    onAiMessage?.invoke(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing UI message: $text", e)
        }
    }

    private fun parseAsrMessage(text: String) {
        when {
            text.startsWith("PARTIAL:") -> _asrPartial.value = text.removePrefix("PARTIAL:").trim()
            text.startsWith("FINAL:") -> {
                val final = text.removePrefix("FINAL:").trim()
                _asrFinals.value = _asrFinals.value + final
            }
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")
    }

    private fun extractJsonArray(json: String, key: String): List<String>? {
        val regex = """"$key"\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(json) ?: return null
        val arrStr = match.groupValues[1]
        if (arrStr.isBlank()) return emptyList()
        return arrStr.split(",").map { it.trim().removeSurrounding("\"") }
    }

    // ==================== Reconnect Logic (per-connection) ====================
    @Volatile private var userDisconnected = false
    private val reconnectCounters = mutableMapOf<String, Int>()
    private var reconnectJob: Job? = null

    private fun scheduleReconnect(connectFn: () -> Unit) {
        if (userDisconnected) return
        val key = connectFn.hashCode().toString()
        val attempts = (reconnectCounters[key] ?: 0) + 1
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached for $key")
            return
        }
        reconnectCounters[key] = attempts
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS * attempts.coerceAtMost(5))
            Log.i(TAG, "Reconnecting (attempt $attempts)...")
            connectFn()
        }
    }

    private fun resetReconnectState() {
        reconnectCounters.clear()
        reconnectJob?.cancel()
        reconnectJob = null
    }

    fun connectAll() {
        userDisconnected = false
        resetReconnectState()
        scope.launch {
            connectCamera()
            connectViewer()
            connectAudio()
            connectUi()
        }
    }

    fun disconnectAll() {
        userDisconnected = true
        resetReconnectState()
        cameraWs?.close(1000, "User disconnect")
        audioWs?.close(1000, "User disconnect")
        viewerWs?.close(1000, "User disconnect")
        uiWs?.close(1000, "User disconnect")
        _connectionState.value = ConnectionState()
    }

    fun destroy() {
        userDisconnected = true
        disconnectAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }
}
