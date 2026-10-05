package com.example.teleprompter.data

import android.content.Context
import com.example.teleprompter.domain.model.DisplaySettings
import com.example.teleprompter.domain.model.AccentColor
import com.example.teleprompter.domain.model.FontScale
import com.example.teleprompter.domain.model.PromptMode
import com.example.teleprompter.domain.model.ScriptDocument
import com.example.teleprompter.domain.model.ThemeMode
import org.json.JSONArray
import org.json.JSONObject

class LocalStore(context: Context) {
    private val preferences = context.getSharedPreferences("teleprompter_local", Context.MODE_PRIVATE)
    fun loadScripts(): List<ScriptDocument> = runCatching {
        val array = JSONArray(preferences.getString(KEY_SCRIPTS, "[]") ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    ScriptDocument(
                        id = item.optLong("id"),
                        title = item.optString("title", "未命名演讲"),
                        rawText = item.optString("rawText"),
                        wordCount = item.optInt("wordCount"),
                        updatedAt = item.optLong("updatedAt"),
                        lastPlaybackUnit = item.optInt("lastPlaybackUnit"),
                        lastPlaybackProgress = item.optDouble("lastPlaybackProgress").toFloat()
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun saveScript(document: ScriptDocument): ScriptDocument {
        val current = loadScripts().toMutableList()
        val saved = document.copy(
            id = if (document.id == 0L) (current.maxOfOrNull { it.id } ?: 0L) + 1L else document.id,
            title = document.title.ifBlank { "未命名演讲" },
            updatedAt = System.currentTimeMillis()
        )
        val index = current.indexOfFirst { it.id == saved.id }
        if (index >= 0) current[index] = saved else current += saved
        preferences.edit().putString(KEY_SCRIPTS, JSONArray(current.map(::scriptJson)).toString()).apply()
        return saved
    }

    fun deleteScript(id: Long) {
        val updated = loadScripts().filterNot { it.id == id }
        preferences.edit().putString(KEY_SCRIPTS, JSONArray(updated.map(::scriptJson)).toString()).apply()
    }

    fun loadSettings(): DisplaySettings = runCatching {
        val item = JSONObject(preferences.getString(KEY_SETTINGS, "{}") ?: "{}")
        DisplaySettings(
            wpm = item.optInt("wpm", 120),
            accentColor = runCatching { AccentColor.valueOf(item.optString("accentColor", "BLUE")) }.getOrDefault(AccentColor.BLUE),
            themeMode = runCatching { ThemeMode.valueOf(item.optString("themeMode", "DARK")) }.getOrDefault(ThemeMode.DARK),
            fontScale = runCatching { FontScale.valueOf(item.optString("fontScale", "LARGE")) }.getOrDefault(FontScale.LARGE),
            countdownSeconds = item.optInt("countdownSeconds", 5),
            landscape = item.optBoolean("landscape", false),
            promptMode = runCatching { PromptMode.valueOf(item.optString("promptMode", "FIXED_WPM")) }
                .getOrDefault(PromptMode.FIXED_WPM)
        )
    }.getOrDefault(DisplaySettings())

    fun saveSettings(settings: DisplaySettings) {
        val item = JSONObject()
            .put("wpm", settings.wpm)
            .put("accentColor", settings.accentColor.name)
            .put("themeMode", settings.themeMode.name)
            .put("fontScale", settings.fontScale.name)
            .put("countdownSeconds", settings.countdownSeconds)
            .put("landscape", settings.landscape)
            .put("promptMode", settings.promptMode.name)
        preferences.edit().putString(KEY_SETTINGS, item.toString()).apply()
    }

    private fun scriptJson(document: ScriptDocument): JSONObject = JSONObject()
        .put("id", document.id)
        .put("title", document.title)
        .put("rawText", document.rawText)
        .put("wordCount", document.wordCount)
        .put("updatedAt", document.updatedAt)
        .put("lastPlaybackUnit", document.lastPlaybackUnit)
        .put("lastPlaybackProgress", document.lastPlaybackProgress)

    private companion object {
        const val KEY_SCRIPTS = "scripts"
        const val KEY_SETTINGS = "settings"
    }
}
