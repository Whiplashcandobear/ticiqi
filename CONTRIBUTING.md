# 开发与提交约定

## 本地构建

```powershell
$env:JAVA_HOME = 'D:\Java21'
.\gradlew.bat assembleDebug --no-daemon --console=plain
```

## 提交前检查

```powershell
git diff --check
git status --short --ignored
```

不要提交：`local.properties`、签名文件、密钥、token、密码、APK、个人台本原文和录音文件。

## 提交原则

- 使用清晰的 conventional commit：`feat:`、`fix:`、`docs:`、`test:`、`chore:`。
- 固定 WPM 是稳定兜底，任何实时语音修改都必须验证回退路径。
- 文本解析修改必须验证长句、硬换行、弯引号和段落边界。
- UI 修改必须同时检查竖屏、横屏和悬浮窗。
- 不把尚未在真机上验证的设备行为写成“所有手机均支持”。
