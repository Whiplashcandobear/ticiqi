package com.example.teleprompter.domain.voice

/**
 * 台本对齐器：把一段识别结果（partial 或 final）在台本里找"这段话最可能对应的位置"。
 *
 * 与旧版逐字消费（±3 跳字容忍、永不后退）不同，这里按**整句打分、双向匹配**：
 *  - 在光标附近（前 [Companion.BACK_WINDOW] / 后 [Companion.FORWARD_WINDOW] 个字符）滑动窗口试匹配，
 *    得分 = 识别文本里有多大比例的字能在台本中**按顺序**对上（容忍少量识别漏字/插字）；
 *  - 得分低于阈值 → 判定为与台本无关的话（乱说话/寒暄/接电话），返回 null，**光标原地不动**；
 *  - 匹配点在光标之前（用户回头重读）→ 光标**自动滚回**；
 *  - 匹配点在光标之后 → 正常前移。
 *
 * 无状态：每次用整段 utterance 重新匹配，流式识别的重复部分结果不会造成二次前进。
 * 纯 Kotlin、无 Android 依赖，可单元测试。
 */
class ScriptAligner(script: String) {

    private val cleanScript: String
    private val indexMap: IntArray // clean index -> raw index

    val cleanLength: Int get() = cleanScript.length

    init {
        val sb = StringBuilder(script.length)
        val map = ArrayList<Int>(script.length)
        for (i in script.indices) {
            if (isCleanChar(script[i])) {
                sb.append(script[i])
                map.add(i)
            }
        }
        cleanScript = sb.toString()
        indexMap = map.toIntArray()
    }

    fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) if (isCleanChar(ch)) sb.append(ch)
        return sb.toString()
    }

    /**
     * 在 [centerClean] 附近匹配一段识别文本。
     * @return null 表示"与台本无关，忽略"；否则返回匹配区间（clean 索引，end 为排他边界）。
     */
    fun match(utterance: String, centerClean: Int): ScriptMatch? {
        val utt = cleanText(utterance)
        if (utt.length < MIN_UTT_CHARS || cleanScript.isEmpty()) return null
        val center = centerClean.coerceIn(0, cleanScript.length - 1)
        val lo = (center - BACK_WINDOW).coerceAtLeast(0)
        val hi = (center + FORWARD_WINDOW).coerceAtMost(cleanScript.length - 1)
        val needed = (utt.length * SCORE_THRESHOLD).toInt() + 1
        var best: ScriptMatch? = null
        for (start in lo..hi) {
            val hit = scoreAt(start, utt, needed) ?: continue
            if (hit.score < SCORE_THRESHOLD) continue
            val distance = Math.abs(start - center)
            val adjusted = hit.score - distance * DISTANCE_PENALTY
            val currentBest = best
            if (currentBest == null || adjusted > currentBest.score) {
                best = ScriptMatch(startClean = start, endClean = hit.endExclusive, score = adjusted)
            }
            if (hit.score >= 0.99f && distance == 0) break // 就地连续朗读，无需再扫
        }
        return best
    }

    private data class AlignHit(val endExclusive: Int, val score: Float)

    /**
     * 从台本 [start] 开始对齐 [utt]，返回能对上的比例与匹配终点。
     *
     * 规则：
     *  - 台本跳 1..[SCRIPT_SKIP] 字命中 → ASR 漏字（识别删了字）；
     *  - 识别跳 1..[UTT_SKIP] 字命中 → 用户/ASR 插了字（如语气词）；
     *  - 连续插字超过 [MISS_SLACK] 后强制推进台本指针，防止同一个台本字
     *    被重复噪声"蹭"出高分（这正是旧版乱说话也前进的根因）。
     */
    private fun scoreAt(start: Int, utt: String, needed: Int): AlignHit? {
        var j = start
        var i = 0
        var matched = 0
        var misses = 0
        var lastMatch = start - 1
        while (i < utt.length && j < cleanScript.length) {
            // 剩余字符即使全对上也到不了阈值，提前止损
            if (matched + (utt.length - i) < needed) break
            val u = utt[i]
            if (cleanScript[j] == u) {
                matched++; lastMatch = j; j++; i++; misses = 0
                continue
            }
            // 台本跳 1..SCRIPT_SKIP 字命中 → ASR 漏字
            var scriptSkip = -1
            for (k in 1..SCRIPT_SKIP) {
                if (j + k >= cleanScript.length) break
                if (cleanScript[j + k] == u) { scriptSkip = k; break }
            }
            if (scriptSkip > 0) {
                matched++; lastMatch = j + scriptSkip; j += scriptSkip + 1; i++; misses = 0
                continue
            }
            // 识别跳 1..UTT_SKIP 字命中 → 插了字（语气词等）
            var uttSkip = -1
            for (k in 1..UTT_SKIP) {
                if (i + k >= utt.length) break
                if (cleanScript[j] == utt[i + k]) { uttSkip = k; break }
            }
            if (uttSkip > 0) {
                matched++; lastMatch = j; j++; i += uttSkip + 1; misses = 0
                continue
            }
            // 真不匹配：容忍少量插字；持续噪声则强制推进台本指针，
            // 防止同一个台本字被重复噪声"蹭"出高分（旧版乱说话也前进的根因）。
            misses++
            if (misses > MISS_SLACK) {
                j++
                misses = MISS_SLACK
            }
            i++
        }
        if (matched == 0) return null
        return AlignHit(endExclusive = lastMatch + 1, score = matched.toFloat() / utt.length)
    }

    data class ScriptMatch(val startClean: Int, val endClean: Int, val score: Float)

    companion object {
        private val CLEAN_CHAR_REGEX = Regex("[a-zA-Z0-9\\u3400-\\u4dbf\\u4e00-\\u9fa5]")

        fun isCleanChar(ch: Char): Boolean = CLEAN_CHAR_REGEX.matches(ch.toString())

        /** 识别文本至少要这么多个有效字才参与匹配（太短容易误匹配到常见词）。 */
        private const val MIN_UTT_CHARS = 5

        /** 允许回头重读的范围（clean 字符数）。 */
        private const val BACK_WINDOW = 400

        /** 允许向前越读（跳读/赶进度）的范围。 */
        private const val FORWARD_WINDOW = 400

        /** ASR 漏字容忍（台本跳字）。 */
        private const val SCRIPT_SKIP = 2

        /** ASR 插字容忍（识别多字）。 */
        private const val UTT_SKIP = 2

        /** 连续插字容忍上限，超过则强制推进，防噪声蹭匹配。 */
        private const val MISS_SLACK = 3

        /** 匹配得分阈值：识别文本至少 70% 的字能按顺序对上台本才动光标。 */
        private const val SCORE_THRESHOLD = 0.70f

        /** 距离惩罚：得分相近时优先离光标近的匹配。 */
        private const val DISTANCE_PENALTY = 0.0005f
    }
}
