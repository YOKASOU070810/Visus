package com.aiglass.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aiglass.app.audio.AudioCaptureManager
import com.aiglass.app.audio.AudioPlaybackManager
import com.aiglass.app.camera.CameraManager
import com.aiglass.app.network.ConnectionState
import com.aiglass.app.network.ServerConfig
import com.aiglass.app.network.WebSocketManager
import com.aiglass.app.ui.ChatMessage
import com.aiglass.app.ui.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "aiglass_prefs"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_SERVER_PORT = "server_port"
    }

    private val webSocketManager = WebSocketManager()
    private var cameraManager: CameraManager? = null
    private var audioCapture: AudioCaptureManager? = null
    private val audioPlayback = AudioPlaybackManager()

    private var latestFrame by mutableStateOf<ByteArray?>(null)
    private var connectionState by mutableStateOf(ConnectionState())
    private var asrPartial by mutableStateOf("")
    private var asrFinals by mutableStateOf<List<String>>(emptyList())
    private var navigationStatus by mutableStateOf("待机")
    private var aiResponse by mutableStateOf<String?>(null)
    private var chatMessages by mutableStateOf<List<ChatMessage>>(emptyList())
    private var serverConfig by mutableStateOf(ServerConfig())
    private var isConnected = false

    private var hasPermissions = false

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasPermissions = grants.values.all { it }
        if (hasPermissions) {
            Log.i(TAG, "Permissions granted, starting camera + audio")
            ensureCameraStarted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        serverConfig = loadServerConfig()
        webSocketManager.updateConfig(serverConfig)
        setupCallbacks()

        setContent {
            MainScreen(
                latestFrame = latestFrame,
                connectionState = connectionState,
                asrPartial = asrPartial,
                asrFinals = asrFinals,
                navigationStatus = navigationStatus,
                aiResponse = aiResponse,
                chatMessages = chatMessages,
                serverConfig = serverConfig,
                onConfigChange = { newConfig ->
                    serverConfig = newConfig
                    saveServerConfig(newConfig)
                    webSocketManager.updateConfig(newConfig)
                    addChatMessage("服务器地址已更新: ${newConfig.host}:${newConfig.port}", isUser = false)
                },
                onConnect = { connect() },
                onDisconnect = { disconnect() }
            )
        }

        requestPermissionsAndStart()
    }

    private fun loadServerConfig(): ServerConfig {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val host = prefs.getString(KEY_SERVER_HOST, "10.0.2.2") ?: "10.0.2.2"
        val port = prefs.getInt(KEY_SERVER_PORT, 8081)
        return ServerConfig(host = host, port = port)
    }

    private fun saveServerConfig(config: ServerConfig) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_HOST, config.host)
            .putInt(KEY_SERVER_PORT, config.port)
            .apply()
    }

    private fun setupCallbacks() {
        lifecycleScope.launch {
            webSocketManager.connectionState.collect { state ->
                connectionState = state
                if (state.isFullyConnected && !isConnected) {
                    isConnected = true
                    addChatMessage("已连接到服务器", isUser = false)
                    startAudioStream()
                }
                if (!state.isFullyConnected && isConnected) {
                    isConnected = false
                    audioPlayback.stop()
                }
            }
        }
        lifecycleScope.launch {
            webSocketManager.asrPartial.collect { partial -> asrPartial = partial }
        }
        lifecycleScope.launch {
            webSocketManager.asrFinals.collect { finals -> asrFinals = finals }
        }
        lifecycleScope.launch {
            webSocketManager.aiResponse.collect { response ->
                if (response != null) {
                    aiResponse = response
                    addChatMessage(response, isUser = false)
                }
            }
        }
        lifecycleScope.launch {
            webSocketManager.navigationStatus.collect { status ->
                navigationStatus = status
                if (status.isNotBlank() && status != "待机") {
                    addChatMessage("[导航] $status", isUser = false)
                }
            }
        }
        webSocketManager.onFrameReceived = { jpegBytes -> latestFrame = jpegBytes }
        webSocketManager.onAiMessage = { msg -> addChatMessage(msg, isUser = false) }
        webSocketManager.onNavigationGuidance = { guidance ->
            addChatMessage("[导航] $guidance", isUser = false)
        }
    }

    private fun requestPermissionsAndStart() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            hasPermissions = true
            ensureCameraStarted()
            connect()
        } else {
            permissionsLauncher.launch(permissions)
        }
    }

    /** 启动 AI 语音流播放——服务器 /stream.wav 推送 AI 回复语音 */
    private fun startAudioStream() {
        val host = serverConfig.host
        val port = serverConfig.port
        val url = "http://$host:$port/stream.wav"
        Log.i(TAG, "Starting audio stream from $url")
        audioPlayback.playStreamFromUrl(
            url = url,
            onStart = { addChatMessage("AI语音已就绪", isUser = false) },
            onDone = { Log.i(TAG, "Audio stream ended") }
        )
    }

    /** 摄像头始终运行，只初始化一次 */
    private fun ensureCameraStarted() {
        if (cameraManager != null) return
        cameraManager = CameraManager(this) { jpegBytes ->
            webSocketManager.sendFrame(jpegBytes)
        }
        cameraManager!!.start(applicationContext)
        addChatMessage("摄像头已启动", isUser = false)
    }

    /** 连接服务器：启动音频 + WebSocket */
    private fun connect() {
        webSocketManager.connectAll()

        if (audioCapture == null) {
            audioCapture = AudioCaptureManager { pcmChunk ->
                webSocketManager.sendAudioChunk(pcmChunk)
            }
        }
        audioCapture!!.startRecording()
        addChatMessage("正在连接服务器...", isUser = false)
    }

    /** 断开：停 WebSocket + 音频，摄像头保持运行 */
    private fun disconnect() {
        audioCapture?.stopRecording()
        audioCapture = null
        webSocketManager.disconnectAll()
        isConnected = false
        addChatMessage("已断开连接", isUser = false)
    }

    private fun addChatMessage(text: String, isUser: Boolean) {
        chatMessages = (chatMessages + ChatMessage(text = text, isUser = isUser))
            .takeLast(100)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { cameraManager?.stop() } catch (_: Exception) {}
        try { audioCapture?.stopRecording() } catch (_: Exception) {}
        try { audioPlayback.release() } catch (_: Exception) {}
        webSocketManager.destroy()
    }
}
