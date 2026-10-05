package com.example.teleprompter.overlay

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
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
 * PreviewTransform 会自动按 targetRotation / 窗口尺寸 / 传感器朝向算出正确变换，
 * 并在前置摄像头时自动镜像 —— 这些是绝大多数相机 App 的标准做法。
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
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var startedAt = 0L
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
        runCatching { pfd?.close() }
        pfd = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    // ---------- 内部实现 ----------

    private fun bindUseCases(provider: ProcessCameraProvider, surfaceProvider: Preview.SurfaceProvider) {
        val selector = when {
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
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
        val preview = Preview.Builder()
            .setTargetRotation(previewRotation())
            .build()
            .also { it.setSurfaceProvider(surfaceProvider) }
        val capture = VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setTargetRotation(previewRotation())
                .build()
        )
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
        val target = createOutput()
        if (target == null) {
            onError("创建录像文件失败")
            return
        }
        val (uri, fd) = target
        outputUri = uri
        pfd = fd
        val options = FileOutputOptions.Builder(fd.fileDescriptor).build()
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
                        runCatching { pfd?.close() }
                        pfd = null
                        val code = event.error
                        if (code == VideoRecordEvent.Finalize.ERROR_NONE) {
                            releasePending(uri)
                            stopCallback?.invoke(uri)
                        } else {
                            // 成片不完整：删掉占位记录，相册不留坏文件
                            runCatching { context.contentResolver.delete(uri, null, null) }
                            onError("录像失败（错误码 $code）")
                            stopCallback?.invoke(null)
                        }
                        stopCallback = null
                    }
                }
            }
        } catch (e: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            runCatching { pfd?.close() }
            pfd = null
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

    /** 解除 IS_PENDING，录像才会出现在相册里。 */
    private fun releasePending(uri: Uri) {
        runCatching {
            val values = ContentValues().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
            }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    private fun createOutput(): Pair<Uri, ParcelFileDescriptor>? = try {
        val name = "ticiqi_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + File.separator + "ticiqi")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            null
        } else {
            val fd = context.contentResolver.openFileDescriptor(uri, "rw")
            if (fd == null) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                null
            } else {
                uri to fd
            }
        }
    } catch (e: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun previewRotation(): Int =
        (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation

    private companion object {
        const val TAG = "OverlayRecorder"
    }
}
