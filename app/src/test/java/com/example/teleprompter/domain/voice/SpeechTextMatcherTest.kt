package com.example.teleprompter.domain.voice

import com.example.teleprompter.domain.model.SpeechUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextMatcherTest {
    private val units = listOf(
        SpeechUnit("Good morning, everyone.", 3, 0),
        SpeechUnit("Today, I’d like to invite you to imagine a garden.", 9, 0),
        SpeechUnit("In this garden, every flower is different.", 7, 0),
        SpeechUnit("Thank you.", 2, 0)
    )

    @Test
    fun `curly apostrophe punctuation and case do not affect sentence match`() {
        val result = SpeechTextMatcher(units).match("today i'd like to invite you to imagine a garden", anchorIndex = 0)

        assertEquals(1, result?.unitIndex)
        assertTrue((result?.coverage ?: 0f) > 0.8f)
    }

    @Test
    fun `partial current sentence reports character progress`() {
        val result = SpeechTextMatcher(units).match("Good mor", anchorIndex = 0)

        assertEquals(0, result?.unitIndex)
        assertTrue((result?.characterProgress ?: 0f) > 0f)
        assertTrue((result?.characterProgress ?: 1f) < 1f)
    }

    @Test
    fun `line wrapping inside a sentence does not change the match`() {
        val wrapped = listOf(
            SpeechUnit("Today, I’d like to invite you to imagine\na garden.", 9, 0)
        )

        val result = SpeechTextMatcher(wrapped).match("Today I'd like to invite you to imagine a garden", anchorIndex = 0)

        assertEquals(0, result?.unitIndex)
        assertTrue((result?.coverage ?: 0f) > 0.8f)
    }

    @Test
    fun `one common word does not jump to a distant sentence`() {
        val result = SpeechTextMatcher(units).match("garden", anchorIndex = 0)

        assertTrue(result == null || result.unitIndex == 0)
    }
}
