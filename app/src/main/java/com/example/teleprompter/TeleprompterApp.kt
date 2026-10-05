package com.example.teleprompter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.teleprompter.data.LocalStore
import com.example.teleprompter.presentation.editor.ScriptEditorScreen
import com.example.teleprompter.presentation.home.HomeScreen
import com.example.teleprompter.presentation.playback.PlaybackScreen
import com.example.teleprompter.presentation.settings.PlaybackSettingsScreen
import com.example.teleprompter.presentation.theme.TeleprompterTheme

@Composable
fun TeleprompterApp(store: LocalStore) {
    val navController = rememberNavController()
    val defaultSettings = remember { store.loadSettings() }
    TeleprompterTheme(defaultSettings) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    store = store,
                    onNew = { navController.navigate("editor/0") },
                    onEdit = { navController.navigate("editor/$it") },
                    onSettings = { navController.navigate("settings/$it") },
                    onPlay = { navController.navigate("settings/$it") }
                )
            }
            composable("editor/{scriptId}") { entry ->
                val id = entry.arguments?.getString("scriptId")?.toLongOrNull() ?: 0L
                ScriptEditorScreen(
                    store = store,
                    scriptId = id,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.navigate("settings/$it") }
                )
            }
            composable("settings/{scriptId}") { entry ->
                val id = entry.arguments?.getString("scriptId")?.toLongOrNull() ?: 0L
                PlaybackSettingsScreen(
                    store = store,
                    scriptId = id,
                    onBack = { navController.popBackStack() },
                    onStart = { navController.navigate("playback/$it") }
                )
            }
            composable("playback/{scriptId}") { entry ->
                val id = entry.arguments?.getString("scriptId")?.toLongOrNull() ?: 0L
                PlaybackScreen(
                    store = store,
                    scriptId = id,
                    onExit = { navController.popBackStack() }
                )
            }
        }
    }
}
