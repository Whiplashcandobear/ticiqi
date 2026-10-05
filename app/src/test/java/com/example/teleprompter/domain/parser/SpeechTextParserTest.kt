package com.example.teleprompter.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextParserTest {
    @Test
    fun keepsManualParagraphBoundaries() {
        val units = SpeechTextParser().parse("First line.\n\nSecond line without punctuation")
        assertEquals(listOf("First line.", "", "Second line without punctuation"), units.map { it.rawText })
    }

    @Test
    fun doesNotSplitCommonAbbreviations() {
        val units = SpeechTextParser().parse("Dr. Smith is here. This is next.")
        assertEquals(2, units.count { it.rawText.isNotBlank() })
        assertEquals("Dr. Smith is here.", units.first { it.rawText.isNotBlank() }.rawText)
    }

    @Test
    fun joinsSingleLineBreakInsideOneSentence() {
        val units = SpeechTextParser().parse("Today, I’d like to invite you to imagine\na garden.")

        assertEquals(
            listOf("Today, I’d like to invite you to imagine a garden."),
            units.filter { it.rawText.isNotBlank() }.map { it.rawText }
        )
    }
}
