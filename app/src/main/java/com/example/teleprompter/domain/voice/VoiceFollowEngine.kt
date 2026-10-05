package com.example.teleprompter.domain.voice

import com.example.teleprompter.domain.model.SpeechUnit

data class VoiceFollowState(
    val currentUnitIndex: Int,
    val characterProgress: Float,
    /** 引擎侧不再主动兜底（见类注释）；此字段保留供 UI 兼容，恒为 false。 */
    val isFallbackToWpm: Boolean,
    val hasStableMatch: Boolean,
    val lastRecognitionAtMillis: Long?
)

/**
 * 中文语音跟随状态机：识别文本 → [ScriptAligner] 整句匹配 → 光标（句号 + 句内进度）。
 *
 * 行为设计（相对旧版的两个关键变化）：
 *  1. **乱说话不动**：识别文本与台本匹配得分低于阈值时（说了无关的话），光标原地不动，
 *     也不会像旧版那样被噪声一点点"蹭"着往前走；
 *  2. **可以回头**：用户回头重读前面已滚过的内容时，匹配点在光标之前，光标自动滚回去。
 *
 * 兜底策略：光标兜底（回固定字/分）只发生在"识别引擎整个不可用"时（由上层
 * voiceUnavailable 控制）；引擎活着但暂时没匹配到（停顿/跑题）就原地等待，
 * 不会自作主张按固定速度往下滚——否则用户说完一段插话回来，屏幕已经滚远了。
 *
 * 纯 Kotlin、无 Android 依赖，可单元测试。
 */
class VoiceFollowEngine(
    private val units: List<SpeechUnit>,
    initialUnitIndex: Int = 0,
    startAtMillis: Long = System.currentTimeMillis()
) {
    private val aligner: ScriptAligner
    private val unitCleanStart: IntArray
    private val unitCleanLen: IntArray
    private val totalClean: Int

    private val lastUnitIndex = (units.size - 1).coerceAtLeast(0)
    /** 对齐器的当前光标（clean 字符索引，指向"下一个未说到的字"）。 */
    private var currentClean: Int
    private var currentIndex = initialUnitIndex.coerceIn(0, lastUnitIndex)
    private var characterProgress = 0f
    private var lastValidRecognitionAt = startAtMillis
    private var hasStableMatch = false

    init {
        val content = units.joinToString(" ") { it.rawText }
        aligner = ScriptAligner(content)
        val starts = IntArray(units.size)
        val lengths = IntArray(units.size)
        var cursor = 0
        for (k in units.indices) {
            val cleanCount = units[k].rawText.count { ScriptAligner.isCleanChar(it) }
            starts[k] = cursor
            lengths[k] = cleanCount
            cursor += cleanCount
        }
        unitCleanStart = starts
        unitCleanLen = lengths
        totalClean = cursor
        currentClean = unitCleanStart.getOrElse(currentIndex) { 0 } +
            (characterProgress * unitCleanLen.getOrElse(currentIndex) { 0 }).toInt()
    }

    fun startSession(nowMillis: Long = System.currentTimeMillis()) {
        lastValidRecognitionAt = nowMillis
        hasStableMatch = false
        seekTo(currentIndex, characterProgress)
    }

    /** 用户手动拖动进度条 / 重新开始时调用。 */
    fun setCursor(unitIndex: Int, progress: Float = 0f) {
        seekTo(unitIndex, progress)
    }

    fun state(nowMillis: Long = System.currentTimeMillis()): VoiceFollowState = snapshot(nowMillis)

    fun onRecognition(text: String, nowMillis: Long, isFinal: Boolean = false): VoiceFollowState {
        val match = aligner.match(text, currentClean)
        if (match == null) {
            // 与台本无关的话（乱说/寒暄/短暂跑题）：光标原地不动。
            return snapshot(nowMillis)
        }
        currentClean = match.endClean.coerceIn(0, totalClean)
        val (unitIndex, fraction) = resolveUnit(currentClean, boundaryIsEnd = true)
        currentIndex = unitIndex
        characterProgress = fraction
        lastValidRecognitionAt = nowMillis
        hasStableMatch = true
        return snapshot(nowMillis)
    }

    fun onTick(nowMillis: Long): VoiceFollowState = snapshot(nowMillis)

    private fun seekTo(unitIndex: Int, progress: Float) {
        currentIndex = unitIndex.coerceIn(0, lastUnitIndex)
        characterProgress = progress.coerceIn(0f, 1f)
        currentClean = (unitCleanStart.getOrElse(currentIndex) { 0 } +
            (characterProgress * unitCleanLen.getOrElse(currentIndex) { 0 }).toInt()
        ).coerceIn(0, totalClean)
    }

    /**
     * clean 索引 → (句索引, 句内进度)。
     * [boundaryIsEnd]=true 时，恰好落在句边界上视为"上一句已读完"（进度 1.0），
     * 用于语音匹配——用户刚重读完那一句；拖动进度条用 false（视为下一句开头）。
     */
    private fun resolveUnit(cleanIndex: Int, boundaryIsEnd: Boolean): Pair<Int, Float> {
        if (units.isEmpty()) return 0 to 0f
        val safe = cleanIndex.coerceIn(0, totalClean)
        for (k in units.indices) {
            val start = unitCleanStart[k]
            val len = unitCleanLen[k]
            if (len <= 0) continue
            if (safe < start + len) {
                return k to ((safe - start).toFloat() / len).coerceIn(0f, 1f)
            }
            if (safe == start + len && boundaryIsEnd) {
                return k to 1f
            }
        }
        return units.lastIndex to 1f
    }

    private fun snapshot(nowMillis: Long): VoiceFollowState = VoiceFollowState(
        currentUnitIndex = currentIndex,
        characterProgress = characterProgress,
        isFallbackToWpm = false,
        hasStableMatch = hasStableMatch,
        lastRecognitionAtMillis = if (hasStableMatch) lastValidRecognitionAt else null
    )
}
