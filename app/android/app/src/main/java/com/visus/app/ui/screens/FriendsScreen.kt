package com.visus.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.SocialState
import com.visus.app.network.SocialApiClient.FriendInfo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onNavigateToSearch: () -> Unit = {},
    onNavigateToRequests: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val friends by SocialState.friends.collectAsState()
    val myStatus by SocialState.myStatus.collectAsState()
    val isLoading by SocialState.isLoadingFriends.collectAsState()
    val error by SocialState.errorMessage.collectAsState()
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by SocialState.searchResults.collectAsState()

    // Load friends on first composition
    LaunchedEffect(Unit) {
        SocialState.loadFriends()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("好友", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { scope.launch { SocialState.loadFriends() } }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(Icons.Default.PersonAdd, "添加好友")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // My Status Card
            MyStatusCard(
                isSafe = myStatus,
                onSetSafe = { SocialState.updateMyStatus(true) },
                onSetUnsafe = { SocialState.updateMyStatus(false) }
            )

            // Pending Requests Button
            val pendingCount = SocialState.requests.collectAsState().value.size
            if (pendingCount > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    onClick = onNavigateToRequests
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔔 $pendingCount 个待处理的好友请求", fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Error message
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }

            // Friends List
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (friends.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无好友", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { showSearchDialog = true }) {
                            Text("添加好友")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(friends, key = { it.user.id }) { friend ->
                        FriendCard(friend)
                    }
                }
            }
        }
    }

    // Search Dialog
    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("搜索用户") },
            text = {
                Column {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("用户名/邮箱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { SocialState.searchUsers(searchQuery) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("搜索") }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(searchResults) { result ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${result.user.firstName} ${result.user.lastName}", fontWeight = FontWeight.Medium)
                                    Text(result.user.email, fontSize = 13.sp, color = Color.Gray)
                                }
                                when {
                                    result.isFriend -> Text("已是好友", color = Color.Green, fontSize = 13.sp)
                                    result.requestPending -> Text("已发送", color = Color.Gray, fontSize = 13.sp)
                                    else -> TextButton(onClick = {
                                        SocialState.addFriend(result.user.id) {
                                            SocialState.searchUsers(searchQuery)
                                        }
                                    }) { Text("添加") }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSearchDialog = false }) { Text("关闭") } },
        )
    }
}

@Composable
fun MyStatusCard(isSafe: Boolean?, onSetSafe: () -> Unit, onSetUnsafe: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("我的安全状态", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onSetSafe,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSafe == true) Color(0xFF34A853) else Color.Gray
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("我安全 ✓") }

                Button(
                    onClick = onSetUnsafe,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSafe == false) Color(0xFFE63946) else Color.Gray
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("需要帮助 !") }
            }
        }
    }
}

@Composable
fun FriendCard(friend: FriendInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(
                        when (friend.status) {
                            true -> Color(0xFF34A853)
                            false -> Color(0xFFE63946)
                            null -> Color.Gray
                        }
                    )
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${friend.user.firstName} ${friend.user.lastName}",
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp
                )
                Text(friend.user.email, fontSize = 13.sp, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when (friend.status) {
                        true -> "安全"
                        false -> if (friend.alertType?.startsWith("emergency") == true) "⚠ 紧急" else "不安全"
                        null -> "未知"
                    },
                    color = when (friend.status) {
                        true -> Color(0xFF34A853)
                        false -> Color(0xFFE63946)
                        null -> Color.Gray
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                if (!friend.city.isNullOrBlank()) {
                    Text(friend.city!!, fontSize = 12.sp, color = Color.Gray)
                }
                if (!friend.lastUpdated.isNullOrBlank()) {
                    val time = friend.lastUpdated!!.takeLast(8).take(5) // extract HH:mm
                    Text(time, fontSize = 11.sp, color = Color.Gray)
                }
            }
        }
    }
}
