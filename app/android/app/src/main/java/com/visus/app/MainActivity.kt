package com.visus.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.visus.app.data.AuthState
import com.visus.app.network.SocialApiClient
import com.visus.app.service.StreamingService
import com.visus.app.ui.screens.HomeScreen
import com.visus.app.ui.screens.LoginScreen
import com.visus.app.ui.theme.VisusTheme

class MainActivity : ComponentActivity() {

    private var streamingService: StreamingService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as StreamingService.LocalBinder
            streamingService = binder.getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            serviceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startStreamingService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize auth state (restore saved session)
        AuthState.init(applicationContext)

        setContent {
            VisusTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var isLoggedIn by remember {
                        mutableStateOf(AuthState.isLoggedIn.value)
                    }

                    // Observe auth state changes
                    val authState by AuthState.isLoggedIn.collectAsState()
                    LaunchedEffect(authState) {
                        isLoggedIn = authState
                        if (authState) {
                            AuthState.token.value?.let { SocialApiClient.setToken(it) }
                        }
                    }

                    if (isLoggedIn) {
                        HomeScreen(
                            onStartStreaming = { startStreamingService() },
                            onStopStreaming = { stopStreamingService() },
                            onLogout = {
                                stopStreamingService()
                                isLoggedIn = false
                            }
                        )
                    } else {
                        LoginScreen(
                            onLoginSuccess = {
                                isLoggedIn = true
                                // Request permissions after login
                                requestPermissions()
                            }
                        )
                    }
                }
            }
        }

        // Request permissions if already logged in
        if (AuthState.isLoggedIn.value) {
            requestPermissions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val missing = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(permissions)
        }
    }

    private fun startStreamingService() {
        val intent = Intent(this, StreamingService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopStreamingService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        val intent = Intent(this, StreamingService::class.java)
        stopService(intent)
    }
}
