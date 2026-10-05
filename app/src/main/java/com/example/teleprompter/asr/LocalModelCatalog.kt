package com.example.teleprompter.asr

/**
 * 本地（设备端）识别模型目录。
 *
 * 调研结论（面向"红米 Note 13 Pro，12G 运存，低延迟实时跟随中文语音"）：
 *  - 真正"低延迟实时跟随"必须用**流式**模型（sherpa-onnx OnlineRecognizer，每 ~0.1s 出中间结果）。
 *    Paraformer / SenseVoice / FunASR-Nano 在 sherpa 里都是**离线（非流式）**模型，单句结束才出结果，
 *    不适合逐字实时跟随，但精度更高，适合"读完再校正"或网络较差时追求准确率。
 *  - FunASR-Nano 在 v1.12.9 的 sherpa-onnx 离线配置里对应的是 LLM 架构
 *    （embedding/encoder_adaptor/llm.onnx），标准 OfflineRecognizer 不直接支持，故本版未纳入。
 *  - 默认内置 [SMALL_CTC_ZH_INT8]（流式 CTC 中文，约 25MB），开箱离线可用、延迟最低。
 *  - 其余高精度模型按需从 HuggingFace 下载（首次约几十~两百 MB），下载后缓存到本机。
 */
enum class ModelKind { ONLINE_CTC, ONLINE_TRANSDUCER, OFFLINE_PARAFORMER, OFFLINE_SENSE_VOICE }

data class LocalModel(
    val id: String,
    val label: String,
    val kind: ModelKind,
    /** HuggingFace repo id，用于按需下载。 */
    val repo: String,
    /** (仓库内相对路径, 本机文件名) 列表。 */
    val files: List<Pair<String, String>>,
    /** 若随 APK 内置，则为 assets 下相对目录（如 "models/xxx"）；否则为 null。 */
    val bundledAssetDir: String?,
    val approxSizeMb: Int,
    val note: String
)

object LocalModelCatalog {
    const val DEFAULT_ID = "TRANSDUCER_ZH_INT8_2025_06_30"

    val entries: List<LocalModel> = listOf(
        LocalModel(
            id = "TRANSDUCER_ZH_INT8_2025_06_30",
            label = "流式中文(内置·最新·推荐)",
            kind = ModelKind.ONLINE_TRANSDUCER,
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            files = listOf(
                "encoder.int8.onnx" to "encoder.int8.onnx",
                "decoder.onnx" to "decoder.onnx",
                "joiner.int8.onnx" to "joiner.int8.onnx",
                "tokens.txt" to "tokens.txt"
            ),
            bundledAssetDir = "models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            approxSizeMb = 158,
            note = "最新一代流式中文 transducer，精度最好，已内置可离线用。"
        ),
        LocalModel(
            id = "ZH_14M_INT8",
            label = "流式中文(轻量·内置)",
            kind = ModelKind.ONLINE_TRANSDUCER,
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            files = listOf(
                "encoder-epoch-99-avg-1.int8.onnx" to "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.int8.onnx" to "decoder-epoch-99-avg-1.int8.onnx",
                "joiner-epoch-99-avg-1.int8.onnx" to "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt" to "tokens.txt"
            ),
            bundledAssetDir = "models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            approxSizeMb = 24,
            // 注意：不能用 2025-04-01 的 small-ctc-zh——它是字节级 BPE(bbpe) tokenizer，
            // 解码输出是字节映射乱码，sherpa 不做反向转换，对齐器拿不到汉字。
            note = "飓风提词器同款轻量流式中文模型，加载最快，已内置。"
        ),
        LocalModel(
            id = "BI_ZH_EN_INT8_2023",
            label = "流式中英(更准·下载)",
            kind = ModelKind.ONLINE_TRANSDUCER,
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            files = listOf(
                "encoder-epoch-99-avg-1.int8.onnx" to "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.int8.onnx" to "decoder-epoch-99-avg-1.int8.onnx",
                "joiner-epoch-99-avg-1.int8.onnx" to "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt" to "tokens.txt"
            ),
            bundledAssetDir = null,
            approxSizeMb = 188,
            note = "流式中英双语 transducer，识别更准，需下载约 188MB。"
        ),
        LocalModel(
            id = "TRANSDUCER_ZH_INT8_2025_06_30",
            label = "流式中文(高精度·下载)",
            kind = ModelKind.ONLINE_TRANSDUCER,
            repo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            files = listOf(
                "encoder.int8.onnx" to "encoder.int8.onnx",
                "decoder.onnx" to "decoder.onnx",
                "joiner.int8.onnx" to "joiner.int8.onnx",
                "tokens.txt" to "tokens.txt"
            ),
            bundledAssetDir = null,
            approxSizeMb = 166,
            note = "流式 transducer 中文（2025 版），精度更高，需下载约 166MB。"
        ),
        LocalModel(
            id = "PARAFORMER_ZH",
            label = "离线Paraformer(高精度)",
            kind = ModelKind.OFFLINE_PARAFORMER,
            repo = "csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14",
            files = listOf("model.int8.onnx" to "model.int8.onnx", "tokens.txt" to "tokens.txt"),
            bundledAssetDir = null,
            approxSizeMb = 40,
            note = "离线 Paraformer 中文，高精度、非流式（略有延迟），需下载约 40MB。"
        ),
        LocalModel(
            id = "SENSE_VOICE_SMALL",
            label = "离线SenseVoice(高精度)",
            kind = ModelKind.OFFLINE_SENSE_VOICE,
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            files = listOf("model.int8.onnx" to "model.int8.onnx", "tokens.txt" to "tokens.txt"),
            bundledAssetDir = null,
            approxSizeMb = 230,
            note = "离线 SenseVoice 多语种，高精度、含情绪/事件标签，体积大，需下载约 230MB。"
        )
    )

    fun get(id: String): LocalModel = entries.firstOrNull { it.id == id } ?: entries.first()
}
