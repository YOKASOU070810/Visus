package com.visus.app.ui.screens

import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.AuthState
import com.visus.app.data.SettingsDataStore
import com.visus.app.data.SocialState
import com.visus.app.data.StreamingUiState
import com.visus.app.network.SocialApiClient
import com.visus.app.network.SocialWebSocketClient
import kotlinx.coroutines.launch

enum class HomeTab(val label: String, val icon: ImageVector) {
    NAVIGATION("导航", Icons.Default.Navigation),
    FRIENDS("好友", Icons.Default.People),
    AGENT("AI", Icons.Default.AutoAwesome),
    ALERTS("提醒", Icons.Default.NotificationsActive),
    PROFILE("我的", Icons.Default.AccountCircle)
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
    val userType by AuthState.userType.collectAsState()
    val tabs = remember(userType) {
        if (userType == "blind")
            listOf(HomeTab.NAVIGATION, HomeTab.FRIENDS, HomeTab.AGENT, HomeTab.ALERTS, HomeTab.PROFILE)
        else
            listOf(HomeTab.FRIENDS, HomeTab.ALERTS, HomeTab.PROFILE)  // Family: simpler, focused on monitoring
    }
    val defaultTab = if (userType == "blind") HomeTab.AGENT else HomeTab.FRIENDS
    val familyTab = if (userType == "family") HomeTab.FRIENDS else null
    var selectedTab by remember { mutableStateOf(defaultTab) }
    var showAgentOverlay by remember { mutableStateOf(false) }
    var showRequestsDialog by remember { mutableStateOf(false) }
    var friendSubTab by remember { mutableStateOf(0) } // 0=friends, 1=groups
    var navSubTab by remember { mutableStateOf(0) }    // 0=stream, 1=map

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
    LaunchedEffect(AuthState.token.value) {
        AuthState.token.value?.let { wsClient.connect(serverIp, serverPort, it) }
    }
    LaunchedEffect(Unit) {
        launch { wsClient.statusUpdates.collect { SocialState.updateFriendStatus(it.userId, it.status, it.alertType, it.note, it.city, it.lastUpdated) } }
        launch { wsClient.emergencyAlerts.collect { SocialState.addEmergencyEvent(it) } }
        launch { wsClient.friendRequests.collect { SocialState.loadRequests() } }
    }
    LaunchedEffect(serverIp, serverPort) {
        SocialApiClient.setServer("http://$serverIp:$serverPort")
        val token = AuthState.token.value
        token?.let { SocialApiClient.setToken(it) }
        // Start emergency notification service (listens for SOS in background)
        if (token != null) {
            com.visus.app.service.EmergencyNotificationService.start(
                context, "http://$serverIp:$serverPort", token
            )
        }
    }
    // Periodic unread count check
    val unread by SocialState.unreadCount.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            SocialState.loadUnreadCount()
            kotlinx.coroutines.delay(5000)
        }
    }
    // Load API keys from settings
    val amapKeySaved by settingsDataStore.amapKey.collectAsState(initial = "")
    val arkKeySaved by settingsDataStore.arkKey.collectAsState(initial = "")
    LaunchedEffect(amapKeySaved, arkKeySaved) {
        SocialApiClient.amapKey = amapKeySaved
        SocialApiClient.arkKey = arkKeySaved
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = {
                                BadgedBox(badge = {
                                    when {
                                        tab == HomeTab.FRIENDS && unread > 0 -> Badge { Text("$unread") }
                                        tab == HomeTab.ALERTS && SocialState.recentEmergencies.collectAsState().value.isNotEmpty() -> Badge { Text("${SocialState.recentEmergencies.collectAsState().value.size}") }
                                    }
                                }) {
                                    Icon(tab.icon, contentDescription = tab.label,
                                        modifier = if (tab == HomeTab.AGENT) Modifier.size(28.dp) else Modifier,
                                        tint = if (tab == HomeTab.AGENT) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                }
                            },
                            label = {
                                Text(tab.label,
                                    fontWeight = if (tab == HomeTab.AGENT) FontWeight.Bold else FontWeight.Normal,
                                    color = if (tab == HomeTab.AGENT) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            }
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (selectedTab) {
                    HomeTab.FRIENDS -> {
                        if (userType == "family") {
                            FamilyDashboardScreen()
                        } else {
                            Column {
                                TabRow(selectedTabIndex = friendSubTab) {
                                    Tab(selected = friendSubTab == 0, onClick = { friendSubTab = 0 }, text = { Text("好友列表") })
                                    Tab(selected = friendSubTab == 1, onClick = { friendSubTab = 1 }, text = { Text("群组") })
                                }
                                when (friendSubTab) {
                                    0 -> FriendsScreen(onNavigateToRequests = { showRequestsDialog = true })
                                    1 -> GroupsScreen()
                                }
                            }
                        }
                    }
                    HomeTab.NAVIGATION -> {
                        Column {
                            TabRow(selectedTabIndex = navSubTab) {
                                Tab(selected = navSubTab == 0, onClick = { navSubTab = 0 }, text = { Text("辅助出行") })
                                Tab(selected = navSubTab == 1, onClick = { navSubTab = 1 }, text = { Text("地图导航") })
                            }
                            when (navSubTab) {
                                0 -> NavigationTab(streaming, connectionStatus, serverIp, serverPort, partialText, finalMessages, latestFrame, onStartStreaming, onStopStreaming, serverIp, serverPort) { ip, port -> scope.launch { settingsDataStore.saveServerIp(ip); settingsDataStore.saveServerPort(port) } }
                                1 -> NavigationMapScreen()
                            }
                        }
                    }
                    HomeTab.AGENT -> VoiceAgentScreen()
                    HomeTab.ALERTS -> AlertsScreen()
                    HomeTab.PROFILE -> ProfileScreen(onLogout = onLogout)
                }
            }
        }

        // AI FAB - visible on all tabs for blind users (switches to AGENT tab)
        if (userType == "blind" && selectedTab != HomeTab.AGENT) {
            FloatingActionButton(
                onClick = { selectedTab = HomeTab.AGENT },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 80.dp)
                    .size(56.dp).shadow(8.dp, CircleShape),
                containerColor = Color(0xFF6366F1),
                shape = CircleShape
            ) {
                Text("🤖", fontSize = 24.sp)
            }
        }
    }

    // Friend Requests Dialog (shared)
    if (showRequestsDialog) {
        val reqs by SocialState.requests.collectAsState()
        AlertDialog(
            onDismissRequest = { showRequestsDialog = false },
            title = { Text("好友请求") },
            text = {
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(reqs) { req ->
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(req.sender?.let { "${it.firstName} ${it.lastName}" } ?: "Unknown", fontWeight = FontWeight.Medium)
                                    Text(req.sender?.email ?: "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { SocialState.respondRequest(req.id, true) }) { Text("批准") }
                                TextButton(onClick = { SocialState.respondRequest(req.id, false) }) { Text("拒绝") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRequestsDialog = false }) { Text("关闭") } }
        )
    }
}

// ── Agent overlay content (reusable in FAB overlay) ──
@Composable
fun AgentChatContent() {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("有什么需要帮助的？") }
    var reply by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val tts = remember { TextToSpeech(ctx) { status -> if (status != TextToSpeech.SUCCESS) {} } }

    fun send(text: String) {
        if (text.isBlank()) return
        status = "思考中..."
        scope.launch {
            try {
                val token = AuthState.token.value ?: return@launch
                val body = org.json.JSONObject().apply {
                    put("text", text); put("user_id", AuthState.currentUserId.value)
                    put("lat", 0.0); put("lng", 0.0); put("city", "")
                }
                val resp = SocialApiClient.post("/api/agent/command", body, token)
                val data = resp.optJSONObject("data") ?: return@launch
                val r = data.optString("reply_text", "")
                reply = r; status = r
                tts.language = java.util.Locale.CHINESE
                tts.speak(r, TextToSpeech.QUEUE_FLUSH, null, "agent_${System.currentTimeMillis()}")
            } catch (e: Exception) { status = "AI暂不可用: ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(status, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        if (reply.isNotBlank()) Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0FE))) {
            Text(reply, modifier = Modifier.padding(12.dp), fontSize = 15.sp)
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = input, onValueChange = { input = it }, placeholder = { Text("说些什么...") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { send(input); input = "" }) { Text("发送") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("🏥 附近医院" to "附近的医院", "🚶 辅助出行" to "开启辅助出行", "📍 我在哪" to "我在哪").forEach { (label, cmd) ->
                AssistChip(onClick = { send(cmd) }, label = { Text(label, fontSize = 12.sp) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun AgentScreenOverlay() {
    Column(Modifier.fillMaxSize().background(Color(0xFFF5F5F5)).padding(12.dp)) {
        Text("🤖 Visus AI 助手", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.padding(bottom = 12.dp))
        AgentChatContent()
    }
}

// ── Navigation Tab (streaming + settings) ──
@Composable
fun NavigationTab(
    streaming: Boolean, connectionStatus: String, serverIp: String, serverPort: String,
    partialText: String, finalMessages: List<String>, latestFrame: Bitmap?,
    onStartStreaming: () -> Unit, onStopStreaming: () -> Unit,
    serverIpValue: String, serverPortValue: String,
    onSaveServer: (String, String) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf(serverIpValue) }
    var portInput by remember { mutableStateOf(serverPortValue) }

    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(8.dp))
            Text(if (streaming) connectionStatus else "已停止", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("$serverIp:$serverPort", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
        }
        Box(Modifier.fillMaxWidth().weight(0.46f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            if (latestFrame != null) Image(latestFrame.asImageBitmap(), "preview", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(if (streaming) "正在打开相机" else "未开始推流", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        }
        Card(Modifier.fillMaxWidth().weight(0.54f), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text("语音文字", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(partialText, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    if (finalMessages.isEmpty()) item { Text("还没有识别结果", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
                    else items(finalMessages.asReversed()) { msg -> Text(msg, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
        Button(onClick = { if (streaming) onStopStreaming() else onStartStreaming() }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp), colors = ButtonDefaults.buttonColors(containerColor = if (streaming) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) {
            Icon(if (streaming) Icons.Default.VideocamOff else Icons.Default.Videocam, null)
            Spacer(Modifier.width(8.dp))
            Text(if (streaming) "停止推流" else "开始推流", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(2.dp))
    }
    if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("服务器设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(ipInput, { ipInput = it }, label = { Text("IP") }, singleLine = true)
            OutlinedTextField(portInput, { portInput = it }, label = { Text("端口") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { onSaveServer(ipInput.trim(), portInput.trim()); showSettings = false }) { Text("保存") } }, dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } })
}
