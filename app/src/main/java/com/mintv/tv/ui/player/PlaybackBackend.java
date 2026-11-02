package com.mintv.tv.ui.player;

import android.content.Context;
import android.view.Surface;

/**
 * 播放内核抽象。
 *
 * <p>换 Media3 / VLC / 系统 MediaPlayer 都只实现这个接口，UI 层一行不动。
 *
 * <p><b>关于快进</b>：接口把「能否 seek」明确暴露出来，而不是让 UI 去猜。
 * 直播流（HLS 实时）的 {@link #canSeek()} 返回 false，长按快进在 UI 层直接不启动；
 * 回看 / 时移 / 点播返回 true，UI 才画进度预览并真的 seek。
 * 这样换任何解码内核，行为都由内核自己声明，UI 不需要写 if (是exo) 这种判断。
 */
public interface PlaybackBackend {

    enum State { IDLE, BUFFERING, READY, RECONNECTING, ERROR }

    /** 播放模式：决定 UI 要不要显示进度条、快进等能力。 */
    enum Mode {
        /** 纯直播，无时长概念，不能 seek */
        LIVE,
        /** 时移 / 回看，有可跳转区间，能 seek */
        TIMESHIFT,
        /** 点播文件，有完整时长 */
        VOD
    }

    interface Callback {
        void onState(State state);
        void onError(String msg);
        void onFirstFrame();

        /**
         * 音频在放、画面出不来：视频编码本机不支持，或者这个流根本没有视频轨。
         *
         * <p>这是「有声音没画面」的明确信号。以前这类情况内核什么都不报，
         * UI 就只能对着黑屏播到底，所以接口必须单开一个回调把它挑明。
         */
        default void onVideoUnavailable(String reason) { }

        /** 解码器输出的视频格式，拿不到就各传 null。 */
        void onVideoFormat(String resolution, String videoCodec, String audioCodec,
                           String channels, String fps);

        /** 时长 / 位置变化（毫秒）。live 模式下 durationMs 为 -1。 */
        void onTimeline(long positionMs, long durationMs, long bufferedMs);
    }

    void setCallback(Callback cb);

    void setSurface(Surface surface);

    /**
     * 用 TextureView 输出画面。
     *
     * <p>为什么两种都要：SurfaceView 走独立图层，性能好，但在部分机型
     * （尤其 MIUI / Android 12+）上解码器出帧却合成不到屏幕，表现就是
     * 「有声音没画面」。TextureView 走 GPU 合成、跟着视图树走，画面一定能出来，
     * 代价是多一次纹理拷贝。默认给 TextureView，能出图优先。
     */
    default void setTextureView(android.view.TextureView tv) { }

    /** 播放。mode 决定内核怎么配置（直播用低延迟缓冲，回看用可 seek 的缓冲）。 */
    void play(Context appCtx, String url, Mode mode);

    /** 兼容旧调用：等价于 mode = LIVE。 */
    default void play(Context appCtx, String url, boolean live) {
        play(appCtx, url, live ? Mode.LIVE : Mode.VOD);
    }

    void stop();

    void release();

    /**
     * 内核是否支持自定义请求头。
     * 不支持的（libVLC）UI 就不显示「请求头」这一项，而不是让用户填了没用。
     */
    default boolean supportsHeaders() { return true; }

    /** 当前生效的自定义请求头，仅用于诊断面板显示。 */
    default String headersSummary() { return ""; }

    // ================= 快进 / 跳转能力 =================

    /** 当前是否允许 seek。直播流必须返回 false。 */
    boolean canSeek();

    /** 跳转。实现里要处理好直播边缘、缓冲不足等情况。 */
    void seekTo(long positionMs);

    /** 当前播放位置（毫秒）；直播返回已缓冲的直播进度或 -1。 */
    long positionMs();

    /** 总时长（毫秒）；直播返回 -1。 */
    long durationMs();

    /** 当前缓冲位置，用于断流检测。 */
    long bufferedPositionMs();

    /** 当前播放模式。 */
    Mode mode();

    /** 内核标识，写日志和诊断面板用。 */
    default String tag() { return PlaybackEngine.EXO.name(); }
}
