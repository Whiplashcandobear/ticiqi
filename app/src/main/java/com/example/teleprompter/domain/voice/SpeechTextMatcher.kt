package com.example.teleprompter.domain.voice

import com.example.teleprompter.domain.model.SpeechUnit

data class SpeechMatch(
    val unitIndex: Int,
    val coverage: Float,
    val characterProgress: Float,
    val matchedTokenCount: Int,
    val isComplete: Boolean,
    val score: Float
)

/**
 * Matches a partial English recognition result to a nearby sentence without
 * changing the sentence boundaries created by SpeechTextParser.
 */
class SpeechTextMatcher(
    units: List<SpeechUnit>,
    private val maxLookAhead: Int = 4
) {
    private val unitTokens = units.map { tokenize(it.rawText) }

    fun match(spokenText: String, anchorIndex: Int): SpeechMatch? {
        if (unitTokens.isEmpty()) return null
        val spokenTokens = tokenize(spokenText)
        if (spokenTokens.isEmpty()) return null

        val safeAnchor = anchorIndex.coerceIn(0, unitTokens.lastIndex)
        return (safeAnchor..minOf(unitTokens.lastIndex, safeAnchor + maxLookAhead))
            .mapNotNull { index -> matchUnit(index, unitTokens[index], spokenTokens, safeAnchor) }
            .maxWithOrNull(
                compareBy<SpeechMatch> { it.score }
                    .thenBy { if (it.unitIndex == safeAnchor) 0 else 1 }
                    .thenByDescending { it.matchedTokenCount }
            )
    }

    private fun matchUnit(
        unitIndex: Int,
        expected: List<String>,
        spoken: List<String>,
        anchorIndex: Int
    ): SpeechMatch? {
        if (expected.isEmpty()) return null
        val best = findBestRun(expected, spoken) ?: return null
        val coverage = best.length.toFloat() / expected.size
        val spokenCoverage = best.length.toFloat() / spoken.size
        val characterProgress = characterProgress(expected, spoken, best)
        val isComplete = coverage >= 0.86f && characterProgress >= 0.86f

        // The current sentence can react to a single partial word. A later
        // sentence needs at least two matching words to avoid jumps caused by
        // common words such as "garden", "today", or "people".
        val accepted = if (unitIndex == anchorIndex) {
            best.length >= 1
        } else {
            best.length >= 2 || isComplete
        }
        if (!accepted) return null

        val distancePenalty = (unitIndex - anchorIndex).coerceAtLeast(0) * 0.025f
        return SpeechMatch(
            unitIndex = unitIndex,
            coverage = coverage,
            characterProgress = characterProgress,
            matchedTokenCount = best.length,
            isComplete = isComplete,
            score = coverage * 0.75f + spokenCoverage * 0.25f - distancePenalty
        )
    }

    private fun findBestRun(expected: List<String>, spoken: List<String>): TokenRun? {
        var best: TokenRun? = null
        expected.indices.forEach { expectedStart ->
            spoken.indices.forEach { spokenStart ->
                var length = 0
                while (
                    expectedStart + length < expected.size &&
                    spokenStart + length < spoken.size &&
                    similar(expected[expectedStart + length], spoken[spokenStart + length])
                ) {
                    length++
                }
                if (length == 0) return@forEach
                val candidate = TokenRun(expectedStart, spokenStart, length)
                if (best == null || candidate.isBetterThan(best!!)) best = candidate
            }
        }
        return best
    }

    private fun characterProgress(expected: List<String>, spoken: List<String>, run: TokenRun): Float {
        val lastExpectedIndex = run.expectedStart + run.length - 1
        val lastSpokenIndex = run.spokenStart + run.length - 1
        val expectedToken = expected[lastExpectedIndex]
        val spokenToken = spoken[lastSpokenIndex]
        val lastTokenProgress = when {
            expectedToken == spokenToken -> 1f
            expectedToken.startsWith(spokenToken) -> {
                (spokenToken.length.toFloat() / expectedToken.length).coerceIn(0.15f, 1f)
            }
            else -> 1f
        }
        return ((lastExpectedIndex + lastTokenProgress) / expected.size.toFloat()).coerceIn(0f, 1f)
    }

    private fun similar(expected: String, spoken: String): Boolean =
        expected == spoken || expected.startsWith(spoken) || spoken.startsWith(expected)

    private data class TokenRun(
        val expectedStart: Int,
        val spokenStart: Int,
        val length: Int
    ) {
        fun isBetterThan(other: TokenRun): Boolean =
            length > other.length || (length == other.length && expectedStart < other.expectedStart)
    }

    private companion object {
        private val tokenPattern = Regex("[a-z0-9]+(?:['’-][a-z0-9]+)*")

        fun tokenize(text: String): List<String> = tokenPattern.findAll(
            text.lowercase().replace('’', '\'').replace('\r', ' ').replace('\n', ' ')
        ).map { it.value }.toList()
    }
}
