package com.visus.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.AuthState
import com.visus.app.data.SettingsDataStore
import com.visus.app.network.SocialApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class FamilyMemberInfo(
    val userId: Int, val name: String, val email: String,
    val status: Boolean?, val alertType: String?, val city: String?,
    val latitude: Double?, val longitude: Double?,
    val lastUpdated: String?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyDashboardScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var familyList by remember { mutableStateOf<List<FamilyMemberInfo>>(emptyList()) }
    var selectedMember by remember { mutableStateOf<FamilyMemberInfo?>(null) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var locationAddress by remember { mutableStateOf("") }
    var showCameraView by remember { mutableStateOf(false) }
    var cameraFrame by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val settingsDataStore = remember { SettingsDataStore(context) }
    val serverIp by settingsDataStore.serverIp.collectAsState(initial = SettingsDataStore.DEFAULT_IP)
    val serverPort by settingsDataStore.serverPort.collectAsState(initial = SettingsDataStore.DEFAULT_PORT)

    val tts = remember { TextToSpeech(context) {} }

    fun loadFamily() {
        scope.launch {
            isLoading = true
            try {
                val token = AuthState.token.value ?: return@launch
                val resp = SocialApiClient.get("/api/family/list", token)
                val arr = resp.optJSONObject("data")?.optJSONArray("family") ?: JSONArray()
                familyList = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    val u = o.optJSONObject("user") ?: JSONObject()
                    FamilyMemberInfo(
                        u.optInt("id"), "${u.optString("first_name")} ${u.optString("last_name")}",
                        u.optString("email"), if (o.isNull("status")) null else o.optBoolean("status"),
                        o.optString("alert_type", null), o.optString("city", null),
                        if (o.isNull("latitude")) null else o.optDouble("latitude"),
                        if (o.isNull("longitude")) null else o.optDouble("longitude"),
                        o.optString("last_updated", null)
                    )
                }
            } catch (e: Exception) { /* ignore */ }
            finally { isLoading = false }
        }
    }

    fun requestLocation(member: FamilyMemberInfo) {
        scope.launch {
            try {
                val token = AuthState.token.value ?: return@launch
                // Request location via message
                SocialApiClient.post("/api/messages/send", JSONObject().apply {
                    put("receiver_id", member.userId)
                    put("content", "家人请求获取你的位置")
                    put("msg_type", "location_request")
                }, token)
                // Try to get current location from status
                if (member.latitude != null && member.longitude != null) {
                    // Use reverse geocode via server
                    val geoResp = SocialApiClient.post("/api/maps/reverse", JSONObject().apply {
                        put("latitude", member.latitude!!); put("longitude", member.longitude!!)
                    }, token)
                    val addr = geoResp.optJSONObject("data")?.optString("address") ?: "未知地址"
                    locationAddress = addr
                } else {
                    locationAddress = "暂无位置数据"
                }
                showLocationDialog = true
            } catch (e: Exception) { locationAddress = "获取失败: ${e.message}"; showLocationDialog = true }
        }
    }

    fun viewCamera() {
        showCameraView = true
        // Try to fetch a frame from the server's viewer WebSocket
        scope.launch {
            while (showCameraView) {
                try {
                    val url = URL("http://$serverIp:$serverPort/api/health")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000; conn.readTimeout = 2000
                    if (conn.responseCode == 200) {
                        // Server is up - but actual camera frames require /ws/viewer
                        // For now show placeholder
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
                delay(2000)
            }
        }
    }

    LaunchedEffect(Unit) { loadFamily() }

    if (selectedMember != null) {
        // Private chat with family member
        ChatScreen(
            friendId = selectedMember!!.userId,
            friendName = selectedMember!!.name,
            onBack = { selectedMember = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("家人守护", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF4CAF50)),
                    actions = {
                        IconButton(onClick = { loadFamily() }) { Icon(Icons.Default.Refresh, "刷新") }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else if (familyList.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("暂无家人", fontSize = 18.sp)
                            Text("请让视障亲属将您设为家人", fontSize = 14.sp, color = Color.Gray)
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(familyList) { member ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = when (member.status) {
                                        true -> Color(0xFFE8F5E9)
                                        false -> Color(0xFFFFEBEE)
                                        null -> Color.White
                                    }
                                )
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(14.dp).clip(CircleShape).background(
                                            when (member.status) { true -> Color(0xFF4CAF50); false -> Color(0xFFE53935); null -> Color.Gray }
                                        ))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(member.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                            Text(member.email, fontSize = 13.sp, color = Color.Gray)
                                        }
                                        Badge(
                                            containerColor = when (member.status) {
                                                true -> Color(0xFF4CAF50); false -> Color(0xFFE53935); null -> Color.Gray
                                            }
                                        ) {
                                            Text(when (member.status) { true -> "安全"; false -> "紧急"; null -> "未知" }, color = Color.White, fontSize = 11.sp)
                                        }
                                    }
                                    if (!member.city.isNullOrBlank()) {
                                        Text("📍 ${member.city}", fontSize = 13.sp, color = Color.Gray)
                                    }
                                    if (!member.lastUpdated.isNullOrBlank()) {
                                        Text("更新: ${member.lastUpdated!!.take(19).replace("T"," ")}", fontSize = 11.sp, color = Color.LightGray)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { requestLocation(member) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))) {
                                            Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("位置", fontSize = 13.sp)
                                        }
                                        OutlinedButton(onClick = { viewCamera() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                                            Icon(Icons.Default.Videocam, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("画面", fontSize = 13.sp)
                                        }
                                        IconButton(onClick = { selectedMember = member }) {
                                            Icon(Icons.Default.Chat, "聊天", tint = Color(0xFF4CAF50))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLocationDialog) {
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            title = { Text("家人位置") },
            text = { Text(locationAddress) },
            confirmButton = { TextButton(onClick = { showLocationDialog = false }) { Text("关闭") } }
        )
    }

    if (showCameraView) {
        AlertDialog(
            onDismissRequest = { showCameraView = false },
            title = { Text("实时画面") },
            text = {
                Column {
                    if (cameraFrame != null) {
                        Image(cameraFrame!!.asImageBitmap(), "camera", Modifier.fillMaxWidth().height(300.dp), contentScale = ContentScale.Fit)
                    } else {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text("等待画面...\n请确认视障亲属正在使用导航功能")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCameraView = false }) { Text("关闭") } }
        )
    }
}
