package com.visus.app.data

import com.visus.app.network.SocialApiClient
import com.visus.app.network.SocialApiClient.FriendInfo
import com.visus.app.network.SocialApiClient.FriendRequestInfo
import com.visus.app.network.SocialApiClient.EmergencyEvent
import com.visus.app.network.SocialApiClient.SearchUserInfo
import com.visus.app.network.EmergencyAlertEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central state management for social features.
 * Holds friends list, requests, my status, and emergency events.
 */
object SocialState {
    private val scope = CoroutineScope(Dispatchers.IO)

    // My status
    private val _myStatus = MutableStateFlow<Boolean?>(null)
    val myStatus: StateFlow<Boolean?> = _myStatus.asStateFlow()

    // Friends list
    private val _friends = MutableStateFlow<List<FriendInfo>>(emptyList())
    val friends: StateFlow<List<FriendInfo>> = _friends.asStateFlow()

    // Pending friend requests
    private val _requests = MutableStateFlow<List<FriendRequestInfo>>(emptyList())
    val requests: StateFlow<List<FriendRequestInfo>> = _requests.asStateFlow()

    // Recent emergency events (from WebSocket)
    private val _recentEmergencies = MutableStateFlow<List<EmergencyAlertEvent>>(emptyList())
    val recentEmergencies: StateFlow<List<EmergencyAlertEvent>> = _recentEmergencies.asStateFlow()

    // Search results
    private val _searchResults = MutableStateFlow<List<SearchUserInfo>>(emptyList())
    val searchResults: StateFlow<List<SearchUserInfo>> = _searchResults.asStateFlow()

    // Loading flags
    private val _isLoadingFriends = MutableStateFlow(false)
    val isLoadingFriends: StateFlow<Boolean> = _isLoadingFriends.asStateFlow()
    private val _isLoadingRequests = MutableStateFlow(false)
    val isLoadingRequests: StateFlow<Boolean> = _isLoadingRequests.asStateFlow()

    // Error messages
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() { _errorMessage.value = null }

    // ── Actions ──

    fun loadFriends() {
        scope.launch {
            _isLoadingFriends.value = true
            try {
                _friends.value = SocialApiClient.getFriends()
                // Also update my status from friends response
                // (the /api/status/ endpoint gives my own status)
                try {
                    val statusResp = SocialApiClient.getStatus()
                    val myStatusData = statusResp.optJSONObject("data")?.optJSONObject("my_status")
                    _myStatus.value = myStatusData?.optBoolean("status")
                } catch (_: Exception) {}
            } catch (e: Exception) {
                _errorMessage.value = "加载好友失败: ${e.message}"
            } finally {
                _isLoadingFriends.value = false
            }
        }
    }

    fun loadRequests() {
        scope.launch {
            _isLoadingRequests.value = true
            try {
                _requests.value = SocialApiClient.getRequests()
            } catch (e: Exception) {
                _errorMessage.value = "加载请求失败: ${e.message}"
            } finally {
                _isLoadingRequests.value = false
            }
        }
    }

    fun updateMyStatus(isSafe: Boolean, note: String = "") {
        scope.launch {
            try {
                _myStatus.value = isSafe  // optimistic update
                SocialApiClient.updateStatus(isSafe, alertType = "manual", note = note)
                // Reload to get server state
                loadFriends()
            } catch (e: Exception) {
                _errorMessage.value = "状态更新失败: ${e.message}"
                // Revert
                _myStatus.value = !isSafe
            }
        }
    }

    fun addFriend(userId: Int, onSuccess: () -> Unit = {}) {
        scope.launch {
            try {
                SocialApiClient.addFriend(userId)
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = "添加好友失败: ${e.message}"
            }
        }
    }

    fun removeFriend(userId: Int, onSuccess: () -> Unit = {}) {
        scope.launch {
            try {
                SocialApiClient.removeFriend(userId)
                loadFriends()
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = "删除好友失败: ${e.message}"
            }
        }
    }

    fun respondRequest(requestId: Int, approve: Boolean) {
        scope.launch {
            try {
                SocialApiClient.respondRequest(requestId, approve)
                loadRequests()
                if (approve) loadFriends()
            } catch (e: Exception) {
                _errorMessage.value = "操作失败: ${e.message}"
            }
        }
    }

    fun searchUsers(query: String) {
        scope.launch {
            try {
                _searchResults.value = SocialApiClient.searchUsers(query)
            } catch (e: Exception) {
                _errorMessage.value = "搜索失败: ${e.message}"
            }
        }
    }

    fun triggerEmergency(type: String = "manual_sos", description: String = "", lat: Double = 0.0, lng: Double = 0.0) {
        scope.launch {
            try {
                _myStatus.value = false
                SocialApiClient.triggerEmergency(type, "high", lat, lng, description = description)
                loadFriends()
            } catch (e: Exception) {
                _errorMessage.value = "紧急通知失败: ${e.message}"
            }
        }
    }

    fun addEmergencyEvent(event: EmergencyAlertEvent) {
        _recentEmergencies.value = listOf(event) + _recentEmergencies.value.take(49)
    }

    // Unread private messages
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    fun loadUnreadCount() {
        scope.launch {
            try {
                val resp = SocialApiClient.get("/api/messages/unread")
                _unreadCount.value = resp.optJSONObject("data")?.optInt("unread", 0) ?: 0
            } catch (_: Exception) {}
        }
    }

    /**
     * Update a friend's status in the local list (called from WebSocket updates).
     */
    fun updateFriendStatus(userId: Int, status: Boolean, alertType: String, note: String?, city: String?, lastUpdated: String?) {
        _friends.value = _friends.value.map { friend ->
            if (friend.user.id == userId) {
                friend.copy(
                    status = status,
                    alertType = alertType,
                    note = note,
                    city = city,
                    lastUpdated = lastUpdated
                )
            } else friend
        }
    }
}
