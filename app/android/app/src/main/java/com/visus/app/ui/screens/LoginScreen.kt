package com.visus.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.AuthState
import com.visus.app.data.SettingsDataStore
import com.visus.app.network.SocialApiClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    val settingsDataStore = remember { SettingsDataStore(context) }
    val scope = rememberCoroutineScope()

    var isLogin by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Server config
    val savedServerIp by settingsDataStore.serverIp.collectAsState(initial = SettingsDataStore.DEFAULT_IP)
    val savedServerPort by settingsDataStore.serverPort.collectAsState(initial = SettingsDataStore.DEFAULT_PORT)
    var serverIp by remember { mutableStateOf(savedServerIp) }
    var serverPort by remember { mutableStateOf(savedServerPort) }
    var showServerConfig by remember { mutableStateOf(false) }

    // Initialize server URL from saved settings
    LaunchedEffect(Unit) {
        serverIp = savedServerIp
        serverPort = savedServerPort
        SocialApiClient.setServer("http://$savedServerIp:$savedServerPort")
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(40.dp))

            Text(
                text = "Visus",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (isLogin) "智能导航 · 好友守护" else "创建新账号",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            // Server info bar (clickable to expand)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                onClick = { showServerConfig = !showServerConfig }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔧 服务器: $serverIp:$serverPort",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (showServerConfig) "▲" else "▼",
                        fontSize = 12.sp
                    )
                }
            }

            if (showServerConfig) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = serverIp,
                                onValueChange = { serverIp = it },
                                label = { Text("IP 地址") },
                                placeholder = { Text("10.0.2.2") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = serverPort,
                                onValueChange = { serverPort = it },
                                label = { Text("端口") },
                                placeholder = { Text("8081") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(100.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                val url = "http://${serverIp.trim()}:${serverPort.trim()}"
                                SocialApiClient.setServer(url)
                                scope.launch {
                                    settingsDataStore.saveServerIp(serverIp.trim())
                                    settingsDataStore.saveServerPort(serverPort.trim())
                                    errorText = "服务器地址已更新: $url"
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) { Text("保存服务器地址", fontSize = 13.sp) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it; errorText = null },
                label = { Text("邮箱") },
                placeholder = { Text("alice@demo.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            if (!isLogin) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("姓") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; errorText = null },
                label = { Text("密码") },
                placeholder = { Text("demo123") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            if (!isLogin) {
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; errorText = null },
                    label = { Text("确认密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
            }

            if (errorText != null) {
                Text(
                    text = errorText!!,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Button(
                onClick = {
                    val url = "http://${serverIp.trim()}:${serverPort.trim()}"
                    SocialApiClient.setServer(url)

                    if (email.isBlank() || password.isBlank()) {
                        errorText = "请填写所有必填字段"
                        return@Button
                    }
                    if (!isLogin && password != confirmPassword) {
                        errorText = "两次密码不一致"
                        return@Button
                    }
                    if (!isLogin && password.length < 6) {
                        errorText = "密码至少6位"
                        return@Button
                    }

                    isLoading = true
                    errorText = null
                    scope.launch {
                        try {
                            if (isLogin) {
                                val (token, user) = SocialApiClient.login(email.trim(), password)
                                AuthState.saveLogin(token, user.id, user.email, user.firstName, user.lastName)
                            } else {
                                val (token, user) = SocialApiClient.signup(
                                    email.trim(), password,
                                    firstName.trim(), lastName.trim()
                                )
                                AuthState.saveLogin(token, user.id, user.email, user.firstName, user.lastName)
                            }
                            onLoginSuccess()
                        } catch (e: Exception) {
                            errorText = when {
                                e.message?.contains("Unable to resolve host") == true -> "无法连接服务器 ($url)，请检查地址和网络"
                                e.message?.contains("failed to connect") == true -> "无法连接 $url，请确认服务器已启动"
                                e.message?.contains("401") == true -> "邮箱或密码错误"
                                e.message?.contains("400") == true -> "邮箱已被注册"
                                e.message?.contains("timeout") == true -> "连接超时，请检查服务器地址"
                                else -> "连接失败: ${e.message}\n服务器: $url"
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (isLogin) "登录" else "注册", fontSize = 16.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            TextButton(onClick = { isLogin = !isLogin; errorText = null }) {
                Text(
                    if (isLogin) "没有账号？注册" else "已有账号？登录",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
