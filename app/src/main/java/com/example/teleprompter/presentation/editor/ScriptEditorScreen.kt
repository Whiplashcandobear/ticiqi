package com.example.teleprompter.presentation.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.teleprompter.data.LocalStore
import com.example.teleprompter.domain.model.ScriptDocument
import com.example.teleprompter.domain.parser.countWords
import com.example.teleprompter.util.ceilDurationSeconds
import com.example.teleprompter.util.formatDurationSeconds

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ScriptEditorScreen(store: LocalStore, scriptId: Long, onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val existing = remember(scriptId) { store.loadScripts().firstOrNull { it.id == scriptId } }
    var title by rememberSaveable(scriptId) { mutableStateOf(existing?.title ?: "未命名演讲") }
    var text by rememberSaveable(scriptId) { mutableStateOf(existing?.rawText ?: "") }
    val clipboard = LocalClipboardManager.current
    val words = remember(text) { countWords(text) }
    val wpm = remember { store.loadSettings().wpm }
    val duration = remember(words, wpm) { formatDurationSeconds(ceilDurationSeconds(words, wpm)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑台本") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("台本标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "会保留原文内容；句号后的换行和空行会保留，句子中间因复制产生的换行在播放时会自动合并。输入框里的折行只是视觉效果。",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 13.sp
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("演讲稿内容") },
                minLines = 15,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { clipboard.getText()?.text?.let { if (it.isNotBlank()) text = it } },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("＋ 粘贴内容") }
                OutlinedButton(onClick = { text = "" }, modifier = Modifier.weight(1f).height(52.dp)) { Text("清空") }
            }
            Text("$words words · 预计 $duration（$wpm WPM）", fontWeight = FontWeight.Medium)
            if (text.isNotBlank()) {
                Text("播放预览", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(text, lineHeight = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val saved = store.saveScript(
                        ScriptDocument(id = scriptId, title = title, rawText = text, wordCount = words)
                    )
                    onSaved(saved.id)
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("保存并开始", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(20.dp))
        }
    }
}
