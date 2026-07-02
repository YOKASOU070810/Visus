package com.visus.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import com.visus.app.data.AuthState
import com.visus.app.data.SocialState
import com.visus.app.data.SettingsDataStore
import com.visus.app.network.SocialApiClient
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = remember { SettingsDataStore(context) }
    val userName by AuthState.currentUserName.collectAsState()
    val userId by AuthState.currentUserId.collectAsState()
    val myStatus by SocialState.myStatus.collectAsState()
    var showServerSettings by remember { mutableStateOf(false) }
    var showApiKeySettings by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { showLogoutConfirm = true }) {
                        Icon(Icons.Default.ExitToApp, "退出登录")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // User info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(userName.ifBlank { "用户" }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("ID: $userId", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "状态: ${if (myStatus == true) "✅ 安全" else if (myStatus == false) "⚠ 不安全" else "未知"}",
                        fontSize = 15.sp,
                        color = if (myStatus == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Settings sections
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("设置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { showServerSettings = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("服务器设置", modifier = Modifier.weight(1f)) }
                    HorizontalDivider()
                    TextButton(
                        onClick = { showApiKeySettings = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("API 密钥配置 (高德/豆包)", modifier = Modifier.weight(1f)) }
                    HorizontalDivider()
                    val ut by AuthState.userType.collectAsState()
                    TextButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val token = AuthState.token.value ?: return@launch
                                    SocialApiClient.post("/api/profile/switch-mode", JSONObject(), token)
                                    AuthState.switchMode()
                                } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("切换身份模式 (当前: ${if (ut == "blind") "盲人" else "家属"})", modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = { showLogoutConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("退出登录", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // About
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("关于", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Visus 智能导航系统 v1.0.0", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("AI辅助出行 · 好友安全守护", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // Server settings dialog
    if (showServerSettings) {
        var ipInput by remember { mutableStateOf(SocialApiClient.getServer().removePrefix("http://").split(":")[0]) }
        var portInput by remember { mutableStateOf(
            SocialApiClient.getServer().split(":").lastOrNull() ?: "8081"
        ) }
        AlertDialog(
            onDismissRequest = { showServerSettings = false },
            title = { Text("服务器设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = ipInput, onValueChange = { v: String -> ipInput = v }, label = { Text("IP 地址") }, singleLine = true)
                    OutlinedTextField(value = portInput, onValueChange = { v: String -> portInput = v }, label = { Text("端口") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = "http://${ipInput.trim()}:${portInput.trim()}"
                    SocialApiClient.setServer(url)
                    showServerSettings = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showServerSettings = false }) { Text("取消") } }
        )
    }

    // API Key settings dialog
    if (showApiKeySettings) {
        val savedAmap by settingsStore.amapKey.collectAsState(initial = "")
        val savedArk by settingsStore.arkKey.collectAsState(initial = "")
        var amapInput by remember { mutableStateOf(savedAmap) }
        var arkInput by remember { mutableStateOf(savedArk) }
        AlertDialog(
            onDismissRequest = { showApiKeySettings = false },
            title = { Text("API 密钥配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("配置你自己的 API Key，留空则使用服务器默认配置。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = amapInput, onValueChange = { amapInput = it }, label = { Text("高德地图 API Key") }, placeholder = { Text("可选，用于地图导航") }, singleLine = true)
                    OutlinedTextField(value = arkInput, onValueChange = { arkInput = it }, label = { Text("豆包 (Ark) API Key") }, placeholder = { Text("可选，用于AI助手") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        settingsStore.saveAmapKey(amapInput.trim())
                        settingsStore.saveArkKey(arkInput.trim())
                        SocialApiClient.amapKey = amapInput.trim()
                        SocialApiClient.arkKey = arkInput.trim()
                        showApiKeySettings = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showApiKeySettings = false }) { Text("取消") } }
        )
    }

    // Logout confirmation
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("确认退出") },
            text = { Text("退出登录后需要重新输入账号密码。") },
            confirmButton = {
                Button(onClick = {
                    SocialApiClient.logout()
                    AuthState.logout()
                    onLogout()
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("退出")
                }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") } }
        )
    }
}
