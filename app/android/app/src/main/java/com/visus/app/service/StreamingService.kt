package com.visus.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.visus.app.MainActivity
import com.visus.app.R
import com.visus.app.data.SettingsDataStore
import com.visus.app.data.StreamingUiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class StreamingService : Service() {

    companion object {
        private const val TAG = "VisusStreaming"
        private const val NOTIFICATION_CHANNEL_ID = "visus_streaming_channel"
        private const val NOTIFICATION_ID = 1
        private const val CAMERA_WIDTH = 640
        private const val CAMERA_HEIGHT = 480
        private const val CAMERA_SEND_INTERVAL_MS = 100L
        private const val CAMERA_PREVIEW_ROTATE_DEG = 90f
        private const val MIC_RESUME_DELAY_MS = 1800L
        private const val PLAYBACK_BUFFER_MS = 1500
        private const val AUDIO_SAMPLE_RATE = 16000
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_BUFFER_SIZE = 3200
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isStreaming = AtomicBoolean(false)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraHandler: Handler? = null
    private var cameraHandlerThread: HandlerThread? = null
    private var lastCameraSendMs = 0L

    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null
    private var playbackJob: Job? = null
    private var micResumeJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private val suppressMicUpload = AtomicBoolean(false)
    private var audioBytesSentThisSecond = 0L
    private var audioStatsLastLogMs = 0L

    private var cameraWebSocket: WebSocketClient? = null
    private var audioWebSocket: WebSocketClient? = null
    private var uiWebSocket: WebSocketClient? = null
    private var settingsDataStore: SettingsDataStore? = null
    private var textToSpeech: TextToSpeech? = null
    private var lastAlertUiMs = 0L
    private var lastAlertLogMs = 0L

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onCreate() {
        super.onCreate()
        settingsDataStore = SettingsDataStore(this)
        createNotificationChannel()
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.CHINESE
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startStreaming()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        serviceScope.cancel()
    }

    fun isStreaming(): Boolean = isStreaming.get()

    private fun startStreaming() {
        if (isStreaming.getAndSet(true)) return
        StreamingUiState.setStreaming(true)
        StreamingUiState.setConnectionStatus("连接服务器中")

        serviceScope.launch {
            try {
                val serverUrl = settingsDataStore?.serverUrl?.first() ?: return@launch

                connectWebSockets(serverUrl)
                startCamera()
                startAudioCapture()
                startAudioPlayback(serverUrl)

                Log.i(TAG, "Streaming started: $serverUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming", e)
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return
        StreamingUiState.setStreaming(false)

        serviceScope.launch {
            try {
                stopAudioCapture()
                stopAudioPlayback()
                stopCamera()
                disconnectWebSockets()

                Log.i(TAG, "Streaming stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping streaming", e)
            }
        }
    }

    private fun connectWebSockets(baseUrl: String) {
        val cameraUri = URI("$baseUrl/ws/camera")
        val audioUri = URI("$baseUrl/ws_audio")
        val uiUri = URI("$baseUrl/ws_ui")

        cameraWebSocket = object : WebSocketClient(cameraUri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i(TAG, "Camera WebSocket connected")
                StreamingUiState.setConnectionStatus("相机已连接")
            }

            override fun onMessage(message: String?) {}

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.i(TAG, "Camera WebSocket closed: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Camera WebSocket error", ex)
                StreamingUiState.setConnectionStatus("相机连接失败")
            }
        }.apply { connect() }

        audioWebSocket = object : WebSocketClient(audioUri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i(TAG, "Audio WebSocket connected")
                Log.i(TAG, "[PERF_ASR] audio_ws_connected")
                send("START")
                Log.i(TAG, "[PERF_ASR] sent START to /ws_audio")
                StreamingUiState.setConnectionStatus("语音识别启动中")
            }

            override fun onMessage(message: String?) {
                if (!message.isNullOrBlank()) {
                    Log.i(TAG, "Audio WebSocket message: $message")
                    if (message.startsWith("OK:STARTED")) {
                        StreamingUiState.setConnectionStatus("推流中，正在听")
                    } else if (message.startsWith("ERR:")) {
                        StreamingUiState.setConnectionStatus("语音启动失败")
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.i(TAG, "Audio WebSocket closed: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Audio WebSocket error", ex)
                StreamingUiState.setConnectionStatus("语音连接失败")
            }
        }.apply { connect() }

        uiWebSocket = object : WebSocketClient(uiUri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i(TAG, "UI WebSocket connected")
            }

            override fun onMessage(message: String?) {
                handleUiMessage(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.i(TAG, "UI WebSocket closed: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "UI WebSocket error", ex)
            }
        }.apply { connect() }
    }

    private fun disconnectWebSockets() {
        try {
            if (audioWebSocket?.isOpen == true) {
                audioWebSocket?.send("STOP")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send audio STOP", e)
        }
        cameraWebSocket?.close()
        cameraWebSocket = null
        audioWebSocket?.close()
        audioWebSocket = null
        uiWebSocket?.close()
        uiWebSocket = null
    }

    private fun handleUiMessage(message: String?) {
        try {
            val raw = message?.trim().orEmpty()
            if (raw.isEmpty()) return

            when {
                raw.startsWith("{") -> handleUiJson(raw)
                raw.startsWith("PARTIAL:") -> StreamingUiState.setPartialText(raw.removePrefix("PARTIAL:"))
                raw.startsWith("FINAL:") -> {
                    val text = raw.removePrefix("FINAL:")
                    StreamingUiState.addFinalMessage(text)
                    StreamingUiState.setPartialText("等待语音输入")
                }
                raw.startsWith("STATUS:") -> StreamingUiState.setConnectionStatus(raw.removePrefix("STATUS:"))
                raw.startsWith("STATE:") -> parseInitialState(raw.removePrefix("STATE:"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle UI message", e)
        }
    }

    private fun handleUiJson(json: String) {
        val type = Regex("\"type\"\\s*:\\s*\"(.*?)\"").find(json)?.groupValues?.getOrNull(1)
        when (type) {
            "ai_reply" -> {
                val text = Regex("\"text\"\\s*:\\s*\"(.*?)\"").find(json)?.groupValues?.getOrNull(1)
                if (!text.isNullOrBlank()) {
                    val reply = unescapeJsonText(text)
                    StreamingUiState.addFinalMessage("[AI] $reply")
                    StreamingUiState.setPartialText("等待语音输入")
                    val shouldUseLocalTts = Regex("\"tts_fallback\"\\s*:\\s*true").containsMatchIn(json)
                    if (shouldUseLocalTts) {
                        speakText(reply)
                    }
                }
            }
            "status" -> {
                val stage = Regex("\"stage\"\\s*:\\s*\"(.*?)\"").find(json)?.groupValues?.getOrNull(1)
                if (!stage.isNullOrBlank()) {
                    StreamingUiState.setConnectionStatus(
                        when (stage) {
                            "listening" -> "正在听"
                            "thinking" -> "AI 思考中"
                            "speaking" -> {
                                micResumeJob?.cancel()
                                Log.i(TAG, "[PERF_ASR] mic_suppressed=true reason=server_speaking")
                                suppressMicUpload.set(true)
                                "AI 回复中"
                            }
                            "idle" -> {
                                scheduleMicResume()
                                "推流中"
                            }
                            else -> stage
                        }
                    )
                }
            }
            "multimodal_alert" -> handleMultimodalAlert(json)
        }
    }

    private fun handleMultimodalAlert(json: String) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastAlertLogMs >= 1000L) {
                Log.i(TAG, "[MULTIMODAL_ALERT] received raw length=${json.length}")
                lastAlertLogMs = now
            }
            val payload = JSONObject(json)
            val level = payload.optString("level", "medium")
            val text = payload.optString("text", "")
            val shouldVibrate = payload.optBoolean("vibrate", true)
            val shouldSpeak = payload.optBoolean("speak", false)

            Log.i(TAG, "[MULTIMODAL_ALERT] parsed level=$level vibrate=$shouldVibrate speak=$shouldSpeak text=$text")
            if (text.isNotBlank() && now - lastAlertUiMs >= 1000L) {
                StreamingUiState.addFinalMessage("[预警] $text")
                StreamingUiState.setConnectionStatus(text)
                lastAlertUiMs = now
            }
            if (shouldVibrate) {
                Log.i(TAG, "[MULTIMODAL_ALERT] calling vibrateAlert")
                vibrateAlert(level)
            }
            if (shouldSpeak && text.isNotBlank()) {
                speakText(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle multimodal alert: $json", e)
        }
    }

    private fun vibrateAlert(level: String) {
        val normalizedLevel = level.lowercase(Locale.ROOT)
        val pattern = when (normalizedLevel) {
            "low" -> longArrayOf(0, 120)
            "medium" -> longArrayOf(0, 160, 120, 160)
            "high" -> longArrayOf(0, 250, 120, 250, 120, 250)
            "critical" -> longArrayOf(0, 500, 120, 250, 120, 250)
            else -> longArrayOf(0, 160, 120, 160)
        }
        val amplitudes = when (normalizedLevel) {
            "low" -> intArrayOf(0, 120)
            "medium" -> intArrayOf(0, 180, 0, 180)
            "high" -> intArrayOf(0, 255, 0, 255, 0, 255)
            "critical" -> intArrayOf(0, 255, 0, 255, 0, 255)
            else -> intArrayOf(0, 180, 0, 180)
        }

        try {
            Log.i(TAG, "[MULTIMODAL_ALERT] vibrate level=$normalizedLevel pattern=${pattern.contentToString()}")

            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) {
                Log.w(TAG, "[MULTIMODAL_ALERT] device has no vibrator")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MULTIMODAL_ALERT] failed to vibrate alert", e)
        }
    }

    private fun unescapeJsonText(value: String): String {
        return value
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun speakText(text: String) {
        if (text.isBlank()) return
        micResumeJob?.cancel()
        Log.i(TAG, "[PERF_ASR] mic_suppressed=true reason=local_tts")
        suppressMicUpload.set(true)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "visus_ai_reply")
        scheduleMicResume(3000L)
    }

    private fun scheduleMicResume(delayMs: Long = MIC_RESUME_DELAY_MS) {
        micResumeJob?.cancel()
        micResumeJob = serviceScope.launch {
            delay(delayMs)
            suppressMicUpload.set(false)
            Log.i(TAG, "[PERF_ASR] mic_suppressed=false")
        }
    }

    private fun parseInitialState(json: String) {
        Regex("\"partial\"\\s*:\\s*\"(.*?)\"").find(json)?.groupValues?.getOrNull(1)?.let {
            StreamingUiState.setPartialText(it)
        }
        Regex("\"finals\"\\s*:\\s*\\[(.*)]").find(json)?.groupValues?.getOrNull(1)?.let { body ->
            Regex("\"(.*?)\"").findAll(body).forEach { match ->
                StreamingUiState.addFinalMessage(match.groupValues[1])
            }
        }
    }

    private fun startCamera() {
        cameraHandlerThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraHandlerThread!!.looper)

        imageReader = ImageReader.newInstance(
            CAMERA_WIDTH, CAMERA_HEIGHT, ImageFormat.JPEG, 2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    updatePreviewFrame(bytes)
                    val now = System.currentTimeMillis()
                    if (now - lastCameraSendMs >= CAMERA_SEND_INTERVAL_MS) {
                        lastCameraSendMs = now
                        sendCameraFrame(bytes)
                    }
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.find { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList[0]

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access error", e)
        }
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return

        try {
            val surfaces = listOf<Surface>(reader.surface)
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                    }.build()
                    session.setRepeatingRequest(captureRequest, null, cameraHandler)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera capture session configuration failed")
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }

    private fun stopCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        cameraHandlerThread?.quitSafely()
        cameraHandlerThread = null
        cameraHandler = null
    }

    private fun sendCameraFrame(jpegData: ByteArray) {
        if (!isStreaming.get()) return
        try {
            if (cameraWebSocket?.isOpen == true) {
                cameraWebSocket?.send(jpegData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send camera frame", e)
        }
    }

    private fun updatePreviewFrame(jpegData: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
            if (bitmap != null) {
                StreamingUiState.setLatestFrame(rotateBitmap(bitmap, CAMERA_PREVIEW_ROTATE_DEG))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode preview frame", e)
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun startAudioCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_CONFIG,
            AUDIO_FORMAT,
            maxOf(minBufferSize, AUDIO_BUFFER_SIZE)
        )

        audioRecord?.startRecording()
        Log.i(TAG, "[PERF_ASR] mic recording started sampleRate=$AUDIO_SAMPLE_RATE bufferSize=${maxOf(minBufferSize, AUDIO_BUFFER_SIZE)}")

        audioJob = serviceScope.launch {
            val buffer = ByteArray(AUDIO_BUFFER_SIZE)
            while (isActive && isStreaming.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val suppressed = suppressMicUpload.get()
                    if (!suppressed) {
                        sendAudioData(buffer.copyOf(read))
                        audioBytesSentThisSecond += read.toLong()
                    }
                    val now = System.currentTimeMillis()
                    if (now - audioStatsLastLogMs >= 1000L) {
                        Log.i(TAG, "[PERF_ASR] audio_bytes_per_sec=$audioBytesSentThisSecond suppressed=$suppressed")
                        audioBytesSentThisSecond = 0L
                        audioStatsLastLogMs = now
                    }
                }
            }
        }
    }

    private fun stopAudioCapture() {
        micResumeJob?.cancel()
        micResumeJob = null
        Log.i(TAG, "[PERF_ASR] mic_suppressed=false reason=stop_audio_capture")
        suppressMicUpload.set(false)
        audioJob?.cancel()
        audioJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun startAudioPlayback(baseUrl: String) {
        val streamUrl = baseUrl
            .replaceFirst("ws://", "http://")
            .replaceFirst("wss://", "https://") + "/stream.wav"

        val minBufferSize = AudioTrack.getMinBufferSize(
            8000,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val playbackBufferBytes = maxOf(minBufferSize, 8000 * 2 * PLAYBACK_BUFFER_MS / 1000)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(8000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(playbackBufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        playbackJob = serviceScope.launch {
            val buffer = ByteArray(1600)
            while (isActive && isStreaming.get()) {
                try {
                    URL(streamUrl).openStream().use { input ->
                        val header = ByteArray(44)
                        var skipped = 0
                        while (skipped < header.size) {
                            val read = input.read(header, skipped, header.size - skipped)
                            if (read < 0) break
                            skipped += read
                        }

                        while (isActive && isStreaming.get()) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            audioTrack?.write(buffer, 0, read)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Audio playback stream disconnected", e)
                    delay(1000)
                }
            }
        }
    }

    private fun stopAudioPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        audioTrack?.release()
        audioTrack = null
    }

    private fun sendAudioData(pcmData: ByteArray) {
        if (!isStreaming.get()) return
        try {
            if (audioWebSocket?.isOpen == true) {
                audioWebSocket?.send(pcmData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio data", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Visus 智能导航推流",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持摄像头和音频推流服务运行"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Visus 智能导航")
            .setContentText("正在向服务器推送视频和音频...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
