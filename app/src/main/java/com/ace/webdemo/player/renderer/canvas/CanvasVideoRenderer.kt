package com.ace.webdemo.player.renderer.canvas

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private val config: PlayerConfig
) : VideoRenderer {

    private var exoPlayer: ExoPlayer? = null
    private var audioFocusManager: AudioFocusManager? = null
    private var dolbyProcessor: DolbyAudioProcessor? = null

    private var eventListener: VideoRendererEventListener? = null
    private var currentState = PlayerState.IDLE

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameProcessor = FrameProcessor(config.maxFrameRate)

    private var currentPosition = AtomicLong(0)
    private var duration = AtomicLong(0)

    private var videoWidth = 0
    private var videoHeight = 0

    init {
        initializePlayer()
        initializeAudioComponents()
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
        }
    }

    override fun release() {
        mainHandler.post {
            stopProgressUpdater()

            exoPlayer?.release()
            exoPlayer = null

            audioFocusManager?.release()
            audioFocusManager = null

            dolbyProcessor?.release()
            dolbyProcessor = null

            frameProcessor.release()

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
        // Canvas模式下布局由H5控制，这里不需要处理
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

/**
 * 帧处理器
 * 负责提取视频帧并转换为RGB数据传输到H5
 *
 * 注意：这是简化实现，实际项目中需要：
 * 1. 使用ExoPlayer的VideoFrameProcessor或MediaCodec直接解码
 * 2. 使用SharedMemory优化数据传输
 * 3. 使用RenderScript/GPU加速YUV->RGB转换
 */
private class FrameProcessor(private val maxFrameRate: Int) {

    private val minFrameInterval = 1000 / maxFrameRate
    private var lastFrameTime = 0L

    fun processFrame(frameData: ByteArray, width: Int, height: Int, timestamp: Long) {
        // 帧率控制
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameTime < minFrameInterval) {
            return
        }
        lastFrameTime = currentTime

        // TODO: 实际项目中，这里应该：
        // 1. 从ExoPlayer获取YUV数据
        // 2. 转换为RGB格式
        // 3. 通过JSBridge发送到H5

        // 示例代码（需要根据实际情况调整）:
        // val rgbData = convertYUVtoRGB(frameData, width, height)
        // eventListener?.onFrameRendered(rgbData, width, height, timestamp)
    }

    fun release() {
        // 清理资源
    }
}
