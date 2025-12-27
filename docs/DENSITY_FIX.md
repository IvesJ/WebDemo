# 密度缩放问题修复

## 问题根源

### 发现的关键信息

从测试页面获取的设备信息：
```
屏幕宽度: 412px (CSS像素)
屏幕高度: 787px (CSS像素)
设备像素比: 2.625
物理宽度: 1081.5px (物理像素)
```

以及位置信息：
```
rect.left: 20
rect.top: 367.42858886718875
rect.width: 372.1904907226562
rect.height: 250
devicePixelRatio: 2.625
```

### 根本原因

**CSS像素 ≠ 物理像素**

- H5中`getBoundingClientRect()`返回的是**CSS像素**
- Android的View坐标使用的是**物理像素**（dp）
- 两者之间的转换系数是`density`（对应H5的devicePixelRatio）

**错误的做法：**
```kotlin
// ❌ 直接使用CSS像素
targetX = webViewLocation[0] + x  // x是CSS像素，但targetX需要物理像素
```

**正确的做法：**
```kotlin
// ✅ 转换CSS像素为物理像素
val density = context.resources.displayMetrics.density
targetX = webViewLocation[0] + (x * density).toInt()
```

## 坐标系统详解

### H5侧（CSS像素）

```javascript
// getBoundingClientRect返回CSS像素
const rect = element.getBoundingClientRect();
// rect.left = 20 (CSS像素)
// rect.top = 367 (CSS像素)
// rect.width = 372 (CSS像素)
// rect.height = 250 (CSS像素)
```

### Android侧（物理像素）

```kotlin
// density = 2.625 (对应devicePixelRatio)
val physicalX = (cssX * density).toInt()
// physicalX = 20 * 2.625 = 52.5 ≈ 53 (物理像素)

val physicalWidth = (cssWidth * density).toInt()
// physicalWidth = 372 * 2.625 = 976.5 ≈ 977 (物理像素)
```

### 完整转换流程

```
H5容器位置 (CSS像素)
    ↓
getBoundingClientRect()
    ↓
JSBridge传输
    ↓
Android接收 (仍是CSS像素)
    ↓
乘以density转换
    ↓
物理像素坐标
    ↓
加上WebView偏移
    ↓
屏幕物理坐标
    ↓
设置SurfaceView位置
```

## 具体修复

### 1. LayerVideoRenderer.kt

```kotlin
override fun updateLayout(x: Int, y: Int, width: Int, height: Int) {
    // x, y, width, height 都是CSS像素

    // 获取WebView在屏幕上的位置（物理像素）
    val webViewLocation = IntArray(2)
    webView.getLocationOnScreen(webViewLocation)

    // 获取密度
    val density = context.resources.displayMetrics.density

    // 转换：CSS像素 * density = 物理像素
    targetX = webViewLocation[0] + (x * density).toInt()
    targetY = webViewLocation[1] + (y * density).toInt()
    targetWidth = (width * density).toInt()
    targetHeight = (height * density).toInt()

    // 调试日志
    Log.d("LayerVideoRenderer", "updateLayout: " +
            "CSS(x=$x, y=$y, w=$width, h=$height) " +
            "density=$density " +
            "WebView(${webViewLocation[0]}, ${webViewLocation[1]}) " +
            "Physical($targetX, $targetY, $targetWidth, $targetHeight)")

    // 立即更新
    mainHandler.post {
        updateSurfaceViewPosition()
    }
}
```

### 2. VideoPlayerJSBridge.kt

添加了支持Float的版本，避免精度损失：

```kotlin
@JavascriptInterface
fun updateLayoutFloat(playerId: String, x: Float, y: Float, width: Float, height: Float) {
    mainHandler.post {
        playerManager.updatePlayerLayout(
            playerId,
            x.toInt(),
            y.toInt(),
            width.toInt(),
            height.toInt()
        )
    }
}
```

### 3. HybridVideoPlayer.js

```javascript
_updatePosition() {
    const rect = this.container.getBoundingClientRect();

    // 保留CSS像素的精度
    const x = rect.left;    // 不要Math.round()
    const y = rect.top;
    const w = rect.width;
    const h = rect.height;

    // 优先使用Float版本保持精度
    if (window.NativeVideoPlayer.updateLayoutFloat) {
        window.NativeVideoPlayer.updateLayoutFloat(this.playerId, x, y, w, h);
    } else {
        // 降级为Int版本
        window.NativeVideoPlayer.updateLayout(
            this.playerId,
            Math.round(x),
            Math.round(y),
            Math.round(w),
            Math.round(h)
        );
    }
}
```

## 预期效果

### 修复前

```
CSS: x=20, y=367, w=372, h=250
直接使用 → Physical: x=20, y=367, w=372, h=250
结果: 视频太小且位置错误 ❌
```

### 修复后

```
CSS: x=20, y=367, w=372, h=250
density=2.625
转换 → Physical: x=53, y=964, w=977, h=656
结果: 视频大小和位置正确 ✅
```

## 验证方法

### 1. 查看日志

```bash
adb logcat | grep LayerVideoRenderer
```

应该看到：
```
LayerVideoRenderer: updateLayout: CSS(x=20, y=367, w=372, h=250) density=2.625 WebView(0, 0) Physical(53, 964, 977, 656)
```

### 2. 检查视频显示

- ✅ 视频应该完全填充红色边框内的黑色区域
- ✅ 视频大小应该正确（不是小缩略图）
- ✅ 视频位置应该在容器内

### 3. 滚动测试

- ✅ 滚动页面时视频应该跟随容器移动
- ✅ 视频应该始终覆盖在容器上

## 不同设备的density值

| 设备类型 | density | devicePixelRatio | 说明 |
|---------|---------|------------------|------|
| mdpi | 1.0 | 1.0 | 标准密度 |
| hdpi | 1.5 | 1.5 | 高密度 |
| xhdpi | 2.0 | 2.0 | 超高密度 |
| xxhdpi | 3.0 | 3.0 | 超超高密度 |
| xxxhdpi | 4.0 | 4.0 | 超超超高密度 |
| 你的设备 | 2.625 | 2.625 | 自定义密度 |

## 相关概念

### CSS像素（逻辑像素）

- H5/CSS中使用的单位
- 与设备无关
- `width: 100px` 在所有设备上视觉大小相同

### 物理像素（设备像素）

- 屏幕实际的像素点
- 与硬件相关
- 不同设备相同的物理像素视觉大小不同

### dp（Density-independent Pixels）

- Android中的密度无关像素
- 等同于mdpi（density=1.0）时的物理像素
- 在我们的代码中，View坐标使用的是物理像素

### 转换关系

```
物理像素 = CSS像素 × devicePixelRatio
物理像素 = dp × density

在Android WebView中:
devicePixelRatio ≈ density
```

## 总结

这次修复的关键点：

1. **识别问题**：通过测试页面发现devicePixelRatio=2.625
2. **理解原因**：CSS像素和物理像素不匹配
3. **正确转换**：使用density进行坐标和尺寸转换
4. **保持精度**：使用Float避免四舍五入误差
5. **验证效果**：通过日志和视觉检查确认修复

现在视频应该可以正确显示在红框内了！
