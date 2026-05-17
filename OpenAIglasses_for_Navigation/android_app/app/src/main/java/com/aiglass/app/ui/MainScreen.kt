package com.aiglass.app.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiglass.app.network.ServerConfig

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

@Composable
fun MainScreen(
    latestFrame: ByteArray?,
    connectionState: com.aiglass.app.network.ConnectionState,
    asrPartial: String,
    asrFinals: List<String>,
    navigationStatus: String,
    aiResponse: String?,
    chatMessages: List<ChatMessage>,
    serverConfig: ServerConfig,
    onConfigChange: (ServerConfig) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }

    // Settings dialog
    if (showSettings) {
        ServerSettingsDialog(
            currentConfig = serverConfig,
            onSave = { newConfig ->
                onConfigChange(newConfig)
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .systemBarsPadding()
    ) {
        // ========== Top bar: Status + Controls ==========
        TopBar(
            connectionState = connectionState,
            navigationStatus = navigationStatus,
            onSettings = { showSettings = true },
            onConnect = onConnect,
            onDisconnect = onDisconnect
        )

        // ========== Camera feed ==========
        CameraFeed(
            latestFrame = latestFrame,
            navigationStatus = navigationStatus,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
        )

        // ========== Voice hint bar ==========
        VoiceHint(asrPartial = asrPartial)

        // ========== Chat / Status panel ==========
        Box(modifier = Modifier.weight(0.35f)) {
            LazyColumn(
                state = scrollState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(chatMessages) { msg ->
                    ChatBubble(message = msg)
                }
            }

            // Auto-scroll to bottom
            LaunchedEffect(chatMessages.size) {
                if (chatMessages.isNotEmpty()) {
                    scrollState.animateScrollToItem(chatMessages.size - 1)
                }
            }
        }
    }
}

@Composable
fun TopBar(
    connectionState: com.aiglass.app.network.ConnectionState,
    navigationStatus: String,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Surface(
        color = Surface,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AI辅助出行",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.width(8.dp))

            Surface(
                color = when {
                    navigationStatus.contains("导航") ||
                    navigationStatus.contains("NAVIGATING") -> Accent
                    navigationStatus.contains("等待") ||
                    navigationStatus.contains("过马路") -> StatusWarning
                    else -> SurfaceVariant
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = when {
                        navigationStatus.contains("盲道") -> "盲道导航"
                        navigationStatus.contains("斑马线") ||
                        navigationStatus.contains("过马路") -> "过马路"
                        navigationStatus.contains("搜索") ||
                        navigationStatus.contains("查找") -> "搜索中"
                        else -> "待机"
                    },
                    color = TextPrimary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            ConnectionDot(isConnected = connectionState.camera, label = "CAM", modifier = Modifier.padding(horizontal = 2.dp))
            ConnectionDot(isConnected = connectionState.audio, label = "MIC", modifier = Modifier.padding(horizontal = 2.dp))
            ConnectionDot(isConnected = connectionState.viewer, label = "VID", modifier = Modifier.padding(horizontal = 2.dp))
            ConnectionDot(isConnected = connectionState.ui, label = "UI", modifier = Modifier.padding(horizontal = 2.dp))

            Spacer(Modifier.width(4.dp))

            // Settings button
            IconButton(onClick = onSettings, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "服务器设置",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (connectionState.isFullyConnected) {
                IconButton(onClick = onDisconnect, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "断开", tint = StatusErr, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(onClick = onConnect, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "连接", tint = StatusOk, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun ConnectionDot(isConnected: Boolean, label: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isConnected) StatusOk else StatusErr)
        )
        Text(
            label,
            color = TextSecondary,
            fontSize = 8.sp
        )
    }
}

@Composable
fun CameraFeed(
    latestFrame: ByteArray?,
    navigationStatus: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center
    ) {
        if (latestFrame != null) {
            val bitmap = remember(latestFrame) {
                try {
                    BitmapFactory.decodeByteArray(latestFrame, 0, latestFrame.size)
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Camera feed",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
            } else {
                PlaceholderCamera("解码中...")
            }
        } else {
            PlaceholderCamera("等待视频流...")
        }

        // Bottom overlay: navigation status text
        if (navigationStatus.isNotBlank() && navigationStatus != "待机") {
            Surface(
                color = Background.copy(alpha = 0.75f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Text(
                    text = navigationStatus,
                    color = StatusWarning,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun PlaceholderCamera(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.Videocam,
            contentDescription = null,
            tint = TextSecondary.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(text, color = TextSecondary.copy(alpha = 0.6f), fontSize = 14.sp)
    }
}

@Composable
fun VoiceHint(asrPartial: String) {
    Surface(
        color = Surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = if (asrPartial.isNotBlank()) Accent else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = asrPartial.ifBlank { "说\"开始导航\"或\"帮我找...\"" },
                color = if (asrPartial.isNotBlank()) TextPrimary else TextSecondary,
                fontSize = 13.sp,
                fontStyle = if (asrPartial.isBlank())
                    androidx.compose.ui.text.font.FontStyle.Italic
                else
                    androidx.compose.ui.text.font.FontStyle.Normal
            )
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!message.isUser) {
            // AI avatar
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Border),
                contentAlignment = Alignment.Center
            ) {
                Text("AI", color = TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
        }

        Surface(
            color = if (message.isUser) BubbleUser else BubbleAI,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (message.isUser) 14.dp else 6.dp,
                bottomEnd = if (message.isUser) 6.dp else 14.dp
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                color = if (message.isUser) Color.White else TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        if (message.isUser) {
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
fun ServerSettingsDialog(
    currentConfig: ServerConfig,
    onSave: (ServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var host by remember { mutableStateOf(currentConfig.host) }
    var portText by remember { mutableStateOf(currentConfig.port.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("服务器设置", color = TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "输入 Python 服务器的 IP 地址和端口：",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("服务器 IP") },
                    placeholder = { Text("例如: 192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border
                    )
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") },
                    placeholder = { Text("8081") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border
                    )
                )
                Text(
                    "提示：手机和服务器需在同一局域网",
                    color = StatusWarning,
                    fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val port = portText.toIntOrNull() ?: 8081
                    onSave(ServerConfig(host = host.ifBlank { "10.0.2.2" }, port = port))
                }
            ) {
                Text("保存", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = TextSecondary)
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(16.dp)
    )
}
