package com.visus.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.visus.app.MainActivity
import com.visus.app.R
import com.visus.app.data.SettingsDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class StreamingService : Service() {

    companion object {
        private const val TAG = "VisusStreaming"
        private const val NOTIFICATION_CHANNEL_ID = "visus_streaming_channel"
        private const val NOTIFICATION_ID = 1
        private const val CAMERA_WIDTH = 640
        private const val CAMERA_HEIGHT = 480
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

    private var audioRecord: AudioRecord? = null
    private var audioJob: Job? = null

    private var cameraWebSocket: WebSocketClient? = null
    private var audioWebSocket: WebSocketClient? = null
    private var settingsDataStore: SettingsDataStore? = null

    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onCreate() {
        super.onCreate()
        settingsDataStore = SettingsDataStore(this)
        createNotificationChannel()
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
        serviceScope.cancel()
    }

    fun isStreaming(): Boolean = isStreaming.get()

    private fun startStreaming() {
        if (isStreaming.getAndSet(true)) return

        serviceScope.launch {
            try {
                val serverUrl = settingsDataStore?.serverUrl?.first() ?: return@launch

                connectWebSockets(serverUrl)
                startCamera()
                startAudioCapture()

                Log.i(TAG, "Streaming started: $serverUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming", e)
                stopStreaming()
            }
        }
    }

    private fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) return

        serviceScope.launch {
            try {
                stopAudioCapture()
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

        cameraWebSocket = object : WebSocketClient(cameraUri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i(TAG, "Camera WebSocket connected")
            }

            override fun onMessage(message: String?) {}

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.i(TAG, "Camera WebSocket closed: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Camera WebSocket error", ex)
            }
        }.apply { connect() }

        audioWebSocket = object : WebSocketClient(audioUri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i(TAG, "Audio WebSocket connected")
            }

            override fun onMessage(message: String?) {}

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.i(TAG, "Audio WebSocket closed: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Audio WebSocket error", ex)
            }
        }.apply { connect() }
    }

    private fun disconnectWebSockets() {
        cameraWebSocket?.close()
        cameraWebSocket = null
        audioWebSocket?.close()
        audioWebSocket = null
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
                    sendCameraFrame(bytes)
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
            cameraWebSocket?.send(jpegData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send camera frame", e)
        }
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

        audioJob = serviceScope.launch {
            val buffer = ByteArray(AUDIO_BUFFER_SIZE)
            while (isActive && isStreaming.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    sendAudioData(buffer.copyOf(read))
                }
            }
        }
    }

    private fun stopAudioCapture() {
        audioJob?.cancel()
        audioJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun sendAudioData(pcmData: ByteArray) {
        if (!isStreaming.get()) return
        try {
            audioWebSocket?.send(pcmData)
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
