# Canvas渲染模式实现说明

## 当前状态

✅ **Layer渲染模式**: 已完成，可正常使用
🚧 **Canvas渲染模式**: 框架已完成，但视频帧提取部分需要进一步实现

## 问题说明

Canvas渲染模式当前只能听到声音，但看不到画面。原因是：

1. **ExoPlayer解码正常** - 音频可以播放
2. **帧提取未实现** - 没有将视频帧数据提取并传输到H5
3. **Canvas渲染准备就绪** - H5侧的Canvas渲染代码已完成，等待接收帧数据

## 临时解决方案

当前列表流已改为使用**Layer渲染模式**，可以正常显示视频画面。

## Canvas渲染的技术挑战

### 挑战1: 视频帧提取

ExoPlayer不直接提供获取解码后帧数据的API，需要通过以下方式之一：

#### 方案A: SurfaceTexture + OpenGL ES（推荐）

```kotlin
// 1. 创建SurfaceTexture
val surfaceTexture = SurfaceTexture(textureId)
val surface = Surface(surfaceTexture)

// 2. 设置给ExoPlayer
player.setVideoSurface(surface)

// 3. 监听帧更新
surfaceTexture.setOnFrameAvailableListener { texture ->
    // 使用OpenGL ES读取纹理数据
    GLES20.glReadPixels(...)
}
```

**优点**: 性能好，GPU加速
**缺点**: 需要OpenGL ES编程

#### 方案B: MediaCodec直接解码

```kotlin
val decoder = MediaCodec.createDecoderByType("video/avc")
decoder.configure(format, surface, null, 0)

decoder.setCallback(object : MediaCodec.Callback() {
    override fun onOutputBufferAvailable(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo
    ) {
        // 获取YUV数据
        val buffer = codec.getOutputBuffer(index)
        // 转换为RGB
    }
})
```

**优点**: 直接访问原始数据
**缺点**: 需要重新实现播放器逻辑

#### 方案C: PixelCopy API (Android 8.0+)

```kotlin
val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

PixelCopy.request(
    surface,
    bitmap,
    { copyResult ->
        if (copyResult == PixelCopy.SUCCESS) {
            // 将bitmap转换为字节数组发送到H5
        }
    },
    handler
)
```

**优点**: API简单
**缺点**: 性能较差，最低API 26

### 挑战2: 数据传输

视频帧数据量大，传输方式影响性能：

#### 当前方案: Base64编码（已实现）

```kotlin
val base64Data = Base64.getEncoder().encodeToString(frameData)
webView.evaluateJavascript("onFrame('$base64Data')", null)
```

**优点**: 实现简单
**缺点**:
- 数据膨胀33%
- 编码/解码开销大
- 1080p @ 30fps ≈ 60MB/s数据量

#### 优化方案: SharedMemory

```kotlin
// 创建共享内存
val sharedMemory = SharedMemory.create("video_frame", frameSize)
val buffer = sharedMemory.mapReadWrite()

// 写入帧数据
buffer.put(frameData)

// H5侧直接读取（需要特殊桥接）
```

**优点**: 零拷贝，性能好
**缺点**: 需要Native层支持，H5无法直接访问

#### 优化方案: WebGL纹理共享

```kotlin
// 共享OpenGL纹理ID到H5
webView.evaluateJavascript(
    "updateTexture($textureId, $width, $height)",
    null
)
```

```javascript
// H5侧使用WebGL渲染
const gl = canvas.getContext('webgl');
gl.bindTexture(gl.TEXTURE_EXTERNAL_OES, nativeTextureId);
```

**优点**: 零拷贝，最优性能
**缺点**: 实现最复杂，需要跨进程纹理共享

### 挑战3: 帧率与性能

Canvas模式的性能考虑：

| 分辨率 | 帧率 | 数据量/秒 | 传输开销 |
|--------|------|-----------|----------|
| 360p | 30fps | ~15MB/s | 中 |
| 720p | 30fps | ~60MB/s | 高 |
| 1080p | 30fps | ~140MB/s | 极高 |

**优化策略**:
1. 降低分辨率（缩放到720p或更低）
2. 降低帧率（列表流15-20fps足够）
3. 只传输可见区域的视频
4. 快速滚动时暂停传输

## 完整实现方案（推荐）

### 方案: SurfaceTexture + PixelCopy + 降采样

这是性能和实现复杂度的最佳平衡：

```kotlin
class CanvasVideoRenderer {
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface
    private val handler = Handler()

    fun initialize() {
        // 1. 创建Surface
        surfaceTexture = SurfaceTexture(0)
        surfaceTexture.setDefaultBufferSize(640, 360) // 降分辨率
        surface = Surface(surfaceTexture)

        // 2. 设置到ExoPlayer
        player.setVideoSurface(surface)

        // 3. 定时提取帧（15fps）
        handler.postDelayed(captureFrame, 66) // 66ms ≈ 15fps
    }

    private val captureFrame = Runnable {
        if (!isPlaying) return

        // 创建小尺寸bitmap
        val bitmap = Bitmap.createBitmap(640, 360, ARGB_8888)

        // 使用PixelCopy获取当前帧
        PixelCopy.request(surface, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                // 转换为JPEG压缩（减小数据量）
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                val jpegData = stream.toByteArray()

                // Base64编码发送
                val base64 = Base64.encodeToString(jpegData, Base64.NO_WRAP)
                sendFrameToJS(base64, 640, 360)
            }

            bitmap.recycle()

            // 继续下一帧
            handler.postDelayed(this, 66)
        }, handler)
    }
}
```

```javascript
// H5侧接收和渲染
function onFrameReceived(base64Data, width, height) {
    const img = new Image();
    img.onload = () => {
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    };
    img.src = 'data:image/jpeg;base64,' + base64Data;
}
```

**性能估算**:
- 640x360 @ 15fps
- JPEG压缩后 ~10KB/帧
- 数据量: 150KB/s ≈ 1.2Mbps
- 可接受的传输开销

## 实现步骤

### Step 1: 基础帧提取（已完成）
- ✅ 创建VideoFrameExtractor框架
- ✅ SurfaceTexture初始化

### Step 2: PixelCopy实现
```kotlin
// 在VideoFrameExtractor.kt中实现
private fun extractFrameWithPixelCopy() {
    val bitmap = Bitmap.createBitmap(width, height, ARGB_8888)

    PixelCopy.request(surface!!, bitmap, { result ->
        if (result == PixelCopy.SUCCESS) {
            val jpegData = bitmapToJpeg(bitmap)
            onFrameExtracted(jpegData, width, height, timestamp)
        }
        bitmap.recycle()
    }, handler)
}
```

### Step 3: 集成到CanvasVideoRenderer
```kotlin
class CanvasVideoRenderer {
    private val frameExtractor = VideoFrameExtractor(
        maxFrameRate = config.maxFrameRate
    ) { frameData, width, height, timestamp ->
        // 发送到H5
        eventListener?.onFrameRendered(frameData, width, height, timestamp)
    }

    override fun prepare(url: String) {
        // ...初始化ExoPlayer
        val surface = frameExtractor.initialize(0)
        exoPlayer?.setVideoSurface(surface)
        frameExtractor.start()
    }
}
```

### Step 4: 优化H5侧渲染
```javascript
_onFrameRendered(frameData, width, height, timestamp) {
    // 将JPEG Base64转换为Image
    const img = new Image();
    img.onload = () => {
        // 渲染到Canvas
        this.ctx.drawImage(img, 0, 0,
            this.canvas.width, this.canvas.height);
        this._emit('framerendered', timestamp);
    };
    img.src = 'data:image/jpeg;base64,' + frameData;
}
```

## 性能优化清单

- [ ] 降低分辨率（640x360或更低）
- [ ] 降低帧率（15fps）
- [ ] JPEG压缩（质量75）
- [ ] 快速滚动时暂停传输
- [ ] 视频不可见时停止提取
- [ ] 使用Web Worker解码（避免阻塞主线程）
- [ ] Canvas离屏渲染（OffscreenCanvas）

## 测试计划

1. **功能测试**
   - [ ] 视频可以显示
   - [ ] 音画同步
   - [ ] 暂停/恢复正常
   - [ ] 多实例同时播放

2. **性能测试**
   - [ ] CPU占用 < 30%
   - [ ] 内存占用 < 100MB
   - [ ] 电量消耗可接受
   - [ ] 滚动流畅（60fps）

3. **兼容性测试**
   - [ ] Android 9/10/11/12测试
   - [ ] 不同分辨率设备
   - [ ] 车机环境测试

## 当前建议

**短期（立即可用）**:
- ✅ 使用Layer渲染模式
- 功能完整，性能好
- 适合大部分场景

**中期（1-2周开发）**:
- 实现基础Canvas渲染
- 使用PixelCopy + JPEG方案
- 适合列表流场景

**长期（1-2月优化）**:
- WebGL纹理共享
- GPU加速处理
- 零拷贝传输
- 极致性能

## 参考资源

- [ExoPlayer官方文档](https://exoplayer.dev/)
- [PixelCopy API](https://developer.android.com/reference/android/view/PixelCopy)
- [SurfaceTexture使用](https://developer.android.com/reference/android/graphics/SurfaceTexture)
- [Canvas性能优化](https://developer.mozilla.org/en-US/docs/Web/API/Canvas_API/Tutorial/Optimizing_canvas)

## 总结

Canvas渲染是一个复杂的功能，需要权衡：
- **性能**: 数据传输开销大
- **复杂度**: 需要OpenGL/PixelCopy等知识
- **兼容性**: 不同Android版本API差异

**建议**:
1. 当前使用Layer模式（已完美工作）
2. 根据实际需求决定是否需要Canvas模式
3. 如果确实需要，按照上述方案实现

Layer模式在大多数车机场景下性能已经足够好，Canvas模式更适合特殊需求（如需要对视频画面进行实时处理）。
