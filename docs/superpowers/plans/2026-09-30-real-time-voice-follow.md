# 实时语音跟随（混合模式）执行计划

## 目标

在现有英语提词器中加入两种互斥的播放模式：

1. **固定 WPM**：保持当前速度、倒计时、暂停/继续、拖动进度条和字体高亮行为不变。
2. **实时语音跟随**：优先使用 Android 设备端语音识别，以“句”为单位将识别结果匹配到台本；识别有效时跟随用户真实语速，识别结果中断约 2 秒时自动切回固定 WPM。

本次实现不做云端上传、不保存音频、不做逐字语音对齐。实时模式只负责句级定位和当前句的字符高亮，固定模式作为稳定兜底。

## 已冻结的产品决策

- 句级同步，不承诺逐字级同步。
- 设备端识别优先；系统没有可用的设备端识别服务时，允许进入页面但立即使用固定 WPM，并明确显示“固定速度兜底”。
- 录音权限只在实时语音跟随启动时申请。
- 识别音频只在当前会话内使用，不录音、不上传、不持久化。
- 连续约 2 秒没有有效识别结果时回退；恢复到稳定匹配后再回到语音跟随。
- 横屏、竖屏和 Android 悬浮窗共用同一套句匹配规则；固定 WPM 的现有行为不改。

## 技术边界

- Kotlin + Jetpack Compose 现有工程继续使用。
- 纯匹配逻辑放在 `domain`，使用 JVM 单元测试覆盖，不依赖 Android API。
- Android `SpeechRecognizer` 只作为输入适配器，负责 partial/final result、错误、重启和释放。
- 不新增网络权限；Android 系统语音服务是否可用由设备决定。
- 覆盖 API 26 起的现有 minSdk；API 31+ 优先调用设备端识别 API，低版本使用系统识别器并在不可用时回退。

## 分步执行

### 1. 建立模式和持久化模型（先测试）

文件：

- `app/src/main/java/com/example/teleprompter/domain/model/ScriptDocument.kt`
- `app/src/main/java/com/example/teleprompter/data/LocalStore.kt`
- `app/src/test/java/com/example/teleprompter/data/DisplaySettingsPersistenceTest.kt`

工作：

1. 先写失败测试：默认模式为 `FIXED_WPM`；保存并读取 `VOICE_FOLLOW` 后值不丢失；旧设置 JSON 没有字段时仍默认为固定 WPM。
2. 新增 `PromptMode { FIXED_WPM, VOICE_FOLLOW }`，加入 `DisplaySettings`。
3. 将模式写入/读取 SharedPreferences 的 JSON；读取未知枚举值时安全回退固定 WPM。

验证：运行 JVM 测试，确保旧设置兼容。

### 2. 实现纯 Kotlin 的句级匹配器（先测试）

文件：

- `app/src/main/java/com/example/teleprompter/domain/voice/VoiceFollowState.kt`
- `app/src/main/java/com/example/teleprompter/domain/voice/SpeechTextMatcher.kt`
- `app/src/main/java/com/example/teleprompter/domain/voice/VoiceFollowEngine.kt`
- `app/src/test/java/com/example/teleprompter/voice/SpeechTextMatcherTest.kt`
- `app/src/test/java/com/example/teleprompter/voice/VoiceFollowEngineTest.kt`

工作：

1. 先写失败测试，覆盖：大小写/标点/弯引号归一化；partial result 只推进当前句；识别到下一句后提交句索引；台本中的长句换行不影响匹配；识别结果含少量口误时不跳到远处；结果为空、重复或不在附近窗口时不改变位置；连续约 2 秒无有效结果进入回退状态；收到稳定有效结果后退出回退。
2. 把每个 `SpeechUnit` 转成可比较的标准化词 token，同时保留原句索引。
3. 只在当前句及后续有限窗口内计算候选，使用有序 token 的最长连续匹配、覆盖率和前缀分数，避免相同短词导致跳句。
4. 维护 `confirmedUnitIndex`、当前句 token/字符进度、候选句、最后有效识别时间、`isFallbackToWpm` 和 `needsResumeConfirmation`。
5. 采用滞后确认：候选句需要达到最小覆盖率或连续 partial/final 结果一致后才提交；识别回退后恢复时先锁定到当前句附近，不跨越多个句子。
6. 匹配器不修改原文换行；显示层仍按台本原文绘制，避免“为了匹配而改排版”。

验证：只运行新增纯 JVM 测试和已有 parser/playback 测试，确保固定模式相关逻辑不回归。

### 3. 接入 Android 语音识别适配器

文件：

- `app/src/main/java/com/example/teleprompter/voice/AndroidSpeechRecognizer.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/teleprompter/MainActivity.kt`

工作：

1. 为 `RECORD_AUDIO` 增加 manifest 声明；检查 API 31+ 的设备端识别可用性。
2. 封装 `SpeechRecognizer`，统一输出 partial text、final text、错误、开始/停止状态和不可用原因。
3. 使用 `RecognizerIntent` 的英文自由识别、partial result、单结果配置；回调在主线程处理。
4. 识别结束后按短延迟重启，避免一次 final result 就停止；连续错误采用退避，明确不可用时停止重试。
5. `close()` 中移除监听、停止识别并销毁实例，防止旋转和退出页面泄漏。
6. 不申请 INTERNET，不保存识别音频；日志不写入原始音频或完整语音内容。

验证：编译检查 API 分支、manifest 和生命周期；真机无权限/拒绝权限/无识别服务时不崩溃。

### 4. 在设置页加入模式选择和状态说明

文件：

- `app/src/main/java/com/example/teleprompter/presentation/settings/PlaybackSettingsScreen.kt`

工作：

1. 新增“播放方式”分段选择：`固定 WPM`、`实时语音跟随`。
2. 固定模式继续显示当前速度选择、测速校准和 ±5 WPM 说明。
3. 语音模式显示简短说明：“按句跟随；识别中断约 2 秒自动回到固定 WPM”。
4. 保存设置后进入播放页；未授予录音权限时不要在设置页强制弹窗。
5. 保持现有深色/浅色、横竖屏和字体设置，不增加复杂配置。

验证：Compose 编译；切换模式后退出再进入仍保持选择；旧脚本设置可正常显示。

### 5. 接入竖屏/横屏播放页

文件：

- `app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackScreen.kt`
- `app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackTextWindow.kt`（如现有实现文件名不同，以实际文件为准）
- `app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackViewModel.kt`（如现有工程没有该文件，则在 `PlaybackScreen.kt` 内保持最小状态封装）

工作：

1. 先保留现有固定 WPM loop；只有 `PromptMode.VOICE_FOLLOW` 才启动语音会话。
2. 进入实时模式时申请录音权限；拒绝时显示一次明确提示并继续固定 WPM，不阻塞用户使用。
3. 将识别结果送入 `VoiceFollowEngine`；句索引变化时更新当前句，字符高亮沿用现有渲染器。
4. 语音跟随有效时，当前句位置由匹配器驱动；回退时使用现有 `segmentDurationSeconds` / WPM 计时器；恢复时以当前句为锚点，不从头开始。
5. 顶部状态展示 `语音跟随`、`固定 WPM 兜底` 或 `固定 WPM`，并保留当前 WPM、横竖屏信息。
6. 保持全文可拖动浏览：暂停时、开始时都可手动查看前后内容；手动拖动后不强行把视口拉回，提供轻量“回到跟随句”操作。
7. 横屏继续使用紧凑底部控制条，确保高亮句和上下文不被按钮遮挡；字体大小不因语音模式变化。

验证：Compose 编译；无权限、识别中断、恢复、暂停、拖拽、横屏和竖屏状态都走同一状态机。

### 6. 接入 Android 悬浮窗模式

文件：

- `app/src/main/java/com/example/teleprompter/overlay/OverlayService.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/teleprompter/presentation/playback/PlaybackMoreDialog.kt`（如现有实现拆分）

工作：

1. 悬浮窗启动前沿用现有 `SYSTEM_ALERT_WINDOW` 检查；语音模式额外检查录音权限。
2. 将前台服务声明为同时支持 overlay 与 microphone 的类型，并使用 SDK 36 可接受的 `startForeground` 方式。
3. OverlayService 按当前设置创建同一个 `VoiceFollowEngine` 和 Android 识别适配器；识别不可用时自动使用已有 WPM loop。
4. 保持悬浮窗可拖动、可调整字号、暂停/继续、从头、颜色切换和进度拖动；语音模式只增加状态小字，不挤压台本区域。
5. 服务销毁、窗口移除、通知取消时一并释放识别器。

验证：编译 manifest；无悬浮窗权限/无录音权限/服务被系统回收时不崩溃；固定模式悬浮窗行为保持不变。

### 7. 回归、构建和交付

文件：

- `README.md`
- `docs/superpowers/specs/2026-09-30-adaptive-speech-speed-design.md`（如需补充模式说明）

工作：

1. 运行全部纯 JVM 测试；失败时先定位根因再修改。
2. 运行 `./gradlew.bat assembleDebug --no-daemon --console=plain`。
3. 检查 APK 输出路径、文件大小、SHA-256、包名和 versionName。
4. 更新 README：说明两种模式、录音权限、设备端优先、2 秒回退和隐私边界。
5. 用 `verification-before-completion` 清单做最后验证；只有命令实际成功后才报告 APK 已生成。

## 验收标准

- 固定 WPM 模式与当前版本行为一致。
- 实时模式能够根据 partial/final 英语识别结果按句推进，不因换行或标点造成错误截断。
- 连续约 2 秒无有效识别时显示并使用固定 WPM 兜底；恢复后能从当前附近继续。
- 竖屏、横屏、悬浮窗都保留当前句高亮、字符级动态高亮和完整上下文。
- 全文在播放或暂停时都可以拖拽浏览；手动浏览不被自动滚动瞬间抢回。
- 录音权限拒绝或设备识别不可用时仍能正常使用固定 WPM。
- 不新增网络权限，不保存或上传音频。
- JVM 测试和 debug APK 构建均通过。

## 风险与处理

- Android 设备端识别能力因厂商而异：识别器不可用时明确回退，不把失败伪装成实时跟随。
- 语音识别 partial 文本可能重复、缺词或带标点：使用标准化、附近窗口和滞后确认，避免跳句。
- 语音识别与相机同时使用可能产生麦克风资源竞争：提示用户确认录制应用没有独占麦克风；冲突时回退 WPM。
- 长句在小屏幕上仍可能跨多行：保持全文滚动容器、动态高度和当前句锚点，不用固定高度裁剪文本。
