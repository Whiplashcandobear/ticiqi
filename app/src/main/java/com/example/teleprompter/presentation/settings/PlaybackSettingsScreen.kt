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
import com.example.teleprompter.domain.model.AsrMode
import com.example.teleprompter.domain.model.CloudAsrConfig
import com.example.teleprompter.domain.model.DisplaySettings
import com.example.teleprompter.domain.model.FontScale
import com.example.teleprompter.domain.model.PromptMode
import com.example.teleprompter.domain.model.ThemeMode
import com.example.teleprompter.asr.LocalModelCatalog
import com.example.teleprompter.domain.parser.countReadingUnits
import com.example.teleprompter.domain.playback.calibratedRate
import com.example.teleprompter.domain.playback.calibrationSample
import com.example.teleprompter.util.ceilDurationSeconds
import com.example.teleprompter.util.formatDurationSeconds
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlaybackSettingsScreen(
    store: LocalStore,
    scriptId: Long,
    onBack: () -> Unit,
    onStart: (Long) -> Unit,
    onStartShooting: (Long) -> Unit,
    onFullscreen: (Long) -> Unit
) {
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
    val calibrationUnits = remember(calibrationText) { countReadingUnits(calibrationText) }

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
            Text("${countReadingUnits(script.rawText)} 字 · 预计 ${formatDurationSeconds(ceilDurationSeconds(countReadingUnits(script.rawText), settings.speed))}", color = MaterialTheme.colorScheme.secondary)

            SettingTitle("播放方式")
            ChoiceRow(PromptMode.entries.toList(), settings.promptMode, { it.label() }) { update(settings.copy(promptMode = it)) }
            Text(
                if (settings.promptMode == PromptMode.VOICE_FOLLOW) {
                    "实时语音跟随：说到哪滚到哪；回头重读会自动滚回去，说与台本无关的话会原地等待；识别引擎不可用时才回固定速度。"
                } else {
                    "固定速度：按照设定字/分播放，适合环境嘈杂或不使用麦克风时。"
                },
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 13.sp
            )

            if (settings.promptMode == PromptMode.VOICE_FOLLOW) {
                SettingTitle("识别引擎")
                ChoiceRow(AsrMode.entries.toList(), settings.asrMode, { it.label() }) { update(settings.copy(asrMode = it)) }
                when (settings.asrMode) {
                    AsrMode.SYSTEM -> Text(
                        "使用手机自带语音识别。零下载、可离线，但精度一般，适合快速上手。",
                        color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp
                    )
                    AsrMode.LOCAL -> {
                        SettingTitle("本地模型")
                        val modelIds = LocalModelCatalog.entries.map { it.id }
                        ChoiceRow(modelIds, settings.localModelId, { LocalModelCatalog.get(it).label }) { update(settings.copy(localModelId = it)) }
                        val lm = LocalModelCatalog.get(settings.localModelId)
                        Text(lm.note, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
                        if (lm.bundledAssetDir == null) {
                            Text(
                                "首次使用需下载约 ${lm.approxSizeMb}MB（下载后离线可用，建议在 Wi-Fi 下）。",
                                color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp
                            )
                        }
                    }
                    AsrMode.CLOUD -> {
                        SettingTitle("云端识别接口")
                        CloudConfigEditor(settings.cloudConfig) { update(settings.copy(cloudConfig = it)) }
                        Text(
                            "把你自己的云端识别 API 填进来：每 ~1 秒把 16k/16bit 音频按模板发送，再从返回 JSON 中按路径取文本。",
                            color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp
                        )
                    }
                }
            }

            SettingTitle("滚动速度")
            ChoiceRow(listOf(120, 160, 200, 240, 280, 320, settings.speed).distinct().sorted(), settings.speed, { it.toString() }) { update(settings.copy(speed = it)) }

            SettingTitle("语速校准")
            Text(
                "请按真实演讲速度完整读完下面的片段，得到适合你的平均字/分。",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 13.sp
            )
            if (calibrationText.isBlank()) {
                Text("当前台本没有可用于校准的内容。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(calibrationText, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurface)
                }
                Text("校准片段：$calibrationUnits 字", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
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
                                val result = calibratedRate(calibrationUnits, elapsed)
                                update(settings.copy(speed = result))
                                calibrationElapsedMillis = elapsed
                                calibrationMessage = "已校准为 $result 字/分"
                            }
                        }
                    },
                    enabled = calibrationUnits > 0,
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

            Text("播放中可用 −10 / +10 字/分 即时微调，当前句会继续播放且不会跳回；你仍可上下滚动浏览全文。", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("提词类型", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            // 悬浮提词：只显示提词悬浮窗
            Button(onClick = { onStart(script.id) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("悬浮提词", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            // 拍摄提词：拉起悬浮窗的同时打开全屏取景；不自动开录，由用户点底部红点开始
            OutlinedButton(onClick = { onStartShooting(script.id) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("拍摄提词（同时打开相机）", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onFullscreen(script.id) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("全屏提词", fontSize = 14.sp)
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
    PromptMode.FIXED_WPM -> "固定速度"
    PromptMode.VOICE_FOLLOW -> "实时语音跟随"
}

private fun AsrMode.label() = when (this) {
    AsrMode.SYSTEM -> "系统识别"
    AsrMode.LOCAL -> "本地模型"
    AsrMode.CLOUD -> "云端API"
}

@Composable
private fun CloudConfigEditor(config: CloudAsrConfig, onUpdate: (CloudAsrConfig) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = config.endpoint,
            onValueChange = { onUpdate(config.copy(endpoint = it)) },
            label = { Text("接口地址 (endpoint)") },
            placeholder = { Text("https://your-asr.example.com/recognize") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = config.apiKey,
            onValueChange = { onUpdate(config.copy(apiKey = it)) },
            label = { Text("API Key（可选）") },
            placeholder = { Text("留空则不带 Authorization 头") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = config.resultPath,
            onValueChange = { onUpdate(config.copy(resultPath = it)) },
            label = { Text("返回文本 JSON 路径") },
            placeholder = { Text("如 text 或 result.text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "请求体默认：{\"audio\":\"{base64}\",\"sample_rate\":16000,\"format\":\"wav\"}；其中 {base64} 为当前音频块的 Base64。",
            color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp
        )
    }
}
