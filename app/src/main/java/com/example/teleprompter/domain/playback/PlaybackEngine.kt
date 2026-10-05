package com.example.teleprompter.domain.playback

import com.example.teleprompter.domain.model.SpeechUnit
import com.example.teleprompter.domain.parser.SpeechTextParser
import com.example.teleprompter.domain.parser.countReadingUnits
import kotlin.math.max
import kotlin.math.min

data class PlaybackWindowItem(
    val index: Int,
    val unit: SpeechUnit,
    val isCurrent: Boolean
)

data class PlaybackPosition(val index: Int, val progress: Float)

enum class PlaybackControlsArrangement { STACKED, COMPACT }

fun playbackControlsArrangement(landscape: Boolean): PlaybackControlsArrangement =
    if (landscape) PlaybackControlsArrangement.COMPACT else PlaybackControlsArrangement.STACKED

/**
 * Keeps context for ordinary sentences, but puts a long current sentence at the
 * top of the viewport so its wrapped tail is not hidden below the controls.
 */
fun autoScrollStartIndex(
    units: List<SpeechUnit>,
    currentIndex: Int,
    contextItems: Int,
    longSentenceUnitThreshold: Int = 18
): Int {
    require(contextItems >= 0) { "contextItems must not be negative" }
    require(longSentenceUnitThreshold > 0) { "longSentenceUnitThreshold must be positive" }
    if (units.isEmpty()) return 0

    val safeIndex = currentIndex.coerceIn(0, units.lastIndex)
    return if (countReadingUnits(units[safeIndex].rawText) >= longSentenceUnitThreshold) {
        safeIndex
    } else {
        (safeIndex - contextItems).coerceAtLeast(0)
    }
}

fun windowAround(units: List<SpeechUnit>, currentIndex: Int): List<PlaybackWindowItem> {
    if (units.isEmpty()) return emptyList()
    val safeIndex = currentIndex.coerceIn(0, units.lastIndex)
    val start = max(0, safeIndex - 2)
    val end = minOf(units.size, safeIndex + 3)
    return units.subList(start, end).mapIndexed { offset, unit ->
        PlaybackWindowItem(index = start + offset, unit = unit, isCurrent = start + offset == safeIndex)
    }
}

/**
 * Builds the playback timeline without changing the author's sentence boundaries.
 * Long sentences are wrapped by the viewport, but remain one highlighted/playback unit.
 */
fun playbackUnits(text: String): List<SpeechUnit> =
    SpeechTextParser().parse(text).filterNot(SpeechUnit::isBlank)

fun countdownValues(seconds: Int): List<Int> = seconds.coerceAtLeast(0).downTo(0).toList()

fun positionForFraction(fraction: Float, unitCount: Int): PlaybackPosition {
    require(unitCount > 0) { "unitCount must be positive" }
    val normalized = fraction.coerceIn(0f, 1f)
    val scaled = normalized * unitCount
    val index = min(scaled.toInt(), unitCount - 1)
    val innerProgress = if (normalized == 1f && index == unitCount - 1) {
        1f
    } else {
        (scaled - index).coerceIn(0f, 1f)
    }
    return PlaybackPosition(index = index, progress = innerProgress)
}

fun segmentDurationSeconds(unit: SpeechUnit, rate: Int): Double =
    max(countReadingUnits(unit.rawText).toDouble() / rate.coerceAtLeast(1) * 60.0, 1.0)
