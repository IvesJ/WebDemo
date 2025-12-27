package com.ace.webdemo.player.config

/**
 * 播放器状态
 */
enum class PlayerState {
    /**
     * 空闲状态
     */
    IDLE,

    /**
     * 准备中
     */
    PREPARING,

    /**
     * 准备完成
     */
    PREPARED,

    /**
     * 播放中
     */
    PLAYING,

    /**
     * 暂停
     */
    PAUSED,

    /**
     * 缓冲中
     */
    BUFFERING,

    /**
     * 播放完成
     */
    COMPLETED,

    /**
     * 错误
     */
    ERROR,

    /**
     * 已释放
     */
    RELEASED
}
