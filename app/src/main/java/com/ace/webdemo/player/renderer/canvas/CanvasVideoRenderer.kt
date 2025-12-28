package com.ace.webdemo.player.renderer.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import com.ace.webdemo.player.audio.AudioFocusChangeListener
import com.ace.webdemo.player.audio.AudioFocusManager
import com.ace.webdemo.player.audio.DolbyAudioProcessor
import com.ace.webdemo.player.config.PlayerConfig
import com.ace.webdemo.player.config.PlayerState
import com.ace.webdemo.player.renderer.VideoRenderer
import com.ace.webdemo.player.renderer.VideoRendererEventListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import android.util.Log

/**
 * Canvas视频渲染器
 * 使用ExoPlayer解码视频，将帧数据传输到H5 Canvas渲染
 *
 * 特点：
 * 1. 视频在H5 Canvas中渲染，滚动零延迟
 * 2. 原生控制解码和音频焦点
 * 3. 支持杜比音效
 * 4. 性能优化：根据滚动速度动态调整帧率
 */
class CanvasVideoRenderer(
    private val context: Context,
    private val config: PlayerConfig,
    private val containerProvider: (() -> android.view.ViewGroup?)? = null
) : VideoRenderer {

    private var exoPlayer: ExoPlayer? = null
    private var audioFocusManager: AudioFocusManager? = null
    private var dolbyProcessor: DolbyAudioProcessor? = null

    private var eventListener: VideoRendererEventListener? = null
    private var currentState = PlayerState.IDLE

    private val mainHandler = Handler(Looper.getMainLooper())

    // 使用TextureView进行离屏渲染
    private var textureView: TextureView? = null
    private var isCapturing = AtomicBoolean(false)
    private val captureHandler = Handler(Looper.getMainLooper())

    private var currentPosition = AtomicLong(0)
    private var duration = AtomicLong(0)

    private var videoWidth = 0
    private var videoHeight = 0

    // 帧率控制
    private val frameInterval = 1000L / config.maxFrameRate
    private var lastFrameTime = 0L

    // 可见性控制（优化：不可见时不捕获帧）
    // 默认为true，只有明确判断为不可见时才设为false
    @Volatile
    private var isVisible = true

    // 颜色格式：true=RGB888, false=RGBA（动态可切换）
    @Volatile
    var useRGB888 = true

    // 主动帧捕获定时器（解决华为设备onSurfaceTextureUpdated不触发的问题）
    private var frameCaptureRunnable: Runnable? = null

    init {
        initializePlayer()
        initializeAudioComponents()
        initializeTextureView()
    }

    /**
     * 初始化TextureView用于离屏渲染
     */
    private fun initializeTextureView() {
        mainHandler.post {
            textureView = TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        Log.d("CanvasVideoRenderer", "Surface available: ${width}x${height}")
                        // Surface可用时，将ExoPlayer绑定到这个Surface
                        try {
                            val videoSurface = Surface(surface)
                            exoPlayer?.setVideoSurface(videoSurface)
                            Log.d("CanvasVideoRenderer", "ExoPlayer surface set successfully")
                        } catch (e: Exception) {
                            Log.e("CanvasVideoRenderer", "Failed to set video surface", e)
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        Log.d("CanvasVideoRenderer", "Surface size changed: ${width}x${height}")
                        // Surface尺寸变化时，重新绑定确保渲染正常
                        try {
                            val videoSurface = Surface(surface)
                            exoPlayer?.setVideoSurface(videoSurface)
                            Log.d("CanvasVideoRenderer", "ExoPlayer surface re-bound after size change")
                        } catch (e: Exception) {
                            Log.e("CanvasVideoRenderer", "Failed to re-bind surface", e)
                        }
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        Log.d("CanvasVideoRenderer", "Surface destroyed")
                        try {
                            exoPlayer?.clearVideoSurface()
                        } catch (e: Exception) {
                            Log.e("CanvasVideoRenderer", "Error clearing surface", e)
                        }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        // 华为设备上这个回调可能不触发，所以我们使用主动轮询
                        // 这里保留回调作为辅助，如果触发了就更新一下
                        // 不依赖这个回调，主要靠startFrameCapture()的定时器
                    }
                }
            }

            // 将TextureView添加到容器中（必须attach到window才能工作）
            try {
                val container = containerProvider?.invoke()
                if (container != null) {
                    // 使用较大的初始尺寸，避免后续调整导致Surface重建
                    // 大部分视频的分辨率都不会超过1080p，使用1920x1080作为初始尺寸
                    // 这样可以避免绝大多数情况下的尺寸调整
                    val initialWidth = 1920
                    val initialHeight = 1080
                    container.addView(textureView, initialWidth, initialHeight)

                    // 然后设置位置和透明度，将其隐藏
                    textureView?.apply {
                        // 移到屏幕外（不能用INVISIBLE，会导致Surface不创建）
                        translationX = -10000f
                        translationY = -10000f
                        // 完全透明
                        alpha = 0f
                        // 保持VISIBLE状态，否则Surface不会被创建
                    }

                    Log.d("CanvasVideoRenderer", "TextureView added to container, waiting for Surface...")
                } else {
                    Log.w("CanvasVideoRenderer", "No container available, TextureView may not work")
                }
            } catch (e: Exception) {
                Log.e("CanvasVideoRenderer", "Failed to add TextureView to container", e)
            }
        }
    }

    /**
     * 初始化播放器
     */
    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(context)
            .build()
            .apply {
                // 设置播放器监听器
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        handlePlaybackStateChanged(playbackState)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handleError(error)
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        handleVideoSizeChanged(videoSize)
                    }
                })

                // 设置音量
                volume = if (config.muted) 0f else config.volume

                // 设置循环播放
                repeatMode = if (config.loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

                // 如果TextureView已经ready，绑定Surface
                textureView?.surfaceTexture?.let { texture ->
                    setVideoSurface(Surface(texture))
                }
            }

        // 启动进度更新
        startProgressUpdater()
    }

    /**
     * 初始化音频组件
     */
    private fun initializeAudioComponents() {
        // 音频焦点管理
        audioFocusManager = AudioFocusManager(context).apply {
            addFocusChangeListener(object : AudioFocusChangeListener {
                override fun onAudioFocusGain() {
                    // 重新获得焦点，恢复播放
                    exoPlayer?.playWhenReady = true
                }

                override fun onAudioFocusLoss(permanent: Boolean) {
                    if (permanent) {
                        // 永久失去焦点，停止播放
                        pause()
                    } else {
                        // 暂时失去焦点，暂停播放
                        pause()
                    }
                }

                override fun onAudioFocusDuck() {
                    // 降低音量
                    exoPlayer?.volume = (config.volume * 0.3f)
                }
            })
        }

        // 杜比音效处理
        if (config.enableDolby) {
            dolbyProcessor = DolbyAudioProcessor(context).apply {
                // 获取ExoPlayer的音频会话ID
                exoPlayer?.audioSessionId?.let { sessionId ->
                    initialize(sessionId)

                    // 应用车机优化配置
                    val dolbyConfig = DolbyAudioProcessor.createCarOptimizedConfig()
                    applyConfig(dolbyConfig)
                }
            }
        }
    }

    override fun prepare(url: String) {
        currentState = PlayerState.PREPARING
        eventListener?.onStateChanged(currentState)

        mainHandler.post {
            val mediaItem = MediaItem.fromUri(url)
            exoPlayer?.apply {
                setMediaItem(mediaItem)
                prepare()
            }
        }
    }

    override fun play() {
        // 请求音频焦点
        val focusGranted = audioFocusManager?.requestAudioFocus(config.audioFocusType) ?: true

        if (focusGranted) {
            mainHandler.post {
                exoPlayer?.playWhenReady = true
                currentState = PlayerState.PLAYING
                eventListener?.onStateChanged(currentState)
                isCapturing.set(true)

                // 启动主动帧捕获定时器（解决华为设备回调不触发问题）
                startFrameCapture()
            }
        }
    }

    /**
     * 启动主动帧捕获（不依赖onSurfaceTextureUpdated回调）
     * 这解决了华为设备上TextureView回调不触发的问题
     */
    private fun startFrameCapture() {
        stopFrameCapture() // 先停止旧的

        frameCaptureRunnable = object : Runnable {
            override fun run() {
                if (isCapturing.get() && currentState == PlayerState.PLAYING) {
                    captureAndSendFrame()
                    // 根据帧率间隔调度下一次捕获
                    mainHandler.postDelayed(this, frameInterval)
                }
            }
        }

        mainHandler.post(frameCaptureRunnable!!)
        Log.d("CanvasVideoRenderer", "Active frame capture started at ${config.maxFrameRate}fps")
    }

    /**
     * 停止主动帧捕获
     */
    private fun stopFrameCapture() {
        frameCaptureRunnable?.let {
            mainHandler.removeCallbacks(it)
            frameCaptureRunnable = null
            Log.d("CanvasVideoRenderer", "Active frame capture stopped")
        }
    }

    /**
     * 捕获并发送当前帧到H5
     */
    private fun captureAndSendFrame() {
        // 如果不在播放状态或未启用捕获或不可见，跳过
        if (!isCapturing.get() || currentState != PlayerState.PLAYING || !isVisible) {
            return
        }

        // 帧率控制（主动轮询时已经由定时器控制，这里做双保险）
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < frameInterval) {
            return
        }
        lastFrameTime = currentTime

        textureView?.let { view ->
            try {
                // 检查TextureView是否可用
                if (!view.isAvailable) {
                    Log.w("CanvasVideoRenderer", "TextureView not available for capture")
                    return
                }

                // 从TextureView获取Bitmap
                val originalBitmap = view.bitmap
                if (originalBitmap == null) {
                    Log.w("CanvasVideoRenderer", "Failed to get bitmap from TextureView")
                    return
                }

                if (originalBitmap.width <= 1 || originalBitmap.height <= 1) {
                    Log.w("CanvasVideoRenderer", "Invalid bitmap size: ${originalBitmap.width}x${originalBitmap.height}")
                    return
                }

                // 只在第一帧和尺寸变化时输出日志
                // Log.d("CanvasVideoRenderer", "Capturing frame: ${originalBitmap.width}x${originalBitmap.height}")

                // 性能优化：使用320px
                val maxDimension = 320
                val scaledBitmap = if (originalBitmap.width > maxDimension || originalBitmap.height > maxDimension) {
                    val scale = minOf(
                        maxDimension.toFloat() / originalBitmap.width,
                        maxDimension.toFloat() / originalBitmap.height
                    )
                    val newWidth = (originalBitmap.width * scale).toInt()
                    val newHeight = (originalBitmap.height * scale).toInt()

                    // 使用FILTER_BITMAP=false加速缩放
                    android.graphics.Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, false).also {
                        if (it !== originalBitmap) {
                            originalBitmap.recycle()
                        }
                    }
                } else {
                    originalBitmap
                }

                // 根据配置选择颜色格式
                val frameData = if (useRGB888) {
                    bitmapToRGB888(scaledBitmap)
                } else {
                    bitmapToRGBA(scaledBitmap)
                }

                // 发送到H5
                eventListener?.onFrameRendered(
                    frameData,
                    scaledBitmap.width,
                    scaledBitmap.height,
                    currentTime
                )

                // 回收Bitmap
                scaledBitmap.recycle()

            } catch (e: IllegalStateException) {
                // TextureView可能在不合适的状态
                Log.e("CanvasVideoRenderer", "IllegalStateException while capturing frame", e)
            } catch (e: OutOfMemoryError) {
                // 内存不足
                Log.e("CanvasVideoRenderer", "OutOfMemoryError while capturing frame", e)
                System.gc() // 触发GC尝试释放内存
            } catch (e: Exception) {
                Log.e("CanvasVideoRenderer", "Error capturing frame", e)
            }
        } ?: run {
            Log.w("CanvasVideoRenderer", "TextureView is null, cannot capture frame")
        }
    }

    /**
     * 将Bitmap转换为RGB888字节数组（无Alpha通道）
     * 相比RGBA减少25%数据量
     */
    private fun bitmapToRGB888(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)

        // 获取ARGB像素数据
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 转换为RGB888格式（只取RGB，忽略Alpha）
        val rgbData = ByteArray(width * height * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val baseIndex = i * 3

            // ARGB -> RGB
            rgbData[baseIndex] = ((pixel shr 16) and 0xFF).toByte()     // R
            rgbData[baseIndex + 1] = ((pixel shr 8) and 0xFF).toByte()  // G
            rgbData[baseIndex + 2] = (pixel and 0xFF).toByte()          // B
        }

        return rgbData
    }

    /**
     * 将Bitmap转换为RGBA字节数组
     * Canvas的ImageData使用RGBA格式
     */
    private fun bitmapToRGBA(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)

        // 获取ARGB像素数据
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 转换为RGBA格式
        val rgbaData = ByteArray(width * height * 4)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val baseIndex = i * 4

            // ARGB -> RGBA
            rgbaData[baseIndex] = ((pixel shr 16) and 0xFF).toByte()     // R
            rgbaData[baseIndex + 1] = ((pixel shr 8) and 0xFF).toByte()  // G
            rgbaData[baseIndex + 2] = (pixel and 0xFF).toByte()          // B
            rgbaData[baseIndex + 3] = ((pixel shr 24) and 0xFF).toByte() // A
        }

        return rgbaData
    }

    override fun pause() {
        mainHandler.post {
            exoPlayer?.playWhenReady = false
            currentState = PlayerState.PAUSED
            eventListener?.onStateChanged(currentState)
            isCapturing.set(false)
            stopFrameCapture()
        }
    }

    override fun seek(position: Long) {
        mainHandler.post {
            exoPlayer?.seekTo(position)
        }
    }

    override fun stop() {
        mainHandler.post {
            exoPlayer?.stop()
            currentState = PlayerState.IDLE
            eventListener?.onStateChanged(currentState)
        }
    }

    override fun release() {
        mainHandler.post {
            isCapturing.set(false)
            stopFrameCapture()
            stopProgressUpdater()

            exoPlayer?.clearVideoSurface()
            exoPlayer?.release()
            exoPlayer = null

            audioFocusManager?.release()
            audioFocusManager = null

            dolbyProcessor?.release()
            dolbyProcessor = null

            // 移除TextureView
            textureView?.let { view ->
                (view.parent as? android.view.ViewGroup)?.removeView(view)
            }
            textureView = null

            currentState = PlayerState.RELEASED
        }
    }

    override fun setVolume(volume: Float) {
        mainHandler.post {
            exoPlayer?.volume = volume
        }
    }

    override fun setMuted(muted: Boolean) {
        mainHandler.post {
            exoPlayer?.volume = if (muted) 0f else config.volume
        }
    }

    override fun updateLayout(x: Int, y: Int, width: Int, height: Int) {
        // Canvas模式下布局由H5控制，这里不做处理
        // 可见性判断暂时禁用，保持默认isVisible=true
        // 后续可以通过JSBridge传递可见性信息
    }

    override fun setPlaybackSpeed(speed: Float) {
        mainHandler.post {
            exoPlayer?.setPlaybackSpeed(speed)
        }
    }

    override fun getCurrentPosition(): Long {
        return currentPosition.get()
    }

    override fun getDuration(): Long {
        return duration.get()
    }

    override fun getState(): PlayerState {
        return currentState
    }

    override fun setEventListener(listener: VideoRendererEventListener) {
        this.eventListener = listener
    }

    override fun setLoop(loop: Boolean) {
        mainHandler.post {
            exoPlayer?.repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        }
    }

    override fun getVideoWidth(): Int = videoWidth

    override fun getVideoHeight(): Int = videoHeight

    /**
     * 处理播放状态变化
     */
    private fun handlePlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_IDLE -> {
                currentState = PlayerState.IDLE
                eventListener?.onStateChanged(currentState)
            }

            Player.STATE_BUFFERING -> {
                currentState = PlayerState.BUFFERING
                eventListener?.onStateChanged(currentState)
            }

            Player.STATE_READY -> {
                if (currentState == PlayerState.PREPARING) {
                    currentState = PlayerState.PREPARED
                    eventListener?.onPrepared()
                    eventListener?.onStateChanged(currentState)

                    // 更新时长
                    exoPlayer?.duration?.let { dur ->
                        if (dur > 0) {
                            duration.set(dur)
                        }
                    }

                    // 如果配置了自动播放，开始播放
                    if (config.autoPlay) {
                        play()
                    }
                }
            }

            Player.STATE_ENDED -> {
                currentState = PlayerState.COMPLETED
                eventListener?.onCompletion()
                eventListener?.onStateChanged(currentState)
            }
        }
    }

    /**
     * 处理错误
     */
    private fun handleError(error: PlaybackException) {
        currentState = PlayerState.ERROR
        eventListener?.onError(error.errorCode, error.message ?: "Unknown error")
        eventListener?.onStateChanged(currentState)
    }

    /**
     * 处理视频尺寸变化
     */
    private fun handleVideoSizeChanged(videoSize: VideoSize) {
        val newWidth = videoSize.width
        val newHeight = videoSize.height

        Log.d("CanvasVideoRenderer", "Video size changed: ${newWidth}x${newHeight}")

        // 只有当尺寸真正变化且有效时才更新
        if (newWidth <= 0 || newHeight <= 0) {
            Log.w("CanvasVideoRenderer", "Invalid video size: ${newWidth}x${newHeight}, skipping update")
            return
        }

        // 检查尺寸是否真的变化了，避免不必要的更新
        if (newWidth == videoWidth && newHeight == videoHeight) {
            Log.d("CanvasVideoRenderer", "Video size unchanged, skipping update")
            return
        }

        videoWidth = newWidth
        videoHeight = newHeight

        // 关键修改：不要改变TextureView的尺寸！
        // 改变TextureView尺寸会导致Surface重建，在华为设备上会导致帧回调停止
        // TextureView保持初始尺寸，ExoPlayer会自动缩放视频内容
        Log.d("CanvasVideoRenderer", "Video size updated to ${videoWidth}x${videoHeight}, TextureView size unchanged")

        eventListener?.onVideoSizeChanged(videoWidth, videoHeight)
    }

    /**
     * 启动进度更新器
     */
    private fun startProgressUpdater() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = object : Runnable {
            override fun run() {
                exoPlayer?.let { player ->
                    currentPosition.set(player.currentPosition)

                    if (player.duration > 0) {
                        duration.set(player.duration)
                    }

                    eventListener?.onProgressChanged(
                        player.currentPosition,
                        player.duration
                    )

                    // 每100ms更新一次进度
                    mainHandler.postDelayed(this, 100)
                }
            }
        }
        progressRunnable?.let { mainHandler.post(it) }
    }

    /**
     * 停止进度更新器
     */
    private fun stopProgressUpdater() {
        progressRunnable?.let { mainHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    private var progressRunnable: Runnable? = null
}
