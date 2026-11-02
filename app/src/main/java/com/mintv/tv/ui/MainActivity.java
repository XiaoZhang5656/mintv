package com.mintv.tv.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mintv.tv.data.CatchupProbe;
import com.mintv.tv.data.EpgParser;
import com.mintv.tv.data.EpgStore;
import com.mintv.tv.data.LogoLoader;
import com.mintv.tv.data.PlaylistParser;
import com.mintv.tv.data.SourceStore;
import com.mintv.tv.ui.focus.FocusEngine;
import com.mintv.tv.ui.player.Media3Backend;
import com.mintv.tv.ui.player.PlaybackBackend;
import com.mintv.tv.ui.player.PlaybackEngine;
import com.mintv.tv.ui.player.PlayerManager;
import com.mintv.tv.ui.player.HttpHeaderProfile;
import com.mintv.tv.ui.widget.ChannelView;
import com.mintv.tv.ui.widget.ClockView;
import com.mintv.tv.ui.widget.EpgPanelView;
import com.mintv.tv.ui.widget.NavItemView;
import com.mintv.tv.ui.widget.RailView;
import com.mintv.tv.ui.widget.ScrimView;
import com.mintv.tv.ui.widget.SettingRowView;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：SurfaceView 播放 + 左上三栏覆盖层 + 设置弹窗。
 *
 * 整个界面没有一个 XML 布局、没有一个第三方 UI 控件，
 * 所有列表都是手写的 View 池（比 RecyclerView 在低端机上更稳：
 * 频道数 <200 时 RecyclerView 的滚动计算开销反而更大）。
 */
public class MainActivity extends Activity implements FocusEngine.Host {

    // ---- 播放 ----
    private SurfaceView surfaceView;
    private android.view.TextureView textureView;

    /** 画面输出方式：0 = TextureView（默认，能出图优先）, 1 = SurfaceView */
    private static final int OUTPUT_TEXTURE = 0;
    private static final int OUTPUT_SURFACE = 1;
    private PlayerManager playerManager;
    private PlaybackBackend.Callback playerCallback;
    private PlaybackBackend player;
    private OsdView osd;

    // ---- 三栏 ----
    private final List<NavItemView> navViews = new ArrayList<>();
    private final List<ChannelView> chanViews = new ArrayList<>();
    private FrameLayout chanColumn;   // 频道行池容器（绝对定位）
    private View navPanel, chanPanel, railView, scrim, navDivider;
    private View chanContainer;   // 频道列的外层，抽屉展开时整体左移
    private ScrollView chanScroll;
    private View videoTap;        // 铺满全屏的「点视频区」层
    private float tapX, tapY;

    // ---- 长按快进 ----
    private SeekOverlayView seekOverlay;
    private View gestureLayer;
    private ErrorPanel errorPanel;
    private boolean seeking = false;
    private long seekBaseMs = 0;      // 长按起点位置
    private long seekStartWall = 0;   // 长按开始的墙钟时间
    private long seekTargetMs = 0;
    private long curPosition = 0, curDuration = -1;
    private final Handler seekHandler = new Handler(Looper.getMainLooper());
    private final Runnable seekTick = new Runnable() {
        @Override public void run() {
            if (!seeking) return;
            updateSeekTarget();
            seekHandler.postDelayed(this, 60);
        }
    };
    private ClockView clock;
    /** 右下角常显的网速标签，独立于 OSD，实时更新。 */
    private TextView speedLabel;

    // ---- 状态 ----
    private final FocusEngine focus = new FocusEngine();
    private List<DemoData.Category> categories;
    private PlaylistParser.Playlist playlist;
    private List<PlaylistParser.Channel> channels;
    private final LogoLoader logos = new LogoLoader();
    private boolean panelVisible = true;
    private int playingChannel = 0;
    private int currentLine = 0;
    private int lineTotal = 1;
    private String lastResolution, lastFps, lastVCodec, lastACodec, lastBitrate;
    private final com.mintv.tv.ui.player.SpeedMeter speedMeter = new com.mintv.tv.ui.player.SpeedMeter();

    // ---- EPG ----
    private final java.util.Map<Integer, String> epgIdOf = new java.util.HashMap<>();  // 频道序号 → EPG channelId
    private EpgParser.Epg epg;
    private EpgPanelView epgPanel;
    private ScrollView epgScroll;
    private long epgTs = 0;
    private final Runnable epgTick = new Runnable() {
        @Override public void run() {
            refreshPlayInfo();
            ui.postDelayed(this, 30_000);
        }
    };

    // ---- 设置弹窗 ----
    private FrameLayout dialogRoot;
    private LinearLayout rowColumn;
    private final List<SettingRowView> rowViews = new ArrayList<>();
    private final List<TextView> tabViews = new ArrayList<>();
    private ScrollView rowScrollView;
    private LinearLayout infoPage;
    private TextView infoPageText;
    /** 当前设置页的 Tab：0 账户登录 / 1 播放设置 / 2 手机助手 */
    private int currentTab = 1;
    /** 焦点是否在顶部 Tab 条上。第一行按「上」进入，左右切页，下回列表。 */
    private boolean tabFocus = false;
    private final int[] selections = DemoData.ROW_DEFAULT.clone();

    /**
     * 全局自定义请求头。
     *
     * <p>为什么要有「全局」这一层：很多源整份都是同一个 CDN，每条地址都写
     * 一遍 {@code |User-Agent=...} 不现实，用户也没法从几百条里挑。
     * 优先级从低到高：默认 UA → 全局请求头 → 单条源里的 |UA=.. / #EXTHTTP。
     * 单条源永远盖全局，因为它是针对这个频道写的。
     */
    private java.util.Map<String, String> globalHeaders = new java.util.LinkedHashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (clock != null) clock.refresh();
            ui.postDelayed(this, 30_000);
        }
    };

    /**
     * 网速刷新节拍。
     *
     * <p>为什么不挂在 OSD 上：OSD 4 秒后自动淡出，网速跟着消失就谈不上"实时"了。
     * 这个标签独立存在，只在「网速显示 = 显示」且确实有流量时出现。
     */
    private final Runnable speedTick = new Runnable() {
        @Override public void run() {
            updateSpeedLabel();
            ui.postDelayed(this, 1000);
        }
    };

    /** 面板占了左边多少，OSD 卡片就要让开多少。 */
    private void updateOsdInset() {
        if (osd == null) return;
        int inset;
        if (!panelVisible) {
            inset = 0;
        } else if (epgPanelOpen) {
            inset = Theme.px(Theme.CHAN_W + 8 + Theme.EPG_W);
        } else {
            inset = Theme.px(Theme.NAV_W + 1 + Theme.CHAN_W + 1 + Theme.RAIL_W);
        }
        osd.setLeftInset(inset);
    }

    private void updateSpeedLabel() {
        if (speedLabel == null) return;
        boolean want = selections[ROW_SPEED] == 0;
        if (!want) {
            if (speedLabel.getVisibility() != View.GONE) speedLabel.setVisibility(View.GONE);
            return;
        }
        speedMeter.sample();
        String t = speedMeter.text();
        if (t.isEmpty()) {
            if (speedLabel.getVisibility() != View.GONE) speedLabel.setVisibility(View.GONE);
            return;
        }
        if (!t.equals(speedLabel.getText().toString())) speedLabel.setText(t);
        if (speedLabel.getVisibility() != View.VISIBLE) speedLabel.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));

        // 用真实屏幕像素的长边做基准（与当前方向无关）。
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int base = Math.max(dm.widthPixels, dm.heightPixels);
        android.util.Log.i("MinTV", "screen realMetrics=" + dm.widthPixels + "x" + dm.heightPixels + " base=" + base);
        Theme.init(base);

        errorPanel = ErrorPanel.install(this);
        CatchupProbe.init(this);
        loadPlaylist();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        buildPlayer(root);
        buildOverlay(root);
        buildDialog(root);
        setContentView(root);

        focus.attach(this);
        loadHeaders();
        loadLinePrefs();
        loadFavorites();
        applyOutputMode();
        recoverFromEngineCrash();
        focus.setNavCount(categories.size());
        focus.setChannelCount(channels.size());
        bindChannels();
        updateNavFocus(1);
        updateChannelFocus(0);
        // 面板可见时 OSD 要让开左边。setPanelVisible 会因为"状态没变"提前返回，
        // 所以启动这条路必须自己调一次，否则 inset 永远是 0。
        ui.post(new Runnable() {
            @Override public void run() { updateOsdInset(); }
        });
        restoreLastChannel();     // 回到上次看的台

        playerManager = new PlayerManager(this);
        player = playerManager;
        playerCallback = new PlaybackBackend.Callback() {
            @Override public void onState(PlaybackBackend.State s) {
                // 注意：READY 只代表解封装 + 解码器就绪，<b>不代表出画面</b>。
                // 以前在这里就把 playing 置 true、把看门狗撤掉，
                // 结果「有声音没画面」时看门狗永远不会触发，黑屏能一路播到底。
                // 现在只认 onFirstFrame。
                osd.setStatus(OsdView.stateText(s));   // READY 返回 null，会自动清空
            }
            @Override public void onFirstFrame() {
                playing = true;
                watchdogFired = true;
                ui.removeCallbacks(playbackWatchdog);
                osd.setStatus(null);
                clearEngineAttempt();       // 出帧了，内核稳定
                rememberLine();             // 记住这个频道能播的线路
            }

            @Override public void onVideoUnavailable(String reason) {
                // 音频在放、画面出不来。这是「有声音没画面」的实锤信号：
                // 先提示，再按设置决定是否换源，绝不静默黑屏。
                dumpPlaybackState("NO_VIDEO", reason);
                osd.toast(reason);
                onNoVideo(reason);
            }

            @Override public void onTimeline(long pos, long dur, long buf) {
                curPosition = pos;
                curDuration = dur;
                checkStall(pos);
                speedMeter.sample();
                if (selections[ROW_SPEED] == 0) refreshOsdTags();   // 0 = 显示
                if (!seeking && seekOverlay != null) seekOverlay.hide();
            }

            @Override public void onError(String msg) {
                dumpPlaybackState("ERROR", msg);
                // 播放失败只显示在 OSD 上，不弹框（弹框只留给崩溃）
                osd.setStatus("播放失败：" + msg);
            }

            @Override public void onVideoFormat(String res, String vc, String ac, String ch, String fps) {
                if (res != null) lastResolution = res;
                if (fps != null) lastFps = fps;
                if (vc != null) lastVCodec = vc;
                if (ac != null) lastACodec = ac;
                refreshOsdTags();
            }

            @Override public void onBitrate(String bitrate) {
                if (bitrate != null) lastBitrate = bitrate;
            }
        };
        playerManager.setCallback(playerCallback);
        // 读的是 ROW_ENGINE（播放内核：0 Exo / 1 VLC），不是 ROW_DECODER。
        // 以前写成 ROW_DECODER，「软解=1」会被当成下一个内核，悄悄换引擎。
        playerManager.setEngine(engineOf(selections[ROW_ENGINE]), true);
        if (selections[ROW_ENGINE] != 0) markEngineAttempt(engineOf(selections[ROW_ENGINE]));

        // Surface 一就绪就开播，避免先黑屏再起播
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                if (selections[ROW_OUTPUT] != OUTPUT_SURFACE) return;  // 当前用 TextureView
                playerManager.setSurfaceView(surfaceView);
                player.setSurface(h.getSurface());
                playChannelInternal(playingChannel, true);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {
                // 尺寸变化后解码器的输出面会失效，必须重新绑一次。
                // 以前这里是空的，切分辨率 / 从后台回来就黑屏只有声音。
                playerManager.setSurfaceView(surfaceView);
                if (h.getSurface() != null && h.getSurface().isValid()) {
                    player.setSurface(h.getSurface());
                }
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { player.setSurface(null); }
        });

        // 「解码方式」设置项：0 = 硬件，1 = 软件
        if (player instanceof Media3Backend) {
            ((Media3Backend) player).setPreferSoftwareDecoder(selections[ROW_DECODER] == 1);
        }

        ui.post(clockTick);
        ui.post(speedTick);
        loadEpgAsync();
    }

    // ================= 布局 =================

    private void buildPlayer(FrameLayout root) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;

        // TextureView：走 GPU 合成，跟着视图树走。默认用它。
        textureView = new android.view.TextureView(this);
        // 注意：TextureView 不允许 setBackgroundDrawable/setBackgroundColor，
        // 会直接抛 UnsupportedOperationException 把 Activity 干掉。
        // 底色交给下面垫一层的 root 背景（黑色）就够了。
        // TextureView 没有 SurfaceHolder.Callback，必须走 SurfaceTextureListener。
        // 不接这个回调，默认用 TextureView 时就没人触发起播（只有 SurfaceView
        // 那条 surfaceCreated 会调用 playChannel），表现为一直黑屏不播。
        textureView.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                if (selections[ROW_OUTPUT] != OUTPUT_TEXTURE) return;
                playerManager.setTextureView(textureView);
                playChannelInternal(playingChannel, true);
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) {
                if (selections[ROW_OUTPUT] != OUTPUT_TEXTURE) return;
                playerManager.setTextureView(textureView);
            }
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                return true;
            }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { }
        });
        root.addView(textureView, lp);

        // SurfaceView：独立图层，低端盒子省电省内存，但在部分机型上
        // 解码器出帧却合成不到屏幕（有声音没画面），所以降级为备选。
        surfaceView = new SurfaceView(this);
        surfaceView.setZOrderMediaOverlay(false);
        surfaceView.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams lp2 = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp2.gravity = Gravity.CENTER;
        surfaceView.setVisibility(View.GONE);
        root.addView(surfaceView, lp2);
    }

    /** 当前真正在输出的 View（画面比例要作用在它身上）。 */
    private View outputView() {
        return selections[ROW_OUTPUT] == OUTPUT_TEXTURE ? textureView : surfaceView;
    }

    /**
     * 应用「画面输出」设置。
     *
     * <p>TextureView 和 SurfaceView 只能有一个可见：两个都挂在树里会同时占
     * 一条合成路径，低端机上直接掉帧。切的时候必须把播放器的输出面也一起换掉，
     * 只改 View 可见性是不生效的。
     */
    private void applyOutputMode() {
        if (textureView == null || surfaceView == null) return;
        boolean tex = selections[ROW_OUTPUT] == OUTPUT_TEXTURE;
        textureView.setVisibility(tex ? View.VISIBLE : View.GONE);
        surfaceView.setVisibility(tex ? View.GONE : View.VISIBLE);
        if (playerManager == null) return;
        if (tex) {
            playerManager.setTextureView(textureView);
        } else {
            playerManager.setTextureView(null);
            if (surfaceView.getHolder().getSurface() != null) {
                playerManager.setSurface(surfaceView.getHolder().getSurface());
            }
        }
    }

    private int chanWidth() {
        return Theme.px(Theme.CHAN_W);
    }

    private void buildOverlay(FrameLayout root) {
        int navW = Theme.px(Theme.NAV_W);
        int chanW = chanWidth();
        int railW = Theme.px(Theme.RAIL_W);
        int scrimW = Theme.px(Theme.SCRIM_W);
        int top = 0;

        // --- 视频区点击层（最底层，铺满全屏）---
        // 所有面板都比它后加，所以点击先给面板，面板不消费才落到这里。
        // 作用只有一个：点视频区 → 收起左侧三栏；面板已收起时反过来交给
        // gestureLayer 处理，两态各管一边，不会互相抢。
        videoTap = new View(this);
        videoTap.setBackgroundColor(0x00000000);
        videoTap.setClickable(true);
        videoTap.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    tapX = e.getX();
                    tapY = e.getY();
                }
                return false;      // 不消费，交给 OnClickListener
            }
        });
        videoTap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onVideoAreaTapped(tapX, tapY); }
        });
        // 只铺右侧视频区，别盖住左侧三栏 —— 铺满全屏会让列表点击全部失效。
        videoTap.setVisibility(View.GONE);   // 面板建好后再按布局显示
        root.addView(videoTap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // OSD 只是"视频层的字幕"，必须垫在面板之下。
        // 以前它最后 addView（最上层），结果信息条压在频道列表上，
        // 打开列表时两者叠成一团。放到这里之后，面板自然盖住它，
        // 纯播放态（面板收起）它才完整可见。
        // --- OSD ---
        osd = new OsdView(this);
        // 进度条拖动：能不能 seek 由播放内核说了算（直播 = false）
        osd.setSeekListener(new OsdView.SeekListener() {
            @Override public boolean canSeek() {
                return player != null && player.canSeek() && curDuration > 0;
            }
            @Override public long durationMs() { return curDuration; }
            @Override public void onPreview(long ms) {
                if (seekOverlay != null) {
                    seekOverlay.update(ms, ms, curDuration);
                    seekOverlay.show();
                }
            }
            @Override public void onCommit(long ms) {
                if (player != null && player.canSeek()) {
                    player.seekTo(ms);
                    osd.toast("已跳转到 " + fmtMs(ms));
                }
                seekHandler.postDelayed(new Runnable() {
                    @Override public void run() { if (seekOverlay != null) seekOverlay.hide(); }
                }, 500);
            }
            @Override public void onSeekBlocked() {
                osd.toast("直播流没有可跳转时间轴，回看才能拖");
            }
        });
        root.addView(osd, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- 遮罩 ---
        scrim = new ScrimView(this);
        root.addView(scrim, new FrameLayout.LayoutParams(scrimW, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- 左导航 ---
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(Theme.px(22), Theme.px(34), Theme.px(22), 0);
        nav.setBackgroundColor(0x0AFFFFFF);

        nav.addView(buildNavHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(76)));

        // 吞掉列表空白处的点击：不然 user 想滚动时点一下空白，
        // 事件会穿透到底层的 videoTap，面板莫名其妙就收起了。
        nav.setClickable(true);
        navPanel = nav;

        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        LinearLayout navList = new LinearLayout(this);
        navList.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < categories.size(); i++) {
            DemoData.Category cat = categories.get(i);
            NavItemView v = new NavItemView(this);
            v.bind(cat.name, cat.count, cat.glyph);
            final int catIndex = i;
            v.setClickable(true);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { onCategoryClicked(catIndex); }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(Theme.NAV_ITEM_H));
            p.bottomMargin = Theme.px(2);
            navList.addView(v, p);
            navViews.add(v);
        }
        navScroll.addView(navList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        nav.addView(navScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 分隔线
        View navLine = new View(this);
        navLine.setBackgroundColor(Theme.LINE);
        navDivider = navLine;
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(navW, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(nav, navLp);
        root.addView(navLine, lineParams(navW));

        // --- 频道列 ---
        LinearLayout chan = new LinearLayout(this);
        chan.setOrientation(LinearLayout.VERTICAL);
        chan.setPadding(0, Theme.px(26), 0, 0);

        TextView chanHead = new TextView(this);
        chanHead.setText("节目 » 信息");
        chanHead.setTextColor(0xFFECECEE);
        chanHead.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(19));
        chanHead.setPadding(Theme.px(22), 0, 0, Theme.px(10));
        chan.addView(chanHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(50)));

        chanScroll = new ScrollView(this);
        chanScroll.setVerticalScrollBarEnabled(false);
        chanScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 用 FrameLayout + topMargin 绝对定位：行池里每个 View 会被反复绑定到
        // 不同频道的不同位置，LinearLayout 的"顺序堆叠"语义用不上。
        chanColumn = new FrameLayout(this);
        // 高度必须 WRAP_CONTENT，ScrollView 才知道内容比视口高、可以滚动
        // 高度由 bindChannels() 按「频道数 × 行高」算好后设，ScrollView 才知道能滚多远
        chanScroll.addView(chanColumn, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        chan.addView(chanScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        chan.setClickable(true);   // 同上，吞掉频道列空白点击
        chanPanel = chan;

        FrameLayout.LayoutParams chanLp = new FrameLayout.LayoutParams(
                chanW, FrameLayout.LayoutParams.MATCH_PARENT);
        chanLp.leftMargin = navW + Theme.px(1);
        chanContainer = chan;
        root.addView(chan, chanLp);

        // 竖轨：EPG 收起时作为「节目 » 信息」入口，点它展开节目单
        View rail = new RailView(this);
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(
                railW, FrameLayout.LayoutParams.MATCH_PARENT);
        railLp.leftMargin = navW + chanW + Theme.px(1);
        railView = rail;
        rail.setClickable(true);
        rail.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showEpgPanel(playingChannel >= 0 ? playingChannel : 0); }
        });
        root.addView(rail, railLp);

        // --- 时钟 ---
        clock = new ClockView(this);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                Theme.px(360), Theme.px(90));
        clockLp.gravity = Gravity.TOP | Gravity.END;
        clockLp.rightMargin = Theme.px(36);
        clockLp.topMargin = Theme.px(18);
        root.addView(clock, clockLp);

        // --- 右下角网速（常显，不跟 OSD 一起消失）---
        speedLabel = new TextView(this);
        speedLabel.setTextColor(0xFFFFFFFF);
        speedLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(19));
        speedLabel.setShadowLayer(Theme.px(4), 0, Theme.px(1), 0xCC000000);
        speedLabel.setGravity(Gravity.END);
        speedLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams spLp = new FrameLayout.LayoutParams(
                Theme.px(320), Theme.px(44));
        spLp.gravity = Gravity.BOTTOM | Gravity.END;
        spLp.rightMargin = Theme.px(40);
        spLp.bottomMargin = Theme.px(30);
        root.addView(speedLabel, spLp);

        // --- 抽屉式 EPG ---
        // 展开后布局：[频道列 476][间隔 8][节目单 422][播放画面 剩余]
        // 分类栏滑出屏幕，频道列左移 300 补位。
        int epgW = Theme.px(Theme.EPG_W);
        epgScroll = new ScrollView(this);
        epgScroll.setVerticalScrollBarEnabled(false);
        epgScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        epgScroll.setBackgroundColor(0x0AFFFFFF);
        epgPanel = new EpgPanelView(this);
        epgScroll.addView(epgPanel, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams epgLp = new FrameLayout.LayoutParams(
                epgW, FrameLayout.LayoutParams.MATCH_PARENT);
        epgLp.leftMargin = Theme.px(Theme.CHAN_W + 8);   // 紧贴左移后的频道列右侧
        epgScroll.setVisibility(View.GONE);
        root.addView(epgScroll, epgLp);

        // EPG 条目的点击。
        //
        // 用 GestureDetector 挂在 ScrollView 上、并且不消费触摸：
        // 如果直接给 EpgPanelView 写 onTouchEvent 并在 DOWN 返回 true，
        // 节目单就再也滚不动了（ScrollView 拿不到事件）。
        // OnTouchListener 返回 false 时事件继续往下走，滚动照常，
        // 而 onSingleTapUp 只会在"确实是点一下"的时候触发。
        final android.view.GestureDetector epgGesture = new android.view.GestureDetector(
                this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(android.view.MotionEvent e) {
                if (epgPanel == null) return false;
                // epgPanel 在 ScrollView 里，局部坐标要加上滚动量
                float y = e.getY() + epgScroll.getScrollY();
                int idx = epgPanel.itemAt(y);
                if (idx < 0) return false;
                int ch = focus.chanIndex() >= 0 ? focus.chanIndex() : playingChannel;
                if (ch < 0 || ch >= channels.size()) return false;
                EpgParser.Program prog = programAt(ch, idx);
                if (prog == null) return false;
                long now = System.currentTimeMillis();

                // 「直播中」那一行不是回看，是"切回直播"。
                // 以前这里一律按"已播完才可点"判断，点直播行就只弹一句
                // "还没播完，回看不了"，于是从回看状态永远回不到直播。
                boolean isLive = prog.startMs <= now && prog.stopMs > now;
                if (isLive) {
                    returnToLive(ch);
                    return true;
                }
                if (prog.startMs > now) {
                    osd.toast("该节目还没开始");
                    return true;
                }
                if (prog.stopMs > now) {
                    osd.toast("该节目还没播完，回看不了");
                    return true;
                }
                playReplay(ch, idx);
                return true;
            }
        });
        epgScroll.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                epgGesture.onTouchEvent(e);
                return false;      // 不消费，滚动照常
            }
        });

        // --- 纯播放态的手势层：只在左侧面板收起时可见，铺满全屏 ---
        // 单击 = 唤回左侧面板，长按 = 打开设置。
        // 为什么必须铺满：面板收起后全屏没有任何可点控件，点击事件会一路落到
        // Activity.dispatchTouchEvent 里没人消费，用户「点了没反应」就是这么来的。
        gestureLayer = new View(this);
        gestureLayer.setBackgroundColor(0x00000000);
        gestureLayer.setClickable(true);
        // 记录点击坐标：靠它区分"点左边"和"点右边"
        gestureLayer.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    tapX = e.getX();
                    tapY = e.getY();
                }
                return false;      // 不消费，交给 OnClickListener
            }
        });
        gestureLayer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onVideoTapped(); }
        });
        gestureLayer.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                openSettings();
                return true;
            }
        });
        gestureLayer.setVisibility(View.GONE);   // 只在面板收起时启用
        root.addView(gestureLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- 长按快进预览 ---
        seekOverlay = new SeekOverlayView(this);
        root.addView(seekOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 视频区点击层只覆盖三栏右侧，左边距在 onLayout 后算准
        videoTap.post(new Runnable() {
            @Override public void run() { layoutVideoTap(); }
        });

    }

    private View buildNavHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(Theme.px(10), 0, 0, Theme.px(26));

        TextView tv = new TextView(this);
        tv.setText("频道列表");
        tv.setTextColor(Theme.TEXT);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(Theme.TS_NAV_TITLE));
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.addView(tv);
        return h;
    }

    private FrameLayout.LayoutParams lineParams(int left) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                Math.max(1, Theme.px(1)), FrameLayout.LayoutParams.MATCH_PARENT);
        p.leftMargin = left;
        return p;
    }

    // ================= 频道列表 =================

    /** 读取源：有 URL 走网络（带本地缓存兜底），没有就用内置示范。 */
    private void loadPlaylist() {
        String url = SourceStore.url(this);
        String body = null;
        if (url != null && !url.isEmpty()) {
            body = SourceStore.cachedBody(this);
        }
        if (body == null || body.trim().isEmpty()) {
            if (url == null || url.isEmpty()) {
                // 完全没配源 → 用内置示范
                body = DemoData.SAMPLE_M3U;
            } else {
                // 配了源但还没缓存 → 先留空，等网络拉回来再渲染，
                // 绝不拿示范数据冒充，否则会闪出一个"省内"分类
                body = "#EXTM3U\n";
            }
        }
        applyPlaylist(body);

        if (url != null && !url.isEmpty()) {
            fetchSourceAsync(url);
        }
    }

    private void applyPlaylist(String body) {
        playlist = PlaylistParser.parse(body);
        categories = DemoData.toCategories(playlist);
        // 「我的收藏」放在最顶上（第 0 项），用户打开就能直接进收藏，
        // 不用先划过「全部」。其余分类整体顺延一位。
        if (!categories.isEmpty() && !FAV_CATEGORY.equals(categories.get(0).name)) {
            categories.add(0, new DemoData.Category(FAV_CATEGORY, favorites.size(), 0));
        }
        channels = playlist.all;
    }

    /** 后台拉源，成功后重建界面。失败就继续用缓存/示范。 */
    private void fetchSourceAsync(final String url) {
        new Thread(new Runnable() {
            @Override public void run() {
                String body = null;
                String lastEtag = null;
                long lastModified = 0L;
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "TVUI/1.0");
                    conn.setInstanceFollowRedirects(true);

                    // 条件请求：源没变就让服务端回 304，不必每次启动重下 200KB。
                    // 两个校验字段都带上，GitHub Pages 走 ETag，nginx 多数走 Last-Modified。
                    String etag = SourceStore.etag(MainActivity.this);
                    long lastMod = SourceStore.lastModified(MainActivity.this);
                    if (etag != null && !etag.isEmpty()) {
                        conn.setRequestProperty("If-None-Match", etag);
                    }
                    if (lastMod > 0) conn.setIfModifiedSince(lastMod);

                    int code = conn.getResponseCode();
                    if (code == 304) {
                        SourceStore.touch(MainActivity.this);
                        return;                 // 缓存就是最新的，界面不用动
                    }
                    java.io.InputStream in = conn.getInputStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close();
                    body = bos.toString("UTF-8");
                    lastEtag = conn.getHeaderField("ETag");
                    lastModified = conn.getLastModified();
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final String result = body;
                if (result == null || result.trim().isEmpty()) {
                    ui.post(new Runnable() {
                        @Override public void run() { osd.toast("直播源拉取失败，请检查网络"); }
                    });
                    return;
                }
                SourceStore.saveBody(MainActivity.this, result,
                        lastEtag, lastModified);
                ui.post(new Runnable() {
                    @Override public void run() {
                        applyPlaylist(result);
                        rebuildCategoryColumn();
                        bindChannels();
                        updateChannelFocus(0);
                    }
                });
            }
        }, "source-fetch").start();
    }

    private void rebuildCategoryColumn() {
        if (navPanel instanceof LinearLayout) {
            LinearLayout nav = (LinearLayout) navPanel;
            View scroll = nav.getChildAt(1);
            if (scroll instanceof ScrollView) {
                LinearLayout list = (LinearLayout) ((ScrollView) scroll).getChildAt(0);
                list.removeAllViews();
                navViews.clear();
                for (int i = 0; i < categories.size(); i++) {
                    DemoData.Category cat = categories.get(i);
                    NavItemView v = new NavItemView(MainActivity.this);
                    v.bind(cat.name, cat.count, cat.glyph);
                    // 重建列表时必须重新挂点击监听，否则网络源加载完后分类全部失效
                    final int catIndex = i;
                    v.setClickable(true);
                    v.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) { onCategoryClicked(catIndex); }
                    });
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(Theme.NAV_ITEM_H));
                    p.bottomMargin = Theme.px(2);
                    list.addView(v, p);
                    navViews.add(v);
                }
                focus.setNavCount(categories.size());
                updateNavFocus(Math.min(focus.navIndex(), categories.size() - 1));
            }
        }
    }

    // ================= 频道列表：固定行池 + 只绑可见行 =================
    //
    // 原来是一次性给 839 个频道各建一个 ChannelView，全部挂在 ScrollView 里。
    // 代价：839 个自绘 View 常驻（每个带 Paint、Bitmap 引用），
    // 切分类要全部重建，低端盒子上切一次卡一次。
    //
    // 现在只保留「一屏 + 上下各 2 行」个 View，滚动时重新绑定。
    // 没有用 RecyclerView：这个项目的前提就是零第三方控件，
    // 而且我们需要对焦点、点击命中、台标加载做精确控制，
    // 自己写一个固定池比把 RecyclerView 调教到听话更简单。
    //
    // 具体做法：
    //   · chanColumn 高度 = 频道数 × 行高，交给 ScrollView 原生滚动（保留惯性）；
    //   · 行池里每个 View 用 topMargin 定位到它当前绑定的那一行；
    //   · 滚动停止/变化时，只在「首个可见行变了」的时候重新绑定。

    private static final int POOL_EXTRA_ROWS = 4;

    /** 行池里每个 View 当前绑定的频道下标，与 chanViews 一一对应。-1 表示空。 */
    private final List<Integer> chanBind = new ArrayList<>();
    /** 当前行池覆盖的起始频道下标。 */
    private int poolStart = -1;

    private int rowH() {
        return Theme.px(Theme.CHAN_ITEM_H + Theme.CHAN_ITEM_GAP);
    }

    /**
     * 频道行在 ScrollView 内容里的起始偏移。
     *
     * <p>必须是 0：「节目 » 信息」那个表头是 chanScroll 的**兄弟**，
     * 不在滚动内容里。之前把表头高度也算进来，等于给每一行多加 76px，
     * 结果第一个频道被顶到很下面，看着像列表没对齐。
     */
    private int listTopPx() {
        return 0;
    }

    private void bindChannels() {
        chanColumn.removeAllViews();
        chanViews.clear();
        chanBind.clear();
        poolStart = -1;
        resetChannelFocusCache();

        if (channels.isEmpty()) {
            focus.setChannelCount(0);
            return;
        }

        int total = channels.size();
        int itemH = rowH();

        // 内容总高：让 ScrollView 的滚动条和惯性按真实列表长度走
        ViewGroup.LayoutParams clp = chanColumn.getLayoutParams();
        int contentH = total * itemH + Theme.px(20);
        if (clp == null) {
            clp = new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentH);
        }
        clp.height = contentH;
        chanColumn.setLayoutParams(clp);

        int viewH = chanScroll != null && chanScroll.getHeight() > 0
                ? chanScroll.getHeight() : getWindow().getDecorView().getHeight();
        int poolSize = Math.max(6, viewH / itemH + POOL_EXTRA_ROWS);

        for (int i = 0; i < poolSize; i++) {
            final ChannelView v = new ChannelView(this);
            v.setClickable(true);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    Object tag = view.getTag();
                    if (tag instanceof Integer) onChannelClicked((Integer) tag);
                }
            });
            // 长按 = 收藏 / 取消收藏。用 tag 里当前的绑定下标，滚动后也不会认错台。
            v.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View view) {
                    Object tag = view.getTag();
                    if (!(tag instanceof Integer)) return false;
                    int idx = (Integer) tag;
                    if (idx < 0 || idx >= channels.size()) return false;
                    toggleFavorite(channels.get(idx));
                    return true;
                }
            });
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(Theme.CHAN_ITEM_H));
            p.leftMargin = Theme.px(14);
            p.rightMargin = Theme.px(14);
            p.topMargin = Theme.px(Theme.CHAN_ITEM_GAP / 2);
            v.setLayoutParams(p);
            v.setVisibility(View.GONE);
            chanColumn.addView(v);
            chanViews.add(v);
            chanBind.add(-1);
        }

        // 分类切换后必须回到顶部：内容高度变了，旧 scrollY 可能越界
        if (chanScroll != null) chanScroll.scrollTo(0, 0);

        focus.setChannelCount(total);
        attachScrollLoader();
        ui.post(new Runnable() {
            @Override public void run() {
                syncPool(true);
                applyFocusToPool();
                tryRestoreIfPending();   // 源异步回来后重建列表，也要回到上次那台
            }
        });
    }

    /**
     * 把行池滚到当前可见区域。
     *
     * @param force true 时无条件重绑（列表刚建好、分类切换）
     */
    private void syncPool(boolean force) {
        if (chanViews.isEmpty() || channels.isEmpty()) return;
        int itemH = rowH();
        int scrollY = chanScroll == null ? 0 : chanScroll.getScrollY();
        int first = (scrollY - listTopPx()) / itemH;
        if (first < 0) first = 0;
        if (!force && first == poolStart) return;
        poolStart = first;

        for (int k = 0; k < chanViews.size(); k++) {
            ChannelView v = chanViews.get(k);
            int idx = first + k;
            if (idx >= channels.size()) {
                chanBind.set(k, -1);
                v.setTag(null);
                if (v.getVisibility() != View.GONE) v.setVisibility(View.GONE);
                continue;
            }
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
            int top = listTopPx() + idx * itemH;
            if (p.topMargin != top) {
                p.topMargin = top;
                v.setLayoutParams(p);
            }
            chanBind.set(k, idx);
            v.setTag(idx);
            bindRow(v, idx);
        }
        refreshChannelSubs();
        requestLogosForPool();
    }

    /** 把一个池化的行绑到具体频道。 */
    private void bindRow(ChannelView v, int idx) {
        PlaylistParser.Channel ch = channels.get(idx);
        v.setNumber(ch.number);
        v.setName(ch.name);
        v.setSub("");
        v.setBadge(null);
        v.setLogo(null);
        v.setFavorite(isFavorite(ch));
    }

    /** 频道下标 → 池里正在显示它的 View；不在池里返回 null。 */
    private ChannelView viewForChannel(int channelIndex) {
        for (int k = 0; k < chanBind.size(); k++) {
            if (chanBind.get(k) == channelIndex) return chanViews.get(k);
        }
        return null;
    }

    private void applyFocusToPool() {
        int fi = focus.chanIndex();
        for (int k = 0; k < chanViews.size(); k++) {
            chanViews.get(k).setFocused(chanBind.get(k) == fi, false);
        }
    }

    /** 列表滚动时：重绑行池 + 补台标。滚动中不重绑，等停一下再做，避免抖。 */
    private void attachScrollLoader() {
        if (chanScroll == null) return;
        if (chanScroll.getTag() != null) return;
        chanScroll.setTag(Boolean.TRUE);
        chanScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                ui.removeCallbacks(scrollPoolTask);
                ui.postDelayed(scrollPoolTask, 16);
            }
        });
    }

    private final Runnable scrollPoolTask = new Runnable() {
        @Override public void run() {
            syncPool(false);
            applyFocusToPool();
        }
    };

    private void requestLogosForPool() {
        for (int k = 0; k < chanViews.size(); k++) {
            int idx = chanBind.get(k);
            if (idx < 0 || idx >= channels.size()) continue;
            final ChannelView v = chanViews.get(k);
            if (v.hasLogo()) continue;
            PlaylistParser.Channel ch = channels.get(idx);
            if (ch.logo == null || ch.logo.isEmpty()) continue;

            Bitmap cached = logos.peek(ch.logo);
            if (cached != null) { v.setLogo(cached); continue; }
            final String logoUrl = ch.logo;
            final int bindIdx = idx;
            logos.load(logoUrl, new LogoLoader.Callback() {
                @Override public void onLogo(String url, final Bitmap bmp) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            // 回调回来时这一格可能已经滚到别的频道了，认一下
                            int k2 = chanBind.indexOf(bindIdx);
                            if (k2 < 0) return;
                            ChannelView target = chanViews.get(k2);
                            if (bmp != null && !bmp.isRecycled()) {
                                target.setLogo(bmp);
                                target.invalidate();
                            }
                        }
                    });
                }
            });
        }
    }

    /** 当前可见的频道行区间 [from, to)。 */
    private int[] visibleChannelRange() {
        if (channels.isEmpty()) return new int[]{0, 0};
        int itemH = rowH();
        int scrollY = chanScroll == null ? 0 : chanScroll.getScrollY();
        int viewH = chanScroll == null ? 0 : chanScroll.getHeight();
        if (viewH <= 0) viewH = getWindow().getDecorView().getHeight();
        int first = Math.max(0, (scrollY - listTopPx()) / itemH - 1);
        int last = Math.min(channels.size(), (scrollY - listTopPx() + viewH) / itemH + 2);
        return new int[]{first, Math.max(first, last)};
    }


    /** 点分类：切分类，焦点交给频道列。 */
    private void onCategoryClicked(int index) {
        clearRestorePending();
        switchCategory(index);
        focus.setZone(FocusEngine.Zone.CHAN);
        focus.setNavIndexSilently(index);
        updateNavFocus(index);
        updateChannelFocus(0);
    }

    /** 点频道：切台 + 选中框 + 刷新节目单。 */
    private void onChannelClicked(int index) {
        clearRestorePending();
        resetNoVideoStage();
        playChannel(index);
        focusOnChannel(index);
        fillEpg(index);
    }

    private void updateNavFocus(int index) {
        boolean navZone = focus.zone() == FocusEngine.Zone.NAV;
        for (int i = 0; i < navViews.size(); i++) {
            NavItemView v = navViews.get(i);
            // 当前分类始终有底，只有焦点真的在分类列时才画蓝框
            v.setActive(i == focus.navIndex());
            v.setFocused(navZone && i == index, true);
        }
    }

    /** 上一次画了焦点框的下标，用来只重绘变化的两行。 */
    private int lastFocusedChan = -1;

    /**
     * 更新频道焦点。
     *
     * <p>以前是「遍历全部 839 行，逐个 setFocused」——每个 setFocused 内部
     * 都会 invalidate()，于是按一次方向键触发 839 次重绘。
     * 低端盒子上这就是按键延迟的直接来源。
     * 实际上只会有一行失去焦点、一行获得焦点，重绘 2 行就够。
     */
    private void updateChannelFocus(int index) {
        // 行池里只有一屏多的 View，焦点一定是「某个被绑定的行」，
        // 所以先把目标行滚进可见区、必要时重绑，再套焦点样式。
        lastFocusedChan = index;
        scrollChannelIntoView(index);
        ensureBound(index);
        applyFocusToPool();
    }

    /** 列表重建后焦点状态全部失效，重置记录。 */
    private void resetChannelFocusCache() { lastFocusedChan = -1; }

    /** 目标行不在当前行池窗口里时，直接把窗口挪过去（跳转、首尾那种大跨度）。 */
    private void ensureBound(int index) {
        if (index < 0 || index >= channels.size()) return;
        if (viewForChannel(index) != null) return;
        int itemH = rowH();
        if (chanScroll != null) {
            int target = listTopPx() + index * itemH - Theme.px(120);
            chanScroll.scrollTo(0, Math.max(0, target));
        }
        syncPool(true);
    }

    /** 焦点保持可见：按行号算位置，不再依赖 View.getTop()。 */
    private void scrollChannelIntoView(int index) {
        if (index < 0 || index >= channels.size() || chanScroll == null) return;
        int itemH = rowH();
        int itemTop = listTopPx() + index * itemH;
        int itemBottom = itemTop + itemH;
        int scrollY = chanScroll.getScrollY();
        int viewH = chanScroll.getHeight();
        if (viewH <= 0) viewH = getWindow().getDecorView().getHeight();
        int target = -1;
        if (itemTop - scrollY < Theme.px(80)) target = Math.max(0, itemTop - Theme.px(80));
        else if (itemBottom - scrollY > viewH - Theme.px(40)) {
            target = itemBottom - viewH + Theme.px(40);
        }
        if (target >= 0) chanScroll.smoothScrollTo(0, target);
    }

    private ScrollView findScrollParent(View v) {
        ViewGroup p = (ViewGroup) v.getParent();
        while (p != null) {
            if (p instanceof ScrollView) return (ScrollView) p;
            if (!(p.getParent() instanceof ViewGroup)) return null;
            p = (ViewGroup) p.getParent();
        }
        return null;
    }

    // ================= 播放 =================

    private void playChannel(int index) { playChannelInternal(index, false); }

    /**
     * @param keepLine true 表示「还是同一个频道的重开」（切线路、自愈、回前台），
     *                 不动 currentLine；false 表示切到某个频道，用该频道记住的线路。
     */
    private void playChannelInternal(int index, boolean keepLine) {
        if (index < 0 || index >= channels.size()) return;
        playingChannel = index;
        PlaylistParser.Channel ch = channels.get(index);
        saveLastChannel(ch);
        String no = String.valueOf(ch.number);
        while (no.length() < 4) no = "0" + no;
        lastResolution = null;
        lastFps = null;
        lastVCodec = null;
        lastACodec = null;
        lastBitrate = null;
        speedMeter.reset();
        stallLastPos = -1;
        stallSince = 0;
        lineTotal = ch.sources.size();
        replayStartMs = -1;
        osd.showChannel(no, ch.name, ch.group);
        osd.setClock(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date()));
        refreshOsdTags();
        refreshSourceRow();
        refreshPlayInfo();
        if (ch.sources.isEmpty()) return;

        if (!keepLine) {
            Integer pref = linePref.get(lineKey(ch));
            currentLine = (pref != null && pref >= 0 && pref < ch.sources.size()) ? pref : 0;
        }
        // 越界兜底必须放在取元素之前。
        // 原来写的是 ch.sources.get(currentLine) 再判断越界 —— 顺序反了，
        // 只要记住的线路号大于当前线路数就直接 IndexOutOfBounds 闪退。
        if (currentLine < 0 || currentLine >= ch.sources.size()) currentLine = 0;
        PlaylistParser.Source src = ch.sources.get(currentLine);
        currentPlayUrl = PlaylistParser.cleanUrl(src.url);
        armWatchdog();
        playUrl(PlaylistParser.cleanUrl(src.url), src.headers, PlaybackBackend.Mode.LIVE);
    }

    private final Runnable playbackWatchdog = new Runnable() {
        @Override public void run() {
            if (!playing && !watchdogFired) {
                watchdogFired = true;
                PlaylistParser.Channel ch = (playingChannel >= 0 && playingChannel < channels.size())
                        ? channels.get(playingChannel) : null;
                if (ch != null && ch.sources.size() > 1) {
                    currentLine = (currentLine + 1) % ch.sources.size();
                    osd.toast("该线路无响应，切换线路 " + (currentLine + 1) + "/" + ch.sources.size());
                    playChannelInternal(playingChannel, true);
                } else {
                    osd.toast("该源无响应");
                }
            }
        }
    };
    /** 设置行下标（与 DemoData.ROW_TITLES 顺序一致） */
    // 顺序必须与 DemoData.ROW_TITLES 完全一致
    private static final int ROW_SOURCE         = 0;   // 切源
    private static final int ROW_RATIO          = 1;
    private static final int ROW_EPG_POS        = 2;
    private static final int ROW_REVERSE        = 3;
    private static final int ROW_OSD_STYLE      = 4;
    private static final int ROW_LEFTKEY        = 5;
    private static final int ROW_SPEED          = 6;
    private static final int ROW_AUTOPLAY       = 7;
    private static final int ROW_DECODER        = 8;
    /** 超时换源：0=关闭，1=5s，2=8s，3=12s，4=20s。开关和秒数合成一项了。 */
    private static final int ROW_TIMEOUT        = 9;
    private static final int ROW_HEADERS        = 10;
    private static final int ROW_ENGINE         = 11;
    private static final int ROW_CLOCK          = 12;
    private static final int ROW_OUTPUT         = 13;

    /**
     * 「没画面」自愈的进度。0 = 还没处理，1 = 已切软解，2 = 已换过线路。
     *
     * <p>只在用户主动换台 / 换分类时清零。绝对不能放在 playChannel /
     * armWatchdog 里清零 —— 那是自愈自己调进去的，会被无限踢源。
     */
    private int noVideoStage = 0;

    /** 是否已切到软解。Exo 的硬解在低端盒子上「初始化成功但不出帧」很常见。 */
    private boolean softPref = false;

    /**
     * 有声音没画面 / 解码失败时的自愈。
     *
     * <p>顺序是「先换内核，再换线路」，因为同一台盒子上
     * 「Exo 解不了、VLC 能解」的源远比「这条线路坏了」常见：
     * <ol>
     *   <li>硬解 → 软解（同内核内，Exo 才需要）；</li>
     *   <li>内核降级 Exo → VLC；</li>
     *   <li>换线路；</li>
     *   <li>都没戏 → 明确提示，不再黑着播。</li>
     * </ol>
     */
    private void onNoVideo(String reason) {
        // 已经有明确结论了，别再让「无响应」看门狗插一脚去踢源
        watchdogFired = true;
        ui.removeCallbacks(playbackWatchdog);

        // ① 硬解 → 软解（只对 Exo 有意义）
        if (noVideoStage == 0
                && playerManager != null
                && playerManager.engine() == PlaybackEngine.EXO
                && !softPref) {
            noVideoStage = 1;
            softPref = true;
            selections[ROW_DECODER] = 1;
            if (rowViews.size() > ROW_DECODER) rowViews.get(ROW_DECODER).setSelected(1);
            rebuildEngine(PlaybackEngine.EXO, false);
            osd.toast("无画面，切换软解重试");
            playChannel(playingChannel);
            return;
        }
        // ② 换内核：Exo → VLC
        if ((noVideoStage <= 1 || !softPref) && playerManager != null && !playerManager.isPinned()) {
            PlaybackEngine cur = playerManager.engine();
            PlaybackEngine next = cur.next();
            if (next != cur && PlayerManager.available(next)) {
                noVideoStage = 2;
                rebuildEngine(next, false);
                osd.toast("无画面，换 " + next.label + " 内核重试");
                playChannel(playingChannel);
                return;
            }
        }
        // ③ 换线路
        PlaylistParser.Channel ch = (playingChannel >= 0 && playingChannel < channels.size())
                ? channels.get(playingChannel) : null;
        if (noVideoStage <= 2 && ch != null && ch.sources.size() > 1 && autoSwitchSource()) {
            noVideoStage = 3;
            currentLine = (currentLine + 1) % ch.sources.size();
            osd.toast("该线路无画面，切换线路 " + (currentLine + 1) + "/" + ch.sources.size());
            playChannelInternal(playingChannel, true);
            return;
        }
        // ④ 没得换，明说，不再黑着播
        noVideoStage = 4;
        osd.setStatus("无画面：" + reason);
    }

    // ================= 全局请求头 =================

    private static final String PREF = "mintv";
    /** 本次会话正在试用的内核。若进程被 native 崩溃带走，下次启动靠它识别。 */
    private static final String KEY_ENGINE_TRY = "engine_try";
    private static final String KEY_HEADERS = "global_headers";

    /**
     * 内核「试运行」标记。
     *
     * <p>为什么要这个：native 库崩溃（SIGSEGV）发生在 JNI 之外，
     * Java 的 Thread.setDefaultUncaughtExceptionHandler **完全收不到**，
     * 进程直接被信号杀掉，弹不出任何框。所以只能提前埋标记，
     * 下次启动发现"上次声明要用某个内核，却没走到首帧"，就判定它挂了。
     *
     * <p>这是 native 崩溃唯一可行的"拦截"方式：事后识别 + 自动回退，
     * 而不是当场捕获。
     */
    private void markEngineAttempt(PlaybackEngine e) {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_ENGINE_TRY, e.name()).apply();
    }

    /** 首帧出来了 → 内核稳定，把标记清掉。 */
    private void clearEngineAttempt() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .remove(KEY_ENGINE_TRY).apply();
    }

    /**
     * 启动时检查上个会话是不是崩在内核上。
     * 是的话把引擎退回 ExoPlayer，并明确告诉用户发生了什么。
     */
    private void recoverFromEngineCrash() {
        String tried = getSharedPreferences(PREF, MODE_PRIVATE)
                .getString(KEY_ENGINE_TRY, null);
        if (tried == null) return;
        getSharedPreferences(PREF, MODE_PRIVATE).edit().remove(KEY_ENGINE_TRY).apply();

        PlaybackEngine bad;
        try {
            bad = PlaybackEngine.valueOf(tried);
        } catch (Throwable t) {
            return;
        }
        if (bad == PlaybackEngine.EXO) return;   // Exo 挂了不是内核兼容问题

        selections[ROW_ENGINE] = 0;             // 退回 Exo
        if (rowViews.size() > ROW_ENGINE) rowViews.get(ROW_ENGINE).setSelected(0);

        final String msg = "上次切到 " + bad.label + " 内核后进程被原生库杀死"
                + "（native SIGSEGV，Java 层拦不住），已自动切回 ExoPlayer。\n\n"
                + "这不是脚本错误，是 " + bad.label + " 的 .so 与当前系统不兼容。";
        ui.post(new Runnable() {
            @Override public void run() {
                osd.toast("上次 " + bad.label + " 内核崩溃，已回退 ExoPlayer");
                CrashActivity.launch(MainActivity.this, "内核崩溃已回退", msg);
            }
        });
    }

    // ---- 每个频道上次能播的线路 ----
    private static final String KEY_LINE_PREFS = "line_prefs";
    private static final String KEY_FAVS = "favorites";
    private static final String KEY_LAST_CHAN = "last_chan";
    private static final String KEY_LAST_CAT = "last_cat";

    /**
     * 启动后要恢复到的频道/分类。
     *
     * <p>不能只在 onCreate 里恢复一次：源是**异步**拉的，拉回来之后会
     * applyPlaylist + bindChannels + updateChannelFocus(0)，把刚恢复的位置整个冲掉。
     * 所以把它当成一个"待办目标"，每次列表重建后都重新应用一次，
     * 直到用户自己点了频道 / 切了分类才清掉。
     */
    private String restoreChanKey;
    private String restoreCatName;
    private static final String FAV_CATEGORY = "我的收藏";

    /** 收藏的频道，key = 台号|台名。用 LinkedHashSet 保住收藏顺序。 */
    private final java.util.LinkedHashSet<String> favorites = new java.util.LinkedHashSet<>();
    private final java.util.Map<String, Integer> linePref = new java.util.HashMap<>();

    private static String lineKey(PlaylistParser.Channel ch) {
        return ch.number + "|" + ch.name;
    }

    private static String favKey(PlaylistParser.Channel ch) {
        return ch.number + "|" + ch.name;
    }

    private void loadFavorites() {
        favorites.clear();
        String raw = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_FAVS, "");
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) favorites.add(line.trim());
        }
    }

    private void saveFavorites() {
        StringBuilder sb = new StringBuilder();
        for (String k : favorites) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(k);
        }
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_FAVS, sb.toString()).apply();
    }

    private boolean isFavorite(PlaylistParser.Channel ch) {
        return favorites.contains(favKey(ch));
    }

    /**
     * 长按频道 = 收藏 / 取消收藏。
     *
     * <p>先判断再改：返回 true 表示这次是「收藏」，false 表示「取消」。
     */
    private boolean toggleFavorite(PlaylistParser.Channel ch) {
        String k = favKey(ch);
        boolean added;
        if (favorites.contains(k)) { favorites.remove(k); added = false; }
        else { favorites.add(k); added = true; }
        saveFavorites();
        refreshFavCategory();
        // 在收藏页里取消收藏，要把这一行从当前列表里去掉
        if (currentCategoryName().equals(FAV_CATEGORY)) {
            applyCategoryChannels(currentNavIndex());
        } else {
            syncPool(true);
        }
        osd.toast(added ? ("已收藏 " + ch.name) : ("已取消收藏 " + ch.name));
        return added;
    }

    /** 收藏分类的计数要跟着变。 */
    private void refreshFavCategory() {
        if (categories == null || categories.isEmpty()) return;
        for (int i = 0; i < categories.size(); i++) {
            DemoData.Category c = categories.get(i);
            if (FAV_CATEGORY.equals(c.name)) {
                categories.set(i, new DemoData.Category(FAV_CATEGORY, favorites.size(), c.glyph));
                rebuildCategoryColumn();
                updateNavFocus(focus.navIndex());
                return;
            }
        }
    }

    /** 当前选中的分类名。 */
    private String currentCategoryName() {
        int i = focus.navIndex();
        if (categories == null || i < 0 || i >= categories.size()) return "";
        return categories.get(i).name;
    }

    private int currentNavIndex() { return focus.navIndex(); }

    /** 按分类名重新算 channels 并重建列表（收藏页会动态变化）。 */
    private void applyCategoryChannels(int catIndex) {
        if (categories == null || catIndex < 0 || catIndex >= categories.size()) return;
        String name = categories.get(catIndex).name;
        List<PlaylistParser.Channel> list;
        if (FAV_CATEGORY.equals(name)) {
            list = new ArrayList<>();
            for (PlaylistParser.Channel ch : playlist.all) {
                if (favorites.contains(favKey(ch))) list.add(ch);
            }
        } else if ("全部".equals(name)) {
            list = playlist.all;
        } else {
            list = playlist.byGroup.get(name);
            if (list == null) list = new ArrayList<>();
        }
        channels = list;
        currentLine = 0;
        bindChannels();
        focus.resetChannelFocus();
        updateChannelFocus(0);
    }

    /**
     * 记住「上次在看哪个台」。
     *
     * <p>以前只记住了每个频道各自的线路，却没记住"当前是哪个频道" ——
     * 重启后永远从第一个台开始，用户得自己翻回去。这个键存的是 台号|台名，
     * 频道列表重建、换源之后只要台还在就能对上。
     */
    private void saveLastChannel(PlaylistParser.Channel ch) {
        if (ch == null) return;
        // 频道和它所在的分类要一起记。只记频道的话，重启后列表停在「全部」、
        // 焦点还在第一个台上，用户看到的和正在播的根本对不上。
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_LAST_CHAN, favKey(ch))
                .putString(KEY_LAST_CAT, currentCategoryName())
                .apply();
    }

    private int indexOfCategory(String name) {
        if (categories == null || name == null) return -1;
        for (int i = 0; i < categories.size(); i++) {
            if (name.equals(categories.get(i).name)) return i;
        }
        return -1;
    }

    /** 启动时恢复上次的频道。找不到就保持默认（第一个台）。 */
    /** 列表重建后调用：把「上次在看的那台」重新定位出来。用户一动手就放弃。 */
    private void tryRestoreIfPending() {
        if (restoreChanKey == null || restoreChanKey.isEmpty()) return;
        if (categories == null || categories.isEmpty() || channels == null) return;

        // ① 分类
        int ci = indexOfCategory(restoreCatName);
        if (ci >= 0 && !restoreCatName.equals(currentCategoryName())) {
            focus.setNavIndexSilently(ci);
            switchCategory(ci);
            updateNavFocus(ci);
        }
        // ② 频道
        int idx = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (favKey(channels.get(i)).equals(restoreChanKey)) { idx = i; break; }
        }
        if (idx < 0) return;
        final int prev = playingChannel;
        playingChannel = idx;
        focus.setChannelCount(channels.size());
        focus.resetChannelFocus();
        final int target = idx;
        if (chanScroll != null) {
            chanScroll.post(new Runnable() {
                @Override public void run() {
                    ensureBound(target);
                    updateChannelFocus(target);
                }
            });
        }
        // 界面已经起播了（Surface 先就绪、源后回来）就把正在播的台也切过去，
        // 否则会出现"列表停在那台、声音还是第一台"的错位。
        if (prev != idx && currentPlayUrl != null) {
            playChannelInternal(idx, false);
        }
    }

    /** 用户自己动手了，启动恢复目标作废。 */
    private void clearRestorePending() {
        restoreChanKey = null;
        restoreCatName = null;
    }

    private void restoreLastChannel() {
        android.content.SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        restoreChanKey = p.getString(KEY_LAST_CHAN, null);
        restoreCatName = p.getString(KEY_LAST_CAT, null);
        tryRestoreIfPending();
    }

    private void loadLinePrefs() {
        linePref.clear();
        String raw = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_LINE_PREFS, "");
        if (raw == null || raw.isEmpty()) return;
        for (String part : raw.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                linePref.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) { }
        }
    }

    /** 首帧出来之后调用：这条线路是能播的，记下来。 */
    private void rememberLine() {
        if (playingChannel < 0 || playingChannel >= channels.size()) return;
        PlaylistParser.Channel ch = channels.get(playingChannel);
        linePref.put(lineKey(ch), currentLine);
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : linePref.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_LINE_PREFS, sb.toString()).apply();
    }

    private void loadHeaders() {
        String raw = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_HEADERS, "");
        globalHeaders = parseHeaderText(raw);
        if (globalHeaders == null) globalHeaders = new java.util.LinkedHashMap<>();
        if (rowViews.size() > ROW_HEADERS) {
            rowViews.get(ROW_HEADERS).setSelected(globalHeaders.isEmpty() ? 0 : 1);
        }
    }

    private void saveHeaders() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : globalHeaders.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_HEADERS, sb.toString()).apply();
        selections[ROW_HEADERS] = globalHeaders.isEmpty() ? 0 : 1;
        if (rowViews.size() > ROW_HEADERS) {
            rowViews.get(ROW_HEADERS).setSelected(selections[ROW_HEADERS]);
        }
    }

    /** 一行一条，支持换行或分号分隔： {@code User-Agent=okhttp/3.12} */
    private static java.util.Map<String, String> parseHeaderText(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String line : raw.split("[;\n]")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            out.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
        }
        return out.isEmpty() ? null : out;
    }

    private static String headerText(java.util.Map<String, String> h) {
        if (h == null || h.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : h.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 编辑全局请求头。
     *
     * <p>TV 上打字不方便，所以除了弹输入框，还把「常见站点模板」做成
     * 一行提示，用户照着抄即可。填完立即生效，不用重启。
     */
    private void openHeaderEditor() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(headerText(globalHeaders));
        input.setHint("User-Agent=okhttp/3.12\nReferer=http://www.example.com/");
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(18));
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF8A8A90);
        input.setBackgroundColor(0xFF1B1C1F);
        input.setPadding(Theme.px(18), Theme.px(14), Theme.px(18), Theme.px(14));
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);

        new android.app.AlertDialog.Builder(this)
                .setTitle("全局请求头（一行一条，留空清除）")
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        java.util.Map<String, String> h = parseHeaderText(input.getText().toString());
                        globalHeaders = h == null ? new java.util.LinkedHashMap<String, String>() : h;
                        saveHeaders();
                        resetNoVideoStage();
                        playChannel(playingChannel);
                        osd.toast(globalHeaders.isEmpty() ? "已清除请求头" : "请求头已生效");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 手动切线路。
     *
     * <p>同一频道在源里常有多条线路（几十上百条的 m3u 尤其明显），
     * 自动换源只在起播失败时才动，用户想主动换一条试试得有个入口。
     * 这里做的是真正的循环切换：最后一条的"下一条"回到第一条。
     */
    private void switchSource(int delta) {
        if (playingChannel < 0 || playingChannel >= channels.size()) return;
        PlaylistParser.Channel ch = channels.get(playingChannel);
        int n = ch.sources.size();
        if (n <= 1) {
            osd.toast("该频道只有一条线路");
            return;
        }
        currentLine = ((currentLine + delta) % n + n) % n;
        resetNoVideoStage();          // 手动换线不算自愈，把降级进度清掉
        osd.toast("切换线路 " + (currentLine + 1) + "/" + n);
        playChannelInternal(playingChannel, true);
    }

    /** 切换内核并立刻重开当前源。 */
    private void rebuildEngine(PlaybackEngine e, boolean pinned) {
        if (playerManager == null) return;
        playerManager.setEngine(e, pinned);
        playerManager.setSurfaceView(surfaceView);
        playerManager.setCallback(playerCallback);
    }

    private PlaybackEngine engineOf(int decoderOption) { return PlaybackEngine.of(decoderOption); }

    /**
     * 唯一的播放入口。所有内核、所有模式都从这里走，
     * 请求头也只在这里合并一次，避免某条路径忘了带头。
     */
    /**
     * 这些协议只能靠 VLC。
     *
     * <p>实测依据：
     * <ul>
     *   <li>{@code udp://} / {@code rtp://}：Media3 只有
     *       {@code androidx.media3.datasource.UdpDataSource} 这一个裸 UDP 数据源，
     *       而且它没被 DefaultDataSource 收录，rtp:// 更没有对应实现；</li>
     *   <li>{@code rtsp://}：Exo 要额外引 media3-exoplayer-rtsp，而 libVLC 里
     *       自带 live555（strings 能搜到 RTSPClient::sendDescribeCommand 等符号）；</li>
     *   <li>{@code mms://}：Exo 完全不支持，libVLC 有 mmsu/mmsh。</li>
     * </ul>
     * 所以这几种协议直接把内核切成 VLC，不去赌 Exo 能不能凑合。
     */
    private static final String[] VLC_ONLY_SCHEMES = {
            "udp://", "rtp://", "rtsp://", "rtsps://", "mms://", "mmst://", "mmsu://", "srt://"
    };

    private static boolean needsVlc(String url) {
        String u = url.toLowerCase(java.util.Locale.US);
        for (String s : VLC_ONLY_SCHEMES) {
            if (u.startsWith(s)) return true;
        }
        return false;
    }

    private void playUrl(String url, java.util.Map<String, String> headers, PlaybackBackend.Mode mode) {
        if (url == null || url.isEmpty()) return;
        if (playerManager == null) return;

        // 组播/单播电视源（udp/rtp/rtsp）自动走 VLC，播放完切台也不会跳回去，
        // 避免每条都重建引擎。
        if (needsVlc(url) && playerManager.engine() != PlaybackEngine.VLC) {
            osd.toast("组播/RTSP 源，自动切换到 VLC 内核");
            playerManager.setEngine(PlaybackEngine.VLC, false);
            playerManager.setTextureView(textureView);
            playerManager.setCallback(playerCallback);
        }
        java.util.Map<String, String> merged = HttpHeaderProfile.merge(globalHeaders, headers);
        if (!merged.isEmpty()
                && playerManager.engine() == PlaybackEngine.VLC) {
            String lost = HttpHeaderProfile.vlcUnsupported(merged);
            if (!lost.isEmpty()) osd.toast("VLC 不支持请求头 " + lost + "，可能影响播放");
        }
        playerManager.play(getApplicationContext(), url, merged, mode);
    }

    /** 用户主动换台 / 换分类：自愈进度清零。 */
    private void resetNoVideoStage() { noVideoStage = 0; }

    private boolean autoSwitchSource() { return selections[ROW_TIMEOUT] != 0; }

    /** 超时阈值。0 = 用户关了超时换源。 */
    private long switchTimeoutMs() {
        switch (selections[ROW_TIMEOUT]) {
            case 1: return 5000;
            case 2: return 8000;
            case 3: return 12000;
            case 4: return 20000;
            default: return 0;
        }
    }

    private boolean playing = false;
    private boolean watchdogFired = false;

    // ---- 播放中卡顿检测 ----
    /** 上一次看到的播放位置，以及它最后一次变化的时间。 */
    private long stallLastPos = -1;
    private long stallSince = 0;

    /**
     * 播放中卡顿检测。
     *
     * <p>原来的看门狗只管「起播 N 秒内没出首帧」，一旦出帧就把自己撤了，
     * 之后播到一半卡住（缓冲不再增长、画面冻住）没有任何机制会处理 ——
     * 电视上这种情况比"起播失败"更常见，尤其是码率高于实际带宽时。
     *
     * <p>判据用的是**播放位置是否前进**，不是网速阈值。理由：
     * 直播间没有可信的「应有码率」可比，网速抖一下不代表卡住；
     * 而"位置 8 秒不动"是用户真正感受到的症状，且和内核无关
     * （Exo / VLC 都会让 positionMs 停住）。
     *
     * <p>位置不动的原因可能是网络断流、也可能是服务端切片 404，
     * 这两种都该换源，所以不细分。
     */
    private void checkStall(long pos) {
        if (!autoSwitchSource() || watchdogFired || !playing) return;
        long limit = switchTimeoutMs();
        if (limit <= 0) return;                 // 关闭
        long now = SystemClock.uptimeMillis();

        if (pos != stallLastPos) {
            stallLastPos = pos;
            stallSince = now;
            return;
        }
        if (stallSince == 0) { stallSince = now; return; }
        if (now - stallSince < limit) return;

        // 卡住了：换下一条线路
        stallSince = now;
        PlaylistParser.Channel ch = (playingChannel >= 0 && playingChannel < channels.size())
                ? channels.get(playingChannel) : null;
        if (ch != null && ch.sources.size() > 1) {
            currentLine = (currentLine + 1) % ch.sources.size();
            osd.toast("播放卡住 " + (limit / 1000) + " 秒，切换线路 "
                    + (currentLine + 1) + "/" + ch.sources.size());
            playChannelInternal(playingChannel, true);
        } else {
            osd.toast("播放卡住，该频道只有一条线路");
            watchdogFired = true;               // 别反复刷提示
        }
    }

    /** 起播后调用：8 秒没出画面就自愈。 */
    private void armWatchdog() {
        playing = false;
        watchdogFired = false;
        ui.removeCallbacks(playbackWatchdog);
        if (!autoSwitchSource()) return;      // 设置里关掉就不看门狗
        ui.postDelayed(playbackWatchdog, switchTimeoutMs());
    }

    /** 把播放状态写到外部文件，用于排查「有解码器但没画面」。 */
    private void dumpPlaybackState(String tag, String extra) {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            java.io.File f = new java.io.File(dir, "playback.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(System.currentTimeMillis() + " [" + tag + "] "
                    + "url=" + currentPlayUrl
                    + " surface=" + (surfaceView != null && surfaceView.getHolder().getSurface() != null
                        && surfaceView.getHolder().getSurface().isValid())
                    + " mode=" + (player == null ? "null" : player.mode())
                    + " canSeek=" + (player == null ? "null" : player.canSeek())
                    + " pos=" + (player == null ? -1 : player.positionMs())
                    + " dur=" + (player == null ? -1 : player.durationMs())
                    + (extra == null ? "" : " " + extra) + "\n");
            w.close();
        } catch (Throwable ignored) { }
    }

    private String currentPlayUrl;

    /** 弹播放诊断面板。 */
    private void showDiagnostics() {
        if (errorPanel == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("当前地址: ").append(currentPlayUrl).append('\n');
        sb.append("播放器: ").append(player == null ? "null" : player.getClass().getSimpleName()).append('\n');
        if (playerManager != null) {
            sb.append("状态: ").append(playerManager.diagnostics()).append('\n');
        }
        boolean surfaceOk = surfaceView != null
                && surfaceView.getHolder().getSurface() != null
                && surfaceView.getHolder().getSurface().isValid();
        sb.append("Surface 有效: ").append(surfaceOk).append('\n');
        sb.append("SurfaceView 尺寸: ")
                .append(surfaceView == null ? "-" : (surfaceView.getWidth() + "x" + surfaceView.getHeight()))
                .append('\n');
        sb.append("面板可见: ").append(panelVisible).append("  抽屉: ").append(epgPanelOpen).append('\n');
        sb.append("屏幕: ").append(getResources().getDisplayMetrics().widthPixels)
                .append('x').append(getResources().getDisplayMetrics().heightPixels).append('\n');
        errorPanel.show("播放诊断", sb.toString());
    }

    /**
     * 「切源」那一行的文字跟着当前线路变：◀ / 源 2/4 / ▶。
     * 不然用户点了以后看不出自己在第几条线上。
     */
    private void refreshSourceRow() {
        if (rowViews.size() <= ROW_SOURCE) return;
        PlaylistParser.Channel ch = (playingChannel >= 0 && playingChannel < channels.size())
                ? channels.get(playingChannel) : null;
        int total = ch == null ? 1 : Math.max(1, ch.sources.size());
        String[] labels = {"◀ 上一条", "源 " + (currentLine + 1) + "/" + total, "下一条 ▶"};
        SettingRowView row = rowViews.get(ROW_SOURCE);
        row.setActionRow(true);
        // -1 = 不画绿底勾：这是动作行，没有"当前值"这回事
        row.bind(DemoData.ROW_TITLES[ROW_SOURCE][0], DemoData.ROW_TITLES[ROW_SOURCE][1],
                labels, -1);
    }

    private void refreshOsdTags() {
        String src = lineTotal > 1 ? ("源 " + (currentLine + 1) + "/" + lineTotal) : "源 1/1";
        // 网速显示设置：0 = 显示，1 = 隐藏
        String speed = selections[ROW_SPEED] == 0 ? speedMeter.text() : null;
        osd.setTags(src, null, lastResolution, lastVCodec, lastACodec, null,
                lastFps, lastBitrate, speed);
    }

    // ================= EPG =================

    private void loadEpgAsync() {
        final byte[] cached = EpgStore.cached(this);
        final boolean fresh = EpgStore.isFresh(this);
        if (cached != null) {
            new Thread(new Runnable() {
                @Override public void run() {
                    final EpgParser.Epg e = EpgParser.parse(cached);
                    ui.post(new Runnable() {
                        @Override public void run() { applyEpg(e); }
                    });
                }
            }, "epg-parse").start();
        }
        if (cached != null && fresh) return;

        new Thread(new Runnable() {
            @Override public void run() {
                for (String url : EpgStore.SOURCES) {
                    byte[] raw = download(url);
                    if (raw == null || raw.length < 1024) continue;
                    EpgStore.save(MainActivity.this, raw);
                    final EpgParser.Epg e = EpgParser.parse(raw);
                    ui.post(new Runnable() {
                        @Override public void run() { applyEpg(e); }
                    });
                    return;
                }
            }
        }, "epg-fetch").start();
    }

    private byte[] download(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "TVUI/1.0");
            conn.setInstanceFollowRedirects(true);
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2 << 20);
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void applyEpg(EpgParser.Epg e) {
        if (e == null || e.byChannel.isEmpty()) return;
        epg = e;
        epgIdOf.clear();
        for (int i = 0; i < channels.size(); i++) {
            PlaylistParser.Channel ch = channels.get(i);
            String id = EpgParser.matchChannelId(ch.tvgId(), ch.name, e);
            if (id != null) epgIdOf.put(i, id);
        }
        refreshChannelSubs();
        refreshPlayInfo();
        probeCatchupAsync();
        ui.removeCallbacks(epgTick);
        ui.postDelayed(epgTick, 30_000);
    }

    /** 给列表每一行填「正在播放 · 节目名」。 */
    /**
     * 刷新可见行的「正在播放」副标题。
     *
     * <p>行池里的 View 会被反复绑定到不同频道，所以这里必须按
     * chanBind 里记录的下标去取 EPG，不能拿池下标当频道下标用。
     */
    private void refreshChannelSubs() {
        if (epg == null) return;
        long now = System.currentTimeMillis();
        for (int k = 0; k < chanViews.size(); k++) {
            int idx = chanBind.get(k);
            if (idx < 0 || idx >= channels.size()) continue;
            ChannelView v = chanViews.get(k);
            String id = epgIdOf.get(idx);
            if (id == null) { v.setSub(""); continue; }
            EpgParser.Program live = EpgParser.live(epg.of(id), now);
            if (live == null) { v.setSub(""); continue; }
            v.setSub("正在播放 · " + EpgParser.hm(live.startMs) + " " + live.title);
            v.setBadge(hasReplay(idx) ? "可回看" : null);
            v.invalidate();
        }
    }

    /**
     * 频道是否真的支持回看。
     * 判据是<b>源的 catchup 属性</b>，不是「EPG 里有没有播完的节目」——
     * 后者每个有 EPG 的台都成立，会导致整列都标成可回看。
     */
    /**
     * 后台探测回看能力。只探前若干个不同 host —— 同一个服务器只发一次请求，
     * 探完刷新列表上的「可回看」标记。
     */
    private void probeCatchupAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
                java.util.Map<String, PlaylistParser.Channel> sample = new java.util.HashMap<>();
                for (PlaylistParser.Channel ch : channels) {
                    String h = hostOf(ch);
                    if (h == null || hosts.contains(h)) continue;
                    if (hosts.size() >= 8) break;          // 最多探 8 台服务器
                    hosts.add(h);
                    sample.put(h, ch);
                }
                for (String h : hosts) {
                    if (CatchupProbe.cached(h) != null) continue;
                    final PlaylistParser.Channel ch = sample.get(h);
                    if (ch == null) continue;
                    // 用「两小时前」的时段探测，任何源都应该有这个时间点的录像
                    long end = System.currentTimeMillis() - 3600_000L;
                    long start = end - 3600_000L;
                    final boolean ok = CatchupProbe.probe(h,
                            PlaylistParser.cleanUrl(ch.sources.get(0).url),
                            ch.catchupSource, ch.catchup, start, end);
                    ui.post(new Runnable() {
                        @Override public void run() { refreshChannelSubs(); }
                    });
                }
            }
        }, "catchup-probe").start();
    }

    private boolean hasReplay(int channelIndex) {
        if (channelIndex < 0 || channelIndex >= channels.size()) return false;
        PlaylistParser.Channel ch = channels.get(channelIndex);
        // 源里显式写了 catchup 就直接信它
        if (PlaylistParser.canReplay(ch)) return true;
        // 否则看对该服务器探测的结论
        String host = hostOf(ch);
        if (host == null) return false;
        Boolean r = CatchupProbe.cached(host);
        return r != null && r;
    }

    private static String hostOf(PlaylistParser.Channel ch) {
        if (ch == null || ch.sources.isEmpty()) return null;
        try {
            return new java.net.URL(PlaylistParser.cleanUrl(ch.sources.get(0).url)).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** 正在播的频道，刷新 OSD 里的「目前/下个节目」。 */
    private void refreshPlayInfo() {
        if (epg == null || playingChannel < 0 || playingChannel >= channels.size()) return;
        String id = epgIdOf.get(playingChannel);
        if (id == null) { osd.setProgram(null, null, -1f); return; }
        long now = System.currentTimeMillis();
        java.util.List<EpgParser.Program> list = epg.of(id);
        EpgParser.Program live = EpgParser.live(list, now);
        EpgParser.Program next = EpgParser.next(list, now);
        String nowText = live == null ? null
                : EpgParser.hm(live.startMs) + " " + live.title;
        String nextText = next == null ? null
                : EpgParser.hm(next.startMs) + " " + next.title;
        float prog = -1f;
        if (live != null && live.stopMs > live.startMs) {
            prog = (now - live.startMs) / (float) (live.stopMs - live.startMs);
        }
        osd.setProgram(nowText, nextText, prog);
    }

    /** 展开抽屉：分类收进去，露出「频道列表 + EPG 节目」。 */
    private void showEpgPanel(int channelIndex) {
        fillEpg(channelIndex);
        openEpgPanel();
    }

    /** 只刷新节目单内容，不动抽屉状态。 */
    private void fillEpg(int channelIndex) {
        if (epgPanel == null || channelIndex < 0 || channelIndex >= channels.size()) return;
        PlaylistParser.Channel ch = channels.get(channelIndex);
        java.util.List<EpgParser.Program> list = epg == null ? null : epg.of(epgIdOf.get(channelIndex));
        if (list == null || list.isEmpty()) {
            epgPanel.setData(ch.name + " · 暂无节目单", new EpgPanelView.Item[0]);
        } else {
            long now = System.currentTimeMillis();
            boolean canReplay = hasReplay(channelIndex);

            // 一天几十个节目全列出来没意义：只显示「当前 + 前 4 + 后 4」，
            // 正好一屏，滚一下就能看前后，跟主流播放器一致。
            int liveIdx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).isLive(now)) { liveIdx = i; break; }
            }
            if (liveIdx < 0) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).startMs > now) { liveIdx = i; break; }
                }
            }
            if (liveIdx < 0) liveIdx = 0;

            int from = Math.max(0, liveIdx - 4);
            int to = Math.min(list.size(), liveIdx + 5);

            epgWindow.clear();
            java.util.ArrayList<EpgPanelView.Item> items = new java.util.ArrayList<>();
            for (int i = from; i < to; i++) {
                EpgParser.Program p = list.get(i);
                epgWindow.add(p);
                boolean live = p.isLive(now);
                boolean ended = p.stopMs <= now;
                // 只有「已播完 + 源支持回看」的条目才可点
                items.add(new EpgPanelView.Item(EpgParser.hm(p.startMs), p.title,
                        live, ended && canReplay, ended, p.startMs, p.stopMs));
            }
            epgPanel.setData(ch.name + (canReplay ? " · 一周回看" : " · 节目单"),
                    items.toArray(new EpgPanelView.Item[0]));
            // setData 会重置内部状态，高亮必须在它之后重新打
            int replayIdx = -1;
            if (replayStartMs > 0) {
                for (int i = 0; i < epgWindow.size(); i++) {
                    if (epgWindow.get(i).startMs == replayStartMs) { replayIdx = i; break; }
                }
            }
            epgPanel.setReplayIndex(replayIdx);
        }
        // 高度撑满视口
        int vh = getWindow().getDecorView().getHeight();
        android.view.ViewGroup.LayoutParams lp = epgPanel.getLayoutParams();
        if (lp != null && vh > 0 && lp.height != vh) {
            lp.height = vh;
            epgPanel.setLayoutParams(lp);
        }
    }

    /**
     * 三种状态，互斥且有明确返回路径：
     *
     * ① 常态   [分类 360][频道 476][竖轨][视频]
     * ② 抽屉   [频道 476][节目单 422][视频]      ← 分类滑出，点频道列 / 返回 → ①
     * ③ 播放   整屏只有视频                        ← 点播放区 / 返回 → ①
     *
     * 关键：抽屉态下频道列必须在最左边可点，否则用户回不去。
     */
    private void openEpgPanel() {
        if (epgPanelOpen) return;
        setPanelVisible(true);          // 抽屉需要面板可见
        epgPanelOpen = true;

        epgScroll.setVisibility(View.VISIBLE);
        epgScroll.setAlpha(0f);
        epgCursor = epgPanel == null ? -1 : epgPanel.liveIndex();
        if (epgCursor >= 0 && epgPanel != null) epgPanel.setFocus(epgCursor);
        epgScroll.animate().alpha(1f).setDuration(Theme.T_PANEL).start();

        // 分类栏滑出屏幕
        navPanel.animate()
                .translationX(-Theme.px(Theme.NAV_W))
                .alpha(0f)
                .setDuration(Theme.T_PANEL).start();
        navDivider.animate().alpha(0f).setDuration(Theme.T_PANEL).start();
        railView.animate().alpha(0f).setDuration(Theme.T_PANEL).start();
        // 滑出去的分类栏不能再吃点击
        for (NavItemView v : navViews) v.setClickable(false);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!epgPanelOpen) return;
                navPanel.setVisibility(View.GONE);
                navDivider.setVisibility(View.GONE);
                railView.setVisibility(View.GONE);
            }
        }, Theme.T_PANEL + 40);
        // 频道列左移补位
        chanContainer.animate()
                .translationX(-Theme.px(Theme.NAV_W))
                .setDuration(Theme.T_PANEL).start();
        layoutVideoTap();
        updateOsdInset();
    }

    /** 收起抽屉：分类栏滑回，频道列归位，节目单淡出。 */
    private void closeEpgPanel() {
        if (!epgPanelOpen) return;
        epgPanelOpen = false;

        epgScroll.animate().alpha(0f).setDuration(Theme.T_PANEL)
                .withEndAction(new Runnable() {
                    @Override public void run() { epgScroll.setVisibility(View.GONE); }
                }).start();

        navPanel.setVisibility(View.VISIBLE);
        navDivider.setVisibility(View.VISIBLE);
        railView.setVisibility(View.VISIBLE);
        navPanel.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(Theme.T_PANEL).start();
        navDivider.animate().alpha(1f).setDuration(Theme.T_PANEL).start();
        railView.animate().alpha(1f).setDuration(Theme.T_PANEL).start();
        for (NavItemView v : navViews) v.setClickable(true);
        chanContainer.animate()
                .translationX(0f)
                .setDuration(Theme.T_PANEL).start();
        layoutVideoTap();
        updateOsdInset();
    }

    /** ③ 纯播放：整套面板淡出，只留视频。任意方向键/点击恢复。 */
    private void enterFullPlayer() {
        if (epgPanelOpen) closeEpgPanel();
        setPanelVisible(false);
    }

    /**
     * 面板显隐。
     *
     * 关键点：alpha=0 的 View <b>依然会接收触摸和点击</b>，
     * 所以「看不见却还能点」的 bug 必须靠 setVisibility / 设置 clickable 来解决，
     * 不能只做透明度动画。
     */
    private void setPanelVisible(final boolean visible) {
        if (panelVisible == visible) return;
        panelVisible = visible;
        final float to = visible ? 1f : 0f;

        if (visible) {
            // 先恢复可见，再做淡入
            setPanelsInteractive(true);
            navPanel.setVisibility(View.VISIBLE);
            navDivider.setVisibility(View.VISIBLE);
            chanContainer.setVisibility(View.VISIBLE);
            railView.setVisibility(View.VISIBLE);
            scrim.setVisibility(View.VISIBLE);
        }

        navPanel.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        navDivider.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        chanContainer.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        railView.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        scrim.animate().alpha(visible ? 1f : 0f).setDuration(Theme.T_PANEL).start();
        // 时钟以前完全不参与面板显隐，所以纯播放态右上角一直挂着日期时间。
        // 现在按设置项决定：时钟显示=显示 时随面板一起淡出，=常显 时始终可见。
        if (clock != null) {
            boolean keepClock = selections[ROW_CLOCK] == 1;
            if (keepClock) {
                clock.setVisibility(View.VISIBLE);
                clock.animate().alpha(1f).setDuration(Theme.T_PANEL).start();
            } else {
                clock.animate().alpha(to).setDuration(Theme.T_PANEL).start();
                if (!visible) clock.setVisibility(View.GONE);
                else clock.setVisibility(View.VISIBLE);
            }
        }

        if (!visible) {
            // 淡出结束后彻底移除，才能保证点不到
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (panelVisible) return;      // 期间又被唤出
                    setPanelsInteractive(false);
                    navPanel.setVisibility(View.GONE);
                    navDivider.setVisibility(View.GONE);
                    chanContainer.setVisibility(View.GONE);
                    railView.setVisibility(View.GONE);
                    scrim.setVisibility(View.GONE);
                }
            }, Theme.T_PANEL + 40);
        }

        if (gestureLayer != null) {
            gestureLayer.setVisibility(visible ? View.GONE : View.VISIBLE);
        }
        // 面板可见时视频区可点（点一下收起）；面板收起时交给 gestureLayer 接管
        if (videoTap != null) {
            videoTap.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        updateOsdInset();
    }

    /** 面板不可见时把子 View 全部设为不可点击，双保险。 */
    private void setPanelsInteractive(boolean enabled) {
        for (NavItemView v : navViews) v.setClickable(enabled);
        for (ChannelView v : chanViews) v.setClickable(enabled);
        if (chanContainer != null) chanContainer.setClickable(enabled);
    }

    private boolean epgPanelOpen = false;
    /** 节目单里方向键的光标位置。抽屉展开时从"正在播"那一条开始。 */
    private int epgCursor = -1;
    /** 当前节目单窗口里每一条对应的原始节目，下标与 EpgPanelView 的行一一对应。 */
    private final java.util.ArrayList<EpgParser.Program> epgWindow = new java.util.ArrayList<>();
    /** 正在回放的节目开始时间；-1 表示当前是直播。用来在节目单里打黄色高亮。 */
    private long replayStartMs = -1;

    /** 节目单里第 idx 条：已播完且源支持回看 → 播时移地址。 */
    private void playReplay(int channelIndex, int idx) {
        if (channelIndex < 0 || channelIndex >= channels.size()) return;
        PlaylistParser.Channel ch = channels.get(channelIndex);
        EpgParser.Program prog = programAt(channelIndex, idx);
        if (prog == null) return;

        final String host = hostOf(ch);
        final PlaylistParser.Channel fch = ch;
        final EpgParser.Program fprog = prog;
        final int fIdx = idx;

        // 已经探过且不支持 → 直接提示，不再发请求
        Boolean known = host == null ? null : CatchupProbe.cached(host);
        if (Boolean.FALSE.equals(known) && !PlaylistParser.declaredReplay(ch)) {
            osd.toast("该源不支持回看");
            return;
        }

        osd.toast("正在解析回看…");
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = true;
                if (known == null) {
                    // 没探过 → 当场探一次（只发 1 个请求，因为结果按 host 缓存）
                    ok = CatchupProbe.probe(host,
                            PlaylistParser.cleanUrl(fch.sources.get(0).url),
                            fch.catchupSource, fch.catchup,
                            fprog.startMs, fprog.stopMs);
                }
                final boolean supported = ok || PlaylistParser.declaredReplay(fch);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!supported) {
                            osd.toast("该源不支持回看");
                            return;
                        }
                        String url = PlaylistParser.declaredReplay(fch)
                                ? PlaylistParser.buildCatchupUrl(fch, fprog.startMs, fprog.stopMs)
                                : CatchupProbe.build(host,
                                    PlaylistParser.cleanUrl(fch.sources.get(0).url),
                                    fprog.startMs, fprog.stopMs);
                        if (url == null || url.isEmpty()) {
                            osd.toast("回看地址生成失败");
                            return;
                        }
                        startReplay(fch, fprog, fIdx, url);
                    }
                });
            }
        }, "catchup-go").start();
    }

    private void startReplay(PlaylistParser.Channel ch, EpgParser.Program prog, int idx, String url) {
        epgPanel.setFocus(idx);
        String no = String.valueOf(ch.number);
        while (no.length() < 4) no = "0" + no;
        osd.showChannel(no, ch.name + " · 回看 " + EpgParser.hm(prog.startMs), ch.group);
        osd.setProgram(null, null, -1f);
        refreshOsdTags();
        // 记下这条，节目单里用黄色标出来，用户能看到"现在放的是哪一条"
        replayStartMs = prog.startMs;
        epgPanel.setReplayIndex(idx);
        playUrl(url, null, PlaybackBackend.Mode.TIMESHIFT);
        // 不再自动收起节目单：收起就看不到回放高亮了。想全屏看片自己按返回。
    }

    /**
     * 回到直播。
     *
     * <p>回看状态下没有任何入口能回到当前直播 —— 这在使用上是死路：
     * 用户点错一条老节目，就只能换台再换回来。EPG 上「直播中」那一行
     * 就是最自然的入口，点它就回直播。
     */
    /**
     * 节目单的方向键操作。
     *
     * <p>之前节目单只响应触摸：遥控器能打开抽屉，却选不中任何一条节目，
     * 「回看」在纯遥控器设备上等于不存在。
     * 这里补上标准做法：上下移光标、OK 确认、左键收起。
     */
    private boolean handleEpgKey(int keyCode) {
        if (epgPanel == null) { closeEpgPanel(); return false; }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int n = epgPanel.count();
                if (n <= 0) return true;
                if (epgCursor < 0) epgCursor = Math.max(0, epgPanel.liveIndex());
                epgCursor += (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) ? 1 : -1;
                if (epgCursor < 0) epgCursor = 0;
                if (epgCursor > n - 1) epgCursor = n - 1;
                epgPanel.setFocus(epgCursor);
                scrollEpgTo(epgCursor);
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER: {
                int ch = focus.chanIndex() >= 0 ? focus.chanIndex() : playingChannel;
                EpgParser.Program prog = programAt(ch, epgCursor);
                if (prog == null) return true;
                long now = System.currentTimeMillis();
                if (prog.startMs <= now && prog.stopMs > now) {
                    returnToLive(ch);
                } else if (prog.stopMs <= now) {
                    playReplay(ch, epgCursor);
                } else {
                    osd.toast("该节目还没开始");
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
                closeEpgPanel();
                return true;
            default:
                return false;
        }
    }

    /** 让光标那一行保持可见。 */
    private void scrollEpgTo(int index) {
        if (epgScroll == null || epgPanel == null) return;
        float top = epgPanel.topOf(index);
        float h = epgPanel.itemHeightPx();
        int viewH = epgScroll.getHeight();
        int scrollY = epgScroll.getScrollY();
        int target = scrollY;
        if (top < scrollY + Theme.px(20)) target = (int) (top - Theme.px(20));
        else if (top + h > scrollY + viewH - Theme.px(20)) {
            target = (int) (top + h - viewH + Theme.px(20));
        }
        if (target != scrollY) epgScroll.smoothScrollTo(0, Math.max(0, target));
    }

    private void returnToLive(int channelIndex) {
        replayStartMs = -1;
        if (epgPanel != null) epgPanel.setReplayIndex(-1);
        if (channelIndex >= 0 && channelIndex < channels.size()
                && channelIndex != playingChannel) {
            playChannel(channelIndex);
            fillEpg(channelIndex);
        } else {
            // 同一个频道：重开直播地址，走 LIVE 模式
            playChannelInternal(playingChannel, true);
        }
        osd.toast("已回到直播");
    }

    /** 取节目单窗口里的第 idx 条（idx 是界面行号，不是全量列表下标）。 */
    private EpgParser.Program programAt(int channelIndex, int idx) {
        if (idx < 0 || idx >= epgWindow.size()) return null;
        return epgWindow.get(idx);
    }



    // ================= 设置弹窗 =================

    private void buildDialog(FrameLayout root) {
        dialogRoot = new FrameLayout(this);
        dialogRoot.setBackgroundColor(0x66000000);
        dialogRoot.setVisibility(View.GONE);
        // 关键：这里绝对不能挂 OnTouchListener。
        // OnTouchListener 先于子 View 的 onTouchEvent 执行，只要它 return true
        // 就把事件整条吃掉 —— 于是弹窗里 Tab、✕、每一行的选项全都点不动。
        // 之前的 handleSettingsTouch 正是这样把所有点击吞了。
        // 正确做法：点遮罩关闭由 dialogRoot 的 OnClickListener 负责，
        // 内部控件各自挂自己的监听。
        dialogRoot.setClickable(true);
        dialogRoot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeSettings(); }
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.DLG_BG);
        // 卡片自己消费点击，否则点在卡片空白处会穿透到 dialogRoot 把弹窗关掉
        card.setClickable(true);

        int w = Theme.px(Theme.DLG_W);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                w, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cardLp.topMargin = Theme.px(50);

        // 标题栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Theme.px(24), 0, Theme.px(24), 0);

        tabViews.clear();
        for (int i = 0; i < DemoData.TABS.length; i++) {
            final int tabIndex = i;
            TextView tab = new TextView(this);
            tab.setText(DemoData.TABS[i]);
            tab.setTextColor(i == 1 ? Theme.TEXT : 0xFFD6D6D8);
            tab.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(21));
            tab.setGravity(Gravity.CENTER);
            tab.setBackgroundColor(i == 1 ? 0x0FFFFFFF : Color.TRANSPARENT);
            tab.setPadding(Theme.px(14), 0, Theme.px(14), 0);
            // 三个 Tab 以前是死的（只有「播放设置」画了个高亮底，点了不换页）。
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showTab(tabIndex); }
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Theme.px(52));
            tp.rightMargin = Theme.px(10);
            bar.addView(tab, tp);
            tabViews.add(tab);
        }

        TextView ver = new TextView(this);
        ver.setText("v" + versionName());
        ver.setTextColor(0xFF9A9A9E);
        ver.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(20));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.weight = 1;
        vp.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        bar.addView(ver, vp);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFD0D0D2);
        close.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(26));
        close.setGravity(Gravity.CENTER);
        // 以前这个 ✕ 只画了个字，没挂任何监听 —— 点了当然没反应。
        close.setClickable(true);
        close.setFocusable(true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeSettings(); }
        });
        // 触屏点击区做得比字大，遥控器上也更好点
        close.setPadding(Theme.px(8), Theme.px(8), Theme.px(8), Theme.px(8));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Theme.px(56), Theme.px(56));
        bar.addView(close, cp);
        card.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(76)));

        // 行
        rowColumn = new LinearLayout(this);
        rowColumn.setOrientation(LinearLayout.VERTICAL);
        rowColumn.setPadding(Theme.px(24), Theme.px(18), Theme.px(24), Theme.px(10));
        for (int i = 0; i < DemoData.ROW_TITLES.length; i++) {
            final int rowIndex = i;
            SettingRowView row = new SettingRowView(this);
            row.bind(DemoData.ROW_TITLES[i][0], DemoData.ROW_TITLES[i][1],
                    DemoData.ROW_OPTIONS[i], selections[i]);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    while (focus.rowIndex() < rowIndex) focus.onKey(KeyEvent.KEYCODE_DPAD_DOWN);
                    while (focus.rowIndex() > rowIndex) focus.onKey(KeyEvent.KEYCODE_DPAD_UP);
                    applyRowFocus(rowIndex);
                }
            });
            // 点右侧分段 → 直接改值并提交。以前分段控件压根没挂监听，
            // 点上去什么都不发生。
            row.setOnOptionClick(new SettingRowView.OnOptionClick() {
                @Override public void onOption(SettingRowView r, int opt) {
                    while (focus.rowIndex() < rowIndex) focus.onKey(KeyEvent.KEYCODE_DPAD_DOWN);
                    while (focus.rowIndex() > rowIndex) focus.onKey(KeyEvent.KEYCODE_DPAD_UP);
                    applyRowFocus(rowIndex);
                    r.setSelected(opt);
                    r.setRowFocused(false);
                    r.setOptionFocused(opt);
                    onSettingCommitted(rowIndex, opt);
                }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(82));
            p.bottomMargin = Theme.px(12);
            rowColumn.addView(row, p);
            rowViews.add(row);
        }
        ScrollView rowScroll = new ScrollView(this);
        rowScroll.setVerticalScrollBarEnabled(false);
        rowScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rowScroll.addView(rowColumn);
        rowScrollView = rowScroll;
        // 限高：卡片总高不能超出屏幕，超出部分滚动
        int maxRowsH = (int) (getResources().getDisplayMetrics().heightPixels * 0.66f);
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxRowsH,
                Theme.px(10 * (82 + 12)) + Theme.px(28)));
        card.addView(rowScroll, rowsLp);

        // 非「播放设置」两个 Tab 的说明页，和列表占同一个位置
        infoPage = new LinearLayout(this);
        infoPage.setOrientation(LinearLayout.VERTICAL);
        infoPage.setPadding(Theme.px(28), Theme.px(20), Theme.px(28), Theme.px(20));
        infoPage.setVisibility(View.GONE);
        infoPageText = new TextView(this);
        infoPageText.setTextColor(0xFFD6D6DA);
        infoPageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(19));
        infoPageText.setLineSpacing(Theme.px(6), 1f);
        infoPage.addView(infoPageText);
        card.addView(infoPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowsLp.height));

        // 底栏
        TextView foot = new TextView(this);
        foot.setText("ⓘ 白框 = 当前焦点；打勾 = 已选中的值        按 OK(Enter) 键修改配置，按 ESC 返回主画面");
        foot.setTextColor(0xFFB9B9BD);
        foot.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(17));
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(Theme.px(24), 0, Theme.px(24), 0);
        card.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(62)));

        dialogRoot.addView(card, cardLp);
        root.addView(dialogRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * 切设置页 Tab。
     *
     * <p>账户登录 / 手机助手 这两页没有后端支撑，但**不能装作能切**然后给一片空白。
     * 这里给每页一张说明卡，写清楚它依赖什么、当前状态如何 ——
     * 用户至少知道这里有什么、为什么还不能用。
     */
    private void showTab(int index) {
        currentTab = index;
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            t.setTextColor(i == index ? Theme.TEXT : 0xFFD6D6D8);
            t.setBackgroundColor(i == index ? 0x0FFFFFFF : Color.TRANSPARENT);
        }
        rowScrollView.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (index != 1) {
            fillInfoPage(index);
            infoPage.setVisibility(View.VISIBLE);
        } else {
            infoPage.setVisibility(View.GONE);
        }
    }

    /** 从 PackageManager 读版本号，不依赖 BuildConfig（javac 直编时没有它）。 */
    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.1.0";
        }
    }

    private static String fmtMs(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private String infoText(int tab) {
        if (tab == 0) {
            return "账户登录\n\n"
                    + "本版本是纯本地播放器：不注册、不上传、不联网校验账号。\n"
                    + "直播源地址存在本机 SharedPreferences 里，没有服务器，\n"
                    + "所以这一页暂时没有需要登录的东西。\n\n"
                    + "将来接后端时会在这里放扫码登录 + 设备绑定。";
        }
        return "手机助手\n\n"
                + "同一 Wi-Fi 下用手机浏览器遥控这台盒子。\n\n"
                + "需要做的：\n"
                + "  1. 盒子上开一个 HTTP 服务（监听 0.0.0.0，端口 8080）\n"
                + "  2. 手机上访问  http://<盒子IP>:8080\n"
                + "  3. 页面里发「换台 / 音量 / 返回」指令，盒子轮询执行\n\n"
                + "当前状态：未实现。这一版先把播放链路修稳，遥控后面再加。";
    }

    private void fillInfoPage(int tab) {
        if (infoPageText == null) return;
        infoPageText.setText(infoText(tab));
    }

    private void openSettings() {
        tabFocus = false;
        if (tabViews.size() > currentTab) showTab(Math.max(0, currentTab));
        if (gestureLayer != null) gestureLayer.setVisibility(View.GONE);
        dialogRoot.setVisibility(View.VISIBLE);
        dialogRoot.setAlpha(0f);
        dialogRoot.animate().alpha(1f).setDuration(140).start();
        int[] counts = new int[DemoData.ROW_OPTIONS.length];
        for (int i = 0; i < counts.length; i++) counts[i] = DemoData.ROW_OPTIONS[i].length;
        focus.enterSettings(counts);
        refreshSettingsFocus();
    }

    private void closeSettings() {
        tabFocus = false;
        dialogRoot.setVisibility(View.GONE);
        if (gestureLayer != null && !panelVisible) gestureLayer.setVisibility(View.VISIBLE);
        focus.exitSettings();
        updateChannelFocus(focus.chanIndex());
    }

    private void refreshSettingsFocus() {
        applyRowFocus(focus.rowIndex());
    }

    private void applyRowFocus(int rowIndex) {
        for (int i = 0; i < rowViews.size(); i++) {
            SettingRowView r = rowViews.get(i);
            r.setRowFocused(i == rowIndex);
            r.setOptionFocused(-1);
        }
    }

    // ================= 焦点回调 =================

    @Override
    public void onNavFocusChanged(int index) {
        updateNavFocus(index);
    }

    @Override
    public void onChannelFocusChanged(int index) {
        updateChannelFocus(index);
        if (epgPanelOpen) fillEpg(index);
    }

    @Override
    public void onSettingRowChanged(int rowIndex, int optIndex) {
        applyRowFocus(rowIndex);
    }

    /** 设置项写入后调用：只处理需要立刻生效的项。 */
    private void onSettingCommitted(int row, int value) {
        selections[row] = value;
        if (row == ROW_TIMEOUT) {
            armWatchdog();     // 立即按新策略重新计时
        } else if (row == ROW_DECODER) {
            // 软硬解：只对 Exo 有意义。改了就重建播放器（渲染器在 build 时固定）。
            if (playerManager != null && playerManager.engine() == PlaybackEngine.EXO) {
                softPref = (value == 1);
                rebuildEngine(PlaybackEngine.EXO, true);
                playChannel(playingChannel);
            }
        } else if (row == ROW_ENGINE) {
            // 用户手选内核 → 锁定，不再自动降级，否则设置等于没设
            PlaybackEngine e = PlaybackEngine.of(value);
            if (!PlayerManager.available(e)) {
                osd.toast(e.label + " 内核在本机不可用（ABI 不匹配）");
                return;
            }
            resetNoVideoStage();
            markEngineAttempt(e);
            rebuildEngine(e, true);
            playChannel(playingChannel);
        } else if (row == ROW_HEADERS) {
            openHeaderEditor();
        } else if (row == ROW_OUTPUT) {
            applyOutputMode();
            playChannel(playingChannel);
        } else if (row == ROW_SPEED) {
            updateSpeedLabel();
            refreshOsdTags();
        } else if (row == ROW_SOURCE) {
            switchSource(value == 0 ? -1 : +1);
        } else if (row == ROW_CLOCK) {
            // 立刻生效：面板当前可见就直接显示；不可见时按新设置决定
            if (clock != null) {
                if (value == 1) {
                    clock.setVisibility(View.VISIBLE);
                    clock.animate().alpha(1f).setDuration(120).start();
                } else if (!panelVisible) {
                    clock.animate().alpha(0f).setDuration(120)
                            .withEndAction(new Runnable() {
                                @Override public void run() { clock.setVisibility(View.GONE); }
                            }).start();
                }
            }
        } else if (row == ROW_RATIO) {
            applyAspectRatio(value);
        }
    }

    /**
     * 画面比例。直接改 SurfaceView 的 LayoutParams 尺寸，
     * 靠 VideoFrameReleaseControl 的居中缩放把画面塞进去 —— 不碰解码器。
     * 对 4:3 老频道的用户来说，这是「画面被拉扁」的唯一解法。
     */
    private void applyAspectRatio(int mode) {
        View out = outputView();
        if (out == null || out.getLayoutParams() == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) out.getLayoutParams();
        if (mode == 0) {                       // 自动适应：铺满全屏
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            float ratio;
            switch (mode) {
                case 1: ratio = 16f / 9f; break;
                case 2: ratio = 4f / 3f; break;
                default: ratio = 0f; break;
            }
            if (mode == 3 || ratio <= 0f) {     // 原始：保持全屏，交给内核原始尺寸
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            } else {
                int sw = getResources().getDisplayMetrics().widthPixels;
                int sh = getResources().getDisplayMetrics().heightPixels;
                int w = sw, h = (int) (sw / ratio);
                if (h > sh) { h = sh; w = (int) (sh * ratio); }
                lp.width = w;
                lp.height = h;
            }
        }
        lp.gravity = Gravity.CENTER;
        out.setLayoutParams(lp);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                SurfaceHolder h = surfaceView.getHolder();
                if (h.getSurface() != null && h.getSurface().isValid()) {
                    player.setSurface(h.getSurface());   // 尺寸变了必须重绑
                }
            }
        }, 60);
    }

    @Override
    public void onZoneChanged(FocusEngine.Zone zone) {
        updateNavFocus(focus.navIndex());
        updateChannelFocus(focus.chanIndex());
    }

    // ================= 触屏 / 手势（手机、平板、带触屏的盒子）=================

    private float downX, downY;
    private boolean moved;
    private final int slop = (int) (24 * Theme.scale());

    /**
     * 触摸策略（重要）：
     * <b>不要重写 dispatchTouchEvent</b> —— 一旦在 ACTION_DOWN 返回 false，
     * 后续的 MOVE/UP 就再也收不到，ScrollView 完全无法滚动。
     *
     * 正确做法：完全不拦截，让事件正常下发；点击用各控件自己的
     * OnClickListener 处理（自绘 View 默认不消费触摸，加 listener 即可）。
     */
    private void bindClickListeners() {
        // 每个自绘 View 加点击回调后，才会消费 DOWN/UP 并能正常参与滚动判定
    }

    /**
     * 视频区（三栏右侧那块黑）被点击。
     *
     * <p>三态各管一边：
     * <ul>
     *   <li>抽屉态 → 收起抽屉，回到「分类 + 频道」；</li>
     *   <li>常态   → 收起整套面板，进纯播放；</li>
     *   <li>纯播放 → 这层已经被 gestureLayer 盖住，走不到这里。</li>
     * </ul>
     */
    /**
     * 纯播放态下点画面的统一处理。
     *
     * <p>规则（就是用户要的那套，别再自作聪明搞两段式）：
     * <ul>
     *   <li>信息条正在显示 → 先把它收掉；点在左半边就顺势把频道列表打开；</li>
     *   <li>信息条没显示 → 弹出来（看进度/编码/网速）。</li>
     * </ul>
     *
     * <p>以前写成"先 reshow() 再 showPanel()"，而 reshow() 只要有过频道就恒为 true，
     * 于是频道列表永远打不开，OSD 反复弹 —— 这就是"一直显示这个、点不开列表"。
     */
    private void onVideoTapped() {
        boolean left = tapX < Theme.px(Theme.NAV_W + Theme.CHAN_W + Theme.RAIL_W);
        if (osd != null && osd.isVisible()) {
            osd.hideNow();
            if (left) showPanel();     // 点左边 → 收 OSD 并打开频道列表
            return;
        }
        if (osd != null) osd.reshow();
    }

    /**
     * 视频区点击层的实际范围 = 屏幕去掉左侧三栏。
     *
     * <p>抽屉展开时节目单更宽，所以要按当前状态算，不能写死。
     * 注意不要把这一层做成铺满全屏：那样它会盖在三栏上，
     * 列表完全点不动 —— 这个坑已经踩过一次。
     */
    private void layoutVideoTap() {
        if (videoTap == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) videoTap.getLayoutParams();
        int left;
        if (epgPanelOpen) {
            left = Theme.px(Theme.CHAN_W + 8 + Theme.EPG_W);
        } else {
            left = Theme.px(Theme.NAV_W + 1 + Theme.CHAN_W + 1 + Theme.RAIL_W);
        }
        lp.leftMargin = left;
        lp.width = Math.max(0, getResources().getDisplayMetrics().widthPixels - left);
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        videoTap.setLayoutParams(lp);
        videoTap.setVisibility(panelVisible ? View.VISIBLE : View.GONE);
    }

    private void onVideoAreaTapped(float x, float y) {
        // 点右侧视频区就是"全都收起来"：面板 + 信息条
        if (osd != null) osd.hideNow();
        if (epgPanelOpen) { closeEpgPanel(); return; }
        if (panelVisible) { enterFullPlayer(); }
    }

    private void moveChannel(int step) {
        clearRestorePending();
        resetNoVideoStage();
        int n = focus.chanIndex() + step;
        if (n < 0) n = 0;
        if (n > channels.size() - 1) n = channels.size() - 1;
        focusOnChannel(n);
        playChannel(n);
    }

    /** 手动把焦点同步到指定频道（触屏用，绕过 FocusEngine 的算术）。 */
    private void focusOnChannel(int index) {
        // 触屏操作时焦点可能停在分类列，先切回频道列，蓝框才会出现
        if (focus.zone() != FocusEngine.Zone.CHAN) {
            focus.setZone(FocusEngine.Zone.CHAN);
        }
        int cur = focus.chanIndex();
        while (cur < index) { focus.onKey(KeyEvent.KEYCODE_DPAD_DOWN); cur++; }
        while (cur > index) { focus.onKey(KeyEvent.KEYCODE_DPAD_UP); cur--; }
        updateNavFocus(focus.navIndex());
        updateChannelFocus(index);
    }

    // 旧的 handleSettingsTouch 已删除。
    // 它靠坐标算「点到了哪一行哪一个选项」，而且挂在 dialogRoot 的
    // OnTouchListener 上、无条件 return true —— 等于把所有触摸事件
    // 在到达子 View 之前全吃掉，Tab / ✕ / 选项全部点不动。
    // 现在每个控件自己挂监听，坐标计算这套整个不需要了。

    // ================= 长按快进 =================

    /** 按住时长 → 快进速率（毫秒每秒）。越按越快，跟主流播放器一致。 */
    private float speedFactor(long heldMs) {
        if (heldMs < 1500) return 8f;      // 1:8
        if (heldMs < 4000) return 30f;     // 1:30
        if (heldMs < 8000) return 60f;     // 1:60
        return 120f;                       // 1:120
    }

    /**
     * 开始长按快进。
     * 直播流（canSeek=false）直接不启动 —— 直播没有可跳转的时间轴，
     * 强行 seek 只会跳回直播点，那就不是「快进」了。
     */
    private boolean startSeek(boolean forward) {
        if (player == null || !player.canSeek()) {
            osd.toast("直播流不支持快进");
            return false;
        }
        if (curDuration <= 0) return false;

        seeking = true;
        seekBaseMs = Math.max(0, curPosition);
        seekStartWall = SystemClock.uptimeMillis();
        seekTargetMs = seekBaseMs;
        seekForward = forward;
        seekOverlay.update(seekBaseMs, seekTargetMs, curDuration);
        seekOverlay.show();
        seekHandler.removeCallbacks(seekTick);
        seekHandler.post(seekTick);
        return true;
    }

    private boolean seekForward = true;

    private void updateSeekTarget() {
        long held = SystemClock.uptimeMillis() - seekStartWall;
        long delta = (long) (held / 1000f * speedFactor(held));
        long t = seekForward ? seekBaseMs + delta : seekBaseMs - delta;
        if (t < 0) t = 0;
        if (t > curDuration) t = curDuration;
        seekTargetMs = t;
        seekOverlay.update(seekBaseMs, t, curDuration);
    }

    /** 松手：真正 seek，并让预览多留 400ms 再淡出。 */
    private void endSeek() {
        if (!seeking) return;
        seeking = false;
        seekHandler.removeCallbacks(seekTick);
        if (player != null && player.canSeek()) {
            player.seekTo(seekTargetMs);
        }
        seekHandler.postDelayed(new Runnable() {
            @Override public void run() { if (seekOverlay != null) seekOverlay.hide(); }
        }, 450);
    }

    // ================= 按键 =================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (dialogRoot != null && dialogRoot.getVisibility() == View.VISIBLE) {
            return handleSettingsKey(keyCode, event);
        }

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            openSettings();
            return true;
        }

        // 长按 OK 键（或 INFO 键）弹播放诊断，方便现场看"有声音没画面"
        if (keyCode == KeyEvent.KEYCODE_INFO
                || (keyCode == KeyEvent.KEYCODE_MENU && event.isLongPress())) {
            showDiagnostics();
            return true;
        }

        if (!panelVisible) {
            // 面板收起时：上下键唤出面板；左右键留给下面的快进/快退。
            // 原来这里写的是「任意方向键先唤出面板」，把左右键也吃了，
            // 导致后面那段长按快进永远不会执行。
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                showPanel();
                return true;
            }
        }

        // 抽屉态：左键 / 返回 → 收起抽屉，回到「分类 + 频道」
        if (epgPanelOpen && handleEpgKey(keyCode)) return true;

        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus.zone() == FocusEngine.Zone.CHAN) {
            showEpgPanel(focus.chanIndex());
            return true;
        }

        // 面板收起（看视频）时，左右键是快进/快退，按住可长按加速
        if (!panelVisible && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            if (event.getRepeatCount() == 0) {
                event.startTracking();          // 交给 onKeyLongPress 判定
            } else if (!seeking) {
                // 不支持长按的设备：连续按每下跳 10 秒
                if (player != null && player.canSeek()) {
                    long step = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 10_000 : -10_000;
                    player.seekTo(Math.max(0, player.positionMs() + step));
                }
            }
            return true;
        }

        if (focus.onKey(keyCode)) return true;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (focus.zone() == FocusEngine.Zone.NAV) {
                    switchCategory(focus.navIndex());
                } else {
                    playChannel(focus.chanIndex());
                    fillEpg(focus.chanIndex());
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (epgPanelOpen) {
                    closeEpgPanel();
                } else if (panelVisible) {
                    hidePanel();
                } else {
                    finish();
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (dialogRoot != null && dialogRoot.getVisibility() == View.VISIBLE) {
            return super.onKeyLongPress(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return startSeek(true);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return startSeek(false);
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (seeking && (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT)) {
            endSeek();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean handleSettingsKey(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            closeSettings();
            return true;
        }

        // Tab 条：在第一行按「上」进入，左右切页，「下」或 OK 回到列表。
        // 以前 Tab 只响应触摸，遥控器永远切不到「账户登录 / 手机助手」。
        if (tabFocus) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                currentTab = (currentTab + DemoData.TABS.length - 1) % DemoData.TABS.length;
                showTab(currentTab);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                currentTab = (currentTab + 1) % DemoData.TABS.length;
                showTab(currentTab);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                tabFocus = false;
                applyRowFocus(focus.rowIndex());
                return true;
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && focus.rowIndex() == 0) {
            tabFocus = true;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            int row = focus.rowIndex();
            onSettingCommitted(row, focus.optIndex());
            rowViews.get(row).setSelected(focus.optIndex());
            return true;
        }
        // 「切源」这一行是动作，不是选项：焦点落在这一行时，
        // 直接按 ← / → 就换线路，不用先移进选项再按 OK。
        // 原来要走「进入选项 → 选中箭头 → 确认」三步，遥控器上很难受。
        if (focus.rowIndex() == ROW_SOURCE
                && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            switchSource(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : +1);
            return true;
        }

        boolean handled = focus.onKey(keyCode);
        // ← → 落到具体选项时，给选项白框、撤掉行白框
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int row = focus.rowIndex();
            if (row >= 0 && row < rowViews.size()) {
                rowViews.get(row).setRowFocused(false);
                rowViews.get(row).setOptionFocused(focus.optIndex());
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            applyRowFocus(focus.rowIndex());
        }
        return handled || true;
    }

    private void switchCategory(int index) {
        resetNoVideoStage();
        for (int i = 0; i < navViews.size(); i++) {
            navViews.get(i).setActive(i == index);
        }
        applyCategoryChannels(index);
        if (channels.isEmpty()) osd.toast("这个分类还没有频道");
    }

    private void hidePanel() { enterFullPlayer(); }

    private void showPanel() {
        if (epgPanelOpen) closeEpgPanel();
        setPanelVisible(true);
    }

    private void animatePanel(float to) {
        navPanel.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        chanPanel.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        railView.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        scrim.animate().alpha(to * 0.25f + 0.75f).setDuration(Theme.T_PANEL).start();
    }

    private static boolean isDpad(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(speedTick);
        if (player != null) {
            player.release();
            player = null;
        }
        logos.shutdown();
    }
}
