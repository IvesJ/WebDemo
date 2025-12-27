# 测试视频资源

## 当前使用的视频源

### 主要视频源（时光网）
```
http://vfx.mtime.cn/Video/2019/03/19/mp4/190319222227698228.mp4
http://vfx.mtime.cn/Video/2019/03/21/mp4/190321153853126488.mp4
http://vfx.mtime.cn/Video/2019/03/19/mp4/190319212559089721.mp4
```

**特点**：
- 国内访问速度快
- 视频质量好
- 适合测试

## 备用视频源

### 1. W3Schools
```
https://www.w3schools.com/html/mov_bbb.mp4
https://www.w3schools.com/html/movie.mp4
```

**特点**：
- 稳定可靠
- 文件较小
- 适合快速测试

### 2. Big Buck Bunny (开源视频)
```
https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4
https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_5MB.mp4
https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_10MB.mp4
```

**特点**：
- 多种分辨率
- 文件大小可选
- 适合性能测试

### 3. Sintel (开源电影片段)
```
https://media.w3.org/2010/05/sintel/trailer.mp4
https://media.w3.org/2010/05/bunny/trailer.mp4
```

**特点**：
- W3C官方测试视频
- 高质量
- 权威来源

### 4. Sample Videos (多种格式)
```
http://techslides.com/demos/sample-videos/small.mp4
http://techslides.com/demos/sample-videos/small.webm
http://techslides.com/demos/sample-videos/small.ogv
```

**特点**：
- 文件极小（<1MB）
- 多种格式
- 快速加载

### 5. Apple HLS 测试流
```
https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8
https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8
```

**特点**：
- HLS流媒体
- 自适应码率
- 测试流媒体播放

### 6. DASH 测试流
```
https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd
https://bitdash-a.akamaihd.net/content/MI201109210084_1/mpds/f08e80da-bf1d-4e3d-8899-f0f6155f6efa.mpd
```

**特点**：
- DASH流媒体
- 适合测试ExoPlayer
- 支持多种分辨率

## 如何更换视频源

### 方法1: 修改demo.html
打开 `app/src/main/assets/demo.html`，找到：

```javascript
const TEST_VIDEO_URL = 'your_video_url_here';
```

替换为任意上述视频URL。

### 方法2: 使用本地视频
1. 将视频文件放到 `app/src/main/assets/videos/` 目录
2. 修改URL为：
```javascript
const TEST_VIDEO_URL = 'file:///android_asset/videos/your_video.mp4';
```

### 方法3: 动态配置
在H5中添加输入框让用户自己输入视频URL：

```html
<input type="text" id="videoUrl" placeholder="输入视频URL">
<button onclick="playCustomVideo()">播放</button>

<script>
function playCustomVideo() {
    const url = document.getElementById('videoUrl').value;
    player.play(url);
}
</script>
```

## 测试建议

### 基础测试（小文件）
推荐使用：
- W3Schools的视频（<10MB）
- techslides的small.mp4（<1MB）

### 性能测试（大文件）
推荐使用：
- Big Buck Bunny 1080p（10MB）
- 时光网的高清视频

### 流媒体测试
推荐使用：
- Apple HLS示例流
- DASH测试流

### 列表流测试
建议使用多个小视频（<5MB），减少加载时间。

## 注意事项

1. **HTTP vs HTTPS**
   - 确保`AndroidManifest.xml`中设置了`usesCleartextTraffic="true"`
   - 或者只使用HTTPS视频

2. **跨域问题**
   - 原生WebView通常不受跨域限制
   - 但某些视频服务器可能限制访问

3. **网络权限**
   - 确保在`AndroidManifest.xml`中添加了网络权限
   - `<uses-permission android:name="android.permission.INTERNET" />`

4. **视频格式支持**
   - 推荐使用H.264编码的MP4
   - ExoPlayer支持大多数主流格式

5. **车机环境**
   - 优先使用MP4格式
   - 避免过高码率（建议<5Mbps）
   - 考虑使用本地视频减少网络依赖

## 杜比音效测试视频

如果要测试杜比音效，需要使用包含杜比音轨的视频：

```
# Dolby Digital Plus示例
http://download.dolby.com/us/en/test-tones/dolby-atmos-trailer_amaze_1080.mp4

# 注意：需要车机硬件支持杜比解码
```

## 自定义视频准备

如果使用自己的视频，建议参数：

- **编码**: H.264
- **容器**: MP4
- **分辨率**: 720p 或 1080p
- **帧率**: 25fps 或 30fps
- **码率**: 2-5 Mbps
- **音频**: AAC, 128kbps

使用FFmpeg转换：
```bash
ffmpeg -i input.mp4 -c:v libx264 -preset medium -crf 23 -c:a aac -b:a 128k output.mp4
```
