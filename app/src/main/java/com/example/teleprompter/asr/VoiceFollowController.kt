package com.example.teleprompter.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.teleprompter.domain.model.AsrMode
import com.example.teleprompter.domain.model.DisplaySettings
import com.example.teleprompter.domain.model.SpeechUnit
import com.example.teleprompter.domain.model.VoiceFollowState
import com.example.teleprompter.domain.voice.VoiceFollowEngine

/**
 * 把"识别引擎 + 中文字符级对齐 + 回退链"封装成一个可控单元，供悬浮窗与播放页共用。
 *
 * 回退链：LOCAL/CLOUD 引擎不可用时自动回退到系统识别；系统识别也不可用时，
 * 由上层（状态 isFallbackToWpm）切回固定字/分。
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
    private var engine: AsrEngine? = null
    private var usingFallback = false
    private val mainHandler = Handler(Looper.getMainLooper())
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
        val target = AsrEngineFactory.create(context, settings)
        engine?.close()
        usingFallback = false
        engine = target
        target.setListener(listener)
        target.start()
    }

    private fun handleUnavailable(reason: String) {
        if (!usingFallback && settings.asrMode != AsrMode.SYSTEM) {
            usingFallback = true
            engine?.close()
            val fb = AsrEngineFactory.createSystem(context)
            engine = fb
            fb.setListener(listener)
            postStatus("已回退到系统识别")
            fb.start()
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
    }
}
