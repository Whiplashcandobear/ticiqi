package com.example.teleprompter.domain.playback

import com.example.teleprompter.domain.parser.countReadingUnits
import kotlin.math.roundToInt

private const val MIN_RATE = 120
private const val MAX_RATE = 400
private const val RATE_STEP = 10

/**
 * Returns the first [maxUnits] reading units of [text] as a calibration sample.
 * For Chinese scripts (no whitespace between words) this is simply the first
 * [maxUnits] characters; for Latin scripts it is the first [maxUnits] words.
 */
fun calibrationSample(text: String, maxUnits: Int = 80): String {
    require(maxUnits > 0) { "maxUnits must be positive" }
    return text.trim().take(maxUnits)
}

fun calibratedRate(units: Int, elapsedMillis: Long): Int {
    require(units > 0) { "units must be positive" }
    require(elapsedMillis >= 1_000) { "elapsedMillis must be at least one second" }
    val raw = units * 60_000.0 / elapsedMillis
    return ((raw / RATE_STEP).roundToInt() * RATE_STEP).coerceIn(MIN_RATE, MAX_RATE)
}

fun stepRate(current: Int, delta: Int): Int =
    (current + delta).coerceIn(MIN_RATE, MAX_RATE)
