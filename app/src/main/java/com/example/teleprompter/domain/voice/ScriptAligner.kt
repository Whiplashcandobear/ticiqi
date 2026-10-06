package com.example.teleprompter.domain.voice

/**
 * 台本对齐器：把一段识别结果（partial 或 final）在台本里找"这段话最可能对应的位置"。
 *
 * ## 为什么需要多层策略
 * 流式识别的 partial 是**自会话起的累积文本**，只能取尾部参与匹配；而识别必然有错字、
 * 漏字、语气词。早期实现只做「整段对齐 + 固定 70% 阈值」，一旦长文本里错字累积，
 * 得分就掉到阈值以下 —— 明明已经念到下一句，光标却卡住不动（用户实测的主要问题）。
 *
 * 现在按**证据强度**分三层，从强到弱：
 *  1. [Tier.STRONG] **整段对齐**：utt 全文按顺序对上台本（容忍少量漏字/插字），
 *     得分 ≥ 0.70 才算。用于"连续朗读一大段"的主路径。
 *  2. [Tier.ANCHOR] **尾部锚点**：取 utt 尾部 16/12/8 字分别匹配（流式 ASR 尾部最新、
 *     错字最少），阈值更高（0.78）但不受长文本拖累。用于"刚念完一两句"的推进。
 *  3. [Tier.FRAGMENT] **片段命中**：utt 尾部存在长度 ≥5 的连续精确命中片段，
 *     且该片段在台本中不是高频套话 —— 命中即推进。用于识别质量极差时的兜底。
 *
 * 三层结果按「置信度」择优返回（见 [pickBest]）：整段命中优先，其次锚点，片段兜底。
 * 这样既避免了"整段得分不够就卡住"，又不会因为个别词命中就乱跳（片段层有唯一性过滤）。
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

    /** 匹配证据强度分层。 */
    enum class Tier { STRONG, ANCHOR, FRAGMENT }

    data class ScriptMatch(
        val startClean: Int,
        val endClean: Int,
        val score: Float,
        val tier: Tier = Tier.STRONG
    )

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

        // ① 整段对齐（强证据）
        val strong = scan(utt, lo, hi, center, SCORE_THRESHOLD)

        // ② 尾部锚点（中证据）：长度越长要求越低，全部尝试取最优
        var anchor: ScriptMatch? = null
        for (len in ANCHOR_LENGTHS) {
            if (utt.length < len) continue
            val piece = utt.takeLast(len)
            // 锚点越短越容易被"撞词"误匹配，阈值相应提高
            val threshold = when {
                len >= 16 -> ANCHOR_THRESHOLD_LO
                len >= 12 -> ANCHOR_THRESHOLD_MID
                else -> ANCHOR_THRESHOLD_HI
            }
            val hit = scan(piece, lo, hi, center, threshold)
                ?: continue
            val candidate = ScriptMatch(hit.startClean, hit.endClean, hit.score, Tier.ANCHOR)
            if (anchor == null || candidate.score > anchor.score) anchor = candidate
        }

        // ③ 连续片段命中（弱证据兜底）
        val fragment = fragmentMatch(utt, lo, hi)

        return pickBest(strong, anchor, fragment)
    }

    /** 择优：强证据优先；同为弱证据时取得分高者；同分取匹配更长（end 更靠后）者。 */
    private fun pickBest(vararg candidates: ScriptMatch?): ScriptMatch? {
        var best: ScriptMatch? = null
        for (c in candidates) {
            if (c == null) continue
            val cur = best
            best = when {
                cur == null -> c
                c.tier.ordinal < cur.tier.ordinal -> c
                c.tier.ordinal == cur.tier.ordinal && c.score > cur.score -> c
                c.tier.ordinal == cur.tier.ordinal && c.score == cur.score &&
                    c.endClean > cur.endClean -> c
                else -> cur
            }
        }
        return best
    }

    /**
     * 在 [lo]..[hi] 内滑动，寻找 [utt] 的最佳对齐点。
     */
    private fun scan(
        utt: String,
        lo: Int,
        hi: Int,
        center: Int,
        threshold: Float
    ): ScriptMatch? {
        val needed = (utt.length * threshold).toInt() + 1
        var best: ScriptMatch? = null
        for (start in lo..hi) {
            val hit = scoreAt(start, utt, needed) ?: continue
            if (hit.score < threshold) continue
            val distance = Math.abs(start - center)
            val adjusted = hit.score - distance * DISTANCE_PENALTY
            val cur = best
            if (cur == null || adjusted > cur.score) {
                best = ScriptMatch(startClean = start, endClean = hit.endExclusive, score = hit.score)
            }
        }
        return best
    }

    /**
     * 片段兜底：utt 尾部取长度 [FRAGMENT_MIN]..[FRAGMENT_MAX] 的片段，
     * 要求在台本窗口内**精确连续命中**，且该片段在整个台本中出现次数不多
     * （过滤"是不是这样""我不知道"这类高频套话，避免乱跳）。
     */
    private fun fragmentMatch(utt: String, lo: Int, hi: Int): ScriptMatch? {
        if (utt.length < FRAGMENT_MIN) return null
        val maxLen = minOf(FRAGMENT_MAX, utt.length)
        for (len in maxLen downTo FRAGMENT_MIN) {
            val frag = utt.takeLast(len)
            if (frag.isBlank()) continue
            val idx = indexOfInWindow(frag, lo, hi) ?: continue
            // 高频片段不可信
            if (countOccurrences(frag) > FRAGMENT_MAX_OCCURRENCES) continue
            // 得分按片段长度折算：越长越可信
            return ScriptMatch(
                startClean = idx,
                endClean = idx + len,
                score = (len.toFloat() / maxLen) * FRAGMENT_SCORE_CEILING,
                tier = Tier.FRAGMENT
            )
        }
        return null
    }

    /** 在 [lo]..[hi]（含）范围内查找 [fragment] 的首个出现位置。 */
    private fun indexOfInWindow(fragment: String, lo: Int, hi: Int): Int? {
        var from = lo.coerceAtLeast(0)
        while (from <= hi) {
            val idx = cleanScript.indexOf(fragment, from)
            if (idx < 0 || idx > hi) return null
            return idx
        }
        return null
    }

    private fun countOccurrences(fragment: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val i = cleanScript.indexOf(fragment, from)
            if (i < 0) break
            count++
            if (count > FRAGMENT_MAX_OCCURRENCES) return count
            from = i + 1
        }
        return count
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

    companion object {
        private val CLEAN_CHAR_REGEX = Regex("[a-zA-Z0-9\\u3400-\\u4dbf\\u4e00-\\u9fa5]")

        fun isCleanChar(ch: Char): Boolean = CLEAN_CHAR_REGEX.matches(ch.toString())

        /** 识别文本至少要这么多个有效字才参与匹配（太短容易误匹配到常见词）。 */
        private const val MIN_UTT_CHARS = 4

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

        /** 整段匹配阈值：识别文本至少 70% 的字能按顺序对上台本才动光标。 */
        private const val SCORE_THRESHOLD = 0.70f

        /** 尾部锚点长度候选（从长到短）。 */
        private val ANCHOR_LENGTHS = intArrayOf(16, 12, 8)

        private const val ANCHOR_THRESHOLD_LO = 0.78f
        private const val ANCHOR_THRESHOLD_MID = 0.80f
        private const val ANCHOR_THRESHOLD_HI = 0.85f

        /** 片段兜底的最短/最长片段长度。 */
        private const val FRAGMENT_MIN = 5
        private const val FRAGMENT_MAX = 10

        /** 片段在台本中出现超过这么多次就认为是套话，丢弃。 */
        private const val FRAGMENT_MAX_OCCURRENCES = 2

        /** 片段兜底层的得分上限（永远低于锚点层，保证层级优先）。 */
        private const val FRAGMENT_SCORE_CEILING = 0.6f

        /** 距离惩罚：得分相近时优先离光标近的匹配。 */
        private const val DISTANCE_PENALTY = 0.0005f
    }
}
