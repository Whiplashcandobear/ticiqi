package com.example.teleprompter.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import com.example.teleprompter.domain.model.CloudAsrConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通用云端识别引擎：把每 ~[CloudAsrConfig.chunkMillis] 的 16k/16bit 音频编码后，
 * 按 [CloudAsrConfig.bodyTemplate] 组装请求发到用户配置的 [CloudAsrConfig.endpoint]，
 * 再沿 [CloudAsrConfig.resultPath] 从 JSON 响应中取出识别文本。
 *
 * 这样"后面也支持自己配云端识别模型 API"——任何接受音频、返回 JSON 文本的 HTTP 服务都能接。
 */
class CloudHttpAsrEngine(
    private val context: Context,
    private val config: CloudAsrConfig
) : AsrEngine {

    private var listener: AsrEngine.Listener? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private var captureThread: Thread? = null
    private @Volatile var running = false
    private @Volatile var inflight = false
    private var audioRecord: AudioRecord? = null
    private val pending = mutableListOf<Short>()

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1600 // 0.1s
    }

    override fun setListener(listener: AsrEngine.Listener) { this.listener = listener }

    override fun start(): Boolean {
        if (config.endpoint.isBlank()) {
            listener?.onUnavailable("未配置云端识别接口地址")
            return false
        }
        if (running) return true
        running = true
        val thread = Thread({ capture() }, "cloud-capture")
        thread.isDaemon = true
        captureThread = thread
        thread.start()
        return true
    }

    private fun capture() {
        val record = try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = (if (minBuffer <= 0) FRAME_SAMPLES * 2 else minBuffer) * 2
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                listener?.onUnavailable("麦克风初始化失败，请检查录音权限")
                running = false
                r.release()
                return
            }
            r
        } catch (e: Throwable) {
            listener?.onUnavailable("麦克风初始化失败：${e.message}")
            running = false
            return
        }
        audioRecord = record
        try {
            record.startRecording()
        } catch (e: Throwable) {
            listener?.onUnavailable("麦克风启动失败，请检查录音权限")
            running = false
            record.release()
            audioRecord = null
            return
        }
        val shortBuf = ShortArray(FRAME_SAMPLES)
        val chunkMillis = config.chunkMillis.coerceIn(200, 5000)
        var lastSend = System.currentTimeMillis()
        listener?.onStatus("云端识别中")
        while (running) {
            val read = record.read(shortBuf, 0, FRAME_SAMPLES)
            if (read <= 0) continue
            synchronized(pending) {
                for (i in 0 until read) pending.add(shortBuf[i])
            }
            val now = System.currentTimeMillis()
            if (now - lastSend >= chunkMillis && !inflight) {
                val chunk: ShortArray = synchronized(pending) {
                    val arr = pending.toShortArray()
                    pending.clear()
                    arr
                }
                lastSend = now
                if (chunk.isNotEmpty()) sendChunk(chunk)
            }
        }
        runCatching { record.stop() }
        runCatching { record.release() }
        audioRecord = null
    }

    private fun sendChunk(samples: ShortArray) {
        inflight = true
        val b64 = encodeAudio(samples, config.audioEncoding)
        val body = config.bodyTemplate
            .replace("{base64}", b64)
            .replace("{sampleRate}", SAMPLE_RATE.toString())
            .replace("{format}", config.audioEncoding)
        val mediaType = config.contentType.toMediaType()
        val request = Request.Builder()
            .url(config.endpoint)
            .method(config.method.uppercase(), if (config.method.equals("GET", true)) null else body.toRequestBody(mediaType))
            .apply {
                if (config.apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${config.apiKey}")
                if (config.headersJson.isNotBlank()) addConfigHeaders(config.headersJson)
            }
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inflight = false
                listener?.onError(-1)
            }

            override fun onResponse(call: Call, response: Response) {
                inflight = false
                response.use {
                    val text = runCatching {
                        val raw = it.body?.string().orEmpty()
                        if (it.isSuccessful) extractResult(raw) else null
                    }.getOrNull()
                    if (!text.isNullOrBlank()) listener?.onPartial(text)
                    else if (!it.isSuccessful) listener?.onError(it.code)
                }
            }
        })
    }

    private fun extractResult(json: String): String? {
        val path = config.resultPath.ifBlank { "text" }
        var cur: Any = runCatching { JSONObject(json) }.getOrNull() ?: return null
        for (raw in path.split('.')) {
            val seg = raw.trim()
            if (seg.isEmpty()) continue
            val (name, idx) = parseSeg(seg)
            when {
                cur is JSONObject -> {
                    if (!cur.has(name)) return null
                    cur = cur.get(name)
                }
                cur is JSONArray -> {
                    val i = idx ?: 0
                    if (i < 0 || i >= cur.length()) return null
                    cur = cur.get(i)
                }
                else -> return null
            }
            if (cur is JSONArray && idx != null) {
                val i = idx
                if (i < 0 || i >= cur.length()) return null
                cur = cur.get(i)
            }
        }
        return cur?.toString()
    }

    private fun parseSeg(seg: String): Pair<String, Int?> {
        val lb = seg.indexOf('[')
        if (lb >= 0 && seg.endsWith("]")) {
            val name = seg.substring(0, lb)
            val idx = seg.substring(lb + 1, seg.length - 1).toIntOrNull()
            return name to idx
        }
        return seg to null
    }

    private fun Request.Builder.addConfigHeaders(headersJson: String) {
        runCatching {
            val obj = JSONObject(headersJson)
            obj.keys().forEach { key -> addHeader(key, obj.getString(key)) }
        }
    }

    private fun encodeAudio(samples: ShortArray, encoding: String): String {
        val pcm = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        val data = if (encoding == "pcm16") pcm else wavHeader(pcm)
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun wavHeader(pcm: ByteArray): ByteArray {
        val total = 44 + pcm.size
        val out = ByteArray(total)
        fun str(off: Int, s: String) { for (i in s.indices) out[off + i] = s[i].code.toByte() }
        fun int(off: Int, v: Int) {
            out[off] = v.toByte(); out[off + 1] = (v ushr 8).toByte()
            out[off + 2] = (v ushr 16).toByte(); out[off + 3] = (v ushr 24).toByte()
        }
        fun sh(off: Int, v: Int) { out[off] = v.toByte(); out[off + 1] = (v ushr 8).toByte() }
        str(0, "RIFF"); int(4, total - 8); str(8, "WAVE")
        str(12, "fmt "); int(16, 16); sh(20, 1); sh(22, 1)
        int(24, SAMPLE_RATE); int(28, SAMPLE_RATE * 2); sh(32, 2); sh(34, 16)
        str(36, "data"); int(40, pcm.size)
        System.arraycopy(pcm, 0, out, 44, pcm.size)
        return out
    }

    override fun stop() {
        running = false
        runCatching { audioRecord?.stop() }
        captureThread?.let { if (it.isAlive) it.join(1500) }
        captureThread = null
    }

    override fun close() {
        stop()
        runCatching { client.dispatcher.executorService.shutdown() }
    }
}
