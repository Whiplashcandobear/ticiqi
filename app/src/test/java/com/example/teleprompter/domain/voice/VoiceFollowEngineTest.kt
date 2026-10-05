package com.example.teleprompter.domain.voice

import com.example.teleprompter.domain.model.SpeechUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFollowEngineTest {
    private val units = listOf(
        SpeechUnit("大家好，欢迎来到今天的分享。", 12, 0),
        SpeechUnit("今天我想和大家聊聊时间管理。", 13, 1),
        SpeechUnit("首先我们要明白，时间是最公平的资源。", 16, 2)
    )

    @Test
    fun `matched speech advances the cursor within the sentence`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)

        val state = engine.onRecognition("大家好欢迎来到今天", 300L)

        assertEquals(0, state.currentUnitIndex)
        assertTrue(state.characterProgress > 0f)
        assertTrue(state.hasStableMatch)
        assertFalse(state.isFallbackToWpm)
    }

    @Test
    fun `reading the next sentence advances to it`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onRecognition("大家好欢迎来到今天的分享", 300L)

        val state = engine.onRecognition("今天我想和大家聊聊时间管理", 400L)

        assertEquals(1, state.currentUnitIndex)
    }

    @Test
    fun `unrelated speech does not move the cursor`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onRecognition("大家好欢迎来到今天的分享", 300L)

        val state = engine.onRecognition("今天天气不错我们去吃火锅吧", 400L)

        assertEquals(0, state.currentUnitIndex)
        assertFalse(state.hasStableMatch)
    }

    @Test
    fun `re-reading an earlier sentence scrolls the cursor back`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onRecognition("大家好欢迎来到今天的分享", 300L)
        engine.onRecognition("今天我想和大家聊聊时间管理", 400L)
        assertEquals(1, engine.state().currentUnitIndex)

        val state = engine.onRecognition("大家好欢迎来到今天的分享", 500L)

        assertEquals(0, state.currentUnitIndex)
        assertEquals(1f, state.characterProgress)
    }

    @Test
    fun `silence keeps the cursor in place and never falls back`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onRecognition("大家好欢迎来到今天", 300L)

        val state = engine.onTick(60_000L)

        // 引擎活着但没识别到（停顿/跑题）：原地等待，不自动回固定速度
        assertEquals(0, state.currentUnitIndex)
        assertFalse(state.isFallbackToWpm)
    }

    @Test
    fun `empty or too short recognition is ignored`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.onRecognition("大家好欢迎来到今天", 300L)
        val before = engine.state()

        val empty = engine.onRecognition("", 400L)
        val short = engine.onRecognition("你好", 500L)

        assertEquals(before.currentUnitIndex, empty.currentUnitIndex)
        assertEquals(before.currentUnitIndex, short.currentUnitIndex)
    }

    @Test
    fun `manual seek moves the matching window`() {
        val engine = VoiceFollowEngine(units, startAtMillis = 0L)
        engine.setCursor(2, 0f)

        val state = engine.onRecognition("首先我们要明白时间是最公平的资源", 300L)

        assertEquals(2, state.currentUnitIndex)
    }
}
