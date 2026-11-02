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
    private SurfaceView surfaceView;
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

    public static boolean isAvailable() {
        try {
            Class.forName("org.videolan.libvlc.LibVLC");
            return true;
        } catch (Throwable t) {
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
        if (player == null || surfaceView == null) return;
        try {
            player.getVLCVout().setVideoView(surfaceView);
            player.getVLCVout().attachViews();
            viewsAttached = true;
        } catch (Throwable t) {
            if (cb != null) cb.onError("VLC 绑定画面失败: " + t.getMessage());
        }
    }

    @Override
    public void play(Context appCtx, String url, Mode m) { play(appCtx, url, null, m); }

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

        releasePlayer();
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

        if (surfaceView != null) attach();

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

    @Override public boolean supportsHeaders() { return true; }

    private void releasePlayer() {
        main.removeCallbacks(tick);
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
            viewsAttached = false;
        }
    }

    @Override
    public void stop() {
        main.removeCallbacks(tick);
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
        }
        emit(State.IDLE);
    }

    @Override
    public void release() {
        released = true;
        releasePlayer();
        if (libVlc != null) {
            try { libVlc.release(); } catch (Throwable ignored) { }
            libVlc = null;
        }
        surfaceView = null;
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
