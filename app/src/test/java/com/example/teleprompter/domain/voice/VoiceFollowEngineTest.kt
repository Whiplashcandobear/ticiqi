package com.example.teleprompter.domain.voice

import com.example.teleprompter.domain.model.SpeechUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFollowEngineTest {
    private val units = listOf(
        SpeechUnit("Good morning, everyone.", 3, 0),
        SpeechUnit("Today, I’d like to invite you to imagine a garden.", 9, 0),
        SpeechUnit("In this garden, every flower is different.", 7, 0)
    )

    @Test
    fun `partial results advance current sentence without changing sentence too early`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)

        val state = engine.onRecognition("Good morning", 300L)

        assertEquals(0, state.currentUnitIndex)
        assertTrue(state.characterProgress > 0f)
        assertFalse(state.isFallbackToWpm)
    }

    @Test
    fun `next sentence needs enough evidence before committing`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)

        val first = engine.onRecognition("Today", 500L)
        val second = engine.onRecognition("Today I'd like to invite you", 700L)

        assertEquals(0, first.currentUnitIndex)
        assertEquals(1, second.currentUnitIndex)
        assertTrue(second.characterProgress > 0f)
    }

    @Test
    fun `no valid result for two seconds falls back to fixed wpm`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)

        val state = engine.onTick(2_100L)

        assertTrue(state.isFallbackToWpm)
    }

    @Test
    fun `fallback requires two stable results before returning to voice follow`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onTick(2_100L)

        val first = engine.onRecognition("Good morning", 2_200L)
        val second = engine.onRecognition("Good morning everyone", 2_350L)

        assertTrue(first.isFallbackToWpm)
        assertFalse(second.isFallbackToWpm)
    }

    @Test
    fun `empty or repeated recognition does not move the cursor`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onRecognition("Good morning everyone", 200L)

        val empty = engine.onRecognition("", 400L)
        val repeated = engine.onRecognition("noise with no matching words", 600L)

        assertEquals(0, empty.currentUnitIndex)
        assertEquals(0, repeated.currentUnitIndex)
    }
}
