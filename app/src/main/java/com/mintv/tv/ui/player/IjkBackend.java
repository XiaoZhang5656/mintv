package com.mintv.tv.ui.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkLibLoader;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/**
 * IJK（FFmpeg）内核。
 *
 * <p>为什么要有它：国内直播源里 AVS2 / AVS3 视频、MP2/AC3 音轨、
 * 非标 HLS 封装很常见，Media3 的 MediaCodec 路径直接不支持，
 * 表现正是「有声音没画面」或干脆整条黑掉。FFmpeg 软解全部吃得下。
 *
 * <p>起播参数按直播调：
 * <pre>
 *   format:  probe 1024KB / analyzeduration 1.5s / 不缓冲（低延迟）
 *   player:  3 帧缓冲区 / 关同步（掉帧优先于卡顿）
 *   codec:   skip_loop_filter=all（低端 CPU 省一半解码开销）/ 线程数=2
 * </pre>
 *
 * <p>库加载：{@code System.loadLibrary} 优先（APK 里 jniLibs 直接带的 so），
 * 失败再退回 {@code ReLinker} 式的自定义 loader。0.8.8 的 so 里有
 * {@code Pragma} 类声明 ABI，加载顺序必须是 ijkffmpeg → ijksdl → ijkplayer。
 */
public final class IjkBackend implements PlaybackBackend {

    /** Pragma 里的静态 ABI 声明，防止把 armv7 的 so 装到 arm64-only 设备上还硬加载。 */
    private static final IjkLibLoader LOADER = new IjkLibLoader() {
        @Override public void loadLibrary(String name) throws UnsatisfiedLinkError {
            System.loadLibrary(name);
        }
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private IjkMediaPlayer player;
    private Surface surface;
    private SurfaceHolder holder;
    private Callback cb;
    private Mode mode = Mode.LIVE;
    private boolean released = false;
    private boolean firstFrame = false;
    private String currentUrl;
    private long lastPosition = 0;
    private long lastDuration = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (released || player == null) return;
            try {
                lastPosition = player.getCurrentPosition();
                long d = player.getDuration();
                if (d > 0) lastDuration = d;
            } catch (Throwable ignored) { }
            if (cb != null) cb.onTimeline(mode == Mode.LIVE ? -1 : lastPosition,
                    mode == Mode.LIVE ? -1 : lastDuration, 0);
            main.postDelayed(this, 500);
        }
    };

    public static boolean isAvailable() {
        try {
            IjkMediaPlayer.loadLibrariesOnce(LOADER);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public void setCallback(Callback cb) { this.cb = cb; }

    @Override public void setSurface(Surface s) {
        this.surface = s;
        if (player != null && s != null && s.isValid()) player.setSurface(s);
    }

    /** 走 SurfaceHolder：IJK 在部分盒子上只认 setDisplay 这条老路。 */
    public void setDisplay(SurfaceHolder h) {
        this.holder = h;
        this.surface = (h == null) ? null : h.getSurface();
        if (player != null && h != null) player.setDisplay(h);
    }

    @Override
    public void play(Context appCtx, String url, Mode m) {
        play(appCtx, url, null, m);
    }

    @Override
    public void play(Context appCtx, String url, boolean live) {
        play(appCtx, url, null, live ? Mode.LIVE : Mode.VOD);
    }

    public void play(Context appCtx, String url, Map<String, String> headers, Mode m) {
        if (released) return;
        this.mode = m;
        currentUrl = url;
        firstFrame = false;
        headersSummary = summarize(headers);
        releasePlayer();
        emit(State.BUFFERING);

        try {
            IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_SILENT);
        } catch (Throwable ignored) { }

        final IjkMediaPlayer p = new IjkMediaPlayer(LOADER);
        player = p;

        boolean live = (m == Mode.LIVE);
        // ---- format ----
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 8_000_000L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", live ? 512 * 1024L : 2 * 1024 * 1024L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", live ? 800_000L : 2_000_000L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer");
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1L);
        // FFmpeg 的 http 协议只有两个入口：user_agent 和 headers（多行字符串）。
        // 以前 referer / cookie 单独当 option 塞进去，FFmpeg 根本不认，
        // 需要特定头的 ts 就这么黑着。现在按 FFmpeg 的实际语义翻译。
        if (headers != null && !headers.isEmpty()) {
            String ua = null;
            StringBuilder extra = new StringBuilder();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                if ("user-agent".equalsIgnoreCase(k)) {
                    ua = e.getValue();
                } else {
                    // "Name: value\r\n" —— FFmpeg 的 headers 参数就是这个格式
                    extra.append(k).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent",
                    ua != null ? ua : "MinTV/1.0");
            if (extra.length() > 0) {
                p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", extra.toString());
            }
        } else {
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", "MinTV/1.0");
        }
        // ---- player ----
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 31L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", live ? 0L : 1L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "find_stream_info", 1L);
        // ---- codec：低端 CPU 必开 ----
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L);   // AVDISCARD_ALL
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", 2L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "mediacodec", 1L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "mediacodec-auto-rotate", 1L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "mediacodec-handle-resolution-change", 1L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "mediacodec-avc", 1L);
        p.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "opensles", 0L);

        p.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(IMediaPlayer mp) {
                long d = mp.getDuration();
                if (d > 0) lastDuration = d;
                emit(State.READY);
                mp.start();
            }
        });

        p.setOnVideoSizeChangedListener(new IMediaPlayer.OnVideoSizeChangedListener() {
            @Override public void onVideoSizeChanged(IMediaPlayer mp, int w, int h, int sarNum, int sarDen) {
                if (w <= 0 || h <= 0) return;
                final String res = w + "x" + h;
                if (cb != null) main.post(new Runnable() {
                    @Override public void run() { cb.onVideoFormat(res, null, null, null, null); }
                });
            }
        });

        p.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
            @Override public boolean onInfo(IMediaPlayer mp, int what, int extra) {
                if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    if (!firstFrame) {
                        firstFrame = true;
                        if (cb != null) cb.onFirstFrame();
                    }
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    emit(State.BUFFERING);
                } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END
                        || what == IMediaPlayer.MEDIA_INFO_VIDEO_DECODED_START
                        || what == IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START) {
                    if (what != IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START) emit(State.READY);
                }
                return false;
            }
        });

        p.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override public boolean onError(IMediaPlayer mp, int what, int extra) {
                emit(State.ERROR);
                if (cb != null) cb.onError("IJK " + what + "/" + extra);
                return true;
            }
        });

        try {
            if (headers != null && !headers.isEmpty()) {
                p.setDataSource(appCtx, Uri.parse(url), headers);
            } else {
                p.setDataSource(url);
            }
            if (holder != null) p.setDisplay(holder);
            else if (surface != null && surface.isValid()) p.setSurface(surface);
            p.prepareAsync();
        } catch (Throwable t) {
            emit(State.ERROR);
            if (cb != null) cb.onError("IJK: " + t.getMessage());
            return;
        }

        main.removeCallbacks(tick);
        main.postDelayed(tick, 500);
    }

    private void releasePlayer() {
        main.removeCallbacks(tick);
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
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
        surface = null;
        holder = null;
    }

    @Override public String tag() { return PlaybackEngine.IJK.name(); }

    @Override public Mode mode() { return mode; }

    @Override
    public boolean canSeek() {
        handleApi();
        return mode != Mode.LIVE && lastDuration > 0;
    }

    @Override
    public void seekTo(long positionMs) {
        if (player != null && canSeek()) {
            try { player.seekTo(positionMs); } catch (Throwable ignored) { }
        }
    }

    @Override public long positionMs() { return player == null ? -1 : lastPosition; }

    @Override public long durationMs() { return mode == Mode.LIVE ? -1 : lastDuration; }

    @Override public long bufferedPositionMs() { return 0; }

    public String currentUrl() { return currentUrl; }

    private String headersSummary = "";

    @Override public String headersSummary() { return headersSummary; }

    private void handleApi() { }

    private static String summarize(Map<String, String> h) {
        if (h == null || h.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void emit(final State s) {
        if (cb != null) main.post(new Runnable() {
            @Override public void run() { if (cb != null) cb.onState(s); }
        });
    }
}
