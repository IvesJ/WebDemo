package com.ace.webdemo.player.config

import android.media.AudioManager

/**
 * 音频焦点类型
 * 对应Android AudioManager的焦点类型
 */
enum class AudioFocusType(val value: Int) {
    /**
     * 长时间获取音频焦点
     * 适用场景: 详情页播放、长视频
     */
    GAIN(AudioManager.AUDIOFOCUS_GAIN),

    /**
     * 短暂获取音频焦点
     * 适用场景: 列表预览、短视频
     */
    TRANSIENT(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT),

    /**
     * 短暂获取焦点，可降低音量
     * 适用场景: 通知音、提示音
     */
    TRANSIENT_MAY_DUCK(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK),

    /**
     * 独占音频焦点（车机场景）
     * 适用场景: 需要独占音频输出的场景
     */
    EXCLUSIVE(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);

    companion object {
        fun fromValue(value: Int): AudioFocusType {
            return values().find { it.value == value } ?: GAIN
        }
    }
}
