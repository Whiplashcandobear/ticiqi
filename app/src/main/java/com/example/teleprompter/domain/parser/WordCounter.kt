package com.example.teleprompter.domain.parser

private val englishWordPattern = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*")

fun countWords(text: String): Int = englishWordPattern.findAll(text).count()
