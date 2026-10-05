package com.example.teleprompter.domain.parser

import com.example.teleprompter.domain.model.SpeechUnit

class SpeechTextParser {
    private val abbreviations = setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "e.g", "i.e")

    fun parse(text: String): List<SpeechUnit> {
        if (text.isEmpty()) return emptyList()

        return normalizeWrappedLineBreaks(text)
            .split('\n')
            .flatMapIndexed { paragraphIndex, line ->
                if (line.isBlank()) {
                    listOf(SpeechUnit("", 0, paragraphIndex, isBlank = true))
                } else {
                    splitLine(line).map { sentence ->
                        SpeechUnit(
                            rawText = sentence,
                            wordCount = countWords(sentence),
                            paragraphIndex = paragraphIndex
                        )
                    }
                }
            }
    }

    /**
     * A copied document can contain hard line breaks inserted by its layout engine.
     * Keep sentence/paragraph breaks, but join a single break that occurs inside a sentence.
     */
    private fun normalizeWrappedLineBreaks(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("(?<![.!?。！？\\n])\\n[ \\t]*(?=\\S)"), " ")

    private fun splitLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var segmentStart = 0

        line.forEachIndexed { index, character ->
            if (character !in ".?!") return@forEachIndexed
            val isEndOfLine = index == line.lastIndex
            val nextIsWhitespace = !isEndOfLine && line[index + 1].isWhitespace()
            if (!isEndOfLine && !nextIsWhitespace) return@forEachIndexed

            val candidate = line.substring(segmentStart, index + 1)
            val lastToken = candidate.dropLast(1).trim().substringAfterLast(' ').lowercase()
            if (character == '.' && lastToken in abbreviations) return@forEachIndexed

            result += candidate.trim()
            segmentStart = index + 1
            while (segmentStart < line.length && line[segmentStart].isWhitespace()) segmentStart++
        }

        if (segmentStart < line.length) {
            result += line.substring(segmentStart).trim()
        }
        return result.ifEmpty { listOf(line.trim()) }
    }
}
