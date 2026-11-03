package com.mintv.tv.ui.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Media3 实现。只写 Java，media3 内部的少量 Kotlin 代码跟你无关。
 *
 * <p>「有声音没画面」是本项目最容易踩的坑，这里有四道防线：
 * <ol>
 *   <li><b>软解优先选择器</b>：设置里选「软件」时，把 c2.android / OMX.google 这类
 *       软解排到硬解前面。低端盒子上硬解「初始化成功但不出帧」极其常见；</li>
 *   <li><b>视频轨可用性检测</b>：轨信息一到就判断「有音频 + 视频轨不被支持」
 *       （AVS2/AVS3、HEVC 10bit、4K 超规格都是），立刻回调
 *       {@link Callback#onVideoUnavailable}，不再默默只放声音；</li>
 *   <li><b>首帧守护</b>：READY 之后 4 秒还没 onRenderedFirstFrame，先重绑 Surface，
 *       再 4 秒还没出帧就上报；</li>
 *   <li><b>Surface 重绑</b>：surfaceChanged、切台、回前台都重新 setVideoSurface，
 *       治「surface 绑早了 / 尺寸变化后解码器输出丢弃」这类时序黑屏。</li>
 * </ol>
 *
 * <p>低端盒子调参：LoadControl 压到 1500ms 起播 / 5s 最大缓冲，直播要的是快。
 * 切台必须 stop → setMediaSource → prepare，别用 seekTo 换源。
 */
@UnstableApi
public final class Media3Backend implements PlaybackBackend {

    /** 软解优先：把 softwareOnly 的解码器排到前面。 */
    private static final MediaCodecSelector SOFT_FIRST = new MediaCodecSelector() {
        @Override
        public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder,
                                                    boolean requiresTunnel)
                throws MediaCodecUtil.DecoderQueryException {
            List<MediaCodecInfo> list = new ArrayList<>(MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnel));
            Collections.sort(list, new Comparator<MediaCodecInfo>() {
                @Override public int compare(MediaCodecInfo a, MediaCodecInfo b) {
                    return (a.softwareOnly ? 0 : 1) - (b.softwareOnly ? 0 : 1);
                }
            });
            return list;
        }
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private Surface surface;
    private android.view.TextureView textureView;
    private Callback cb;
    private String currentUrl;
    private boolean released = false;
    private Mode mode = Mode.LIVE;
    private String lastDecoder = null;
    private boolean preferSoftware = false;

    /** 本次播放是否已经真的画出过一帧。 */
    private boolean firstFrameArrived = false;
    /** 已经为重绑 Surface 做过一次尝试，防止死循环。 */
    private boolean surfaceRebindTried = false;
    /** 本次播放是否已经报过「只有音频」 */
    private boolean noVideoReported = false;
    /** 有没有媒体项（避免 stop() 之后再触发重绑逻辑）。 */
    private boolean hasMedia = false;

    private final Runnable firstFrameGuard = new Runnable() {
        @Override public void run() {
            if (released || !hasMedia || firstFrameArrived) return;
            if (!surfaceRebindTried) {
                // 第一招：重绑 Surface。多数「解码器在跑但画面不到」是这个原因
                surfaceRebindTried = true;
                rebindSurface();
                main.postDelayed(this, 4000);
            } else {
                // 第二招：上报，让 UI 换源 / 提示切软解
                if (cb != null) cb.onVideoUnavailable("解码器已就绪但 8 秒无画面");
            }
        }
    };

    /** 诊断用：当前视频解码器名与轨道信息。 */
    public String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("mode=").append(mode);
        sb.append(" decoder=").append(lastDecoder == null ? "(未初始化)" : lastDecoder);
        sb.append(" soft=").append(preferSoftware);
        sb.append(" firstFrame=").append(firstFrameArrived);
        sb.append(" canSeek=").append(canSeek());
        sb.append(" dur=").append(durationMs());
        sb.append(" pos=").append(positionMs());
        if (player != null) {
            try {
                Tracks t = player.getCurrentTracks();
                boolean hasVideo = t.containsType(C.TRACK_TYPE_VIDEO);
                boolean hasAudio = t.containsType(C.TRACK_TYPE_AUDIO);
                sb.append(" videoTrack=").append(hasVideo);
                sb.append(" audioTrack=").append(hasAudio);
                sb.append(" vSupported=").append(t.isTypeSupported(C.TRACK_TYPE_VIDEO));
            } catch (Throwable ignored) { }
        }
        return sb.toString();
    }

    @Override
    public void setCallback(Callback cb) { this.cb = cb; }

    @Override
    public boolean canSeek() {
        // 直播没有可跳转时间轴，只有回看/时移才有
        if (player == null) return false;
        try {
            return player.isCurrentMediaItemSeekable();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 设置页「解码方式」：true = 软解优先。下一次播放生效。 */
    public void setPreferSoftwareDecoder(boolean soft) {
        if (this.preferSoftware == soft) return;
        this.preferSoftware = soft;
        // 渲染器在 build 时就固定了，必须重建播放器
        if (player != null) {
            Surface keep = surface;
            player.release();
            player = null;
            surface = keep;
        }
    }

    public boolean preferSoftwareDecoder() { return preferSoftware; }

    private ExoPlayer ensurePlayer(Context appCtx) {
        if (player != null) return player;

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        /* minBufferMs          */ 1_500,
                        /* maxBufferMs          */ 5_000,
                        /* bufferForPlaybackMs  */ 400,
                        /* bufferForPlaybackAfterRebufferMs */ 700)
                .build();

        // 硬解失败自动降级到软解 —— Media3 默认是关的，低端盒子必须打开
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(appCtx)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(preferSoftware ? SOFT_FIRST : MediaCodecSelector.DEFAULT);

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(appCtx);

        // 用宽容 HTTPS 数据源：很多直播源证书自签/过期，
        // 用系统默认校验会直接 IO_NETWORK_CONNECTION_FAILED，但内容其实是好的。
        LenientHttpDataSource.Factory http = new LenientHttpDataSource.Factory()
                .setUserAgent("MinTV/1.0")
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(12000);

        player = new ExoPlayer.Builder(appCtx, renderers)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();

        // 缩放到适配 Surface，尺寸为 0 时也不丢帧
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) notifyState(State.BUFFERING);
                else if (state == Player.STATE_READY) notifyState(State.READY);
            }

            @Override public void onPlayerError(PlaybackException error) {
                notifyState(State.ERROR);
                // getErrorCodeName() 只有 ERROR_CODE_IO_BAD_HTTP_STATUS 这种常量，
                // 真正的原因（哪一跳、什么状态码）在 message 和 cause 里。
                StringBuilder sb = new StringBuilder(error.getErrorCodeName());
                Throwable c = error.getCause();
                int depth = 0;
                while (c != null && depth++ < 4) {
                    String m = c.getMessage();
                    if (m != null && !m.isEmpty()) sb.append(" | ").append(m);
                    c = c.getCause();
                }
                android.util.Log.e("MinTV", "player error: " + sb);
                if (cb != null) cb.onError(sb.toString());
            }

            @Override public void onRenderedFirstFrame() {
                firstFrameArrived = true;
                main.removeCallbacks(firstFrameGuard);
                if (cb != null) cb.onFirstFrame();
            }


            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize vs) {
                if (vs == null || vs.width == 0 || vs.height == 0) return;
                final String res = vs.width + "x" + vs.height;
                // Media3 1.3.x 的 VideoSize 没有 frameRate 字段，
                // 帧率只能从 Format 的 frameRate 拿；拿不到就留 null，不编。
                final String fps = formatFps();
                if (cb != null) main.post(new Runnable() {
                    @Override public void run() { cb.onVideoFormat(res, null, null, null, fps); }
                });
                reportFormats();
            }

            @Override public void onTracksChanged(Tracks tracks) {
                checkVideoTracks(tracks);
                reportFormats();
            }
        });

        bindOutput(player);
        startTimelineTicker();
        return player;
    }

    /**
     * 关键检测：音频有了、视频轨却一个都不被支持 → 这个源在本机放不出画面。
     * 以前这里什么都不做，结果就是「有声音没画面」一直黑着播。
     */
    private void checkVideoTracks(Tracks tracks) {
        if (tracks == null || noVideoReported) return;
        boolean hasVideoGroup = false, videoOk = false, hasAudio = false;
        String videoMime = null;
        try {
            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() == C.TRACK_TYPE_VIDEO) {
                    hasVideoGroup = true;
                    for (int i = 0; i < g.length; i++) {
                        if (videoMime == null) {
                            androidx.media3.common.Format f = g.getTrackFormat(i);
                            if (f != null && f.sampleMimeType != null) videoMime = f.sampleMimeType;
                        }
                        if (g.isTrackSupported(i)) videoOk = true;
                    }
                } else if (g.getType() == C.TRACK_TYPE_AUDIO) {
                    hasAudio = true;
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        if (hasAudio && !videoOk) {
            noVideoReported = true;
            final String reason = hasVideoGroup
                    ? "视频编码本机不支持（" + videoMime + "）"
                    : "该流只有音频，没有视频轨";
            if (cb != null) cb.onVideoUnavailable(reason);
        }
    }

    /** 重新把 surface 交给播放器。治「解码器在跑但画面不到」的时序黑屏。 */
    private void rebindSurface() {
        if (player == null) return;
        player.setVideoSurface(null);
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (player == null || released) return;
                if (textureView != null) player.setVideoTextureView(textureView);
                else if (surface != null && surface.isValid()) player.setVideoSurface(surface);
            }
        }, 120);
    }

    @Override
    public void setSurface(Surface s) {
        surface = s;
        if (player != null && textureView == null) player.setVideoSurface(s);
    }

    @Override
    public void setTextureView(android.view.TextureView tv) {
        textureView = tv;
        if (player != null) {
            if (tv != null) player.setVideoTextureView(tv);
            else player.setVideoSurface(surface);
        }
    }

    /** 起播前把输出面绑好，别等 prepare 之后再补。 */
    private void bindOutput(ExoPlayer p) {
        if (textureView != null) p.setVideoTextureView(textureView);
        else if (surface != null && surface.isValid()) p.setVideoSurface(surface);
    }

    /** 带自定义请求头播放（源里写成 url|User-Agent=xx 的情况）。 */
    public void play(Context appCtx, String url, Map<String, String> headers, Mode m) {
        this.mode = m;
        playInternal(appCtx, url, headers);
    }

    @Override
    public void play(Context appCtx, String url, Mode m) {
        this.mode = m;
        playInternal(appCtx, url, null);
    }

    @Override
    public void play(Context appCtx, String url, boolean live) {
        this.mode = live ? Mode.LIVE : Mode.VOD;
        playInternal(appCtx, url, null);
    }

    private void playInternal(Context appCtx, String url, Map<String, String> headers) {
        if (released) return;
        ExoPlayer p = ensurePlayer(appCtx);

        // 先彻底停，释放解码器 —— 低端机上同时跑两个解码器必黑屏
        p.stop();
        p.clearMediaItems();

        // 每次起播都重置首帧状态，并把 surface 重新绑一次
        firstFrameArrived = false;
        surfaceRebindTried = false;
        noVideoReported = false;
        hasMedia = true;
        bindOutput(p);

        currentUrl = url;
        notifyState(State.BUFFERING);

        // 用宽容 HTTPS 数据源：很多直播源证书自签/过期，
        // 用系统默认校验会直接 IO_NETWORK_CONNECTION_FAILED，但内容其实是好的。
        LenientHttpDataSource.Factory http = new LenientHttpDataSource.Factory()
                .setUserAgent("MinTV/1.0")
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(12000);
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                // User-Agent 单独设，其余进默认请求头
                if ("User-Agent".equalsIgnoreCase(e.getKey())) {
                    http.setUserAgent(e.getValue());
                }
            }
            http.setDefaultRequestProperties(headers);
        }

        headersSummary = summarize(headers);
        MediaItem item = MediaItem.fromUri(Uri.parse(url));
        // 用 DefaultMediaSourceFactory 按内容自动识别 HLS / Progressive，
        // 比手动判断扩展名稳（很多源的 m3u8 带 ?xxx 参数或走 302）
        MediaSource source = new DefaultMediaSourceFactory(http).createMediaSource(item);

        p.setMediaSource(source);
        p.setPlayWhenReady(true);
        p.prepare();

        // READY 之后 4 秒还没首帧就开始自愈
        main.removeCallbacks(firstFrameGuard);
        main.postDelayed(firstFrameGuard, 4000);
    }

    @Override
    public void stop() {
        hasMedia = false;
        main.removeCallbacks(firstFrameGuard);
        if (player != null) player.stop();
        notifyState(State.IDLE);
    }

    @Override
    public void release() {
        released = true;
        hasMedia = false;
        main.removeCallbacks(firstFrameGuard);
        if (timelineTask != null) main.removeCallbacks(timelineTask);
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

    private String headersSummary = "";

    @Override public String headersSummary() { return headersSummary; }

    private static String summarize(java.util.Map<String, String> h) {
        if (h == null || h.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : h.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    @Override public Mode mode() { return mode; }

    @Override public String tag() { return PlaybackEngine.EXO.name(); }

    /**
     * 只有时移 / 点播才允许 seek。
     * Media3 对直播 HLS 调用 seekTo 会跳到直播窗口内最近的可播放点，
     * 在没有 seekable window 的情况下会抛 IllegalSeekPositionException。
     */
    @Override public void pause() {
        if (player != null) main.post(new Runnable() {
            @Override public void run() { player.pause(); }
        });
    }

    @Override public void resume() {
        if (player != null) main.post(new Runnable() {
            @Override public void run() { player.play(); }
        });
    }

    @Override public boolean isPlaying() {
        return player != null && player.getPlayWhenReady();
    }

    @Override
    public void seekTo(long positionMs) {
        if (player == null || !canSeek()) return;
        long dur = durationMs();
        long target = positionMs;
        if (dur > 0) target = Math.max(0, Math.min(target, dur));
        player.seekTo(target);
    }

    @Override
    public long positionMs() {
        return player == null ? -1 : player.getCurrentPosition();
    }

    @Override
    public long durationMs() {
        if (player == null) return -1;
        long d = player.getDuration();
        return (d == C.TIME_UNSET || d <= 0) ? -1 : d;
    }

    /** MIME → 人看的编码名。取不到就返回 null，不在 OSD 上编。 */
    private static String codecName(String mime) {
        if (mime == null) return null;
        switch (mime) {
            case "video/avc": return "H.264";
            case "video/hevc": return "H.265";
            case "video/av01": return "AV1";
            case "video/x-vnd.on2.vp9": return "VP9";
            case "video/x-vnd.on2.vp8": return "VP8";
            case "video/mp4v-es": return "MPEG-4";
            case "video/avs2": return "AVS2";
            case "video/avs3": return "AVS3";
            case "audio/mp4a-latm": return "AAC";
            case "audio/mpeg": return "MP3";
            case "audio/ac3": return "AC3";
            case "audio/eac3": return "EAC3";
            case "audio/opus": return "Opus";
            case "audio/vnd.dts": return "DTS";
            default: return mime.startsWith("video/") || mime.startsWith("audio/")
                    ? mime.substring(6).toUpperCase(java.util.Locale.US) : null;
        }
    }

    /**
     * 一把抓出 OSD 需要的技术信息：分辨率 / 帧率 / 视频编码 / 音频编码 / 码率。
     * 每项都可能拿不到，拿不到就传 null，OSD 会自动不画那一格 —— 不编数据。
     */
    private void reportFormats() {
        if (player == null || cb == null) return;
        String res = null, fps = null, vcodec = null, acodec = null, bitrate = null;
        try {
            Tracks tracks = player.getCurrentTracks();
            for (Tracks.Group g : tracks.getGroups()) {
                for (int i = 0; i < g.length; i++) {
                    if (!g.isTrackSupported(i)) continue;
                    androidx.media3.common.Format f = g.getTrackFormat(i);
                    if (f == null) continue;
                    if (g.getType() == C.TRACK_TYPE_VIDEO) {
                        if (vcodec == null) vcodec = codecName(f.sampleMimeType);
                        if (fps == null && f.frameRate > 0) fps = Math.round(f.frameRate) + "fps";
                        if (f.width > 0 && f.height > 0 && res == null) res = f.width + "x" + f.height;
                        if (bitrate == null && f.bitrate > 0) bitrate = mbps(f.bitrate);
                    } else if (g.getType() == C.TRACK_TYPE_AUDIO) {
                        if (acodec == null) acodec = codecName(f.sampleMimeType);
                    }
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        final String fr = res, ff = fps, fv = vcodec, fa = acodec, fb = bitrate;
        main.post(new Runnable() {
            @Override public void run() {
                if (cb != null) cb.onVideoFormat(fr, fv, fa, null, ff, fb);
            }
        });
    }

    private static String mbps(long bps) {
        if (bps >= 1000_000) return String.format(java.util.Locale.US, "%.1fMbps", bps / 1000_000f);
        return String.format(java.util.Locale.US, "%dKbps", bps / 1000);
    }

    /** 从当前视频轨的 Format 里取帧率。取不到返回 null。 */
    private String formatFps() {
        if (player == null) return null;
        try {
            Tracks tracks = player.getCurrentTracks();
            if (tracks == null) return null;
            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
                for (int i = 0; i < g.length; i++) {
                    if (!g.isTrackSupported(i)) continue;
                    androidx.media3.common.Format f = g.getTrackFormat(i);
                    if (f != null && f.frameRate > 0) {
                        return Math.round(f.frameRate) + "fps";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Runnable timelineTask;

    private void startTimelineTicker() {
        if (timelineTask != null) return;
        timelineTask = new Runnable() {
            @Override public void run() {
                if (player == null || released) return;
                if (cb != null) {
                    cb.onTimeline(positionMs(), durationMs(), bufferedPositionMs());
                }
                main.postDelayed(this, 500);
            }
        };
        main.postDelayed(timelineTask, 500);
    }

    private void notifyState(State s) {
        final State st = s;
        if (cb != null) {
            main.post(new Runnable() {
                @Override public void run() { if (cb != null) cb.onState(st); }
            });
        }
    }
}
