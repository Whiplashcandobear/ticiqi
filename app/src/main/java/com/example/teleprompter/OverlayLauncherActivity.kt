package com.example.teleprompter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import com.example.teleprompter.data.LocalStore
import com.example.teleprompter.domain.model.PromptMode
import com.example.teleprompter.overlay.OverlayService

/**
 * 悬浮提词的默认入口：点「开始提词」直接走这里。
 *
 * 一次性处理三件事，然后立即拉起 [OverlayService] 并退出自己：
 *  1. 没选台本 → 取最近编辑的台本；
 *  2. 没有悬浮窗权限 → 跳系统设置，用户返回后自动继续；
 *  3. 语音跟随但没麦克风权限 → 请求一次，拒绝也继续（悬浮窗内会显示原因并兜底）。
 */
class OverlayLauncherActivity : Activity() {

    private var scriptId = -1L
    private var requestedOverlayPermission = false
    private var requestedMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptId = intent.getLongExtra(EXTRA_SCRIPT_ID, -1L)
    }

    override fun onResume() {
        super.onResume()
        if (scriptId == -1L) {
            val latest = LocalStore(this).loadScripts().maxByOrNull { it.updatedAt }
            if (latest == null) {
                Toast.makeText(this, "先新建一个台本再开始提词", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return
            }
            scriptId = latest.id
        }

        if (!Settings.canDrawOverlays(this)) {
            if (!requestedOverlayPermission) {
                requestedOverlayPermission = true
                Toast.makeText(
                    this,
                    "请允许「显示在其他应用上层/悬浮窗」权限，返回后自动开始",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
            Toast.makeText(this, "未授予悬浮窗权限，无法开始悬浮提词", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val settings = LocalStore(this).loadSettings()
        if (settings.promptMode == PromptMode.VOICE_FOLLOW &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            if (!requestedMic) {
                requestedMic = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST_CODE)
                return
            }
            // 拒绝了也继续：悬浮窗里会显示"未授权麦克风"并自动兜底固定速度。
        }

        val document = LocalStore(this).loadScripts().firstOrNull { it.id == scriptId }
        if (document == null) {
            Toast.makeText(this, "没有找到这个台本", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        OverlayService.start(this, document.id, document.lastPlaybackUnit, document.lastPlaybackProgress)
        finish()
    }

    companion object {
        private const val MIC_REQUEST_CODE = 1001
        const val EXTRA_SCRIPT_ID = "script_id"

        fun start(context: Context, scriptId: Long) {
            val intent = Intent(context, OverlayLauncherActivity::class.java)
                .putExtra(EXTRA_SCRIPT_ID, scriptId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
