package com.visus.app.ui.screens

import android.speech.tts.TextToSpeech
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visus.app.data.AuthState
import com.visus.app.data.SocialState
import com.visus.app.network.SocialApiClient
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

data class GInfo(val id: Int, val name: String, val memberCount: Int, val lastMsg: GMsg?)
data class GMsg(val id: Int, val sender: SocialApiClient.UserInfo?, val content: String, val msgType: String, val createdAt: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<GInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var selGroup by remember { mutableStateOf<GInfo?>(null) }
    var msgs by remember { mutableStateOf<List<GMsg>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val tts = remember { TextToSpeech(context) {} }

    fun loadGroups() {
        scope.launch {
            isLoading = true
            try {
                val token = AuthState.token.value ?: return@launch
                val resp = SocialApiClient.get("/api/groups/", token)
                val arr = resp.optJSONObject("data")?.optJSONArray("groups") ?: JSONArray()
                groups = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val lm = o.optJSONObject("last_message")
                    GInfo(o.optInt("id"), o.optString("name"), o.optInt("member_count"),
                        if (lm != null) GMsg(lm.optInt("id"), null, lm.optString("content"), lm.optString("msg_type"), lm.optString("created_at")) else null)
                }
            } catch (e: Exception) { err = "加载失败: ${e.message}" }
            finally { isLoading = false }
        }
    }

    fun loadMsgs(gid: Int) {
        scope.launch {
            try {
                val token = AuthState.token.value ?: return@launch
                val resp = SocialApiClient.get("/api/groups/$gid/messages?limit=50", token)
                val arr = resp.optJSONObject("data")?.optJSONArray("messages") ?: JSONArray()
                msgs = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val s = o.optJSONObject("sender")
                    GMsg(o.optInt("id"),
                        if (s != null) SocialApiClient.UserInfo(s.optInt("id"), s.optString("username"), s.optString("email"), s.optString("first_name",""), s.optString("last_name","")) else null,
                        o.optString("content"), o.optString("msg_type"), o.optString("created_at"))
                }
            } catch (e: Exception) { err = "加载消息失败: ${e.message}" }
        }
    }

    LaunchedEffect(Unit) { loadGroups() }

    if (selGroup != null) {
        val g = selGroup!!
        Scaffold(
            topBar = { TopAppBar(title = { Text(g.name, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = { selGroup = null; msgs = emptyList() }) { Icon(Icons.Default.ArrowBack, "返回") } }, actions = { IconButton(onClick = { loadMsgs(g.id) }) { Icon(Icons.Default.Refresh, "刷新") } }) }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(msgs) { msg ->
                        val me = msg.sender?.id == AuthState.currentUserId.value
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
                            if (!me) Text(msg.sender?.firstName ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                            Card(
                                modifier = Modifier.widthIn(max = 280.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = if (me) Color(0xFFDCF8C6) else Color.White),
                                onClick = { tts.language = Locale.CHINESE; tts.speak("${msg.sender?.firstName ?: ""} 说: ${msg.content}", TextToSpeech.QUEUE_FLUSH, null, "tts_${msg.id}") }
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(msg.content, fontSize = 14.sp)
                                    if (msg.msgType == "location") Text("📍 位置", fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, { input = it }, placeholder = { Text("输入消息...") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (input.isNotBlank()) scope.launch {
                            try {
                                SocialApiClient.post("/api/groups/${g.id}/message", JSONObject().apply { put("content", input.trim()); put("msg_type","text") }, AuthState.token.value ?: return@launch)
                                input = ""; loadMsgs(g.id)
                            } catch (e: Exception) { err = "发送失败: ${e.message}" }
                        }
                    }) { Icon(Icons.Default.Send, "发送", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    } else {
        Scaffold(
            topBar = { TopAppBar(title = { Text("群组", fontWeight = FontWeight.Bold) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) },
            floatingActionButton = { FloatingActionButton(onClick = { showCreate = true }) { Icon(Icons.Default.Add, "创建") } }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
                if (isLoading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else if (groups.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("暂无群组", fontSize = 18.sp); TextButton(onClick = { showCreate = true }) { Text("创建第一个群组") } } }
                else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groups) { g ->
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), onClick = { selGroup = g; loadMsgs(g.id) }) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Group, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) { Text(g.name, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text("${g.memberCount} 人", fontSize = 13.sp, color = Color.Gray); g.lastMsg?.let { Text(it.content.take(40), fontSize = 12.sp, color = Color.Gray) } }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        var gname by remember { mutableStateOf("") }
        val friends = SocialState.friends.collectAsState()
        var sel by remember { mutableStateOf<Set<Int>>(emptySet()) }
        AlertDialog(onDismissRequest = { showCreate = false }, title = { Text("创建群组") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(gname, { gname = it }, label = { Text("群名称") }, singleLine = true)
                Text("选择成员:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                LazyColumn(Modifier.height(200.dp)) {
                    items(friends.value) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = f.user.id in sel, onCheckedChange = { if (it) sel = sel + f.user.id else sel = sel - f.user.id })
                            Text("${f.user.firstName} ${f.user.lastName}")
                        }
                    }
                }
            }
        }, confirmButton = {
            Button(onClick = {
                if (gname.isNotBlank()) scope.launch {
                    try {
                        SocialApiClient.post("/api/groups/create", JSONObject().apply { put("name", gname.trim()); put("member_ids", JSONArray(sel.toList())) }, AuthState.token.value ?: return@launch)
                        showCreate = false; loadGroups()
                    } catch (e: Exception) { err = "创建失败: ${e.message}" }
                }
            }) { Text("创建") }
        }, dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } })
    }
}
