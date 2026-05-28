package com.visus.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.SettingsDataStore
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    isStreaming: () -> Boolean
) {
    val context = LocalContext.current
    val settingsDataStore = remember { SettingsDataStore(context) }
    val scope = rememberCoroutineScope()

    val serverIp by settingsDataStore.serverIp.collectAsState(initial = SettingsDataStore.DEFAULT_IP)
    val serverPort by settingsDataStore.serverPort.collectAsState(initial = SettingsDataStore.DEFAULT_PORT)

    var showSettings by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf("") }
    var portInput by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }

    LaunchedEffect(serverIp, serverPort) {
        ipInput = serverIp
        portInput = serverPort
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Visus 智能导航",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 状态显示区域
            StatusCard(
                isStreaming = streaming,
                serverIp = serverIp,
                serverPort = serverPort
            )

            Spacer(modifier = Modifier.weight(1f))

            // 控制按钮
            ControlButton(
                isStreaming = streaming,
                onToggle = {
                    streaming = !streaming
                    if (streaming) {
                        onStartStreaming()
                    } else {
                        onStopStreaming()
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 设置对话框
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("服务器设置") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        label = { Text("服务器 IP") },
                        placeholder = { Text("例如: 192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = { portInput = it },
                        label = { Text("端口") },
                        placeholder = { Text("例如: 8081") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            settingsDataStore.saveServerIp(ipInput)
                            settingsDataStore.saveServerPort(portInput)
                            showSettings = false
                        }
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun StatusCard(
    isStreaming: Boolean,
    serverIp: String,
    serverPort: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "连接状态",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            if (isStreaming) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                )
                Text(
                    text = if (isStreaming) "推流中" else "已停止",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Divider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "服务器地址",
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = "$serverIp:$serverPort",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ControlButton(
    isStreaming: Boolean,
    onToggle: () -> Unit
) {
    Button(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(32.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isStreaming) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isStreaming) Icons.Default.VideocamOff
                else Icons.Default.Videocam,
                contentDescription = null
            )
            Text(
                text = if (isStreaming) "停止推流" else "开始推流",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
