package com.ace.webdemo.player.renderer.layer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
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
import java.util.concurrent.atomic.AtomicLong

/**
 * 同层视频渲染器
 * 使用SurfaceView覆盖在WebView上，实时同步位置
 *
 * 特点：
 * 1. SurfaceView硬件加速，画质好
 * 2. 使用Choreographer同步刷新，减少延迟
 * 3. 适合详情页、全屏播放场景
 * 4. 支持音频焦点和杜比音效
 */
class LayerVideoRenderer(
    private val context: Context,
    private val webView: WebView,
    private val config: PlayerConfig
) : VideoRenderer {

    private var exoPlayer: ExoPlayer? = null
    private var audioFocusManager: AudioFocusManager? = null
    private var dolbyProcessor: DolbyAudioProcessor? = null

    private var surfaceView: SurfaceView? = null
    private var surfaceHolder: SurfaceHolder? = null

    private var eventListener: VideoRendererEventListener? = null
    private var currentState = PlayerState.IDLE

    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentPosition = AtomicLong(0)
    private var duration = AtomicLong(0)

    private var videoWidth = 0
    private var videoHeight = 0

    // 位置同步
    private var targetX = config.x
    private var targetY = config.y
    private var targetWidth = config.width
    private var targetHeight = config.height

    private val choreographer = Choreographer.getInstance()
    private var isPositionSyncActive = false

    init {
        initializeSurfaceView()
        initializePlayer()
        initializeAudioComponents()
        startPositionSync()
    }

    /**
     * 初始化SurfaceView
     */
    private fun initializeSurfaceView() {
        mainHandler.post {
            surfaceView = SurfaceView(context).apply {
                // 设置Z-Order，使其在WebView内容之上
                setZOrderMediaOverlay(true)

                // 设置背景透明
                holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)

                // 初始化布局参数（使用绝对定位）
                layoutParams = FrameLayout.LayoutParams(
                    if (config.width > 0) config.width else 100,
                    if (config.height > 0) config.height else 100
                ).apply {
                    leftMargin = 0
                    topMargin = 0
                }

                // 设置Holder回调
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        surfaceHolder = holder
                        exoPlayer?.setVideoSurfaceHolder(holder)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        // Surface尺寸变化
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        surfaceHolder = null
                        exoPlayer?.clearVideoSurfaceHolder(holder)
                    }
                })
            }

            // 将SurfaceView添加到WebView的父容器
            val parent = webView.parent as? ViewGroup
            parent?.addView(surfaceView)

            // 延迟更新初始位置，确保H5已经计算好坐标
            mainHandler.postDelayed({
                updateSurfaceViewPosition()
            }, 100)
        }
    }

    /**
     * 初始化播放器
     */
    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(context)
            .build()
            .apply {
                // 设置Surface
                surfaceHolder?.let { setVideoSurfaceHolder(it) }

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
                    exoPlayer?.playWhenReady = true
                }

                override fun onAudioFocusLoss(permanent: Boolean) {
                    if (permanent) {
                        pause()
                    } else {
                        pause()
                    }
                }

                override fun onAudioFocusDuck() {
                    exoPlayer?.volume = (config.volume * 0.3f)
                }
            })
        }

        // 杜比音效处理
        if (config.enableDolby) {
            dolbyProcessor = DolbyAudioProcessor(context).apply {
                exoPlayer?.audioSessionId?.let { sessionId ->
                    initialize(sessionId)
                    val dolbyConfig = DolbyAudioProcessor.createCarOptimizedConfig()
                    applyConfig(dolbyConfig)
                }
            }
        }
    }

    /**
     * 启动位置同步
     * 使用Choreographer确保60fps刷新
     */
    private fun startPositionSync() {
        isPositionSyncActive = true
        scheduleNextPositionUpdate()
    }

    /**
     * 停止位置同步
     */
    private fun stopPositionSync() {
        isPositionSyncActive = false
    }

    /**
     * 调度下一次位置更新
     */
    private fun scheduleNextPositionUpdate() {
        if (!isPositionSyncActive) return

        choreographer.postFrameCallback { frameTimeNanos ->
            updateSurfaceViewPosition()
            scheduleNextPositionUpdate()
        }
    }

    /**
     * 更新SurfaceView位置
     */
    private fun updateSurfaceViewPosition() {
        surfaceView?.let { view ->
            // 检查是否需要更新
            val needsUpdate = view.x != targetX.toFloat() ||
                    view.y != targetY.toFloat() ||
                    view.layoutParams?.width != targetWidth ||
                    view.layoutParams?.height != targetHeight

            if (needsUpdate) {
                // 使用绝对坐标定位
                view.x = targetX.toFloat()
                view.y = targetY.toFloat()

                // 更新尺寸
                val layoutParams = view.layoutParams
                layoutParams?.width = targetWidth
                layoutParams?.height = targetHeight
                view.layoutParams = layoutParams
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
        val focusGranted = audioFocusManager?.requestAudioFocus(config.audioFocusType) ?: true

        if (focusGranted) {
            mainHandler.post {
                exoPlayer?.playWhenReady = true
                currentState = PlayerState.PLAYING
                eventListener?.onStateChanged(currentState)

                // 确保SurfaceView可见
                surfaceView?.visibility = android.view.View.VISIBLE
            }
        }
    }

    override fun pause() {
        mainHandler.post {
            exoPlayer?.playWhenReady = false
            currentState = PlayerState.PAUSED
            eventListener?.onStateChanged(currentState)
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

            // 隐藏SurfaceView
            surfaceView?.visibility = android.view.View.GONE
        }
    }

    override fun release() {
        mainHandler.post {
            stopPositionSync()
            stopProgressUpdater()

            exoPlayer?.release()
            exoPlayer = null

            audioFocusManager?.release()
            audioFocusManager = null

            dolbyProcessor?.release()
            dolbyProcessor = null

            // 移除SurfaceView
            surfaceView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
            surfaceView = null

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
        // H5传来的是CSS像素（相对于WebView视口）
        // 需要转换CSS像素为物理像素（乘以density）
        // 注意：view.x/view.y 是相对于父容器的坐标，不需要加WebView偏移

        // 获取设备密度（devicePixelRatio在Android中对应density）
        val density = context.resources.displayMetrics.density

        // 转换CSS像素到物理像素
        targetX = (x * density).toInt()
        targetY = (y * density).toInt()
        targetWidth = (width * density).toInt()
        targetHeight = (height * density).toInt()

        // 调试日志
        Log.d("LayerVideoRenderer", "updateLayout: " +
                "CSS(x=$x, y=$y, w=$width, h=$height) " +
                "density=$density " +
                "Physical($targetX, $targetY, $targetWidth, $targetHeight)")

        // 立即更新一次（不等待Choreographer）
        mainHandler.post {
            updateSurfaceViewPosition()
        }
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

                    exoPlayer?.duration?.let { dur ->
                        if (dur > 0) {
                            duration.set(dur)
                        }
                    }

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
