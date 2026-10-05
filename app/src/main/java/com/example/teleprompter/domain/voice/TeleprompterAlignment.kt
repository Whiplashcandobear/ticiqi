package com.example.teleprompter.domain.voice

/**
 * Character-level transcript-to-script alignment, ported from 飓风提词器
 * (client/src/lib/teleprompter/alignment/engine.ts) to Kotlin.
 *
 * The original ticiqi [SpeechTextMatcher] only matched English word tokens, so
 * Chinese speech never aligned. This engine works on individual characters
 * (CJK + alphanumerics), tolerates small ASR omissions/hallucinations via a
 * ±3 character skip window, and re-syncs within a 24-character look-ahead when
 * the cursor drifts. It is deliberately pure (no Android dependency) so it can
 * be unit-tested deterministically.
 */
data class AlignmentResult(
    /** Raw index into the original content at the current aligned position. */
    val rawIndex: Int,
    /** Index into the cleaned (punctuation-stripped) script. */
    val cleanIndex: Int,
    /** How many characters were matched in the latest decode. */
    val matchedLength: Int,
    val strategy: String,
    val text: String,
    val isFinal: Boolean
)

class TeleprompterAlignment(content: String) {
    private val cleanScript = StringBuilder()
    private val indexMap = mutableListOf<Int>() // clean index -> raw index
    private var anchorIndex = -1
    private var currentIndex = -1

    init {
        for (i in content.indices) {
            val ch = content[i].toString()
            if (CLEAN_CHAR_REGEX.matches(ch)) {
                cleanScript.append(ch)
                indexMap.add(i)
            }
        }
    }

    val cleanLength: Int get() = indexMap.size

    fun reset() {
        anchorIndex = -1
        currentIndex = -1
    }

    /** Point the cursor at a raw position (e.g. when the user scrubs). */
    fun setCurrentRawIndex(rawIndex: Int) {
        if (rawIndex < 0) {
            anchorIndex = -1
            currentIndex = -1
            return
        }
        var clean = -1
        for (i in indexMap.indices) {
            if (indexMap[i] >= rawIndex) {
                clean = i
                break
            }
        }
        if (clean == -1) clean = indexMap.lastIndex.coerceAtLeast(0)
        anchorIndex = clean
        currentIndex = clean
    }

    fun currentCleanIndex(): Int = currentIndex

    private fun toRawIndex(cleanIndex: Int): Int =
        if (cleanIndex in indexMap.indices) indexMap[cleanIndex] else -1

    private fun normalizeTranscript(text: String): String =
        text.filter { CLEAN_CHAR_REGEX.matches(it.toString()) }

    private fun charLevelMatch(scriptStart: Int, transcript: String): MatchResult {
        var scriptIndex = scriptStart
        var transcriptIndex = 0
        var lastMatchedScriptIndex = scriptStart - 1
        var matchedLength = 0
        while (scriptIndex < cleanScript.length && transcriptIndex < transcript.length) {
            val scriptChar = cleanScript[scriptIndex].toString()
            val transcriptChar = transcript[transcriptIndex].toString()
            if (scriptChar == transcriptChar) {
                lastMatchedScriptIndex = scriptIndex
                matchedLength++
                scriptIndex++
                transcriptIndex++
                continue
            }
            var aligned = false
            for (skipScript in 1..SKIP_TOLERANCE) {
                if (scriptIndex + skipScript >= cleanScript.length) break
                if (cleanScript[scriptIndex + skipScript].toString() == transcriptChar) {
                    scriptIndex += skipScript
                    aligned = true
                    break
                }
            }
            if (aligned) continue
            for (skipTranscript in 1..SKIP_TOLERANCE) {
                if (transcriptIndex + skipTranscript >= transcript.length) break
                if (scriptChar == transcript[transcriptIndex + skipTranscript].toString()) {
                    transcriptIndex += skipTranscript
                    aligned = true
                    break
                }
            }
            if (aligned) continue
            scriptIndex++
            transcriptIndex++
        }
        return MatchResult(
            matchedLength = matchedLength,
            scriptAdvance = maxOf(0, lastMatchedScriptIndex - scriptStart + 1),
            transcriptAdvance = transcriptIndex
        )
    }

    private fun findBestMatch(scriptStart: Int, transcript: String): WindowedMatchResult {
        val best = charLevelMatch(scriptStart, transcript)
        if (best.matchedLength > 0) return WindowedMatchResult(best, 0)
        val maxOffset = minOf(RESYNC_LOOKAHEAD_WINDOW, maxOf(0, cleanScript.length - scriptStart - 1))
        var bestMatch = best
        var bestOffset = 0
        for (offset in 1..maxOffset) {
            val candidate = charLevelMatch(scriptStart + offset, transcript)
            if (candidate.matchedLength > bestMatch.matchedLength) {
                bestMatch = candidate
                bestOffset = offset
            }
            if (bestMatch.matchedLength >= minOf(6, transcript.length)) break
        }
        return WindowedMatchResult(bestMatch, bestOffset)
    }

    fun consumeTranscript(text: String, isFinal: Boolean): AlignmentResult {
        if (cleanScript.isEmpty()) {
            return AlignmentResult(-1, -1, 0, "none", text, isFinal)
        }
        val cleanText = normalizeTranscript(text)
        if (cleanText.isEmpty()) {
            val raw = toRawIndex(currentIndex)
            if (isFinal) anchorIndex = currentIndex
            return AlignmentResult(raw, currentIndex, 0, "empty", text, isFinal)
        }
        val anchorStart = maxOf(0, anchorIndex + 1)
        val (anchorMatch, anchorOffset) = findBestMatch(anchorStart, cleanText)
        if (anchorMatch.matchedLength == 0) {
            val raw = toRawIndex(currentIndex)
            if (isFinal) anchorIndex = currentIndex
            return AlignmentResult(raw, currentIndex, 0, "none", text, isFinal)
        }
        val resolvedAnchorStart = anchorStart + anchorOffset
        val nextCurrentIndex = minOf(resolvedAnchorStart + anchorMatch.scriptAdvance - 1, cleanScript.lastIndex)
        currentIndex = maxOf(currentIndex, nextCurrentIndex)
        if (isFinal) anchorIndex = currentIndex
        val raw = toRawIndex(currentIndex)
        val strategy = if (anchorStart == 0 && anchorOffset == 0) "anchor" else "char_resync"
        return AlignmentResult(raw, currentIndex, anchorMatch.matchedLength, strategy, text, isFinal)
    }

    private data class MatchResult(
        val matchedLength: Int,
        val scriptAdvance: Int,
        val transcriptAdvance: Int
    )

    private data class WindowedMatchResult(val match: MatchResult, val offset: Int)

    companion object {
        private val CLEAN_CHAR_REGEX = Regex("[a-zA-Z0-9\\u3400-\\u4dbf\\u4e00-\\u9fa5]")
        private const val SKIP_TOLERANCE = 3
        private const val RESYNC_LOOKAHEAD_WINDOW = 24

        fun isCleanChar(ch: Char): Boolean = CLEAN_CHAR_REGEX.matches(ch.toString())
    }
}
