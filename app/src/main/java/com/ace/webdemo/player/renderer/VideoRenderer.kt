package com.ace.webdemo.player.renderer

import com.ace.webdemo.player.config.PlayerState

/**
 * 视频渲染器接口
 * 定义播放器的核心功能
 */
interface VideoRenderer {

    /**
     * 准备播放
     * @param url 视频URL
     */
    fun prepare(url: String)

    /**
     * 开始播放
     */
    fun play()

    /**
     * 暂停播放
     */
    fun pause()

    /**
     * 跳转到指定位置
     * @param position 位置(毫秒)
     */
    fun seek(position: Long)

    /**
     * 停止播放
     */
    fun stop()

    /**
     * 释放资源
     */
    fun release()

    /**
     * 设置音量
     * @param volume 音量 (0.0 - 1.0)
     */
    fun setVolume(volume: Float)

    /**
     * 设置是否静音
     * @param muted 是否静音
     */
    fun setMuted(muted: Boolean)

    /**
     * 更新布局位置
     * @param x X坐标
     * @param y Y坐标
     * @param width 宽度
     * @param height 高度
     */
    fun updateLayout(x: Int, y: Int, width: Int, height: Int)

    /**
     * 设置播放速度
     * @param speed 播放速度 (0.5 - 2.0)
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * 获取当前播放位置
     * @return 当前位置(毫秒)
     */
    fun getCurrentPosition(): Long

    /**
     * 获取视频总时长
     * @return 总时长(毫秒)
     */
    fun getDuration(): Long

    /**
     * 获取当前状态
     * @return 播放器状态
     */
    fun getState(): PlayerState

    /**
     * 设置播放器事件监听器
     * @param listener 事件监听器
     */
    fun setEventListener(listener: VideoRendererEventListener)

    /**
     * 设置是否循环播放
     * @param loop 是否循环
     */
    fun setLoop(loop: Boolean)

    /**
     * 获取视频宽度
     * @return 视频宽度(像素)
     */
    fun getVideoWidth(): Int

    /**
     * 获取视频高度
     * @return 视频高度(像素)
     */
    fun getVideoHeight(): Int
}

/**
 * 视频渲染器事件监听器
 */
interface VideoRendererEventListener {
    /**
     * 准备完成
     */
    fun onPrepared()

    /**
     * 播放状态改变
     * @param state 新状态
     */
    fun onStateChanged(state: PlayerState)

    /**
     * 播放进度更新
     * @param position 当前位置(毫秒)
     * @param duration 总时长(毫秒)
     */
    fun onProgressChanged(position: Long, duration: Long)

    /**
     * 缓冲进度更新
     * @param percent 缓冲百分比 (0-100)
     */
    fun onBufferingUpdate(percent: Int)

    /**
     * 视频尺寸改变
     * @param width 视频宽度
     * @param height 视频高度
     */
    fun onVideoSizeChanged(width: Int, height: Int)

    /**
     * 播放完成
     */
    fun onCompletion()

    /**
     * 发生错误
     * @param errorCode 错误码
     * @param errorMessage 错误信息
     */
    fun onError(errorCode: Int, errorMessage: String)

    /**
     * 帧渲染（Canvas模式专用）
     * @param frameData 帧数据
     * @param width 帧宽度
     * @param height 帧高度
     * @param timestamp 时间戳
     */
    fun onFrameRendered(frameData: ByteArray, width: Int, height: Int, timestamp: Long) {}
}
