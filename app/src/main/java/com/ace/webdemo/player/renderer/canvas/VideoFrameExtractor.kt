package com.ace.webdemo.player.renderer.canvas

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 视频帧提取器
 * 从ExoPlayer提取视频帧，转换为可传输到H5的格式
 */
class VideoFrameExtractor(
    private val maxFrameRate: Int = 30,
    private val onFrameExtracted: (frameData: ByteArray, width: Int, height: Int, timestamp: Long) -> Unit
) {

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private val minFrameInterval = 1000L / maxFrameRate
    private var lastFrameTime = 0L

    /**
     * 初始化Surface用于ExoPlayer输出
     */
    fun initialize(textureId: Int): Surface? {
        try {
            surfaceTexture = SurfaceTexture(textureId).apply {
                setDefaultBufferSize(1280, 720)

                // 监听帧更新
                setOnFrameAvailableListener { texture ->
                    if (isRunning) {
                        extractFrame(texture)
                    }
                }
            }

            surface = Surface(surfaceTexture)
            return surface
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * 设置ExoPlayer
     */
    fun setPlayer(player: ExoPlayer, width: Int, height: Int) {
        // ExoPlayer会自动渲染到设置的Surface
        // 我们通过SurfaceTexture的回调获取帧
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    /**
     * 开始提取帧
     */
    fun start() {
        isRunning = true
    }

    /**
     * 停止提取帧
     */
    fun stop() {
        isRunning = false
    }

    /**
     * 提取当前帧
     */
    private fun extractFrame(texture: SurfaceTexture) {
        val currentTime = System.currentTimeMillis()

        // 帧率控制
        if (currentTime - lastFrameTime < minFrameInterval) {
            return
        }
        lastFrameTime = currentTime

        try {
            // 更新纹理
            texture.updateTexImage()

            // 获取时间戳
            val timestamp = texture.timestamp

            // 注意：这里是简化实现
            // 实际项目中应该使用OpenGL读取纹理数据并转换为RGB
            // 目前这个方法不会真正提取像素数据

            // TODO: 使用OpenGL ES读取纹理数据
            // 1. 创建FBO (FrameBuffer Object)
            // 2. 将SurfaceTexture绑定到FBO
            // 3. 使用glReadPixels读取像素数据
            // 4. 转换为RGB格式

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)

        surface?.release()
        surface = null

        surfaceTexture?.release()
        surfaceTexture = null
    }
}

/**
 * Bitmap帧提取器（备用方案）
 * 使用定时截图的方式获取帧
 *
 * 注意：这个方案性能较差，仅用于演示
 */
class BitmapFrameExtractor(
    private val frameRate: Int = 15,
    private val onFrameExtracted: (frameData: ByteArray, width: Int, height: Int, timestamp: Long) -> Unit
) {

    private var isRunning = false
    private val handler = Handler(Looper.getMainLooper())
    private val frameInterval = 1000L / frameRate

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                // 这里需要从播放器截图
                // 但ExoPlayer不直接提供截图API
                // 需要通过Surface/TextureView来实现

                handler.postDelayed(this, frameInterval)
            }
        }
    }

    fun start() {
        isRunning = true
        handler.post(captureRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(captureRunnable)
    }

    fun release() {
        stop()
    }
}
