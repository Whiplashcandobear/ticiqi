package com.example.teleprompter.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedCalibrationTest {
    @Test
    fun calculatesRoundedWpmFromWordCountAndElapsedTime() {
        assertEquals(120, calibratedWpm(wordCount = 60, elapsedMillis = 30_000))
        assertEquals(125, calibratedWpm(wordCount = 100, elapsedMillis = 48_000))
    }

    @Test
    fun clampsCalibrationToSafeSpeechRange() {
        assertEquals(220, calibratedWpm(wordCount = 100, elapsedMillis = 1_000))
        assertEquals(60, calibratedWpm(wordCount = 1, elapsedMillis = 60_000))
    }

    @Test
    fun extractsAtMostSixtyWordsForCalibration() {
        val sample = calibrationSample("one two three four five", maxWords = 3)
        assertEquals("one two three", sample)
    }

    @Test
    fun stepsWpmByFiveWithoutCrossingBounds() {
        assertEquals(130, stepWpm(125, +5))
        assertEquals(120, stepWpm(125, -5))
        assertEquals(60, stepWpm(60, -5))
        assertEquals(220, stepWpm(220, +5))
    }
}
