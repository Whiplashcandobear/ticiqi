package com.example.teleprompter.data

import android.content.Context
import com.example.teleprompter.domain.model.DisplaySettings
import com.example.teleprompter.domain.model.AccentColor
import com.example.teleprompter.domain.model.AsrMode
import com.example.teleprompter.domain.model.CloudAsrConfig
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
            speed = item.optInt("speed", item.optInt("wpm", 200)),
            accentColor = runCatching { AccentColor.valueOf(item.optString("accentColor", "BLUE")) }.getOrDefault(AccentColor.BLUE),
            themeMode = runCatching { ThemeMode.valueOf(item.optString("themeMode", "DARK")) }.getOrDefault(ThemeMode.DARK),
            fontScale = runCatching { FontScale.valueOf(item.optString("fontScale", "LARGE")) }.getOrDefault(FontScale.LARGE),
            countdownSeconds = item.optInt("countdownSeconds", 5),
            landscape = item.optBoolean("landscape", false),
            promptMode = runCatching { PromptMode.valueOf(item.optString("promptMode", "FIXED_WPM")) }
                .getOrDefault(PromptMode.FIXED_WPM),
            asrMode = runCatching { AsrMode.valueOf(item.optString("asrMode", "LOCAL")) }
                .getOrDefault(AsrMode.LOCAL),
            localModelId = item.optString("localModelId", "SMALL_CTC_ZH_INT8"),
            cloudConfig = runCatching { cloudConfigFrom(item.optJSONObject("cloudConfig")) }
                .getOrDefault(CloudAsrConfig()),
            overlayWidthDp = item.optInt("overlayWidthDp", 320),
            overlayHeightDp = item.optInt("overlayHeightDp", 320)
        )
    }.getOrDefault(DisplaySettings())

    fun saveSettings(settings: DisplaySettings) {
        val cc = settings.cloudConfig
        val cloudJson = JSONObject()
            .put("endpoint", cc.endpoint)
            .put("apiKey", cc.apiKey)
            .put("method", cc.method)
            .put("contentType", cc.contentType)
            .put("headersJson", cc.headersJson)
            .put("bodyTemplate", cc.bodyTemplate)
            .put("resultPath", cc.resultPath)
            .put("audioEncoding", cc.audioEncoding)
            .put("chunkMillis", cc.chunkMillis)
        val item = JSONObject()
            .put("speed", settings.speed)
            .put("accentColor", settings.accentColor.name)
            .put("themeMode", settings.themeMode.name)
            .put("fontScale", settings.fontScale.name)
            .put("countdownSeconds", settings.countdownSeconds)
            .put("landscape", settings.landscape)
            .put("promptMode", settings.promptMode.name)
            .put("asrMode", settings.asrMode.name)
            .put("localModelId", settings.localModelId)
            .put("cloudConfig", cloudJson)
            .put("overlayWidthDp", settings.overlayWidthDp)
            .put("overlayHeightDp", settings.overlayHeightDp)
        preferences.edit().putString(KEY_SETTINGS, item.toString()).apply()
    }

    private fun cloudConfigFrom(obj: JSONObject?): CloudAsrConfig = if (obj == null) CloudAsrConfig() else CloudAsrConfig(
        endpoint = obj.optString("endpoint", ""),
        apiKey = obj.optString("apiKey", ""),
        method = obj.optString("method", "POST"),
        contentType = obj.optString("contentType", "application/json"),
        headersJson = obj.optString("headersJson", ""),
        bodyTemplate = obj.optString(
            "bodyTemplate",
            """{"audio":"{base64}","sample_rate":{sampleRate},"format":"{format}"}"""
        ),
        resultPath = obj.optString("resultPath", "text"),
        audioEncoding = obj.optString("audioEncoding", "wav"),
        chunkMillis = obj.optInt("chunkMillis", 1000)
    )

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
