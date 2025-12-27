package com.ace.webdemo

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.ace.webdemo.player.HybridVideoPlayerManager
import com.ace.webdemo.player.bridge.VideoPlayerJSBridge

/**
 * 主Activity
 * 展示混合视频播放器功能
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var playerManager: HybridVideoPlayerManager
    private lateinit var jsBridge: VideoPlayerJSBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 创建WebView
        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        setContentView(webView)

        // 初始化播放器管理器
        playerManager = HybridVideoPlayerManager.getInstance(this)
        playerManager.setWebView(webView)

        // 配置WebView
        configureWebView()

        // 设置JSBridge
        setupJSBridge()

        // 加载演示页面
        webView.loadUrl("file:///android_asset/demo.html")
//        webView.loadUrl("file:///android_asset/test-simple.html")
    }

    /**
     * 配置WebView设置
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            // 启用JavaScript
            javaScriptEnabled = true

            // 启用DOM Storage
            domStorageEnabled = true

            // 启用数据库
            databaseEnabled = true

            // 设置缓存模式
            cacheMode = WebSettings.LOAD_DEFAULT

            // 启用硬件加速
            setRenderPriority(WebSettings.RenderPriority.HIGH)

            // 支持缩放
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // 适配屏幕
            useWideViewPort = true
            loadWithOverviewMode = true

            // 允许访问文件
            allowFileAccess = true
            allowContentAccess = true

            // 支持多窗口
            setSupportMultipleWindows(false)

            // 启用混合内容（HTTPS页面中的HTTP内容）
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // 设置User-Agent
            userAgentString = "$userAgentString HybridVideoPlayer/1.0"
        }

        // 启用硬件加速
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // 设置WebViewClient（可选）
        webView.webViewClient = android.webkit.WebViewClient()

        // 设置WebChromeClient（可选，用于支持console.log等）
        webView.webChromeClient = android.webkit.WebChromeClient()
    }

    /**
     * 设置JSBridge
     */
    private fun setupJSBridge() {
        jsBridge = VideoPlayerJSBridge(webView, playerManager)

        // 注入JSBridge到WebView
        webView.addJavascriptInterface(jsBridge, "NativeVideoPlayer")
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()

        // 暂停所有播放器
        playerManager.pauseAllPlayers()
    }

    override fun onDestroy() {
        super.onDestroy()

        // 销毁所有播放器
        playerManager.destroyAllPlayers()

        // 销毁WebView
        webView.removeAllViews()
        webView.destroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
