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
    private PlaybackBackend player;
    private OsdView osd;

    // ---- 三栏 ----
    private final List<NavItemView> navViews = new ArrayList<>();
    private final List<ChannelView> chanViews = new ArrayList<>();
    private LinearLayout chanColumn;
    private View navPanel, chanPanel, railView, scrim, navDivider;
    private View chanContainer;   // 频道列的外层，抽屉展开时整体左移
    private ScrollView chanScroll;

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
    private String lastResolution, lastFps;

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
    private final int[] selections = DemoData.ROW_DEFAULT.clone();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (clock != null) clock.refresh();
            ui.postDelayed(this, 30_000);
        }
    };

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
        focus.setNavCount(categories.size());
        focus.setChannelCount(channels.size());
        bindChannels();
        updateNavFocus(1);
        updateChannelFocus(0);

        player = new Media3Backend();
        player.setCallback(new PlaybackBackend.Callback() {
            @Override public void onState(PlaybackBackend.State s) {
                if (s == PlaybackBackend.State.READY) {
                    playing = true;
                    watchdogFired = true;
                    ui.removeCallbacks(playbackWatchdog);
                }
                osd.setStatus(OsdView.stateText(s));   // READY 返回 null，会自动清空
            }
            @Override public void onFirstFrame() {
                playing = true;
                watchdogFired = true;
                ui.removeCallbacks(playbackWatchdog);
                osd.setStatus(null);
            }

            @Override public void onTimeline(long pos, long dur, long buf) {
                curPosition = pos;
                curDuration = dur;
                if (!seeking && seekOverlay != null) seekOverlay.hide();
            }

            @Override public void onError(String msg) {
                dumpPlaybackState("ERROR", msg);
                osd.setStatus("播放失败：" + msg);
                if (errorPanel != null) {
                    errorPanel.show("播放失败", "地址：" + currentPlayUrl + "\n\n" + msg);
                }
            }

            @Override public void onVideoFormat(String res, String vc, String ac, String ch, String fps) {
                if (res != null) lastResolution = res;
                if (fps != null) lastFps = fps;
                refreshOsdTags();
            }
        });

        // Surface 一就绪就开播，避免先黑屏再起播
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                player.setSurface(h.getSurface());
                playChannel(playingChannel);
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { player.setSurface(null); }
        });

        ui.post(clockTick);
        loadEpgAsync();
    }

    // ================= 布局 =================

    private void buildPlayer(FrameLayout root) {
        surfaceView = new SurfaceView(this);
        // 不要改 SurfaceView 的 PixelFormat：默认就是解码器直出的格式，
        // 设成 RGBA_8888 会强制走 GPU 合成，MediaCodec 就出不了画面。
        surfaceView.setZOrderMediaOverlay(false);
        surfaceView.setBackgroundColor(Color.BLACK);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        root.addView(surfaceView, lp);
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
        chanColumn = new LinearLayout(this);
        chanColumn.setOrientation(LinearLayout.VERTICAL);
        // 高度必须 WRAP_CONTENT，ScrollView 才知道内容比视口高、可以滚动
        chanScroll.addView(chanColumn, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        chan.addView(chanScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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

        // --- 长按唤出设置的手势层（只在中央区域生效，不吃列表触摸）---
        gestureLayer = new View(this);
        gestureLayer.setBackgroundColor(0x00000000);
        gestureLayer.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                openSettings();     // 长按播放区中央唤出设置
                return true;
            }
        });
        gestureLayer.setVisibility(View.GONE);   // 只在面板收起时启用
        FrameLayout.LayoutParams gLp = new FrameLayout.LayoutParams(
                Theme.px(760), FrameLayout.LayoutParams.MATCH_PARENT);
        gLp.leftMargin = Theme.px(Theme.NAV_W + Theme.CHAN_W + Theme.RAIL_W);
        gLp.gravity = Gravity.CENTER_VERTICAL;
        root.addView(gestureLayer, gLp);

        // --- 长按快进预览 ---
        seekOverlay = new SeekOverlayView(this);
        root.addView(seekOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- OSD ---
        osd = new OsdView(this);
        root.addView(osd, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        channels = playlist.all;
    }

    /** 后台拉源，成功后重建界面。失败就继续用缓存/示范。 */
    private void fetchSourceAsync(final String url) {
        new Thread(new Runnable() {
            @Override public void run() {
                String body = null;
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "TVUI/1.0");
                    conn.setInstanceFollowRedirects(true);
                    java.io.InputStream in = conn.getInputStream();
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    in.close();
                    body = bos.toString("UTF-8");
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final String result = body;
                if (result == null || result.trim().isEmpty()) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (errorPanel != null) {
                                errorPanel.show("直播源拉取失败",
                                        "地址：" + url + "\n\n请检查网络或换一个源地址。");
                            }
                        }
                    });
                    return;
                }
                SourceStore.saveBody(MainActivity.this, result);
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

    private void bindChannels() {
        chanColumn.removeAllViews();
        chanViews.clear();
        for (int i = 0; i < channels.size(); i++) {
            final PlaylistParser.Channel ch = channels.get(i);
            final ChannelView v = new ChannelView(this);
            v.setNumber(ch.number);
            v.setName(ch.name);
            v.setSub("");                       // 没接 EPG 就不画副行，不编造假节目
            v.setBadge(null);

            final int chanIndex = i;
            v.setClickable(true);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { onChannelClicked(chanIndex); }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(Theme.CHAN_ITEM_H));
            p.leftMargin = Theme.px(14);
            p.rightMargin = Theme.px(14);
            p.topMargin = Theme.px(i == 0 ? 0 : Theme.CHAN_ITEM_GAP / 2);
            p.bottomMargin = Theme.px(Theme.CHAN_ITEM_GAP / 2);
            chanColumn.addView(v, p);
            chanViews.add(v);
        }
        focus.setChannelCount(channels.size());
        refreshChannelSubs();
        // 台标改成按可见范围惰性加载，绝不在绑定阶段全量入队
        attachScrollLoader();
        ui.post(new Runnable() {
            @Override public void run() { loadVisibleLogos(); }
        });
    }

    /** 列表滚动时补加载新露出行的台标。只挂一次监听。 */
    private void attachScrollLoader() {
        if (chanScroll == null) return;
        if (chanScroll.getTag() != null) return;
        chanScroll.setTag(Boolean.TRUE);
        chanScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int osx, int osy) {
                // 滚完再拉，避免滚动中频繁触发
                ui.removeCallbacks(scrollLogoTask);
                ui.postDelayed(scrollLogoTask, 80);
            }
        });
    }

    private final Runnable scrollLogoTask = new Runnable() {
        @Override public void run() { loadVisibleLogos(); }
    };

    /** 只给「当前屏幕能看到 + 前后各 2 行」的频道请求台标。 */
    private void loadVisibleLogos() {
        if (chanViews.isEmpty()) return;
        ViewGroup parent = (ViewGroup) chanColumn.getParent();
        if (!(parent instanceof ScrollView)) {
            requestLogos(0, chanViews.size());
            return;
        }
        ScrollView sv = (ScrollView) parent;
        int scrollY = sv.getScrollY();
        int viewH = sv.getHeight();
        if (viewH <= 0) { viewH = getWindow().getDecorView().getHeight(); }
        int itemH = Theme.px(Theme.CHAN_ITEM_H + Theme.CHAN_ITEM_GAP);
        int listTop = Theme.px(26) + Theme.px(50);

        int first = Math.max(0, (scrollY + listTop) / itemH - 3);
        // 多预取一屏，滚动时不会出现「露出来还没图」的空档
        int last = Math.min(chanViews.size() - 1, (scrollY + viewH) / itemH + 12);
        requestLogos(first, last + 1);
    }

    private void requestLogos(int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(to, channels.size()); i++) {
            final PlaylistParser.Channel ch = channels.get(i);
            if (ch.logo == null || ch.logo.isEmpty()) continue;
            final ChannelView v = chanViews.get(i);
            if (v.hasLogo()) continue;

            Bitmap cached = logos.peek(ch.logo);
            if (cached != null) {
                v.setLogo(cached);
                continue;
            }
            logos.load(ch.logo, new LogoLoader.Callback() {
                @Override public void onLogo(String url, final Bitmap bmp) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (bmp != null && !bmp.isRecycled()) {
                                v.setLogo(bmp);
                                v.invalidate();
                            }
                        }
                    });
                }
            });
        }
    }

    /** 点分类：切分类，焦点交给频道列。 */
    private void onCategoryClicked(int index) {
        switchCategory(index);
        focus.setZone(FocusEngine.Zone.CHAN);
        focus.setNavIndexSilently(index);
        updateNavFocus(index);
        updateChannelFocus(0);
    }

    /** 点频道：切台 + 选中框 + 刷新节目单。 */
    private void onChannelClicked(int index) {
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

    private void updateChannelFocus(int index) {
        for (int i = 0; i < chanViews.size(); i++) {
            ChannelView v = chanViews.get(i);
            v.setFocused(i == index, true);
        }
        scrollChannelIntoView(index);
    }

    /** 焦点保持可见：只在越界时动，用 translationY，不重排、不 smoothScroll。 */
    private void scrollChannelIntoView(int index) {
        if (index < 0 || index >= chanViews.size() || chanColumn.getParent() == null) return;
        View item = chanViews.get(index);
        int itemTop = item.getTop(), itemBottom = item.getBottom();
        ScrollView sv = findScrollParent(item);
        if (sv == null) return;
        int scrollY = sv.getScrollY();
        int viewH = sv.getHeight();
        int target = -1;
        if (itemTop - scrollY < Theme.px(80)) target = Math.max(0, itemTop - Theme.px(80));
        else if (itemBottom - scrollY > viewH - Theme.px(40)) target = itemBottom - viewH + Theme.px(40);
        if (target >= 0) sv.smoothScrollTo(0, target);
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

    private void playChannel(int index) {
        if (index < 0 || index >= channels.size()) return;
        playingChannel = index;
        PlaylistParser.Channel ch = channels.get(index);
        String no = String.valueOf(ch.number);
        while (no.length() < 4) no = "0" + no;
        lastResolution = null;
        lastFps = null;
        lineTotal = ch.sources.size();
        osd.showChannel(no, ch.name, ch.group);
        osd.setClock(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date()));
        refreshOsdTags();
        refreshPlayInfo();
        if (ch.sources.isEmpty()) return;
        PlaylistParser.Source src = ch.sources.get(currentLine);
        if (currentLine >= ch.sources.size()) { src = ch.sources.get(0); currentLine = 0; }
        currentPlayUrl = PlaylistParser.cleanUrl(src.url);
        armWatchdog();
        if (player instanceof Media3Backend) {
            ((Media3Backend) player).play(getApplicationContext(),
                    PlaylistParser.cleanUrl(src.url), src.headers, PlaybackBackend.Mode.LIVE);
        } else {
            player.play(getApplicationContext(), PlaylistParser.cleanUrl(src.url), PlaybackBackend.Mode.LIVE);
        }
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
                    playChannel(playingChannel);
                } else {
                    osd.toast("该源无响应");
                }
            }
        }
    };
    /** 设置行下标（与 DemoData.ROW_TITLES 顺序一致） */
    private static final int ROW_TIMEOUT_SWITCH = 8;
    private static final int ROW_TIMEOUT_SECONDS = 9;

    private boolean autoSwitchSource() { return selections[ROW_TIMEOUT_SWITCH] == 0; }

    private long switchTimeoutMs() {
        switch (selections[ROW_TIMEOUT_SECONDS]) {
            case 0: return 5000;
            case 2: return 12000;
            case 3: return 20000;
            default: return 8000;
        }
    }

    private boolean playing = false;
    private boolean watchdogFired = false;

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
        if (player instanceof Media3Backend) {
            sb.append("状态: ").append(((Media3Backend) player).diagnostics()).append('\n');
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

    private void refreshOsdTags() {
        String src = lineTotal > 1 ? ("源 " + (currentLine + 1) + "/" + lineTotal) : "源 1/1";
        osd.setTags(src, null, lastResolution, null, null, null, lastFps);
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
    private void refreshChannelSubs() {
        if (epg == null) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < chanViews.size() && i < channels.size(); i++) {
            ChannelView v = chanViews.get(i);
            String id = epgIdOf.get(i);
            if (id == null) { v.setSub(""); continue; }
            EpgParser.Program live = EpgParser.live(epg.of(id), now);
            if (live == null) { v.setSub(""); continue; }
            v.setSub("正在播放 · " + EpgParser.hm(live.startMs) + " " + live.title);
            v.setBadge(hasReplay(i) ? "可回看" : null);
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
    }

    /** 面板不可见时把子 View 全部设为不可点击，双保险。 */
    private void setPanelsInteractive(boolean enabled) {
        for (NavItemView v : navViews) v.setClickable(enabled);
        for (ChannelView v : chanViews) v.setClickable(enabled);
        if (chanContainer != null) chanContainer.setClickable(enabled);
    }

    private boolean epgPanelOpen = false;
    /** 当前节目单窗口里每一条对应的原始节目，下标与 EpgPanelView 的行一一对应。 */
    private final java.util.ArrayList<EpgParser.Program> epgWindow = new java.util.ArrayList<>();

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
        player.play(getApplicationContext(), url, PlaybackBackend.Mode.TIMESHIFT);
        closeEpgPanel();
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
        dialogRoot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { }   // 吞掉点击
        });

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.DLG_BG);

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

        for (int i = 0; i < DemoData.TABS.length; i++) {
            TextView tab = new TextView(this);
            tab.setText(DemoData.TABS[i]);
            tab.setTextColor(i == 1 ? Theme.TEXT : 0xFFD6D6D8);
            tab.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(21));
            tab.setGravity(Gravity.CENTER);
            tab.setBackgroundColor(i == 1 ? 0x0FFFFFFF : Color.TRANSPARENT);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Theme.px(52));
            tp.rightMargin = Theme.px(10);
            bar.addView(tab, tp);
        }

        TextView ver = new TextView(this);
        ver.setText("v1.0.0");
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
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Theme.px(44), Theme.px(44));
        bar.addView(close, cp);
        card.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(76)));

        // 行
        rowColumn = new LinearLayout(this);
        rowColumn.setOrientation(LinearLayout.VERTICAL);
        rowColumn.setPadding(Theme.px(24), Theme.px(18), Theme.px(24), Theme.px(10));
        for (int i = 0; i < DemoData.ROW_TITLES.length; i++) {
            SettingRowView row = new SettingRowView(this);
            row.bind(DemoData.ROW_TITLES[i][0], DemoData.ROW_TITLES[i][1],
                    DemoData.ROW_OPTIONS[i], selections[i]);
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
        // 限高：卡片总高不能超出屏幕，超出部分滚动
        int maxRowsH = (int) (getResources().getDisplayMetrics().heightPixels * 0.66f);
        card.addView(rowScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxRowsH,
                Theme.px(10 * (82 + 12)) + Theme.px(28))));

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

    private void openSettings() {
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
        if (row == ROW_TIMEOUT_SWITCH || row == ROW_TIMEOUT_SECONDS) {
            armWatchdog();     // 立即按新策略重新计时
        }
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

    /** 按屏幕坐标判断点到了哪一栏哪一项。 */
    private boolean hitTest(float x, float y) {
        int navW = Theme.px(Theme.NAV_W);
        int chanW = chanWidth();

        // 抽屉态：左边整块是频道列（已左移到 0），右边是节目单
        if (epgPanelOpen) {
            // 最左侧一条热区：退出抽屉，恢复分类栏
            if (x < Theme.px(48)) {
                closeEpgPanel();
                return true;
            }
            if (x < chanW) {
                hitChannelColumn(x, y, 0);      // 频道列左移到 0 起
                return true;
            }
            int epgLeft = chanW + Theme.px(8);
            if (x >= epgLeft) {
                int idx = epgPanel.itemAt(y);
                if (idx >= 0) playReplay(playingChannel, idx);
            }
            return true;
        }

        // --- 左侧分类列 ---
        if (x < navW) {
            int listTop = Theme.px(34 + 76);              // 顶部内边距 + 标题高度
            int rowH = Theme.px(Theme.NAV_ITEM_H + 2);
            ScrollView navScroll = findScrollParent(navViews.isEmpty() ? navPanel : navViews.get(0));
            int scrollY = navScroll == null ? 0 : navScroll.getScrollY();
            int idx = (int) ((y + scrollY - listTop) / rowH);
            if (idx >= 0 && idx < navViews.size()) {
                switchCategory(idx);
                // 分类点完把焦点交给频道列，否则看不到选中框
                focus.setZone(FocusEngine.Zone.CHAN);
                focus.setNavIndexSilently(idx);
                updateNavFocus(idx);
                updateChannelFocus(0);
            }
            return true;
        }

        // --- 中间频道列 ---
        if (x < navW + chanW) {
            hitChannelColumn(x, y, navW);
            return true;
        }

        // --- 竖轨：点它展开抽屉 ---
        if (x <= navW + chanW + Theme.px(Theme.RAIL_W)) {
            showEpgPanel(playingChannel >= 0 ? playingChannel : focus.chanIndex());
            return true;
        }
        return false;
    }

    /** 频道列命中。offsetX = 频道列左边界在屏幕上的 x。 */
    private void hitChannelColumn(float x, float y, int offsetX) {
        ScrollView sv = findScrollParent(chanViews.isEmpty() ? chanColumn : chanViews.get(0));
        int scrollY = sv == null ? 0 : sv.getScrollY();
        int itemH = Theme.px(Theme.CHAN_ITEM_H + Theme.CHAN_ITEM_GAP);
        int listTop = Theme.px(26 + 50);
        int idx = (int) ((y + scrollY - listTop) / itemH);
        if (idx >= 0 && idx < channels.size()) {
            playChannel(idx);
            focusOnChannel(idx);
            fillEpg(idx);
        }
    }

    private void moveChannel(int step) {
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

    /** 设置弹窗里的触摸：点选项选中、点卡片外面关闭。 */
    private boolean handleSettingsTouch(android.view.MotionEvent ev) {
        if (ev.getActionMasked() != android.view.MotionEvent.ACTION_UP) {
            return true;   // 按下 / 移动一律吃掉，交给 UP 处理
        }
        float x = ev.getX(), y = ev.getY();
        int screenW = getWindow().getDecorView().getWidth();
        int cardW = Theme.px(Theme.DLG_W);
        int cardLeft = (screenW - cardW) / 2;
        int cardTop = Theme.px(Theme.DLG_TOP);

        // 点弹窗外面 → 关闭
        if (x < cardLeft || x > cardLeft + cardW) {
            closeSettings();
            return true;
        }

        int rowTop = cardTop + Theme.px(76 + 18);
        int rowH = Theme.px(82 + 12);
        int idx = (int) ((y - rowTop) / rowH);
        if (idx >= 0 && idx < rowViews.size()) {
            int innerY = (int) ((y - rowTop) - idx * rowH);
            if (innerY >= 0 && innerY <= Theme.px(82)) {
                int localX = (int) (x - cardLeft - Theme.px(24));
                int opt = rowViews.get(idx).segment().indexAt(localX);
                while (focus.rowIndex() < idx) focus.onKey(KeyEvent.KEYCODE_DPAD_DOWN);
                while (focus.rowIndex() > idx) focus.onKey(KeyEvent.KEYCODE_DPAD_UP);
                applyRowFocus(idx);
                if (opt >= 0) {
                    selections[idx] = opt;
                    rowViews.get(idx).setSelected(opt);
                    rowViews.get(idx).setRowFocused(false);
                    rowViews.get(idx).setOptionFocused(opt);
                }
            }
        }
        return true;
    }

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
            // 面板收起时，任意方向键先唤出面板
            if (isDpad(keyCode)) {
                showPanel();
                return true;
            }
        }

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
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            int row = focus.rowIndex();
            onSettingCommitted(row, focus.optIndex());
            rowViews.get(row).setSelected(focus.optIndex());
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
        for (int i = 0; i < navViews.size(); i++) {
            navViews.get(i).setActive(i == index);
        }
        String name = categories.get(index).name;
        List<PlaylistParser.Channel> list = "全部".equals(name)
                ? playlist.all
                : playlist.byGroup.get(name);
        channels = list == null ? new ArrayList<PlaylistParser.Channel>() : list;
        currentLine = 0;
        bindChannels();
        focus.resetChannelFocus();
        updateChannelFocus(0);
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
        if (player != null) {
            player.release();
            player = null;
        }
        logos.shutdown();
    }
}
