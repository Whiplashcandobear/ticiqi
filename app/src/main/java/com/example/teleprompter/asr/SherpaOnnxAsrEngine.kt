package com.example.teleprompter.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * sherpa-onnx 本地识别引擎。
 *  - 流式模型（ONLINE_CTC / ONLINE_TRANSDUCER）走 [OnlineRecognizer]，每 ~0.1s 出中间结果，
 *    延迟最低，适合实时逐字跟随。
 *  - 离线模型（OFFLINE_PARAFORMER / OFFLINE_SENSE_VOICE）走 [OfflineRecognizer]，按时间窗周期解码，
 *    精度更高、略有延迟。
 *  - 内置模型从 assets 加载；其余模型首次使用时从 HuggingFace 下载并缓存到本机 files 目录。
 *
 * 音频：16kHz / 单声道 / 16bit PCM，由 [AudioRecord] 采集。
 */
class SherpaOnnxAsrEngine(
    private val context: Context,
    private val model: LocalModel
) : AsrEngine {

    private var listener: AsrEngine.Listener? = null
    private val assetManager = context.assets
    private val modelDirFile: File get() = File(context.filesDir, "models/${model.id}")

    private var captureThread: Thread? = null
    private @Volatile var running = false

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null

    private var offlineRecognizer: OfflineRecognizer? = null
    private var offlineBuf = FloatArray(0)
    private var offlineLen = 0
    private var lastOfflineDecodeAt = 0L

    private var audioRecord: AudioRecord? = null

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1600 // 0.1s @16k
        private const val NUM_THREADS = 4
        private const val OFFLINE_DECODE_MS = 1200L
        private const val OFFLINE_WINDOW_SAMPLES = SAMPLE_RATE * 5 // keep ~5s of context
        private const val OFFLINE_SLACK_SAMPLES = SAMPLE_RATE // trim headroom
        private const val SILENT_FRAMES_TO_ALERT = 30 // 0.1s/帧 × 30 = 连续 3 秒纯静音
        private val HOSTS = listOf("https://huggingface.co", "https://hf-mirror.com")
    }

    override fun setListener(listener: AsrEngine.Listener) { this.listener = listener }

    override fun start(): Boolean {
        if (running) return true
        running = true
        val thread = Thread({ prepareAndRun() }, "sherpa-capture")
        thread.isDaemon = true
        captureThread = thread
        thread.start()
        return true
    }

    private fun prepareAndRun() {
        try {
            val dir: String = if (model.bundledAssetDir != null) {
                model.bundledAssetDir
            } else {
                val localDir = modelDirFile
                if (!modelFilesPresent(localDir)) {
                    listener?.onStatus("正在准备模型（约 ${model.approxSizeMb}MB）…")
                    if (!downloadModel()) {
                        listener?.onUnavailable("本地模型下载失败，请检查网络或改用系统识别")
                        running = false
                        return
                    }
                }
                localDir.absolutePath
            }

            if (!running) return

            listener?.onStatus("正在加载模型…")
            when (model.kind) {
                ModelKind.ONLINE_CTC, ModelKind.ONLINE_TRANSDUCER -> {
                    onlineRecognizer = if (model.bundledAssetDir != null) {
                        OnlineRecognizer(assetManager, onlineConfig(dir))
                    } else {
                        OnlineRecognizer(null, onlineConfig(dir))
                    }
                    onlineStream = onlineRecognizer!!.createStream()
                }
                ModelKind.OFFLINE_PARAFORMER, ModelKind.OFFLINE_SENSE_VOICE -> {
                    offlineRecognizer = if (model.bundledAssetDir != null) {
                        OfflineRecognizer(assetManager, offlineConfig(dir))
                    } else {
                        OfflineRecognizer(null, offlineConfig(dir))
                    }
                    lastOfflineDecodeAt = System.currentTimeMillis()
                }
            }

            if (!running) return
            startCapture()
        } catch (e: Throwable) {
            listener?.onUnavailable("本地模型初始化失败：${e.message}")
            running = false
        }
    }

    private fun startCapture() {
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
        val floatBuf = FloatArray(FRAME_SAMPLES)
        listener?.onStatus("识别中")
        var silentFrames = 0
        var micOccupiedNotified = false
        while (running) {
            val read = record.read(shortBuf, 0, FRAME_SAMPLES)
            if (read < 0) {
                // 麦克风被系统临时接管时 read 可能返回错误码，稍等重试，避免热循环
                try { Thread.sleep(50) } catch (_: InterruptedException) { }
                continue
            }
            if (read <= 0) continue
            var sumSq = 0.0
            var maxAbs = 0
            for (i in 0 until read) {
                val f = shortBuf[i] / 32768.0f
                floatBuf[i] = f
                sumSq += f * f
                val a = if (shortBuf[i] < 0) -shortBuf[i].toInt() else shortBuf[i].toInt()
                if (a > maxAbs) maxAbs = a
            }
            // Android 10+ 采集独占策略：麦克风被其他应用（如相机录像）抢占时，
            // 系统仍允许本进程"录音"，但喂进来的全是全 0 静音数据。
            // 真实安静环境的底噪不会是纯 0，连续数秒纯 0 即判定被占用。
            if (maxAbs == 0) {
                if (!micOccupiedNotified && ++silentFrames >= SILENT_FRAMES_TO_ALERT) {
                    micOccupiedNotified = true
                    listener?.onStatus("麦克风被其他应用占用（如相机正在录像）：关掉相机「录制声音」即可恢复跟随")
                }
            } else {
                silentFrames = 0
                if (micOccupiedNotified) {
                    micOccupiedNotified = false
                    listener?.onStatus("识别中")
                }
            }
            val rms = Math.sqrt(sumSq / read)
            listener?.onRms((20.0 * Math.log10(rms + 1e-9)).toFloat())

            val samples = if (read == FRAME_SAMPLES) floatBuf else floatBuf.copyOf(read)
            when (model.kind) {
                ModelKind.ONLINE_CTC, ModelKind.ONLINE_TRANSDUCER -> feedOnline(samples)
                ModelKind.OFFLINE_PARAFORMER, ModelKind.OFFLINE_SENSE_VOICE -> feedOffline(samples)
            }
        }
        runCatching { record.stop() }
        runCatching { record.release() }
        audioRecord = null
    }

    private fun feedOnline(samples: FloatArray) {
        val rec = onlineRecognizer ?: return
        val stream = onlineStream ?: return
        stream.acceptWaveform(samples, SAMPLE_RATE)
        while (rec.isReady(stream)) {
            rec.decode(stream)
        }
        val result = rec.getResult(stream)
        if (result.text.isNotBlank()) {
            listener?.onPartial(result.text)
        }
        if (rec.isEndpoint(stream)) {
            rec.reset(stream)
        }
    }

    private fun feedOffline(samples: FloatArray) {
        val rec = offlineRecognizer ?: return
        appendOffline(samples)
        val now = System.currentTimeMillis()
        if (now - lastOfflineDecodeAt < OFFLINE_DECODE_MS) return
        lastOfflineDecodeAt = now
        if (offlineLen <= 0) return
        val window = offlineBuf.copyOf(offlineLen)
        runCatching {
            val stream = rec.createStream()
            stream.use {
                it.acceptWaveform(window, SAMPLE_RATE)
                rec.decode(it)
                val result = rec.getResult(it)
                if (result.text.isNotBlank()) listener?.onPartial(result.text)
            }
        }
    }

    private fun appendOffline(samples: FloatArray) {
        if (offlineBuf.size < offlineLen + samples.size) {
            val newCap = maxOf(offlineLen + samples.size, maxOf(offlineBuf.size * 2, 1))
            offlineBuf = offlineBuf.copyOf(newCap)
        }
        System.arraycopy(samples, 0, offlineBuf, offlineLen, samples.size)
        offlineLen += samples.size
        if (offlineLen > OFFLINE_WINDOW_SAMPLES + OFFLINE_SLACK_SAMPLES) {
            val keep = OFFLINE_WINDOW_SAMPLES
            System.arraycopy(offlineBuf, offlineLen - keep, offlineBuf, 0, keep)
            offlineLen = keep
        }
    }

    private fun onlineConfig(dir: String): OnlineRecognizerConfig {
        val modelConfig = when (model.kind) {
            ModelKind.ONLINE_CTC -> OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = "$dir/model.int8.onnx"),
                tokens = "$dir/tokens.txt",
                numThreads = NUM_THREADS
            )
            ModelKind.ONLINE_TRANSDUCER -> {
                // 不同代际的模型文件名不同（encoder.int8.onnx vs encoder-epoch-99-avg-1.int8.onnx），
                // 按前缀从模型目录里解析，避免硬编码。
                fun fileName(prefix: String): String =
                    model.files.first { it.second.startsWith(prefix) }.second
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$dir/${fileName("encoder")}",
                        decoder = "$dir/${fileName("decoder")}",
                        joiner = "$dir/${fileName("joiner")}"
                    ),
                    tokens = "$dir/${model.files.first { it.second == "tokens.txt" }.second}",
                    modelType = "zipformer2",
                    numThreads = NUM_THREADS
                )
            }
            else -> throw IllegalArgumentException("not an online model kind")
        }
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            enableEndpoint = true
        )
    }

    private fun offlineConfig(dir: String): OfflineRecognizerConfig {
        val modelConfig = when (model.kind) {
            ModelKind.OFFLINE_PARAFORMER -> OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = "$dir/model.int8.onnx"),
                tokens = "$dir/tokens.txt",
                numThreads = NUM_THREADS
            )
            ModelKind.OFFLINE_SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = "$dir/model.int8.onnx"),
                tokens = "$dir/tokens.txt",
                numThreads = NUM_THREADS
            )
            else -> throw IllegalArgumentException("not an offline model kind")
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig
        )
    }

    private fun modelFilesPresent(dir: File): Boolean =
        model.files.all { f -> val file = File(dir, f.second); file.exists() && file.length() > 0 }

    private fun downloadModel(): Boolean {
        val dir = modelDirFile
        dir.mkdirs()
        val total = model.files.size
        model.files.forEachIndexed { index, (repoPath, fileName) ->
            val target = File(dir, fileName)
            if (target.exists() && target.length() > 0) return@forEachIndexed
            var ok = false
            for (host in HOSTS) {
                val url = "$host/${model.repo}/resolve/main/$repoPath"
                listener?.onStatus("下载模型 ${index + 1}/$total …")
                if (downloadFile(url, target)) { ok = true; break }
            }
            if (!ok) return false
        }
        return true
    }

    private fun downloadFile(urlStr: String, target: File): Boolean = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299) { conn.disconnect(); return@runCatching false }
        conn.inputStream.use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(8192)
                var read: Int
                while (input.read(buf).also { read = it } != -1) out.write(buf, 0, read)
            }
        }
        conn.disconnect()
        true
    }.getOrDefault(false)

    override fun stop() {
        running = false
        runCatching { audioRecord?.stop() }
        captureThread?.let { if (it.isAlive) it.join(1500) }
        captureThread = null
    }

    override fun close() {
        stop()
        runCatching { onlineStream?.release() }
        runCatching { onlineRecognizer?.release() }
        runCatching { offlineRecognizer?.release() }
        onlineStream = null; onlineRecognizer = null
        offlineRecognizer = null
        offlineBuf = FloatArray(0); offlineLen = 0
    }
}
