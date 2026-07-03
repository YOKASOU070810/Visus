package com.visus.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.SocialState
import com.visus.app.network.EmergencyAlertEvent
import com.visus.app.network.SocialApiClient.FriendInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen() {
    val scope = rememberCoroutineScope()
    val alerts by SocialState.recentEmergencies.collectAsState()
    val friends by SocialState.friends.collectAsState()
    var showSosDialog by remember { mutableStateOf(false) }

    // Track dismissed alert IDs
    val dismissedIds = remember { mutableStateListOf<Int>() }

    val emergencyFriends = friends.filter { it.alertType?.startsWith("emergency") == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("紧急提醒", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showSosDialog = true },
                containerColor = MaterialTheme.colorScheme.error
            ) { Icon(Icons.Default.Warning, "SOS", tint = Color.White) }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (emergencyFriends.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("⚠ 好友紧急状态", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFE65100))
                        Spacer(Modifier.height(8.dp))
                        for (friend in emergencyFriends) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⚠", fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("${friend.user.firstName} ${friend.user.lastName}", fontWeight = FontWeight.Medium)
                                    Text(friend.note ?: "紧急情况", fontSize = 13.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }

            val visibleAlerts = alerts.filter { it.userId !in dismissedIds }
            if (visibleAlerts.isEmpty() && emergencyFriends.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无紧急提醒", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("当好友触发紧急情况时会在这里显示", fontSize = 14.sp, color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleAlerts, key = { it.userId.hashCode() + (it.createdAt?.hashCode() ?: 0) }) { alert ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFEBEE)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🆘 SOS求助", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFFE63946))
                                    Spacer(Modifier.weight(1f))
                                    Text(alert.createdAt?.take(19)?.replace("T", " ") ?: "", fontSize = 11.sp, color = Color.Gray)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("${alert.userName} (${alert.userEmail})", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                if (!alert.description.isNullOrBlank()) {
                                    Text(alert.description!!, fontSize = 13.sp, color = Color.DarkGray)
                                }
                                if (!alert.city.isNullOrBlank()) {
                                    Text("📍 ${alert.city}", fontSize = 12.sp, color = Color.Gray)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = {
                                        dismissedIds.add(alert.userId)
                                    }) {
                                        Text("确认收到 ✓", color = Color(0xFF4CAF50))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = { Text("确认发送紧急求助", fontWeight = FontWeight.Bold) },
            text = { Text("这将向所有好友推送紧急通知，并将你的状态标记为[不安全]。确认继续？") },
            confirmButton = {
                Button(
                    onClick = {
                        SocialState.triggerEmergency("manual_sos", "用户手动触发SOS紧急求助")
                        showSosDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE63946))
                ) { Text("发送SOS", color = Color.White) }
            },
            dismissButton = { TextButton(onClick = { showSosDialog = false }) { Text("取消") } }
        )
    }
}
