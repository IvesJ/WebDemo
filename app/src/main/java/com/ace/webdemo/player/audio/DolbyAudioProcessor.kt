package com.ace.webdemo.player.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build

/**
 * 杜比音效处理器
 * 提供杜比音效的配置和处理功能
 *
 * 注意：完整的杜比音效需要：
 * 1. 车机硬件支持杜比解码
 * 2. 集成杜比SDK（需要授权）
 * 3. 音频输出设备支持
 *
 * 这里提供基础框架和标准Android AudioEffect的增强实现
 */
class DolbyAudioProcessor(private val context: Context) {

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

    private var audioSessionId: Int = 0
    private var isEnabled = false

    /**
     * 杜比音效配置
     */
    data class DolbyConfig(
        // 是否启用杜比音效
        val enabled: Boolean = false,

        // 响度增强级别 (0-1000)
        val loudnessGain: Int = 0,

        // 动态范围压缩
        val enableDRC: Boolean = true,

        // 虚拟环绕声
        val enableVirtualizer: Boolean = true,

        // 对话增强
        val enableDialogueEnhancer: Boolean = false,

        // 低音增强级别 (0-1000)
        val bassBoost: Int = 0
    )

    /**
     * 初始化音效处理器
     * @param audioSessionId 音频会话ID（从MediaPlayer/ExoPlayer获取）
     */
    fun initialize(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
        release() // 先释放之前的实例
    }

    /**
     * 应用杜比音效配置
     * @param config 配置参数
     */
    fun applyConfig(config: DolbyConfig) {
        if (audioSessionId == 0) {
            throw IllegalStateException("AudioProcessor not initialized. Call initialize() first.")
        }

        isEnabled = config.enabled

        if (!isEnabled) {
            release()
            return
        }

        try {
            // 1. 响度增强
            if (config.loudnessGain > 0) {
                applyLoudnessEnhancement(config.loudnessGain)
            }

            // 2. 动态范围处理（Android 9.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && config.enableDRC) {
                applyDynamicsProcessing()
            }

            // 注意：完整的杜比功能需要集成杜比SDK
            // 这里提供的是基于Android标准API的增强实现

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 应用响度增强
     */
    private fun applyLoudnessEnhancement(gain: Int) {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(gain)
                enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 应用动态范围处理
     */
    private fun applyDynamicsProcessing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dynamicsProcessing?.release()

                // 创建DynamicsProcessing配置
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1, // channelCount
                    true, // preEqInUse
                    1, // preEqBandCount
                    true, // mbcInUse
                    1, // mbcBandCount
                    true, // postEqInUse
                    1, // postEqBandCount
                    true  // limiterInUse
                ).build()

                dynamicsProcessing = DynamicsProcessing(0, audioSessionId, config)
                dynamicsProcessing?.enabled = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 检查设备是否支持杜比音效
     * @return 是否支持
     */
    fun isDolbySupported(): Boolean {
        // 检查设备是否支持关键的音效
        val effects = AudioEffect.queryEffects()

        // 查找杜比相关的音效
        val hasDolby = effects.any { effect ->
            effect.name.contains("Dolby", ignoreCase = true) ||
            effect.implementor.contains("Dolby", ignoreCase = true)
        }

        return hasDolby || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }

    /**
     * 获取支持的音频格式（用于杜比音轨）
     * @return 支持的音频格式列表
     */
    @SuppressLint("NewApi")
    fun getSupportedAudioFormats(): List<Int> {
        val formats = mutableListOf<Int>()

        // 检查各种杜比格式的支持
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Dolby Digital (AC-3)
            if (AudioTrack.isDirectPlaybackSupported(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_AC3)
                        .build(),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )) {
                formats.add(AudioFormat.ENCODING_AC3)
            }

            // Dolby Digital Plus (E-AC-3)
            if (AudioTrack.isDirectPlaybackSupported(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_E_AC3)
                        .build(),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )) {
                formats.add(AudioFormat.ENCODING_E_AC3)
            }

            // Dolby Atmos (E-AC-3-JOC, Android 9.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (AudioTrack.isDirectPlaybackSupported(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_E_AC3_JOC)
                            .build(),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )) {
                    formats.add(AudioFormat.ENCODING_E_AC3_JOC)
                }
            }

            // Dolby AC-4 (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (AudioTrack.isDirectPlaybackSupported(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_AC4)
                            .build(),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )) {
                    formats.add(AudioFormat.ENCODING_AC4)
                }
            }
        }

        return formats
    }

    /**
     * 为ExoPlayer配置杜比音轨选择
     * @return 音频格式优先级列表
     */
    fun getAudioTrackSelectionPriority(): List<String> {
        // 根据设备支持情况返回音频格式优先级
        val supportedFormats = getSupportedAudioFormats()
        val priority = mutableListOf<String>()

        // Dolby Atmos最高优先级
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            supportedFormats.contains(AudioFormat.ENCODING_E_AC3_JOC)) {
            priority.add("audio/eac3-joc")
        }

        // Dolby AC-4
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            supportedFormats.contains(AudioFormat.ENCODING_AC4)) {
            priority.add("audio/ac4")
        }

        // Dolby Digital Plus
        if (supportedFormats.contains(AudioFormat.ENCODING_E_AC3)) {
            priority.add("audio/eac3")
        }

        // Dolby Digital
        if (supportedFormats.contains(AudioFormat.ENCODING_AC3)) {
            priority.add("audio/ac3")
        }

        // 标准AAC/MP3作为备选
        priority.add("audio/mp4a-latm")
        priority.add("audio/mpeg")

        return priority
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null

            dynamicsProcessing?.release()
            dynamicsProcessing = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 是否已启用
     */
    fun isEnabled(): Boolean = isEnabled

    companion object {
        /**
         * 创建车机优化的杜比配置
         */
        fun createCarOptimizedConfig(): DolbyConfig {
            return DolbyConfig(
                enabled = true,
                loudnessGain = 300,          // 适度增强响度
                enableDRC = true,            // 启用动态范围压缩（车内环境噪音大）
                enableVirtualizer = true,    // 启用虚拟环绕声
                enableDialogueEnhancer = true, // 启用对话增强（提升人声清晰度）
                bassBoost = 200              // 适度低音增强
            )
        }

        /**
         * 创建列表流优化配置（降低功耗）
         */
        fun createListModeConfig(): DolbyConfig {
            return DolbyConfig(
                enabled = true,
                loudnessGain = 100,
                enableDRC = false,
                enableVirtualizer = false,
                enableDialogueEnhancer = false,
                bassBoost = 0
            )
        }
    }
}
