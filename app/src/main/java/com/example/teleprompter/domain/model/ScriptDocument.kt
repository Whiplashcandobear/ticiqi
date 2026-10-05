package com.example.teleprompter.domain.model

data class ScriptDocument(
    val id: Long = 0L,
    val title: String = "未命名演讲",
    val rawText: String = "",
    val wordCount: Int = 0,
    val updatedAt: Long = 0L,
    val lastPlaybackUnit: Int = 0,
    val lastPlaybackProgress: Float = 0f
)

enum class AccentColor { BLUE, AMBER, MINT }

enum class ThemeMode { DARK, LIGHT }

enum class FontScale { SMALL, MEDIUM, LARGE, EXTRA_LARGE }

enum class PromptMode { FIXED_WPM, VOICE_FOLLOW }

/**
 * Which speech engine drives 语音跟随.
 * - SYSTEM: Android 自带（设备端）识别，零下载、离线可用，但精度一般。
 * - LOCAL: sherpa-onnx 本地模型（已内置一个流式中文模型，也可下载更高精度模型）。
 * - CLOUD: 用户自己配置的云端识别 API（见 [CloudAsrConfig]）。
 */
enum class AsrMode { SYSTEM, LOCAL, CLOUD }

/**
 * 用户可自助配置的云端识别 API。引擎会把每 ~1 秒的 16k/16bit 音频编码后按
 * [bodyTemplate] 发送，并沿 [resultPath] 从 JSON 响应中取出识别文本。
 *
 * [bodyTemplate] 支持的占位符：
 *   {base64}      —— 当前音频块的 Base64（WAV 或裸 PCM，见 [audioEncoding]）
 *   {sampleRate}  —— 16000
 *   {format}      —— 与 [audioEncoding] 一致（"wav" / "pcm16"）
 * [resultPath] 支持点路径与数组下标，例如 "text"、"result.text"、"data.result[0].text"。
 */
data class CloudAsrConfig(
    val endpoint: String = "",
    val apiKey: String = "",
    val method: String = "POST",
    val contentType: String = "application/json",
    val headersJson: String = "",
    val bodyTemplate: String = """{"audio":"{base64}","sample_rate":{sampleRate},"format":"{format}"}""",
    val resultPath: String = "text",
    val audioEncoding: String = "wav",
    val chunkMillis: Int = 1000
)

data class DisplaySettings(
    val speed: Int = 200,
    val accentColor: AccentColor = AccentColor.BLUE,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val fontScale: FontScale = FontScale.LARGE,
    val countdownSeconds: Int = 5,
    val landscape: Boolean = false,
    val promptMode: PromptMode = PromptMode.FIXED_WPM,
    val asrMode: AsrMode = AsrMode.SYSTEM,
    val localModelId: String = "SMALL_CTC_ZH_INT8",
    val cloudConfig: CloudAsrConfig = CloudAsrConfig()
)

data class SpeechUnit(
    val rawText: String,
    val wordCount: Int,
    val paragraphIndex: Int,
    val isBlank: Boolean = rawText.isBlank()
)
