package com.mintv.tv.ui.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.interfaces.IMedia;
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
    /** 0=自动 1=内置硬解 2=软件解码（来自设置项） */
    private int hwMode = 0;

    /** 设置解码方式。下次创建 LibVLC 时生效。 */
    public void setHwMode(int mode) {
        // 只记下来。下一次 play() 会作为 Media 选项带上，
        // **不重建 LibVLC** —— 重建会短暂出现两个实例抢音频设备。
        hwMode = mode;
    }

    /** 解码方式对应的 VLC 选项值。 */
    private static String hwValue(int mode) {
        switch (mode) {
            case 1:  return "mediacodec";   // 强制 Android MediaCodec
            case 2:  return "none";         // 强制软解
            default: return "any";          // VLC 原生默认：逐级尝试，失败回落
        }
    }
    private SurfaceView surfaceView;
    private android.view.TextureView textureView;
    private Callback cb;
    private Mode mode = Mode.LIVE;
    private boolean released = false;

    /**
     * 播放器操作队列。
     *
     * <p>libvlc 的 pause/resume 是 JNI 同步调用，内部要等解码线程让出
     * 控制权。解码线程正忙时（刚换台、高码率），主线程被阻塞几十到几百
     * 毫秒 —— 表现就是"点暂停，图标过一会儿才变"。
     *
     * <p>单线程队列：既离开主线程，又保证 pause 后立刻 resume 不会乱序。
     * UI 状态由 pausedByUser 维护，不等 native 返回。
     */
    private final java.util.concurrent.ExecutorService opQueue =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "vlc-op");
                            t.setDaemon(true);
                            return t;
                        }
                    });
    /**
     * 用户按了暂停。
     *
     * <p>不能用 MediaPlayer.isPlaying() 反推：直播流在 buffering 状态下
     * isPlaying() 返回 false，于是"点暂停"被当成"该恢复了"，永远停不下来。
     * 截图里那个「缓冲中…」就是这种情况。所以自己记一份。
     */
    private boolean pausedByUser = false;

    /** 上次上报的轨道指纹，避免每个 Vout 事件都回调 UI。 */
    private String tracksSig;
    private boolean firstFrame = false;
    private boolean viewsAttached = false;
    private String currentUrl;
    private long duration = -1;

    // ---- 实时倍率采样（Δ播放时间 / Δ墙钟时间）----
    private long speedLastTime = -1;
    private long speedLastWall = 0;
    private float speedEma = 1f;      // 指数滑动平均，压掉瞬时抖动

    /**
     * 最近一次 EncounteredError 的时刻，0 = 当前没报错。
     *
     * <p>为什么单独记：源断了以后，VLC 缓冲区里剩下的内容还会继续播几秒，
     * getTime() 照常递增 —— 实时倍率算出来还是 1.00x，位置判据和速度判据
     * 都不会动。用户看到的是「画面卡在最后一帧、右上角写着源失效」，
     * 而程序觉得一切正常。必须把"报错"这个信号单独接进判据。
     */
    private long errorAt = 0;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (released || player == null) return;
            long t = 0;
            try { t = player.getTime(); } catch (Throwable ignored) { }
            // 源报错时强制按"速度 0"上报。
            //
            // 这段时间缓冲还在播（getTime 递增），正常采样会算出 1.00x，
            // 两条判据都不会动。强制 0 之后，速度判据的计时会正常推进，
            // 到达「超时换源」秒数就自动换下一条。重连成功收到 Playing
            // 时 errorAt 归零，恢复正常采样。
            if (errorAt > 0) {
                if (cb != null) cb.onRealtimeSpeed(0f);
                if (cb != null) cb.onTimeline(t, duration, 0);
                main.postDelayed(this, 500);
                return;
            }
            if (cb != null && t > 0) {
                long wall = android.os.SystemClock.uptimeMillis();
                if (speedLastTime > 0 && wall > speedLastWall) {
                    float inst = (t - speedLastTime) / (float) (wall - speedLastWall);
                    if (inst < 0f) inst = 0f;
                    if (inst > 3f) inst = 3f;      // 换源/seek 之后的跳变，掐掉
                    // 0.3 的平滑系数：约 2 秒收敛，不会一直跳
                    speedEma = speedEma * 0.7f + inst * 0.3f;
                    cb.onRealtimeSpeed(speedEma);
                }
                speedLastTime = t;
                speedLastWall = wall;
            }
            if (cb != null) {
                // ⚠ 直播也要把真实播放位置传上去。
                //
                // 原来写的是 onTimeline(-1, -1, 0)：位置恒定 -1，
                // 而卡顿检测 checkStall 的判据正是「位置有没有变化」——
                // 于是每一路直播都会被判定为卡住，画面明明在动却一直弹
                // 「播放卡住，该频道只有一条线路」。
                // 直播没有时长（duration 给 -1），但 getTime() 是递增的，
                // 正好用来判断是否还在走。
                if (mode == Mode.LIVE) cb.onTimeline(t, -1, 0);
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
        if (sv == null) {
            // 明确解绑：切到 TextureView 时调用
            if (attachedTarget == surfaceView) detachOutput();
            surfaceView = null;
            return;
        }
        textureView = null;      // 互斥：同一时刻只能有一个渲染目标
        this.surfaceView = sv;
        if (player != null) attach();
    }

    /**
     * 解绑当前渲染目标（后台线程）。
     *
     * <p>必须在被解绑的 view 变成 GONE 之前完成，否则 VLC 会继续往
     * 一个已经不可见的 surface 上刷帧 —— 用户看到的就是黑屏。
     */
    private void detachOutput() {
        final org.videolan.libvlc.MediaPlayer p = player;
        viewsAttached = false;
        attachedTarget = null;
        if (p == null) return;
        opQueue.execute(new Runnable() {
            @Override public void run() {
                try { p.getVLCVout().detachViews(); } catch (Throwable ignored) { }
            }
        });
    }

    @Override public void setSurface(Surface s) {
        // VLC 这条路径无效，保留接口兼容
    }

    @Override public void setTextureView(android.view.TextureView tv) {
        if (tv == null) {
            // 明确解绑：切到 SurfaceView 时调用
            if (attachedTarget == textureView) detachOutput();
            textureView = null;
            return;
        }
        surfaceView = null;      // 互斥：同一时刻只能有一个渲染目标
        this.textureView = tv;
        if (player != null) attach();
    }

    private void ensureLibVlc(Context ctx) {
        if (libVlc != null) return;
        List<String> args = new ArrayList<>();
        // ⚠ 那两个 "--no-drop-late-frames / --no-skip-frames" 删掉了。
        //
        // 它们的含义是"迟到的帧也要画、绝不丢弃"。听起来很美好，实际上：
        // 解码跟不上时，未丢弃的帧会一帧帧堆积，播放进度越来越落后于
        // 音频 —— 看十分钟能差出好几秒，然后突然快进追帧，或者一直对不上。
        // 这就是"总感觉不同步"的根源。
        //
        // VLC 默认是丢迟到帧保同步（音画以时间为准），直播必须这样。
        //
        // 缓冲从 800ms 降到 500ms：起播更快，弱网仍有余量。
        args.add("--network-caching=500");
        args.add("--live-caching=500");
        args.add("--file-caching=500");
        // 解码方式**不在这里设**，见 play() 里的 media option。
        //
        // 原因：--avcodec-hw 是实例级参数，改了必须重建 LibVLC。
        // 而重建是异步释放旧实例 + 立刻建新实例，两个 LibVLC 会并存
        // 一小段时间，音频输出(AudioTrack)互相抢占 —— 这就是
        // "切一下解码方式再切回来就没声音"的根因。
        // 放到 Media 级选项后，解封装/音频/视频输出全部复用，
        // 切解码只是重新 open 一路流，不碰输出设备。
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

    /** 上一次 attach 的目标视图，用于幂等判断。 */
    private Object attachedTarget;

    /**
     * 安全回调：TextureView 销毁时只交还 SurfaceTexture，**不碰 libvlc**。
     *
     * <p>为什么必须有：
     * <pre>
     * TextureView.onSurfaceTextureDestroyed (主线程)
     *   → AWindow.SurfaceHelper.onSurfaceTextureDestroyed
     *   → AWindow.detachViews
     *   → MediaPlayer.setVideoTrackEnabled(false)
     *   → libvlc_video_set_track   ← 在这里等内核锁
     *   → NonPI::MutexLockWithTimeout  ← 卡 5.9 秒
     * </pre>
     * 实测抓到的 ANR：
     * <pre>
     *   ANR in com.mintv.tvui/com.mintv.tv.ui.MainActivity
     *   Input dispatching timed out, 主线程卡 5909ms
     *   libvlc_video_set_track+28 / nativeSetVideoTrack
     * </pre>
     *
     * <p>换掉 listener 之后，销毁走系统默认路径（返回 true 由框架释放
     * SurfaceTexture），libvlc 那条同步回调链根本不会触发。
     */
    private static final android.view.TextureView.SurfaceTextureListener SAFE_LISTENER =
            new android.view.TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(
                        android.graphics.SurfaceTexture st, int w, int h) { }
                @Override public void onSurfaceTextureSizeChanged(
                        android.graphics.SurfaceTexture st, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(
                        android.graphics.SurfaceTexture st) { return true; }
                @Override public void onSurfaceTextureUpdated(
                        android.graphics.SurfaceTexture st) { }
            };

    /**
     * 进后台：先摘掉 VLC 的 TextureView 回调，再异步解绑视频输出。
     *
     * <p>顺序不能反。先解绑再摘 listener 的话，中间那段 TextureView
     * 还挂着 VLC 的回调，一旦此刻被销毁，照样在主线程卡锁。
     */
    public void detachForBackground() {
        final org.videolan.libvlc.MediaPlayer p = player;
        final android.view.TextureView tv = textureView;
        if (tv != null) {
            // 换成安全回调（主线程，极快，不碰 native）
            tv.setSurfaceTextureListener(SAFE_LISTENER);
        }
        if (p == null || !viewsAttached) return;
        viewsAttached = false;
        attachedTarget = null;
        // detachViews 会走 libvlc，可能等解码线程，必须离开主线程
        new Thread(new Runnable() {
            @Override public void run() {
                try { p.getVLCVout().detachViews(); } catch (Throwable ignored) { }
            }
        }, "vlc-detach").start();
    }

    /**
     * 把视频/音频轨道信息报给 UI（OSD 底栏的分辨率、编码、帧率、码率）。
     *
     * <p>这条链以前在 Media3Backend 里，靠 Exo 的 onTracksChanged 拿 Format。
     * 删 Exo 时整条上报一起没了，所以 OSD 只剩「源 1/1」和网速。
     *
     * <p>VLC 的取法：视频用 {@code getCurrentVideoTrack()}（带 width/height/
     * frameRate/bitrate/codec）；音频 {@code IMedia.Track} 没有按类型取的 API，
     * 只能遍历后用 instanceof 认。
     */
    private void reportTracks() {
        if (cb == null) return;
        final MediaPlayer p = player;
        if (p == null) return;
        try {
            String res = null, vc = null, fps = null, ac = null, chs = null, br = null;

            IMedia.VideoTrack v = p.getCurrentVideoTrack();
            if (v != null) {
                if (v.width > 0 && v.height > 0) res = v.width + "x" + v.height;
                if (v.codec != null) vc = prettyCodec(v.codec);
                if (v.frameRateNum > 0 && v.frameRateDen > 0) {
                    float f = v.frameRateNum / (float) v.frameRateDen;
                    // 25/1 这种整数帧率不要显示成 25.00
                    fps = (Math.abs(f - Math.round(f)) < 0.05f)
                            ? (Math.round(f) + " fps")
                            : (String.format(java.util.Locale.US, "%.2f fps", f));
                }
                if (v.bitrate > 0) br = (v.bitrate / 1000) + " kbps";
            }

            IMedia im = p.getMedia();
            if (im != null) {
                int n = im.getTrackCount();
                for (int i = 0; i < n; i++) {
                    IMedia.Track t = im.getTrack(i);
                    if (t instanceof IMedia.AudioTrack) {
                        IMedia.AudioTrack a = (IMedia.AudioTrack) t;
                        if (a.codec != null) ac = prettyCodec(a.codec);
                        if (a.channels > 0) chs = a.channels + "ch";
                        break;
                    }
                }
            }
            String sig = res + "|" + vc + "|" + ac + "|" + chs + "|" + fps + "|" + br;
            if (sig.equals(tracksSig)) return;   // 没变就不打扰 UI
            tracksSig = sig;
            cb.onVideoFormat(res, vc, ac, chs, fps);
            if (br != null) cb.onBitrate(br);
        } catch (Throwable ignored) {
            // 拿不到就不显示，不要让诊断信息把播放搞挂
        }
    }

    /** VLC 给的是 "h264"/"hevc"/"mp2a" 这种短名，换成台标栏能看的写法。 */
    private static String prettyCodec(String c) {
        if (c == null) return null;
        // VLC 有时给 "H264" 有时给 "h264"，统一小写再比
        switch (c.toLowerCase(java.util.Locale.US)) {
            case "h264": case "avc1": return "H.264";
            case "hevc": case "h265": return "H.265";
            case "mpeg2": case "mpgv": return "MPEG-2";
            case "avs2": return "AVS2";
            case "avs3": return "AVS3";
            case "mp2a": case "mpga": return "MP2";
            case "aac": case "mp4a": return "AAC";
            case "ac3": case "a52": return "AC3";
            case "eac3": return "EAC3";
            case "mp3": return "MP3";
            case "opus": return "Opus";
            default: return c.toUpperCase(java.util.Locale.US);
        }
    }

    private void attach() {
        if (player == null) return;
        Object target = surfaceView != null ? surfaceView : textureView;
        if (target == null) return;
        // 同一个 player 已经绑过同一个视图就不要再绑。
        // 重复 attachViews 会让 VLC 重新初始化 vout，表现为闪一下黑屏；
        // 而"先 detach 再 attach"更糟 —— 中间那段没有 vout，
        // 解码器出帧却没人接，就成了「有声音没画面」。
        if (target == attachedTarget) return;
        try {
            // ⚠ setWindowSize 必须在 attachViews 之前调用。
            //
            // 不调的话 VLC 不知道视口多大，就按解码出来的原始尺寸画
            // （实测日志：libvlc decoder: output 1920x1088 stride 0 0, crop 0 0 0 0），
            // 画面就贴在左上角、右边留一大块黑 —— 这正是"VLC 右边黑边"的根因。
            int w = surfaceView != null ? surfaceView.getWidth() : textureView.getWidth();
            int h = surfaceView != null ? surfaceView.getHeight() : textureView.getHeight();
            // 尺寸还是 0 说明布局没走完。这时硬 attach 会让 VLC 按解码
            // 原始尺寸往左上角画（实测 1920x1088，屏幕只显示一角），
            // 看起来就是"没画面"。等 onSurfaceTextureSizeChanged 再来。
            if (w <= 0 || h <= 0) return;
            player.getVLCVout().setWindowSize(w, h);
            if (surfaceView != null) player.getVLCVout().setVideoView(surfaceView);
            else player.getVLCVout().setVideoView(textureView);
            player.getVLCVout().attachViews();
            viewsAttached = true;
            attachedTarget = target;
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
        // ⚠ released 必须在起播时复位。
        // 以前 release() 把它置 true 之后就再没有地方置回 false，
        // 而 play() 第一行就是 if (released) return —— 于是在设置里改一次
        // 解码方式（setHwMode → release）之后，播放器永久罢工，怎么点都不播。
        released = false;
        pausedByUser = false;
        tracksSig = null;
        speedLastTime = -1;
        errorAt = 0;
        speedLastWall = 0;
        speedEma = 1f;
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
        attachedTarget = null;   // 新实例，必须重新绑视图
        player.setEventListener(new MediaPlayer.EventListener() {
            @Override public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        // VLC 带 --http-reconnect，断流会自动重连，
                        // 重连成功会重新收到 Playing —— 这时清掉报错标记。
                        errorAt = 0;
                        emit(State.READY);
                        break;
                    case MediaPlayer.Event.Buffering:
                        // 只有**还没出画面**的时候才把 Buffering 当作状态。
                        //
                        // 直播流的 buffering 百分比会长期停在 90 多并反复
                        // 触发这个事件，而 Playing（对应 READY）只在开头发
                        // 一次 —— 一旦被拉回 BUFFERING 就再没有事件来清空，
                        // 右下角永远挂着「缓冲中…」，哪怕画面在正常播。
                        if (!firstFrame && event.getBuffering() < 99f) {
                            emit(State.BUFFERING);
                        }
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        errorAt = android.os.SystemClock.uptimeMillis();
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
                        // 出画之后轨道信息才准，这时上报给 OSD 底栏。
                        reportTracks();
                        break;
                    default:
                        break;
                }
            }
        });

        attach();

        Media media = new Media(libVlc, Uri.parse(url));
        // 低延迟直播：不走本地缓存文件，直接吃网络流
        media.addOption(":network-caching=500");
        media.addOption(":live-caching=500");
        // 解码方式在这里给：Media 级选项，切换时不用重建 LibVLC
        media.addOption(":avcodec-hw=" + hwValue(hwMode));
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

    /**
     * 当前是否在播。
     *
     * <p>以前没实现这个，接口给了 default { return true; } ——
     * 于是"暂停/继续"永远走"正在播→暂停"分支，点了暂停就再也恢复不了。
     *
     * <p>libVLC 的 isPlaying() 在暂停后返回 false，正好符合语义。
     */
    @Override public boolean isPlaying() {
        if (player == null) return false;
        try { return player.isPlaying(); } catch (Throwable t) { return false; }
    }

    @Override public void pause() {
        final org.videolan.libvlc.MediaPlayer p = player;
        if (p == null) return;
        pausedByUser = true;          // UI 立即拿到新状态
        opQueue.execute(new Runnable() {
            @Override public void run() {
                try { p.pause(); } catch (Throwable ignored) { }
            }
        });
    }

    /** 当前是否处于「用户暂停」状态（不看缓冲态）。 */
    @Override public boolean isPaused() {
        return pausedByUser;
    }

    @Override public void resume() {
        final org.videolan.libvlc.MediaPlayer p = player;
        if (p == null) return;
        pausedByUser = false;
        opQueue.execute(new Runnable() {
            @Override public void run() {
                try { p.play(); } catch (Throwable ignored) { }
            }
        });
    }

    /**
     * 只摘画面，不做任何释放动作。
     *
     * <p>由 PlayerManager 在换内核时于主线程调用 —— 因为 Surface 相关操作
     * 最好留在主线程，而 stop/release 那些会抢 mutex 的调用在后台做。
     * detachViews 本身如果也卡（VLC 内部要等 vout 线程），会被 surfaceDestroyed
     * 自然兜住，不会死锁。
     */
    public void detachViewsSafe() {
        final org.videolan.libvlc.MediaPlayer p = player;
        if (p == null || !viewsAttached) return;
        viewsAttached = false;
        try { p.getVLCVout().detachViews(); } catch (Throwable ignored) { }
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
        attachedTarget = null;

        // ⚠ 关键顺序：**先 detachViews，再 release**。
        //
        // 以前直接 release，而 SurfaceView 上还挂着视频输出。
        // native 侧把 LibVLC 销毁后，Surface 的尺寸/销毁回调仍会打到
        // 已经释放的对象上 —— use-after-free，进程直接 SIGSEGV。
        // 这就是"切内核必闪退"的根因。
        // detach 已由 detachViewsSafe() 在换内核时于主线程做过；
        // 这里兜底一次（比如 release 被单独调用），但不在主线程做重活。
        if (viewsAttached && p != null) {
            viewsAttached = false;
            // 同样不能在主线程 detach：libvlc 的 detachViews 会等内核线程
            final org.videolan.libvlc.MediaPlayer dp = p;
            new Thread(new Runnable() {
                @Override public void run() {
                    try { dp.getVLCVout().detachViews(); } catch (Throwable ignored) { }
                }
            }, "vlc-detach-rel").start();
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

    @Override public String tag() { return "VLC"; }

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
