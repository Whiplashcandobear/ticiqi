package com.example.teleprompter.asr

import android.content.Context
import com.example.teleprompter.domain.model.AsrMode
import com.example.teleprompter.domain.model.DisplaySettings

object AsrEngineFactory {
    fun create(context: Context, settings: DisplaySettings): AsrEngine = when (settings.asrMode) {
        AsrMode.SYSTEM -> GoogleOnDeviceAsrEngine(context)
        AsrMode.LOCAL -> SherpaOnnxAsrEngine(context, LocalModelCatalog.get(settings.localModelId))
        AsrMode.CLOUD -> CloudHttpAsrEngine(context, settings.cloudConfig)
    }

    fun createSystem(context: Context): AsrEngine = GoogleOnDeviceAsrEngine(context)
}
