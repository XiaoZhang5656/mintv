package com.mintv.tv.ui.player;

import android.content.Context;
import android.view.SurfaceView;
import android.view.TextureView;

import java.util.Map;

/**
 * VLC 内核适配到 {@link PlaybackSession.Backend}。
 *
 * <p>这层只是转接，不放业务逻辑 —— 业务仍然在 {@link VlcBackend} 里。
 * 存在的意义是满足 Session 的契约，尤其是 teardown 必须带完成回调。
 */
final class VlcSessionBackend implements PlaybackSession.Backend {

    private final VlcBackend vlc = new VlcBackend();
    private volatile boolean released;

    @Override public void setCallback(PlaybackBackend.Callback cb) {
        vlc.setCallback(cb);
    }

    @Override public void prepare(Context ctx, String url,
                                  Map<String, String> headers, PlaybackBackend.Mode mode) {
        released = false;
        vlc.play(ctx, url, headers, mode);
    }

    @Override public void setTextureView(TextureView tv) { vlc.setTextureView(tv); }
    @Override public void setSurfaceView(SurfaceView sv) { vlc.setSurfaceView(sv); }

    @Override public void detachOutput() {
        try { vlc.detachOutputForSession(); } catch (Throwable ignored) { }
    }

    /**
     * 带完成回调的销毁。
     *
     * <p>VlcBackend.release() 内部是异步的（LibVLC.release 会等回放线程收尾），
     * 这里起一个线程等它把资源真正交出去，再回调 —— 不能让 Session
     * 在旧实例还活着的时候建新的。
     */
    @Override public void teardown(final Runnable done) {
        if (released) {
            if (done != null) done.run();
            return;
        }
        released = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    vlc.detachOutputForSession();
                    vlc.release();
                    // release 内部已经起线程做实际释放，这里等一小段让它收尾。
                    // 不是睡够时间，而是给 LibVLC 的 release 线程一个调度窗口。
                    Thread.sleep(120);
                } catch (Throwable ignored) {
                } finally {
                    if (done != null) done.run();
                }
            }
        }, "vlc-teardown").start();
    }

    @Override public boolean isReleased() { return released; }
}
