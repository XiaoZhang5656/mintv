package com.mintv.tv.ui.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * libVLC 内核。
 *
 * <p>定位是「最后一张牌」：格式覆盖最广（AVS2/AVS3、RealVideo、各种非标封装都能解），
 * 网络层重连最激进。代价是体积大（arm64 + armv7 加起来 ~86MB）和起播比 Exo 慢 200~400ms。
 *
 * <p>VLC 必须用 {@code attachViews} 绑一个真实 SurfaceView，光给 Surface 它不出图，
 * 这是它跟 Exo/IJK 最大的区别 —— 所以 {@link #setSurfaceView} 是必需的，不是可选项。
 */
public final class VlcBackend implements PlaybackBackend {

    private final Handler main = new Handler(Looper.getMainLooper());
    private LibVLC libVlc;
    private MediaPlayer player;
    /** 后台收尾线程（stop/release）。libVLC 的 join 可能要几秒，不能在主线程等。 */
    private Thread ioThread;
    private SurfaceView surfaceView;
    private android.view.TextureView textureView;
    private Callback cb;
    private Mode mode = Mode.LIVE;
    private boolean released = false;
    private boolean firstFrame = false;
    private boolean viewsAttached = false;
    private String currentUrl;
    private long duration = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (released || player == null) return;
            long t = 0;
            try { t = player.getTime(); } catch (Throwable ignored) { }
            if (cb != null) {
                if (mode == Mode.LIVE) cb.onTimeline(-1, -1, 0);
                else cb.onTimeline(t, duration, 0);
            }
            main.postDelayed(this, 500);
        }
    };

    /**
     * 本机能否真的用 VLC。
     *
     * <p>只做 Class.forName 是假的可用性判断 —— 类在 dex 里，
     * native 库加载失败要到第一次 new LibVLC() 才炸，而且炸的是
     * UnsatisfiedLinkError，用户看到的就是"切 VLC 就闪退"。
     * 这里直接试着把 native 库载进来，失败就明确不可用，让上层退回 Exo。
     */
    public static boolean isAvailable() {
        try {
            Class.forName("org.videolan.libvlc.LibVLC");
            System.loadLibrary("vlc");
            System.loadLibrary("vlcjni");
            return true;
        } catch (Throwable t) {
            android.util.Log.w("VlcBackend", "VLC 不可用: " + t);
            return false;
        }
    }

    @Override public void setCallback(Callback cb) { this.cb = cb; }

    /** VLC 只认 SurfaceView（内部走 IVLCVout.attachViews）。TextureView 出不了图。 */
    public void setSurfaceView(SurfaceView sv) {
        this.surfaceView = sv;
        if (player != null && sv != null && !viewsAttached) attach();
    }

    @Override public void setSurface(Surface s) {
        // VLC 这条路径无效，保留接口兼容
    }

    @Override public void setTextureView(android.view.TextureView tv) {
        this.textureView = tv;
        if (player != null && tv != null) {
            try {
                if (viewsAttached) player.getVLCVout().detachViews();
                player.getVLCVout().setVideoView(tv);
                player.getVLCVout().attachViews();
                viewsAttached = true;
            } catch (Throwable ignored) { }
        }
    }

    private void ensureLibVlc(Context ctx) {
        if (libVlc != null) return;
        List<String> args = new ArrayList<>();
        // 直播低延迟 + 硬件解码 + 不显字幕
        args.add("--no-drop-late-frames");
        args.add("--no-skip-frames");
        args.add("--network-caching=800");
        args.add("--live-caching=800");
        args.add("--file-caching=800");
        args.add("--avcodec-hw=any");
        args.add("--no-sub-autodetect-file");
        args.add("--no-osd");
        args.add("--no-video-title-show");
        args.add("--rtsp-tcp");
        try {
            libVlc = new LibVLC(ctx.getApplicationContext(), args);
        } catch (Throwable t) {
            if (cb != null) cb.onError("VLC 初始化失败: " + t.getMessage());
        }
    }

    private void attach() {
        if (player == null) return;
        if (surfaceView == null && textureView == null) return;
        try {
            // ⚠ setWindowSize 必须在 attachViews 之前调用。
            //
            // 不调的话 VLC 不知道视口多大，就按解码出来的原始尺寸画
            // （实测日志：libvlc decoder: output 1920x1088 stride 0 0, crop 0 0 0 0），
            // 画面就贴在左上角、右边留一大块黑 —— 这正是"VLC 右边黑边"的根因。
            int w = surfaceView != null ? surfaceView.getWidth() : textureView.getWidth();
            int h = surfaceView != null ? surfaceView.getHeight() : textureView.getHeight();
            if (w > 0 && h > 0) {
                player.getVLCVout().setWindowSize(w, h);
            }
            if (surfaceView != null) player.getVLCVout().setVideoView(surfaceView);
            else player.getVLCVout().setVideoView(textureView);
            player.getVLCVout().attachViews();
            viewsAttached = true;
        } catch (Throwable t) {
            if (cb != null) cb.onError("VLC 绑定画面失败: " + t.getMessage());
        }
    }

    /** 画面尺寸变了（旋转 / 比例切换 / 布局完成）后重新告知 VLC。 */
    public void updateWindowSize(int w, int h) {
        if (player == null || w <= 0 || h <= 0) return;
        try { player.getVLCVout().setWindowSize(w, h); } catch (Throwable ignored) { }
    }

    @Override
    public void play(Context appCtx, String url, Mode m) { play(appCtx, url, null, m); }

    /**
     * 换源前先停掉上一个实例。
     *
     * <p>**异步**：libVLC 的 stop() 内部走 input_Close→vlc_join，
     * 同步等解复用线程退出，网络源卡住时能等好几秒。
     * 以前 PlayerManager.play 直接在主线程调 stop()，就是 ANR 的来源。
     */
    private void stopOldAsync() {
        final org.videolan.libvlc.MediaPlayer old = player;
        if (old == null) return;
        if (viewsAttached) {
            try { old.getVLCVout().detachViews(); } catch (Throwable ignored) { }
            viewsAttached = false;
        }
        player = null;
        new Thread(new Runnable() {
            @Override public void run() {
                try { old.stop(); } catch (Throwable ignored) { }
                try { old.release(); } catch (Throwable ignored) { }
            }
        }, "vlc-replace").start();
    }

    @Override
    public void play(Context appCtx, String url, boolean live) {
        play(appCtx, url, null, live ? Mode.LIVE : Mode.VOD);
    }

    public void play(Context appCtx, String url, Map<String, String> headers, Mode m) {
        if (released) return;
        this.mode = m;
        this.currentUrl = url;
        this.firstFrame = false;
        this.duration = -1;
        ensureLibVlc(appCtx);
        if (libVlc == null) return;

        // 换源时**异步**停掉旧实例：同步 stop 会 vlc_join 等线程，
        // 网络源卡住时直接 ANR（实测主线程 native 栈就是这条链）。
        stopOldAsync();
        emit(State.BUFFERING);

        player = new MediaPlayer(libVlc);
        player.setEventListener(new MediaPlayer.EventListener() {
            @Override public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        emit(State.READY);
                        break;
                    case MediaPlayer.Event.Buffering:
                        if (event.getBuffering() < 99f) emit(State.BUFFERING);
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        emit(State.ERROR);
                        if (cb != null) cb.onError("VLC 播放失败");
                        break;
                    case MediaPlayer.Event.LengthChanged:
                        duration = event.getLengthChanged();
                        break;
                    case MediaPlayer.Event.Vout:
                        if (!firstFrame) {
                            firstFrame = true;
                            if (cb != null) cb.onFirstFrame();
                        }
                        break;
                    default:
                        break;
                }
            }
        });

        attach();

        Media media = new Media(libVlc, Uri.parse(url));
        // 低延迟直播：不走本地缓存文件，直接吃网络流
        media.addOption(":network-caching=800");
        media.addOption(":live-caching=800");
        if (m == Mode.LIVE) media.addOption(":http-reconnect=true");
        dropped = applyHeaders(media, headers);
        player.setMedia(media);
        media.release();
        player.play();

        main.removeCallbacks(tick);
        main.postDelayed(tick, 500);
    }

    /** VLC 表达不了的请求头，留给诊断面板显示。 */
    private String dropped = "";

    @Override public String headersSummary() {
        return dropped.isEmpty() ? "" : "VLC 忽略: " + dropped;
    }

    /** VLC 3.x 只有三个请求头入口，其余只能丢。丢了的必须记下来。 */
    private static String applyHeaders(Media media, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        // 注意：这里绝不能假设 headers 非 null —— 调用链上游曾经在无头时给 null，
        // 结果点一个不带任何请求头的频道就 NPE 崩掉。
        StringBuilder lost = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey().toLowerCase(java.util.Locale.US);
            if (k.equals("user-agent")) {
                media.addOption(":http-user-agent=" + e.getValue());
            } else if (k.equals("referer") || k.equals("referrer")) {
                media.addOption(":http-referrer=" + e.getValue());
            } else if (k.equals("cookie")) {
                media.addOption(":http-cookies=" + e.getValue());
            } else {
                if (lost.length() > 0) lost.append('/');
                lost.append(e.getKey());
            }
        }
        return lost.toString();
    }

    @Override
    public void setAspectMode(int mode) {
        if (player == null) return;
        try {
            // libVLC 的缩放发生在视频输出层，必须用它的 ScaleType，
            // 改 SurfaceView 的 LayoutParams 它根本不看。
            switch (mode) {
                case 1:  player.setVideoScale(MediaPlayer.ScaleType.SURFACE_16_9); break;
                case 2:  player.setVideoScale(MediaPlayer.ScaleType.SURFACE_4_3); break;
                case 3:  player.setVideoScale(MediaPlayer.ScaleType.SURFACE_ORIGINAL); break;
                case 4:  player.setVideoScale(MediaPlayer.ScaleType.SURFACE_FILL); break;
                default: player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT); break;
            }
        } catch (Throwable ignored) { }
    }

    @Override public boolean supportsHeaders() { return true; }

    /**
     * 后台线程专用。**绝对不能在主线程调用**。
     */
    private void releasePlayer() {
        main.removeCallbacks(tick);
        final org.videolan.libvlc.MediaPlayer p = player;
        player = null;
        viewsAttached = false;
        if (p == null) return;
        if (ioThread != null && ioThread != Thread.currentThread()) {
            // 已在别的线程：等它按顺序做完（stop 之后再 release）
            try { ioThread.join(3000); } catch (Throwable ignored) { }
        }
        try { p.stop(); } catch (Throwable ignored) { }
        try { p.release(); } catch (Throwable ignored) { }
    }

    /**
     * 停止播放。
     *
     * <p><b>为什么必须异步</b>：libVLC 的 MediaPlayer.stop() 会走
     * {@code input_Close → vlc_join}，同步等待解复用线程退出。
     * 网络源卡住时这个 join 能等好几秒，放在主线程就是 ANR。
     *
     * <p>实测 ANR 堆栈（anr_2026-11-03_14-38）+ 主线程 native stack：
     * <pre>
     *   "main" prio=5 tid=1 Native
     *   #00 libc.so (syscall)
     *   #01 base.apk (vlc_cond_wait)
     *   #02 base.apk (vlc_sem_wait)
     *   #03 base.apk (vlc_join)
     *   #04 base.apk (input_Close)
     * </pre>
     * 就是这条链把主线程挂死了 5 秒以上。
     */
    @Override
    public void stop() {
        main.removeCallbacks(tick);
        final org.videolan.libvlc.MediaPlayer p = player;
        if (p != null) {
            // 立刻从"当前播放器"里摘掉，避免后续操作再碰它
            player = null;
            viewsAttached = false;
            ioThread = new Thread(new Runnable() {
                @Override public void run() {
                    try { p.stop(); } catch (Throwable ignored) { }
                    try { p.release(); } catch (Throwable ignored) { }
                }
            }, "vlc-stop");
            ioThread.setPriority(Thread.MIN_PRIORITY);
            ioThread.start();
        }
        emit(State.IDLE);
    }

    @Override
    public void release() {
        released = true;
        main.removeCallbacks(tick);
        final org.videolan.libvlc.MediaPlayer p = player;
        final org.videolan.libvlc.LibVLC lv = libVlc;
        player = null;
        libVlc = null;
        surfaceView = null;

        // ⚠ 关键顺序：**先 detachViews，再 release**。
        //
        // 以前直接 release，而 SurfaceView 上还挂着视频输出。
        // native 侧把 LibVLC 销毁后，Surface 的尺寸/销毁回调仍会打到
        // 已经释放的对象上 —— use-after-free，进程直接 SIGSEGV。
        // 这就是"切内核必闪退"的根因。
        if (viewsAttached && p != null) {
            try { p.getVLCVout().detachViews(); } catch (Throwable ignored) { }
            viewsAttached = false;
        }

        // 释放同样走后台：LibVLC.release() 会等内部回放线程收尾
        new Thread(new Runnable() {
            @Override public void run() {
                if (p != null) {
                    try { p.stop(); } catch (Throwable ignored) { }
                    try { p.release(); } catch (Throwable ignored) { }
                }
                if (lv != null) {
                    try { lv.release(); } catch (Throwable ignored) { }
                }
            }
        }, "vlc-release").start();
    }

    @Override public String tag() { return PlaybackEngine.VLC.name(); }

    @Override public Mode mode() { return mode; }

    @Override public boolean canSeek() { return mode != Mode.LIVE && duration > 0; }

    @Override
    public void seekTo(long positionMs) {
        if (player != null && canSeek()) {
            try { player.setTime(positionMs); } catch (Throwable ignored) { }
        }
    }

    @Override
    public long positionMs() {
        if (player == null) return -1;
        try { return player.getTime(); } catch (Throwable t) { return -1; }
    }

    @Override public long durationMs() { return mode == Mode.LIVE ? -1 : duration; }

    @Override public long bufferedPositionMs() { return 0; }

    public String currentUrl() { return currentUrl; }

    private void emit(final State s) {
        if (cb != null) main.post(new Runnable() {
            @Override public void run() { if (cb != null) cb.onState(s); }
        });
    }
}
