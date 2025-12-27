package com.ace.webdemo.player.config

import org.json.JSONObject

/**
 * 播放器配置
 */
data class PlayerConfig(
    /**
     * 播放器唯一标识
     */
    val playerId: String,

    /**
     * 渲染模式
     */
    val renderMode: RenderMode = RenderMode.AUTO,

    /**
     * 音频焦点类型
     */
    val audioFocusType: AudioFocusType = AudioFocusType.TRANSIENT,

    /**
     * 是否启用杜比音效
     */
    val enableDolby: Boolean = false,

    /**
     * 最大帧率 (fps)
     * Canvas模式下有效，用于性能优化
     */
    val maxFrameRate: Int = 60,

    /**
     * 是否启用SharedMemory优化
     * Canvas模式下使用SharedMemory传输帧数据，减少拷贝
     */
    val enableSharedMemory: Boolean = true,

    /**
     * 播放器宽度（像素）
     */
    val width: Int = 0,

    /**
     * 播放器高度（像素）
     */
    val height: Int = 0,

    /**
     * 播放器X坐标（相对WebView）
     */
    val x: Int = 0,

    /**
     * 播放器Y坐标（相对WebView）
     */
    val y: Int = 0,

    /**
     * 是否自动播放
     */
    val autoPlay: Boolean = false,

    /**
     * 是否循环播放
     */
    val loop: Boolean = false,

    /**
     * 是否静音
     */
    val muted: Boolean = false,

    /**
     * 初始音量 (0.0 - 1.0)
     */
    val volume: Float = 1.0f
) {
    companion object {
        /**
         * 从JSON创建配置
         */
        fun fromJson(json: JSONObject): PlayerConfig {
            return PlayerConfig(
                playerId = json.optString("playerId", ""),
                renderMode = parseRenderMode(json.optString("renderMode", "auto")),
                audioFocusType = parseAudioFocusType(json.optString("audioFocusType", "transient")),
                enableDolby = json.optBoolean("enableDolby", false),
                maxFrameRate = json.optInt("maxFrameRate", 60),
                enableSharedMemory = json.optBoolean("enableSharedMemory", true),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                x = json.optInt("x", 0),
                y = json.optInt("y", 0),
                autoPlay = json.optBoolean("autoPlay", false),
                loop = json.optBoolean("loop", false),
                muted = json.optBoolean("muted", false),
                volume = json.optDouble("volume", 1.0).toFloat()
            )
        }

        private fun parseRenderMode(mode: String): RenderMode {
            return when (mode.lowercase()) {
                "canvas" -> RenderMode.CANVAS
                "layer" -> RenderMode.LAYER
                "auto" -> RenderMode.AUTO
                else -> RenderMode.AUTO
            }
        }

        private fun parseAudioFocusType(type: String): AudioFocusType {
            return when (type.lowercase()) {
                "gain" -> AudioFocusType.GAIN
                "transient" -> AudioFocusType.TRANSIENT
                "transient_may_duck" -> AudioFocusType.TRANSIENT_MAY_DUCK
                "exclusive" -> AudioFocusType.EXCLUSIVE
                else -> AudioFocusType.TRANSIENT
            }
        }
    }

    /**
     * 转换为JSON
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("playerId", playerId)
            put("renderMode", renderMode.name.lowercase())
            put("audioFocusType", audioFocusType.name.lowercase())
            put("enableDolby", enableDolby)
            put("maxFrameRate", maxFrameRate)
            put("enableSharedMemory", enableSharedMemory)
            put("width", width)
            put("height", height)
            put("x", x)
            put("y", y)
            put("autoPlay", autoPlay)
            put("loop", loop)
            put("muted", muted)
            put("volume", volume)
        }
    }
}
