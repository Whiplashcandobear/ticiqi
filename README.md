# 演讲提词器 Android MVP

这是一个离线运行的 Android 英语演讲提词器，适用于“一台手机录制视频、另一台手机播放台本”的场景。

## 当前功能

- 新建、编辑、保存、删除本地台本
- 粘贴英文稿件并保留换行、空行和段落顺序
- 80 / 100 / 120 / 140 / 160 WPM 速度预设；支持按当前台本校准实际 WPM
- 可选择“固定 WPM”或“实时语音跟随”；实时模式按句匹配英语语音，识别中断约 2 秒自动回到固定 WPM
- 实时语音跟随优先使用设备端识别；只在当前播放会话使用麦克风，不保存、不上传音频
- 播放页和悬浮窗支持 −5 / +5 WPM 实时微调，不重置当前播放位置
- 按真实句子建立播放单元；当前句高亮，长句仅在屏幕内视觉折行，不拆成多个播放段
- 播放页和悬浮窗都支持拖动进度、全文上下滚动与字母级动态高亮
- 深色/浅色背景；晨蓝、暖黄、薄荷绿三种强调色
- 四档字号、倒计时、暂停/继续、从头开始
- 默认竖屏，播放页可切换横屏
- 播放期间保持屏幕常亮
- 固定 WPM 模式断网可用；台本和播放设置保存在本机
- 实时语音模式需要录音权限，具体能力取决于手机系统语音识别服务

## 构建环境

- Android Studio：`D:\Android`
- Android SDK：`D:\program\Android\SDK`
- JDK：Android Studio 自带 JDK 21
- Gradle：Wrapper 9.3.1
- 最低 Android 版本：API 26

## 构建 APK

```powershell
$env:JAVA_HOME = 'D:\Android\jbr'
.\gradlew.bat assembleDebug
```

APK 输出路径：`app\build\outputs\apk\debug\app-debug.apk`

## 测试说明

领域层 28 个 JUnit 测试已用 Android Studio 自带 Kotlin 编译器独立运行通过，覆盖固定播放和实时语音句级匹配。当前 AGP 9 内置 Kotlin 的 Gradle JUnit 任务存在测试 classpath 路径问题，因此 `testDebugUnitTest` 暂不作为唯一验收入口；APK 的 Kotlin 编译和 `assembleDebug` 已通过。

## 实现说明

为避免 Room 编译器与 AGP 9 内置 Kotlin/KSP 的兼容问题，MVP 使用 `SharedPreferences + org.json` 保存本地台本和播放设置。它保持离线、本地、无服务器的产品行为；后续需要更大规模台本或复杂查询时，再迁移到 Room。
