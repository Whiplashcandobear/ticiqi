package com.example.teleprompter.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptAlignerTest {
    private val script = "大家好，欢迎来到今天的分享。今天我想和大家聊聊时间管理。首先我们要明白，时间是最公平的资源。"
    private val aligner = ScriptAligner(script)

    @Test
    fun `continuing speech matches at the given center`() {
        val m = aligner.match("今天我想和大家聊聊时间管理", 0)

        assertNotNull(m)
        assertEquals(12, m!!.startClean)
        assertEquals(25, m.endClean)
    }

    @Test
    fun `partial prefix of a sentence matches too`() {
        val m = aligner.match("今天我想和大家", 12)

        assertNotNull(m)
        assertEquals(12, m!!.startClean)
        assertEquals(19, m.endClean)
    }

    @Test
    fun `unrelated speech is rejected`() {
        assertNull(aligner.match("今天天气不错我们去吃火锅吧", 0))
        assertNull(aligner.match("啊这个那个是吧对的嗯", 0))
    }

    @Test
    fun `re-reading an earlier part matches behind the center`() {
        val forward = aligner.match("今天我想和大家聊聊时间管理", 0)!!
        assertEquals(25, forward.endClean)

        val back = aligner.match("大家好欢迎来到今天的分享", forward.endClean)

        assertNotNull(back)
        assertTrue("应匹配到光标之前", back!!.startClean < forward.endClean)
        assertEquals(0, back.startClean)
        assertEquals(12, back.endClean)
    }

    @Test
    fun `short fragments are ignored`() {
        assertNull(aligner.match("", 0))
        assertNull(aligner.match("你好", 0))
    }

    @Test
    fun `tolerates small recognition errors`() {
        // "聊聊" 被识别成 "聊协"
        val m = aligner.match("大家好欢迎来到今天的分享今天我想和大家聊协时间管理", 0)

        assertNotNull(m)
        assertTrue("得分应达标: ${m!!.score}", m.score >= ScriptAlignerTestThreshold)
        assertEquals(25, m.endClean)
    }

    @Test
    fun `tolerates inserted filler words`() {
        // 台本"聊聊时间管理"，识别成"聊了一下时间管理"
        val m = aligner.match("今天我想和大家聊了一下时间管理", 12)

        assertNotNull(m)
        assertEquals(25, m!!.endClean)
    }

    companion object {
        // 测试里拿不到私有常量，用与实现一致的值断言
        private const val ScriptAlignerTestThreshold = 0.70f
    }
}
