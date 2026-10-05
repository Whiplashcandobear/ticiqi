package com.example.teleprompter.domain.voice

import com.example.teleprompter.domain.model.SpeechUnit

data class VoiceFollowState(
    val currentUnitIndex: Int,
    val characterProgress: Float,
    val isFallbackToWpm: Boolean,
    val hasStableMatch: Boolean,
    val lastRecognitionAtMillis: Long?
)

/**
 * Chinese-aware voice-follow state machine. It is deliberately independent of
 * Android so partial-result behavior can be tested deterministically.
 *
 * Replaces the old English-token [SpeechTextMatcher] with [TeleprompterAlignment],
 * which matches the recognized text to the script at the character level. That is
 * what makes 语音跟随 work for Chinese: the cursor now advances by how many
 * characters were actually spoken, instead of by English words (which are zero
 * for Chinese).
 */
class VoiceFollowEngine(
    units: List<SpeechUnit>,
    initialUnitIndex: Int = 0,
    private val fallbackAfterMillis: Long = 2_000L,
    startAtMillis: Long = System.currentTimeMillis()
) {
    private val alignment: TeleprompterAlignment
    private val unitCleanStart: IntArray
    private val unitCleanLen: IntArray
    private val totalClean: Int

    private val lastUnitIndex = (units.size - 1).coerceAtLeast(0)
    private var currentIndex = initialUnitIndex.coerceIn(0, lastUnitIndex)
    private var characterProgress = 0f
    private var lastValidRecognitionAt = startAtMillis
    private var fallbackToWpm = false
    private var hasStableMatch = false
    private var recoveryStreak = 0

    init {
        val content = units.joinToString(" ") { it.rawText }
        alignment = TeleprompterAlignment(content)
        val starts = IntArray(units.size)
        val lengths = IntArray(units.size)
        var cursor = 0
        for (k in units.indices) {
            val cleanCount = units[k].rawText.count { ch ->
                TeleprompterAlignment.isCleanChar(ch)
            }
            starts[k] = cursor
            lengths[k] = cleanCount
            cursor += cleanCount
        }
        unitCleanStart = starts
        unitCleanLen = lengths
        totalClean = cursor
    }

    fun startSession(nowMillis: Long = System.currentTimeMillis()) {
        lastValidRecognitionAt = nowMillis
        fallbackToWpm = false
        hasStableMatch = false
        recoveryStreak = 0
        seekAlignmentToCursor()
    }

    fun setCursor(unitIndex: Int, progress: Float = 0f) {
        currentIndex = unitIndex.coerceIn(0, lastUnitIndex)
        characterProgress = progress.coerceIn(0f, 1f)
        seekAlignmentToCursor()
    }

    fun state(nowMillis: Long = System.currentTimeMillis()): VoiceFollowState = snapshot(nowMillis)

    fun onRecognition(text: String, nowMillis: Long, isFinal: Boolean = false): VoiceFollowState {
        val res = alignment.consumeTranscript(text, isFinal)
        if (res.cleanIndex < 0) return onTick(nowMillis)

        val (unitIndex, fraction) = resolveUnit(res.cleanIndex)
        // Never move the cursor backwards; only advance or hold.
        if (unitIndex >= currentIndex) {
            currentIndex = unitIndex
            characterProgress = fraction
        }
        lastValidRecognitionAt = nowMillis
        hasStableMatch = true
        fallbackToWpm = false
        recoveryStreak = 0
        return snapshot(nowMillis)
    }

    fun onTick(nowMillis: Long): VoiceFollowState {
        if (nowMillis - lastValidRecognitionAt >= fallbackAfterMillis) {
            fallbackToWpm = true
            recoveryStreak = 0
        }
        return snapshot(nowMillis)
    }

    private fun seekAlignmentToCursor() {
        val start = unitCleanStart.getOrElse(currentIndex) { 0 }
        val len = unitCleanLen.getOrElse(currentIndex) { 0 }
        val raw = start + (characterProgress * len).toInt()
        alignment.setCurrentRawIndex(raw)
    }

    private fun resolveUnit(cleanIndex: Int): Pair<Int, Float> {
        if (units.isEmpty()) return 0 to 0f
        val safe = cleanIndex.coerceIn(0, totalClean.coerceAtLeast(0))
        for (k in units.indices) {
            val start = unitCleanStart[k]
            val len = unitCleanLen[k]
            if (safe < start + len) {
                val fraction = if (len > 0) {
                    ((safe - start + 1).toFloat() / len).coerceIn(0f, 1f)
                } else 0f
                return k to fraction
            }
        }
        return units.lastIndex to 1f
    }

    private fun snapshot(nowMillis: Long): VoiceFollowState = VoiceFollowState(
        currentUnitIndex = currentIndex,
        characterProgress = characterProgress,
        isFallbackToWpm = fallbackToWpm,
        hasStableMatch = hasStableMatch,
        lastRecognitionAtMillis = if (hasStableMatch) lastValidRecognitionAt else null
    )
}
