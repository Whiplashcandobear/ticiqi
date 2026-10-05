# 数据与隐私说明

## 当前实现

- 台本内容、播放进度和显示设置只保存在 Android 本机的 `SharedPreferences` 中。
- 应用没有申请 `INTERNET` 权限，不包含自建服务器、账号系统或远程数据同步。
- “固定 WPM”模式完全不需要麦克风。
- “实时语音跟随”只在当前播放会话申请麦克风权限；应用本身不录音、不保存音频、不上传音频文件。
- Android 系统语音识别服务是否会使用设备或系统提供的处理能力，取决于手机厂商、Android 版本和用户的系统设置；应用只消费识别文本结果。
- 悬浮窗功能使用 Android 官方悬浮窗权限和前台服务，不读取其他应用的内容。

## 提交到 GitHub 前的安全规则

以下文件和内容不得提交到仓库：

- `local.properties`
- `.env*`
- Android 签名文件：`*.jks`、`*.keystore`、`*.p12`、`*.pfx`
- 私钥和证书材料：`*.pem`、`*.key`
- `keystore.properties`、`secrets.properties`、`google-services.json`
- API key、access token、密码和个人台本原文

这些路径已写入 `.gitignore`。如果误提交过凭据，应立即撤销并重新生成，而不是只删除当前文件。

## 发布前检查

推送前应检查：

1. `git status --short` 中没有本机配置、签名文件或个人台本。
2. 使用 `git grep` 或等效扫描确认没有 token、密码和私钥。
3. APK 使用本机 release keystore 签名；不要把 keystore 或密码放进 GitHub。

如发现安全问题，请不要在公开 Issue 中粘贴凭据或台本内容，优先撤销凭据并私下报告。
