package com.visus.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.AuthState
import com.visus.app.data.SocialState
import com.visus.app.data.StreamingUiState
import com.visus.app.data.SettingsDataStore
import com.visus.app.network.SocialApiClient
import com.visus.app.network.SocialWebSocketClient
import com.visus.app.network.StatusUpdateEvent
import com.visus.app.network.EmergencyAlertEvent
import com.visus.app.network.FriendRequestEvent
import kotlinx.coroutines.launch

// ── Bottom Navigation Tabs ──
enum class HomeTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    NAVIGATION("导航", Icons.Default.Navigation, Icons.Default.Navigation),
    FRIENDS("好友", Icons.Default.People, Icons.Default.People),
    ALERTS("提醒", Icons.Default.NotificationsActive, Icons.Default.NotificationsActive),
    PROFILE("我的", Icons.Default.AccountCircle, Icons.Default.AccountCircle)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsDataStore = remember { SettingsDataStore(context) }

    var selectedTab by remember { mutableStateOf(HomeTab.NAVIGATION) }
    var showRequestsDialog by remember { mutableStateOf(false) }

    // Streaming state
    val streaming by StreamingUiState.isStreaming.collectAsState()
    val connectionStatus by StreamingUiState.connectionStatus.collectAsState()
    val partialText by StreamingUiState.partialText.collectAsState()
    val finalMessages by StreamingUiState.finalMessages.collectAsState()
    val latestFrame by StreamingUiState.latestFrame.collectAsState()
    val serverIp by settingsDataStore.serverIp.collectAsState(initial = SettingsDataStore.DEFAULT_IP)
    val serverPort by settingsDataStore.serverPort.collectAsState(initial = SettingsDataStore.DEFAULT_PORT)

    // Social WebSocket
    val wsClient = remember { SocialWebSocketClient() }

    // Connect WebSocket when auth is ready
    LaunchedEffect(AuthState.token.value) {
        val token = AuthState.token.value
        if (token != null) {
            wsClient.connect(serverIp, serverPort, token)
        }
    }

    // Listen for WebSocket events
    LaunchedEffect(Unit) {
        launch {
            wsClient.statusUpdates.collect { event ->
                SocialState.updateFriendStatus(
                    event.userId, event.status, event.alertType,
                    event.note, event.city, event.lastUpdated
                )
            }
        }
        launch {
            wsClient.emergencyAlerts.collect { event ->
                SocialState.addEmergencyEvent(event)
            }
        }
        launch {
            wsClient.friendRequests.collect { _ ->
                SocialState.loadRequests()
            }
        }
    }

    // Update server URL for social API
    LaunchedEffect(serverIp, serverPort) {
        SocialApiClient.setServer("http://$serverIp:$serverPort")
        // also set the token if we have it
        AuthState.token.value?.let { SocialApiClient.setToken(it) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = {
                            selectedTab = tab
                            // Load data when switching to tabs
                            when (tab) {
                                HomeTab.FRIENDS -> SocialState.loadFriends()
                                HomeTab.ALERTS -> SocialState.loadFriends() // to check emergency status
                                else -> {}
                            }
                        },
                        icon = {
                            BadgedBox(badge = {
                                if (tab == HomeTab.ALERTS) {
                                    val alertCount = SocialState.recentEmergencies.collectAsState().value.size
                                    if (alertCount > 0) {
                                        Badge { Text("$alertCount") }
                                    }
                                }
                            }) {
                                Icon(tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                HomeTab.NAVIGATION -> {
                    // AI Navigation tab (original Visus functionality)
                    NavigationTab(
                        isStreaming = streaming,
                        connectionStatus = connectionStatus,
                        serverIp = serverIp,
                        serverPort = serverPort,
                        partialText = partialText,
                        finalMessages = finalMessages,
                        latestFrame = latestFrame,
                        onStartStreaming = onStartStreaming,
                        onStopStreaming = onStopStreaming,
                        serverIpValue = serverIp,
                        serverPortValue = serverPort,
                        onSaveServer = { ip, port ->
                            scope.launch {
                                settingsDataStore.saveServerIp(ip)
                                settingsDataStore.saveServerPort(port)
                            }
                        }
                    )
                }
                HomeTab.FRIENDS -> FriendsScreen(
                    onNavigateToRequests = { showRequestsDialog = true }
                )
                HomeTab.ALERTS -> AlertsScreen()
                HomeTab.PROFILE -> ProfileScreen(onLogout = onLogout)
            }
        }
    }

    // Friend Requests Dialog
    if (showRequestsDialog) {
        val requests by SocialState.requests.collectAsState()
        AlertDialog(
            onDismissRequest = { showRequestsDialog = false },
            title = { Text("好友请求") },
            text = {
                if (requests.isEmpty()) {
                    Text("没有待处理的好友请求")
                } else {
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(requests) { request ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(request.sender?.let { "${it.firstName} ${it.lastName}" } ?: "Unknown", fontWeight = FontWeight.Medium)
                                        Text(request.sender?.email ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row {
                                        TextButton(onClick = { SocialState.respondRequest(request.id, true) }) {
                                            Text("批准", color = MaterialTheme.colorScheme.primary)
                                        }
                                        TextButton(onClick = { SocialState.respondRequest(request.id, false) }) {
                                            Text("拒绝", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRequestsDialog = false }) { Text("关闭") } }
        )
    }
}

// ── Navigation Tab (original Visus streaming functionality) ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationTab(
    isStreaming: Boolean,
    connectionStatus: String,
    serverIp: String,
    serverPort: String,
    partialText: String,
    finalMessages: List<String>,
    latestFrame: Bitmap?,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    serverIpValue: String,
    serverPortValue: String,
    onSaveServer: (String, String) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf(serverIpValue) }
    var portInput by remember { mutableStateOf(serverPortValue) }

    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(10.dp).clip(CircleShape)
                    .background(if (isStreaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            )
            Spacer(Modifier.width(8.dp))
            Text(if (isStreaming) connectionStatus else "已停止", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("$serverIp:$serverPort", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, "设置")
            }
        }

        // Video preview
        Box(
            modifier = Modifier.fillMaxWidth().weight(0.46f).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (latestFrame != null) {
                Image(
                    bitmap = latestFrame.asImageBitmap(),
                    contentDescription = "相机预览",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    if (isStreaming) "正在打开相机" else "未开始推流",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp
                )
            }
        }

        // Speech card
        Card(
            modifier = Modifier.fillMaxWidth().weight(0.54f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Text("语音文字", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(partialText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (finalMessages.isEmpty()) {
                        item { Text("还没有识别结果", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                    } else {
                        items(finalMessages.asReversed()) { msg ->
                            Text(msg, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        // Control button
        Button(
            onClick = { if (isStreaming) onStopStreaming() else onStartStreaming() },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                if (isStreaming) Icons.Default.VideocamOff else Icons.Default.Videocam,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(if (isStreaming) "停止推流" else "开始推流", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(2.dp))
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("服务器设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(value = ipInput, onValueChange = { ipInput = it }, label = { Text("IP") }, singleLine = true)
                    OutlinedTextField(value = portInput, onValueChange = { portInput = it }, label = { Text("端口") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { onSaveServer(ipInput.trim(), portInput.trim()); showSettings = false }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } }
        )
    }
}
