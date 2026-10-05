# 英语演讲提词器 Android MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个离线原生 Android 提词器 APK，支持台本粘贴、格式尽力保留、WPM 速度选择、标准五句歌词式滚动、黑白背景、三种强调色、字号调整和横竖屏切换。

**Architecture:** 使用 Kotlin + Jetpack Compose。领域层只处理文本分段、单词统计、WPM 计算和播放状态；MVP 数据层使用 SharedPreferences + org.json 保存台本和设置；展示层使用 Compose 页面和单向状态流，播放层使用受控时钟驱动当前句窗口和滚动进度。

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Navigation Compose, SharedPreferences, org.json, Android Clipboard API, JUnit, Compose UI Test, Android Instrumentation Test.

**Source spec:** [`tasks/prd-english-teleprompter.md`](../../tasks/prd-english-teleprompter.md)

**Implementation adjustment:** 为避免 Room 编译器与 AGP 9 内置 Kotlin/KSP 的兼容问题，MVP 实际使用 `SharedPreferences + org.json` 保存台本和设置；行为仍保持离线、本地、无服务器。

## Implementation status

- [x] Task 1：Android 工程骨架、Manifest、Compose 构建配置
- [x] Task 2：单词统计、英文分句、WPM 时长和播放窗口领域逻辑
- [x] Task 3：本地台本与设置存储、系统剪贴板读取
- [x] Task 4：视觉主题、首页和导航
- [x] Task 5：编辑台本页、粘贴、保存和最近列表
- [x] Task 6：WPM、字号、颜色、背景、倒计时和方向设置
- [x] Task 7：歌词式五句播放、当前句高亮、暂停/继续和剩余时间
- [x] Task 8：横竖屏切换、屏幕常亮、主题切换和播放位置恢复
- [x] Task 9：debug APK 构建、APK 元数据核验和 Wrapper 验证
- [!] Gradle `testDebugUnitTest` 的测试 worker 仍有 AGP 9 classpath 问题；12 个领域测试已用独立 Kotlin/JUnit 入口通过，待后续升级 AGP/Kotlin 或修正测试任务配置。

---

## 1. 文件结构与职责

以下路径以新建 Android 项目 `app/` 为基准；包名建议使用 `com.example.teleprompter`，正式发布前替换为最终包名。

```text
app/src/main/java/com/example/teleprompter/
├── MainActivity.kt
├── TeleprompterApp.kt
├── data/
│   └── LocalStore.kt
├── domain/
│   ├── model/ScriptDocument.kt
│   ├── model/DisplaySettings.kt
│   ├── model/PlaybackState.kt
│   ├── parser/SpeechTextParser.kt
│   ├── parser/WordCounter.kt
│   └── playback/PlaybackEngine.kt
├── presentation/
│   ├── navigation/AppNavGraph.kt
│   ├── theme/TeleprompterTheme.kt
│   ├── home/HomeScreen.kt
│   ├── editor/ScriptEditorScreen.kt
│   ├── settings/PlaybackSettingsScreen.kt
│   ├── playback/PlaybackScreen.kt
│   ├── playback/PlaybackViewModel.kt
│   └── components/TeleprompterControls.kt
└── util/Formatters.kt

app/src/test/java/com/example/teleprompter/
├── domain/parser/WordCounterTest.kt
├── domain/parser/SpeechTextParserTest.kt
└── domain/playback/PlaybackEngineTest.kt

app/src/androidTest/java/com/example/teleprompter/
├── editor/ScriptEditorScreenTest.kt
├── playback/PlaybackScreenTest.kt
└── navigation/AppNavigationTest.kt
```

边界约束：

- `domain` 不引用 Compose、Activity 或 Android View。
- `data` 不直接修改 UI 状态，只通过 `LocalStore` 提供本地数据。
- `presentation` 不直接拼接 JSON；页面通过 `LocalStore` 读写台本和设置。
- `PlaybackEngine` 不负责绘制，只返回当前单元、进度比例、剩余时间和窗口内容。

## 2. Task 1：创建 Android 项目骨架

**Files:**

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/example/teleprompter/MainActivity.kt`
- Create: `app/src/main/java/com/example/teleprompter/TeleprompterApp.kt`

- [ ] **Step 1: 创建空项目并配置 Compose**

配置 Compose、Material 3、Navigation Compose 和 AndroidX 基础依赖。MVP 使用 Android 原生 `SharedPreferences` 与 `org.json`，最低版本使用 API 26；targetSdk 使用实现时的当前稳定 SDK。

- [ ] **Step 2: 配置 AndroidManifest**

Manifest 不添加网络权限；主 Activity 使用 `android:screenOrientation="unspecified"`，由播放页手动控制方向；设置应用标签为“演讲提词器”。

- [ ] **Step 3: 创建最小 Activity**

`MainActivity` 只负责设置 Compose 内容和宿主窗口，导航由 `TeleprompterApp` 管理：

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TeleprompterApp() }
    }
}
```

- [ ] **Step 4: 构建空项目**

Run: `./gradlew assembleDebug`

Expected: `BUILD SUCCESSFUL`，生成 `app/build/outputs/apk/debug/app-debug.apk`。

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts app
git commit -m "chore: bootstrap teleprompter android app"
```

## 3. Task 2：实现领域模型、单词统计和分句

**Files:**

- Create: `app/src/main/java/com/example/teleprompter/domain/model/ScriptDocument.kt`
- Create: `app/src/main/java/com/example/teleprompter/domain/model/DisplaySettings.kt`
- Create: `app/src/main/java/com/example/teleprompter/domain/model/PlaybackState.kt`
- Create: `app/src/main/java/com/example/teleprompter/domain/parser/WordCounter.kt`
- Create: `app/src/main/java/com/example/teleprompter/domain/parser/SpeechTextParser.kt`
- Test: `app/src/test/java/com/example/teleprompter/domain/parser/WordCounterTest.kt`
- Test: `app/src/test/java/com/example/teleprompter/domain/parser/SpeechTextParserTest.kt`

- [ ] **Step 1: 写单词统计失败测试**

测试必须覆盖普通单词、撇号、连字符、数字、标点和多余空格：

```kotlin
@Test fun countsEnglishWordsWithoutCountingNumbers() {
    assertEquals(4, countWords("Good morning, everyone. It's well-known in 2026."))
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew testDebugUnitTest --tests '*WordCounterTest'`

Expected: FAIL，因为 `countWords` 尚未实现。

- [ ] **Step 3: 实现 WordCounter**

使用正则 `\\b[A-Za-z]+(?:['-][A-Za-z]+)*\\b` 提取单词；数字、中文、标点和空白不计入单词数。

- [ ] **Step 4: 写分句失败测试**

测试手动换行优先、缩写不误切、缺少标点时保留段落：

```kotlin
@Test fun keepsManualParagraphBoundaries() {
    val units = SpeechTextParser().parse("First line.\\n\\nSecond line without punctuation")
    assertEquals(listOf("First line.", "", "Second line without punctuation"), units.map { it.rawText })
}
```

- [ ] **Step 5: 实现 SpeechTextParser**

返回 `SpeechUnit` 列表；每个单元包含原文、单词数、段落索引和是否为空行。先按换行切分，再在非空段落内识别 `.`, `?`, `!` 边界；对 `Mr.`, `Mrs.`, `Dr.`, `e.g.`, `i.e.` 保留句点，不切分。

- [ ] **Step 6: 运行领域测试**

Run: `./gradlew testDebugUnitTest --tests '*parser*'`

Expected: 所有单元测试 PASS。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/domain app/src/test/java/com/example/teleprompter/domain/parser
git commit -m "feat: add speech parsing and word counting"
```

## 4. Task 3：实现本地存储与剪贴板读取

**Files:**

- Create: `app/src/main/java/com/example/teleprompter/data/LocalStore.kt`

- [ ] **Step 1: 实现本地存储**

`LocalStore` 使用 `SharedPreferences` 保存台本数组和显示设置 JSON。台本记录保存 `id`、`title`、`rawText`、`wordCount`、`updatedAt`、`lastPlaybackUnit` 和 `lastPlaybackProgress`。

- [ ] **Step 2: 实现剪贴板读取**

优先读取可用的 styled text 或 HTML 文本，同时始终提供 plain text；读取失败返回明确的 `ClipboardReadResult.Error`，不让页面崩溃。

- [ ] **Step 3: 实现设置存储**

保存：`wpm`、`fontScale`、`accentColor`、`themeMode`、`countdownSeconds`、`orientationMode`。

- [ ] **Step 4: 运行存储测试**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardReaderTest'`

Expected: 纯文本、空剪贴板和富文本降级测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/data app/src/test/java/com/example/teleprompter/data
git commit -m "feat: add offline scripts and settings storage"
```

## 5. Task 4：建立视觉主题和导航

**Files:**

- Create: `app/src/main/java/com/example/teleprompter/presentation/theme/TeleprompterTheme.kt`
- Create: `app/src/main/java/com/example/teleprompter/presentation/navigation/AppNavGraph.kt`
- Create: `app/src/main/java/com/example/teleprompter/presentation/home/HomeScreen.kt`
- Create: `app/src/main/java/com/example/teleprompter/presentation/components/TeleprompterControls.kt`
- Test: `app/src/androidTest/java/com/example/teleprompter/navigation/AppNavigationTest.kt`

- [ ] **Step 1: 创建颜色令牌**

实现深色/浅色两套语义颜色：背景、主文本、次文本、主按钮、晨蓝、暖黄、薄荷绿和分割线。不要在页面中散落原始颜色值。

- [ ] **Step 2: 创建首页布局**

首页包含“开始提词”“新建台本”“播放设置”和最近台本列表；删除操作需二次确认；空状态显示明确的“新建台本”入口。

- [ ] **Step 3: 创建导航图**

路由固定为：`home`、`editor/{scriptId}`、`settings/{scriptId}`、`playback/{scriptId}`。返回键从播放页回到播放设置，从设置回到编辑页，从编辑页回到首页。

- [ ] **Step 4: 添加点击目标和无障碍标签**

所有按钮最小 48dp，图标按钮提供 `contentDescription`，文本颜色不能低于设计令牌中的对比度要求。

- [ ] **Step 5: 运行导航 UI 测试**

Run: `./gradlew connectedDebugAndroidTest --tests '*AppNavigationTest'`

Expected: 首页可以进入编辑页、设置页和播放页，系统返回行为正确。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/presentation/theme app/src/main/java/com/example/teleprompter/presentation/navigation app/src/main/java/com/example/teleprompter/presentation/home app/src/main/java/com/example/teleprompter/presentation/components app/src/androidTest/java/com/example/teleprompter/navigation
git commit -m "feat: add app theme and navigation shell"
```

## 6. Task 5：实现编辑台本页

**Files:**

- Create: `app/src/main/java/com/example/teleprompter/presentation/editor/ScriptEditorScreen.kt`
- Create: `app/src/main/java/com/example/teleprompter/presentation/editor/ScriptEditorViewModel.kt`
- Modify: `app/src/main/java/com/example/teleprompter/presentation/navigation/AppNavGraph.kt`
- Test: `app/src/androidTest/java/com/example/teleprompter/editor/ScriptEditorScreenTest.kt`

- [ ] **Step 1: 写编辑页 UI 测试**

测试标题输入、文本输入、字数/时长显示、粘贴按钮和保存按钮存在；空内容保存时显示提示。

- [ ] **Step 2: 实现编辑状态**

ViewModel 使用 `StateFlow<ScriptEditorUiState>` 保存标题、原文、格式块、单词数、预计时长和保存状态。

- [ ] **Step 3: 实现粘贴路径**

点击“＋ 粘贴内容”读取剪贴板；成功后更新文本和格式块，失败后显示“无法读取剪贴板，请直接粘贴或输入”。

- [ ] **Step 4: 实现排版保留规则**

编辑页保留换行、空行和缩进；渲染预览最多显示两个连续空行，原文数据不改变；不支持的富文本属性降级为普通文本。

- [ ] **Step 5: 实现保存和草稿恢复**

文本改变后 500ms 防抖自动保存；点击“保存并开始”先写入 `LocalStore`，再进入设置页；应用重启后从 `LocalStore` 恢复草稿。

- [ ] **Step 6: 运行编辑页测试**

Run: `./gradlew connectedDebugAndroidTest --tests '*ScriptEditorScreenTest'`

Expected: 纯文本输入、剪贴板粘贴、空内容校验和保存流程 PASS。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/presentation/editor app/src/main/java/com/example/teleprompter/presentation/navigation app/src/androidTest/java/com/example/teleprompter/editor
git commit -m "feat: add script editing and paste flow"
```

## 7. Task 6：实现播放设置与 WPM 估算

**Files:**

- Create: `app/src/main/java/com/example/teleprompter/presentation/settings/PlaybackSettingsScreen.kt`
- Create: `app/src/main/java/com/example/teleprompter/presentation/settings/PlaybackSettingsViewModel.kt`
- Create: `app/src/main/java/com/example/teleprompter/util/Formatters.kt`
- Modify: `app/src/main/java/com/example/teleprompter/presentation/navigation/AppNavGraph.kt`
- Test: `app/src/test/java/com/example/teleprompter/util/FormattersTest.kt`

- [ ] **Step 1: 写时长计算测试**

```kotlin
@Test fun formats1024WordsAt120WpmAsEightMinutesThirtyTwoSeconds() {
    assertEquals("08:32", formatDurationSeconds(ceil(1024.0 / 120.0 * 60).toInt()))
}
```

- [ ] **Step 2: 实现 WPM 选择**

仅提供 80、100、120、140、160；默认 120；切换后立即更新预计时长并写入 `LocalStore`。

- [ ] **Step 3: 实现显示设置**

提供字号四档、三种强调色、深/浅背景、倒计时四档和方向选择；设置页面使用分组卡片，但不使用复杂嵌套弹窗。

- [ ] **Step 4: 实现开始提词**

点击“开始提词”时保存当前设置和当前稿件，按倒计时设置导航到播放页；倒计时关闭时直接进入播放状态。

- [ ] **Step 5: 运行设置测试**

Run: `./gradlew testDebugUnitTest --tests '*FormattersTest'`

Expected: WPM、时长和剩余时间格式化测试 PASS。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/presentation/settings app/src/main/java/com/example/teleprompter/util app/src/test/java/com/example/teleprompter/util
git commit -m "feat: add playback settings and wpm presets"
```

## 8. Task 7：实现歌词式播放引擎

**Files:**

- Create: `app/src/main/java/com/example/teleprompter/domain/playback/PlaybackEngine.kt`
- Create: `app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackViewModel.kt`
- Create: `app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackScreen.kt`
- Create: `app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackTextWindow.kt`
- Test: `app/src/test/java/com/example/teleprompter/domain/playback/PlaybackEngineTest.kt`
- Test: `app/src/androidTest/java/com/example/teleprompter/playback/PlaybackScreenTest.kt`

- [ ] **Step 1: 写播放引擎失败测试**

测试给定句子列表和当前索引时返回前两句、当前句和后两句；缺少的句子不补假文字。

- [ ] **Step 2: 实现窗口计算**

```kotlin
fun windowAround(units: List<SpeechUnit>, currentIndex: Int): List<WindowItem> {
    val start = maxOf(0, currentIndex - 2)
    val end = minOf(units.size, currentIndex + 3)
    return units.subList(start, end).mapIndexed { offset, unit ->
        WindowItem(unit = unit, isCurrent = start + offset == currentIndex)
    }
}
```

- [ ] **Step 3: 实现时间推进**

每个非空单元使用 `max(segmentWordCount / wpm * 60, 1.5)` 秒；使用 `withFrameNanos` 或稳定的协程时钟更新进度，不在每帧触发数据库写入。

- [ ] **Step 4: 实现暂停和继续**

暂停时保存 `currentIndex` 与 `progressFraction`；继续时从相同位置恢复；每 1 秒或状态切换时持久化一次播放位置。

- [ ] **Step 5: 实现播放 UI**

深色背景下显示标准五句窗口，当前句使用强调背景块和高亮色，上下文使用次文本色；底部显示进度条、段落索引和剩余时间。

- [ ] **Step 6: 实现播放控件**

主按钮为暂停/继续；更多面板提供字号、背景、字体颜色和方向切换；不显示播放中速度滑杆。

- [ ] **Step 7: 运行播放测试**

Run: `./gradlew testDebugUnitTest --tests '*PlaybackEngineTest'`

Expected: 窗口、时长、暂停恢复和边界索引测试 PASS。

Run: `./gradlew connectedDebugAndroidTest --tests '*PlaybackScreenTest'`

Expected: 当前句高亮、暂停/继续和剩余时间显示 PASS。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/domain/playback app/src/main/java/com/example/teleprompter/presentation/playback app/src/test/java/com/example/teleprompter/domain/playback app/src/androidTest/java/com/example/teleprompter/playback
git commit -m "feat: add lyric-style teleprompter playback"
```

## 9. Task 8：接入横竖屏、常亮和主题切换

**Files:**

- Modify: `app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackScreen.kt`
- Modify: `app/src/main/java/com/example/teleprompter/MainActivity.kt`
- Modify: `app/src/main/java/com/example/teleprompter/presentation/theme/TeleprompterTheme.kt`
- Test: `app/src/androidTest/java/com/example/teleprompter/playback/PlaybackScreenTest.kt`

- [ ] **Step 1: 接入方向切换**

使用 `requestedOrientation` 切换 `SCREEN_ORIENTATION_PORTRAIT` 和 `SCREEN_ORIENTATION_LANDSCAPE`；方向变化后从 ViewModel 恢复相同的当前索引和进度比例。

- [ ] **Step 2: 接入屏幕常亮**

播放状态为 `Playing` 或 `Paused` 时设置窗口 `FLAG_KEEP_SCREEN_ON`；离开播放页或暂停超过 5 分钟时清除标志。

- [ ] **Step 3: 接入主题切换**

主题颜色由 `DisplaySettings` 映射到语义颜色令牌，切换主题时仅更新 UI 状态，不重新解析稿件、不重置播放位置。

- [ ] **Step 4: 测试重建恢复**

在横屏、主题切换、应用切后台和系统字体放大后确认当前句、速度和剩余时间仍然正确。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/teleprompter/MainActivity.kt app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackScreen.kt app/src/main/java/com/example/teleprompter/presentation/theme app/src/androidTest/java/com/example/teleprompter/playback
git commit -m "feat: add orientation, awake screen, and theme switching"
```

## 10. Task 9：完整验收、性能检查和 APK 打包

**Files:**

- Create: `docs/testing/teleprompter-test-matrix.md`
- Modify: `README.md`
- Create: `app/proguard-rules.pro`（只保留实际需要的规则）

- [ ] **Step 1: 运行全部单元测试**

Run: `./gradlew testDebugUnitTest`

Expected: 所有领域、格式化、播放引擎测试 PASS。

- [ ] **Step 2: 运行全部 Android UI 测试**

Run: `./gradlew connectedDebugAndroidTest`

Expected: 导航、编辑、播放和方向切换测试 PASS。

- [ ] **Step 3: 进行真机手工验收**

按以下顺序执行：

1. 新建 1000 词英文稿件。
2. 粘贴包含空行、缩进、粗体和颜色的内容。
3. 选择 120 WPM、36sp、深色、晨蓝、5 秒倒计时。
4. 播放 3 分钟，执行暂停/继续。
5. 切换横屏，再切回竖屏。
6. 切换浅色、暖黄和薄荷绿。
7. 退出应用并重新打开，确认继续上次位置。
8. 关闭网络后重复播放流程。

- [ ] **Step 4: 检查性能指标**

确认 1000 词稿件无明显掉帧；10000 词稿件打开不触发 ANR；主题和方向切换有即时反馈；播放期间不持续产生高频数据库写入。

- [ ] **Step 5: 生成 APK**

Run: `./gradlew assembleRelease`

Expected: 生成签名配置要求明确的 release APK；开发阶段至少提供 `app-debug.apk` 供真机验证。

- [ ] **Step 6: 更新 README**

记录：构建命令、最低 Android 版本、APK 输出路径、已知限制、测试设备和安装方式。

- [ ] **Step 7: Commit**

```bash
git add docs/testing README.md app/proguard-rules.pro
git commit -m "test: verify mvp and document apk build"
```

## 11. 开发顺序与里程碑

| 里程碑 | 完成条件 |
|---|---|
| M1 可运行骨架 | 空项目可构建，导航和主题令牌存在 |
| M2 台本闭环 | 新建、粘贴、保存、编辑、最近列表可用 |
| M3 速度闭环 | WPM、单词统计、预计时长和设置可用 |
| M4 播放闭环 | 标准五句、当前句高亮、暂停/继续和滚动可用 |
| M5 现场能力 | 横竖屏、常亮、黑白背景、三种强调色和恢复位置可用 |
| M6 交付 | 单元测试、UI 测试、真机验收、APK 构建完成 |

## 12. 计划自检

- 已覆盖：台本输入、格式保留、WPM、预计时长、标准五句、高亮、暂停、黑白背景、三种强调色、字号、横竖屏、常亮、离线和异常恢复。
- 未加入与核心场景无关的登录、联网、摄像头、AI 和复杂编辑功能。
- 领域模型、数据层、UI 层和播放引擎边界明确，单元测试不依赖 Compose。
- 文档中没有未完成标记或需要开发者自行猜测的功能要求。
- 所有任务包含目标文件、测试方式和提交节点。
