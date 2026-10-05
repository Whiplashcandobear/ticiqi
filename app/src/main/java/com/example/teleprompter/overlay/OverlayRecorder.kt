package com.example.teleprompter.overlay

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 悬浮提词内置录像 + 实时预览：前置摄像头（Camera2）+ 麦克风（MediaRecorder）。
 *
 * 为什么能和语音跟随同时工作：Android 10+ 的麦克风采集独占策略只限制「不同 UID」的
 * 应用争抢；同一个 APP 内 MediaRecorder 与 AudioRecord 同 UID，允许并发采集，
 * 因此边录边跟随不会被系统静音（这与打开系统相机是两回事）。
 *
 * 预览：通过 [attachPreview] 传入预览 Surface，与录制 Surface 同一会话输出，
 * 录制时也能实时看到取景；[detachPreview] 退出预览。
 *
 * 录像保存到公共相册 Movies/ticiqi/（MediaStore，无需存储权限）。
 */
class OverlayRecorder(
    private val context: Context,
    private val onError: (String) -> Unit
) {

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var recSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var startedAt = 0L
    private var pendingStart = false
    private var startCallback: (() -> Unit)? = null
    private var opening = false // 防止相机在打开过程中被二次 openCamera（报错代码 3/2）

    @Volatile
    var isRecording = false
        private set

    fun elapsedSeconds(): Int =
        if (!isRecording) 0 else ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    /** 附加预览 Surface（主线程调用）：没有相机则打开相机，有则重建会话。 */
    fun attachPreview(surface: Surface) {
        previewSurface = surface
        if (camera == null) openCamera() else rebuildSession()
    }

    /** 移除预览：不在录像时直接释放相机；录像中则退回仅录制的会话。 */
    fun detachPreview() {
        previewSurface = null
        if (!isRecording) {
            releaseCamera()
        } else {
            rebuildSession()
        }
    }

    /** 启动录像（主线程调用）：成功后回调 [onReady]。 */
    fun start(onReady: () -> Unit) {
        if (isRecording) {
            onReady()
            return
        }
        try {
            if (recorder == null && !prepareRecorder()) return
            startCallback = onReady
            pendingStart = true
            if (camera == null) openCamera() else rebuildSession()
        } catch (e: Throwable) {
            pendingStart = false
            startCallback = null
            onError("录像启动失败：${e.message}")
            releaseRecorderOnly()
        }
    }

    /** 停止并保存（主线程调用；回调已保存的相册 Uri）。 */
    fun stop(onSaved: (Uri?) -> Unit) {
        if (!isRecording) {
            onSaved(null)
            return
        }
        isRecording = false
        var savedUri: Uri? = null
        try {
            recorder?.stop()
            // 解除 IS_PENDING，视频才会出现在相册里
            outputUri?.let {
                val values = ContentValues().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                }
                runCatching { context.contentResolver.update(it, values, null, null) }
            }
            savedUri = outputUri
        } catch (e: Throwable) {
            onError("录像保存失败：${e.message}")
            // 文件不完整，删除占位记录
            runCatching { outputUri?.let { context.contentResolver.delete(it, null, null) } }
        }
        val uri = savedUri
        releaseRecorderOnly()
        if (previewSurface != null) rebuildSession() else releaseCamera()
        onSaved(uri)
    }

    /** 立即释放全部资源（不保证成片），用于服务销毁等场景。 */
    fun releaseNow() {
        pendingStart = false
        isRecording = false
        runCatching { recorder?.stop() }
        releaseRecorderOnly()
        releaseCamera()
        previewSurface = null
    }

    // ---------- 内部实现 ----------

    private fun prepareRecorder(): Boolean {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        rec.setAudioEncodingBitRate(128_000)
        rec.setAudioSamplingRate(44_100)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoFrameRate(30)
        rec.setVideoEncodingBitRate(8_000_000)

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = pickFrontCamera(cm)
        if (cameraId == null) {
            onError("没有找到可用摄像头")
            return false
        }
        val characteristics = cm.getCameraCharacteristics(cameraId)
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaRecorder::class.java)
            ?.toList()
            .orEmpty()
        val size = chooseVideoSize(sizes)
        rec.setVideoSize(size.width, size.height)
        // 竖屏持机：以前置摄像头的传感器方向作为播放方向提示
        val sensorOrientation = characteristics
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        rec.setOrientationHint(sensorOrientation)

        val uri = createOutput()
        if (uri == null) {
            onError("创建录像文件失败")
            return false
        }
        outputUri = uri
        rec.setOutputFile(pfd!!.fileDescriptor)
        rec.prepare()
        recorder = rec
        recSurface = rec.surface
        return true
    }

    private fun openCamera() {
        if (camera != null || opening) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = pickFrontCamera(cm)
        if (cameraId == null) {
            onError("没有找到可用摄像头")
            return
        }
        opening = true
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false
                    camera = device
                    rebuildSession()
                }

                override fun onDisconnected(device: CameraDevice) {
                    opening = false
                    device.close()
                    if (camera === device) camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    opening = false
                    onError("打开相机失败（代码 $error）")
                    releaseCamera()
                }
            }, null)
        } catch (e: SecurityException) {
            opening = false
            onError("没有相机权限")
        } catch (e: Throwable) {
            opening = false
            onError("打开相机失败：${e.message}")
        }
    }

    /** 按当前 recorder/preview Surface 组合重建会话；pendingStart 时在配置成功后启动录像。 */
    private fun rebuildSession() {
        val device = camera ?: return
        val targets = mutableListOf<Surface>()
        recSurface?.let { targets.add(it) }
        previewSurface?.let { targets.add(it) }
        if (targets.isEmpty()) {
            releaseCamera()
            return
        }
        runCatching { session?.close() }
        session = null
        val template =
            if (isRecording || pendingStart) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        try {
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (camera == null) {
                        s.close()
                        return
                    }
                    session = s
                    try {
                        val request = device.createCaptureRequest(template).apply {
                            targets.forEach { addTarget(it) }
                            set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                        }.build()
                        s.setRepeatingRequest(request, null, null)
                        if (pendingStart) {
                            pendingStart = false
                            recorder?.start()
                            isRecording = true
                            startedAt = System.currentTimeMillis()
                            startCallback?.invoke()
                            startCallback = null
                        }
                    } catch (e: Throwable) {
                        onError("启动取景/录像失败：${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    pendingStart = false
                    onError("相机会话配置失败")
                }
            }, null)
        } catch (e: Throwable) {
            pendingStart = false
            onError("配置相机会话失败：${e.message}")
        }
    }

    private fun releaseRecorderOnly() {
        runCatching { recorder?.release() }
        recorder = null
        recSurface = null
        runCatching { pfd?.close() }
        pfd = null
    }

    private fun releaseCamera() {
        opening = false
        runCatching { session?.close() }
        runCatching { camera?.close() }
        session = null
        camera = null
    }

    private fun pickFrontCamera(cm: CameraManager): String? {
        var fallback: String? = null
        for (id in cm.cameraIdList) {
            val facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) return id
            if (fallback == null && facing == CameraCharacteristics.LENS_FACING_BACK) fallback = id
        }
        return fallback
    }

    /** 选一个 ≤1080p、最接近 16:9 的录像尺寸，避免不受支持的分辨率。 */
    private fun chooseVideoSize(sizes: List<Size>): Size {
        if (sizes.isEmpty()) return Size(1280, 720)
        val candidates = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        val pool = candidates.ifEmpty { sizes.toList() }
        return pool.minByOrNull {
            abs(it.width.toFloat() / it.height - 16f / 9f) * 10000 + it.width
        } ?: pool.first()
    }

    private fun createOutput(): Uri? = try {
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
            ?: return null
        pfd = context.contentResolver.openFileDescriptor(uri, "rw")
        uri
    } catch (e: Throwable) {
        null
    }
}
