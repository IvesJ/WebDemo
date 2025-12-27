package com.ace.webdemo.player.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.ace.webdemo.player.config.AudioFocusType

/**
 * 音频焦点管理器
 * 负责管理音频焦点的请求和释放，适配车机场景
 */
class AudioFocusManager(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var currentFocusRequest: AudioFocusRequest? = null
    private var currentFocusType: AudioFocusType? = null

    private val focusChangeListeners = mutableListOf<AudioFocusChangeListener>()

    /**
     * 音频焦点变化监听器
     */
    private val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        handleFocusChange(focusChange)
    }

    /**
     * 请求音频焦点
     * @param focusType 焦点类型
     * @return 是否成功获取焦点
     */
    fun requestAudioFocus(focusType: AudioFocusType): Boolean {
        // 如果已经持有相同类型的焦点，直接返回成功
        if (currentFocusType == focusType && hasFocus()) {
            return true
        }

        // 先释放之前的焦点
        abandonAudioFocus()

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0及以上使用AudioFocusRequest
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            val focusRequest = AudioFocusRequest.Builder(focusType.value)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(afChangeListener)
                .setAcceptsDelayedFocusGain(true) // 允许延迟获取焦点
                .build()

            currentFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            // Android 8.0以下使用旧API
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                afChangeListener,
                AudioManager.STREAM_MUSIC,
                focusType.value
            )
        }

        val success = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (success) {
            currentFocusType = focusType
        }

        return success
    }

    /**
     * 释放音频焦点
     */
    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            currentFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                currentFocusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(afChangeListener)
        }

        currentFocusType = null
    }

    /**
     * 是否持有焦点
     */
    fun hasFocus(): Boolean {
        return currentFocusType != null
    }

    /**
     * 获取当前焦点类型
     */
    fun getCurrentFocusType(): AudioFocusType? {
        return currentFocusType
    }

    /**
     * 处理焦点变化
     */
    private fun handleFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 重新获得焦点，恢复播放
                notifyFocusGained()
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久失去焦点，停止播放
                currentFocusType = null
                notifyFocusLost(permanent = true)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 暂时失去焦点，暂停播放
                notifyFocusLost(permanent = false)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 暂时失去焦点，但可以降低音量继续播放
                notifyFocusDuck()
            }
        }
    }

    /**
     * 添加焦点变化监听器
     */
    fun addFocusChangeListener(listener: AudioFocusChangeListener) {
        if (!focusChangeListeners.contains(listener)) {
            focusChangeListeners.add(listener)
        }
    }

    /**
     * 移除焦点变化监听器
     */
    fun removeFocusChangeListener(listener: AudioFocusChangeListener) {
        focusChangeListeners.remove(listener)
    }

    /**
     * 通知焦点获得
     */
    private fun notifyFocusGained() {
        focusChangeListeners.forEach { it.onAudioFocusGain() }
    }

    /**
     * 通知焦点失去
     */
    private fun notifyFocusLost(permanent: Boolean) {
        focusChangeListeners.forEach { it.onAudioFocusLoss(permanent) }
    }

    /**
     * 通知需要降低音量
     */
    private fun notifyFocusDuck() {
        focusChangeListeners.forEach { it.onAudioFocusDuck() }
    }

    /**
     * 清理资源
     */
    fun release() {
        abandonAudioFocus()
        focusChangeListeners.clear()
    }
}

/**
 * 音频焦点变化监听器接口
 */
interface AudioFocusChangeListener {
    /**
     * 获得音频焦点
     */
    fun onAudioFocusGain()

    /**
     * 失去音频焦点
     * @param permanent 是否永久失去
     */
    fun onAudioFocusLoss(permanent: Boolean)

    /**
     * 需要降低音量（Duck）
     */
    fun onAudioFocusDuck()
}
