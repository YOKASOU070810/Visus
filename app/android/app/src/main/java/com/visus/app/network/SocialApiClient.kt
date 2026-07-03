package com.visus.app.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// HTTP API client for social features (auth, friends, status, emergency).
// Communicates with the Visus FastAPI server's /api/ endpoints.
object SocialApiClient {
    private const val TAG = "SocialApiClient"
    private var baseUrl: String = "http://10.0.2.2:8081"
    private var authToken: String? = null
    var amapKey: String = ""
    var arkKey: String = "ark-1b4752cf-a621-4c2b-b4b1-7de39d2108ec-aa48e"

    fun setServer(url: String) { baseUrl = url.trimEnd('/') }
    fun getServer(): String = baseUrl
    fun setToken(token: String?) { authToken = token }
    fun getToken(): String? = authToken
    fun isLoggedIn(): Boolean = authToken != null

    // ── Data classes ──
    data class UserInfo(
        val id: Int, val username: String, val email: String,
        val firstName: String, val lastName: String,
        val userType: String = "blind"
    )
    data class FriendInfo(
        val user: UserInfo, val status: Boolean?, val alertType: String?,
        val latitude: Double?, val longitude: Double?,
        val city: String?, val note: String?, val lastUpdated: String?
    )
    data class FriendRequestInfo(val id: Int, val sender: UserInfo?, val createdAt: String?)
    data class SearchUserInfo(
        val user: UserInfo, val isFriend: Boolean, val requestPending: Boolean
    )
    data class EmergencyEvent(
        val id: Int, val userId: Int, val eventType: String,
        val severity: String, val latitude: Double, val longitude: Double,
        val city: String?, val description: String?,
        val isResolved: Boolean, val createdAt: String?
    )
    data class ApiResult<T>(val success: Boolean, val data: T?, val error: String?)

    // ── HTTP helpers ──
    suspend fun post(path: String, body: JSONObject, token: String? = authToken): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (amapKey.isNotBlank()) conn.setRequestProperty("X-Visus-AMAP-Key", amapKey)
        if (arkKey.isNotBlank()) conn.setRequestProperty("X-Visus-ARK-Key", arkKey)

        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: "{}"
        conn.disconnect()

        if (code == 401) { authToken = null }
        if (code !in 200..299) {
            val err = try { JSONObject(text).optJSONObject("detail")?.optString("error", "HTTP $code") } catch (e: Exception) { "HTTP $code" }
            throw ApiException(code, err ?: "Unknown error")
        }
        JSONObject(text)
    }

    suspend fun get(path: String, token: String? = authToken): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (amapKey.isNotBlank()) conn.setRequestProperty("X-Visus-AMAP-Key", amapKey)
        if (arkKey.isNotBlank()) conn.setRequestProperty("X-Visus-ARK-Key", arkKey)

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: "{}"
        conn.disconnect()

        if (code == 401) { authToken = null }
        if (code !in 200..299) throw ApiException(code, "HTTP $code")
        JSONObject(text)
    }

    private fun parseUser(obj: JSONObject) = UserInfo(
        id = obj.getInt("id"), username = obj.optString("username"),
        email = obj.optString("email"), firstName = obj.optString("first_name", ""),
        lastName = obj.optString("last_name", ""),
        userType = obj.optString("user_type", "blind")
    )

    private fun parseFriend(obj: JSONObject): FriendInfo {
        val userObj = obj.getJSONObject("user")
        return FriendInfo(
            user = parseUser(userObj),
            status = if (obj.isNull("status")) null else obj.optBoolean("status"),
            alertType = obj.optString("alert_type", null),
            latitude = if (obj.isNull("latitude")) null else obj.optDouble("latitude"),
            longitude = if (obj.isNull("longitude")) null else obj.optDouble("longitude"),
            city = obj.optString("city", null),
            note = obj.optString("note", null),
            lastUpdated = obj.optString("last_updated", null)
        )
    }

    // ── Auth ──
    suspend fun login(email: String, password: String): Pair<String, UserInfo> {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        val resp = post("/api/login/", body)
        val data = resp.getJSONObject("data")
        val token = data.getString("token")
        val user = parseUser(data.getJSONObject("user"))
        authToken = token
        return Pair(token, user)
    }

    suspend fun signup(email: String, password: String, firstName: String, lastName: String, userType: String = "blind"): Pair<String, UserInfo> {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
            put("first_name", firstName)
            put("last_name", lastName)
            put("user_type", userType)
        }
        val resp = post("/api/signup/", body)
        val data = resp.getJSONObject("data")
        val token = data.getString("token")
        val user = parseUser(data.getJSONObject("user"))
        authToken = token
        return Pair(token, user)
    }

    // ── Status ──
    suspend fun updateStatus(isSafe: Boolean, lat: Double = 0.0, lng: Double = 0.0, city: String = "", alertType: String = "manual", note: String = ""): JSONObject {
        val body = JSONObject().apply {
            put("status", isSafe)
            put("latitude", lat)
            put("longitude", lng)
            put("city", city)
            put("alert_type", alertType)
            put("note", note)
        }
        return post("/api/status/update/", body)
    }

    suspend fun getStatus(): JSONObject = get("/api/status/")

    // ── Friends ──
    suspend fun getFriends(): List<FriendInfo> {
        val resp = get("/api/friends/")
        val arr = resp.getJSONObject("data").getJSONArray("friends")
        return (0 until arr.length()).map { parseFriend(arr.getJSONObject(it)) }
    }

    suspend fun addFriend(userId: Int): JSONObject {
        return post("/api/friends/add/", JSONObject().put("user_id", userId))
    }

    suspend fun removeFriend(userId: Int): JSONObject {
        return post("/api/friends/remove/", JSONObject().put("user_id", userId))
    }

    // ── Friend Requests ──
    suspend fun getRequests(): List<FriendRequestInfo> {
        val resp = get("/api/friends/requests/")
        val arr = resp.getJSONObject("data").getJSONArray("requests")
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            val sender = if (obj.isNull("sender")) null else parseUser(obj.getJSONObject("sender"))
            FriendRequestInfo(
                id = obj.getInt("id"),
                sender = sender,
                createdAt = obj.optString("created_at", null)
            )
        }
    }

    suspend fun respondRequest(requestId: Int, approve: Boolean): JSONObject {
        val action = if (approve) "approve" else "decline"
        return post("/api/friends/requests/$requestId/$action/", JSONObject())
    }

    // ── Search ──
    suspend fun searchUsers(query: String): List<SearchUserInfo> {
        val resp = post("/api/search/", JSONObject().put("query", query))
        val arr = resp.getJSONObject("data").getJSONArray("users")
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            SearchUserInfo(
                user = parseUser(obj.getJSONObject("user")),
                isFriend = obj.optBoolean("is_friend"),
                requestPending = obj.optBoolean("request_pending")
            )
        }
    }

    // ── Emergency ──
    suspend fun triggerEmergency(
        eventType: String = "manual_sos",
        severity: String = "high",
        lat: Double = 0.0, lng: Double = 0.0,
        city: String = "", description: String = ""
    ): JSONObject {
        val body = JSONObject().apply {
            put("event_type", eventType)
            put("severity", severity)
            put("latitude", lat)
            put("longitude", lng)
            put("city", city)
            put("description", description)
        }
        return post("/api/emergency/trigger/", body)
    }

    suspend fun getEmergencyHistory(): List<EmergencyEvent> {
        val resp = get("/api/emergency/history/")
        val arr = resp.getJSONObject("data").getJSONArray("events")
        return (0 until arr.length()).map {
            val obj = arr.getJSONObject(it)
            EmergencyEvent(
                id = obj.getInt("id"), userId = obj.getInt("user_id"),
                eventType = obj.optString("event_type"),
                severity = obj.optString("severity"),
                latitude = obj.optDouble("latitude"),
                longitude = obj.optDouble("longitude"),
                city = obj.optString("city", null),
                description = obj.optString("description", null),
                isResolved = obj.optBoolean("is_resolved"),
                createdAt = obj.optString("created_at", null)
            )
        }
    }

    suspend fun resolveEmergency(eventId: Int): JSONObject {
        return post("/api/emergency/$eventId/resolve/", JSONObject())
    }

    fun logout() { authToken = null }
}

class ApiException(val code: Int, message: String) : Exception(message)
