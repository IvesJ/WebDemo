package com.ace.webdemo.player

import android.content.Context
import android.webkit.WebView
import com.ace.webdemo.player.config.PlayerConfig
import com.ace.webdemo.player.config.RenderMode
import com.ace.webdemo.player.renderer.VideoRenderer
import com.ace.webdemo.player.renderer.canvas.CanvasVideoRenderer
import com.ace.webdemo.player.renderer.layer.LayerVideoRenderer
import java.util.concurrent.ConcurrentHashMap

/**
 * 混合视频播放器管理器
 * 负责创建、管理和销毁播放器实例
 */
class HybridVideoPlayerManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: HybridVideoPlayerManager? = null

        fun getInstance(context: Context): HybridVideoPlayerManager {
            return instance ?: synchronized(this) {
                instance ?: HybridVideoPlayerManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * 播放器实例池
     */
    private val playerPool = ConcurrentHashMap<String, VideoRenderer>()

    /**
     * WebView引用（用于同层渲染）
     */
    private var webView: WebView? = null

    /**
     * 设置WebView引用
     */
    fun setWebView(webView: WebView) {
        this.webView = webView
    }

    /**
     * 创建播放器实例
     * @param config 播放器配置
     * @return 渲染器实例
     */
    fun createPlayer(config: PlayerConfig): VideoRenderer {
        // 如果已存在，先销毁
        playerPool[config.playerId]?.let {
            destroyPlayer(config.playerId)
        }

        // 根据配置选择渲染模式
        val renderMode = selectRenderMode(config)

        // 创建渲染器
        val renderer = when (renderMode) {
            RenderMode.CANVAS -> {
                CanvasVideoRenderer(context, config)
            }
            RenderMode.LAYER -> {
                webView?.let { wv ->
                    LayerVideoRenderer(context, wv, config)
                } ?: throw IllegalStateException("WebView not set for LAYER mode")
            }
            RenderMode.AUTO -> {
                // AUTO模式下根据配置自动选择
                if (shouldUseCanvasMode(config)) {
                    CanvasVideoRenderer(context, config)
                } else {
                    webView?.let { wv ->
                        LayerVideoRenderer(context, wv, config)
                    } ?: CanvasVideoRenderer(context, config)
                }
            }
        }

        playerPool[config.playerId] = renderer
        return renderer
    }

    /**
     * 获取播放器实例
     * @param playerId 播放器ID
     * @return 渲染器实例，如果不存在返回null
     */
    fun getPlayer(playerId: String): VideoRenderer? {
        return playerPool[playerId]
    }

    /**
     * 销毁播放器实例
     * @param playerId 播放器ID
     */
    fun destroyPlayer(playerId: String) {
        playerPool.remove(playerId)?.let { renderer ->
            renderer.release()
        }
    }

    /**
     * 销毁所有播放器实例
     */
    fun destroyAllPlayers() {
        playerPool.values.forEach { it.release() }
        playerPool.clear()
    }

    /**
     * 暂停所有播放器
     */
    fun pauseAllPlayers() {
        playerPool.values.forEach { it.pause() }
    }

    /**
     * 获取当前活跃的播放器数量
     */
    fun getActivePlayerCount(): Int {
        return playerPool.size
    }

    /**
     * 选择渲染模式
     */
    private fun selectRenderMode(config: PlayerConfig): RenderMode {
        return when (config.renderMode) {
            RenderMode.AUTO -> {
                if (shouldUseCanvasMode(config)) RenderMode.CANVAS else RenderMode.LAYER
            }
            else -> config.renderMode
        }
    }

    /**
     * 判断是否应该使用Canvas模式
     * 策略：
     * 1. 小尺寸视频（列表流）使用Canvas
     * 2. 需要高性能滚动的场景使用Canvas
     * 3. 大尺寸视频（详情页）使用Layer
     */
    private fun shouldUseCanvasMode(config: PlayerConfig): Boolean {
        val area = config.width * config.height
        val screenArea = context.resources.displayMetrics.run {
            widthPixels * heightPixels
        }

        // 如果视频面积小于屏幕面积的40%，使用Canvas模式（列表流）
        // 否则使用Layer模式（详情页/全屏）
        return area < screenArea * 0.4
    }

    /**
     * 更新播放器布局
     * @param playerId 播放器ID
     * @param x X坐标
     * @param y Y坐标
     * @param width 宽度
     * @param height 高度
     */
    fun updatePlayerLayout(playerId: String, x: Int, y: Int, width: Int, height: Int) {
        playerPool[playerId]?.updateLayout(x, y, width, height)
    }

    /**
     * 播放指定URL
     * @param playerId 播放器ID
     * @param url 视频URL
     */
    fun play(playerId: String, url: String) {
        playerPool[playerId]?.let { renderer ->
            renderer.prepare(url)
            renderer.play()
        }
    }

    /**
     * 暂停播放
     * @param playerId 播放器ID
     */
    fun pause(playerId: String) {
        playerPool[playerId]?.pause()
    }

    /**
     * 继续播放
     * @param playerId 播放器ID
     */
    fun resume(playerId: String) {
        playerPool[playerId]?.play()
    }

    /**
     * 跳转到指定位置
     * @param playerId 播放器ID
     * @param position 位置(毫秒)
     */
    fun seek(playerId: String, position: Long) {
        playerPool[playerId]?.seek(position)
    }

    /**
     * 设置音量
     * @param playerId 播放器ID
     * @param volume 音量 (0.0 - 1.0)
     */
    fun setVolume(playerId: String, volume: Float) {
        playerPool[playerId]?.setVolume(volume)
    }

    /**
     * 设置静音
     * @param playerId 播放器ID
     * @param muted 是否静音
     */
    fun setMuted(playerId: String, muted: Boolean) {
        playerPool[playerId]?.setMuted(muted)
    }

    /**
     * 设置播放速度
     * @param playerId 播放器ID
     * @param speed 播放速度
     */
    fun setPlaybackSpeed(playerId: String, speed: Float) {
        playerPool[playerId]?.setPlaybackSpeed(speed)
    }

    /**
     * 获取当前播放位置
     * @param playerId 播放器ID
     * @return 当前位置(毫秒)
     */
    fun getCurrentPosition(playerId: String): Long {
        return playerPool[playerId]?.getCurrentPosition() ?: 0L
    }

    /**
     * 获取视频总时长
     * @param playerId 播放器ID
     * @return 总时长(毫秒)
     */
    fun getDuration(playerId: String): Long {
        return playerPool[playerId]?.getDuration() ?: 0L
    }
}
