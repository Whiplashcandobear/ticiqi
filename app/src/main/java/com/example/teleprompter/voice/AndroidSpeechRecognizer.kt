package com.example.teleprompter.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Android speech input adapter. It never stores audio or recognition text. */
class AndroidSpeechRecognizer(
    context: Context,
    private val listener: Listener
) : RecognitionListener {
    interface Listener {
        fun onText(text: String, isFinal: Boolean)
        fun onUnavailable(reason: String)
        fun onError(code: Int)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var closed = false
    private var restartPosted = false

    fun start(): Boolean {
        if (closed) return false
        if (!isAvailable(appContext)) {
            listener.onUnavailable("当前设备没有可用的语音识别服务")
            return false
        }
        running = true
        createRecognizerIfNeeded()
        requestRecognition()
        return true
    }

    fun stop() {
        running = false
        restartPosted = false
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
    }

    fun close() {
        if (closed) return
        closed = true
        stop()
        recognizer?.setRecognitionListener(null)
        recognizer?.destroy()
        recognizer = null
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit

    override fun onPartialResults(results: Bundle?) {
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { listener.onText(it, false) }
    }

    override fun onResults(results: Bundle?) {
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf(String::isNotBlank)
            ?.let { listener.onText(it, true) }
        scheduleRestart(120L)
    }

    override fun onError(error: Int) {
        listener.onError(error)
        if (running && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 350L else 180L)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun createRecognizerIfNeeded() {
        if (recognizer != null) return
        recognizer = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            // On older Android versions the system recognizer is the only
            // public API. No INTERNET permission is added by this app.
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.also { it.setRecognitionListener(this) }
    }

    private fun requestRecognition() {
        if (!running || closed) return
        restartPosted = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer?.startListening(intent) }
            .onFailure { listener.onUnavailable("语音识别启动失败") }
    }

    private fun scheduleRestart(delayMillis: Long) {
        if (!running || closed || restartPosted) return
        restartPosted = true
        mainHandler.postDelayed({ requestRecognition() }, delayMillis)
    }

    companion object {
        fun isAvailable(context: Context): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                true
            } else {
                SpeechRecognizer.isRecognitionAvailable(context)
            }
        }.getOrDefault(false)
    }
}
