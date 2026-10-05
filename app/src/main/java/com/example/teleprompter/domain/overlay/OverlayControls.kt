package com.example.teleprompter.domain.overlay

import com.example.teleprompter.domain.model.FontScale

fun stepOverlayFontScale(current: FontScale, increase: Boolean): FontScale {
    val options = FontScale.entries
    val currentIndex = options.indexOf(current).coerceAtLeast(0)
    val nextIndex = if (increase) currentIndex + 1 else currentIndex - 1
    return options[nextIndex.coerceIn(options.indices)]
}
