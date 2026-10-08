package com.example.teleprompter.overlay

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * 悬浮提词内置录像 + 实时取景，基于 **CameraX**（Google 官方相机库）。
 *
 * 为什么用 CameraX 而不是裸 Camera2：取景画面的比例、旋转、前置镜像这些在
 * Camera2 里需要自己用 TextureView.setTransform 手算矩阵（坐标系/镜像平移符号
 * 极易出错，表现为画面拉伸、放大数倍、黑屏）。CameraX 的 PreviewView 内置
 * PreviewTransform 会自动按 targetRotation / 窗口尺寸 / 传感器朝向算出正确变换
 * —— 这些是绝大多数相机 App 的标准做法。
 *
 * 关于镜像（踩过的坑，实测结论以真机为准）：
 * - 取景预览的镜像：由 PreviewView 的显示变换决定，默认前置会照镜子；
 *   我们**没有**给 PreviewView 加 scaleX = -1f，所以预览是真实方向（所见即所得）。
 * - 录像成片的镜像：由 VideoCapture 的 mirrorMode 决定，**默认行为随版本漂移**，
 *   在 1.4.2 上实测前置录像成片是镜像的（与预览相反）。
 * - 因此要「取景与成片方向完全一致」，必须**显式**把 VideoCapture 的 mirrorMode
 *   设为 MIRROR_MODE_OFF，不能依赖默认。只改预览或只改成片都会导致两边不一致。
 *
 * 边录边跟随仍然成立：CameraX 的 VideoCapture 与我们自己的 sherpa AudioRecord
 * 属于同一 UID，Android 10+ 允许并发采集麦克风。
 *
 * 生命周期：Service 不是 LifecycleOwner，这里自建一个 LifecycleRegistry，
 * 取景期间置 STARTED，退出预览/销毁时回退，保证 CameraX 正确解绑相机。
 */
class OverlayRecorder(
    private val context: Context,
    private val onError: (String) -> Unit
) : LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentSurfaceProvider: Preview.SurfaceProvider? = null
    private var startedAt = 0L

    /** 当前镜头朝向：默认前置（自拍口播场景）。 */
    @Volatile
    var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
        private set

    /** 是否为前置镜头（决定取景是否水平镜像）。 */
    val isFrontFacing: Boolean get() = lensFacing == CameraSelector.LENS_FACING_FRONT
    private var pendingStart = false
    private var startCallback: (() -> Unit)? = null
    private var stopCallback: ((Uri?) -> Unit)? = null
    private var destroyed = false

    @Volatile
    var isRecording = false
        private set

    fun elapsedSeconds(): Int =
        if (!isRecording) 0 else ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    /**
     * 开启全屏取景：绑定前置摄像头到 [surfaceProvider]（PreviewView 提供）。
     * 若此前请求了开录（[start] 先于 surface 就绪），绑定完成后自动开始。
     */
    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        if (destroyed) return
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (destroyed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                bindUseCases(provider, surfaceProvider)
            } catch (e: Throwable) {
                onError("相机初始化失败：${e.message}")
            }
        }, mainExecutor)
    }

    /** 退出取景：解绑相机（CameraX 会同步释放，天然避免「相机被占用」错误码 3）。 */
    fun detachPreview() {
        recording?.let { stopInternal() }
        try {
            cameraProvider?.unbindAll()
        } catch (e: Throwable) {
            Log.w(TAG, "unbindAll 失败", e)
        }
        videoCapture = null
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    /**
     * 开始录像。若相机尚未就绪（pendingSurface），会先记住请求，
     * 等取景绑定完成后自动开录。
     */
    fun start(onReady: () -> Unit) {
        if (isRecording) {
            onReady()
            return
        }
        val capture = videoCapture
        if (capture == null) {
            pendingStart = true
            startCallback = onReady
            return
        }
        beginRecording(capture, onReady)
    }

    /** 停止录像并保存（回调已保存到相册的 Uri）。 */
    fun stop(onSaved: (Uri?) -> Unit) {
        if (!isRecording) {
            onSaved(null)
            return
        }
        stopCallback = onSaved
        stopInternal()
    }

    /**
     * 翻转镜头：前置 ↔ 后置。CameraX 通过换 CameraSelector 重新 bind 实现。
     * 录像中翻转会先落盘当前片段再切换，避免文件损坏。
     */
    fun flipCamera(onDone: (Boolean) -> Unit) {
        val provider = cameraProvider
        val surfaceProvider = currentSurfaceProvider
        if (provider == null || surfaceProvider == null) {
            onDone(false)
            return
        }
        val wasRecording = isRecording
        if (wasRecording) {
            // 先停止并等 Finalize 落盘，再换镜头
            stopCallback = { _ ->
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                bindUseCases(provider, surfaceProvider)
                onDone(true)
            }
            stopInternal()
            return
        }
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        bindUseCases(provider, surfaceProvider)
        onDone(true)
    }

    /** 服务销毁：立即释放全部资源（不保证成片）。 */
    fun releaseNow() {
        destroyed = true
        pendingStart = false
        runCatching { recording?.stop() }
        recording = null
        isRecording = false
        runCatching { cameraProvider?.unbindAll() }
        videoCapture = null
        cameraProvider = null
        currentSurfaceProvider = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    // ---------- 内部实现 ----------

    private fun bindUseCases(provider: ProcessCameraProvider, surfaceProvider: Preview.SurfaceProvider) {
        currentSurfaceProvider = surfaceProvider
        val selector = when {
            lensFacing == CameraSelector.LENS_FACING_FRONT &&
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            lensFacing == CameraSelector.LENS_FACING_BACK &&
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            // 目标镜头不存在（部分机器只有单摄）→ 退回另一个可用的
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> {
                lensFacing = CameraSelector.LENS_FACING_FRONT
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> {
                lensFacing = CameraSelector.LENS_FACING_BACK
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            else -> {
                onError("没有找到可用摄像头")
                return
            }
        }
        // 优先 1080p，失败逐级降到 720p/480p
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
        )
        val previewBuilder = Preview.Builder()
        previewBuilder.setTargetRotation(previewRotation())
        val preview = previewBuilder.build()
        preview.setSurfaceProvider(surfaceProvider)
        val recorderBuilder = Recorder.Builder()
        recorderBuilder.setQualitySelector(qualitySelector)
        // 前置录像默认会被镜像（1.4.2 实测成片是镜像的），这里显式关闭，
        // 让录出的成片与取景预览（真实方向）完全一致，做到所见即所得。
        val capture = VideoCapture.Builder<Recorder>(recorderBuilder.build())
            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
            .build()
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, capture)
            videoCapture = capture
            if (pendingStart) {
                pendingStart = false
                val cb = startCallback
                startCallback = null
                beginRecording(capture, cb ?: {})
            }
        } catch (e: Throwable) {
            onError("相机启动失败：${e.message}")
        }
    }

    private fun beginRecording(capture: VideoCapture<Recorder>, onReady: () -> Unit) {
        val name = "ticiqi_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + File.separator + "ticiqi"
                )
            }
        }
        // MediaStoreOutputOptions：CameraX 自动处理 IS_PENDING（成功可见/失败清理）
        // rotation 交给 CameraX 依据 Preview 的 targetRotation / 传感器朝向自行处理
        val options = MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()
        var pending = capture.output.prepareRecording(context, options)
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            pending = pending.withAudioEnabled()
        }
        startCallback = onReady
        try {
            recording = pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        startedAt = System.currentTimeMillis()
                        startCallback?.invoke()
                        startCallback = null
                    }

                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        recording = null
                        val uri = event.outputResults.outputUri
                        if (event.hasError()) {
                            onError("录像失败（错误码 ${event.error}）")
                            stopCallback?.invoke(null)
                        } else {
                            stopCallback?.invoke(uri)
                        }
                        stopCallback = null
                    }
                }
            }
        } catch (e: Throwable) {
            onError("录像启动失败：${e.message}")
            stopCallback = null
        }
    }

    private fun stopInternal() {
        val active = recording
        if (active == null) {
            isRecording = false
            return
        }
        runCatching { active.stop() }
    }

    @Suppress("DEPRECATION")
    private fun previewRotation(): Int =
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation

    private companion object {
        const val TAG = "OverlayRecorder"
    }
}
