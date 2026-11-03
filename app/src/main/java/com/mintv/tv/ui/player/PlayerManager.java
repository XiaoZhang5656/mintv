package com.mintv.tv.ui.player;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.Map;

/**
 * 三内核调度器。
 *
 * <p>为什么不在 MainActivity 里 if/else：三种内核的 Surface 绑定方式各不相同
 * （Exo/IJK 给 Surface，VLC 必须给 SurfaceView 让 it attachViews），
 * 这些差异必须收在一层里，UI 只看到 {@link PlaybackBackend} 一个接口。
 *
 * <p>降级顺序固定为 {@code EXO → IJK → VLC}，由 {@link PlaybackEngine#next()} 定义。
 * 哪一步是「用户手选」还是「自动降级」要分开记 —— 手选之后不再自动跳，
 * 否则用户在设置里点死 VLC，一黑屏又被改回 Exo，等于设置不生效。
 */
public final class PlayerManager implements PlaybackBackend {
    /** 引擎切换完成后的回调（由调用方设置，用来接着发起播放）。 */
    private Runnable onSwapped;

    /** 设置切换完成回调。为空表示不需要。 */
    public void setOnSwapped(Runnable r) { this.onSwapped = r; }

    private final android.os.Handler mainThread = new android.os.Handler(
            android.os.Looper.getMainLooper());


    private static final String TAG = "PlayerManager";

    private final Context appCtx;
    private PlaybackBackend impl;
    private PlaybackEngine engine = PlaybackEngine.EXO;

    /** 用户在设置里锁定了某个内核时，禁止自动降级。 */
    private boolean pinned = false;

    private Callback cb;
    private SurfaceView surfaceView;
    private android.view.TextureView textureView;
    private Surface surface;

    public PlayerManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    // ================= 内核切换 =================

    public PlaybackEngine engine() { return engine; }

    public void setEngine(PlaybackEngine e, boolean userPinned) {
        if (e == null) e = PlaybackEngine.EXO;
        this.pinned = userPinned;
        if (e == engine && impl != null) return;
        this.engine = e;
        rebuild();
    }

    public boolean isPinned() { return pinned; }

    /** 自动降级：只在没锁定、且还有下一张牌时换。 */
    public boolean degrade() {
        if (pinned) return false;
        PlaybackEngine next = engine.next();
        if (next == engine) return false;
        engine = next;
        rebuild();
        Log.i(TAG, "degrade -> " + next);
        return true;
    }

    /** 内核是否真的能用（so 没打进 APK 或 ABI 不匹配时返回 false）。 */
    public static boolean available(PlaybackEngine e) {
        switch (e) {
            case VLC: return VlcBackend.isAvailable();
            default: return true;
        }
    }

    /**
     * 切换内核。
     *
     * <p><b>为什么不能同步做</b>：libVLC 的 detachViews / stop / release
     * 三个调用都会去抢它的内部 mutex（实测主线程 native 栈：
     * {@code NonPI::MutexLockWithTimeout} → {@code libvlc 内部}，
     * utm 到 450ms、累计 5.3 秒），在解复用线程还没退出时能等好几秒。
     * 放在主线程就是 ANR；而表面看着像"闪退"，因为系统会杀进程。
     *
     * <p>所以拆成两阶段：
     * <ol>
     *   <li>主线程：把回调摘掉，避免旧实例再往 UI 发消息；</li>
     *   <li>后台线程：detachViews → stop → release（全部阻塞都在这）；</li>
     *   <li>回主线程：建新实例、绑画面。</li>
     * </ol>
     * 旧实例在后台释放期间，Surface 已经先被 detach 掉，不会再出现
     * use-after-free（Surface 回调打到已释放对象上）。
     */
    private void rebuild() {
        final PlaybackBackend old = impl;
        impl = null;
        if (old != null) old.setCallback(null);

        final Runnable buildNew = new Runnable() {
            @Override public void run() {
                switch (engine) {
                    case VLC: impl = new VlcBackend(); break;
                    default: impl = new Media3Backend(); break;
                }
                impl.setCallback(cb);
                bindSurface();
            }
        };

        if (old == null) {
            buildNew.run();
            return;
        }

        // 主线程先把画面从旧实例上摘下来（这一步只是清引用，不碰 native）
        if (old instanceof VlcBackend) {
            ((VlcBackend) old).detachViewsSafe();
        }

        swapping = true;
        new Thread(new Runnable() {
            @Override public void run() {
                // 阻塞调用全在这里：detach / stop / release
                try { old.release(); } catch (Throwable ignored) { }
                Runnable done = new Runnable() {
                    @Override public void run() {
                        buildNew.run();
                        swapping = false;
                        // 先执行挂起的播放请求（换内核期间用户按了换台）
                        if (pendingPlay != null) {
                            Runnable r = pendingPlay;
                            pendingPlay = null;
                            r.run();
                        }
                        // 再执行显式的"切换完成"回调
                        if (onSwapped != null) {
                            Runnable r = onSwapped;
                            onSwapped = null;
                            r.run();
                        }
                    }
                };
                if (mainThread != null) mainThread.post(done); else done.run();
            }
        }, "engine-swap").start();
    }

    private void bindSurface() {
        if (impl == null) return;
        if (textureView != null) {
            impl.setTextureView(textureView);
            return;
        }
        if (impl instanceof VlcBackend) {
            if (surfaceView != null) ((VlcBackend) impl).setSurfaceView(surfaceView);
        } else {
            if (surface != null) impl.setSurface(surface);
        }
    }

    // ================= 接口转发 =================

    @Override
    public void setCallback(Callback cb) {
        this.cb = cb;
        if (impl != null) impl.setCallback(cb);
    }

    @Override
    public void setSurface(Surface s) {
        this.surface = s;
        if (impl != null && !(impl instanceof VlcBackend)) impl.setSurface(s);
    }

    /** MainActivity 在 surfaceCreated / surfaceChanged 里调这个。 */
    public void setSurfaceView(SurfaceView sv) {
        this.surfaceView = sv;
        bindSurface();
    }

    /** 切到 TextureView 输出（SurfaceView 出不了图时的解法）。 */
    public void setTextureView(android.view.TextureView tv) {
        this.textureView = tv;
        if (impl != null) impl.setTextureView(tv);
    }

    @Override
    public void play(Context appCtx, String url, Mode mode) { play(appCtx, url, null, mode); }

    /** 换内核期间挂起的播放请求。 */
    private Runnable pendingPlay;
    /** 是否正在换内核（后台释放旧实例中）。 */
    private volatile boolean swapping;

    public void play(final Context appCtx, final String url,
                     final Map<String, String> headersIn, final Mode mode) {
        final Map<String, String> headers = headersIn == null
                ? java.util.Collections.<String, String>emptyMap() : headersIn;

        if (impl == null) {
            if (swapping) {
                // ⚠ 关键：换内核进行中，**绝不能**在这里 rebuild()。
                //
                // 以前这里写的是 if (impl == null) rebuild()，于是：
                //   后台线程正在 release 旧实例（VLC，可能几百毫秒）
                //   主线程这句又建了一个新实例并开始播放
                // → 两个播放器同时活着，音频混在一起 ——
                //   用户听到的就是"换个台还能听见上一个台的声音"。
                //
                // 现在改成排队：把这次播放请求记下来，等 swap 完成后执行。
                pendingPlay = new Runnable() {
                    @Override public void run() { play(appCtx, url, headers, mode); }
                };
                return;
            }
            rebuild();
            if (impl == null) return;
        }

        bindSurface();
        if (impl instanceof Media3Backend) {
            ((Media3Backend) impl).play(appCtx, url, headers, mode);
        } else if (impl instanceof VlcBackend) {
            ((VlcBackend) impl).play(appCtx, url, headers, mode);
        } else {
            impl.play(appCtx, url, mode);
        }
    }

    /** 视口尺寸变化：只有 VLC 需要（它按窗口尺寸做缩放）。 */
    public void updateWindowSize(int w, int h) {
        if (impl instanceof VlcBackend) ((VlcBackend) impl).updateWindowSize(w, h);
    }

    @Override
    public void stop() { if (impl != null) impl.stop(); }

    @Override
    public void release() {
        if (impl != null) {
            impl.setCallback(null);
            impl.release();
            impl = null;
        }
        surface = null;
        surfaceView = null;
    }

    @Override public boolean canSeek() { return impl != null && impl.canSeek(); }

    @Override public void seekTo(long positionMs) { if (impl != null) impl.seekTo(positionMs); }

    @Override public long positionMs() { return impl == null ? -1 : impl.positionMs(); }

    @Override public long durationMs() { return impl == null ? -1 : impl.durationMs(); }

    @Override public long bufferedPositionMs() { return impl == null ? 0 : impl.bufferedPositionMs(); }

    @Override public Mode mode() { return impl == null ? Mode.LIVE : impl.mode(); }

    @Override public String tag() { return engine.name(); }

    @Override public boolean supportsHeaders() { return impl == null || impl.supportsHeaders(); }

    @Override public String headersSummary() { return impl == null ? "" : impl.headersSummary(); }

    /** 当前内核 + 丢头情况的完整描述，给诊断面板用。 */
    public String headerDiagnostics(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "无自定义请求头";
        StringBuilder sb = new StringBuilder(headersSummary());
        String lost = HttpHeaderProfile.vlcUnsupported(headers);
        if (engine == PlaybackEngine.VLC && !lost.isEmpty()) {
            sb.append(" | VLC 无法表达: ").append(lost);
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /** 诊断串，一行看完当前内核和状态。 */
    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("engine=").append(engine).append(" pinned=").append(pinned);
        sb.append(" headers=[").append(impl == null ? "" : impl.headersSummary()).append(']');
        if (impl instanceof Media3Backend) {
            sb.append(' ').append(((Media3Backend) impl).diagnostics());
        }
        return sb.toString();
    }
}
