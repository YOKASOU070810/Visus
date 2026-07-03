package com.visus.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.visus.app.MainActivity
import com.visus.app.R
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

/**
 * Foreground service that listens for SOS/emergency WebSocket events
 * and shows high-priority notifications with vibration, even when app is in background.
 * Like WeChat - notification pops up immediately with sound + vibration.
 */
class EmergencyNotificationService : Service() {

    companion object {
        private const val TAG = "EmergencyNotif"
        const val CHANNEL_SOS = "visus_sos_channel"
        const val NOTIFY_SOS_ID = 200
        private const val CHANNEL_SOS_NAME = "紧急求助"

        fun start(context: Context, serverUrl: String, token: String) {
            val intent = Intent(context, EmergencyNotificationService::class.java).apply {
                putExtra("server_url", serverUrl)
                putExtra("token", token)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmergencyNotificationService::class.java))
        }
    }

    private var wsClient: WebSocketClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverUrl = intent?.getStringExtra("server_url") ?: ""
        val token = intent?.getStringExtra("token") ?: ""

        // Start foreground with a silent notification (required for background service)
        val silentNotification = NotificationCompat.Builder(this, CHANNEL_SOS)
            .setContentTitle("Visus 紧急守护")
            .setContentText("正在监听紧急求助...")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFY_SOS_ID + 1, silentNotification)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start foreground emergency service", e)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Connect to social WebSocket to listen for emergencies
        connectWebSocket(serverUrl, token)
        return START_STICKY
    }

    private fun connectWebSocket(serverUrl: String, token: String) {
        if (serverUrl.isBlank()) return
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
        val fullUrl = "$wsUrl/ws/social?token=$token"

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    wsClient?.close()
                    wsClient = object : WebSocketClient(URI(fullUrl)) {
                        override fun onOpen(handshakedata: ServerHandshake?) {
                            println("[EmergencyNotif] Connected to WebSocket")
                        }

                        override fun onMessage(message: String?) {
                            message ?: return
                            try {
                                val obj = JSONObject(message)
                                val type = obj.optString("type", "")
                                if (type == "emergency_alert") {
                                    handleEmergencyAlert(obj)
                                }
                            } catch (_: Exception) {}
                        }

                        override fun onClose(code: Int, reason: String?, remote: Boolean) {
                            scope.launch {
                                delay(5000)
                                connectWebSocket(serverUrl, token)
                            }
                        }

                        override fun onError(ex: Exception?) {
                            scope.launch {
                                delay(5000)
                                connectWebSocket(serverUrl, token)
                            }
                        }
                    }
                    wsClient?.connect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to connect WebSocket for SOS: ${e.message}")
            }
        }
    }

    private fun handleEmergencyAlert(obj: JSONObject) {
        val userName = obj.optJSONObject("user")?.optString("first_name", "") ?: ""
        val alert = obj.optJSONObject("alert") ?: return
        val note = alert.optString("note", "")
        val description = if (note.isNotBlank()) note else "用户触发了紧急求助"

        // Vibrate with pattern: long-short-long
        vibrator?.vibrate(
            VibrationEffect.createWaveform(
                longArrayOf(0, 500, 200, 200, 500),
                intArrayOf(0, 255, 0, 255, 0),
                -1
            )
        )

        // Show high-priority notification
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("show_emergency", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss button
        val dismissIntent = Intent(this, EmergencyNotificationService::class.java).apply {
            action = "DISMISS_SOS"
        }
        val dismissPending = PendingIntent.getService(
            this, 1, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_SOS)
            .setContentTitle("🆘 $userName 发来紧急求助！")
            .setContentText(description)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$userName 触发了紧急求助。\n$description\n\n请立即查看并联系该用户。"))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setOngoing(true)  // persist until user taps
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_dialog_alert, "我已知道", dismissPending)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 500, 200, 200, 500))
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_SOS_ID, notification)

        // Also play ringtone
        try {
            val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val r = RingtoneManager.getRingtone(this, ringtone)
            r.play()
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wsClient?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_SOS, CHANNEL_SOS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "紧急求助通知"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 200, 500)
                lightColor = Color.RED
                enableLights(true)
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
