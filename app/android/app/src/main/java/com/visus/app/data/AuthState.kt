package com.visus.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages authentication state: JWT token, current user info, login status.
 * Persists token in SharedPreferences for session survival.
 */
object AuthState {
    private const val PREFS_NAME = "visus_auth_prefs"
    private const val KEY_TOKEN = "jwt_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_FIRST_NAME = "user_first_name"
    private const val KEY_USER_LAST_NAME = "user_last_name"

    private var prefs: SharedPreferences? = null
    private var userEmail: String = ""

    // Observable state
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _currentUserId = MutableStateFlow(-1)
    val currentUserId: StateFlow<Int> = _currentUserId.asStateFlow()

    private val _currentUserName = MutableStateFlow("")
    val currentUserName: StateFlow<String> = _currentUserName.asStateFlow()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedToken = prefs?.getString(KEY_TOKEN, null)
            if (savedToken != null) {
                _token.value = savedToken
                _currentUserId.value = prefs?.getInt(KEY_USER_ID, -1) ?: -1
                userEmail = prefs?.getString(KEY_USER_EMAIL, "") ?: ""
                val firstName = prefs?.getString(KEY_USER_FIRST_NAME, "") ?: ""
                val lastName = prefs?.getString(KEY_USER_LAST_NAME, "") ?: ""
                _currentUserName.value = "$firstName $lastName".trim()
                _isLoggedIn.value = true
            }
        }
    }

    fun saveLogin(token: String, userId: Int, email: String, firstName: String, lastName: String) {
        _token.value = token
        _currentUserId.value = userId
        userEmail = email
        _currentUserName.value = "$firstName $lastName".trim()
        _isLoggedIn.value = true

        prefs?.edit()?.apply {
            putString(KEY_TOKEN, token)
            putInt(KEY_USER_ID, userId)
            putString(KEY_USER_EMAIL, email)
            putString(KEY_USER_FIRST_NAME, firstName)
            putString(KEY_USER_LAST_NAME, lastName)
            apply()
        }
    }

    fun logout() {
        _token.value = null
        _currentUserId.value = -1
        userEmail = ""
        _currentUserName.value = ""
        _isLoggedIn.value = false

        prefs?.edit()?.clear()?.apply()
    }

    fun getCurrentEmail(): String = userEmail
}
