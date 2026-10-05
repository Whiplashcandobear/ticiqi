package com.example.teleprompter.asr

import android.content.Context
import com.example.teleprompter.voice.AndroidSpeechRecognizer

/**
 * 包装 Android 自带（设备端）语音识别。零下载、可离线，作为 LOCAL/CLOUD 引擎的回退。
 */
class GoogleOnDeviceAsrEngine(context: Context) : AsrEngine {
    private var listener: AsrEngine.Listener? = null
    private val recognizer = AndroidSpeechRecognizer(
        context.applicationContext,
        object : AndroidSpeechRecognizer.Listener {
            override fun onText(text: String, isFinal: Boolean) {
                if (isFinal) listener?.onFinal(text) else listener?.onPartial(text)
            }

            override fun onUnavailable(reason: String) {
                listener?.onUnavailable(reason)
            }

            override fun onError(code: Int) {
                listener?.onError(code)
            }
        }
    )

    override fun setListener(listener: AsrEngine.Listener) {
        this.listener = listener
    }

    override fun start(): Boolean = recognizer.start()

    override fun stop() {
        recognizer.stop()
    }

    override fun close() {
        recognizer.close()
    }
}
