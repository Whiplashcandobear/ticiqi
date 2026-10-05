package com.example.teleprompter.domain.model

data class ScriptDocument(
    val id: Long = 0L,
    val title: String = "未命名演讲",
    val rawText: String = "",
    val wordCount: Int = 0,
    val updatedAt: Long = 0L,
    val lastPlaybackUnit: Int = 0,
    val lastPlaybackProgress: Float = 0f
)

enum class AccentColor { BLUE, AMBER, MINT }

enum class ThemeMode { DARK, LIGHT }

enum class FontScale { SMALL, MEDIUM, LARGE, EXTRA_LARGE }

enum class PromptMode { FIXED_WPM, VOICE_FOLLOW }

data class DisplaySettings(
    val speed: Int = 200,
    val accentColor: AccentColor = AccentColor.BLUE,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val fontScale: FontScale = FontScale.LARGE,
    val countdownSeconds: Int = 5,
    val landscape: Boolean = false,
    val promptMode: PromptMode = PromptMode.FIXED_WPM
)

data class SpeechUnit(
    val rawText: String,
    val wordCount: Int,
    val paragraphIndex: Int,
    val isBlank: Boolean = rawText.isBlank()
)
