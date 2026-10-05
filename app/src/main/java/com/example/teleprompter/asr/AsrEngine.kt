package com.example.teleprompter.asr

/**
 * 统一语音识别引擎抽象。三种实现共享同一套回调：
 *  - [GoogleOnDeviceAsrEngine]：Android 自带设备端识别（零下载、离线）。
 *  - [SherpaOnnxAsrEngine]：sherpa-onnx 本地模型（流式低延迟 / 离线高精度）。
 *  - [CloudHttpAsrEngine]：用户自己配置的云端识别 API。
 *
 * 识别结果统一为 onPartial（中间结果）/ onFinal（整句结果），由上层对齐引擎消费。
 */
interface AsrEngine {
    fun setListener(listener: Listener)
    /** 开始识别。返回 true 表示已启动（或正在准备，如本地模型下载中）。 */
    fun start(): Boolean
    fun stop()
    fun close()

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** 实时音量（dB），用于将来做声音指示，可选。 */
        fun onRms(dB: Float) {}
        fun onError(code: Int) {}
        /** 引擎不可用（无权限 / 模型下载失败 / 网络错误等）。 */
        fun onUnavailable(reason: String)
        /** 状态提示（模型下载进度、加载中等），用于 UI 展示。 */
        fun onStatus(message: String) {}
    }
}
