package com.gg.tv.ui.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;

import java.util.Map;

/**
 * Media3 实现。只写 Java，media3 内部的少量 Kotlin 代码跟你无关。
 *
 * 针对低端盒子的三处调参：
 *   1. LoadControl 压到 500ms 起播 / 8s 最大缓冲 —— 直播要的是「快」，不是「稳」；
 *   2. 所有请求统一走一个 DefaultHttpDataSource.Factory，带 UA 和超时；
 *   3. 切台必须 stop → setMediaItem → prepare，千万别用 seekTo 换源。
 */
@OptIn(markerClass = UnstableApi.class)
public final class Media3Backend implements PlaybackBackend {

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private Surface surface;
    private Callback cb;
    private String currentUrl;
    private boolean released = false;

    @Override
    public void setCallback(Callback cb) { this.cb = cb; }

    private ExoPlayer ensurePlayer(Context appCtx) {
        if (player != null) return player;

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        /* minBufferMs          */ 3_000,
                        /* maxBufferMs          */ 8_000,
                        /* bufferForPlaybackMs  */ 500,
                        /* bufferForPlaybackAfterRebufferMs */ 800)
                .build();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("TVUI/1.0")
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
                .setAllowCrossProtocolRedirects(true);

        player = new ExoPlayer.Builder(appCtx)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http))
                .build();

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) notifyState(State.BUFFERING);
                else if (state == Player.STATE_READY) notifyState(State.READY);
            }

            @Override public void onPlayerError(PlaybackException error) {
                notifyState(State.ERROR);
                if (cb != null) cb.onError(error.getErrorCodeName());
            }

            @Override public void onRenderedFirstFrame() {
                if (cb != null) cb.onFirstFrame();
            }
        });

        if (surface != null) player.setVideoSurface(surface);
        return player;
    }

    @Override
    public void setSurface(Surface s) {
        surface = s;
        if (player != null) player.setVideoSurface(s);
    }

    /** 带自定义请求头播放（源里写成 url|User-Agent=xx 的情况）。 */
    public void play(Context appCtx, String url, Map<String, String> headers, boolean live) {
        if (released) return;
        ExoPlayer p = ensurePlayer(appCtx);
        if (surface != null) p.setVideoSurface(surface);
        p.stop();
        p.clearMediaItems();

        currentUrl = url;
        notifyState(State.BUFFERING);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("TVUI/1.0")
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
                .setAllowCrossProtocolRedirects(true);
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                // User-Agent 单独设，其余进默认请求头
                if ("User-Agent".equalsIgnoreCase(e.getKey())) {
                    http.setUserAgent(e.getValue());
                }
            }
            http.setDefaultRequestProperties(headers);
        }

        MediaItem item = MediaItem.fromUri(Uri.parse(url));
        String lower = url.toLowerCase();
        MediaSource source;
        if (lower.contains(".m3u8")) {
            source = new HlsMediaSource.Factory(http).createMediaSource(item);
        } else {
            // mp4 / flv / ts 直链走通用解析器
            source = new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(http)
                    .createMediaSource(item);
        }

        p.setMediaSource(source);
        p.setPlayWhenReady(true);
        p.prepare();
    }

    @Override
    public void play(Context appCtx, String url, boolean live) {
        if (released) return;
        ExoPlayer p = ensurePlayer(appCtx);

        if (surface != null) p.setVideoSurface(surface);

        // 先彻底停，释放解码器 —— 低端机上同时跑两个解码器必黑屏
        p.stop();
        p.clearMediaItems();

        currentUrl = url;
        notifyState(State.BUFFERING);

        MediaItem item = MediaItem.fromUri(Uri.parse(url));
        MediaSource source = new HlsMediaSource.Factory(
                new DefaultHttpDataSource.Factory().setUserAgent("TVUI/1.0"))
                .createMediaSource(item);

        p.setMediaSource(source);
        p.setPlayWhenReady(true);
        p.prepare();
    }

    @Override
    public void stop() {
        if (player != null) player.stop();
        notifyState(State.IDLE);
    }

    @Override
    public void release() {
        released = true;
        if (player != null) {
            player.release();
            player = null;
        }
        surface = null;
    }

    @Override
    public long bufferedPositionMs() {
        return player == null ? 0 : player.getBufferedPosition();
    }

    public String currentUrl() { return currentUrl; }

    private void notifyState(State s) {
        final State st = s;
        if (cb != null) {
            main.post(new Runnable() {
                @Override public void run() { if (cb != null) cb.onState(st); }
            });
        }
    }
}
