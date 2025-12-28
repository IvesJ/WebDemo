package com.ace.webdemo.player.bridge

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.ace.webdemo.player.HybridVideoPlayerManager
import com.ace.webdemo.player.config.PlayerConfig
import com.ace.webdemo.player.config.PlayerState
import com.ace.webdemo.player.renderer.VideoRenderer
import com.ace.webdemo.player.renderer.VideoRendererEventListener
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频播放器JSBridge
 * 提供H5与Native通信的桥梁
 */
class VideoPlayerJSBridge(
    private val webView: WebView,
    private val playerManager: HybridVideoPlayerManager
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // 共享内存缓冲区池 - 每个播放器一个buffer
    private val frameBuffers = ConcurrentHashMap<String, ByteBuffer>()

    // 帧序号，用于JS端判断是否有新帧
    private val frameSequences = ConcurrentHashMap<String, Long>()

    // 帧跳过计数器（隔帧传输优化）
    private val frameSkipCounters = ConcurrentHashMap<String, Int>()

    // 隔帧传输开关（全局配置）
    @Volatile
    private var frameSkipEnabled = true

    // 隔帧间隔（1=不跳帧，2=每2帧传1帧，3=每3帧传1帧）
    @Volatile
    private var frameSkipInterval = 2

    // 使用RGB888格式（否则使用RGBA）
    @Volatile
    private var useRGB888 = true

    /**
     * 创建播放器
     * @param configJson 配置JSON字符串
     */
    @JavascriptInterface
    fun createPlayer(configJson: String) {
        try {
            val json = JSONObject(configJson)
            val config = PlayerConfig.fromJson(json)

            mainHandler.post {
                val renderer = playerManager.createPlayer(config)

                // 设置事件监听器
                renderer.setEventListener(object : VideoRendererEventListener {
                    override fun onPrepared() {
                        callJS(config.playerId, "onPrepared")
                    }

                    override fun onStateChanged(state: PlayerState) {
                        callJS(config.playerId, "onStateChanged", state.name)
                    }

                    override fun onProgressChanged(position: Long, duration: Long) {
                        callJS(config.playerId, "onProgressChanged", position, duration)
                    }

                    override fun onBufferingUpdate(percent: Int) {
                        callJS(config.playerId, "onBufferingUpdate", percent)
                    }

                    override fun onVideoSizeChanged(width: Int, height: Int) {
                        callJS(config.playerId, "onVideoSizeChanged", width, height)
                    }

                    override fun onCompletion() {
                        callJS(config.playerId, "onCompletion")
                    }

                    override fun onError(errorCode: Int, errorMessage: String) {
                        callJS(config.playerId, "onError", errorCode, errorMessage)
                    }

                    override fun onFrameRendered(
                        frameData: ByteArray,
                        width: Int,
                        height: Int,
                        timestamp: Long
                    ) {
                        // 将帧数据传递到H5
                        sendFrameToJS(config.playerId, frameData, width, height, timestamp)
                    }
                })
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 播放视频
     * @param playerId 播放器ID
     * @param url 视频URL
     */
    @JavascriptInterface
    fun play(playerId: String, url: String) {
        mainHandler.post {
            playerManager.play(playerId, url)
        }
    }

    /**
     * 暂停播放
     * @param playerId 播放器ID
     */
    @JavascriptInterface
    fun pause(playerId: String) {
        mainHandler.post {
            playerManager.pause(playerId)
        }
    }

    /**
     * 继续播放
     * @param playerId 播放器ID
     */
    @JavascriptInterface
    fun resume(playerId: String) {
        mainHandler.post {
            playerManager.resume(playerId)
        }
    }

    /**
     * 跳转到指定位置
     * @param playerId 播放器ID
     * @param position 位置(毫秒)
     */
    @JavascriptInterface
    fun seek(playerId: String, position: Long) {
        mainHandler.post {
            playerManager.seek(playerId, position)
        }
    }

    /**
     * 设置音量
     * @param playerId 播放器ID
     * @param volume 音量 (0.0 - 1.0)
     */
    @JavascriptInterface
    fun setVolume(playerId: String, volume: Float) {
        mainHandler.post {
            playerManager.setVolume(playerId, volume)
        }
    }

    /**
     * 设置静音
     * @param playerId 播放器ID
     * @param muted 是否静音
     */
    @JavascriptInterface
    fun setMuted(playerId: String, muted: Boolean) {
        mainHandler.post {
            playerManager.setMuted(playerId, muted)
        }
    }

    /**
     * 设置播放速度
     * @param playerId 播放器ID
     * @param speed 播放速度
     */
    @JavascriptInterface
    fun setPlaybackSpeed(playerId: String, speed: Float) {
        mainHandler.post {
            playerManager.setPlaybackSpeed(playerId, speed)
        }
    }

    /**
     * 更新播放器布局
     * @param playerId 播放器ID
     * @param x X坐标（CSS像素）
     * @param y Y坐标（CSS像素）
     * @param width 宽度（CSS像素）
     * @param height 高度（CSS像素）
     */
    @JavascriptInterface
    fun updateLayout(playerId: String, x: Int, y: Int, width: Int, height: Int) {
        mainHandler.post {
            playerManager.updatePlayerLayout(playerId, x, y, width, height)
        }
    }

    /**
     * 更新播放器布局（接收Float参数以支持小数）
     * H5侧getBoundingClientRect返回的可能是小数
     */
    @JavascriptInterface
    fun updateLayoutFloat(playerId: String, x: Float, y: Float, width: Float, height: Float) {
        mainHandler.post {
            playerManager.updatePlayerLayout(
                playerId,
                x.toInt(),
                y.toInt(),
                width.toInt(),
                height.toInt()
            )
        }
    }

    /**
     * 更新可见性
     * @param playerId 播放器ID
     * @param isVisible 是否可见
     */
    @JavascriptInterface
    fun updateVisibility(playerId: String, isVisible: Boolean) {
        // 可以根据可见性暂停/恢复播放，节省资源
        mainHandler.post {
            if (!isVisible) {
                // 视频不可见时可以选择暂停或降低帧率
                // playerManager.pause(playerId)
            }
        }
    }

    /**
     * 获取当前播放位置
     * @param playerId 播放器ID
     * @return 当前位置(毫秒)
     */
    @JavascriptInterface
    fun getCurrentPosition(playerId: String): Long {
        return playerManager.getCurrentPosition(playerId)
    }

    /**
     * 获取视频总时长
     * @param playerId 播放器ID
     * @return 总时长(毫秒)
     */
    @JavascriptInterface
    fun getDuration(playerId: String): Long {
        return playerManager.getDuration(playerId)
    }

    /**
     * 销毁播放器
     * @param playerId 播放器ID
     */
    @JavascriptInterface
    fun destroyPlayer(playerId: String) {
        mainHandler.post {
            playerManager.destroyPlayer(playerId)
        }
    }

    /**
     * 调用JS回调
     */
    private fun callJS(playerId: String, method: String, vararg args: Any) {
        mainHandler.post {
            try {
                val argsJson = args.joinToString(",") { arg ->
                    when (arg) {
                        is String -> "\"$arg\""
                        is Number -> arg.toString()
                        is Boolean -> arg.toString()
                        else -> "\"$arg\""
                    }
                }

                val script = """
                    if (window.__hybridVideoPlayerCallbacks__ &&
                        window.__hybridVideoPlayerCallbacks__['$playerId'] &&
                        window.__hybridVideoPlayerCallbacks__['$playerId'].$method) {
                        window.__hybridVideoPlayerCallbacks__['$playerId'].$method($argsJson);
                    }
                """.trimIndent()

                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 获取共享内存缓冲区（供JS调用）
     * @param playerId 播放器ID
     * @return Base64编码的帧数据（用于初始化）
     */
    @JavascriptInterface
    fun getFrameBuffer(playerId: String): String {
        val buffer = frameBuffers[playerId]
        return if (buffer != null) {
            // 返回buffer的当前内容（Base64编码）
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            buffer.rewind()
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } else {
            ""
        }
    }

    /**
     * 获取帧序号（供JS轮询使用）
     * @param playerId 播放器ID
     * @return 当前帧序号
     */
    @JavascriptInterface
    fun getFrameSequence(playerId: String): Long {
        return frameSequences[playerId] ?: 0L
    }

    /**
     * 设置隔帧传输开关
     * @param enabled 是否启用隔帧传输
     */
    @JavascriptInterface
    fun setFrameSkipEnabled(enabled: Boolean) {
        frameSkipEnabled = enabled
        android.util.Log.d("VideoPlayerJSBridge", "Frame skip enabled: $enabled")
    }

    /**
     * 设置隔帧间隔
     * @param interval 间隔（1=不跳帧，2=每2帧传1帧，3=每3帧传1帧）
     */
    @JavascriptInterface
    fun setFrameSkipInterval(interval: Int) {
        frameSkipInterval = interval.coerceIn(1, 10)
        android.util.Log.d("VideoPlayerJSBridge", "Frame skip interval: $frameSkipInterval")
    }

    /**
     * 获取当前隔帧配置
     * @return JSON格式的配置信息
     */
    @JavascriptInterface
    fun getFrameSkipConfig(): String {
        return """{"enabled":$frameSkipEnabled,"interval":$frameSkipInterval,"useRGB888":$useRGB888}"""
    }

    /**
     * 设置颜色格式
     * @param useRGB888 true=RGB888, false=RGBA
     */
    @JavascriptInterface
    fun setColorFormat(useRGB888: Boolean) {
        this.useRGB888 = useRGB888

        // 同时更新所有Canvas渲染器的颜色格式
        mainHandler.post {
            playerManager.getAllPlayers().forEach { (_, renderer) ->
                if (renderer is com.ace.webdemo.player.renderer.canvas.CanvasVideoRenderer) {
                    renderer.useRGB888 = useRGB888
                }
            }
        }

        android.util.Log.d("VideoPlayerJSBridge", "Color format: ${if (useRGB888) "RGB888" else "RGBA"}")
    }

    /**
     * 发送帧数据到JS（Canvas模式 - 使用共享内存优化）
     */
    private fun sendFrameToJS(
        playerId: String,
        frameData: ByteArray,
        width: Int,
        height: Int,
        timestamp: Long
    ) {
        mainHandler.post {
            try {
                // 隔帧传输（可动态开关）
                if (frameSkipEnabled && frameSkipInterval > 1) {
                    val skipCounter = frameSkipCounters[playerId] ?: 0
                    frameSkipCounters[playerId] = (skipCounter + 1) % frameSkipInterval

                    if (skipCounter != 0) {
                        return@post // 跳过此帧
                    }
                }

                // 1. 写入数据到ByteBuffer
                var buffer = frameBuffers[playerId]
                if (buffer == null || buffer.capacity() < frameData.size) {
                    buffer = ByteBuffer.allocateDirect(frameData.size)
                    frameBuffers[playerId] = buffer
                }

                buffer.rewind()
                buffer.put(frameData)
                buffer.rewind()

                // 2. 更新帧序号
                val seq = (frameSequences[playerId] ?: 0L) + 1
                frameSequences[playerId] = seq

                // 3. 判断实际数据格式
                // RGB888: width * height * 3
                // RGBA: width * height * 4
                val bytesPerPixel = frameData.size / (width * height)
                val isRGB888 = (bytesPerPixel == 3)

                // 4. 通知JS有新帧（轻量级通知，包含格式信息）
                val script = """
                    (function() {
                        const callbacks = window.__hybridVideoPlayerCallbacks__;
                        if (callbacks && callbacks['$playerId'] && callbacks['$playerId'].onFrameReady) {
                            callbacks['$playerId'].onFrameReady($seq, $width, $height, $timestamp, $isRGB888);
                        }
                    })();
                """.trimIndent()

                webView.evaluateJavascript(script, null)
            } catch (e: Exception) {
                android.util.Log.e("VideoPlayerJSBridge", "Error sending frame to JS", e)
                e.printStackTrace()
            }
        }
    }
}
