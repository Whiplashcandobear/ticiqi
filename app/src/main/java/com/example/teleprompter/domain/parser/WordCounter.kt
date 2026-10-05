package com.example.teleprompter.domain.parser

private val englishWordPattern = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*")
private val cjkCharPattern = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")

fun countWords(text: String): Int = englishWordPattern.findAll(text).count()

/**
 * Counts "reading units" used for playback timing. Each CJK character counts as
 * one unit and each Latin word counts as one unit; punctuation and whitespace
 * count as zero. This makes the speed control meaningful for Chinese scripts,
 * where [countWords] returns 0 because there are no whitespace-separated words.
 */
fun countReadingUnits(text: String): Int =
    cjkCharPattern.findAll(text).count() + englishWordPattern.findAll(text).count()
