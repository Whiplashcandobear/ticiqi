package com.example.teleprompter.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.teleprompter.data.LocalStore
import com.example.teleprompter.domain.model.ScriptDocument
import com.example.teleprompter.util.ceilDurationSeconds
import com.example.teleprompter.util.formatDurationSeconds

@Composable
fun HomeScreen(
    store: LocalStore,
    onNew: () -> Unit,
    onEdit: (Long) -> Unit,
    onSettings: (Long) -> Unit,
    onPlay: (Long) -> Unit
) {
    var scripts by remember { mutableStateOf(store.loadScripts()) }
    var deleteId by remember { mutableLongStateOf(0L) }
    val recent = scripts.sortedByDescending { it.updatedAt }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("演讲提词器", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("让每一次表达都更从容", color = MaterialTheme.colorScheme.secondary)

            Button(onClick = { if (recent.isEmpty()) onNew() else onPlay(recent.first().id) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text(if (recent.isEmpty()) "开始提词" else "继续最近台本", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onNew, modifier = Modifier.weight(1f).height(56.dp)) { Text("＋ 新建台本") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { recent.firstOrNull()?.let { onSettings(it.id) } },
                    enabled = recent.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text("播放设置") }
            }

            Text("最近台本", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            if (recent.isEmpty()) {
                Text("还没有台本，点击“新建台本”粘贴你的演讲稿。", color = MaterialTheme.colorScheme.secondary)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recent, key = { it.id }) { script ->
                        ScriptCard(
                            script = script,
                            onEdit = { onEdit(script.id) },
                            onPlay = { onPlay(script.id) },
                            onDelete = { deleteId = script.id }
                        )
                    }
                }
            }
        }
    }

    if (deleteId != 0L) {
        AlertDialog(
            onDismissRequest = { deleteId = 0L },
            title = { Text("删除台本？") },
            text = { Text("删除后无法恢复，但不会影响其他台本。") },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteScript(deleteId)
                    scripts = store.loadScripts()
                    deleteId = 0L
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteId = 0L }) { Text("取消") } }
        )
    }
}

@Composable
private fun ScriptCard(script: ScriptDocument, onEdit: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(script.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("${script.wordCount} words · ${formatDurationSeconds(ceilDurationSeconds(script.wordCount, 120))}", color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) { Text("提词") }
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
        }
    }
}
