# 视频位置问题诊断指南

## 当前问题

从截图看到：
- ✅ 视频可以播放（有声音，能看到小缩略图）
- ❌ 视频位置错误（在左上角而不是H5容器内）
- ❌ 视频尺寸不对（很小的缩略图，而不是填充容器）

## 已添加的修复

### 1. 坐标转换
```kotlin
// 获取WebView在屏幕上的位置
val webViewLocation = IntArray(2)
webView.getLocationOnScreen(webViewLocation)

// H5坐标 + WebView偏移 = 屏幕坐标
targetX = webViewLocation[0] + x
targetY = webViewLocation[1] + y
```

### 2. 调试日志

已添加详细日志，安装新版本后查看：

```bash
# 查看位置更新日志
adb logcat | grep LayerVideoRenderer

# 查看H5日志
adb logcat | grep chromium
```

或者在Chrome DevTools中查看Console输出。

## 如何诊断

### 步骤1: 安装新版本

```bash
./gradlew installDebug
```

### 步骤2: 查看日志

打开应用后，在终端运行：

```bash
adb logcat -c  # 清空日志
adb logcat | grep -E "LayerVideoRenderer|updatePosition"
```

你会看到类似这样的输出：

```
LayerVideoRenderer: updateLayout: H5(x=20, y=150, w=480, h=200) WebView(0, 100) Screen(20, 250, 480, 200)
```

这告诉我们：
- `H5(x=20, y=150, w=480, h=200)` - H5传来的坐标（视口坐标）
- `WebView(0, 100)` - WebView在屏幕上的位置
- `Screen(20, 250, 480, 200)` - 最终计算的屏幕坐标

### 步骤3: 使用Chrome DevTools

1. 打开Chrome浏览器，访问 `chrome://inspect`
2. 找到你的设备和WebView
3. 点击"inspect"
4. 查看Console中的日志：

```
[player_xxx] updatePosition: x=20, y=150, w=480, h=200, scrollY=0, devicePixelRatio=2
```

### 步骤4: 对比数据

检查以下信息：
- devicePixelRatio是多少？（通常是1.0、2.0或3.0）
- WebView的位置是否正确？
- 计算出的屏幕坐标是否合理？

## 可能的问题和解决方案

### 问题1: DPI缩放

如果`devicePixelRatio=2`或`3`，可能需要缩放：

```kotlin
val scale = context.resources.displayMetrics.density
targetX = webViewLocation[0] + (x * scale).toInt()
targetY = webViewLocation[1] + (y * scale).toInt()
targetWidth = (width * scale).toInt()
targetHeight = (height * scale).toInt()
```

### 问题2: WebView缩放

如果WebView设置了缩放，需要考虑：

```kotlin
val webViewScale = webView.scale
targetX = webViewLocation[0] + (x * webViewScale).toInt()
// ...
```

### 问题3: 视口meta标签

检查H5是否设置了viewport：

```html
<meta name="viewport" content="width=device-width, initial-scale=1.0">
```

### 问题4: WebView设置

确保WebView没有额外的缩放：

```kotlin
webView.settings.apply {
    useWideViewPort = true
    loadWithOverviewMode = true
    // 不要使用缩放
    setSupportZoom(false)
}
```

## 测试用的简单页面

创建一个简单的测试页面来诊断：

```html
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
        body { margin: 0; padding: 20px; }
        .test-box {
            width: 300px;
            height: 200px;
            background: red;
            border: 3px solid yellow;
            margin-top: 100px;
        }
    </style>
</head>
<body>
    <h1>位置测试</h1>
    <div id="test-box" class="test-box"></div>

    <script src="js/HybridVideoPlayer.js"></script>
    <script>
        const player = new HybridVideoPlayer({
            containerId: 'test-box',
            renderMode: 'layer'
        });

        player.play('https://www.w3schools.com/html/mov_bbb.mp4');

        // 每秒打印一次位置信息
        setInterval(() => {
            const box = document.getElementById('test-box');
            const rect = box.getBoundingClientRect();
            console.log('Box position:', {
                left: rect.left,
                top: rect.top,
                width: rect.width,
                height: rect.height,
                devicePixelRatio: window.devicePixelRatio,
                innerWidth: window.innerWidth,
                innerHeight: window.innerHeight
            });
        }, 1000);
    </script>
</body>
</html>
```

保存为 `test-position.html`，在MainActivity中加载：

```kotlin
webView.loadUrl("file:///android_asset/test-position.html")
```

## 预期的日志输出

正确时应该看到：

```
# H5侧
[player_xxx] updatePosition: x=20, y=120, w=300, h=200

# Native侧
LayerVideoRenderer: updateLayout: H5(x=20, y=120, w=300, h=200) WebView(0, 0) Screen(20, 120, 300, 200)
```

如果看到：

```
# 视频太小
H5(x=20, y=120, w=300, h=200) Screen(10, 60, 150, 100)  # ❌ 缩小了一半

# 位置不对
H5(x=20, y=120, w=300, h=200) Screen(20, 120, 300, 200)  # ✅ 但视频不在这个位置
```

说明可能是：
1. DPI缩放问题
2. SurfaceView的父容器不对
3. 坐标系统理解错误

## 下一步调试

请：
1. 安装新版本APK
2. 运行 `adb logcat | grep LayerVideoRenderer`
3. 打开Chrome DevTools查看Console
4. 把日志输出发给我

这样我就能准确知道问题在哪里并修复！
