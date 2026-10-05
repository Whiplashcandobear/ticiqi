package com.example.teleprompter.domain.voice

import com.example.teleprompter.domain.model.SpeechUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFollowEngineTest {
    private val units = listOf(
        SpeechUnit("Good morning, everyone.", 3, 0),
        SpeechUnit("Today, I'd like to invite you to imagine a garden.", 9, 0),
        SpeechUnit("In this garden, every flower is different.", 7, 0)
    )

    @Test
    fun `partial result advances within current sentence`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)

        val state = engine.onRecognition("Good morning", 300L)

        assertEquals(0, state.currentUnitIndex)
        assertTrue(state.characterProgress > 0f)
        assertFalse(state.isFallbackToWpm)
    }

    @Test
    fun `spoken next sentence advances the cursor`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)

        val state = engine.onRecognition(
            "Good morning everyone Today I'd like to invite you",
            500L
        )

        assertEquals(1, state.currentUnitIndex)
        assertTrue(state.characterProgress > 0f)
    }

    @Test
    fun `silence before first match stays in grace period then falls back`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)

        // 模型加载/开口前的静默（这里模拟 2.1s）不应立刻兜底
        assertFalse(engine.onTick(2_100L).isFallbackToWpm)
        // 超过首次匹配宽限期（默认 15s）才兜底
        assertTrue(engine.onTick(15_100L).isFallbackToWpm)
    }

    @Test
    fun `after first match two second silence falls back to fixed wpm`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onRecognition("Good morning", 300L)

        val state = engine.onTick(2_500L)

        assertTrue(state.isFallbackToWpm)
    }

    @Test
    fun `recognition recovery returns to voice follow`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onTick(15_100L) // 先进入兜底

        val state = engine.onRecognition("Good morning everyone", 15_200L)

        assertFalse(state.isFallbackToWpm)
    }

    @Test
    fun `empty or unmatched recognition does not move the cursor`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onRecognition("Good morning everyone", 200L)

        val empty = engine.onRecognition("", 400L)
        val unmatched = engine.onRecognition("zzz", 600L)

        assertEquals(0, empty.currentUnitIndex)
        assertEquals(0, unmatched.currentUnitIndex)
    }
}
