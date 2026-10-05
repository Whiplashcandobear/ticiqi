package com.example.teleprompter.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedCalibrationTest {
    @Test
    fun calculatesRoundedRateFromUnitsAndElapsedTime() {
        assertEquals(120, calibratedRate(units = 60, elapsedMillis = 30_000))
        assertEquals(150, calibratedRate(units = 100, elapsedMillis = 40_000))
    }

    @Test
    fun clampsCalibrationToSafeSpeechRange() {
        assertEquals(400, calibratedRate(units = 100, elapsedMillis = 1_000))
        assertEquals(120, calibratedRate(units = 1, elapsedMillis = 60_000))
    }

    @Test
    fun extractsAtMostEightyCharsByDefault() {
        val longText = "a".repeat(200)
        assertEquals(80, calibrationSample(longText).length)
    }

    @Test
    fun calibrationSampleRespectsRequestedMaxUnits() {
        assertEquals("one", calibrationSample("one two three four five", maxUnits = 3))
        assertEquals("你好世界这", calibrationSample("你好世界这是中文测试", maxUnits = 5))
    }

    @Test
    fun stepsRateByTenWithoutCrossingBounds() {
        assertEquals(135, stepRate(125, +10))
        assertEquals(115, stepRate(125, -10))
        assertEquals(120, stepRate(120, -10))
        assertEquals(400, stepRate(400, +10))
    }
}
