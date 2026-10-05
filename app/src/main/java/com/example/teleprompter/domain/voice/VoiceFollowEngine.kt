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
 * Sentence-level voice-follow state machine. It is deliberately independent
 * of Android so partial-result behavior can be tested deterministically.
 */
class VoiceFollowEngine(
    units: List<SpeechUnit>,
    initialUnitIndex: Int = 0,
    private val fallbackAfterMillis: Long = 2_000L,
    startAtMillis: Long = System.currentTimeMillis()
) {
    private val matcher = SpeechTextMatcher(units)
    private val lastUnitIndex = (units.size - 1).coerceAtLeast(0)
    private var currentIndex = initialUnitIndex.coerceIn(0, lastUnitIndex)
    private var characterProgress = 0f
    private var lastValidRecognitionAt = startAtMillis
    private var fallbackToWpm = false
    private var hasStableMatch = false
    private var candidateIndex: Int? = null
    private var candidateStreak = 0
    private var recoveryStreak = 0

    fun startSession(nowMillis: Long = System.currentTimeMillis()) {
        lastValidRecognitionAt = nowMillis
        fallbackToWpm = false
        hasStableMatch = false
        candidateIndex = null
        candidateStreak = 0
        recoveryStreak = 0
    }

    fun setCursor(unitIndex: Int, progress: Float = 0f) {
        currentIndex = unitIndex.coerceIn(0, lastUnitIndex)
        characterProgress = progress.coerceIn(0f, 1f)
        candidateIndex = null
        candidateStreak = 0
    }

    fun state(nowMillis: Long = System.currentTimeMillis()): VoiceFollowState = snapshot(nowMillis)

    fun onRecognition(text: String, nowMillis: Long): VoiceFollowState {
        val match = matcher.match(text, currentIndex)
        if (match == null) return onTick(nowMillis)

        lastValidRecognitionAt = nowMillis
        hasStableMatch = true
        if (match.unitIndex == currentIndex) {
            characterProgress = maxOf(characterProgress, match.characterProgress)
            candidateIndex = null
            candidateStreak = 0
        } else if (match.unitIndex == currentIndex + 1) {
            if (candidateIndex == match.unitIndex) candidateStreak++ else {
                candidateIndex = match.unitIndex
                candidateStreak = 1
            }
            val canCommit = match.isComplete || match.coverage >= 0.55f || candidateStreak >= 2
            if (canCommit) {
                currentIndex = match.unitIndex.coerceAtMost(lastUnitIndex)
                characterProgress = match.characterProgress
                candidateIndex = null
                candidateStreak = 0
            }
        }

        if (fallbackToWpm) {
            recoveryStreak++
            if (recoveryStreak >= 2) {
                fallbackToWpm = false
                recoveryStreak = 0
            }
        }
        return snapshot(nowMillis)
    }

    fun onTick(nowMillis: Long): VoiceFollowState {
        if (nowMillis - lastValidRecognitionAt >= fallbackAfterMillis) {
            fallbackToWpm = true
            recoveryStreak = 0
        }
        return snapshot(nowMillis)
    }

    private fun snapshot(nowMillis: Long): VoiceFollowState = VoiceFollowState(
        currentUnitIndex = currentIndex,
        characterProgress = characterProgress,
        isFallbackToWpm = fallbackToWpm,
        hasStableMatch = hasStableMatch,
        lastRecognitionAtMillis = if (hasStableMatch) lastValidRecognitionAt else null
    )
}
