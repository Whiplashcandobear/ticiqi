package com.example.teleprompter.presentation.playback

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.teleprompter.data.LocalStore
import com.example.teleprompter.domain.model.AccentColor
import com.example.teleprompter.domain.model.DisplaySettings
import com.example.teleprompter.domain.model.FontScale
import com.example.teleprompter.domain.model.PromptMode
import com.example.teleprompter.domain.model.SpeechUnit
import com.example.teleprompter.domain.model.ThemeMode
import com.example.teleprompter.domain.playback.autoScrollStartIndex
import com.example.teleprompter.domain.playback.countdownValues
import com.example.teleprompter.domain.playback.playbackUnits
import com.example.teleprompter.domain.playback.PlaybackControlsArrangement
import com.example.teleprompter.domain.playback.playbackControlsArrangement
import com.example.teleprompter.domain.playback.positionForFraction
import com.example.teleprompter.domain.playback.segmentDurationSeconds
import com.example.teleprompter.domain.playback.stepRate
import com.example.teleprompter.domain.voice.VoiceFollowEngine
import com.example.teleprompter.domain.voice.VoiceFollowState
import com.example.teleprompter.overlay.OverlayService
import com.example.teleprompter.presentation.theme.TeleprompterTheme
import com.example.teleprompter.presentation.theme.accentColor
import com.example.teleprompter.presentation.theme.liveHighlightColor
import com.example.teleprompter.util.formatDurationSeconds
import com.example.teleprompter.voice.AndroidSpeechRecognizer
import kotlinx.coroutines.delay

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlaybackScreen(store: LocalStore, scriptId: Long, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val document = remember(scriptId) { store.loadScripts().firstOrNull { it.id == scriptId } }
    if (document == null) {
        Text("没有找到这个台本")
        return
    }

    var settings by remember { mutableStateOf(store.loadSettings()) }
    val units = remember(document.rawText) { playbackUnits(document.rawText) }
    var currentIndex by remember { mutableIntStateOf(document.lastPlaybackUnit.coerceIn(0, (units.size - 1).coerceAtLeast(0))) }
    var progress by remember { mutableFloatStateOf(document.lastPlaybackProgress.coerceIn(0f, 1f)) }
    var isPlaying by remember { mutableStateOf(settings.countdownSeconds == 0) }
    var countdown by remember { mutableIntStateOf(settings.countdownSeconds) }
    var countdownCycle by remember { mutableIntStateOf(0) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var confirmRestart by rememberSaveable { mutableStateOf(false) }
    var audioPermission by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var voiceUnavailable by remember {
        mutableStateOf(settings.promptMode == PromptMode.VOICE_FOLLOW && !audioPermission)
    }
    var voiceStatus by rememberSaveable { mutableStateOf("") }
    val voiceEngine = remember(units) { VoiceFollowEngine(units, currentIndex) }
    var voiceState by remember(voiceEngine) { mutableStateOf(voiceEngine.state()) }
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestProgress by rememberUpdatedState(progress)

    fun applyVoiceState(next: VoiceFollowState) {
        voiceState = next
        if (!next.isFallbackToWpm && next.hasStableMatch) {
            currentIndex = next.currentUnitIndex.coerceIn(0, (units.size - 1).coerceAtLeast(0))
            progress = next.characterProgress.coerceIn(0f, 1f)
        }
        if (next.isFallbackToWpm) voiceStatus = "识别中断，固定 字/分 兜底"
        else if (next.hasStableMatch) voiceStatus = "语音跟随中"
    }

    val requestAudioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        audioPermission = granted
        if (!granted) {
            voiceUnavailable = true
            voiceStatus = "未开启麦克风，固定 字/分 兜底"
        }
    }

    val speechRecognizer = remember(voiceEngine) {
        AndroidSpeechRecognizer(
            context = context,
            listener = object : AndroidSpeechRecognizer.Listener {
                override fun onText(text: String, isFinal: Boolean) {
                    applyVoiceState(voiceEngine.onRecognition(text, System.currentTimeMillis()))
                }

                override fun onUnavailable(reason: String) {
                    voiceUnavailable = true
                    voiceStatus = "$reason，固定 字/分 兜底"
                }

                override fun onError(code: Int) {
                    if (code == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        voiceUnavailable = true
                        voiceStatus = "麦克风权限不可用，固定 字/分 兜底"
                    }
                }
            }
        )
    }

    fun nudgeSpeed(delta: Int) {
        val next = stepRate(settings.speed, delta)
        if (next != settings.speed) {
            settings = settings.copy(speed = next)
            store.saveSettings(settings)
        }
    }

    BackHandler(onBack = onExit)

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            store.saveScript(document.copy(lastPlaybackUnit = latestIndex, lastPlaybackProgress = latestProgress))
        }
    }

    DisposableEffect(speechRecognizer) {
        onDispose { speechRecognizer.close() }
    }

    DisposableEffect(settings.landscape) {
        activity?.requestedOrientation = if (settings.landscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        onDispose { }
    }

    LaunchedEffect(countdownCycle) {
        val sequence = countdownValues(settings.countdownSeconds)
        countdown = sequence.first()
        isPlaying = countdown == 0
        sequence.drop(1).forEach { nextValue ->
            delay(1000)
            countdown = nextValue
        }
        isPlaying = true
    }

    LaunchedEffect(settings.promptMode, isPlaying, countdown, audioPermission) {
        if (settings.promptMode != PromptMode.VOICE_FOLLOW || !isPlaying || countdown > 0) {
            speechRecognizer.stop()
            return@LaunchedEffect
        }
        if (!audioPermission) {
            voiceUnavailable = true
            voiceStatus = "需要麦克风权限，固定 字/分 兜底"
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return@LaunchedEffect
        }
        voiceUnavailable = false
        voiceEngine.setCursor(currentIndex, progress)
        voiceEngine.startSession()
        applyVoiceState(voiceEngine.state())
        if (!speechRecognizer.start()) {
            voiceUnavailable = true
            voiceStatus = "语音识别不可用，固定 字/分 兜底"
        }
    }

    LaunchedEffect(settings.promptMode, isPlaying, countdown) {
        if (settings.promptMode != PromptMode.VOICE_FOLLOW || !isPlaying || countdown > 0) return@LaunchedEffect
        while (isPlaying && countdown == 0) {
            delay(250)
            applyVoiceState(voiceEngine.onTick(System.currentTimeMillis()))
        }
    }

    LaunchedEffect(isPlaying, currentIndex, settings.speed, settings.promptMode, voiceState.isFallbackToWpm, voiceUnavailable) {
        val fixedPlaybackEnabled = settings.promptMode == PromptMode.FIXED_WPM || voiceState.isFallbackToWpm || voiceUnavailable
        if (!isPlaying || !fixedPlaybackEnabled || units.isEmpty() || currentIndex !in units.indices) return@LaunchedEffect
        val duration = segmentDurationSeconds(units[currentIndex], settings.speed)
        while (isPlaying) {
            delay(100)
            progress += (0.1f / duration.toFloat())
            if (progress >= 1f) {
                progress = 0f
                if (currentIndex < units.lastIndex) currentIndex += 1 else isPlaying = false
            }
        }
    }

    TeleprompterTheme(settings) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(document.title, maxLines = 1) },
                    navigationIcon = { TextButton(onClick = onExit) { Text("‹") } },
                    actions = { TextButton(onClick = { showMore = true }) { Text("更多") } }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (countdown > 0) "倒计时 00:0$countdown"
                        else if (settings.promptMode == PromptMode.VOICE_FOLLOW && voiceStatus.isNotBlank()) voiceStatus
                        else if (isPlaying) "正在提词" else "已暂停",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 12.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                                when {
                                    settings.promptMode == PromptMode.FIXED_WPM -> "固定 ${settings.speed} 字/分"
                                    voiceUnavailable || voiceState.isFallbackToWpm -> "固定 字/分 兜底"
                                    else -> "语音跟随"
                                },
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 11.sp
                        )
                        TextButton(
                            onClick = { nudgeSpeed(-10) },
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp)
                        ) { Text("−10", fontSize = 10.sp) }
                        TextButton(
                            onClick = { nudgeSpeed(+10) },
                            contentPadding = PaddingValues(horizontal = 3.dp, vertical = 0.dp)
                        ) { Text("+10", fontSize = 10.sp) }
                        Text("· ${if (settings.landscape) "横屏" else "竖屏"}", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                    }
                }

                if (countdown > 0) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(countdown.toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold, color = accentColor(settings))
                    }
                } else {
                    PlaybackTextWindow(
                        units = units,
                        currentIndex = currentIndex,
                        settings = settings,
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }

                val currentFraction = if (units.isEmpty()) 0f else {
                    ((currentIndex + progress) / units.size.toFloat()).coerceIn(0f, 1f)
                }
                val onSeek: (Float) -> Unit = { fraction ->
                    if (units.isNotEmpty()) {
                        val position = positionForFraction(fraction, units.size)
                        currentIndex = position.index
                        progress = position.progress
                        voiceEngine.setCursor(position.index, position.progress)
                        voiceState = voiceEngine.state()
                    }
                }
                val onTogglePlay = { isPlaying = !isPlaying }

                if (playbackControlsArrangement(settings.landscape) == PlaybackControlsArrangement.COMPACT) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Slider(
                            value = currentFraction,
                            onValueChange = onSeek,
                            enabled = units.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(28.dp)
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${if (units.isEmpty()) 0 else currentIndex + 1}/${units.size}句",
                                color = MaterialTheme.colorScheme.secondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                remainingTime(units, currentIndex, progress, settings.speed),
                                color = MaterialTheme.colorScheme.secondary,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Button(
                            onClick = onTogglePlay,
                            modifier = Modifier.width(72.dp).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Text(if (isPlaying) "暂停" else "继续", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { confirmRestart = true },
                            modifier = Modifier.width(64.dp).height(40.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("从头", fontSize = 12.sp) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Slider(
                            value = currentFraction,
                            onValueChange = onSeek,
                            enabled = units.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().height(28.dp)
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("第 ${if (units.isEmpty()) 0 else currentIndex + 1} / ${units.size} 句", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                            Text("预计剩余 ${remainingTime(units, currentIndex, progress, settings.speed)}", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onTogglePlay,
                                modifier = Modifier.weight(1f).height(56.dp)
                            ) {
                                Text(if (isPlaying) "暂停" else "继续", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { confirmRestart = true },
                                modifier = Modifier.width(100.dp).height(56.dp)
                            ) { Text("从头", fontSize = 16.sp) }
                        }
                    }
                }
            }
        }
    }

    if (showMore) {
        PlaybackMoreDialog(
            settings = settings,
            onSettings = { next -> settings = next; store.saveSettings(next) },
            onStartOverlay = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                    Toast.makeText(context, "请允许悬浮窗权限，返回后再次点击开启", Toast.LENGTH_LONG).show()
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                } else if (settings.promptMode == PromptMode.VOICE_FOLLOW && !audioPermission) {
                    Toast.makeText(context, "实时语音跟随需要麦克风权限，已保持固定 字/分", Toast.LENGTH_LONG).show()
                    requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    OverlayService.start(context, scriptId, currentIndex, progress)
                }
            },
            onDismiss = { showMore = false }
        )
    }
    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("从头开始？") },
            text = { Text("当前播放位置会回到第 1 句，并重新执行倒计时。") },
            confirmButton = {
                TextButton(onClick = {
                    currentIndex = 0
                    progress = 0f
                    voiceEngine.setCursor(0, 0f)
                    voiceEngine.startSession()
                    voiceState = voiceEngine.state()
                    isPlaying = false
                    countdownCycle += 1
                    confirmRestart = false
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun PlaybackTextWindow(
    units: List<SpeechUnit>,
    currentIndex: Int,
    settings: DisplaySettings,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val size = when (settings.fontScale) {
        FontScale.SMALL -> 24.sp
        FontScale.MEDIUM -> 28.sp
        FontScale.LARGE -> 34.sp
        FontScale.EXTRA_LARGE -> 40.sp
    }
    val accent = accentColor(settings)
    val live = liveHighlightColor(settings)
    val listState = rememberLazyListState()
    LaunchedEffect(currentIndex, units.size, settings.landscape) {
        if (units.isNotEmpty() && currentIndex in units.indices) {
            val contextItems = if (settings.landscape) 1 else 2
            listState.animateScrollToItem(
                autoScrollStartIndex(units, currentIndex, contextItems)
            )
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = if (settings.landscape) 8.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(units, key = { index, _ -> index }) { index, unit ->
            val current = index == currentIndex
            Text(
                text = if (current) {
                    buildAnnotatedString {
                        val visibleCharacters = (unit.rawText.length * progress)
                            .toInt()
                            .coerceIn(0, unit.rawText.length)
                        withStyle(SpanStyle(color = live)) {
                            append(unit.rawText.substring(0, visibleCharacters))
                        }
                        withStyle(SpanStyle(color = accent)) {
                            append(unit.rawText.substring(visibleCharacters))
                        }
                    }
                } else {
                    buildAnnotatedString { append(unit.rawText) }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp).then(
                    if (current) Modifier.background(accent.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 9.dp) else Modifier
                ),
                color = if (current) accent else MaterialTheme.colorScheme.secondary.copy(alpha = 0.88f),
                fontSize = if (current) size else size * 0.72f,
                lineHeight = if (current) size * 1.35f else size * 1.25f,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun PlaybackMoreDialog(
    settings: DisplaySettings,
    onSettings: (DisplaySettings) -> Unit,
    onStartOverlay: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("显示设置") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("字号")
                CompactChoices(FontScale.entries.toList(), settings.fontScale, { it.label() }) { onSettings(settings.copy(fontScale = it)) }
                Text("背景")
                CompactChoices(ThemeMode.entries.toList(), settings.themeMode, { if (it == ThemeMode.DARK) "深色" else "浅色" }) { onSettings(settings.copy(themeMode = it)) }
                Text("字体颜色")
                CompactChoices(AccentColor.entries.toList(), settings.accentColor, { it.label() }) { onSettings(settings.copy(accentColor = it)) }
                Text("播放方式")
                CompactChoices(PromptMode.entries.toList(), settings.promptMode, { it.label() }) { onSettings(settings.copy(promptMode = it)) }
                Text("方向")
                CompactChoices(listOf(false, true), settings.landscape, { if (it) "横屏" else "竖屏" }) { onSettings(settings.copy(landscape = it)) }
                Text("单机使用")
                Button(onClick = onStartOverlay, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Text("开启悬浮提词")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun <T> CompactChoices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            if (option == selected) {
                Button(
                    onClick = { onSelect(option) },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text(label(option), fontSize = 11.sp, maxLines = 1, softWrap = false) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(option) },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) { Text(label(option), fontSize = 11.sp, maxLines = 1, softWrap = false) }
            }
        }
    }
}

private fun remainingTime(units: List<SpeechUnit>, index: Int, progress: Float, rate: Int): String {
    if (units.isEmpty()) return "00:00"
    val current = (segmentDurationSeconds(units[index], rate) * (1f - progress)).toInt()
    val rest = units.drop(index + 1).sumOf { segmentDurationSeconds(it, rate).toInt() }
    return formatDurationSeconds(current + rest)
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

private fun PromptMode.label() = when (this) {
    PromptMode.FIXED_WPM -> "固定速度"
    PromptMode.VOICE_FOLLOW -> "语音跟随"
}
