package com.example.teleprompter.util

import kotlin.math.ceil

fun ceilDurationSeconds(wordCount: Int, wpm: Int): Int =
    ceil(wordCount.toDouble() / wpm.coerceAtLeast(1) * 60.0).toInt()

fun formatDurationSeconds(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}
