package com.visus.app.ui.screens

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.AuthState
import com.visus.app.network.MapApiClient
import com.visus.app.network.SocialApiClient
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationMapScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var navigateResult by remember { mutableStateOf<MapApiClient.NavigateResult?>(null) }
    var currentStep by remember { mutableStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // TTS engine for voice guidance
    val tts = remember { TextToSpeech(context) { status -> if (status != TextToSpeech.SUCCESS) errorMsg = "TTS初始化失败" } }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }

    fun speak(text: String) {
        tts.language = Locale.CHINESE
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_${System.currentTimeMillis()}")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("语音导航", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Default.Info, "帮助")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Input area
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("🎤 说出去哪里", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("例如：最近的医院、南京东路地铁站、回家", fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = destination,
                            onValueChange = { destination = it },
                            placeholder = { Text("目的地") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (destination.isBlank()) {
                                    errorMsg = "请输入目的地"
                                    return@Button
                                }
                                isLoading = true
                                errorMsg = null
                                scope.launch {
                                    try {
                                        val result = MapApiClient.navigate(
                                            destination.trim(),
                                            31.2304, 121.4737, // Default: Shanghai
                                            "上海",
                                            AuthState.token.value
                                        )
                                        if (result != null) {
                                            navigateResult = result
                                            currentStep = 0
                                            speak(result.voiceSummary)
                                        } else {
                                            errorMsg = "无法找到\"$destination\"，请换个说法试试"
                                        }
                                    } catch (e: Exception) {
                                        errorMsg = "导航失败: ${e.message}"
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = !isLoading,
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("搜索") }
                    }
                }
            }

            // Error
            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }

            // Loading
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text("正在规划路线...", fontSize = 14.sp)
                    }
                }
            }

            // Navigation result
            navigateResult?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("📍 ${result.destination}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("全程 ${result.distanceMeters}米 · 步行约${result.durationMinutes}分钟",
                            fontSize = 14.sp, color = Color.DarkGray)
                        Spacer(Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    isNavigating = !isNavigating
                                    if (isNavigating && result.steps.isNotEmpty()) {
                                        speak("开始导航。" + result.steps[0].instruction)
                                    } else {
                                        speak("导航已停止")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isNavigating) Color(0xFFE63946) else Color(0xFF34A853)
                                )
                            ) { Text(if (isNavigating) "停止导航" else "开始导航") }

                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        val addr = MapApiClient.reverseGeocode(31.2304, 121.4737, AuthState.token.value)
                                        val locMsg = "📍 我当前在: ${addr.address}"
                                        speak(locMsg)
                                    } catch (_: Exception) {}
                                }
                            }) { Text("📍 我在哪") }

                            OutlinedButton(onClick = {
                                scope.launch {
                                    val msg = "我当前在 ${result.destination} 附近，请速联系我！"
                                    try {
                                        SocialApiClient.triggerEmergency(
                                            eventType = "manual_sos",
                                            lat = 0.0, lng = 0.0,
                                            description = msg
                                        )
                                        speak("已向所有好友发送位置求助")
                                    } catch (_: Exception) {}
                                }
                            }) { Text("🆘 求助") }
                        }
                    }
                }

                // Step list
                Text("路线步骤 (${result.stepCount}步)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(result.steps) { index, step ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (index == currentStep && isNavigating)
                                    Color(0xFFFFF9C4) else MaterialTheme.colorScheme.surface
                            ),
                            onClick = {
                                currentStep = index
                                speak("第${index + 1}步: ${step.instruction}")
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${index + 1}", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(step.instruction, fontSize = 14.sp)
                                    Text("${step.distanceMeters}m · ${step.road}",
                                        fontSize = 12.sp, color = Color.Gray)
                                }
                                IconButton(onClick = { speak(step.instruction) }) {
                                    Icon(Icons.Default.VolumeUp, "朗读")
                                }
                            }
                        }
                    }
                }
            }

            // Quick actions
            if (navigateResult == null && !isLoading) {
                Text("快捷搜索", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickChip("🏥 医院") { destination = "医院"; scope.launch { quickNav("医院", { r -> navigateResult = r }, { l -> isLoading = l }, { e -> errorMsg = e }) } }
                    QuickChip("🚇 地铁站") { destination = "地铁站"; scope.launch { quickNav("地铁站", { r -> navigateResult = r }, { l -> isLoading = l }, { e -> errorMsg = e }) } }
                    QuickChip("🏪 超市") { destination = "超市"; scope.launch { quickNav("超市", { r -> navigateResult = r }, { l -> isLoading = l }, { e -> errorMsg = e }) } }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QuickChip("🚾 厕所") { destination = "厕所"; scope.launch { quickNav("厕所", { r -> navigateResult = r }, { l -> isLoading = l }, { e -> errorMsg = e }) } }
                    QuickChip("🍜 餐厅") { destination = "餐厅"; scope.launch { quickNav("餐厅", { r -> navigateResult = r }, { l -> isLoading = l }, { e -> errorMsg = e }) } }
                    QuickChip("🏠 回家") { destination = "家"; scope.launch { quickNav("家", { r -> navigateResult = r }, { l -> isLoading = l }, { e -> errorMsg = e }) } }
                }
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("语音导航帮助") },
            text = { Text("1. 输入目的地或点击快捷按钮\n2. 查看路线规划\n3. 点击[开始导航]语音引导\n4. 点击[我在哪]获取当前位置\n5. 点击[求助]向好友发送位置求助") },
            confirmButton = { TextButton(onClick = { showHelpDialog = false }) { Text("知道了") } }
        )
    }
}

@Composable
fun RowScope.QuickChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.weight(1f)
    )
}

private suspend fun quickNav(
    dest: String,
    resultSetter: (MapApiClient.NavigateResult?) -> Unit,
    loadingSetter: (Boolean) -> Unit,
    errorSetter: (String?) -> Unit
) {
    loadingSetter(true)
    errorSetter(null)
    try {
        val result = MapApiClient.navigate(dest, 31.2304, 121.4737, "上海", AuthState.token.value)
        resultSetter(result)
        if (result == null) errorSetter("无法找到 $dest")
    } catch (e: Exception) {
        errorSetter("导航失败: ${e.message}")
    } finally {
        loadingSetter(false)
    }
}
