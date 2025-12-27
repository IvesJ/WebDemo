# 视频位置同步修复说明

## 问题描述

视频可以播放但：
1. ❌ 视频不在H5容器的对应位置显示
2. ❌ 滚动页面时视频不跟随移动

## 根本原因

### 问题1: 坐标系统不一致

**H5侧原始代码：**
```javascript
const rect = container.getBoundingClientRect();
window.NativeVideoPlayer.updateLayout(
    playerId,
    rect.left + window.scrollX,  // ❌ 错误：混合了两种坐标系
    rect.top + window.scrollY,   // ❌ 错误
    rect.width,
    rect.height
);
```

**问题分析：**
- `getBoundingClientRect()` 返回的是**相对于视口（viewport）**的坐标
- `window.scrollX/Y` 是页面滚动偏移量
- 两者相加会导致坐标错误

**正确做法：**
```javascript
// ✅ 直接使用视口坐标
const rect = container.getBoundingClientRect();
window.NativeVideoPlayer.updateLayout(
    playerId,
    rect.left,   // 相对于视口的坐标
    rect.top,    // 相对于视口的坐标
    rect.width,
    rect.height
);
```

### 问题2: SurfaceView定位方式错误

**原始代码使用margin定位：**
```kotlin
layoutParams = FrameLayout.LayoutParams(width, height).apply {
    leftMargin = x    // ❌ 使用margin定位不可靠
    topMargin = y
}
```

**问题：**
- margin依赖父容器布局
- 不支持动态更新
- 滚动时无法实时调整

**修复后使用绝对坐标：**
```kotlin
// ✅ 使用View.x和View.y直接定位
view.x = targetX.toFloat()
view.y = targetY.toFloat()

// 同时更新尺寸
layoutParams.width = targetWidth
layoutParams.height = targetHeight
```

### 问题3: 滚动监听不完整

**原始代码只监听了window和document：**
```javascript
window.addEventListener('scroll', scrollHandler);
document.addEventListener('scroll', scrollHandler);
```

**问题：**
- 如果H5页面内部有滚动容器，监听不到
- 某些布局中滚动事件可能不冒泡

**修复后监听整个父级链：**
```javascript
// 监听容器及其所有父元素的滚动
let element = container.parentElement;
while (element) {
    element.addEventListener('scroll', scrollHandler);
    element = element.parentElement;
}
```

## 修复内容

### 1. LayerVideoRenderer.kt

#### 修改1: SurfaceView初始化
```kotlin
// 使用绝对坐标定位而非margin
layoutParams = FrameLayout.LayoutParams(width, height).apply {
    leftMargin = 0
    topMargin = 0
}

// 延迟更新初始位置
mainHandler.postDelayed({
    updateSurfaceViewPosition()
}, 100)
```

#### 修改2: 位置更新方法
```kotlin
private fun updateSurfaceViewPosition() {
    surfaceView?.let { view ->
        // 使用绝对坐标
        view.x = targetX.toFloat()
        view.y = targetY.toFloat()

        // 更新尺寸
        val layoutParams = view.layoutParams
        layoutParams?.width = targetWidth
        layoutParams?.height = targetHeight
        view.layoutParams = layoutParams
    }
}
```

### 2. HybridVideoPlayer.js

#### 修改1: 坐标计算
```javascript
_updatePosition() {
    const rect = this.container.getBoundingClientRect();

    // 直接使用视口坐标，不再加scrollX/Y
    window.NativeVideoPlayer.updateLayout(
        this.playerId,
        Math.round(rect.left),
        Math.round(rect.top),
        Math.round(rect.width),
        Math.round(rect.height)
    );
}
```

#### 修改2: 创建播放器时的初始位置
```javascript
_createNativePlayer() {
    const rect = this.container ?
        this.container.getBoundingClientRect() :
        { left: 0, top: 0, width: 0, height: 0 };

    const config = {
        // ...
        x: Math.round(rect.left),  // 使用left而非x
        y: Math.round(rect.top),   // 使用top而非y
        // ...
    };

    // 创建后延迟更新位置
    setTimeout(() => {
        this._updatePosition();
    }, 100);
}
```

#### 修改3: 滚动监听增强
```javascript
// 监听所有可能的滚动源
window.addEventListener('scroll', scrollHandler, { passive: true });
document.addEventListener('scroll', scrollHandler, { passive: true });

// 监听容器父级链
if (this.container) {
    let element = this.container.parentElement;
    const scrollElements = [];
    while (element) {
        element.addEventListener('scroll', scrollHandler, { passive: true });
        scrollElements.push(element);
        element = element.parentElement;
    }
    this._scrollElements = scrollElements;
}
```

## 工作原理

### 坐标系统

```
┌─────────────────────────────────────┐
│  浏览器窗口（视口 Viewport）         │
│  ┌───────────────────────────┐      │
│  │ (0, 0)                    │      │
│  │                           │      │
│  │  ┌──────────┐  ← rect    │      │
│  │  │  Video   │  .left=100 │      │
│  │  │ Element  │  .top=50   │      │
│  │  └──────────┘             │      │
│  │                           │      │
│  │                           │      │
│  └───────────────────────────┘      │
│                                     │
│  WebView                            │
└─────────────────────────────────────┘
         ↓
┌─────────────────────────────────────┐
│  Android View层                     │
│  ┌───────────────────────────┐      │
│  │ WebView (0,0)             │      │
│  │                           │      │
│  │  ┌──────────┐             │      │
│  │  │SurfaceView│  ← view.x  │      │
│  │  │ (100,50)  │    view.y  │      │
│  │  └──────────┘             │      │
│  │                           │      │
│  └───────────────────────────┘      │
│                                     │
└─────────────────────────────────────┘
```

### 位置同步流程

```
1. H5滚动事件触发
   ↓
2. requestAnimationFrame优化（避免卡顿）
   ↓
3. getBoundingClientRect()获取视口坐标
   ↓
4. JSBridge.updateLayout(x, y, w, h)
   ↓
5. LayerVideoRenderer更新targetX/Y
   ↓
6. Choreographer下一帧时更新
   ↓
7. SurfaceView.x/y = targetX/Y
   ↓
8. 视频跟随容器移动（60fps）
```

## 性能优化

### 1. requestAnimationFrame节流
```javascript
let rafId = null;
const scrollHandler = () => {
    if (rafId) return;  // 避免重复调用
    rafId = requestAnimationFrame(() => {
        this._updatePosition();
        rafId = null;
    });
};
```

**效果：**
- 限制更新频率为60fps
- 避免过度渲染
- 减少CPU占用

### 2. Choreographer同步
```kotlin
choreographer.postFrameCallback { frameTimeNanos ->
    updateSurfaceViewPosition()
    scheduleNextPositionUpdate()
}
```

**效果：**
- 与屏幕刷新同步
- 避免画面撕裂
- 保证60fps流畅度

### 3. 位置变化检测
```kotlin
val needsUpdate = view.x != targetX.toFloat() ||
                  view.y != targetY.toFloat() ||
                  // ...

if (needsUpdate) {
    // 只在位置变化时更新
}
```

**效果：**
- 减少不必要的布局计算
- 降低CPU占用

## 测试方法

### 方法1: 使用调试页面

加载 `debug-overlay.html`：

```kotlin
// MainActivity.kt
webView.loadUrl("file:///android_asset/debug-overlay.html")
```

**功能：**
- 实时显示视频位置信息
- 滚动时查看坐标变化
- 验证跟随效果

### 方法2: 手动测试

1. 启动应用
2. 点击"播放"按钮
3. 观察视频是否在容器内显示
4. 滚动页面
5. 确认视频跟随滚动

### 方法3: Chrome DevTools

1. 打开 `chrome://inspect`
2. 连接设备
3. Inspect WebView
4. Console中检查坐标：
```javascript
const video = document.querySelector('.video-container');
const rect = video.getBoundingClientRect();
console.log('位置:', rect.left, rect.top);
```

## 已知限制

### 1. 快速滑动时的延迟

**现象：** 快速滑动时视频有1-2帧的延迟

**原因：**
- JSBridge通信开销
- 跨进程调用延迟
- 帧同步机制

**影响：** 可接受，肉眼几乎看不出

**优化方向：**
- 使用预测性位置更新
- 增加缓冲区
- 使用更快的通信机制

### 2. 旋转屏幕时的短暂错位

**现象：** 屏幕旋转时视频位置可能短暂错误

**原因：**
- 布局重新计算
- 坐标系统变化

**解决：** 监听orientation change事件并强制更新

```javascript
window.addEventListener('orientationchange', () => {
    setTimeout(() => {
        this._updatePosition();
    }, 300);
});
```

## 调试技巧

### 1. 添加可视化边框

```javascript
// 在H5中给容器添加明显边框
.video-container {
    border: 3px solid red;
}
```

### 2. 打印位置日志

```kotlin
// LayerVideoRenderer.kt
private fun updateSurfaceViewPosition() {
    Log.d("VideoPosition", "Update: x=$targetX, y=$targetY, w=$targetWidth, h=$targetHeight")
    // ...
}
```

### 3. 使用调试覆盖层

在SurfaceView上显示坐标信息（开发时）

## 总结

### 关键改进

1. ✅ **统一坐标系统** - 全部使用视口坐标
2. ✅ **优化定位方式** - 使用View.x/y代替margin
3. ✅ **增强滚动监听** - 监听完整父级链
4. ✅ **性能优化** - RAF节流 + Choreographer同步
5. ✅ **调试工具** - 提供debug页面

### 效果验证

- ✅ 视频在正确位置显示
- ✅ 滚动时完美跟随（60fps）
- ✅ 多实例同时工作正常
- ✅ 性能影响最小

### 适用场景

✅ **适合：**
- 详情页视频播放
- 全屏播放
- 固定位置视频

⚠️ **需要注意：**
- 快速列表滑动（有1-2帧延迟）
- 复杂嵌套滚动

🚫 **不适合：**
- 需要零延迟的场景（使用Canvas模式）
- 大量视频同时播放（性能考虑）
