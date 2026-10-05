package com.example.teleprompter.domain.playback

import kotlin.math.roundToInt

private const val MIN_WPM = 60
private const val MAX_WPM = 220
private const val WPM_STEP = 5

fun calibrationSample(text: String, maxWords: Int = 60): String {
    require(maxWords > 0) { "maxWords must be positive" }
    return text.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .take(maxWords)
        .joinToString(" ")
}

fun calibratedWpm(wordCount: Int, elapsedMillis: Long): Int {
    require(wordCount > 0) { "wordCount must be positive" }
    require(elapsedMillis >= 1_000) { "elapsedMillis must be at least one second" }
    val raw = wordCount * 60_000.0 / elapsedMillis
    return ((raw / WPM_STEP).roundToInt() * WPM_STEP).coerceIn(MIN_WPM, MAX_WPM)
}

fun stepWpm(current: Int, delta: Int): Int =
    (current + delta).coerceIn(MIN_WPM, MAX_WPM)
