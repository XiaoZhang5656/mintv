package com.mintv.tv.ui.player;

import android.content.Context;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;

import java.util.Map;

/**
 * 播放管理器。
 *
 * <p>现在只有一个内核（VLC）。以前有 Exo + VLC 双内核，好处是 Exo 起播快、
 * 能用硬件解码，代价是一整套切换/降级/释放时序逻辑 —— 而"两个台同时出声"
 * "切内核闪退"这类 bug 全部出自那套时序。
 *
 * <p>删掉 Exo 之后：
 * <ul>
 *   <li>不再有"换内核"这个状态，也就没有实例竞态；</li>
 *   <li>硬解由 VLC 自己的 mediacodec 负责，失败时它内部会转软解；</li>
 *   <li>解码方式变成 VLC 的一个启动参数，不再需要重建播放器。</li>
 * </ul>
 *
 * <p>保留这一层是为了以后要再加内核时不用动调用方。
 */
public final class PlayerManager implements PlaybackBackend {

    private VlcBackend impl;
    private SurfaceView surfaceView;
    private TextureView textureView;
    private Callback cb;
    /** 解码方式要存住：release() 后 impl 会重建，新实例不记得旧值。 */
    private int decodeMode = 0;
    private int aspectMode = 0;

    public PlayerManager(Context ctx) {
        impl = new VlcBackend();
    }

    public void setCallback(Callback c) {
        this.cb = c;
        if (impl != null) impl.setCallback(c);
    }

    public void setSurfaceView(SurfaceView sv) {
        this.surfaceView = sv;
        if (sv != null) this.textureView = null;   // 互斥，避免 play() 里两个都补绑
        if (impl != null) impl.setSurfaceView(sv);
    }

    /**
     * 绑定画面输出（TextureView）。
     *
     * <p>⚠ 这个方法必须显式实现并转发。
     * PlaybackBackend 里 setTextureView 有 default 空实现，一旦这里漏了
     * 转发，调用方 playerManager.setTextureView(tv) 就静默变成空操作：
     * VlcBackend.textureView 一直是 null，attach() 里拿不到目标视图直接
     * return，VLC 从来没有绑定过 vout —— 音频照常解码出声，视频帧没有
     * 出口，症状就是「有声音没画面」。
     *
     * <p>这就是删掉 Exo、重写这个类时弄丢的那一行。
     */
    @Override public void setTextureView(TextureView tv) {
        this.textureView = tv;
        if (tv != null) this.surfaceView = null;   // 互斥
        if (impl != null) impl.setTextureView(tv);
    }

    /** 解码方式：0=自动 1=内置硬解 2=软件解码。 */
    public void setDecoderMode(int mode) {
        this.decodeMode = mode;
        if (impl != null) impl.setHwMode(mode);
    }

    /** 进后台：摘掉渲染回调并异步解绑，避免销毁时主线程卡在 libvlc。 */
    public void detachForBackground() {
        if (impl != null) impl.detachForBackground();
    }

    public void updateWindowSize(int w, int h) {
        if (impl != null) impl.updateWindowSize(w, h);
    }

    /** 画面比例。0=自动适应 1=16:9 2=4:3 3=原始 4=铺满 */
    public void setAspectMode(int mode) {
        this.aspectMode = mode;
        if (impl != null) impl.setAspectMode(mode);
    }

    @Override public void play(Context appCtx, String url, Mode mode) {
        play(appCtx, url, null, mode);
    }

    /** 带请求头的播放入口（不属于接口，调用方直接用）。 */
    public void play(Context appCtx, String url, Map<String, String> headers, Mode mode) {
        if (impl == null) {
            // 重建内核（比如切换画面输出 / release 之后）：
            // 回调、解码方式、画面比例全部要补回去，否则新实例是"失忆"的
            // —— 播放事件没人收，OSD 不更新、首帧回调不到、卡顿检测失灵。
            impl = new VlcBackend();
            impl.setHwMode(decodeMode);
            impl.setAspectMode(aspectMode);
            if (cb != null) impl.setCallback(cb);
        }
        if (headers == null) headers = java.util.Collections.emptyMap();
        // 两个都补一遍：VLC 实例重建后 attachedTarget 已清空，
        // 必须有人重新把当前生效的那个视图递进去，否则同样不出画面。
        if (textureView != null) impl.setTextureView(textureView);
        if (surfaceView != null) impl.setSurfaceView(surfaceView);
        impl.play(appCtx, url, headers, mode);
    }

    @Override public void stop() { if (impl != null) impl.stop(); }
    @Override public void release() { if (impl != null) { impl.release(); impl = null; } }
    @Override public void setSurface(Surface s) { if (impl != null) impl.setSurface(s); }
    @Override public boolean canSeek() { return impl != null && impl.canSeek(); }
    @Override public void seekTo(long ms) { if (impl != null) impl.seekTo(ms); }
    @Override public long positionMs() { return impl == null ? 0 : impl.positionMs(); }
    @Override public long bufferedPositionMs() { return impl == null ? 0 : impl.bufferedPositionMs(); }
    @Override public long durationMs() { return impl == null ? 0 : impl.durationMs(); }
    @Override public String tag() { return "VLC"; }
    @Override public Mode mode() { return impl == null ? Mode.LIVE : impl.mode(); }
    @Override public boolean isPaused() { return impl != null && impl.isPaused(); }
    @Override public boolean isPlaying() { return impl != null && impl.isPlaying(); }
    @Override public void pause() { if (impl != null) impl.pause(); }
    @Override public void resume() { if (impl != null) impl.resume(); }
}
