package com.example.teleprompter.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the Chinese-aware, character-level alignment behaviour that makes 语音跟随
 * work for Chinese scripts (the old [SpeechTextMatcher] only matched English words).
 */
class TeleprompterAlignmentTest {

    @Test
    fun cleanLengthIgnoresPunctuationAndSpaces() {
        val a = TeleprompterAlignment("你好，世界！我们 去散步。")
        // 你好世界我们去散步 = 9 clean chars
        assertEquals(9, a.cleanLength)
    }

    @Test
    fun isCleanCharRecognizesCjkAlnumAndRejectsPunctuation() {
        assertTrue(TeleprompterAlignment.isCleanChar('你'))
        assertTrue(TeleprompterAlignment.isCleanChar('A'))
        assertTrue(TeleprompterAlignment.isCleanChar('3'))
        assertFalse(TeleprompterAlignment.isCleanChar('，'))
        assertFalse(TeleprompterAlignment.isCleanChar('！'))
        assertFalse(TeleprompterAlignment.isCleanChar(' '))
    }

    @Test
    fun consumeTranscriptAdvancesCursorForward() {
        val a = TeleprompterAlignment("今天天气真好我们出去散步")
        val first = a.consumeTranscript("今天天气", false)
        assertTrue("first should align at least 4 chars", first.cleanIndex >= 4)

        val second = a.consumeTranscript("真好我们", false)
        assertTrue("second should advance past first", second.cleanIndex > first.cleanIndex)
    }

    @Test
    fun toleratesSmallAsrOmissions() {
        // Script has no 'g'/'h' gap handled; recognition dropped the 'g' between f and h.
        val a = TeleprompterAlignment("abcdefghijklmnop")
        val r = a.consumeTranscript("abcdefhijkl", false)
        assertTrue("should recover almost the whole prefix", r.cleanIndex >= 10)
    }

    @Test
    fun emptyTranscriptDoesNotMoveCursor() {
        val a = TeleprompterAlignment("大家好")
        val before = a.currentCleanIndex()
        val r = a.consumeTranscript("，。", false)
        assertEquals(before, r.cleanIndex)
        assertEquals(0, r.matchedLength)
    }

    @Test
    fun setCurrentRawIndexSeeksCursor() {
        val a = TeleprompterAlignment("第一句。第二句。第三句。")
        a.setCurrentRawIndex(0) // at start of 第一句
        assertEquals(0, a.currentCleanIndex())
        a.setCurrentRawIndex(4) // inside 第一句 (first clean char index 0..3)
        assertEquals(3, a.currentCleanIndex())
    }
}
