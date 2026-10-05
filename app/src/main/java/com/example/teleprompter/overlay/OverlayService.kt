package com.example.teleprompter.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.teleprompter.MainActivity
import com.example.teleprompter.data.LocalStore
import com.example.teleprompter.domain.model.AccentColor
import com.example.teleprompter.domain.model.DisplaySettings
import com.example.teleprompter.domain.model.FontScale
import com.example.teleprompter.domain.model.PromptMode
import com.example.teleprompter.domain.model.ScriptDocument
import com.example.teleprompter.domain.model.SpeechUnit
import com.example.teleprompter.domain.model.ThemeMode
import com.example.teleprompter.domain.overlay.stepOverlayFontScale
import com.example.teleprompter.domain.playback.playbackUnits
import com.example.teleprompter.domain.playback.positionForFraction
import com.example.teleprompter.domain.playback.segmentDurationSeconds
import com.example.teleprompter.domain.playback.stepRate
import com.example.teleprompter.domain.voice.VoiceFollowState
import com.example.teleprompter.asr.VoiceFollowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: LocalStore
    private lateinit var windowManager: WindowManager
    private var overlayRoot: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var script: ScriptDocument? = null
    private var settings = DisplaySettings()
    private var units = emptyList<SpeechUnit>()
    private var currentIndex = 0
    private var progress = 0f
    private var isPlaying = true
    private var playbackJob: Job? = null
    private var seekBar: SeekBar? = null
    private var seekUpdating = false
    private var transcriptContainer: LinearLayout? = null
    private var transcriptScroll: ScrollView? = null
    private val lineViews = mutableListOf<TextView>()
    private var playButton: TextView? = null
    private var statusText: TextView? = null
    private var controller: VoiceFollowController? = null
    private var voiceState: VoiceFollowState? = null
    private var voiceUnavailable = false
    private var voiceEngineStatus = ""
    // 内置录像：悬浮提词 + 语音跟随的同时用前摄录像（同 UID 允许并发采集麦克风）
    private var recorder: OverlayRecorder? = null
    private var recordButton: TextView? = null
    // 全屏取景预览（飓风式）：独立的全屏窗口铺在提词窗口之下，
    // 提词窗口保持原大小/位置浮在上层，缩放/拖动互不影响
    private var previewWindow: FrameLayout? = null
    private var previewView: TextureView? = null
    private var previewMode = false
    private var previewAttached = false
    private var pendingRecordStart = false
    private var collapseButton: TextView? = null
    // 连续滚动跟随：用户手动滚动后暂停自动跟随一段时间
    private var lastUserScrollAt = 0L
    private var lastScrollTarget = -1

    override fun onCreate() {
        super.onCreate()
        store = LocalStore(applicationContext)
        settings = store.loadSettings()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val scriptId = intent?.getLongExtra(EXTRA_SCRIPT_ID, 0L) ?: 0L
        val document = store.loadScripts().firstOrNull { it.id == scriptId }
        if (document == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        script = document
        settings = store.loadSettings()
        units = playbackUnits(document.rawText)
        currentIndex = (intent?.getIntExtra(EXTRA_INDEX, document.lastPlaybackUnit)
            ?: document.lastPlaybackUnit)
            .coerceIn(0, (units.size - 1).coerceAtLeast(0))
        progress = (intent?.getFloatExtra(EXTRA_PROGRESS, document.lastPlaybackProgress)
            ?: document.lastPlaybackProgress).coerceIn(0f, 1f)
        isPlaying = true

        startVoiceFollowIfNeeded()
        startForegroundCompat()

        if (overlayRoot == null) showOverlay()
        else renderTranscript()
        startPlaybackLoop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        playbackJob?.cancel()
        controller?.stop()
        controller = null
        recorder?.releaseNow()
        recorder = null
        removePreviewWindow()
        savePlaybackPosition()
        overlayRoot?.let { root ->
            runCatching { windowManager.removeViewImmediate(root) }
        }
        overlayRoot = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        val frame = FrameLayout(this).apply {
            background = roundedBackground(surfaceColor(), 18)
            elevation = dp(8).toFloat()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 底部多留出缩放手柄的空间
            setPadding(dp(10), dp(8), dp(10), dp(26))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = label("⋮⋮", 18f, secondaryColor()).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(8), 0)
            setOnTouchListener(createDragListener())
        }
        header.addView(handle, LinearLayout.LayoutParams(dp(28), dp(38)))
        header.addView(label("悬浮提词", 15f, textColor()).apply {
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        collapseButton = label("收起", 13f, secondaryColor()).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { exitPreviewMode() }
        }
        header.addView(collapseButton, LinearLayout.LayoutParams(dp(52), dp(38)))
        header.addView(label("×", 24f, secondaryColor()).apply {
            gravity = Gravity.CENTER
            setOnClickListener { stopSelf() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(header)

        val seek = SeekBar(this).apply {
            max = 1000
            this.progress = overallProgress()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser || seekUpdating || units.isEmpty()) return
                    val position = positionForFraction(value / 1000f, units.size)
                    currentIndex = position.index
                    this@OverlayService.progress = position.progress
                    controller?.setCursor(position.index, position.progress)
                    refreshLineStyles()
                    updateStatus()
                    updateSeekBar()
                    lastScrollTarget = -1
                    updateScrollFollowing()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        seekBar = seek
        root.addView(seek, LinearLayout.LayoutParams(-1, dp(28)))

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(4))
            // 用户手动滚动时暂停自动跟随，松手后稍等片刻再接管
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        lastUserScrollAt = System.currentTimeMillis()
                }
                false
            }
        }
        val transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(transcript, LinearLayout.LayoutParams(-1, -2))
        transcriptScroll = scroll
        transcriptContainer = transcript
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(actionButton("字−") { changeFont(false) })
        controls.addView(actionButton("慢10") { changeSpeed(-10) })
        playButton = actionButton("暂停") {
            isPlaying = !isPlaying
            if (isPlaying) startVoiceFollowIfNeeded() else controller?.stop()
            updatePlayButton()
        }
        controls.addView(playButton)
        controls.addView(actionButton("快10") { changeSpeed(+10) })
        controls.addView(actionButton("字＋") { changeFont(true) })
        controls.addView(actionButton("色") { toggleTheme() })
        recordButton = actionButton("录像") { toggleRecording() }
        controls.addView(recordButton)
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(42)))

        statusText = label("", 11f, secondaryColor()).apply { gravity = Gravity.CENTER }
        root.addView(statusText, LinearLayout.LayoutParams(-1, dp(22)))

        frame.addView(root, FrameLayout.LayoutParams(-1, -1))

        // 右下角缩放手柄：按住拖动调整悬浮窗大小
        val grip = TextView(this).apply {
            text = "◢"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(secondaryColor())
            gravity = Gravity.CENTER
            setOnTouchListener(createResizeListener())
        }
        frame.addView(grip, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.BOTTOM or Gravity.END))

        overlayRoot = frame
        val params = WindowManager.LayoutParams(
            dp(settings.overlayWidthDp.coerceIn(MIN_OVERLAY_SIZE_DP, 720)),
            dp(settings.overlayHeightDp.coerceIn(MIN_OVERLAY_SIZE_DP, 720)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        overlayParams = params
        windowManager.addView(frame, params)
        renderTranscript()
    }

    private fun createResizeListener(): View.OnTouchListener = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startWidth = 0
        private var startHeight = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = overlayParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startWidth = params.width
                    startHeight = params.height
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val metrics = resources.displayMetrics
                    val maxW = (metrics.widthPixels * 0.94f).toInt()
                    val maxH = (metrics.heightPixels * 0.85f).toInt()
                    params.width = (startWidth + (event.rawX - downX).toInt())
                        .coerceIn(dp(MIN_OVERLAY_SIZE_DP), maxW)
                    params.height = (startHeight + (event.rawY - downY).toInt())
                        .coerceIn(dp(MIN_OVERLAY_SIZE_DP), maxH)
                    overlayRoot?.let { windowManager.updateViewLayout(it, params) }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persistOverlaySize(params.width, params.height)
                    return true
                }
            }
            return true
        }
    }

    private fun persistOverlaySize(widthPx: Int, heightPx: Int) {
        val density = resources.displayMetrics.density
        val w = ((widthPx / density) + 0.5f).toInt().coerceIn(MIN_OVERLAY_SIZE_DP, 720)
        val h = ((heightPx / density) + 0.5f).toInt().coerceIn(MIN_OVERLAY_SIZE_DP, 720)
        if (w != settings.overlayWidthDp || h != settings.overlayHeightDp) {
            settings = settings.copy(overlayWidthDp = w, overlayHeightDp = h)
            store.saveSettings(settings)
        }
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView = label(text, 13f, textColor()).apply {
        gravity = Gravity.CENTER
        background = roundedBackground(buttonColor(), 12)
        setOnClickListener { onClick() }
        setPadding(dp(6), 0, dp(6), 0)
        layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
    }

    private fun createDragListener(): View.OnTouchListener = object : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = overlayParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    overlayRoot?.let { windowManager.updateViewLayout(it, params) }
                    return true
                }
            }
            return true
        }
    }

    private fun renderTranscript() {
        val container = transcriptContainer ?: return
        container.removeAllViews()
        lineViews.clear()
        units.forEachIndexed { index, unit ->
            val view = label(unit.rawText, 16f, secondaryColor()).apply {
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setLineSpacing(0f, 1.15f)
            }
            container.addView(view, LinearLayout.LayoutParams(-1, -2))
            lineViews += view
            updateLine(index, view, unit)
        }
        updateCurrentLine()
        updateStatus()
        updateSeekBar()
        lastScrollTarget = -1
        updateScrollFollowing()
    }

    /**
     * 连续滚动跟随：让「当前正读到的位置」（当前句顶部 + 句内进度×句高）
     * 始终保持在窗口中部，随朗读进度平滑滚动，而不是整句切换时跳一下。
     */
    private fun updateScrollFollowing() {
        val scroll = transcriptScroll ?: return
        val current = lineViews.getOrNull(currentIndex) ?: return
        if (scroll.height <= 0) {
            scroll.post { updateScrollFollowing() }
            return
        }
        // 用户刚手动滚动过，暂停自动跟随，避免抢滚动条
        if (System.currentTimeMillis() - lastUserScrollAt < USER_SCROLL_PAUSE_MS) return
        val anchorY = current.top + current.height * progress.coerceIn(0f, 1f)
        val target = (anchorY - scroll.height / 2f).toInt().coerceAtLeast(0)
        if (lastScrollTarget < 0 || Math.abs(target - lastScrollTarget) > dp(1)) {
            lastScrollTarget = target
            scroll.smoothScrollTo(0, target)
        }
    }

    private fun updateCurrentLine() {
        units.forEachIndexed { index, unit ->
            lineViews.getOrNull(index)?.let { updateLine(index, it, unit) }
        }
        lineViews.getOrNull(currentIndex)?.text = currentSpannable()
        updateStatus()
        updateSeekBar()
    }

    private fun updateLine(index: Int, view: TextView, unit: SpeechUnit) {
        val current = index == currentIndex
        val past = index < currentIndex
        // 全文统一字号常显，当前句用加粗+强调色+底色高亮，已读句淡出
        view.textSize = overlayFontSize()
        view.typeface = if (current) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        view.setTextColor(
            when {
                current -> accentColor()
                past -> secondaryColor()
                else -> textColor()
            }
        )
        view.alpha = when {
            current -> 1f
            past -> 0.7f
            else -> 0.92f
        }
        view.background = if (current) roundedBackground(accentColor(), 10, 0.15f) else null
        view.text = if (current) currentSpannable() else unit.rawText
    }

    private fun currentSpannable(): SpannableString {
        val text = units.getOrNull(currentIndex)?.rawText.orEmpty()
        val visible = (text.length * progress).toInt().coerceIn(0, text.length)
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(liveColor()), 0, visible, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(accentColor()), visible, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive) {
                delay(100)
                if (settings.promptMode == PromptMode.VOICE_FOLLOW && isPlaying) {
                    controller?.onTick(System.currentTimeMillis())
                }
                val fixedPlaybackEnabled = settings.promptMode == PromptMode.FIXED_WPM ||
                    voiceUnavailable || voiceState?.isFallbackToWpm == true
                if (!isPlaying || !fixedPlaybackEnabled || units.isEmpty() || currentIndex !in units.indices) continue
                val duration = segmentDurationSeconds(units[currentIndex], settings.speed)
                progress += 0.1f / duration.toFloat()
                if (progress >= 1f) {
                    progress = 0f
                    if (currentIndex < units.lastIndex) {
                        currentIndex += 1
                        refreshLineStyles()
                    } else {
                        isPlaying = false
                        updatePlayButton()
                    }
                } else {
                    lineViews.getOrNull(currentIndex)?.text = currentSpannable()
                    updateSeekBar()
                }
                updateStatus()
                // 边读边滚：每 100ms 把阅读锚点往窗口中部平滑推进
                updateScrollFollowing()
            }
        }
    }

    private fun changeFont(increase: Boolean) {
        settings = settings.copy(fontScale = stepOverlayFontScale(settings.fontScale, increase))
        store.saveSettings(settings)
        renderTranscript()
    }

    private fun toggleRecording() {
        val rec = recorder
        if (rec?.isRecording == true) {
            stopRecording()
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            voiceEngineStatus = "未授权相机，请到系统设置开启后重试录像"
            updateStatus()
            return
        }
        val target = rec ?: OverlayRecorder(applicationContext) { msg ->
            scope.launch {
                voiceEngineStatus = "录像：$msg"
                updateRecordButton()
                updateStatus()
            }
        }.also { recorder = it }
        // 先刷新前台服务类型（录像需要 camera 类型），再开相机
        startForegroundCompat()
        if (!previewMode) {
            // 进入全屏取景预览，Surface 就绪后自动开始录像
            pendingRecordStart = true
            enterPreviewMode()
            attachPreviewIfReady()
        } else {
            // 已在全屏预览（上次录完仍在取景）：直接开录
            target.start {
                scope.launch { updateRecordButton(); updateStatus() }
            }
        }
    }

    private fun stopRecording() {
        recorder?.stop { uri ->
            scope.launch {
                if (uri != null) {
                    Toast.makeText(this@OverlayService, "录像已保存到 相册 › Movies › ticiqi", Toast.LENGTH_LONG).show()
                }
                updateRecordButton()
                updateStatus()
            }
        }
    }

    /** TextureView Surface 就绪后：挂起预览；若等待开始录像则同时触发。 */
    private fun attachPreviewIfReady() {
        val rec = recorder ?: return
        val tv = previewView ?: return
        if (!tv.isAvailable || previewAttached) return
        if (pendingRecordStart) {
            pendingRecordStart = false
            rec.start {
                scope.launch { updateRecordButton(); updateStatus() }
            }
        }
        previewAttached = true
        rec.attachPreview(Surface(tv.surfaceTexture))
    }

    /** 进入取景预览：创建独立全屏预览窗口铺底，提词窗口浮在上层保持原样。 */
    private fun enterPreviewMode() {
        previewMode = true
        collapseButton?.visibility = View.VISIBLE
        // 提词面板改半透明，身后的取景画面能透出来（对标飓风效果）
        overlayRoot?.background = roundedBackground(Color.argb(110, 10, 14, 22), 18)
        showPreviewWindow()
        bringTranscriptToFront()
        attachPreviewIfReady()
        updateRecordButton()
        updateStatus()
    }

    /** 退出取景预览：停录、释放相机、移除预览窗口。 */
    private fun exitPreviewMode() {
        if (recorder?.isRecording == true) stopRecording()
        previewMode = false
        previewAttached = false
        pendingRecordStart = false
        collapseButton?.visibility = View.GONE
        overlayRoot?.background = roundedBackground(surfaceColor(), 18)
        recorder?.detachPreview()
        removePreviewWindow()
        updateRecordButton()
        updateStatus()
    }

    /** 全屏预览窗口：不可点击（触摸穿透），始终铺满整屏。 */
    private fun showPreviewWindow() {
        if (previewWindow != null) return
        val container = FrameLayout(this)
        val tv = TextureView(this).apply {
            scaleX = -1f // 前置摄像头镜像，符合自拍直觉
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    attachPreviewIfReady()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    recorder?.detachPreview()
                    previewAttached = false
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
        container.addView(tv, FrameLayout.LayoutParams(-1, -1))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(container, params)
        previewWindow = container
        previewView = tv
    }

    private fun removePreviewWindow() {
        val window = previewWindow ?: return
        previewWindow = null
        previewView = null
        runCatching { windowManager.removeViewImmediate(window) }
    }

    /** 预览窗口后加入会盖住提词窗口，把提词窗口重新提到最上层。 */
    private fun bringTranscriptToFront() {
        val frame = overlayRoot ?: return
        val params = overlayParams ?: return
        runCatching {
            windowManager.removeViewImmediate(frame)
            windowManager.addView(frame, params)
        }
    }

    private fun updateRecordButton() {
        val recording = recorder?.isRecording == true
        recordButton?.text = if (recording) "停止" else "录像"
        recordButton?.setTextColor(
            if (recording) Color.rgb(255, 107, 107) else textColor()
        )
    }

    private fun changeSpeed(delta: Int) {
        val next = stepRate(settings.speed, delta)
        if (next != settings.speed) {
            settings = settings.copy(speed = next)
            store.saveSettings(settings)
            updateStatus()
        }
    }

    private fun toggleTheme() {
        settings = settings.copy(
            themeMode = if (settings.themeMode == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
        )
        store.saveSettings(settings)
        overlayRoot?.background = roundedBackground(surfaceColor(), 18)
        renderTranscript()
    }

    private fun updatePlayButton() {
        playButton?.text = if (isPlaying) "暂停" else "继续"
    }

    private fun updateStatus() {
        val rec = recorder
        val recIndicator = if (rec?.isRecording == true) {
            val s = rec.elapsedSeconds()
            " ● 录像中 ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        } else ""
        val mode = when {
            settings.promptMode == PromptMode.FIXED_WPM -> "固定 ${settings.speed} 字/分"
            voiceUnavailable || voiceState?.isFallbackToWpm == true -> "固定 字/分 兜底"
            else -> "语音跟随"
        }
        // 诊断优先级：麦克风被占用 > 听到但未匹配 > 引擎状态文案（模型加载/下载等）。
        val heard = voiceState?.lastHeard.orEmpty()
        val occupied = voiceEngineStatus.startsWith("麦克风被")
        val extra = when {
            settings.promptMode != PromptMode.VOICE_FOLLOW -> ""
            occupied && rec?.isRecording == true -> " · 本机不支持边录边识别：跟随暂停，录像正常"
            occupied -> " · $voiceEngineStatus"
            voiceState?.lastMatched == true -> " · 已跟随"
            heard.isNotBlank() -> " · 听到「${heard}」未匹配"
            voiceEngineStatus.isNotBlank() -> " · $voiceEngineStatus"
            else -> ""
        }
        statusText?.text =
            if (units.isEmpty()) "暂无台本" else "第 ${currentIndex + 1} / ${units.size} 句 · $mode$extra$recIndicator"
    }

    private fun applyVoiceState(next: VoiceFollowState?) {
        next ?: return
        voiceState = next
        if (!next.isFallbackToWpm) {
            val newUnit = next.currentUnitIndex.coerceIn(0, (units.size - 1).coerceAtLeast(0))
            val moved = newUnit != currentIndex
            currentIndex = newUnit
            progress = next.characterProgress.coerceIn(0f, 1f)
            if (moved) {
                // 前进或回头都整行刷新样式（回头重读时会向上滚）
                refreshLineStyles()
            } else {
                lineViews.getOrNull(currentIndex)?.text = currentSpannable()
            }
            // 边读边滚：句内进度变化也持续平滑滚动
            updateScrollFollowing()
        }
        updateStatus()
        updateSeekBar()
    }

    private fun refreshLineStyles() {
        units.forEachIndexed { index, unit ->
            lineViews.getOrNull(index)?.let { updateLine(index, it, unit) }
        }
    }

    private fun startVoiceFollowIfNeeded() {
        controller?.stop()
        controller = null
        voiceState = null
        voiceUnavailable = false
        voiceEngineStatus = ""
        if (settings.promptMode != PromptMode.VOICE_FOLLOW || !isPlaying || units.isEmpty()) {
            updateStatus()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voiceUnavailable = true
            voiceEngineStatus = "未授权麦克风，请到系统设置开启"
            updateStatus()
            return
        }
        val ctrl = VoiceFollowController(
            applicationContext,
            settings,
            units,
            currentIndex,
            onState = { applyVoiceState(it) },
            onUnavailable = { reason ->
                voiceUnavailable = true
                voiceEngineStatus = reason
                updateStatus()
            },
            onStatus = { msg ->
                voiceEngineStatus = msg
                updateStatus()
            }
        )
        controller = ctrl
        ctrl.start()
    }

    private fun updateSeekBar() {
        seekBar?.let {
            seekUpdating = true
            it.progress = overallProgress()
            seekUpdating = false
        }
    }

    private fun overallProgress(): Int =
        if (units.isEmpty()) 0 else (((currentIndex + progress) / units.size) * 1000f).toInt().coerceIn(0, 1000)

    private fun savePlaybackPosition() {
        script?.let {
            store.saveScript(it.copy(lastPlaybackUnit = currentIndex, lastPlaybackProgress = progress))
        }
    }

    private fun startForegroundCompat() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮提词正在运行")
            .setContentText("点击返回演讲提词器")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (settings.promptMode == PromptMode.VOICE_FOLLOW &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (recorder?.isRecording == true &&
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "悬浮提词", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    private fun roundedBackground(color: Int, radius: Int, alpha: Float = 1f) = GradientDrawable().apply {
        setColor(withAlpha(color, alpha))
        cornerRadius = dp(radius).toFloat()
    }

    private fun surfaceColor() = if (settings.themeMode == ThemeMode.DARK) Color.rgb(16, 23, 34) else Color.WHITE
    private fun buttonColor() = if (settings.themeMode == ThemeMode.DARK) Color.rgb(35, 48, 65) else Color.rgb(232, 239, 248)
    private fun textColor() = if (settings.themeMode == ThemeMode.DARK) Color.rgb(244, 247, 251) else Color.rgb(20, 35, 61)
    private fun secondaryColor() = if (settings.themeMode == ThemeMode.DARK) Color.rgb(148, 162, 181) else Color.rgb(85, 112, 143)
    private fun accentColor() = when (settings.accentColor) {
        AccentColor.BLUE -> if (settings.themeMode == ThemeMode.DARK) Color.rgb(101, 200, 255) else Color.rgb(20, 89, 183)
        AccentColor.AMBER -> if (settings.themeMode == ThemeMode.DARK) Color.rgb(255, 215, 106) else Color.rgb(139, 90, 0)
        AccentColor.MINT -> if (settings.themeMode == ThemeMode.DARK) Color.rgb(113, 225, 188) else Color.rgb(12, 107, 84)
    }
    private fun liveColor() = if (settings.themeMode == ThemeMode.DARK) Color.rgb(255, 180, 84) else Color.rgb(179, 92, 0)
    private fun overlayFontSize() = when (settings.fontScale) {
        FontScale.SMALL -> 16f
        FontScale.MEDIUM -> 18f
        FontScale.LARGE -> 21f
        FontScale.EXTRA_LARGE -> 24f
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb(
        (alpha.coerceIn(0f, 1f) * 255).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.example.teleprompter.overlay.STOP"
        const val CHANNEL_ID = "teleprompter_overlay"
        const val EXTRA_SCRIPT_ID = "script_id"
        const val EXTRA_INDEX = "index"
        const val EXTRA_PROGRESS = "progress"
        const val NOTIFICATION_ID = 42
        const val MIN_OVERLAY_SIZE_DP = 200
        const val USER_SCROLL_PAUSE_MS = 3000L

        fun start(context: Context, scriptId: Long, index: Int, progress: Float) {
            val intent = Intent(context.applicationContext, OverlayService::class.java).apply {
                putExtra(EXTRA_SCRIPT_ID, scriptId)
                putExtra(EXTRA_INDEX, index)
                putExtra(EXTRA_PROGRESS, progress)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }
    }
}
