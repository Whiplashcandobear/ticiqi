package com.example.teleprompter.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.teleprompter.domain.model.AccentColor
import com.example.teleprompter.domain.model.DisplaySettings
import com.example.teleprompter.domain.model.ThemeMode

private val DarkBackground = Color(0xFF070B12)
private val LightBackground = Color(0xFFF7F9FC)
private val BlueDark = Color(0xFF65C8FF)
private val BlueLight = Color(0xFF1459B7)
private val AmberDark = Color(0xFFFFD76A)
private val AmberLight = Color(0xFF8B5A00)
private val MintDark = Color(0xFF71E1BC)
private val MintLight = Color(0xFF0C6B54)
private val LiveHighlightDark = Color(0xFFFFB454)
private val LiveHighlightLight = Color(0xFFB35C00)

fun accentColor(settings: DisplaySettings): Color = when (settings.accentColor) {
    AccentColor.BLUE -> if (settings.themeMode == ThemeMode.DARK) BlueDark else BlueLight
    AccentColor.AMBER -> if (settings.themeMode == ThemeMode.DARK) AmberDark else AmberLight
    AccentColor.MINT -> if (settings.themeMode == ThemeMode.DARK) MintDark else MintLight
}

fun liveHighlightColor(settings: DisplaySettings): Color =
    if (settings.themeMode == ThemeMode.DARK) LiveHighlightDark else LiveHighlightLight

@Composable
fun TeleprompterTheme(settings: DisplaySettings, content: @Composable () -> Unit) {
    val dark = settings.themeMode == ThemeMode.DARK
    val accent = accentColor(settings)
    val colors = if (dark) {
        darkColorScheme(
            primary = accent,
            secondary = Color(0xFF94A2B5),
            background = DarkBackground,
            surface = Color(0xFF101722),
            onBackground = Color(0xFFF4F7FB),
            onSurface = Color(0xFFF4F7FB)
        )
    } else {
        lightColorScheme(
            primary = accent,
            secondary = Color(0xFF55708F),
            background = LightBackground,
            surface = Color.White,
            onBackground = Color(0xFF14233D),
            onSurface = Color(0xFF14233D)
        )
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
