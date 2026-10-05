package com.example.teleprompter.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class WordCounterTest {
    @Test
    fun countsEnglishWordsWithoutCountingNumbers() {
        assertEquals(6, countWords("Good morning, everyone. It's well-known in 2026."))
    }

    @Test
    fun ignoresChineseTextAndPunctuation() {
        assertEquals(3, countWords("Hello 世界, this is 中文."))
    }

    @Test
    fun treatsCurlyApostropheAsPartOfAnEnglishWord() {
        assertEquals(3, countWords("Today, I’d like."))
    }
}
