/**
 * 混合视频播放器 H5 SDK
 * 支持Canvas渲染和同层渲染两种模式
 */
class HybridVideoPlayer {
    constructor(options = {}) {
        this.playerId = options.playerId || this._generatePlayerId();
        this.containerId = options.containerId;
        this.renderMode = options.renderMode || 'auto'; // 'canvas' | 'layer' | 'auto'
        this.audioFocusType = options.audioFocusType || 'transient'; // 'gain' | 'transient' | 'transient_may_duck' | 'exclusive'
        this.enableDolby = options.enableDolby || false;
        this.maxFrameRate = options.maxFrameRate || 60;
        this.autoPlay = options.autoPlay || false;
        this.loop = options.loop || false;
        this.muted = options.muted || false;
        this.volume = options.volume !== undefined ? options.volume : 1.0;

        this.state = 'idle';
        this.currentTime = 0;
        this.duration = 0;
        this.videoWidth = 0;
        this.videoHeight = 0;

        this.listeners = {};
        this.canvas = null;
        this.ctx = null;
        this.container = null;
        this.positionObserver = null;

        this._init();
    }

    /**
     * 初始化
     */
    _init() {
        if (this.containerId) {
            this.container = document.getElementById(this.containerId);
            if (!this.container) {
                throw new Error(`Container element with id '${this.containerId}' not found`);
            }
        }

        // 创建Canvas元素（Canvas模式使用）
        this._createCanvas();

        // 注册全局回调
        this._registerGlobalCallbacks();

        // 创建原生播放器实例
        this._createNativePlayer();

        // 开始监听位置变化
        this._startPositionTracking();
    }

    /**
     * 创建Canvas元素
     */
    _createCanvas() {
        if (!this.container) return;

        this.canvas = document.createElement('canvas');
        this.canvas.style.width = '100%';
        this.canvas.style.height = '100%';
        this.canvas.style.objectFit = 'contain';
        this.container.appendChild(this.canvas);

        this.ctx = this.canvas.getContext('2d');
    }

    /**
     * 创建原生播放器实例
     */
    _createNativePlayer() {
        const rect = this.container ? this.container.getBoundingClientRect() : { left: 0, top: 0, width: 0, height: 0 };

        const config = {
            playerId: this.playerId,
            renderMode: this.renderMode,
            audioFocusType: this.audioFocusType,
            enableDolby: this.enableDolby,
            maxFrameRate: this.maxFrameRate,
            enableSharedMemory: true,
            // 初始位置也是CSS像素，Native层会转换
            width: Math.round(rect.width),
            height: Math.round(rect.height),
            x: Math.round(rect.left),
            y: Math.round(rect.top),
            autoPlay: this.autoPlay,
            loop: this.loop,
            muted: this.muted,
            volume: this.volume
        };

        if (window.NativeVideoPlayer) {
            window.NativeVideoPlayer.createPlayer(JSON.stringify(config));

            // 延迟更新位置，确保：
            // 1. Native播放器创建完成
            // 2. 布局计算完成
            setTimeout(() => {
                this._updatePosition();
            }, 100);

            // 再延迟一次，确保首次同步
            setTimeout(() => {
                this._updatePosition();
            }, 300);
        } else {
            console.warn('NativeVideoPlayer bridge not available');
        }
    }

    /**
     * 注册全局回调
     */
    _registerGlobalCallbacks() {
        // 确保全局回调对象存在
        if (!window.__hybridVideoPlayerCallbacks__) {
            window.__hybridVideoPlayerCallbacks__ = {};
        }

        // 注册当前播放器的回调
        window.__hybridVideoPlayerCallbacks__[this.playerId] = {
            onPrepared: () => this._onPrepared(),
            onStateChanged: (state) => this._onStateChanged(state),
            onProgressChanged: (position, duration) => this._onProgressChanged(position, duration),
            onBufferingUpdate: (percent) => this._onBufferingUpdate(percent),
            onVideoSizeChanged: (width, height) => this._onVideoSizeChanged(width, height),
            onCompletion: () => this._onCompletion(),
            onError: (errorCode, errorMessage) => this._onError(errorCode, errorMessage),
            onFrameRendered: (frameData, width, height, timestamp) => this._onFrameRendered(frameData, width, height, timestamp)
        };
    }

    /**
     * 开始位置跟踪
     */
    _startPositionTracking() {
        if (!this.container) return;

        // 使用IntersectionObserver监听可见性
        this.visibilityObserver = new IntersectionObserver((entries) => {
            const isVisible = entries[0].isIntersecting;
            this._updateVisibility(isVisible);
        }, { threshold: [0, 0.1, 0.5, 1] });
        this.visibilityObserver.observe(this.container);

        // 使用ResizeObserver监听尺寸变化
        this.resizeObserver = new ResizeObserver(() => {
            this._updatePosition();
        });
        this.resizeObserver.observe(this.container);

        // 监听滚动事件（使用requestAnimationFrame优化）
        let rafId = null;
        const scrollHandler = () => {
            if (rafId) return;
            rafId = requestAnimationFrame(() => {
                this._updatePosition();
                rafId = null;
            });
        };

        // 监听多个滚动源
        window.addEventListener('scroll', scrollHandler, { passive: true });
        document.addEventListener('scroll', scrollHandler, { passive: true });

        // 监听容器及其父元素的滚动
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

        // 保存引用以便清理
        this._scrollHandler = scrollHandler;
    }

    /**
     * 更新位置
     */
    _updatePosition() {
        if (!this.container || !window.NativeVideoPlayer) return;

        const rect = this.container.getBoundingClientRect();

        // 保留CSS像素的精度，不要四舍五入
        const x = rect.left;
        const y = rect.top;
        const w = rect.width;
        const h = rect.height;

        // 调试日志
        console.log(`[${this.playerId}] updatePosition: x=${x}, y=${y}, w=${w}, h=${h}, ` +
                    `scrollY=${window.scrollY}, devicePixelRatio=${window.devicePixelRatio}`);

        // 使用getBoundingClientRect相对于视口的坐标（CSS像素）
        // Native层会负责转换为物理像素
        if (window.NativeVideoPlayer.updateLayoutFloat) {
            // 如果支持Float版本，使用它（保留小数精度）
            window.NativeVideoPlayer.updateLayoutFloat(this.playerId, x, y, w, h);
        } else {
            // 否则使用Int版本（取整）
            window.NativeVideoPlayer.updateLayout(
                this.playerId,
                Math.round(x),
                Math.round(y),
                Math.round(w),
                Math.round(h)
            );
        }
    }

    /**
     * 更新可见性
     */
    _updateVisibility(isVisible) {
        if (window.NativeVideoPlayer) {
            window.NativeVideoPlayer.updateVisibility(this.playerId, isVisible);
        }
    }

    /**
     * 播放视频
     * @param {string} url 视频URL
     */
    play(url) {
        if (url) {
            this.url = url;
            if (window.NativeVideoPlayer) {
                window.NativeVideoPlayer.play(this.playerId, url);
            }
        } else if (this.state === 'paused') {
            if (window.NativeVideoPlayer) {
                window.NativeVideoPlayer.resume(this.playerId);
            }
        }
    }

    /**
     * 暂停播放
     */
    pause() {
        if (window.NativeVideoPlayer) {
            window.NativeVideoPlayer.pause(this.playerId);
        }
    }

    /**
     * 跳转到指定位置
     * @param {number} position 位置(秒)
     */
    seek(position) {
        if (window.NativeVideoPlayer) {
            window.NativeVideoPlayer.seek(this.playerId, Math.round(position * 1000));
        }
    }

    /**
     * 设置音量
     * @param {number} volume 音量 (0.0 - 1.0)
     */
    setVolume(volume) {
        this.volume = Math.max(0, Math.min(1, volume));
        if (window.NativeVideoPlayer) {
            window.NativeVideoPlayer.setVolume(this.playerId, this.volume);
        }
    }

    /**
     * 设置静音
     * @param {boolean} muted 是否静音
     */
    setMuted(muted) {
        this.muted = muted;
        if (window.NativeVideoPlayer) {
            window.NativeVideoPlayer.setMuted(this.playerId, muted);
        }
    }

    /**
     * 设置播放速度
     * @param {number} speed 播放速度 (0.5 - 2.0)
     */
    setPlaybackSpeed(speed) {
        if (window.NativeVideoPlayer) {
            window.NativeVideoPlayer.setPlaybackSpeed(this.playerId, speed);
        }
    }

    /**
     * 销毁播放器
     */
    destroy() {
        // 停止位置跟踪
        if (this.visibilityObserver) {
            this.visibilityObserver.disconnect();
        }
        if (this.resizeObserver) {
            this.resizeObserver.disconnect();
        }
        if (this._scrollHandler) {
            window.removeEventListener('scroll', this._scrollHandler);
            document.removeEventListener('scroll', this._scrollHandler);

            // 清理容器滚动监听
            if (this._scrollElements) {
                this._scrollElements.forEach(element => {
                    element.removeEventListener('scroll', this._scrollHandler);
                });
            }
        }

        // 销毁原生播放器
        if (window.NativeVideoPlayer) {
            window.NativeVideoPlayer.destroyPlayer(this.playerId);
        }

        // 清理DOM
        if (this.canvas && this.canvas.parentNode) {
            this.canvas.parentNode.removeChild(this.canvas);
        }

        // 清理回调
        if (window.__hybridVideoPlayerCallbacks__) {
            delete window.__hybridVideoPlayerCallbacks__[this.playerId];
        }

        // 清理事件监听器
        this.listeners = {};
    }

    /**
     * 监听事件
     * @param {string} event 事件名称
     * @param {function} callback 回调函数
     */
    on(event, callback) {
        if (!this.listeners[event]) {
            this.listeners[event] = [];
        }
        this.listeners[event].push(callback);
    }

    /**
     * 取消监听事件
     * @param {string} event 事件名称
     * @param {function} callback 回调函数
     */
    off(event, callback) {
        if (!this.listeners[event]) return;

        if (callback) {
            const index = this.listeners[event].indexOf(callback);
            if (index > -1) {
                this.listeners[event].splice(index, 1);
            }
        } else {
            this.listeners[event] = [];
        }
    }

    /**
     * 触发事件
     */
    _emit(event, ...args) {
        if (this.listeners[event]) {
            this.listeners[event].forEach(callback => {
                try {
                    callback(...args);
                } catch (e) {
                    console.error(`Error in event listener for '${event}':`, e);
                }
            });
        }
    }

    /**
     * 原生回调：准备完成
     */
    _onPrepared() {
        this.state = 'prepared';
        this._emit('prepared');
        this._emit('ready');
    }

    /**
     * 原生回调：状态改变
     */
    _onStateChanged(state) {
        this.state = state.toLowerCase();
        this._emit('statechange', this.state);

        if (this.state === 'playing') {
            this._emit('playing');
        } else if (this.state === 'paused') {
            this._emit('paused');
        } else if (this.state === 'buffering') {
            this._emit('buffering');
        }
    }

    /**
     * 原生回调：进度更新
     */
    _onProgressChanged(position, duration) {
        this.currentTime = position / 1000; // 转换为秒
        this.duration = duration / 1000;
        this._emit('progress', this.currentTime, this.duration);
        this._emit('timeupdate', this.currentTime);
    }

    /**
     * 原生回调：缓冲更新
     */
    _onBufferingUpdate(percent) {
        this._emit('bufferingupdate', percent);
    }

    /**
     * 原生回调：视频尺寸改变
     */
    _onVideoSizeChanged(width, height) {
        this.videoWidth = width;
        this.videoHeight = height;

        // 更新Canvas尺寸
        if (this.canvas) {
            this.canvas.width = width;
            this.canvas.height = height;
        }

        this._emit('videosizechange', width, height);
    }

    /**
     * 原生回调：播放完成
     */
    _onCompletion() {
        this.state = 'completed';
        this._emit('ended');
        this._emit('completion');
    }

    /**
     * 原生回调：错误
     */
    _onError(errorCode, errorMessage) {
        this.state = 'error';
        this._emit('error', { code: errorCode, message: errorMessage });
    }

    /**
     * 原生回调：帧渲染（Canvas模式）
     */
    _onFrameRendered(frameData, width, height, timestamp) {
        if (!this.ctx || !this.canvas) return;

        try {
            // frameData是Uint8Array格式的RGB数据
            const imageData = new ImageData(
                new Uint8ClampedArray(frameData),
                width,
                height
            );

            // 更新Canvas尺寸（如果需要）
            if (this.canvas.width !== width || this.canvas.height !== height) {
                this.canvas.width = width;
                this.canvas.height = height;
            }

            // 渲染到Canvas
            this.ctx.putImageData(imageData, 0, 0);

            this._emit('framerendered', timestamp);
        } catch (e) {
            console.error('Error rendering frame:', e);
        }
    }

    /**
     * 生成播放器ID
     */
    _generatePlayerId() {
        return `player_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    }

    /**
     * 获取当前状态
     */
    getState() {
        return this.state;
    }

    /**
     * 获取当前播放位置（秒）
     */
    getCurrentTime() {
        return this.currentTime;
    }

    /**
     * 获取视频总时长（秒）
     */
    getDuration() {
        return this.duration;
    }

    /**
     * 获取视频尺寸
     */
    getVideoSize() {
        return {
            width: this.videoWidth,
            height: this.videoHeight
        };
    }
}

// 导出到全局（兼容非模块化使用）
if (typeof window !== 'undefined') {
    window.HybridVideoPlayer = HybridVideoPlayer;
}

// 导出（支持ES6模块）
if (typeof module !== 'undefined' && module.exports) {
    module.exports = HybridVideoPlayer;
}
