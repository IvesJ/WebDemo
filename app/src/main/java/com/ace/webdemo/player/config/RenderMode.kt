package com.ace.webdemo.player.config

/**
 * 视频渲染模式
 */
enum class RenderMode {
    /**
     * Canvas渲染模式
     * 适用场景: 列表流、需要高性能滚动
     * 原理: 原生解码后将帧数据传输到H5 Canvas渲染
     */
    CANVAS,

    /**
     * 同层渲染模式
     * 适用场景: 详情页、全屏播放
     * 原理: 使用SurfaceView覆盖在WebView上，位置实时同步
     */
    LAYER,

    /**
     * 自动选择模式
     * 根据播放场景自动选择最优渲染方案
     */
    AUTO
}
