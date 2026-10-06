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

    // ---------- 用户实测场景：识别错字导致卡住（容错回归测试） ----------

    /** 用户实例：台本"…爬山感受大自然，该吃吃该喝喝该歪歪，就跟普通感冒一样"，识别成"然该迟迟该喝喝该"。 */
    @Test
    fun `misrecognized next sentence still advances via tolerant anchor`() {
        val speech = listOf(
            SpeechUnit("把心敞开了去到处爬山感受大自然，", 16, 0),
            SpeechUnit("该吃吃该喝喝该歪歪，就跟普通感冒一样。", 18, 1)
        )
        val engine = VoiceFollowEngine(speech, startAtMillis = 0L)
        // 先把第一句念到接近结尾
        engine.onRecognition("把心敞开了去到处爬山感受大自然", 300L)
        val before = engine.state()
        assertEquals(0, before.currentUnitIndex)

        // 第二句识别质量差（多个错字），整段得分不够 —— 靠尾部锚点/片段命中推进
        val state = engine.onRecognition("然该迟迟该喝喝该", 600L)

        assertEquals("错字也应推进到下一句", 1, state.currentUnitIndex)
    }

    /** 用户实例：台本"…把自己的身体推进了一个恶性循环了。"，识别到后半段"体推进了一个恶性"。 */
    @Test
    fun `recognizing only the tail of the current sentence advances progress`() {
        val speech = listOf(
            SpeechUnit("我一天到黑焦虑、绝望、睡不着，免疫力都遭你削弱完咯，", 24, 0),
            SpeechUnit("把自己的身体推进了一个恶性循环了。", 15, 1),
            SpeechUnit("我身边就有个活生生的例子。", 11, 2)
        )
        val engine = VoiceFollowEngine(speech, startAtMillis = 0L)
        engine.onRecognition("我一天到黑焦虑绝望睡不着", 300L)

        val state = engine.onRecognition("把自己的身体推进了一个恶性", 600L)

        assertTrue("应推进到第 2 句", state.currentUnitIndex >= 1)
    }

    /** 长时间识别不上时（用户在说话但识别质量差），光标到句尾应容错推进一句，不卡死。 */
    @Test
    fun `stuck at sentence tail nudges forward while user keeps talking`() {
        val speech = listOf(
            SpeechUnit("第一句话内容在这里。", 9, 0),
            SpeechUnit("第二句话内容也在这里。", 10, 1),
            SpeechUnit("第三句话内容在这里。", 9, 2)
        )
        val engine = VoiceFollowEngine(speech, startAtMillis = 0L)
        engine.onRecognition("第一句话内容在这里", 300L)
        // 念到句尾
        engine.onRecognition("第一句话内容在这里。", 500L)
        val before = engine.state()

        // 识别持续有内容但完全匹配不上（乱码），且已卡住超过 STUCK_AFTER_MS → 容错推进
        var state = engine.state(600L)
        for (t in 800L..6000L step 100L) {
            state = engine.onRecognition("嗯嗯啊啊嗯嗯", t)
        }

        assertTrue(
            "卡住时应容错推进（from unit ${before.currentUnitIndex}）",
            state.currentUnitIndex > before.currentUnitIndex
        )
    }

    /** 弱匹配不应让画面来回跳：往回滚需要连续多次一致确认。 */
    @Test
    fun `weak backtrack needs repeated confirmation`() {
        val speech = listOf(
            SpeechUnit("第一句话内容在这里。", 9, 0),
            SpeechUnit("第二句话内容也在这里。", 10, 1)
        )
        val engine = VoiceFollowEngine(speech, startAtMillis = 0L)
        engine.onRecognition("第一句话内容在这里", 300L)
        engine.onRecognition("第二句话内容也在这里", 600L)
        val before = engine.state()
        assertEquals(1, before.currentUnitIndex)

        // 一次弱回退信号不应立刻把光标拉回去
        val once = engine.onRecognition("第一句", 900L)
        assertEquals("单次弱信号不回退", 1, once.currentUnitIndex)
    }

    /** 未成功匹配过之前，容错推进不应生效（避免开场乱说话就乱跑）。 */
    @Test
    fun `no nudge before any stable match`() {
        val speech = listOf(
            SpeechUnit("第一句话内容在这里。", 9, 0),
            SpeechUnit("第二句话内容也在这里。", 10, 1)
        )
        val engine = VoiceFollowEngine(speech, startAtMillis = 0L)

        var state = engine.state(0L)
        for (t in 100L..3000L step 100L) {
            state = engine.onRecognition("嗯嗯啊啊嗯嗯", t)
        }

        assertEquals(0, state.currentUnitIndex)
    }

    /** 念完一句后立刻插一句无关的话，不应被当成"卡住"而推进（保护插话场景）。 */
    @Test
    fun `brief interjection right after a sentence does not nudge forward`() {
        val speech = listOf(
            SpeechUnit("第一句话内容在这里。", 9, 0),
            SpeechUnit("第二句话内容也在这里。", 10, 1)
        )
        val engine = VoiceFollowEngine(speech, startAtMillis = 0L)
        engine.onRecognition("第一句话内容在这里", 300L)
        engine.onRecognition("第一句话内容在这里。", 500L)
        val before = engine.state()

        // 200ms 后就插话，远短于 STUCK_AFTER_MS(1500)
        val state = engine.onRecognition("今天天气不错啊", 700L)

        assertEquals("插话不应推进", before.currentUnitIndex, state.currentUnitIndex)
    }
}
