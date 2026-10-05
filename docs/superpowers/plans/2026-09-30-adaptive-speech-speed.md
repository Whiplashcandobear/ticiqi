# 自适应演讲语速 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add offline WPM calibration and live ±5 WPM correction to the playback screen and Android overlay without changing the current playback model.

**Architecture:** Keep `DisplaySettings.wpm` as the single persisted playback speed. Add pure domain helpers for extracting a calibration sample, calculating a bounded/rounded WPM, and stepping WPM. The Compose settings/playback UI and native overlay both call those helpers; the existing playback loop already recalculates sentence duration from `settings.wpm`.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android `SystemClock`, `SharedPreferences + org.json`, Kotlin/JUnit 4 domain tests, Gradle Android APK build.

---

### Task 1: Add pure speed-calibration rules

**Files:**
- Create: `app/src/main/java/com/example/teleprompter/domain/playback/SpeedCalibration.kt`
- Test: `app/src/test/java/com/example/teleprompter/domain/playback/SpeedCalibrationTest.kt`

- [x] **Step 1: Write failing tests**

Add tests for these exact rules:

```kotlin
@Test
fun calculatesRoundedWpmFromWordCountAndElapsedTime() {
    assertEquals(120, calibratedWpm(wordCount = 60, elapsedMillis = 30_000))
    assertEquals(125, calibratedWpm(wordCount = 100, elapsedMillis = 48_000))
}

@Test
fun clampsCalibrationToSafeSpeechRange() {
    assertEquals(220, calibratedWpm(wordCount = 100, elapsedMillis = 1_000))
    assertEquals(60, calibratedWpm(wordCount = 1, elapsedMillis = 60_000))
}

@Test
fun extractsAtMostSixtyWordsForCalibration() {
    val sample = calibrationSample("one two three four five", maxWords = 3)
    assertEquals("one two three", sample)
}

@Test
fun stepsWpmByFiveWithoutCrossingBounds() {
    assertEquals(130, stepWpm(125, +5))
    assertEquals(120, stepWpm(125, -5))
    assertEquals(60, stepWpm(60, -5))
    assertEquals(220, stepWpm(220, +5))
}
```

- [x] **Step 2: Run the focused test and confirm RED**

Run the existing independent Kotlin/JUnit runner with the new test class. Expected result: compilation fails because `calibratedWpm`, `calibrationSample`, and `stepWpm` do not exist.

- [x] **Step 3: Implement the minimal pure helpers**

Implement:

```kotlin
private const val MIN_WPM = 60
private const val MAX_WPM = 220
private const val WPM_STEP = 5

fun calibrationSample(text: String, maxWords: Int = 60): String =
    text.trim().split(Regex("\\s+")).filter(String::isNotBlank).take(maxWords).joinToString(" ")

fun calibratedWpm(wordCount: Int, elapsedMillis: Long): Int {
    require(wordCount > 0)
    require(elapsedMillis >= 1_000)
    val raw = wordCount * 60_000.0 / elapsedMillis
    return ((raw / WPM_STEP).roundToInt() * WPM_STEP).coerceIn(MIN_WPM, MAX_WPM)
}

fun stepWpm(current: Int, delta: Int): Int =
    (current + delta).coerceIn(MIN_WPM, MAX_WPM)
```

Use `kotlin.math.roundToInt`; reject invalid inputs instead of silently saving an invalid calibration.

- [x] **Step 4: Run the focused test and confirm GREEN**

Run the same runner. Expected result: all speed-calibration tests pass.

### Task 2: Add calibration UI to playback settings

**Files:**
- Modify: `app/src/main/java/com/example/teleprompter/presentation/settings/PlaybackSettingsScreen.kt`
- Modify: `README.md` if the feature list needs updating

- [x] **Step 1: Add calibration state and timer behavior**

Use `rememberSaveable` for `calibrationRunning`, `calibrationStartedAt`, `calibrationElapsedMillis`, and the latest result message. Use `SystemClock.elapsedRealtime()` and a `LaunchedEffect(calibrationRunning)` with a short delay to refresh elapsed time. Do not update `settings.wpm` while the timer is running.

- [x] **Step 2: Add the calibration card beside the speed choices**

Show the derived sample from `calibrationSample(script.rawText)`, its word count, elapsed time, and one action button. The idle button starts timing; the running button finishes timing, calls `calibratedWpm`, updates `settings.copy(wpm = result)`, saves it through the existing `update`, and shows the resulting WPM.

- [x] **Step 3: Preserve selectable speed options for calibrated values**

Build the speed choice list from `listOf(80, 100, 120, 140, 160, settings.wpm).distinct().sorted()` so a calibrated value such as 125 WPM remains visibly selectable and does not lose its selected state.

- [x] **Step 4: Update the helper copy**

Replace the current statement that says the playback speed slider is hidden with text explaining that the user can calibrate before playback and adjust by ±5 WPM during playback.

### Task 3: Add live WPM correction to the main playback screen

**Files:**
- Modify: `app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackScreen.kt`

- [x] **Step 1: Add a local speed-nudge action**

Add a small action that calls `stepWpm(settings.wpm, delta)`, updates Compose state, and persists the new `DisplaySettings` through `store.saveSettings`. Keep the current `progress` and `currentIndex` unchanged.

- [x] **Step 2: Add compact `−5 WPM +5` controls to the playback status row**

Keep the current WPM and orientation visible. Use compact `TextButton`s so the controls do not consume another vertical row or reduce the text viewport. The displayed value must update immediately after each tap.

- [x] **Step 3: Verify playback timing uses the new WPM**

Keep `settings.wpm` in the existing `LaunchedEffect(isPlaying, currentIndex, settings.wpm)` key list. This restarts the duration calculation with the new WPM while preserving the current sentence progress.

### Task 4: Add live WPM correction to the Android overlay

**Files:**
- Modify: `app/src/main/java/com/example/teleprompter/overlay/OverlayService.kt`

- [x] **Step 1: Add `changeSpeed(delta: Int)`**

Call `stepWpm`, update the service’s `settings`, save with `LocalStore`, and refresh the status text. Do not call `renderTranscript`, so the user’s scroll position and current character highlight remain unchanged.

- [x] **Step 2: Add two labeled speed buttons**

Keep the existing font controls but make their labels explicit (`字−` and `字＋`). Add `慢5` and `快5` buttons next to them, alongside the existing pause and theme controls. Keep the overlay status text showing the current WPM.

- [x] **Step 3: Verify the overlay playback loop reads updated settings**

Confirm the existing loop reads `settings.wpm` on each cycle; after `changeSpeed`, the next duration calculation uses the new value without resetting `progress`.

### Task 5: Run the full verification and build the APK

**Files:**
- Modify: `README.md` to update the feature list and test count.

- [x] **Step 1: Run all pure domain tests**

Compile the existing four test classes plus `SpeedCalibrationTest` with the Android Studio Kotlin compiler and run them through JUnit 4. Expected result: all existing tests plus the new calibration and curly-apostrophe word-count tests pass.

- [x] **Step 2: Build the debug APK**

Run:

```powershell
.\gradlew.bat assembleDebug --no-daemon --console=plain
```

Expected result: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [x] **Step 3: Inspect the APK artifact**

Record file size and SHA-256, and verify package metadata with the installed Android SDK `aapt2`. Check `adb devices`; if no device is connected, report that physical interaction testing remains pending.

- [x] **Step 4: Update README verification count and behavior**

Document calibration, ±5 WPM live adjustment, and the final number of passing tests. Do not claim device testing unless `adb` reports a connected device.
