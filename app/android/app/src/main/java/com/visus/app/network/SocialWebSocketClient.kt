package com.visus.app.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

/**
 * WebSocket client for real-time social updates.
 *
 * Connects to /ws/social on the Visus server with JWT auth.
 * Receives: status updates, friend request notifications, emergency alerts.
 */
class SocialWebSocketClient {
    companion object {
        private const val TAG = "SocialWS"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val KEEPALIVE_INTERVAL_MS = 25_000L
    }

    private var wsClient: WebSocketClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var keepaliveJob: Job? = null

    // Connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    // Event streams for different message types
    private val _statusUpdates = MutableSharedFlow<StatusUpdateEvent>(replay = 0, extraBufferCapacity = 64)
    val statusUpdates: SharedFlow<StatusUpdateEvent> = _statusUpdates

    private val _emergencyAlerts = MutableSharedFlow<EmergencyAlertEvent>(replay = 0, extraBufferCapacity = 16)
    val emergencyAlerts: SharedFlow<EmergencyAlertEvent> = _emergencyAlerts

    private val _friendRequests = MutableSharedFlow<FriendRequestEvent>(replay = 0, extraBufferCapacity = 16)
    val friendRequests: SharedFlow<FriendRequestEvent> = _friendRequests

    private var serverUrl: String = ""
    private var token: String = ""

    fun connect(serverIp: String, serverPort: String, jwtToken: String) {
        serverUrl = "ws://$serverIp:$serverPort/ws/social?token=$jwtToken"
        token = jwtToken
        scope.launch { doConnect() }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        keepaliveJob?.cancel()
        scope.launch {
            wsClient?.close()
            wsClient = null
            _isConnected.value = false
        }
    }

    private suspend fun doConnect() = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext

        wsClient?.close()
        wsClient = object : WebSocketClient(URI(serverUrl)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.i(TAG, "Connected to social WebSocket")
                _isConnected.value = true
                startKeepalive()
            }

            override fun onMessage(message: String?) {
                message ?: return
                try {
                    val obj = org.json.JSONObject(message)
                    when (obj.optString("type")) {
                        "status_update" -> handleStatusUpdate(obj)
                        "emergency_alert" -> handleEmergencyAlert(obj)
                        "friend_request" -> handleFriendRequest(obj)
                        "pong", "keepalive" -> { /* ignore */ }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse message: $message", e)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.i(TAG, "Disconnected: $code $reason")
                _isConnected.value = false
                scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
                _isConnected.value = false
            }
        }
        wsClient?.connect()
    }

    private fun handleStatusUpdate(obj: org.json.JSONObject) {
        val alert = obj.optJSONObject("alert") ?: return
        val event = StatusUpdateEvent(
            userId = obj.optInt("user_id"),
            alertId = alert.optInt("id"),
            status = alert.optBoolean("status"),
            alertType = alert.optString("alert_type", "manual"),
            latitude = alert.optDouble("latitude", 0.0),
            longitude = alert.optDouble("longitude", 0.0),
            city = alert.optString("city", null),
            note = alert.optString("note", null),
            lastUpdated = alert.optString("last_updated", null)
        )
        _statusUpdates.tryEmit(event)
    }

    private fun handleEmergencyAlert(obj: org.json.JSONObject) {
        val user = obj.optJSONObject("user") ?: return
        val alert = obj.optJSONObject("alert") ?: return
        val event = obj.optJSONObject("event") ?: return
        val emergencyEvent = EmergencyAlertEvent(
            userId = obj.optInt("user_id"),
            userName = user.optString("first_name", "") + " " + user.optString("last_name", ""),
            userEmail = user.optString("email", ""),
            eventType = event.optString("event_type", "unknown"),
            severity = event.optString("severity", "high"),
            description = alert.optString("note", null) ?: event.optString("description", null),
            latitude = alert.optDouble("latitude", 0.0),
            longitude = alert.optDouble("longitude", 0.0),
            city = alert.optString("city", null),
            createdAt = event.optString("created_at", null)
        )
        _emergencyAlerts.tryEmit(emergencyEvent)
    }

    private fun handleFriendRequest(obj: org.json.JSONObject) {
        val request = obj.optJSONObject("request") ?: return
        val sender = request.optJSONObject("sender")
        val event = FriendRequestEvent(
            requestId = request.optInt("id"),
            senderName = sender?.optString("first_name", "") + " " + sender?.optString("last_name", ""),
            senderEmail = sender?.optString("email", "") ?: ""
        )
        _friendRequests.tryEmit(event)
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                try {
                    wsClient?.send("""{"type":"ping"}""")
                } catch (_: Exception) {}
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (isActive && token.isNotBlank()) {
                Log.i(TAG, "Reconnecting...")
                doConnect()
            }
        }
    }
}

// ── Event data classes ──
data class StatusUpdateEvent(
    val userId: Int, val alertId: Int,
    val status: Boolean, val alertType: String,
    val latitude: Double, val longitude: Double,
    val city: String?, val note: String?, val lastUpdated: String?
)

data class EmergencyAlertEvent(
    val userId: Int, val userName: String, val userEmail: String,
    val eventType: String, val severity: String,
    val description: String?,
    val latitude: Double, val longitude: Double,
    val city: String?, val createdAt: String?
)

data class FriendRequestEvent(
    val requestId: Int, val senderName: String, val senderEmail: String
)
