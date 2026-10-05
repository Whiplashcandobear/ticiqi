package com.example.teleprompter.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test
    fun formats1024WordsAt120WpmAsEightMinutesThirtyTwoSeconds() {
        assertEquals("08:32", formatDurationSeconds(ceilDurationSeconds(1024, 120)))
    }
}
