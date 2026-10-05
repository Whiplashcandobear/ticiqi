package com.example.teleprompter.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.teleprompter.domain.model.AsrMode
import com.example.teleprompter.domain.model.DisplaySettings
import com.example.teleprompter.domain.model.SpeechUnit
import com.example.teleprompter.domain.voice.VoiceFollowEngine
import com.example.teleprompter.domain.voice.VoiceFollowState

/**
 * 把"识别引擎 + 中文字符级对齐 + 回退链"封装成一个可控单元，供悬浮窗与播放页共用。
 *
 * 回退链（按需自动切换，每种引擎最多尝试一次）：
 *  - 用户选 SYSTEM：只试系统识别。
 *  - 用户选 LOCAL：本地模型 → 系统识别。
 *  - 用户选 CLOUD：云端 → 系统识别 → 本地模型。
 * 全部不可用才交回上层（isFallbackToWpm → 固定字/分）。
 *
 * 关键场景：国产 ROM（红米/澎湃OS）通常没有 Google 语音识别服务，选了 SYSTEM 会失败，
 * 此时自动切到本地 sherpa 模型（内置 25MB 流式中文，离线可用）。
 */
class VoiceFollowController(
    private val context: Context,
    private val settings: DisplaySettings,
    units: List<SpeechUnit>,
    initialIndex: Int,
    private val onState: (VoiceFollowState) -> Unit,
    private val onUnavailable: (String) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val voiceEngine = VoiceFollowEngine(units, initialIndex)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: AsrEngine? = null
    private val remaining = ArrayDeque<AsrMode>()
    private var currentMode: AsrMode? = null

    private val listener = object : AsrEngine.Listener {
        override fun onPartial(text: String) = feed(text, false)
        override fun onFinal(text: String) = feed(text, true)
        override fun onError(code: Int) = postStatus("识别出错($code)")
        override fun onUnavailable(reason: String) = handleUnavailable(reason)
        override fun onStatus(message: String) = postStatus(message)
    }

    fun start() {
        voiceEngine.startSession()
        postState()
        remaining.clear()
        when (settings.asrMode) {
            // 系统识别失败（很多国产 ROM 没有 Google 识别服务）也能落到本地模型。
            AsrMode.SYSTEM -> {
                remaining.addLast(AsrMode.SYSTEM)
                remaining.addLast(AsrMode.LOCAL)
            }
            AsrMode.LOCAL -> {
                remaining.addLast(AsrMode.LOCAL)
                remaining.addLast(AsrMode.SYSTEM)
            }
            AsrMode.CLOUD -> {
                remaining.addLast(AsrMode.CLOUD)
                remaining.addLast(AsrMode.SYSTEM)
                remaining.addLast(AsrMode.LOCAL)
            }
        }
        startNext()
    }

    private fun startNext() {
        val mode = remaining.removeFirstOrNull()
        if (mode == null) {
            mainHandler.post { onUnavailable("没有可用的识别引擎") }
            return
        }
        currentMode = mode
        val label = when (mode) {
            AsrMode.SYSTEM -> "系统识别"
            AsrMode.LOCAL -> "本地模型"
            AsrMode.CLOUD -> "云端识别"
        }
        postStatus("正在启动$label…")
        val target = AsrEngineFactory.create(context, settings.copy(asrMode = mode))
        engine?.close()
        engine = target
        target.setListener(listener)
        if (!target.start()) {
            // start() 返回 false 时引擎内部已回调 onUnavailable；这里兜底再触发一次。
            handleUnavailable("$label 启动失败")
        }
    }

    private fun handleUnavailable(reason: String) {
        if (remaining.isNotEmpty()) {
            postStatus("$reason，切换备选引擎…")
            startNext()
        } else {
            mainHandler.post { onUnavailable(reason) }
        }
    }

    private fun feed(text: String, isFinal: Boolean) {
        postState(voiceEngine.onRecognition(text, System.currentTimeMillis(), isFinal))
    }

    private fun postState(state: VoiceFollowState = voiceEngine.state()) {
        mainHandler.post { onState(state) }
    }

    private fun postStatus(message: String) {
        mainHandler.post { onStatus(message) }
    }

    fun onTick(now: Long = System.currentTimeMillis()) {
        postState(voiceEngine.onTick(now))
    }

    fun setCursor(index: Int, progress: Float) {
        voiceEngine.setCursor(index, progress)
    }

    fun latestState(): VoiceFollowState = voiceEngine.state()

    fun stop() {
        engine?.close()
        engine = null
        remaining.clear()
    }
}
