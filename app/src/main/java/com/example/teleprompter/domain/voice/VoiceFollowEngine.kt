package com.example.teleprompter.domain.voice

import com.example.teleprompter.domain.model.SpeechUnit

data class VoiceFollowState(
    val currentUnitIndex: Int,
    val characterProgress: Float,
    /** 引擎侧不再主动兜底（见类注释）；此字段保留供 UI 兼容，恒为 false。 */
    val isFallbackToWpm: Boolean,
    val hasStableMatch: Boolean,
    val lastRecognitionAtMillis: Long?,
    /** 最近一次听到的识别文本（清洗后，截断），用于 UI 诊断"到底听到了什么"。 */
    val lastHeard: String = "",
    /** 最近一次识别是否成功匹配上台本。 */
    val lastMatched: Boolean = false
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
    private var lastHeard = ""
    private var lastMatched = false
    /** 最近一次收到非空识别文本的时刻（用于判断"用户是否正在说话"）。 */
    private var lastVoiceAt = 0L
    /** 上次容错推进的时刻（冷却控制）。 */
    private var lastNudgeAt = 0L
    /** 待确认的弱回退信号。 */
    private var pendingBacktrack: ScriptAligner.ScriptMatch? = null
    private var backtrackConfirmations = 0
    /** 连续容错推进次数（兜底上限，防止长时间乱说话一路滚到底）。 */
    private var consecutiveNudges = 0

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
        lastVoiceAt = 0L
        lastNudgeAt = 0L
        consecutiveNudges = 0
        pendingBacktrack = null
        backtrackConfirmations = 0
        seekTo(currentIndex, characterProgress)
    }

    /** 用户手动拖动进度条 / 重新开始时调用。 */
    fun setCursor(unitIndex: Int, progress: Float = 0f) {
        pendingBacktrack = null
        backtrackConfirmations = 0
        lastNudgeAt = 0L
        consecutiveNudges = 0
        seekTo(unitIndex, progress)
    }

    fun state(nowMillis: Long = System.currentTimeMillis()): VoiceFollowState = snapshot(nowMillis)

    fun onRecognition(text: String, nowMillis: Long, isFinal: Boolean = false): VoiceFollowState {
        val cleaned = aligner.cleanText(text)
        // 流式识别的部分结果是"自会话起的累计文本"，会越来越长；只取尾部参与匹配
        // （对齐器本就无状态整段重配，取尾足够），既限 CPU 又保证是最新说的话。
        val utterance = if (cleaned.length > MAX_UTT_CLEAN_CHARS) {
            cleaned.takeLast(MAX_UTT_CLEAN_CHARS)
        } else {
            cleaned
        }
        lastHeard = if (utterance.length > HEARD_SNIPPET_CHARS) {
            utterance.takeLast(HEARD_SNIPPET_CHARS)
        } else {
            utterance
        }
        if (utterance.isEmpty()) {
            return snapshot(nowMillis)
        }
        lastVoiceAt = nowMillis
        val match = aligner.match(utterance, currentClean)
        lastMatched = match != null
        if (match == null) {
            // 没匹配上：若用户正在连续说话且光标已接近本句末尾，容错推进一小步，
            // 避免"下一句前几个字没识别对 → 永远卡住"（用户实测的核心痛点）。
            maybeNudgeForward(nowMillis)
            return snapshot(nowMillis)
        }

        // 回退滞回：往回滚需要更明确的证据，避免弱匹配让画面在两句之间来回跳。
        val effective = resolveBacktrack(match)
        if (effective == null) return snapshot(nowMillis)

        currentClean = effective.endClean.coerceIn(0, totalClean)
        val (unitIndex, fraction) = resolveUnit(currentClean, boundaryIsEnd = true)
        currentIndex = unitIndex
        characterProgress = fraction
        lastValidRecognitionAt = nowMillis
        hasStableMatch = true
        lastNudgeAt = 0L
        consecutiveNudges = 0
        return snapshot(nowMillis)
    }

    /**
     * 回退决策：
     *  - 命中点在光标附近或之后 → 直接采纳（前���是主路径）；
     *  - 命中点明显在光标之前（回头重读）→ **强证据立即回退**；弱证据需连续
     *    [BACKTRACK_CONFIRMATIONS] 次指向同一位置才回退，避免画面来回抖。
     */
    private fun resolveBacktrack(match: ScriptAligner.ScriptMatch): ScriptAligner.ScriptMatch? {
        val isBehind = match.startClean < currentClean - BACKTRACK_TOLERANCE
        if (!isBehind) {
            pendingBacktrack = null
            backtrackConfirmations = 0
            return match
        }
        if (match.tier == ScriptAligner.Tier.STRONG) {
            pendingBacktrack = null
            backtrackConfirmations = 0
            return match
        }
        if (pendingBacktrack?.startClean == match.startClean) {
            backtrackConfirmations++
        } else {
            pendingBacktrack = match
            backtrackConfirmations = 1
        }
        if (backtrackConfirmations >= BACKTRACK_CONFIRMATIONS) {
            pendingBacktrack = null
            backtrackConfirmations = 0
            return match
        }
        return null
    }

    /**
     * 容错推进：识别一直有内容（用户在说）但连续匹配不上，且光标已到当前句末尾附近时，
     * 把光标往下一句推一小步（到下一句开头），让跟读不至于卡死。
     *
     * 保护措施（避免"念完一句插话"被误判成卡住而乱跑）：
     *  - 必须已经有过成功匹配（hasStableMatch）；
     *  - 必须**距上次成功匹配已超过 [STUCK_AFTER_MS]** —— 真正"卡住"时用户会持续念，
     *    而插话往往紧接着就出现；
     *  - 2.5s 内有语音活动、距上次推进有冷却、每次只推进一句；
     *  - 连续容错推进次数有上限，兜底防止长时间乱说话一路滚到底。
     */
    private fun maybeNudgeForward(nowMillis: Long) {
        if (!hasStableMatch) return
        if (nowMillis - lastVoiceAt > VOICE_ACTIVE_WINDOW_MS) return
        if (nowMillis - lastValidRecognitionAt < STUCK_AFTER_MS) return
        if (nowMillis - lastNudgeAt < NUDGE_COOLDOWN_MS) return
        if (consecutiveNudges >= MAX_CONSECUTIVE_NUDGES) return
        if (currentIndex >= lastUnitIndex) return
        val len = unitCleanLen.getOrElse(currentIndex) { 0 }
        if (len <= 0) return
        // 只在"当前句已念到 85% 以后"时容错推进，避免整句没念完就跳
        if (currentClean < unitCleanStart.getOrElse(currentIndex) { 0 } + (len * NUDGE_TAIL_RATIO).toInt()) return
        currentIndex += 1
        characterProgress = 0f
        currentClean = unitCleanStart.getOrElse(currentIndex) { 0 }
        lastNudgeAt = nowMillis
        consecutiveNudges++
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
        lastRecognitionAtMillis = if (hasStableMatch) lastValidRecognitionAt else null,
        lastHeard = lastHeard,
        lastMatched = lastMatched
    )

    companion object {
        /** 参与匹配的识别文本（清洗后）最大长度，取尾部。 */
        private const val MAX_UTT_CLEAN_CHARS = 120

        /** 状态里携带的"听到"摘要长度。 */
        private const val HEARD_SNIPPET_CHARS = 10

        /** 判定"用户正在说话"的时间窗：两次识别间隔超过它就不做容错推进。 */
        private const val VOICE_ACTIVE_WINDOW_MS = 2500L

        /** 容错推进的冷却时间，防止连续跳句。 */
        private const val NUDGE_COOLDOWN_MS = 900L

        /** 距上次成功匹配超过这么久仍匹配不上，才认定为「卡住」（区分插话）。 */
        private const val STUCK_AFTER_MS = 1500L

        /** 连续容错推进上限，兜底防止乱说话一路滚到底。 */
        private const val MAX_CONSECUTIVE_NUDGES = 3

        /** 当前句念到这个比例之后，才允许容错推进到下一句。 */
        private const val NUDGE_TAIL_RATIO = 0.85f

        /** 命中点比光标落后这么多字才算"往回滚"。 */
        private const val BACKTRACK_TOLERANCE = 8

        /** 弱回退需要连续确认几次才真的滚回去（防抖动）。 */
        private const val BACKTRACK_CONFIRMATIONS = 2
    }
}
