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
                        exoPlayer?.setVideoSurface(Surface(surface))
                        Log.d("CanvasVideoRenderer", "ExoPlayer surface set")
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        Log.d("CanvasVideoRenderer", "Surface size changed: ${width}x${height}")
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        Log.d("CanvasVideoRenderer", "Surface destroyed")
                        exoPlayer?.clearVideoSurface()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        // 每当有新帧渲染时，捕获并发送到H5
                        Log.v("CanvasVideoRenderer", "onSurfaceTextureUpdated called")
                        captureAndSendFrame()
                    }
                }
            }

            // 将TextureView添加到容器中（必须attach到window才能工作）
            try {
                val container = containerProvider?.invoke()
                if (container != null) {
                    // 先添加到容器，让容器生成合适的LayoutParams
                    container.addView(textureView, 1, 1)

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
            }
        }
    }

    /**
     * 捕获并发送当前帧到H5
     */
    private fun captureAndSendFrame() {
        // 帧率控制
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < frameInterval) {
            return
        }

        // 如果不在播放状态或未启用捕获或不可见，跳过
        if (!isCapturing.get() || currentState != PlayerState.PLAYING || !isVisible) {
            Log.d("CanvasVideoRenderer", "Frame capture skipped - isCapturing=${isCapturing.get()}, state=$currentState, isVisible=$isVisible")
            return
        }

        lastFrameTime = currentTime

        textureView?.let { view ->
            try {
                // 从TextureView获取Bitmap
                val originalBitmap = view.bitmap
                Log.d("CanvasVideoRenderer", "Captured bitmap: ${originalBitmap?.width}x${originalBitmap?.height}")

                if (originalBitmap == null || originalBitmap.width <= 1 || originalBitmap.height <= 1) {
                    Log.w("CanvasVideoRenderer", "Invalid bitmap: ${originalBitmap?.width}x${originalBitmap?.height}")
                    return
                }

                // 性能优化：大幅降低分辨率，避免传输过大数据
                // 对于列表流，使用更小的分辨率以提升流畅度
                val maxDimension = 320 // 最大宽度或高度（从640降到320）
                val scaledBitmap = if (originalBitmap.width > maxDimension || originalBitmap.height > maxDimension) {
                    val scale = minOf(
                        maxDimension.toFloat() / originalBitmap.width,
                        maxDimension.toFloat() / originalBitmap.height
                    )
                    val newWidth = (originalBitmap.width * scale).toInt()
                    val newHeight = (originalBitmap.height * scale).toInt()

                    Log.d("CanvasVideoRenderer", "Scaling bitmap from ${originalBitmap.width}x${originalBitmap.height} to ${newWidth}x${newHeight}")

                    // 使用FILTER_BITMAP=false加速缩放
                    android.graphics.Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, false).also {
                        if (it !== originalBitmap) {
                            originalBitmap.recycle()
                        }
                    }
                } else {
                    originalBitmap
                }

                // 转换为RGBA数据
                val rgbData = bitmapToRGBA(scaledBitmap)
                Log.d("CanvasVideoRenderer", "Converted to RGBA: ${rgbData.size} bytes")

                // 发送到H5
                eventListener?.onFrameRendered(
                    rgbData,
                    scaledBitmap.width,
                    scaledBitmap.height,
                    currentTime
                )
                Log.d("CanvasVideoRenderer", "Frame sent to H5: ${scaledBitmap.width}x${scaledBitmap.height}")

                // 回收Bitmap
                scaledBitmap.recycle()

            } catch (e: Exception) {
                Log.e("CanvasVideoRenderer", "Error capturing frame", e)
            }
        } ?: Log.w("CanvasVideoRenderer", "TextureView is null, cannot capture frame")
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
        videoWidth = videoSize.width
        videoHeight = videoSize.height

        Log.d("CanvasVideoRenderer", "Video size changed: ${videoWidth}x${videoHeight}")

        // 更新TextureView尺寸以匹配视频尺寸
        mainHandler.post {
            textureView?.let { view ->
                val params = view.layoutParams
                params?.width = videoWidth
                params?.height = videoHeight
                view.layoutParams = params
                Log.d("CanvasVideoRenderer", "TextureView size updated to ${videoWidth}x${videoHeight}")
            }
        }

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
