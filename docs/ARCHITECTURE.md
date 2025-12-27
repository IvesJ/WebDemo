# 车机H5视频播放器架构设计

## 1. 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                        H5 Layer                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  HybridVideoPlayer.js (H5 SDK)                       │   │
│  │  - Canvas渲染器                                       │   │
│  │  - 视频元素管理                                       │   │
│  │  - 播放控制接口                                       │   │
│  └──────────────────────────────────────────────────────┘   │
│                           ↕ JSBridge                         │
└─────────────────────────────────────────────────────────────┘
                             ↕
┌─────────────────────────────────────────────────────────────┐
│                      Native Layer                            │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  HybridVideoPlayerManager (统一管理)                 │   │
│  │  - 播放器实例管理                                     │   │
│  │  - 策略选择 (Canvas/同层)                            │   │
│  │  - 生命周期管理                                       │   │
│  └──────────────────────────────────────────────────────┘   │
│                             ↓                                │
│  ┌─────────────────────┐        ┌──────────────────────┐    │
│  │ CanvasVideoRenderer │        │ LayerVideoRenderer   │    │
│  │ (方案B: Canvas渲染) │        │ (方案A: 同层渲染)    │    │
│  │ - MediaCodec解码    │        │ - SurfaceView覆盖    │    │
│  │ - 帧数据传输        │        │ - 位置同步           │    │
│  │ - SharedMemory优化  │        │ - 硬件加速           │    │
│  └─────────────────────┘        └──────────────────────┘    │
│                             ↓                                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  AudioManager (音频管理)                             │   │
│  │  - AudioFocus管理                                    │   │
│  │  - 杜比音效处理                                       │   │
│  │  - 音量控制                                           │   │
│  └──────────────────────────────────────────────────────┘   │
│                             ↓                                │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ExoPlayer (底层播放器)                              │   │
│  │  - 视频解码                                           │   │
│  │  - 音频解码                                           │   │
│  │  - 网络缓冲                                           │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## 2. 模块划分

### 2.1 核心模块

#### HybridVideoPlayerManager
- **职责**: 播放器总控制器
- **功能**:
  - 管理所有播放器实例
  - 根据场景选择渲染策略
  - 统一对外接口
  - 生命周期管理

#### CanvasVideoRenderer (方案B)
- **职责**: Canvas渲染实现
- **功能**:
  - MediaCodec视频解码
  - YUV到RGB转换
  - 帧数据传输到H5
  - 性能优化(SharedMemory)
- **适用场景**: 列表流、需要高性能滚动

#### LayerVideoRenderer (方案A)
- **职责**: 同层渲染实现
- **功能**:
  - SurfaceView管理
  - 位置实时同步
  - 硬件加速渲染
- **适用场景**: 详情页、全屏播放

#### AudioFocusManager
- **职责**: 音频焦点管理
- **功能**:
  - AudioFocus请求/释放
  - 焦点变化监听
  - 多播放器焦点协调

#### DolbyAudioProcessor
- **职责**: 杜比音效处理
- **功能**:
  - Dolby Atmos支持
  - 音效参数配置
  - 音轨选择

### 2.2 通信模块

#### JSBridge
- **职责**: Native与H5通信
- **功能**:
  - 方法调用(play/pause/seek等)
  - 事件回调(播放状态/进度等)
  - 帧数据传输

### 2.3 H5模块

#### HybridVideoPlayer.js
- **职责**: H5侧SDK
- **功能**:
  - Canvas元素管理
  - 帧渲染
  - 播放控制API
  - 事件监听

## 3. 数据流

### 3.1 Canvas渲染流程

```
ExoPlayer → MediaCodec解码 → YUV Buffer
                                ↓
                          YUV转RGB (Native)
                                ↓
                          SharedMemory写入
                                ↓
                    JSBridge.sendFrame(frameData)
                                ↓
                      H5接收帧数据 (Uint8Array)
                                ↓
                    Canvas ImageData渲染
```

### 3.2 同层渲染流程

```
ExoPlayer → Surface → SurfaceView
                            ↓
                    H5滚动事件监听
                            ↓
                JSBridge.updatePosition(x,y,w,h)
                            ↓
                    Native更新SurfaceView位置
                            ↓
                  Choreographer同步刷新
```

### 3.3 音频焦点流程

```
H5请求播放 → JSBridge.play()
                    ↓
            AudioFocusManager.requestFocus()
                    ↓
            配置Dolby音效参数
                    ↓
            ExoPlayer开始播放
                    ↓
            回调播放状态到H5
```

## 4. 关键技术点

### 4.1 Canvas渲染优化
- 使用SharedMemory减少数据拷贝
- 使用ArrayBuffer传输，避免Base64编码
- 根据滚动速度动态调整帧率
- 后台时停止解码

### 4.2 同层渲染优化
- 使用Choreographer同步刷新
- 硬件加速绘制
- 预测性位置更新
- IntersectionObserver监听可见性

### 4.3 性能优化策略
- 列表流使用Canvas(流畅度优先)
- 详情页使用同层(画质优先)
- 快速滑动时降帧
- 视频回收池复用

### 4.4 音频焦点策略
```kotlin
// 不同场景的焦点类型
AUDIOFOCUS_GAIN              // 长时间播放(详情页)
AUDIOFOCUS_GAIN_TRANSIENT    // 短暂播放(列表预览)
AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK  // 可降低音量的播放
```

## 5. 接口设计

### 5.1 H5侧接口

```javascript
// 创建播放器实例
const player = new HybridVideoPlayer({
    containerId: 'video-container',
    renderMode: 'auto', // 'canvas' | 'layer' | 'auto'
    audioFocusType: 'transient'
});

// 播放控制
player.play(url);
player.pause();
player.seek(position);
player.setVolume(0.8);

// 事件监听
player.on('ready', () => {});
player.on('playing', () => {});
player.on('paused', () => {});
player.on('ended', () => {});
player.on('error', (error) => {});
player.on('progress', (position, duration) => {});

// 销毁
player.destroy();
```

### 5.2 Native侧接口

```kotlin
// 播放器管理器
class HybridVideoPlayerManager {
    fun createPlayer(playerId: String, config: PlayerConfig): VideoRenderer
    fun destroyPlayer(playerId: String)
    fun play(playerId: String, url: String)
    fun pause(playerId: String)
    fun seek(playerId: String, position: Long)
}

// 渲染器接口
interface VideoRenderer {
    fun prepare(url: String)
    fun play()
    fun pause()
    fun seek(position: Long)
    fun release()
    fun updateLayout(x: Int, y: Int, width: Int, height: Int)
}
```

## 6. 配置参数

```kotlin
data class PlayerConfig(
    val playerId: String,
    val renderMode: RenderMode,        // CANVAS, LAYER, AUTO
    val audioFocusType: AudioFocusType, // GAIN, TRANSIENT, TRANSIENT_MAY_DUCK
    val enableDolby: Boolean = false,
    val maxFrameRate: Int = 60,
    val enableSharedMemory: Boolean = true
)

enum class RenderMode {
    CANVAS,  // Canvas渲染
    LAYER,   // 同层渲染
    AUTO     // 自动选择
}
```

## 7. 目录结构

```
app/src/main/java/com/ace/webdemo/
├── player/
│   ├── HybridVideoPlayerManager.kt       # 播放器管理器
│   ├── config/
│   │   ├── PlayerConfig.kt              # 配置类
│   │   └── RenderMode.kt                # 渲染模式枚举
│   ├── renderer/
│   │   ├── VideoRenderer.kt             # 渲染器接口
│   │   ├── CanvasVideoRenderer.kt       # Canvas渲染实现
│   │   └── LayerVideoRenderer.kt        # 同层渲染实现
│   ├── decoder/
│   │   ├── VideoDecoder.kt              # 视频解码器
│   │   └── FrameConverter.kt            # 帧格式转换
│   ├── audio/
│   │   ├── AudioFocusManager.kt         # 音频焦点管理
│   │   └── DolbyAudioProcessor.kt       # 杜比音效处理
│   └── bridge/
│       └── VideoPlayerJSBridge.kt       # JSBridge通信
└── webview/
    └── HybridWebView.kt                 # 自定义WebView

app/src/main/assets/
└── js/
    └── HybridVideoPlayer.js             # H5侧SDK
```

## 8. 开发阶段

### Phase 1: 核心框架
- HybridVideoPlayerManager
- VideoRenderer接口
- JSBridge通信层

### Phase 2: Canvas渲染
- CanvasVideoRenderer
- VideoDecoder
- 帧数据传输

### Phase 3: 同层渲染
- LayerVideoRenderer
- 位置同步机制

### Phase 4: 音频管理
- AudioFocusManager
- DolbyAudioProcessor

### Phase 5: H5 SDK
- HybridVideoPlayer.js
- 示例页面

### Phase 6: 优化与测试
- 性能优化
- 兼容性测试
- 车机适配
