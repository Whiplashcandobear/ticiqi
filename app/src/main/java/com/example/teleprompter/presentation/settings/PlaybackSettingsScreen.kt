package com.example.teleprompter.presentation.settings

import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.teleprompter.data.LocalStore
import com.example.teleprompter.domain.model.AccentColor
import com.example.teleprompter.domain.model.DisplaySettings
import com.example.teleprompter.domain.model.FontScale
import com.example.teleprompter.domain.model.PromptMode
import com.example.teleprompter.domain.model.ThemeMode
import com.example.teleprompter.domain.parser.countWords
import com.example.teleprompter.domain.playback.calibratedWpm
import com.example.teleprompter.domain.playback.calibrationSample
import com.example.teleprompter.util.ceilDurationSeconds
import com.example.teleprompter.util.formatDurationSeconds
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlaybackSettingsScreen(store: LocalStore, scriptId: Long, onBack: () -> Unit, onStart: (Long) -> Unit) {
    val script = remember(scriptId) { store.loadScripts().firstOrNull { it.id == scriptId } }
    var settings by remember { mutableStateOf(store.loadSettings()) }
    if (script == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("没有找到这个台本")
            TextButton(onClick = onBack) { Text("返回") }
        }
        return
    }

    var calibrationRunning by rememberSaveable { mutableStateOf(false) }
    var calibrationStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var calibrationElapsedMillis by rememberSaveable { mutableLongStateOf(0L) }
    var calibrationMessage by rememberSaveable { mutableStateOf("") }
    val calibrationText = remember(script.rawText) { calibrationSample(script.rawText) }
    val calibrationWordCount = remember(calibrationText) { countWords(calibrationText) }

    LaunchedEffect(calibrationRunning) {
        while (calibrationRunning) {
            calibrationElapsedMillis = (SystemClock.elapsedRealtime() - calibrationStartedAt).coerceAtLeast(0L)
            delay(100)
        }
    }

    fun update(next: DisplaySettings) {
        settings = next
        store.saveSettings(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("播放设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(script.title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("${script.wordCount} words · 预计 ${formatDurationSeconds(ceilDurationSeconds(script.wordCount, settings.wpm))}", color = MaterialTheme.colorScheme.secondary)

            SettingTitle("播放方式")
            ChoiceRow(PromptMode.entries.toList(), settings.promptMode, { it.label() }) { update(settings.copy(promptMode = it)) }
            Text(
                if (settings.promptMode == PromptMode.VOICE_FOLLOW) {
                    "实时语音跟随：按句匹配你的英语语音；识别中断约 2 秒会自动回到固定 WPM。"
                } else {
                    "固定 WPM：按照设定速度播放，适合环境嘈杂或不使用麦克风时。"
                },
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 13.sp
            )

            SettingTitle("滚动速度")
            ChoiceRow(listOf(80, 100, 120, 140, 160, settings.wpm).distinct().sorted(), settings.wpm, { it.toString() }) { update(settings.copy(wpm = it)) }

            SettingTitle("语速校准")
            Text(
                "请按真实演讲速度完整读完下面的片段，得到适合你的平均 WPM。",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 13.sp
            )
            if (calibrationText.isBlank()) {
                Text("当前台本没有可用于校准的英文内容。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(calibrationText, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Text("校准片段：$calibrationWordCount 词", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                Text(
                    "已用时：${"%.1f".format(calibrationElapsedMillis / 1000.0)} 秒",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp
                )
                Button(
                    onClick = {
                        if (!calibrationRunning) {
                            calibrationStartedAt = SystemClock.elapsedRealtime()
                            calibrationElapsedMillis = 0L
                            calibrationMessage = ""
                            calibrationRunning = true
                        } else {
                            val elapsed = SystemClock.elapsedRealtime() - calibrationStartedAt
                            calibrationRunning = false
                            if (elapsed < 1_000L) {
                                calibrationMessage = "计时太短，请重新完整读完片段。"
                            } else {
                                val result = calibratedWpm(calibrationWordCount, elapsed)
                                update(settings.copy(wpm = result))
                                calibrationElapsedMillis = elapsed
                                calibrationMessage = "已校准为 $result WPM"
                            }
                        }
                    },
                    enabled = calibrationWordCount > 0,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(if (calibrationRunning) "完成校准" else "开始校准")
                }
                if (calibrationMessage.isNotBlank()) {
                    Text(calibrationMessage, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                }
            }

            SettingTitle("字号")
            ChoiceRow(FontScale.entries, settings.fontScale, { it.label() }) { update(settings.copy(fontScale = it)) }

            SettingTitle("字体颜色")
            ChoiceRow(AccentColor.entries, settings.accentColor, { it.label() }) { update(settings.copy(accentColor = it)) }

            SettingTitle("背景")
            ChoiceRow(ThemeMode.entries, settings.themeMode, { it.label() }) { update(settings.copy(themeMode = it)) }

            SettingTitle("倒计时")
            ChoiceRow(listOf(0, 3, 5, 10), settings.countdownSeconds, { if (it == 0) "关闭" else "$it 秒" }) { update(settings.copy(countdownSeconds = it)) }

            SettingTitle("初始方向")
            ChoiceRow(listOf(false, true), settings.landscape, { if (it) "横屏" else "竖屏" }) { update(settings.copy(landscape = it)) }

            Text("播放中可用 −5 / +5 WPM 即时微调，当前句会继续播放且不会跳回；你仍可上下滚动浏览全文。", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Button(onClick = { onStart(script.id) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("开始提词", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SettingTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            if (option == selected) {
                Button(
                    onClick = { onSelect(option) },
                    modifier = Modifier.widthIn(min = 58.dp).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(label(option), fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(option) },
                    modifier = Modifier.widthIn(min = 58.dp).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(label(option), fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private fun FontScale.label() = when (this) {
    FontScale.SMALL -> "小"
    FontScale.MEDIUM -> "中"
    FontScale.LARGE -> "大"
    FontScale.EXTRA_LARGE -> "特大"
}

private fun AccentColor.label() = when (this) {
    AccentColor.BLUE -> "晨蓝"
    AccentColor.AMBER -> "暖黄"
    AccentColor.MINT -> "薄荷绿"
}

private fun ThemeMode.label() = if (this == ThemeMode.DARK) "深色" else "浅色"

private fun PromptMode.label() = when (this) {
    PromptMode.FIXED_WPM -> "固定 WPM"
    PromptMode.VOICE_FOLLOW -> "实时语音跟随"
}
