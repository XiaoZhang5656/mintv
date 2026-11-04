package com.mintv.tv.ui.player;

import android.content.Context;
import android.util.Log;
import android.view.SurfaceView;
import android.view.TextureView;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * 播放会话：**唯一的所有者**。
 *
 * <p>以前根本没有这一层 —— PlayerManager 直接 new 内核、直接 release，
 * 而 release 是异步 fire-and-forget（起个线程就返回）。于是主线程以为
 * 释放完了，后台还在跑，三个老 bug 全部由此而来：
 *
 * <pre>
 *   切内核闪退     旧实例还持有已销毁的 Surface，use-after-free
 *   两个台同时出声  新实例已经起来，旧实例还没死
 *   切主题黑屏有声  recreate 时新旧两个 Activity 各持一个播放器
 * </pre>
 *
 * <p>本条类存在的意义：把「谁创建、谁销毁、什么时候」收进一个地方，
 * 用状态机 + 串行队列保证**同一时刻只有一个内核存活**。
 *
 * <h3>四条铁律</h3>
 * <ol>
 *   <li>切换内核 = 完整 teardown 之后才允许建新的，不允许重叠</li>
 *   <li>teardown 必须等待完成（回调），不是起个线程就不管</li>
 *   <li>Surface / TextureView 的绑定只由本类转发，内核不直接持有</li>
 *   <li>Activity 生命周期只调本类，不直接操作内核</li>
 * </ol>
 *
 * <h3>为后续加内核预留</h3>
 * 现在只有 VLC。要加 Exo（DASH + Widevine）时，实现一个
 * {@link Backend} 并在 {@link #createBackend} 里按需返回即可，
 * 上面四条铁律不用重写。
 */
public final class PlaybackSession {

    private static final String TAG = "MinTVSession";

    public enum State { IDLE, PREPARING, PLAYING, RELEASING }

    /**
     * 内核契约。
     *
     * <p>{@code teardown} 带完成回调是这套架构的关键 —— 只有这样，
     * Session 才知道「旧的真的死透了」，才敢建新的。
     */
    public interface Backend {
        void setCallback(PlaybackBackend.Callback cb);
        void prepare(Context ctx, String url, Map<String, String> headers,
                     PlaybackBackend.Mode mode);
        void setTextureView(TextureView tv);
        void setSurfaceView(SurfaceView sv);
        void detachOutput();                  // 必须幂等
        void teardown(Runnable done);         // 完成时回调，不能 fire-and-forget
        boolean isReleased();
    }

    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean draining;

    private Backend backend;
    private State state = State.IDLE;

    private SurfaceView surfaceView;
    private TextureView textureView;
    private PlaybackBackend.Mode pendingMode = PlaybackBackend.Mode.LIVE;
    private String pendingUrl;
    private Map<String, String> pendingHeaders;
    private PlaybackBackend.Callback callback;

    public State state() { return state; }
    public boolean isIdle() { return state == State.IDLE; }

    // ================= 生命周期 =================

    public void setCallback(PlaybackBackend.Callback cb) {
        this.callback = cb;
        if (backend != null) backend.setCallback(cb);
    }

    /**
     * 起播。如果正在释放中，这次请求会排队等释放完 —— 不打断、不重叠。
     */
    public void play(final Context ctx, final String url,
                     final Map<String, String> headers, PlaybackBackend.Mode mode) {
        pendingUrl = url;
        pendingHeaders = headers;
        pendingMode = mode;
        enqueue(new Runnable() {
            @Override public void run() {
                startNow(ctx.getApplicationContext());
            }
        });
    }

    private void startNow(Context appCtx) {
        if (pendingUrl == null) return;

        // 有活着的旧实例：先彻底拆掉，再建新的。
        // 这里不能并行 —— 并行就是"两个台同时出声"的来源。
        if (backend != null && !backend.isReleased()) {
            state = State.RELEASING;
            final Context c = appCtx;
            backend.teardown(new Runnable() {
                @Override public void run() {
                    markReleased();
                    enqueue(new Runnable() {
                        @Override public void run() { startNow(c); }
                    });
                }
            });
            return;
        }

        backend = createBackend(pendingMode);
        backend.setCallback(callback);
        state = State.PREPARING;

        // 渲染目标在起播前就递进去，避免内核先出帧再补绑定
        if (textureView != null) backend.setTextureView(textureView);
        if (surfaceView != null) backend.setSurfaceView(surfaceView);

        backend.prepare(appCtx, pendingUrl, pendingHeaders, pendingMode);
        state = State.PLAYING;
        Log.i(TAG, "session start: " + pendingUrl);
    }

    /**
     * 要加新内核时改这里。
     *
     * <p>现在固定返回 VLC。加 Exo 之后按 url 特征或设置项选择 ——
     * 上层的排队 / 释放语义不用动。
     */
    private Backend createBackend(PlaybackBackend.Mode mode) {
        return new VlcSessionBackend();
    }

    public void stop() {
        enqueue(new Runnable() {
            @Override public void run() { stopNow(null); }
        });
    }

    /** 彻底销毁。幂等，多次调用安全。 */
    public void destroy(final Runnable after) {
        enqueue(new Runnable() {
            @Override public void run() { stopNow(after); }
        });
    }

    private void stopNow(final Runnable after) {
        if (backend == null || backend.isReleased()) {
            state = State.IDLE;
            if (after != null) after.run();
            return;
        }
        state = State.RELEASING;
        backend.teardown(new Runnable() {
            @Override public void run() {
                markReleased();
                if (after != null) after.run();
            }
        });
    }

    private void markReleased() {
        backend = null;
        state = State.IDLE;
        Log.i(TAG, "session released");
    }

    // ================= 渲染目标 =================

    public void setTextureView(TextureView tv) {
        this.textureView = tv;
        if (tv != null) this.surfaceView = null;      // 互斥
        if (backend != null) backend.setTextureView(tv);
    }

    public void setSurfaceView(SurfaceView sv) {
        this.surfaceView = sv;
        if (sv != null) this.textureView = null;
        if (backend != null) backend.setSurfaceView(sv);
    }

    // ================= 串行队列 =================
    //
    // 所有状态变更都排进这里，同一时刻只跑一个。
    // 这是"不允许重叠"的执行保证 —— 不是靠调用方自觉。

    private void enqueue(Runnable r) {
        queue.add(r);
        if (draining) return;
        draining = true;
        try {
            while (!queue.isEmpty()) {
                Runnable job = queue.poll();
                try { job.run(); } catch (Throwable t) { Log.w(TAG, "job failed: " + t); }
            }
        } finally {
            draining = false;
        }
    }
}
