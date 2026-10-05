# 演讲提词器｜Ticiqi

一个面向英语演讲录制场景的 Android 提词器。核心目标是：让演讲者在录制视频时，能够看到清晰、完整、不会被长句截断的台本，并根据自己的真实语速稳定跟随。

适用场景：一部手机拍摄，另一部手机播放台本；也支持单机前置摄像头 + Android 悬浮窗模式。

## 功能概览

- 本地新建、编辑、保存和删除台本
- 粘贴文本时保留段落、空行和作者排版；自动换行不会被误判为新句
- 固定 WPM：80 / 100 / 120 / 140 / 160 等预设，支持真实语速校准和播放中 ±5 WPM 微调
- 实时语音跟随：优先使用设备端识别，按句匹配 partial/final 结果
- 混合回退：识别中断约 2 秒后自动切换固定 WPM，识别恢复后继续跟随
- 当前句高亮 + 当前句字符级动态高亮
- 长句自适应折行，全文可滚动，横屏使用紧凑底部控制条
- 进度条拖动、暂停/继续、从头开始、倒计时
- 深色/浅色背景、晨蓝/暖黄/薄荷绿三种强调色、四档字号
- Android 悬浮窗：可拖动窗口、调整字号、暂停、从头、调速和切换颜色

## 技术结构

```text
app/src/main/java/com/example/teleprompter/
├── data/                 本地 SharedPreferences + JSON 存储
├── domain/parser/        文本清洗、句子边界和单词统计
├── domain/playback/      WPM 播放、进度映射、滚动和校准
├── domain/voice/         纯 Kotlin 句级语音匹配与回退状态机
├── voice/                Android SpeechRecognizer 适配器
├── presentation/         Compose 首页、编辑、设置和播放页
└── overlay/              Android 原生悬浮窗前台服务
```

实时语音模式只负责句级定位，不承诺音素级或真正逐字语音对齐；字符高亮由识别 partial 结果和当前进度共同驱动。固定 WPM 始终是可用的稳定方案。

## 构建环境

- Android Studio：`D:\Android`
- Android SDK：`D:\program\Android\SDK`
- JDK：Android Studio 自带 JDK 21
- Gradle Wrapper：9.3.1
- 最低 Android 版本：API 26
- 当前应用版本：`0.1.0`

## 构建 APK

```powershell
$env:JAVA_HOME = 'D:\Java21'
.\gradlew.bat assembleDebug --no-daemon --console=plain
```

APK 输出路径：`app\build\outputs\apk\debug\app-debug.apk`

## 测试与验收

- 28 个领域层 JUnit 测试已通过独立 Kotlin/JUnit 运行器
- 覆盖文本解析、英文单词统计、固定播放、WPM 校准、语音句级匹配和 2 秒回退恢复
- Android Kotlin 编译和 `assembleDebug` 已通过
- GitHub Actions 会在 push / pull request 时执行 debug APK 构建

当前 AGP 9 内置 Kotlin 的 Gradle JUnit worker 存在测试 classpath 兼容问题，因此 `testDebugUnitTest` 不作为唯一验收入口；独立 JVM 测试和 APK 构建分别验证领域逻辑与 Android 编译链。

## 数据和隐私

- 应用不申请 `INTERNET` 权限
- 台本、播放进度和显示设置保存在本机，不上传到应用服务器
- 固定 WPM 模式不需要麦克风
- 实时语音模式只在播放会话内申请麦克风；应用本身不保存或上传音频
- Android 系统语音识别服务的处理方式取决于设备厂商、Android 版本和系统设置
- 本仓库不提交 `local.properties`、签名文件、密钥、token、密码、APK 和本机台本

更多信息见：[SECURITY.md](SECURITY.md)。

## 开发记录

- [CHANGELOG.md](CHANGELOG.md)：版本变更摘要
- [开发历程与问题复盘](docs/DEVELOPMENT_HISTORY.md)：从需求、实现、测试到重新整理上传的过程
- [问题与解决方案](docs/TROUBLESHOOTING.md)：长句、换行、横屏、进度、语速和语音识别等问题
- [实时语音跟随执行计划](docs/superpowers/plans/2026-09-30-real-time-voice-follow.md)：技术执行计划

## 许可证

当前仓库未声明开源许可证。除非仓库所有者另行授权，代码和设计资料不代表允许第三方复制、发布或商业使用。
