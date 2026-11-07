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
import com.mintv.tv.ui.player.PlaybackBackend;
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
    /**
     * 组播接收锁。
     *
     * <p>Android 默认把组播包丢掉 —— 不申请这个锁，{@code udp://239.x.x.x}
     * 一个字节都收不到，VLC 那边表现为永远"缓冲中"。这是和内网组播源
     * 打交道时的头号坑，酷9 那类播放器同样是这么处理的。
     */
    private android.net.wifi.WifiManager.MulticastLock multicastLock;

    /** 根容器，弹层往这里加。 */
    private android.widget.FrameLayout rootView;
    /** 主题调色板（打开时非 null）。 */
    private com.mintv.tv.ui.widget.ThemePickerView themePicker;
    private PlaybackBackend.Callback playerCallback;
    private PlaybackBackend player;
    private OsdView osd;

    // ---- 三栏 ----
    private final List<NavItemView> navViews = new ArrayList<>();
    /** 最左侧的订阅列。开关关闭时为 null。 */
    private LinearLayout subColumn;
    /**
     * 开屏是否已经放过。
     *
     * <p>静态的 —— 一个进程只放一次。换主题会 recreate()，
     * onCreate 重跑，如果这里不做判断，用户会看到"怎么又开机了"。
     * 换主题是主动操作，不该被当成冷启动。
     */
    /** 「关于」页里的检测更新按钮。 */
    private TextView updateBtn;

    private static boolean splashDone = false;

    /** 四列的水平容器。加/删列只改这里。 */
    private LinearLayout colsRow;

    /** 订阅列右侧那条分隔线。开关「显示订阅列」时要跟着一起加/删。 */
    private View subLineView;

    /**
     * navViews 里前 subItemCount 个是「订阅」，后面才是「分类」。
     *
     * <p>两个层级共用同一个列表，是为了不改焦点引擎 —— navIndex 仍然
     * 是 0..n-1 的连续下标，只是语义分两段。改动集中在
     * rebuildCategoryColumn（构建）和 onCategoryClicked（分发）两处。
     */
    private int subItemCount = 0;
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
    /** 分类列表。同样要空 List 初始值，理由见 channels。 */
    private List<DemoData.Category> categories = new java.util.ArrayList<>();
    private PlaylistParser.Playlist playlist;

    /**
     * 每个订阅解析后的结果缓存。
     *
     * <p>有了它，切换订阅不用重新拉网络 —— 直接换内存里的列表，
     * 几百毫秒内完成。远程源尤其明显：以前切一次要等几秒空白。
     *
     * <p>失效靠手动刷新（长按订阅 → 重新拉取）或重启 App。
     * 不做 TTL：源本来就是长期不变的，过期自动重拉反而会让用户
     * 在关键时刻等待网络。
     */
    private final java.util.Map<String, PlaylistParser.Playlist> sourceCache =
            new java.util.HashMap<>();
    private final java.util.Map<String, java.util.List<PlaylistParser.Channel>> channelCache =
            new java.util.HashMap<>();
    /**
     * 频道列表。
     *
     * <p><b>初始必须是空 List 而不是 null</b>：loadPlaylist 现在跑在后台线程，
     * onCreate 里的 buildOverlay 会在数据到位之前跑 —— null 的话直接 NPE
     * 闪退（buildOverlay:1161 那次就是这么崩的）。
     */
    private List<PlaylistParser.Channel> channels = new java.util.ArrayList<>();
    private final LogoLoader logos = new LogoLoader();
    private boolean panelVisible = true;
    private int playingChannel = 0;
    /** 最近一次 NAT 检测结果，启动 P2P 时传给引擎。 */
    private com.mintv.tv.data.NetCheck.Result lastNatResult;
    private int currentLine = 0;
    private int lineTotal = 1;
    private String lastResolution, lastFps, lastVCodec, lastACodec, lastBitrate;
    private String lastChannels;
    // ---- 数字键换台 ----
    private final StringBuilder numBuf = new StringBuilder();
    private final Runnable numCommit = new Runnable() {
        @Override public void run() { commitChannelNumber(); }
    };

    // ---- 上一频道 ----
    private int lastChannelNum = -1;

    // 当前节目信息（给 OSD 和手机端共用，避免两套计算）
    private String epgNowText;
    private String epgNextText;
    private float epgProgress = -1f;

    // ---- 搜索 ----
    private String searchQuery = "";

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

    // ---- 手机助手（HTTP 服务 + 二维码）----
    private com.mintv.tv.data.RemoteServer remote;
    private int remotePort = -1;
    private LinearLayout helperPage;
    private View infoPageWrap;

    /**
     * 系统 AlertDialog 正在显示。
     *
     * <p>弹窗是独立的窗口，按键本该由它处理；但我们的 onKeyDown 会把所有按键
     * 吃掉（设置页/主界面的方向键逻辑），结果弹窗里的「知道了 / 去设置 / 保存」
     * 遥控器永远选不到。这个标记用来在弹窗期间把按键交还给系统。
     */
    private boolean sysDialogOpen = false;
    /** 本次换台已经降级过一次（防止 Exo↔VLC 来回切）。 */
    /** MENU 按下但还没抬手：用于区分短按（设置）与长按（诊断）。 */
    private boolean menuPressed = false;

    // 「关于 / 手机助手」页里的可聚焦控件（设置弹窗的按键只有这一条通道，
    // 这些页面不在 rowColumn 里，必须单独维护一份列表，否则遥控器碰不到）
    private final java.util.ArrayList<View> pageFocus = new java.util.ArrayList<>();
    private int pageFocusIdx = -1;
    private android.widget.ImageView qrView;
    private TextView helperAddr;
    /** 上次扫 sources/ 的诊断串，显示在订阅页上方便定位问题。 */
    private String lastScanDiag = "";
    private TextView helperState;
    private LinearLayout helperSubs;
    private TextView infoPageText;
    /** 关于页的免责声明块，独立成形，不混在正文里。 */
    private LinearLayout disclaimerBox;
    /** 当前设置页的 Tab：0 登录 / 1 设置 / 2 订阅 / 3 关于 */
    private int currentTab = 1;
    /** 焦点是否在顶部 Tab 条上。第一行按「上」进入，左右切页，下回列表。 */
    private boolean tabFocus = false;
    /** Tab 下标：0 登录 / 1 设置 / 2 订阅 / 3 关于 */
    private static final int TAB_SETTINGS = 1;
    private static final int TAB_HELPER   = 2;
    private static final int TAB_ABOUT    = 3;
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
        // 直接读最后一列的实际右边界，不用常量累加。
        //
        // 常量累加的问题是：加一列要改这里，分辨率/缩放不同时算出来的
        // 值还可能和实际布局差一截。读 getRight() 则永远准确，
        // 而且加列不用管。
        final View last = epgPanelOpen ? epgScroll : railView;
        if (!panelVisible || last == null) {
            osd.setLeftInset(0);
        } else {
            osd.setLeftInset(Math.max(0, last.getRight()));
            // 布局还没完成时 getRight() 是 0，下一帧再校正一次
            last.post(new Runnable() {
                @Override public void run() {
                    if (osd != null && panelVisible) {
                        osd.setLeftInset(Math.max(0, last.getRight()));
                    }
                }
            });
        }

        // 台号要对齐时钟中心，这里直接量时钟的真实屏幕位置，不靠公式猜
        if (clock != null && clock.getWidth() > 0) {
            int[] loc = new int[2];
            clock.getLocationOnScreen(loc);
            osd.setNumberAnchorX(loc[0] + clock.getWidth() / 2f);
        }
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
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));

        // 用真实屏幕像素的长边做基准（与当前方向无关）。
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int base = Math.max(dm.widthPixels, dm.heightPixels);
        android.util.Log.i("MinTV", "screen realMetrics=" + dm.widthPixels + "x" + dm.heightPixels + " base=" + base);
        Theme.init(base);
        long __tselections = System.currentTimeMillis();
        loadSelections();
        android.util.Log.w("Startup", "selections " + (System.currentTimeMillis() - __tselections) + "ms");
        // 存档里源力加速是开启状态时，重启后必须重测一次。
        //
        // lastNatResult 是内存变量，重启即 null；而触发检测的只有
        // applyP2pBoost（用户点开关那一下）。所以"开着的情况下杀后台
        // 再进来"，副标题会永远停在「正在检测网络…」—— 没有任何代码
        // 去重新跑 NetCheck。
        //
        // 订阅静默刷新：后台定时拉，不打断播放。
        startAutoRefresh();
        // 延迟 1.5 秒，等 osd/播放器这些初始化完，避免检测线程跟它们抢资源。
        if (selections[ROW_P2P_BOOST] == 1) {
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    if (selections[ROW_P2P_BOOST] != 1) return;
                    netCheckLoop.run();
                }
            }, 1500);
        }
        // HTTP 服务在 UI 之前就起：手机端要能在任何时候连进来，
        // 不能等到用户去点"手机助手"那一页才有服务。
        startRemoteServer();
        long __thiddenCats = System.currentTimeMillis();
        loadHiddenCats();
        android.util.Log.w("Startup", "hiddenCats " + (System.currentTimeMillis() - __thiddenCats) + "ms");
        // 底色固定深色，强调色从设置里读（默认 0 = 盒子蓝）
        Theme.applyAccent(getSharedPreferences(PREF, MODE_PRIVATE).getInt(KEY_ACCENT, 0));

        // 崩溃页点了「重新进入」：从最保守的状态开始，不恢复现场。
        // 崩溃后内存里留着坏的播放器句柄/Surface 绑定，直接接着用会二次崩溃。
        boolean cleanStart = getSharedPreferences(PREF, MODE_PRIVATE)
                .getBoolean("restart_clean", false);
        if (cleanStart) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .remove("restart_clean").apply();
            // 崩溃很可能就是这份源解析时炸的，缓存必须一起清掉，
            // 否则重启后又读同一份内容、再崩一次 —— 变成死循环。
            SourceStore.saveBody(this, "");
            SourceStore.clearBad(this);
            android.util.Log.i("MinTV", "崩溃后重启：跳过现场恢复并清空源缓存");
        }

        purgeLegacyDefaultSub();

        errorPanel = ErrorPanel.install(this);
        CatchupProbe.init(this);
        long __tplaylist = System.currentTimeMillis();
        loadPlaylist();
        android.util.Log.w("Startup", "playlist " + (System.currentTimeMillis() - __tplaylist) + "ms");

        FrameLayout root = new FrameLayout(this);
        this.rootView = root;
        // 统一通知条：顶部居中，全应用只有这一个提示入口
        com.mintv.tv.ui.widget.NotifyBar.attach(root);
        // 底色必须跟随主题：这是屏幕最大的面积，
        // 写死黑色的话换主题看起来"完全没变"。
        root.setBackgroundColor(Theme.BG);

        buildPlayer(root);
        buildOverlay(root);
        // buildOverlay 里才创建 subColumn，而 loadPlaylist 在它之前就跑了 ——
        // 那次 fillSubColumn 撞上 subColumn==null 直接返回，
        // 导致订阅列只剩标题、列表永远是空的。这里补一次。
        fillSubColumn();

        // 扫一遍存储目录的 sources/，把里面的 m3u/txt 自动加成订阅。
        // 延迟一点：loadPlaylist 刚跑完，别和它抢
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isFinishing()) loadLocalSources();
            }
        }, 1200);
        buildDialog(root);
        // 开屏：极简一屏，App 名 + 版本，1200ms 后淡出。
        // 作用不是"好看"，是把"正在加载列表/EPG"这段不可避免的等待遮住，
        // 否则用户看到的是空界面闪一下再出内容。
        //
        // 只在进程第一次创建时放。换主题等场景会 recreate()、onCreate 重跑，
        // 那种情况再放一遍就是"怎么又开机了"。
        final boolean showSplash = !splashDone;
        splashDone = true;
        if (showSplash) {
            final FrameLayout splash = new FrameLayout(this);
            splash.setBackgroundColor(Theme.BG);

            LinearLayout splashBox = new LinearLayout(this);
            splashBox.setOrientation(LinearLayout.VERTICAL);
            splashBox.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

            // 应用名从包信息读 —— 改名、换图标之后这里自动跟上，
            // 不用回头改代码。
            CharSequence appLabel = "MinTV";
            try {
                appLabel = getApplicationInfo().loadLabel(getPackageManager());
            } catch (Throwable ignored) { }

            android.widget.TextView sName = new android.widget.TextView(this);
            sName.setText(appLabel);
            sName.setGravity(android.view.Gravity.CENTER);
            sName.setTextColor(Theme.TEXT);
            sName.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(52));
            sName.setLetterSpacing(0.1f);
            // letterSpacing 在部分 ROM 上不计入测量宽度，最后一个字会被裁掉
            // （MinTV 显示成 MinT）。右边补一段内边距把余量让出来。
            sName.setPadding(0, 0, Theme.px(14), 0);
            splashBox.addView(sName);

            // 短线：用强调色，换主题色它跟着变
            View sLine = new View(this);
            sLine.setBackgroundColor(Theme.FOCUS_BLUE);
            LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                    Theme.px(56), Theme.px(2));
            lineLp.topMargin = Theme.px(30);
            lineLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            splashBox.addView(sLine, lineLp);

            android.widget.TextView sSub = new android.widget.TextView(this);
            sSub.setText("电视直播播放器");
            sSub.setGravity(android.view.Gravity.CENTER);
            sSub.setTextColor(0xFF7D8691);
            sSub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(19));
            sSub.setLetterSpacing(0.22f);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = Theme.px(24);
            subLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            splashBox.addView(sSub, subLp);

            FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            boxLp.gravity = android.view.Gravity.CENTER;
            splash.addView(splashBox, boxLp);

            // 开屏底部的告知。放两行最关键的就够 —— 完整条款在「关于」页。
            // 位置选底部：中心是用户第一眼看的区域，法律文本放那儿会盖过品牌，
            // 也显得像在求人同意。
            android.widget.TextView sDisc = new android.widget.TextView(this);
            sDisc.setText("本软件仅为本地播放工具，不提供、不存储任何音视频内容。\n"
                    + "所有直播源由用户自行添加，请确保来源合法。\n"
                    + "不主张任何频道内容的著作权，权利归原权利人所有。\n"
                    + "如不接受上述条款，请停止使用并卸载本应用。");
            sDisc.setGravity(android.view.Gravity.CENTER);
            // 原来 #4D5560 在深色底上几乎看不见，等于没写。
            // 但也不能用正文色 —— 它是告知，不该和品牌名争注意力。
            sDisc.setTextColor(0xFF9AA3AE);
            sDisc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(15));
            sDisc.setLineSpacing(Theme.px(8), 1.15f);
            FrameLayout.LayoutParams discLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            discLp.gravity = android.view.Gravity.BOTTOM;
            discLp.bottomMargin = Theme.px(76);
            splash.addView(sDisc, discLp);

            root.addView(splash, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            splash.postDelayed(new Runnable() {
                @Override public void run() {
                    if (isFinishing()) return;
                    splash.animate().alpha(0f).setDuration(380)
                            .withEndAction(new Runnable() {
                                @Override public void run() {
                                    try { root.removeView(splash); } catch (Throwable ignored) { }
                                }
                            }).start();
                }
            }, 1200);
        }

        setContentView(root);

        // 欢迎语：开屏淡出之后再出，免得两块东西叠在一起
        root.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing() || osd == null) return;
                String last = lastChannelName();
                String sub = "已加载 " + channels.size() + " 个频道"
                        + (last == null ? "" : " · 上次看到 " + last);
                String welcome = "欢迎使用 " + appName();
                com.mintv.tv.ui.widget.NotifyBar.ok(welcome, sub);
            }
        }, 1800);

        focus.attach(this);
        long __theaders = System.currentTimeMillis();
        loadHeaders();
        android.util.Log.w("Startup", "headers " + (System.currentTimeMillis() - __theaders) + "ms");
        loadAutoPlayPref();
        loadLinePrefs();
        loadFavorites();
        applyOutputMode();
        focus.setNavCount(navViews.size());
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
                attempt().onFirstFrame();
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
                // 回看：用真实播放位置画进度条（主题色）。
                // 直播 dur 恒为 -1，走 refreshPlayInfo 的 EPG 推算（固定色）。
                if (dur > 0 && pos >= 0) {
                    osd.setProgram(epgNowText, epgNextText,
                            Math.min(1f, pos / (float) dur), false);
                }
            }

            @Override public void onError(String msg) {
                dumpPlaybackState("ERROR", msg);
                // 播放失败只显示在 OSD 上，不弹框（弹框只留给崩溃）
                osd.setStatus("播放失败：" + msg);
            }

            /**
             * 解码能力问题 → 无感切 VLC 重试。
             *
             * <p>用户不该知道内核出过什么事。Exo 解不了的源，VLC 大概率能解，
             * 所以这里不报错、不提示，直接换个内核重播同一个地址。
             * 只有当降级链走到底（VLC 也不行）才让 onError 兜底。
             */
            @Override public void onErrorRetryable(String msg) {
                dumpPlaybackState("RETRY", msg);
                android.util.Log.w("MinTV", "解码失败，尝试降级内核: " + msg);
                // 同一次换台只降级一次：否则 VLC 也失败时会来回切，
                // 表现为"画面一直闪、日志刷屏"。
                // 只剩一个内核，没有"降级"可走 —— 记一次尝试后直接按错误处理，
                // 由 onNoVideo / 看门狗去换线路。
                attempt().onErrorRetryable();
                onError(msg);
            }

            @Override public void onVideoFormat(String res, String vc, String ac, String ch, String fps) {
                if (res != null) lastResolution = res;
                if (ch != null) lastChannels = ch;
                if (fps != null) lastFps = fps;
                if (vc != null) lastVCodec = vc;
                if (ac != null) lastACodec = ac;
                refreshOsdTags();
            }

            @Override public void onBitrate(String bitrate) {
                if (bitrate != null) lastBitrate = bitrate;
            }

            @Override public void onRealtimeSpeed(float speed) {
                // Δ播放时间 / Δ墙钟时间。1.0 = 实时，<0.9 = 跟不上直播。
                if (osd != null) osd.setRealtimeSpeed(speed);
                // 低速持续 8 秒 → 换线路。
                // 这条能抓到"还在播但越来越慢"，位置判据抓不到那种。
                com.mintv.tv.data.PlaybackAttempt.Action act =
                        attempt().onSpeed(speed, SystemClock.uptimeMillis());
                if (act != com.mintv.tv.data.PlaybackAttempt.Action.NONE) {
                    handleDegrade(act, "播放跟不上，切换线路", "播放跟不上直播");
                }
            }
        };
        playerManager.setCallback(playerCallback);
        // 解码方式直接交给 VLC：它是启动参数，不需要重建播放器
        playerManager.setDecoderMode(effectiveDecoder());

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
                // VLC 需要知道视口尺寸，否则按解码原始尺寸画（右边留黑边）
                playerManager.updateWindowSize(w, ht);
            }
            @Override public void surfaceDestroyed(SurfaceHolder h) { player.setSurface(null); }
        });

        // 「解码方式」设置项：0 = 硬件，1 = 软件


        ui.post(clockTick);
        ui.post(speedTick);
        // 启动时记下当前订阅的 EPG 源，后面切订阅才能判断「要不要重载」
        {
            com.mintv.tv.data.SubscriptionStore.Sub act =
                    com.mintv.tv.data.SubscriptionStore.active(this);
            lastEpgSource = (act == null || act.epgUrl == null) ? "" : act.epgUrl.trim();
        }
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
    /**
     * 打开主题色选择。
     *
     * <p>选完要 recreate：颜色是 static 常量，已经画好的 View 不会自动
     * 换色，重建界面最干净。重建前必须先停播放器 —— recreate 的顺序是
     * 「新 Activity onCreate → 旧 Activity onDestroy」，新界面在 onCreate
     * 里就开始播，等旧 Activity 释放会有一段两个实例同时出声。
     */
    private static final int REQ_SCRIPT_DIR = 4001;
    private static final int REQ_LOCAL_SOURCE = 4002;

    /**
     * 网络代理输入框。
     *
     * <p>为什么需要代理：有些源只对特定出口 IP 开放，或者在内网深处。
     * 做法不是给播放器配代理（VLC 那套只管 HTTP，组播根本不走），
     * 而是让播放器全程访问 127.0.0.1，由宿主带代理去回源。
     */
    private void showProxyDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(com.mintv.tv.data.NetProxy.url(this));
        input.setHint("socks5://127.0.0.1:1080  或  http://127.0.0.1:8080");
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(17));
        input.setSingleLine(true);
        input.setPadding(Theme.px(18), Theme.px(14), Theme.px(18), Theme.px(14));
        input.setTextColor(0xFFE8ECF2);
        input.setHintTextColor(0xFF6E7683);
        input.setBackgroundColor(0xFF1B1C1F);
        showSystemDialog(new android.app.AlertDialog.Builder(this)
                .setTitle("播放器代理（留空 = 直连）")
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        com.mintv.tv.data.NetProxy.save(MainActivity.this, input.getText().toString());
                        // 副标题和右侧「代理/直连」都要跟着变，否则看起来像没保存
                        refreshSettingRow(ROW_NET_PROXY);
                        osd.toast("代理已保存：" + com.mintv.tv.data.NetProxy.summary(MainActivity.this));
                        // 重建播放器，让新配置在下次起播生效
                        if (playerManager != null) playerManager.release();
                        playChannelInternal(playingChannel, true);
                    }
                })
                .setNegativeButton("取消", null));
    }

    /**
     * 从本机 / U 盘导入一份源文件。
     *
     * <p>读完之后内容会**复制**到 App 目录，不是引用原文件 ——
     * 否则拔了 U 盘源就没了。
     */
    private void pickLocalSource() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                    new String[]{"text/plain", "text/x-mpegurl", "application/x-mpegurl",
                                 "audio/mpegurl", "application/octet-stream"});
            startActivityForResult(i, REQ_LOCAL_SOURCE);
        } catch (Throwable t) {
            osd.toast("这台设备没有文件选择器");
        }
    }

    /** 读 SAF 选中的文件 → 存进 sources/ → 建订阅并切过去。 */
    private void importLocalSource(final android.net.Uri uri) {
        if (uri == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                String display = "imported.m3u";
                String content = null;
                try {
                    android.database.Cursor c = getContentResolver().query(uri,
                            new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                            null, null, null);
                    if (c != null) {
                        if (c.moveToFirst()) {
                            String n = c.getString(0);
                            if (n != null && !n.isEmpty()) display = n;
                        }
                        c.close();
                    }
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in != null) {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        in.close();
                        content = new String(bos.toByteArray(),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (Throwable ignored) { }

                final String bodyText = content;
                final String fileName = display;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (bodyText == null || bodyText.trim().isEmpty()) {
                            osd.toast("文件读不到内容");
                            return;
                        }
                        try {
                            String safe = fileName.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
                            java.io.File f = new java.io.File(localSourceDir(), safe);
                            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                            try { fos.write(bodyText.getBytes("UTF-8")); } finally { fos.close(); }

                            com.mintv.tv.data.SubscriptionStore.Sub sub =
                                    new com.mintv.tv.data.SubscriptionStore.Sub(
                                            "local" + System.currentTimeMillis(),
                                            fileName.replaceAll("\\.[^.]+$", ""),
                                            "local://" + safe);
                            com.mintv.tv.data.SubscriptionStore.upsert(MainActivity.this, sub);
                            com.mintv.tv.data.SubscriptionStore.setActive(
                                    MainActivity.this, sub.id);

                            applyPlaylist(bodyText);
                            saveSelections();
                            osd.toast("已导入 " + fileName + " · " + channels.size() + " 个频道");
                        } catch (Throwable t) {
                            osd.toast("导入失败：" + t.getClass().getSimpleName());
                        }
                    }
                });
            }
        }, "local-import").start();
    }

    /**
     * 让用户选脚本目录。
     *
     * <p>为什么用 SAF 而不是直接读写 /sdcard：Android 11+ 之后
     * 访问 Android/data 以外的目录要么申请 MANAGE_EXTERNAL_STORAGE
     * （电视盒子上经常没有这个设置页），要么走 SAF。SAF 是系统标准，
     * 手机和盒子都能用。
     */
    /**
     * 选存储根目录。
     *
     * <p>一个授权管全部：脚本、本地订阅、上传文件都在这个根下面。
     * 之前是"脚本目录"单独一套，本地导入又一套，三处各存各的。
     */
    private void pickStorageDir() {
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_SCRIPT_DIR);
        } catch (Throwable t) {
            osd.toast("这台设备没有文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        if (req == REQ_LOCAL_SOURCE) {
            if (res == RESULT_OK && data != null) {
                importLocalSource(data.getData());
            }
            return;
        }
        if (req == REQ_SCRIPT_DIR) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                com.mintv.tv.data.StorageDir.setRoot(this, data.getData());
                String path = com.mintv.tv.data.StorageDir.readablePath(data.getData());
                com.mintv.tv.data.ScriptLog.info("系统", "存储目录已设置: " + path);
                osd.toast("已授权 " + path);
                // 三个子目录已经建好，顺便把本地源扫一遍
                com.mintv.tv.data.SubscriptionStore.reload();
                loadLocalSources();
                refreshSettingRows();
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    private final java.util.concurrent.atomic.AtomicInteger reconnectSeq =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * 单源超时的重连调度。
     *
     * <p>以前只有一条线路时超时直接放弃 —— 用户看到「该源无响应」
     * 之后永久黑屏，只能手动切台。而绝大多数"无响应"是临时的
     * （服务端重启、切片还没生成），重连几次往往就好了。
     *
     * <p>间隔就是用户在「超时换源」里设的秒数 —— 设 8 秒就每 8 秒重连一次，
     * 不做指数退避（用户设了个数，不该被程序偷偷改掉）。
     * 出画面后计数清零。
     */
    private void scheduleReconnect() {
        final int seq = reconnectSeq.incrementAndGet();
        long delay = attempt().nextReconnectDelayMs();
        int n = attempt().reconnectCount();

        String sec = delay < 1000 ? (delay + "ms") : ((delay / 1000) + " 秒");
        osd.toast("该源无响应， " + sec + "后重连" + (n > 1 ? "（第 " + n + " 次）" : ""));

        ui.postDelayed(new Runnable() {
            @Override public void run() {
                // 期间用户换台了就不重连了
                if (seq != reconnectSeq.get()) return;
                if (isFinishing()) return;
                // 清掉"已经尝试过"的标记，否则第二次超时会被挡住
                attempt().resetForReconnect();
                playChannelInternal(playingChannel, true);
            }
        }, delay);
    }

    private void openThemePicker() {
        if (themePicker != null || rootView == null) return;
        themePicker = new com.mintv.tv.ui.widget.ThemePickerView(this);
        themePicker.setDismissAction(new Runnable() {
            @Override public void run() {
                if (themePicker != null && rootView != null) {
                    rootView.removeView(themePicker);
                    themePicker = null;
                }
            }
        });
        themePicker.setListener(new com.mintv.tv.ui.widget.ThemePickerView.Listener() {
            @Override public void onPicked(int index) {
                if (index == Theme.accentIndex) return;    // 没换色，别白重建
                Theme.applyAccent(index);
                // 用 commit 不用 apply：recreate 紧接着就要读这个值，
                // apply 是异步落盘，虽然内存里已经更新，但换成 commit
                // 能排除掉"重建时读到旧值"这一整类可能。
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                        .putInt(KEY_ACCENT, index).commit();

                // 先停播放器再重建。不先停的话 recreate 期间新旧两个
                // Activity 会各持一个播放器，出现"两个台同时出声"。
                try {
                    if (playerManager != null) playerManager.release();
                } catch (Throwable ignored) { }
                playerManager = null;
                player = null;

                android.util.Log.i("MinTV", "accent -> " + index + "，准备重建界面");
                // 停一个事件循环再重建：让 release 先把渲染目标交出去，
                // 立刻 recreate 有概率撞上 Surface 销毁回调。
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(new Runnable() {
                            @Override public void run() {
                                if (!isFinishing()) recreate();
                            }
                        }, 150);
            }
            @Override public void onCancelled() { }
        });
        rootView.addView(themePicker, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        themePicker.requestFocus();
    }

    /**
     * 把 OSD 的两个外观设置推给 OsdView。
     *
     * <p>这两个设置以前是**假的** —— SettingDef 里定义了、界面能点、
     * selections 也存下来了，但从来没有代码读它，改了完全没效果。
     * 比没有这个功能更糟：用户以为调好了，其实没变。
     */
    /**
     * 一行分组标题。
     *
     * <p>**文字用灰白，主题色放在左侧竖条上。**
     *
     * <p>试过用主题色当文字色（v2.51.0），在你的设备上完全看不见 ——
     * 同样的字号、同样的宽度，只改颜色就消失。原因没查出来（颜色本身
     * 有 FF alpha、对比度也够），所以回到 v2.50.0 这个验证过能显示的方案。
     *
     * <p>结构也保持最简：竖条 + 单行 TextView，不加横线、不用 weight，
     * 避免再踩布局测量的坑。
     */
    private LinearLayout buildGroupHeader(String title) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(Theme.px(6), Theme.px(18), Theme.px(6), Theme.px(6));

        View bar = new View(this);
        bar.setBackgroundColor(Theme.FOCUS_BLUE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                Theme.px(3), Theme.px(15));
        barLp.rightMargin = Theme.px(10);
        h.addView(bar, barLp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Theme.TEXT);        // 灰白 —— 唯一验证过一定可见的颜色
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(14));
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(t, new LinearLayout.LayoutParams(
                Theme.px(84), Theme.px(24)));
        return h;
    }

    private void applyOsdPrefs() {
        if (osd == null) return;
        osd.setAlignLeft(selections[ROW_EPG_POS] == 0);
        osd.setSwitchMask(selections[ROW_SWITCH_UI]);
    }

    private void applyOutputMode() {
        if (textureView == null || surfaceView == null) return;
        boolean tex = selections[ROW_OUTPUT] == OUTPUT_TEXTURE;
        textureView.setVisibility(tex ? View.VISIBLE : View.GONE);
        surfaceView.setVisibility(tex ? View.GONE : View.VISIBLE);
        if (playerManager == null) return;
        if (tex) {
            // 必须用组件而不是裸 Surface：VLC 的 IVLCVout 只认 SurfaceView。
            // 之前这里调 setSurface(裸 Surface)，而 VlcBackend.setSurface
            // 是空实现 —— 切到 SurfaceView 后根本没绑上，黑屏有声音。
            playerManager.setTextureView(textureView);
        } else {
            playerManager.setTextureView(null);   // 真正解绑 TextureView
            playerManager.setSurfaceView(surfaceView);
        }
    }

    /**
     * 订阅列宽度。开关关掉时是 0 —— 所有列的位置都基于它算，
     * 关闭状态下整个布局和以前完全一样，不会留下空白。
     */
    private int subColW() {
        // 240 而不是 200：去掉图标和计数之后仍然要给中文名留够位置，
        // 200 的时候「咪咕直播」只能显示成「咪咕…」
        return selections[ROW_SHOW_SUBS] == 1 ? Theme.px(240) : 0;
    }

    private int chanWidth() {
        return Theme.px(Theme.CHAN_W);
    }

    private void buildOverlay(FrameLayout root) {
        int navW = Theme.px(Theme.NAV_W);

        // 四列（订阅 / 分类 / 频道 / 竖轨）放进同一个水平容器。
        //
        // 以前每列都直接挂 root 并硬算 leftMargin，"前面所有列宽之和"。
        // 加一列要改所有下游坐标，而且显隐/动画各写一套 ——
        // 全屏时订阅列不消失就是这个结构造成的。
        //
        // 换容器之后位置完全不变（容器从 x=0 开始、MATCH_PARENT 宽），
        // 但加/删列只改一行，其他列自动流式跟随。
        // 容器在 scrim / osd 之后添加，保持原有 z-order。
        LinearLayout colsRow = new LinearLayout(this);
        colsRow.setOrientation(LinearLayout.HORIZONTAL);
        this.colsRow = colsRow;
        // 容器本身不消费触摸：空白区域的事件继续往下传给 OSD / 视频区。
        // 否则它铺满全屏、又在 OSD 之上，会把整屏点击吞掉。
        colsRow.setClickable(false);
        colsRow.setFocusable(false);
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
        osd.setCtlListener(new OsdView.CtlListener() {
            @Override public void onPrevChannel() { moveChannel(-1); }
            @Override public void onNextChannel() { moveChannel(+1); }
            @Override public void onTogglePause() {
                if (player == null) return;
                // ⚠ 必须读 isPaused()，不能读 isPlaying()。
                // VLC 直播在 buffering 时 isPlaying() 返回 false，
                // 用它会永远走 resume 分支 —— 表现就是"点暂停毫无反应"。
                boolean nowPaused = !player.isPaused();
                if (nowPaused) player.pause(); else player.resume();
                // 暂停期间播放位置不动，必须让卡顿检测停手，
                // 否则暂停十几秒就会弹「播放卡住，切换线路」
                attempt().setPaused(nowPaused);
                osd.setPaused(nowPaused);
                osd.reshow();
            }
        });
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

        // --- 遮罩 ---
        scrim = new ScrimView(this);
        root.addView(scrim, new FrameLayout.LayoutParams(scrimW, ViewGroup.LayoutParams.MATCH_PARENT));

        // OSD 必须在 scrim **之上**、面板之下。
        // 之前它加在 scrim 前面，左侧那块 94% 黑的渐变遮罩直接盖住信息条左半边，
        // 看起来就是"信息条只显示了一半/压根没显示"。
        applyOsdPrefs();
        root.addView(osd, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- 左导航 ---
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(Theme.px(22), Theme.px(34), Theme.px(22), 0);
        nav.setBackgroundColor(Theme.PANEL_NAV);

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
        // 前三列整体右移 subColW —— 关掉订阅列时它是 0，布局和以前一致
        int subW = subColW();
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(navW, FrameLayout.LayoutParams.MATCH_PARENT);
        navLp.leftMargin = subW;
        colsRow.addView(nav, new LinearLayout.LayoutParams(navW, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams navLineLp = lineParams(navW);
        navLineLp.leftMargin += subW;
        colsRow.addView(navLine, new LinearLayout.LayoutParams(Theme.px(1), ViewGroup.LayoutParams.MATCH_PARENT));

        // --- 订阅列 ---
        // 只在「显示订阅列」开启时创建。内容在 rebuildCategoryColumn 里刷新。
        if (subW > 0) {
            LinearLayout subCol = new LinearLayout(this);
            subCol.setOrientation(LinearLayout.VERTICAL);
            subCol.setPadding(0, Theme.px(26), 0, 0);
            subCol.setBackgroundColor(0x66000000);
            subColumn = subCol;

            TextView hd = new TextView(this);
            hd.setText("订阅");
            hd.setTextColor(Theme.TEXT);
            hd.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(Theme.TS_NAV_TITLE));
            hd.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            hd.setPadding(Theme.px(18), 0, 0, Theme.px(18));
            subCol.addView(hd);

            FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(
                    subW, FrameLayout.LayoutParams.MATCH_PARENT);
            // 插到索引 0 —— 订阅是最外层（先选源，再选分类）
            colsRow.addView(subCol, 0, new LinearLayout.LayoutParams(
                    subW, ViewGroup.LayoutParams.MATCH_PARENT));

            // 分隔线
            View subLine = new View(this);
            subLine.setBackgroundColor(Theme.LINE);
            subLineView = subLine;
            FrameLayout.LayoutParams slLp = new FrameLayout.LayoutParams(
                    Theme.px(1), FrameLayout.LayoutParams.MATCH_PARENT);
            slLp.leftMargin = subW;
            colsRow.addView(subLine, 1, new LinearLayout.LayoutParams(
                    Theme.px(1), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // --- 频道列 ---
        LinearLayout chan = new LinearLayout(this);
        chan.setOrientation(LinearLayout.VERTICAL);
        chan.setPadding(0, Theme.px(26), 0, 0);

        TextView chanHead = new TextView(this);
        chanHead.setText("节目 » 信息");
        chanHead.setTextColor(Theme.HEADER_TEXT);
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
        chanLp.leftMargin = subW + navW + Theme.px(1);
        chanContainer = chan;
        colsRow.addView(chan, new LinearLayout.LayoutParams(chanW, ViewGroup.LayoutParams.MATCH_PARENT));

        // 竖轨：EPG 收起时作为「节目 » 信息」入口，点它展开节目单
        View rail = new RailView(this);
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(
                railW, FrameLayout.LayoutParams.MATCH_PARENT);
        railLp.leftMargin = subW + navW + chanW + Theme.px(1);
        railView = rail;
        rail.setClickable(true);
        rail.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showEpgPanel(playingChannel >= 0 ? playingChannel : 0); }
        });
        colsRow.addView(rail, new LinearLayout.LayoutParams(railW, ViewGroup.LayoutParams.MATCH_PARENT));
        // 剩余空间留给视频区（列容器本身铺满，视频区在它下面一层）
        root.addView(colsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        epgScroll.setBackgroundColor(Theme.PANEL_NAV);
        epgPanel = new EpgPanelView(this);
        epgScroll.addView(epgPanel, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams epgLp = new FrameLayout.LayoutParams(
                epgW, FrameLayout.LayoutParams.MATCH_PARENT);
        // EPG 面板也进 colsRow —— 位置由流式布局决定，不再手算 leftMargin。
        //
        // 手算的问题是每加一列都要回来改这里，而且设备分辨率不同、
        // 缩放不同时算出来的值容易和实际布局差一截（抽屉态左边空一片就是这么来的）。
        // 放进容器之后，它永远紧跟在频道列右侧，谁也不用管。
        epgScroll.setVisibility(View.GONE);   // GONE 不占位，常态下自动收缩
        colsRow.addView(epgScroll, new LinearLayout.LayoutParams(
                epgW, ViewGroup.LayoutParams.MATCH_PARENT));

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
                // ⚠ 先把事件喂给 OSD。
                //
                // gestureLayer 是纯播放态下唯一铺满全屏的可点层，而它是在
                // osd 之后 addView 的 —— FrameLayout 从最上层往下分发，
                // 所以点击永远先落到 gestureLayer，OSD 上的暂停/切台按钮
                // 一次都收不到事件。表现就是"点暂停，按钮没反应，
                // 反而当成点视频区把 OSD 收掉了"。
                //
                // 不能靠调换 addView 顺序解决：OSD 必须垫在面板之下
                // （否则信息条会压在频道列表上）。所以在这里手工让位。
                if (osd != null && osd.dispatchTouchEvent(e)) return true;
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

    /**
     * 读取源。
     *
     * <p>**没有订阅就显示空状态，不再拿内置演示频道冒充真实数据。**
     * 以前这里会 fallback 到 DemoData.SAMPLE_M3U（北京卫视那一套），
     * 结果用户从没加过任何源，一打开就看到一堆频道，点开还播不了 ——
     * 那是给开发期做布局预览用的假数据，不该出现在正式包里。
     */
    /**
     * 启动时加载订阅。
     *
     * <p><b>读文件和解析都丢到后台线程</b>：SAF 读一次要几十到几百毫秒，
     * 839 个频道的 PlaylistParser.parse 也要上百毫秒 —— 全在主线程的话
     * 冷启动首屏会明显迟滞。主线程只负责最后的 View 重建。
     */
    private void loadPlaylist() {
        final String url = currentSourceUrl();
        new Thread(new Runnable() {
            @Override public void run() {
                String body = null;
                boolean badSource = false;
                try {
                    if (url != null && !url.isEmpty()) {
                        body = SourceStore.cachedBody(MainActivity.this);
                    }
                } catch (Throwable t) {
                    android.util.Log.w("MinTV", "读源缓存失败", t);
                }
                if (body == null || body.trim().isEmpty()) {
                    body = "#EXTM3U\n";     // 空源：等网络拉回来再渲染
                } else if (SourceStore.isBad(MainActivity.this,
                        SourceStore.fingerprint(body))) {
                    // 上次这份内容就解析不出频道（多半是填错地址拿到的 HTML 页）。
                    // 直接跳过，不让它再跑一次解析。
                    android.util.Log.w("MinTV", "跳过已标记为无效的源缓存");
                    body = "#EXTM3U\n";
                    badSource = true;
                }

                final PlaylistParser.Playlist parsed;
                try {
                    parsed = PlaylistParser.parse(body);
                } catch (Throwable t) {
                    android.util.Log.w("MinTV", "解析源失败", t);
                    return;
                }

                final String finalBody = body;
                final boolean finalBad = badSource;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        if (finalBad && osd != null) {
                            osd.toast("上次的源无效已忽略，请更新源或换一个订阅");
                        }
                        applyPlaylistParsed(parsed, finalBody);
                        if (url != null && !url.isEmpty()) fetchSourceAsync(url);
                    }
                });
            }
        }, "load-playlist").start();
    }

    /** 当前生效的订阅地址（没有订阅就返回空串）。 */
    private String currentSourceUrl() {
        com.mintv.tv.data.SubscriptionStore.Sub s =
                com.mintv.tv.data.SubscriptionStore.active(this);
        if (s != null && s.url != null && !s.url.isEmpty()) return s.url;
        // 兼容：还没迁移过的老配置
        return SourceStore.hasUserUrl(this) ? SourceStore.url(this) : "";
    }

    /**
     * 解析 + 应用。保留这个签名给「切源」等调用点用。
     *
     * <p>启动路径不走这里 —— 那边在后台线程解析好，直接调
     * {@link #applyPlaylistParsed}，避免主线程做 839 个频道的解析。
     */
    private void applyPlaylist(String body) {
        applyPlaylistParsed(PlaylistParser.parse(body), body);
    }

    /** 只做 UI 部分，解析已经在调用方完成（可能在后台线程）。 */
    private void applyPlaylistParsed(PlaylistParser.Playlist parsed, String body) {
        playlist = parsed;
        rebuildCategories();
        channels = playlist.all;
        // 订阅列表的频道数要跟着更新；不刷新的话切完源订阅列还显示旧数字
        fillSubColumn();

        // 存进缓存，下次切回来直接取
        String curSub = com.mintv.tv.data.SubscriptionStore.activeId(this);
        if (curSub != null && channels != null && !channels.isEmpty()) {
            sourceCache.put(curSub, playlist);
            channelCache.put(curSub, channels);
        }

        // ── 关键：数据换完了必须重建 UI ──
        //
        // 之前这里只更新 playlist / channels / categories，界面不动。
        // 结果：
        //   · 分类计数显示 0（categories 是新的，但列表还是旧的 View）
        //   · 切订阅后频道列表不刷新，非得重启 App
        // 两个现象是同一个原因。
        if (navPanel != null) {
            try {
                rebuildCategoryColumn();
                bindChannels();
                updateChannelFocus(Math.min(focus.chanIndex(), Math.max(0, channels.size() - 1)));
            } catch (Throwable t) {
                android.util.Log.w("MinTV", "重建列表失败", t);
            }
        }

        // 解析出 0 个频道，但内容明显不是空源 → 判定这份内容有问题，记下指纹。
        // 记下来之后下次启动会直接跳过，避免"一次填错地址，之后每次启动都崩"。
        boolean looksEmpty = body == null || body.trim().isEmpty()
                || body.trim().equals("#EXTM3U");
        if (!looksEmpty && (channels == null || channels.isEmpty())) {
            SourceStore.markBad(this, SourceStore.fingerprint(body));
            android.util.Log.w("MinTV", "这份源解析不出频道，已标记为无效");
        } else if (channels != null && !channels.isEmpty()) {
            SourceStore.clearBad(this);
        }

        // 列表就绪了，补一次「恢复上次没看完的台」。
        //
        // onCreate 里的 restoreLastChannel() 是同步跑的，那时 channels /
        // categories 还是空的（loadPlaylist 已经挪到后台线程），
        // tryRestoreIfPending 会因为条件不满足直接 return 且不置 restoreDone。
        // 之后没人再调用，结果就是每次冷启动都停在第一台不自动播。
        if (restoreChanKey != null) {
            restoreDone = false;
            tryRestoreIfPending();
        }
    }

    // ---------- 无感刷新订阅 ----------

    /**
     * 自动刷新总开关。<b>默认关闭</b>：逻辑先备好，等设置项做好再打开。
     *
     * <p>打开方式：改成 true，或者调用 {@link #setAutoRefresh(long)}。
     */
    private static final boolean AUTO_REFRESH_ENABLED = false;

    /** 默认间隔，6 小时。 */
    private static final long AUTO_REFRESH_MS = 6L * 60 * 60 * 1000;
    /**
     * 启动后第一次刷新的延迟。
     *
     * <p>放长到 5 分钟而不是 60 秒 —— 冷启动后那段时间用户可能刚点开某个台，
     * 这时候在后台重建频道列表纯属添乱。
     */
    private static final long AUTO_REFRESH_FIRST_MS = 5L * 60 * 1000;

    /** 数据已换但界面还没重建。面板下次打开时补上。 */
    private volatile boolean channelsDirty = false;
    /** 一次只允许一个刷新在跑，防并发把 channels 和 playlist 换岔。 */
    private volatile boolean refreshBusy = false;

    private final Runnable autoRefreshLoop = new Runnable() {
        @Override public void run() {
            refreshSubscriptionsSilently();
            ui.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    /** 启动自动刷新。受总开关控制；重复调用无害。 */
    private void startAutoRefresh() {
        if (!AUTO_REFRESH_ENABLED) return;
        ui.removeCallbacks(autoRefreshLoop);
        ui.postDelayed(autoRefreshLoop, AUTO_REFRESH_FIRST_MS);
    }

    /**
     * 外部接口：打开自动刷新。
     *
     * <p>给以后的设置项用。{@code intervalMs <= 0} 表示关掉。
     */
    public void setAutoRefresh(long intervalMs) {
        ui.removeCallbacks(autoRefreshLoop);
        if (intervalMs <= 0) return;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                refreshSubscriptionsSilently();
                ui.postDelayed(autoRefreshLoop, intervalMs);
            }
        }, intervalMs);
    }

    /** 外部接口：停掉自动刷新。Activity 销毁时也会调。 */
    public void stopAutoRefresh() {
        ui.removeCallbacks(autoRefreshLoop);
    }

    /**
     * 后台拉一次当前订阅，成功就静默换掉数据。
     *
     * <p>拉取失败、内容为空、解析不出频道，都保留旧列表不动 ——
     * 订阅源抽风的时候不能让正在看的人突然没台了。
     */
    private void refreshSubscriptionsSilently() {
        final String url = currentSourceUrl();
        if (url == null || url.isEmpty()) return;
        if (refreshBusy) {
            android.util.Log.w("MinTV", "上一次刷新还没结束，跳过本轮");
            return;
        }
        refreshBusy = true;
        final java.util.Map<String, String> hdrs = headersOfActiveSub();
        new Thread(new Runnable() {
            @Override public void run() {
                String body = null;
                try {
                    com.mintv.tv.data.Http.Result r =
                            com.mintv.tv.data.Http.get(url, hdrs, 8000, 12000, false);
                    if (r != null && r.ok()) body = r.body;
                } catch (Throwable t) {
                    android.util.Log.w("MinTV", "无感刷新拉取失败: " + t);
                }
                if (body == null) {
                    android.util.Log.w("MinTV", "无感刷新：拉取失败，保留旧列表");
                    refreshBusy = false;
                    return;
                }
                final String b = body;
                ui.post(new Runnable() {
                    @Override public void run() {
                        try { applyPlaylistSilently(b); }
                        catch (Throwable t) {
                            android.util.Log.w("MinTV", "无感刷新应用失败", t);
                        }
                    }
                });
            }
        }, "sub-auto-refresh").start();
    }

    /** 频道的稳定标识。名字相同但地址不同算两个频道。 */
    private static String channelKey(PlaylistParser.Channel c) {
        if (c == null) return "";
        // URL 在 sources 里，一个频道可能有多个备选源
        String u = "";
        if (c.sources != null && !c.sources.isEmpty()
                && c.sources.get(0) != null && c.sources.get(0).url != null) {
            u = c.sources.get(0).url;
        }
        return c.name + "|" + u;
    }

    /**
     * 静默应用新列表：换数据 + 重建列表视图，<b>不碰播放器</b>。
     *
     * <p>难点是 playingChannel 是下标，列表一换就指错台。所以先记住
     * 正在播的那个频道，换完再按名字+地址找回来：
     * <ul>
     *   <li><b>找到了</b>：只更新下标。地址变了也不重播，等用户下次切台
     *       才用新的 —— 正在播的那路流是好的，没必要掐掉。</li>
     *   <li><b>只找到同名（地址变了）</b>：同上，按名字兜底。</li>
     *   <li><b>完全找不到</b>：说明这个台被源删了。播放不打断（流还能拉），
     *       但提示一句，下标钳到合法范围。</li>
     * </ul>
     */
    private void applyPlaylistSilently(String body) {
        if (body == null || body.trim().isEmpty()) return;

        PlaylistParser.Playlist np;
        try {
            np = PlaylistParser.parse(body);
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "无感刷新：解析失败", t);
            return;
        }
        List<PlaylistParser.Channel> nc = np == null ? null : np.all;
        if (nc == null || nc.isEmpty()) {
            android.util.Log.w("MinTV", "无感刷新：新列表为空，保留旧的");
            return;
        }

        // 记住当前在播的
        String key = null, name = null;
        int curIdx = playingChannel;
        if (channels != null && curIdx >= 0 && curIdx < channels.size()) {
            PlaylistParser.Channel cur = channels.get(curIdx);
            key = channelKey(cur);
            name = cur.name;
        }

        int oldSize = channels == null ? 0 : channels.size();

        // 换数据
        playlist = np;
        channels = nc;
        rebuildCategories();

        // 找回当前频道
        int found = -1;
        if (key != null && !key.isEmpty()) {
            for (int i = 0; i < nc.size(); i++) {
                if (key.equals(channelKey(nc.get(i)))) { found = i; break; }
            }
            if (found < 0 && name != null) {
                for (int i = 0; i < nc.size(); i++) {
                    if (name.equals(nc.get(i).name)) { found = i; break; }
                }
            }
        }

        if (found >= 0) {
            playingChannel = found;
            android.util.Log.w("MinTV", "无感刷新：当前频道重新定位 "
                    + curIdx + " -> " + found + "（" + name + "）");
        } else if (key != null) {
            // 当前台被删了
            if (playingChannel >= nc.size()) playingChannel = nc.size() - 1;
            if (playingChannel < 0) playingChannel = 0;
            android.util.Log.w("MinTV", "无感刷新：当前频道已被源移除（" + name
                    + "），播放不打断，下标钳到 " + playingChannel);
            if (osd != null) osd.toast("当前频道已从源中移除，播放不中断");
        } else {
            if (playingChannel >= nc.size()) playingChannel = nc.size() - 1;
            if (playingChannel < 0) playingChannel = 0;
        }

        // 缓存
        try {
            String curSub = com.mintv.tv.data.SubscriptionStore.activeId(this);
            if (curSub != null) {
                sourceCache.put(curSub, playlist);
                channelCache.put(curSub, channels);
            }
        } catch (Throwable ignored) { }

        // 重建列表视图 —— 只在面板可见时做。
        //
        // bindChannels 会在主线程 removeAllViews 再逐个建，几百个频道就是
        // 几百次 measure/layout，几百毫秒的卡顿。播放中这么做直接掉帧，
        // 而且用户正在翻列表时会跳回顶部、焦点丢失。
        //
        // 面板不可见时只把数据换掉，标记 dirty，等下次打开面板再重建 ——
        // 那时候重建是用户预期的，不会有任何突兀感。
        if (navPanel != null && panelVisible) {
            try {
                rebuildCategoryColumn();
                bindChannels();
                updateChannelFocus(Math.min(focus.chanIndex(),
                        Math.max(0, channels.size() - 1)));
                fillSubColumn();
                channelsDirty = false;
            } catch (Throwable t) {
                android.util.Log.w("MinTV", "无感刷新重建列表失败", t);
                channelsDirty = true;
            }
        } else if (navPanel != null) {
            channelsDirty = true;
            android.util.Log.i("MinTV", "无感刷新：面板未打开，界面延后重建");
        }
        refreshBusy = false;

        android.util.Log.i("MinTV", "无感刷新完成：" + oldSize + " -> "
                + channels.size() + " 个频道，现在播第 " + playingChannel + " 个");
    }

    /** URL 的 host 是不是回环地址。解析失败按「不是」处理。 */
    private static boolean isLoopbackUrl(String url) {
        if (url == null) return false;
        try {
            String h = new java.net.URL(url).getHost();
            if (h == null) return false;
            h = h.toLowerCase(java.util.Locale.US);
            return "127.0.0.1".equals(h)
                    || "localhost".equals(h)
                    || "::1".equals(h)
                    || "[::1]".equals(h)
                    || h.startsWith("127.");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 日志用的短字符串。    /**
     * 日志用的短字符串。
     *
     * <p>解析后的地址可能很长（mintv:// 里面套了 base64），直接拼进日志
     * 是有风险的 —— 之前就在这里 OOM 过一次（49MB 的 StringBuilder）。
     */
    private static String brief(String s) {
        if (s == null) return "null";
        if (s.length() <= 200) return s;
        return s.substring(0, 200) + "...(len=" + s.length() + ")";
    }

    /** 当前订阅自带请求头（可能为 null / 空）。 */
    private java.util.Map<String, String> headersOfActiveSub() {
        com.mintv.tv.data.SubscriptionStore.Sub sub =
                com.mintv.tv.data.SubscriptionStore.active(this);
        if (sub == null) return null;
        return com.mintv.tv.ui.player.HttpHeaderProfile.parsePairs(sub.headers);
    }

    /**
     * 把配置里的请求头写到连接上。
     *
     * <p>**没有配置就一个都不写**，包括 User-Agent —— 让 HttpURLConnection
     * 用它自己的默认值。写死一个 "TVUI/1.0" 会让某些源直接 403
     * （服务端按 UA 白名单过滤），而用户根本不知道是这里的问题。
     */
    private void applyConfiguredHeaders(java.net.HttpURLConnection conn,
                                        java.util.Map<String, String> hdrs) {
        if (conn == null || hdrs == null || hdrs.isEmpty()) return;
        for (java.util.Map.Entry<String, String> e : hdrs.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || v == null || k.isEmpty() || v.isEmpty()) continue;
            try { conn.setRequestProperty(k, v); } catch (Throwable ignored) { }
        }
    }

    /** 后台拉源，成功后重建界面。失败就继续用缓存/示范。 */
    /**
     * 把网络异常翻译成人话。
     *
     * <p>原来直接把异常 toString() 拼进 toast，用户看到的是
     * 「java.net.ConnectException: Failed to connect to xxx.com/...:443」
     * —— 类名、域名、端口号，对用户零信息量，而且长到横跨半个屏幕。
     *
     * <p>技术细节走 Log，界面上只留「能不能解决、怎么解决」。
     */
    private static String friendlySourceError(String err) {
        if (err == null || err.isEmpty()) return "源拉取失败";
        String e = err.toLowerCase(java.util.Locale.US);
        if (e.contains("connectexception") || e.contains("failed to connect")
                || e.contains("unknownhost") || e.contains("unreachable")
                || e.contains("no route")) {
            return "源拉取失败：网络连不上，检查网络或换个源地址";
        }
        if (e.contains("timeout") || e.contains("timed out")) {
            return "源拉取失败：连接超时，稍后重试";
        }
        if (e.contains("404")) return "源拉取失败：地址不存在（404）";
        if (e.contains("403")) return "源拉取失败：没有访问权限（403）";
        if (e.contains("ssl") || e.contains("certificate")) {
            return "源拉取失败：证书校验不通过";
        }
        if (e.contains("cleartext")) {
            return "源拉取失败：该地址不允许明文 HTTP";
        }
        return "源拉取失败";
    }

    /** 本地源的存放目录：Android/data/<包名>/files/sources */
    private java.io.File localSourceDir() {
        java.io.File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        java.io.File d = new java.io.File(base, "sources");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /**
     * 本地源：直接从文件读，不走网络、不发 HTTP。
     *
     * <p>地址形如 {@code local://我的源.m3u}。文件名限制在 sources 目录内，
     * 拦 ".." 和斜杠，避免这个协议被用来读任意文件。
     */
    /**
     * 扫 sources/ 目录，把里面的 m3u / txt 都建成 local:// 订阅。
     *
     * <p>替代原来的「导入本地源」按钮：把文件丢进目录就行，不用再点导入。
     * 已存在的（同文件名）跳过，不重复加。
     */
    /**
     * 扫某个子目录，把里面的 m3u/m3u8/txt 建成订阅。
     *
     * @return 新增的订阅数
     */
    private int scanDirForSubs(String subDir, String scheme) {
        java.util.List<String> names = com.mintv.tv.data.StorageDir.list(this, subDir);
        boolean rooted = com.mintv.tv.data.StorageDir.hasRoot(this);
        android.util.Log.w("MinTV", "扫描 " + subDir + ": "
                + (names == null ? "null" : names.size() + " 项 · " + names)
                + " · " + (rooted ? "已授权" : "未授权"));
        if (names == null || names.isEmpty()) return 0;

        // 已有订阅的 (scheme+文件名) 集合，避免重复加
        java.util.Set<String> have = new java.util.HashSet<>();
        for (com.mintv.tv.data.SubscriptionStore.Sub sub
                : com.mintv.tv.data.SubscriptionStore.list(this)) {
            if (sub.url != null) have.add(sub.url);
        }

        int added = 0;
        for (String n : names) {
            String lower = n.toLowerCase();
            if (!lower.endsWith(".m3u") && !lower.endsWith(".m3u8")
                    && !lower.endsWith(".txt")) continue;
            String full = scheme + n;
            if (have.contains(full)) continue;
            // 文件名去掉扩展名当订阅名
            String title = n;
            int dot = title.lastIndexOf('.');
            if (dot > 0) title = title.substring(0, dot);
            com.mintv.tv.data.SubscriptionStore.Sub created =
                    new com.mintv.tv.data.SubscriptionStore.Sub(
                            java.util.UUID.randomUUID().toString(), title, full);
            com.mintv.tv.data.SubscriptionStore.upsert(this, created);
            have.add(full);
            added++;
        }
        return added;
    }

    private void loadLocalSources() {
        // 两个目录都扫：
        //   sources/ 用户自己丢进去的  → local://
        //   upload/  手机后台上传的    → upload://
        int added = 0;
        added += scanDirForSubs(com.mintv.tv.data.StorageDir.SUB_SOURCES, "local://");
        added += scanDirForSubs(com.mintv.tv.data.StorageDir.SUB_UPLOAD, "upload://");
        // 声明了 exports.list 的脚本自动变成订阅源 ——
        // 用户把 .js 丢进 scripts/ 就能在订阅列表里看到，不用手动加
        // http://127.0.0.1:18899/xxx.m3u 这种地址。
        scanScriptsForSubs();
        if (added > 0) {
            android.util.Log.w("MinTV", "本次新增 " + added + " 个本地订阅");
            osd.toast("发现 " + added + " 个本地源");
        }
    }

    /**
     * 把声明了 {@code exports.list} 的脚本注册成订阅源。
     *
     * <p>约定：脚本导出 {@code exports.list = "/xxx.m3u"} 就说明它能提供
     * 完整清单，框架把它当成一个订阅。没声明的脚本不注册 —— 不是每个
     * 脚本都需要对外给清单。
     *
     * <p>订阅 id 用 {@code script:<文件名>}，跟用户手动加的订阅区分开；
     * URL 指向本机 HTTP 服务，端口变了会自动更新。
     */
    private void scanScriptsForSubs() {
        try {
            com.mintv.tv.data.ScriptStore store = new com.mintv.tv.data.ScriptStore(this);
            java.util.List<com.mintv.tv.data.ScriptStore.Item> items = store.list();
            if (items == null || items.isEmpty()) return;
            int port = remotePort > 0
                    ? remotePort : com.mintv.tv.data.RemoteServer.PORT_MAIN;
            // 先清理「脚本已经不在」的订阅。
            //
            // 脚本订阅是自动注册的，但删掉脚本文件之后订阅记录不会自己消失：
            // 界面里还留着一行，点进去 404，而且因为脚本没了也扫不回来。
            // 这里按现存脚本名做一次对账。
            java.util.Set<String> alive = new java.util.HashSet<>();
            for (com.mintv.tv.data.ScriptStore.Item it : items) alive.add("script:" + it.name);
            java.util.List<com.mintv.tv.data.SubscriptionStore.Sub> all =
                    com.mintv.tv.data.SubscriptionStore.list(this);
            boolean purged = false;
            if (all != null) {
                for (com.mintv.tv.data.SubscriptionStore.Sub sb : all) {
                    if (sb.id == null || !sb.id.startsWith("script:")) continue;
                    if (alive.contains(sb.id)) continue;
                    com.mintv.tv.data.SubscriptionStore.remove(this, sb.id);
                    android.util.Log.w("MinTV", "清理失效脚本订阅: " + sb.id);
                    purged = true;
                }
            }
            if (purged) fillSubColumn();

            java.util.Set<String> dismissed = dismissedSubs();
            int added = 0;
            for (com.mintv.tv.data.ScriptStore.Item it : items) {
                if (it.listPath == null || it.listPath.isEmpty()) continue;
                String id = "script:" + it.name;
                if (dismissed.contains(id)) continue;   // 用户手动删过，别再塞回来
                String url = "http://127.0.0.1:" + port + it.listPath;
                com.mintv.tv.data.SubscriptionStore.Sub existing =
                        com.mintv.tv.data.SubscriptionStore.find(this, id);
                if (existing != null && url.equals(existing.url)) continue;
                com.mintv.tv.data.SubscriptionStore.Sub sub =
                        new com.mintv.tv.data.SubscriptionStore.Sub();
                sub.id = id;
                sub.name = (it.displayName != null && !it.displayName.isEmpty())
                        ? it.displayName : it.name;
                sub.url = url;
                com.mintv.tv.data.SubscriptionStore.upsert(this, sub);
                if (existing == null) added++;
                android.util.Log.w("MinTV", "脚本源已注册: " + sub.name + " → " + url);
            }
            if (added > 0) {
                fillSubColumn();
                android.util.Log.w("MinTV", "本次新增 " + added + " 个脚本订阅");
            }
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "扫脚本源失败", t);
        }
    }

    /**
     * 只重绑每行的文案，不重建弹窗。
     *
     * <p>授权返回后必须调一次，否则「存储目录」那行还显示旧值 ——
     * 之前用 showTab() 刷新是无效的，它只切可见性，不重绑数据。
     */
    /**
     * 真正切换源力加速。
     *
     * <p>开启：立刻检测一次网络，之后每 30 分钟重测（网络可能换）。
     * <p>关闭：停掉定时器，什么都不做。
     */
    private void applyP2pBoost(final boolean on) {
        selections[ROW_P2P_BOOST] = on ? 1 : 0;
        saveSelections();
        refreshSettingRows();

        ui.removeCallbacks(netCheckLoop);
        if (!on) {
            // 关掉就把引擎停干净：停心跳、通知 tracker 离开、清缓存。
            // 只停定时检测而留着心跳，等于"关了还在上传"。
            try {
                com.mintv.tv.data.P2PEngine.get(getApplicationContext()).stop();
            } catch (Throwable t) {
            }
            osd.toast("源力加速已关闭");
            return;
        }
        osd.toast("源力加速已开启");
        netCheckLoop.run();
    }

    /** 定时检测：开关打开时每 30 分钟跑一次。 */
    private final Runnable netCheckLoop = new Runnable() {
        @Override public void run() {
            if (selections[ROW_P2P_BOOST] != 1) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    final com.mintv.tv.data.NetCheck.Result r =
                            com.mintv.tv.data.NetCheck.run();
                    lastNatResult = r;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            // 副标题要跟着变（显示网络类型），
                            // 不重绑的话用户得切页面再回来才看得到
                            refreshSettingRows();
                            if (selections[ROW_P2P_BOOST] == 1) {
                                ui.postDelayed(netCheckLoop, 30 * 60 * 1000L);
                            }
                        }
                    });
                }
            }, "net-check-periodic").start();
        }
    };

    private void refreshSettingRows() {
        if (rowViews == null) return;
        for (int i = 0; i < rowViews.size() && i < DemoData.ROW_TITLES.length; i++) {
            try {
                rowViews.get(i).bind(DemoData.ROW_TITLES[i][0], hintFor(i),
                        optionLabelsFor(i), selections[i]);
            } catch (Throwable t) {
                android.util.Log.w("MinTV", "重绑设置行 " + i + " 失败", t);
            }
        }
    }

    /**
     * 读本地订阅内容。
     *
     * <p>两种前缀：
     * <ul>
     *   <li>{@code local://}  → 存储目录的 sources/（用户自己丢进去的）</li>
     *   <li>{@code upload://} → 存储目录的 upload/（手机后台上传的）</li>
     * </ul>
     */
    private String readLocalSource(String url) {
        try {
            String name;
            String sub;
            if (url.startsWith("upload://")) {
                name = url.substring("upload://".length());
                sub = com.mintv.tv.data.StorageDir.SUB_UPLOAD;
            } else if (url.startsWith("local://")) {
                name = url.substring("local://".length());
                sub = com.mintv.tv.data.StorageDir.SUB_SOURCES;
            } else {
                return null;
            }
            if (name.isEmpty() || name.contains("..") || name.contains("/") || name.contains("\\")) {
                return null;
            }
            return com.mintv.tv.data.StorageDir.read(this, sub, name);
        } catch (Throwable t) {
            return null;
        }
    }

    private void fetchSourceAsync(final String url) {
        // 本地源：不联网，直接读文件，走完同样的解析和落库流程
        if (url != null && url.startsWith("local://")) {
            final String body = readLocalSource(url);
            ui.post(new Runnable() {
                @Override public void run() {
                    if (body == null || body.trim().isEmpty()) {
                        if (osd != null) osd.toast("本地源读不到内容");
                        return;
                    }
                    applyPlaylist(body);
                    SourceStore.saveBody(MainActivity.this, body, null, 0);
                    if (osd != null) osd.toast("已加载本地源：" + channels.size() + " 个频道");
                }
            });
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                // 统一走 Http：结构不对（HTML/JSON）在读的时候就中止
                java.util.Map<String, String> hdrs =
                        com.mintv.tv.ui.player.HttpHeaderProfile.merge(
                                globalHeaders, headersOfActiveSub());

                String etag = SourceStore.etag(MainActivity.this);
                long lastMod = SourceStore.lastModified(MainActivity.this);
                java.util.Map<String, String> withCache = hdrs;
                if (etag != null && !etag.isEmpty()) {
                    withCache = new java.util.LinkedHashMap<>(hdrs);
                    withCache.put("If-None-Match", etag);
                }
                if (lastMod > 0) {
                    if (withCache == hdrs) withCache = new java.util.LinkedHashMap<>(hdrs);
                    withCache.put("If-Modified-Since",
                            new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                                    java.util.Locale.US).format(new java.util.Date(lastMod)));
                }

                com.mintv.tv.data.Http.Result r = com.mintv.tv.data.Http.get(
                        url, withCache, 10000, 15000, true);
                if (r.code == 304) {
                    SourceStore.touch(MainActivity.this);
                    return;
                }
                final String body = r.body;
                final String err = r.error;
                final String lastEtag = r.etag;
                final long lastModified = r.lastModified;

                if (body == null) {
                    android.util.Log.w("MinTV", "拉源失败：" + err);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (osd != null) {
                                osd.toast(friendlySourceError(err));
                            }
                        }
                    });
                    return;
                }
                if (body.trim().isEmpty()) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (osd != null) osd.toast("源内容为空");
                        }
                    });
                    return;
                }
                SourceStore.saveBody(MainActivity.this, body, lastEtag, lastModified);
                ui.post(new Runnable() {
                    @Override public void run() {
                        applyPlaylist(body);
                        // 记下这个订阅解析出多少频道，手机助手页要显示"839 个频道"。
                        // applyPlaylist 之后 playlist 已经是新解析的结果。
                        com.mintv.tv.data.SubscriptionStore.Sub act =
                                com.mintv.tv.data.SubscriptionStore.active(MainActivity.this);
                        if (act != null && playlist != null && playlist.all != null) {
                            act.channelCount = playlist.all.size();
                            com.mintv.tv.data.SubscriptionStore.update(MainActivity.this, act);
                        }
                        rebuildCategoryColumn();
                        bindChannels();
                        updateChannelFocus(0);
                        // 源拉回来了、列表整个重建过，恢复目标要重新应用一次。
                        // 用户已经自己点过就自动失效（那时 restoreChanKey 已被清空）。
                        if (restoreChanKey != null) {
                            restoreDone = false;
                            tryRestoreIfPending();
                        }
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

                subItemCount = 0;

                // ---- 第二段：分类 ----
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
                focus.setNavCount(navViews.size());
                updateNavFocus(Math.min(focus.navIndex(), Math.max(0, navViews.size() - 1)));

                fillSubColumn();
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

    private static final int POOL_EXTRA_ROWS = 8;   // 视口外再备 8 行，快速滑动不露白

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
            showChannelEmptyHint();
            return;
        }

        int total = channels.size();
        int itemH = rowH();

        // 撑高用一个隐形 spacer，**不要**去改 chanColumn 自己的 LayoutParams。
        // 行池里每个 View 都是 topMargin 绝对定位，容器的 wrap_content 高度
        // 只由这个 spacer 决定；这样 ScrollView 一定量到正确的内容高度，
        // 也避免容器被重新包装后 setLayoutParams 不生效（之前就是这里让列表滚不动）。
        int contentH = total * itemH + Theme.px(20);
        // 容器高度交回 wrap_content 决定（由下面的 spacer 撑），
        // 防止上一次分类留下的固定高度残留。
        ViewGroup.LayoutParams clp = chanColumn.getLayoutParams();
        if (clp != null && clp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            clp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            chanColumn.setLayoutParams(clp);
        }
        View spacer = new View(this);
        chanColumn.addView(spacer, new FrameLayout.LayoutParams(1, contentH));
        android.util.Log.i("MinTV", "chan list content=" + contentH
                + " rows=" + total + " itemH=" + itemH
                + " viewport=" + (chanScroll == null ? -1 : chanScroll.getHeight()));

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
        boolean changed = force;

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
            // 已经绑在这个台上就别重绑。滚动一行时只有一格是真的换了内容，
            // 全量重绑会让 11 个 View 一起 invalidate、台标一起闪，看起来一抖一抖。
            if (!force && chanBind.get(k) == idx) continue;
            chanBind.set(k, idx);
            v.setTag(idx);
            bindRow(v, idx);
            changed = true;
        }
        // 副标题和台标只在换绑时刷，滚动过程中不重复跑
        if (changed) {
            refreshChannelSubs();
            requestLogosForPool();
            prefetchLogosAround(first);
        }
    }

    /** 把一个池化的行绑到具体频道。 */
    private void bindRow(ChannelView v, int idx) {
        PlaylistParser.Channel ch = channels.get(idx);
        v.setNumber(ch.number);
        v.setName(ch.name);
        v.setFavorite(isFavorite(ch));
        // 台标：先从内存缓存直接给，别先 setLogo(null) 再等异步回来 ——
        // 那样每次重绑都会闪一下空图标，滚动时特别明显。
        Bitmap cached = (ch.logo == null || ch.logo.isEmpty()) ? null : logos.peek(ch.logo);
        v.setLogo(cached);
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
                // 必须当场同步，不能丢给延迟任务。
                // 延迟任务在滚动过程中会被不断"再推迟 16ms"，等于整段滚动期间
                // 行池停在原位、跟着内容一起被推上去，下方就露白；
                // 松手才一次性补回来 —— 用户看到的就是"拖动不加载、突然又出来"。
                // 现在 syncPool 内部只有真正换绑的行才重绘，
                // 同步执行的开销就是改几个 topMargin，可以接受。
                syncPool(false);
                applyFocusToPool();
            }
        });
    }

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

    /**
     * 预先加载行池上下各若干行的台标。
     *
     * <p>只加载可见行的话，往下拖一屏就要等一次网络 —— 那就是"突然又加载出来"。
     * 提前把附近的图灌进内存缓存（现在缓存上限是 8MB，装得下），
     * 滚动到位时直接命中，图标不会跳。
     */
    private void prefetchLogosAround(int first) {
        if (channels == null || channels.isEmpty()) return;
        int from = Math.max(0, first - 10);
        int to = Math.min(channels.size(), first + chanViews.size() + 10);
        for (int i = from; i < to; i++) {
            PlaylistParser.Channel ch = channels.get(i);
            if (ch.logo == null || ch.logo.isEmpty()) continue;
            if (logos.peek(ch.logo) != null) continue;
            logos.load(ch.logo, new LogoLoader.Callback() {
                @Override public void onLogo(String url, Bitmap bmp) { }
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
    /** 订阅项是否等于当前激活订阅。 */
    private boolean isCurrentSub(List<NavItemView> ignored, int navIdx) {
        try {
            java.util.List<com.mintv.tv.data.SubscriptionStore.Sub> subs =
                    com.mintv.tv.data.SubscriptionStore.list(this);
            return navIdx >= 0 && navIdx < subs.size()
                    && subs.get(navIdx).id.equals(
                            com.mintv.tv.data.SubscriptionStore.activeId(this));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 点了订阅：切源，焦点留在订阅列不动。 */
    /**
     * 刷新最左侧订阅列的内容。
     *
     * <p>只在「显示订阅列」开启时存在容器，关闭时整个方法空转。
     */
    private void fillSubColumn() {
        if (subColumn == null) return;
        // 保留标题（第 0 个），清掉后面的项
        while (subColumn.getChildCount() > 1) {
            subColumn.removeViewAt(1);
        }

        java.util.List<com.mintv.tv.data.SubscriptionStore.Sub> subs =
                com.mintv.tv.data.SubscriptionStore.list(this);
        String activeId = com.mintv.tv.data.SubscriptionStore.activeId(this);

        if (subs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有订阅");
            empty.setTextColor(0xFF6E7683);
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(14));
            empty.setPadding(Theme.px(18), Theme.px(8), 0, 0);
            subColumn.addView(empty);
            return;
        }

        for (int i = 0; i < subs.size(); i++) {
            final com.mintv.tv.data.SubscriptionStore.Sub sub = subs.get(i);
            int cnt = channelCache.containsKey(sub.id) ? channelCache.get(sub.id).size() : 0;

            NavItemView v = new NavItemView(this);
            // 订阅列不画图标也不画频道数：这一列本来就窄，两个装饰一占，
            // 名字只能显示两三个字（"咪…"、"模…"），完全看不出是哪个源。
            // count 传 -1 表示不显示，glyph 传 0 表示不占位。
            v.bind(sub.name, -1, (char) 0);
            v.setActive(sub.id.equals(activeId));
            v.setClickable(true);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { onSubClicked(sub.id); }
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(Theme.NAV_ITEM_H));
            p.bottomMargin = Theme.px(2);
            subColumn.addView(v, p);
        }
    }

    /** 订阅列的焦点下标（0 = 第一个订阅）。 */
    private int subFocusIdx = 0;

    /** 从分类列进订阅列：焦点落在当前激活的订阅上。 */
    private void enterSubColumn() {
        java.util.List<com.mintv.tv.data.SubscriptionStore.Sub> subs =
                com.mintv.tv.data.SubscriptionStore.list(this);
        String active = com.mintv.tv.data.SubscriptionStore.activeId(this);
        subFocusIdx = 0;
        for (int i = 0; i < subs.size(); i++) {
            if (subs.get(i).id.equals(active)) { subFocusIdx = i; break; }
        }
        focus.setZone(FocusEngine.Zone.SUB);
        paintSubFocus();
    }

    /** 订阅列的按键：上下移动、右回分类、OK 切换。 */
    private boolean handleSubColumnKey(int keyCode) {
        java.util.List<com.mintv.tv.data.SubscriptionStore.Sub> subs =
                com.mintv.tv.data.SubscriptionStore.list(this);
        if (subs.isEmpty()) return false;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                subFocusIdx = (subFocusIdx - 1 + subs.size()) % subs.size();
                paintSubFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                subFocusIdx = (subFocusIdx + 1) % subs.size();
                paintSubFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                focus.setZone(FocusEngine.Zone.NAV);
                clearSubFocus();
                updateNavFocus(focus.navIndex());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                onSubClicked(subs.get(Math.min(subFocusIdx, subs.size() - 1)).id);
                return true;
            default:
                return false;
        }
    }

    /** 只把第 idx 个订阅画成焦点态，其余恢复。 */
    private void paintSubFocus() {
        if (subColumn == null) return;
        int navIdx = 0;
        for (int i = 1; i < subColumn.getChildCount(); i++) {
            View v = subColumn.getChildAt(i);
            if (!(v instanceof NavItemView)) continue;
            ((NavItemView) v).setFocused(navIdx == subFocusIdx, true);
            navIdx++;
        }
    }

    private void clearSubFocus() {
        if (subColumn == null) return;
        for (int i = 1; i < subColumn.getChildCount(); i++) {
            View v = subColumn.getChildAt(i);
            if (v instanceof NavItemView) ((NavItemView) v).setFocused(false, true);
        }
    }

    private void onSubClicked(String subId) {
        String cur = com.mintv.tv.data.SubscriptionStore.activeId(this);
        if (subId != null && subId.equals(cur)) return;      // 已经是它了
        switchSubscription(subId);
        // 注意：**不要在这里再 rebuildCategoryColumn()**。
        //
        // switchSubscription 内部已经重建过一次（缓存命中和联网两条路径都有），
        // 这里再来一遍等于把几百个 View 全销毁再造一次 —— 切订阅卡顿的主因。
        // 只补一下订阅列自己的红点/计数就行。
        fillSubColumn();
        focus.setNavIndexSilently(Math.min(subItemCount, Math.max(0, navViews.size() - 1)));
        updateNavFocus(focus.navIndex());
    }

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

    /**
     * 分类列的两种状态必须分开画，否则用户完全看不出区别：
     * <ul>
     *   <li><b>已生效的分类</b>（activeCategory）：始终有深色底；</li>
     *   <li><b>焦点</b>（focus.zone()==NAV 时）：额外的蓝色描边。</li>
     * </ul>
     * 以前两者都用 focus.navIndex() 画，焦点一移动，原来的分类底色就消失，
     * 看起来像"分类根本没切换" —— 其实焦点动了，只是没有视觉差别。
     */
    private void updateNavFocus(int index) {
        boolean navZone = focus.zone() == FocusEngine.Zone.NAV;
        for (int i = 0; i < navViews.size(); i++) {
            NavItemView v = navViews.get(i);
            // 前 subItemCount 个是订阅：用「当前订阅」判断高亮
            // 后面是分类：用 activeCategory（相对分类段的偏移）
            boolean active;
            if (i < subItemCount) {
                // 订阅项：拿 tag 里存的 id 和当前激活 id 比。
                // 不能按索引去重新读 list() —— 那次读的顺序可能和构建时不同，
                // 会导致"高亮跳到别的项上"（实际出现过：高亮落在「我的收藏」）。
                Object tag = v.getTag();
                active = tag != null && tag.equals(
                        com.mintv.tv.data.SubscriptionStore.activeId(this));
            } else {
                active = (i - subItemCount) == activeCategory;
            }
            v.setActive(active);
            v.setFocused(navZone && i == index, true);
        }
    }

    /** 上一次画了焦点框的下标，用来只重绘变化的两行。 */
    private int lastFocusedChan = -1;
    /** 已经生效的分类下标。焦点移走时它仍要保持高亮，否则用户看不出自己在哪个分类里。 */
    private int activeCategory = 0;
    private int lastSubsFocus = -1;

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
        if (lastSubsFocus != index) {
            lastSubsFocus = index;
            refreshChannelSubs();   // 徽标跟着焦点走
        }
        scrollChannelIntoView(index);
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
        // 新的一次播放，允许重新降级一次。
        // 但"降级后重播同一台"这条路径传 keepLine=true 且引擎已变，
        // 不能再无条件清零，否则会 Exo→VLC→Exo 死循环。
        // keepLine=true 表示"还是同一个频道的重开"（换线路/自愈），
        // 必须复用同一个 attempt —— 否则换线路后降级进度清零，
        // 会出现"换线路→无画面→又软解→又换线路"的循环。
        if (!keepLine) attempt = null;
        // 换台必然重新起播，暂停态要清掉；否则新频道一进来 OSD 就常驻了
        if (osd != null) osd.setPaused(false);
        if (index != playingChannel && playingChannel >= 0 && playingChannel < channels.size()) {
            lastChannelNum = channels.get(playingChannel).number;
        }
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
        attempt().onRestart();   // 重开一次播放：清掉卡顿采样，但保留降级进度
        lineTotal = ch.sources.size();
        replayStartMs = -1;
        // 台标：缓存命中就直接用，否则异步拉，回来再补上。
        // 先 setLogo(null) 是为了不留上一个台的图标 —— 换台瞬间
        // 显示上个台标比不显示更容易让人迷惑。
        if (ch.logo != null && !ch.logo.isEmpty()) {
            android.graphics.Bitmap cached = logos.peek(ch.logo);
            osd.setLogo(cached);
            if (cached == null) {
                final String logoUrl = ch.logo;
                logos.load(logoUrl, new LogoLoader.Callback() {
                    @Override public void onLogo(String url, android.graphics.Bitmap bmp) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (osd != null) osd.setLogo(bmp);
                            }
                        });
                    }
                });
            }
        } else {
            osd.setLogo(null);
        }
        osd.showChannel(no, ch.name, ch.group);
        // 换台时的大号台号去掉前导零：显示 9 而不是 0009
        osd.pulseChannelNumber(stripLeadingZeros(no));
        updateOsdInset();   // 顺便校准台号锚点（时钟可能刚完成布局）
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
        // 立刻告诉用户"已经在换了"。
        // 原来要等播放器的 Buffering 回调才显示状态，中间一两秒屏幕
        // 一动不动，手感就是"按了没反应"。
        osd.setStatus("加载中…");
        updateMulticastLock(src.url);
        preResolveDns(src.url);
        playUrlEntry(PlaylistParser.cleanUrl(src.url), src.headers, PlaybackBackend.Mode.LIVE);
    }

    private final Runnable playbackWatchdog = new Runnable() {
        @Override public void run() {
            com.mintv.tv.data.PlaybackAttempt.Action act = attempt().onStartupTimeout();
            if (act == com.mintv.tv.data.PlaybackAttempt.Action.NONE) return;
            if (act == com.mintv.tv.data.PlaybackAttempt.Action.SWITCH_SOURCE) {
                PlaylistParser.Channel ch = (playingChannel >= 0 && playingChannel < channels.size())
                        ? channels.get(playingChannel) : null;
                if (ch != null) {
                    currentLine = (currentLine + 1) % ch.sources.size();
                    osd.toast("该线路无响应，切换线路 " + (currentLine + 1) + "/" + ch.sources.size());
                    playChannelInternal(playingChannel, true);
                    return;
                }
            }
            if (act == com.mintv.tv.data.PlaybackAttempt.Action.RECONNECT) {
                scheduleReconnect();
                return;
            }
            osd.toast("该源无响应");
        }
    };
    /** 设置行下标（与 DemoData.ROW_TITLES 顺序一致） */
    // 顺序必须与 DemoData.ROW_TITLES 完全一致
    // 行号一律指向 Settings 的索引常量，不再自己写数字 ——
    // 否则加一个设置项就要同时改这里和 Settings，迟早对不上。
    private static final int ROW_SOURCE   = com.mintv.tv.ui.setting.Settings.IDX_SOURCE;
    private static final int ROW_RATIO    = com.mintv.tv.ui.setting.Settings.IDX_ASPECT;
    private static final int ROW_EPG_POS  = com.mintv.tv.ui.setting.Settings.IDX_EPG_POS;
    private static final int ROW_SWITCH_UI = com.mintv.tv.ui.setting.Settings.IDX_SWITCH_UI;
    private static final int ROW_REVERSE  = com.mintv.tv.ui.setting.Settings.IDX_REVERSE;
    private static final int ROW_OSD_STYLE= com.mintv.tv.ui.setting.Settings.IDX_SWITCH_UI;
    private static final int ROW_LEFTKEY  = com.mintv.tv.ui.setting.Settings.IDX_LEFT_KEY;
    private static final int ROW_SPEED    = com.mintv.tv.ui.setting.Settings.IDX_SPEED;
    private static final int ROW_AUTOPLAY = com.mintv.tv.ui.setting.Settings.IDX_AUTOSTART;
    private static final int ROW_DECODER  = com.mintv.tv.ui.setting.Settings.IDX_DECODER;
    /** 超时换源：0=关闭，1=5s，2=8s，3=12s，4=20s。开关和秒数合成一项了。 */
    private static final int ROW_TIMEOUT  = com.mintv.tv.ui.setting.Settings.IDX_TIMEOUT;
    private static final int ROW_HEADERS  = com.mintv.tv.ui.setting.Settings.IDX_HEADERS;
    private static final int ROW_CLOCK    = com.mintv.tv.ui.setting.Settings.IDX_CLOCK;
    private static final int ROW_OUTPUT   = com.mintv.tv.ui.setting.Settings.IDX_OUTPUT;
    private static final int ROW_THEME    = com.mintv.tv.ui.setting.Settings.IDX_THEME;
    private static final int ROW_GROUPMGR = com.mintv.tv.ui.setting.Settings.IDX_GROUP_MGR;

    private static final String KEY_UI_THEME    = "ui_theme";
    /** 强调色序号（0~9），见 Theme.ACCENT_COLORS。 */
    private static final String KEY_ACCENT      = "accent_color";
    private static final String KEY_SELECTIONS  = "selections";
    private static final String KEY_HIDDEN_CATS = "hidden_cats";
    private static final int ROW_GROUP_MGR= com.mintv.tv.ui.setting.Settings.IDX_GROUP_MGR;
    private static final int ROW_STORAGE_DIR = com.mintv.tv.ui.setting.Settings.IDX_STORAGE_DIR;
    private static final int ROW_P2P_BOOST  = com.mintv.tv.ui.setting.Settings.IDX_P2P_BOOST;
    /** 是否已经告知过源力加速的后果（只告知一次，之后静默切换）。 */
    private static final String KEY_P2P_ACKED = "p2p_acked";
    private static final int ROW_NET_PROXY = com.mintv.tv.ui.setting.Settings.IDX_NET_PROXY;
    private static final int ROW_SHOW_SUBS = com.mintv.tv.ui.setting.Settings.IDX_SHOW_SUBS;

    /**
     * 分组标题。索引必须和 Settings.ALL 的顺序对上。
     *
     * <p>用边界数组而不是给 SettingDef 加字段：分组是纯展示概念，
     * 塞进数据类会让"设置项语义"和"排版位置"耦合。
     * 代价是重排 Settings.ALL 时要同步这里。
     */
    private static final int[] GROUP_START = {0, 5, 10, 15, 17};
    private static final String[] GROUP_NAME = {"频道源", "播放", "界面", "操作", "其它"};


    /** 被用户隐藏的分类名（「全部」和「我的收藏」不允许隐藏）。 */
    private final java.util.LinkedHashSet<String> hiddenCats = new java.util.LinkedHashSet<>();

    /**
     * 「没画面」自愈的进度。0 = 还没处理，1 = 已切软解，2 = 已换过线路。
     *
     * <p>只在用户主动换台 / 换分类时清零。绝对不能放在 playChannel /
     * armWatchdog 里清零 —— 那是自愈自己调进去的，会被无限踢源。
     */


    /** 是否已切到软解。Exo 的硬解在低端盒子上「初始化成功但不出帧」很常见。 */

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
        ui.removeCallbacks(playbackWatchdog);

        com.mintv.tv.data.PlaybackAttempt.Action act = attempt().onNoVideo();
        PlaylistParser.Channel ch = (playingChannel >= 0 && playingChannel < channels.size())
                ? channels.get(playingChannel) : null;

        switch (act) {
            case RETRY_SOFT_DECODE:
                // 只影响本次尝试，不动用户设置
                playerManager.setDecoderMode(attempt().decoderMode());
                osd.toast("无画面，临时切软件解码重试");
                playChannelInternal(playingChannel, true);
                return;
            case SWITCH_SOURCE:
                if (ch != null && !ch.sources.isEmpty()) {
                    currentLine = (currentLine + 1) % ch.sources.size();
                    osd.toast("该线路无画面，切换线路 " + (currentLine + 1) + "/" + ch.sources.size());
                    playChannelInternal(playingChannel, true);
                    return;
                }
                break;
            default:
                break;
        }
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


    /** 首帧出来了 → 内核稳定，把标记清掉。 */
    private void clearEngineAttempt() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .remove(KEY_ENGINE_TRY).apply();
    }


    // ---- 每个频道上次能播的线路 ----
    private static final String KEY_LINE_PREFS = "line_prefs";
    private static final String KEY_FAVS = "favorites";
    private static final String KEY_LAST_CHAN = "last_chan";
    private static final String KEY_LAST_CAT = "last_cat";
    private static final String KEY_LAST_NUM = "last_num";

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
    /** 上次观看的台号（从 1 开始）。列表变了拿名字找不到时，用它就近兜底。 */
    private int restoreChanNum = -1;
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

    /** 启动时把"开机自启"的选择读回来，并同步给接收器。 */
    /**
     * 开机自启的说明弹窗。
     *
     * <p>Android 10+ 明确禁止后台启动 Activity，广播里 startActivity 会被静默丢弃，
     * 这不是 App 能绕过的限制。系统里唯一可靠的「开机进入某个应用」的办法是
     * **把它设为默认桌面（Launcher）** —— 本项目 Manifest 里已经带了
     * LEANBACK_LAUNCHER / LAUNCHER，可以被设为默认桌面。
     */
    private void showAutoStartGuide() {
        showSystemDialog(new android.app.AlertDialog.Builder(this)
                .setTitle("开机自启说明")
                .setMessage("Android 4.4~9：打开后开机自动进入，无需额外设置。\n\n"
                        + "Android 10 及以上：系统禁止应用在后台自行启动界面。"
                        + "想让开机直接进入本应用，最可靠的办法是把它设为【默认桌面】：\n"
                        + "系统设置 → 应用 → 默认应用 → 主屏幕应用 → 选择 MinTV\n\n"
                        + "部分手机还需要在系统管家/权限管理里允许「自启动」「后台弹出界面」。")
                .setPositiveButton("去设置", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            startActivity(new android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS));
                        } catch (Throwable t) {
                            try {
                                startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS));
                            } catch (Throwable ignored) { }
                        }
                    }
                })
                .setNegativeButton("知道了", null)
                );
    }

    private void loadAutoPlayPref() {
        android.content.SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        // 老 key 迁移：以前叫 boot_autoplay
        boolean on = p.getBoolean(KEY_BOOT_AUTOSTART, p.getBoolean("boot_autoplay", false));
        selections[ROW_AUTOPLAY] = on ? 0 : 1;
        if (rowViews.size() > ROW_AUTOPLAY) {
            rowViews.get(ROW_AUTOPLAY).setSelected(selections[ROW_AUTOPLAY]);
        }
        saveAutoStartFlag();
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

    /**
     * 收藏分类跟着增减：0 个时整项消失，从 0 变 1 时立刻出现。
     *
     * <p>原来这里只「更新已有收藏分类的数字」——循环找不到就直接返回，
     * 所以从来没收藏过的人收藏第一个台之后，分类列表里什么都不会冒出来；
     * 反过来取消到 0 时，那一项还留着显示「我的收藏 0」。
     *
     * <p>现在改成完整重建：rebuildCategories() 里按「当前订阅里实际存在的
     * 收藏数」决定要不要这一项，自然是双向都对的。
     *
     * <p>重建后分类下标可能整体位移（收藏项在最前面，增删会让其他项前移/
     * 后移），所以按**名字**找回焦点，而不是沿用旧下标。
     */
    private void refreshFavCategory() {
        if (categories == null) return;
        String cur = currentCategoryName();
        rebuildCategories();

        int ni = indexOfCategory(cur);
        boolean gone = ni < 0;          // 当前分类被删掉了（收藏清空时会发生）
        if (gone) ni = defaultCategoryIndex();

        activeCategory = ni;
        rebuildCategoryColumn();
        focus.setNavIndexSilently(ni);
        updateNavFocus(ni);

        if (gone) {
            // 站着的分类没了，必须把内容也换过去，否则列表还停在旧数据
            applyCategoryChannels(ni);
            updateChannelFocus(0);
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
        // playlist 可能为 null —— 删完所有订阅时会置空（confirmDeleteSub 里），
        // 之后如果用户还能点到分类，这里直接 NPE。manageableCategories()
        // 早就有这个判断，这里漏了。
        if (playlist == null || playlist.all == null) return;
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
        // 三样一起记：台名（最稳的身份）、分类、台号（名字变了就用它兜底）。
        // 只记频道的下标是完全不行的 —— 源一更新，整份列表的顺序就变了。
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_LAST_CHAN, favKey(ch))
                .putString(KEY_LAST_CAT, currentCategoryName())
                .putInt(KEY_LAST_NUM, ch.number)
                .apply();
    }

    /**
     * 找"上次那个台"：**以台号为锚点由近及远搜，名字匹配上才算命中**。
     *
     * <p>为什么是这个顺序：
     * <ul>
     *   <li>源每天更新，频道会增删，但**顺序基本稳定** —— 所以上次的台号
     *       仍然是位置的最佳估计，"附近"大概率就是它；</li>
     *   <li>光看台号不安全：中间要是插了一个台，台号就整体错位了。
     *       所以每个候选都必须**名字对得上**才认，避免点开一个不相干的台；</li>
     *   <li>由近及远一圈圈扩，命中的第一个就是最近的同名台 ——
     *       列表小幅变动（插 1~2 个台）时一次就中。</li>
     * </ul>
     *
     * <p>名称对不上名字时，才做全表的包含匹配（源里常给台名加前后缀），
     * 最后实在没有就退回锚点位置本身（夹紧到有效范围），至少不跑到第一台。
     *
     * @param key       形如 "76|浙江钱江台"
     * @param anchorNum 上次的台号，从 1 开始
     */
    private int resolveChannelIndex(String key, int anchorNum) {
        if (channels == null || channels.isEmpty()) return -1;
        String name = key;
        int bar = key.indexOf('|');
        if (bar >= 0 && bar + 1 < key.length()) name = key.substring(bar + 1);

        int n = channels.size();
        int anchor = anchorNum > 0 ? anchorNum - 1 : 0;
        if (anchor < 0) anchor = 0;
        if (anchor >= n) anchor = n - 1;

        // ① 以锚点为中心向两边扩散，第一个同名即为最近命中
        for (int r = 0; r < n; r++) {
            int lo = anchor - r;
            int hi = anchor + r;
            if (lo >= 0 && lo < n && channels.get(lo).name.equals(name)) return lo;
            if (r != 0 && hi >= 0 && hi < n && channels.get(hi).name.equals(name)) return hi;
        }
        // ② 名字被改过（加了 HD / 后缀）：全表包含匹配
        if (name.length() >= 2) {
            for (int i = 0; i < n; i++) {
                String cn = channels.get(i).name;
                if (cn.contains(name) || name.contains(cn)) return i;
            }
        }
        // ③ 这条台没了：停在原来的位置附近
        return anchor;
    }

    /**
     * 默认选中哪个分类。
     *
     * <p>优先「全部」——那是「所有频道」的完整视图。只有 1 个分组时
     * 0.3.62 会把冗余的「全部」省掉，此时退回第一个分类。
     *
     * <p>不能直接写 activeCategory = 0：分类列表是动态的，
     * 索引 0 今天可能是「全部」，明天可能是「我的收藏」。
     */
    private int defaultCategoryIndex() {
        int all = indexOfCategory("\u5168\u90e8");
        return all >= 0 ? all : 0;
    }

    private int indexOfCategory(String name) {
        if (categories == null || name == null) return -1;
        for (int i = 0; i < categories.size(); i++) {
            if (name.equals(categories.get(i).name)) return i;
        }
        return -1;
    }

    /** 恢复只做一次：成功定位后就不再碰滚动位置，避免和用户滚动打架。 */
    private boolean restoreDone = false;

    private void tryRestoreIfPending() {
        if (restoreDone) return;
        if (restoreChanKey == null || restoreChanKey.isEmpty()) { restoreDone = true; return; }
        if (categories == null || categories.isEmpty() || channels == null) return;

        int ci = indexOfCategory(restoreCatName);
        if (ci >= 0 && !restoreCatName.equals(currentCategoryName())) {
            focus.setNavIndexSilently(ci);
            switchCategory(ci);
            updateNavFocus(ci);
        }

        int idx = resolveChannelIndex(restoreChanKey, restoreChanNum);
        if (idx < 0) { restoreDone = true; return; }

        restoreDone = true;          // 先置位，后面的滚动/渲染不要再被打断
        final int prev = playingChannel;
        playingChannel = idx;
        focus.setChannelCount(channels.size());
        focus.resetChannelFocus();
        // 焦点下标也要跟着落到目标台，否则列表定位对了、蓝框还在第一行。
        focus.setChanIndexSilently(idx);

        final int target = idx;
        if (chanScroll != null) {
            // 直接定位到目标行，再重绑行池。用 scrollTo 而不是 smoothScroll，
            // 一次性到位，不会留下正在跑的滚动动画。
            int top = listTopPx() + target * rowH() - Theme.px(160);
            chanScroll.scrollTo(0, Math.max(0, top));
            chanScroll.post(new Runnable() {
                @Override public void run() {
                    syncPool(true);
                    updateChannelFocus(target);
                }
            });
        }
        if (prev != idx && currentPlayUrl != null) {
            playChannelInternal(idx, false);
        }
    }

    /** 用户自己动手了，启动恢复目标作废。 */
    private void clearRestorePending() {
        restoreChanKey = null;
        restoreCatName = null;
        restoreDone = true;
    }

    private void restoreLastChannel() {
        android.content.SharedPreferences p = getSharedPreferences(PREF, MODE_PRIVATE);
        restoreChanKey = p.getString(KEY_LAST_CHAN, null);
        restoreCatName = p.getString(KEY_LAST_CAT, null);
        restoreChanNum = p.getInt(KEY_LAST_NUM, -1);
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

    private static final String KEY_BOOT_AUTOSTART = "boot_autostart";

    /**
     * 「开机自启」是否开启（设置行 0=开启 1=关闭）。
     *
     * <p>注意它**只管自启**：应用被打开后一定自动播放上次的频道，
     * 这个不再受开关控制（原来那项叫"开机自动播放"，语义混乱 ——
     * 用户想关的通常是"开机别自己跳出来"，不是"打开后别播"）。
     */
    private boolean autoStartOnBoot() {
        return selections[ROW_AUTOPLAY] == 0;
    }

    /** 把开关同步给 BootReceiver 用（它读同一个 SP）。 */
    private void saveAutoStartFlag() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putBoolean(KEY_BOOT_AUTOSTART, autoStartOnBoot()).apply();
    }

    /**
     * 设置项持久化。
     *
     * <p>以前这些选择只活在内存里，重启就回默认值 —— 用户改过解码方式、
     * 超时秒数，下次打开又变回去。主题切换要 recreate()，不存就会把
     * 其它设置一起冲掉，所以顺手把这块补齐。
     */
    private void loadSelections() {
        // 优先读存储目录里的 settings.json。
        String raw = com.mintv.tv.data.SettingsStore.getString(this, "selections", null);
        if (raw == null || raw.isEmpty()) {
            // 文件里没有 —— 说明还没迁移过（或者用户手动删了文件）。
            // 从旧的 SharedPreferences 读一次，顺手写进文件。
            String legacy = getSharedPreferences(PREF, MODE_PRIVATE)
                    .getString(KEY_SELECTIONS, null);
            if (legacy != null && !legacy.isEmpty()) {
                com.mintv.tv.data.SettingsStore.putString(this, "selections", legacy);
                raw = legacy;
                android.util.Log.i("MinTV", "设置已迁移到 config/settings.json");
            }
        }
        if (raw == null || raw.isEmpty()) return;

        // 新格式：id=value;id=value
        if (raw.indexOf('=') >= 0) {
            for (String kv : raw.split(";")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String id = kv.substring(0, eq);
                int v;
                try { v = Integer.parseInt(kv.substring(eq + 1).trim()); }
                catch (NumberFormatException e) { continue; }
                for (int i = 0; i < com.mintv.tv.ui.setting.Settings.ALL.length; i++) {
                    if (com.mintv.tv.ui.setting.Settings.ALL[i].id.equals(id)) {
                        selections[i] = v;
                        break;
                    }
                }
            }
            return;
        }

        // 旧格式：逗号分隔的定长串。
        //
        // 设置表重排过，旧的行号已经对不上新的语义 —— 硬按位置套用
        // 会让"解码方式"的值跑到"画面比例"上，比丢失更糟。
        // 所以直接丢弃，全部回到默认值，并告诉用户一次。
        android.util.Log.w("MinTV", "设置格式过旧（按行号存），已重置为默认值");
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .remove(KEY_SELECTIONS).apply();
        ui.post(new Runnable() {
            @Override public void run() {
                if (osd != null) osd.toast("设置项已重排，旧配置已重置为默认");
            }
        });
    }

    /**
     * 存设置。
     *
     * <p><b>按 id 存，不按行号</b>。以前存的是 "0,0,2,1,..." 这样的定长串，
     * 行号一变（重排设置项）用户所有设置就整体错位 —— 比如"解码方式"
     * 的值跑到"画面比例"上。改成 id=value 之后重排是安全的。
     */
    private void saveSelections() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selections.length && i < com.mintv.tv.ui.setting.Settings.ALL.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(com.mintv.tv.ui.setting.Settings.ALL[i].id)
              .append('=').append(selections[i]);
        }
        // 写进存储目录，文件管理器可见、可备份。
        // SharedPreferences 那份不再更新 —— 迁移只在首次读取时发生一次。
        com.mintv.tv.data.SettingsStore.putString(this, "selections", sb.toString());
    }

    /**
     * 重新生成左侧分类列表。
     *
     * <p>顺序：「我的收藏」置顶 → 「全部」→ 源里的各个分组。
     * 用户隐藏的分组整条去掉；「全部」和「我的收藏」不允许隐藏，
     * 否则隐藏完最后一个分组就再也没法从界面里找回频道了。
     */
    private void rebuildCategories() {
        List<DemoData.Category> all = DemoData.toCategories(playlist);
        // 收藏计数只算「当前订阅里真实存在的」。
        //
        // favorites 是全局的（按「频道名|地址」的 hash 存），换订阅之后
        // 之前收藏的台可能不在这份列表里。原来直接用 favorites.size()，
        // 结果就是「我的收藏 1」点进去空空如也 —— 那个 1 是别的订阅里的台。
        int favHits = 0;
        if (playlist != null && playlist.all != null) {
            for (PlaylistParser.Channel ch : playlist.all) {
                if (favorites.contains(favKey(ch))) favHits++;
            }
        }
        // 一个都对不上就不显示这一项，不然它只是个空壳。
        if (!all.isEmpty() && !FAV_CATEGORY.equals(all.get(0).name) && favHits > 0) {
            all.add(0, new DemoData.Category(FAV_CATEGORY, favHits, 0));
        }
        List<DemoData.Category> keep = new ArrayList<>();
        for (DemoData.Category c : all) {
            if (FAV_CATEGORY.equals(c.name) || "全部".equals(c.name)
                    || !hiddenCats.contains(c.name)) {
                keep.add(c);
            }
        }
        categories = keep;
    }

    /**
     * 运行时开关订阅列，不重建 Activity。
     *
     * <p>做三件事：加/删 View、重新算焦点区、刷新分类列表。
     * 播放器完全不动 —— 正在看的节目不受影响。
     */
    private void applyShowSubs(boolean show) {
        if (colsRow == null) return;
        try {
            if (show) {
                if (subColumn != null) return;      // 已经在了
                int subW = Theme.px(240);

                LinearLayout subCol = new LinearLayout(this);
                subCol.setOrientation(LinearLayout.VERTICAL);
                subCol.setPadding(0, Theme.px(26), 0, 0);
                subCol.setBackgroundColor(0x66000000);

                TextView hd = new TextView(this);
                hd.setText("订阅");
                hd.setTextColor(Theme.TEXT);
                hd.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        Theme.sp(Theme.TS_NAV_TITLE));
                hd.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                hd.setPadding(Theme.px(18), 0, 0, Theme.px(18));
                subCol.addView(hd);

                subColumn = subCol;
                colsRow.addView(subCol, 0, new LinearLayout.LayoutParams(
                        subW, ViewGroup.LayoutParams.MATCH_PARENT));

                View line = new View(this);
                line.setBackgroundColor(Theme.LINE);
                subLineView = line;
                colsRow.addView(line, 1, new LinearLayout.LayoutParams(
                        Theme.px(1), ViewGroup.LayoutParams.MATCH_PARENT));

                fillSubColumn();
                rebuildCategoryColumn();
            } else {
                if (subColumn == null) return;      // 已经没了
                colsRow.removeView(subColumn);
                subColumn = null;
                if (subLineView != null) {
                    colsRow.removeView(subLineView);
                    subLineView = null;
                }
                rebuildCategoryColumn();
            }
            // 列数变了，焦点区要重算，否则左右键会跳到不存在的列
            focus.setZone(FocusEngine.Zone.NAV);
            focus.setNavIndexSilently(0);
            updateNavFocus(0);
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "切换订阅列失败", t);
        }
    }

    /** 可隐藏的全部分类名（用于管理弹窗），按源里的顺序。 */
    private java.util.List<String> manageableCategories() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (playlist == null) return out;
        for (String g : playlist.byGroup.keySet()) {
            if (!FAV_CATEGORY.equals(g) && !"全部".equals(g)) out.add(g);
        }
        return out;
    }

    /**
     * 频道分组管理弹窗。
     *
     * <p>源里动辄二十几个分组（央视、各省、港澳台…），用户实际只看其中几个。
     * 这里把所有分组列出来，点一下切换显示/隐藏，关掉后左侧分类栏立刻重建。
     *
     * <p>「全部」和「我的收藏」不在列表里 —— 它们不允许被隐藏，
     * 否则隐藏光了就再也找不回频道入口。
     */
    private void openGroupManager() {
        final java.util.List<String> names = manageableCategories();
        if (names.isEmpty()) { osd.toast("当前源没有可管理的分组"); return; }

        final int cols = 3;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(Theme.px(20), Theme.px(16), Theme.px(20), Theme.px(8));

        final java.util.List<TextView> chips = new java.util.ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < names.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final String name = names.get(i);
            final TextView chip = new TextView(this);
            chip.setText(chipLabel(name));
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(19));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Theme.px(10), Theme.px(14), Theme.px(10), Theme.px(14));
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setTextColor(Theme.TEXT);
            styleChip(chip, !hiddenCats.contains(name));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (hiddenCats.contains(name)) hiddenCats.remove(name);
                    else hiddenCats.add(name);
                    saveHiddenCats();
                    chip.setText(chipLabel(name));
                    styleChip(chip, !hiddenCats.contains(name));
                }
            });
            chips.add(chip);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Theme.px(8);
            lp.bottomMargin = Theme.px(8);
            row.addView(chip, lp);
        }

        ScrollView sc = new ScrollView(this);
        sc.addView(container, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 只留一个「反选」。
        // 默认是全选，所以"全选"是废按钮；"全部隐藏"反选一次就能得到同样结果，
        // 两个都留着只是把按钮数量堆起来。反选一次搞定两者。
        LinearLayout bulk = new LinearLayout(this);
        bulk.setOrientation(LinearLayout.HORIZONTAL);
        bulk.setPadding(Theme.px(20), Theme.px(8), Theme.px(20), 0);

        bulk.addView(makeBulkButton("反选", new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (String n : names) {
                    if (!hiddenCats.remove(n)) hiddenCats.add(n);
                }
                saveHiddenCats();
                refreshChips(chips, names);
            }
        }), bulkParams());

        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.addView(bulk);
        dialogRoot.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        showSystemDialog(new android.app.AlertDialog.Builder(this)
                .setTitle("频道分组管理（点一下切换显示/隐藏）")
                .setView(dialogRoot)
                .setPositiveButton("关闭", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        applyGroupChanges();
                    }
                })
                .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override public void onCancel(android.content.DialogInterface d) {
                        applyGroupChanges();
                    }
                })
                );
    }

    /** 批量操作后统一刷新所有分组的显示状态。 */
    private void refreshChips(java.util.List<TextView> chips, java.util.List<String> names) {
        for (int i = 0; i < chips.size() && i < names.size(); i++) {
            String n = names.get(i);
            chips.get(i).setText(chipLabel(n));
            styleChip(chips.get(i), !hiddenCats.contains(n));
        }
    }

    private TextView makeBulkButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(18));
        b.setGravity(Gravity.CENTER);
        b.setPadding(Theme.px(18), Theme.px(10), Theme.px(18), Theme.px(10));
        b.setBackgroundColor(Theme.DLG_OPT_BG);
        b.setTextColor(Theme.TEXT);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams bulkParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.rightMargin = Theme.px(8);
        return p;
    }

    private String chipLabel(String name) {
        return hiddenCats.contains(name) ? (name + "  ⊘") : (name + "  ✓");
    }

    private void styleChip(TextView chip, boolean visible) {
        chip.setBackgroundColor(visible ? Theme.DLG_OPT_SEL : Theme.DLG_OPT_BG);
        chip.setTextColor(visible ? Theme.DLG_OPT_TXT_ON : Theme.TEXT_DIM);
    }

    /** 管理弹窗关掉后重建左侧分类栏。 */
    private void applyGroupChanges() {
        rebuildCategories();
        rebuildCategoryColumn();
        int nav = Math.min(focus.navIndex(), Math.max(0, categories.size() - 1));
        focus.setNavCount(navViews.size());
        focus.setNavIndexSilently(nav);
        updateNavFocus(nav);
        switchCategory(nav);
        osd.toast("已更新分组显示");
    }

    private void loadHiddenCats() {
        hiddenCats.clear();
        // 跟着存储根走：未授权在私有目录，授权后在 <授权根>/hidden_cats.txt
        String raw = com.mintv.tv.data.StorageDir.readPath(this, com.mintv.tv.data.StorageDir.SUB_CONFIG + "/hidden_cats.txt");
        if (raw == null || raw.isEmpty()) {
            // 一次性迁移：老版本存在 SharedPreferences
            String legacy = getSharedPreferences(PREF, MODE_PRIVATE)
                    .getString(KEY_HIDDEN_CATS, "");
            if (legacy != null && !legacy.isEmpty()) {
                raw = legacy;
                com.mintv.tv.data.StorageDir.writePath(this, com.mintv.tv.data.StorageDir.SUB_CONFIG + "/hidden_cats.txt", legacy);
            }
        }
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) hiddenCats.add(line.trim());
        }
    }

    private void saveHiddenCats() {
        StringBuilder sb = new StringBuilder();
        for (String n : hiddenCats) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(n);
        }
        com.mintv.tv.data.StorageDir.writePath(this, com.mintv.tv.data.StorageDir.SUB_CONFIG + "/hidden_cats.txt", sb.toString());
    }

    private void loadHeaders() {
        String raw = com.mintv.tv.data.StorageDir.readPath(this, com.mintv.tv.data.StorageDir.SUB_CONFIG + "/headers.txt");
        if (raw == null || raw.isEmpty()) {
            String legacy = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_HEADERS, "");
            if (legacy != null && !legacy.isEmpty()) {
                raw = legacy;
                com.mintv.tv.data.StorageDir.writePath(this, com.mintv.tv.data.StorageDir.SUB_CONFIG + "/headers.txt", legacy);
            }
        }
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
        com.mintv.tv.data.StorageDir.writePath(this, com.mintv.tv.data.StorageDir.SUB_CONFIG + "/headers.txt", sb.toString());
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

        showSystemDialog(new android.app.AlertDialog.Builder(this)
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
                );
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

    /**
     * DNS 预解析。
     *
     * <p>换台时 VLC 要先解析域名才能建连，第一次解析经常 100~300ms。
     * 这里在起播前先把域名丢给系统解析器，结果进 netd 的缓存，
     * VLC 后续的 getaddrinfo 直接命中。
     *
     * <p>只对域名生效：已经是 IP 的地址没有解析开销，跳过。
     */
    private void preResolveDns(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            String host = android.net.Uri.parse(url).getHost();
            if (host == null || host.isEmpty()) return;
            if (host.matches("[0-9.]+")) return;          // 纯 IP，不用解析
            final String h = host;
            new Thread(new Runnable() {
                @Override public void run() {
                    try { java.net.InetAddress.getByName(h); } catch (Throwable ignored) { }
                }
            }, "dns-prefetch").start();
        } catch (Throwable ignored) { }
    }

    /**
     * 按地址类型管理组播锁。
     *
     * <p>udp / rtp / rtsp 都是组播或单播流，需要这个锁；
     * http(s) 的 HLS 不需要，且持有它会多耗一点电，所以切换时释放。
     */
    private void updateMulticastLock(String url) {
        boolean need = url != null
                && (url.startsWith("udp://") || url.startsWith("rtp://")
                    || url.startsWith("rtsp://") || url.startsWith("igmp://"));
        try {
            if (need) {
                if (multicastLock == null) {
                    android.net.wifi.WifiManager wm =
                            (android.net.wifi.WifiManager) getApplicationContext()
                                    .getSystemService(android.content.Context.WIFI_SERVICE);
                    if (wm != null) {
                        multicastLock = wm.createMulticastLock("mintv-mcast");
                        multicastLock.setReferenceCounted(false);
                    }
                }
                if (multicastLock != null && !multicastLock.isHeld()) {
                    multicastLock.acquire();
                }
            } else {
                if (multicastLock != null && multicastLock.isHeld()) {
                    multicastLock.release();
                }
            }
        } catch (Throwable ignored) {
            // 有些盒子没有 WifiManager（纯有线），拿不到就算了
        }
    }

    /**
     * 私有协议 → 本地 http。
     *
     * <p>VLC 只认标准协议，tvbus:// 和 p2p:// 必须先在 native 引擎里转成本机
     * http 地址才能播。转换失败返回 null，调用方继续用原地址，让 VLC 报错。
     */
    /** tvbus / p2p~p9p 这类内核不认的私有协议。 */
    private boolean isPrivateProtocol(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        // mintv:// 是我们自己的分发协议：mintv://tvbus/xxx、mintv://p2p/host/path
        if (u.startsWith("mintv://")) return true;
        if (u.startsWith("tvbus://")) return true;
        // mitv: 只有单个冒号，也要认
        if (u.startsWith("mitv:")) return true;
        if (u.startsWith("p2p:") || u.startsWith("pp:")) return true;
        for (int i = 3; i <= 9; i++) {
            if (u.startsWith("p" + i + "p:")) return true;
        }
        return false;
    }

    // ---------- 私有协议引擎的生命周期 ----------
    //
    // tvbus 和 p2p 都会在本机起一个 HTTP 服务，还带 native 线程、socket、
    // 甚至往路由器写 UPnP 端口映射。不显式停就会一路累积：
    // 切十几个台之后端口耗尽、native 内存涨、路由器映射表越来越长。
    //
    // 三条规则：
    //   1. 切到非私有协议前，先停私有引擎
    //   2. 私有 → 私有（哪怕同一个 tvbus）也要先停，再起新的
    //   3. onDestroy 兜底全停
    //
    // stop() 是 native 调用，可能阻塞，所以一律丢后台线程。

    private volatile boolean tvbusInited = false;
    private volatile com.forcetech.android.ForceTV curForceTv = null;
    private volatile String curPrivateUrl = null;

    /** 正在解析的私有协议地址，用来挡住重复点击。 */
    private String resolvingUrl;
    /** TVCore.init 只做一次，且必须串行 —— 日志里出现过 4 线程抢锁 7 秒。 */
    private final Object tvbusInitLock = new Object();

    /**
     * 放开当前私有协议引擎的引用。非阻塞。
     *
     * <p><b>不再调用 ForceTV.stop()</b>：native 的 stop 会释放
     * j_select_reactor，而 _forcetv_run 的线程还在跑 select 循环，
     * 下一轮就 SEGV（fault addr 0x198, run_reactor_event_loop()+6）。
     * 实测切源三次必崩一次。
     *
     * <p>引擎本身按需拉流：VLC 断开 9906 之后它自然不干活，留着不占带宽。
     * 猫影原版同样从不 stop。
     */
    private void stopPrivateEngines() {
        curForceTv = null;
        curPrivateUrl = null;
        if (!tvbusInited) return;
        // tvbus 是另一套 so，close 不涉及 forcetv 的 reactor，仍然照常停。
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    com.tvbus.engine.TVCore.close();
                    android.util.Log.i("MinTV", "私有引擎已停止");
                } catch (Throwable t) {
                    android.util.Log.w("MinTV", "TVCore close 失败", t);
                }
            }
        }, "private-stop").start();
        tvbusInited = false;
    }

    private String resolvePrivateProtocol(String url) {
        if (url == null) return null;

        // mintv:// 是我们自己的分发器：展开成内部协议再走下面的分支。
        if (url.toLowerCase().startsWith("mintv://")) {
            String expanded = expandMintvUrl(url);
            if (expanded == null) return null;
            if (expanded.startsWith("script:")) {
                return runScriptProtocol(expanded.substring("script:".length()));
            }
            // 展开成 tvbus:// 或 pNp:// 后递归处理。
            //
            // 但 mintv://http://... 展开出来的是本地代理地址
            // （http://127.0.0.1:port/mintv/m3u8?u=...），递归进去
            // "http" 不匹配任何私有协议，会一路返回 null，把展开结果丢掉，
            // 最后回退成原地址交给播放器 —— VLC 不认识 mintv:// ，一个请求
            // 都不发，界面永远停在"正在解析源地址"。
            //
            // 展开结果本身就是最终播放地址，递归拿不到东西时就用它。
            String inner = resolvePrivateProtocol(expanded);
            return inner != null ? inner : expanded;
        }

        int idx = url.indexOf("://");
        if (idx <= 0) {
            // mitv: / p2p: 这种单冒号写法，补成双斜杠好走后面的解析
            int c = url.indexOf(':');
            if (c <= 0) return null;
            url = url.substring(0, c) + "://" + url.substring(c + 1);
            idx = url.indexOf("://");
        }
        String scheme = url.substring(0, idx).toLowerCase();

        // 同一个地址又解析一次（比如重连）也算"新的一路"，
        // 必须先停旧的 —— 否则 native 侧会有两个实例抢同一个本地端口。
        if (curPrivateUrl != null) {
            android.util.Log.i("MinTV", "切私有源，先停旧引擎");
            stopPrivateEngines();
        }

        try {
            if ("tvbus".equals(scheme)) {
                final com.tvbus.engine.TVCore core = com.tvbus.engine.TVCore.get();
                // init 必须串行。
                //
                // 真实日志：4 个 proto-resolve 线程同时进来，
                // Long monitor contention ... TVCore.init ... waiters=3 for 7.007s。
                // 并发调 init 会让 native 层状态互相覆盖，之后 start 也拿不到地址。
                synchronized (tvbusInitLock) {
                    if (!tvbusInited) {
                        // applicationContext：TVCore 是全局单例，持有 Activity 会泄漏整个界面
                        core.init(getApplicationContext());
                        tvbusInited = true;
                    }
                }
                curPrivateUrl = url;
                // 同一地址已经在解析：直接返回，不再起第二个 start。
                // 重复 start 会让 native 收到多次请求，但只有一份内部状态。
                if (url.equals(resolvingUrl)) {
                    android.util.Log.i("MinTV", "同一地址正在解析，忽略重复请求");
                    return null;
                }
                // 超时时间跟用户设置走（"超时换源"那一项）。
                // 用户的设置是全局约定，不该被某个协议单独写死。
                // 用和"超时换源"同一个值；用户关掉超时（0）时给个 8 秒兜底，
                // 否则 tvbus 的循环会退化成最短值。
                int toMs = (int) switchTimeoutMs();
                if (toMs <= 0) toMs = 8000;
                com.tvbus.engine.TVCore.setTimeoutMs(toMs);
                android.util.Log.i("MinTV", "tvbus 超时=" + toMs + "ms");
                resolvingUrl = url;
                String out;
                try {
                    out = core.playTVBUSUrl(url);
                } finally {
                    resolvingUrl = null;
                }
                if (out == null || out.isEmpty()) {
                    android.util.Log.w("MinTV", "tvbus 解析失败: " + url);
                    // 失败也要停，别留一个半初始化的引擎
                    stopPrivateEngines();
                    return null;
                }
                android.util.Log.i("MinTV", "tvbus -> " + out);
                return out;
            }

            String lib = com.forcetech.android.ForceTV.libFor(url);
            if (lib != null) {
                // 端口是固定的，按协议前缀选（猫影 PlayerActivity 里的常量）：
                //   mitv → 9002 (0x232a)，P2p → 9001，p2p/pNp → 9000
                // 之前用 18899+1 是错的 —— 那是 RemoteServer 的备用端口，
                // 引擎根本没在那监听，VLC 一连接就 Connection refused。
                int port = com.forcetech.android.ForceTV.portFor(url);
                com.forcetech.android.ForceTV ft =
                        new com.forcetech.android.ForceTV();
                ft.start(lib, port);
                curForceTv = ft;
                curPrivateUrl = url;

                // 关键一步：先发控制请求告诉引擎播哪个频道，再返回播放地址。
                // 猫影的 a(url, port) 就是干这个的：
                //   GET http://127.0.0.1:<port>/cmd.xml?cmd=switch_chan
                //       &server=<host>:<port>&id=<lastPathSegment 去掉扩展名>
                //   播放地址 = http://127.0.0.1:<port>/<lastPathSegment>[.ts]
                String out = forceTvPlayUrl(url, port);
                android.util.Log.w("MinTV", lib + " port=" + port + " -> " + out);
                if (out == null) {
                    stopPrivateEngines();
                    return null;
                }
                // 引擎起来需要一点时间，给它 300ms 再让 VLC 去连
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                return out;
            }
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "私有协议解析异常: " + url, t);
            stopPrivateEngines();
        }
        return null;
    }

    /**
     * 把 mintv:// 展开成内部协议。
     *
     * <pre>
     *   mintv://tvbus/39D2wC2h...        → tvbus://39D2wC2h...
     *   mintv://p2p/host.com/live/x      → p2p://host.com/live/x
     *   mintv://p5p/host.com:8000/x      → p5p://host.com:8000/x
     *   mintv://mitv/host.com/x          → mitv://host.com/x
     *   mintv://script/name?a=1&amp;b=2      → script:name?a=1&amp;b=2
     * </pre>
     *
     * @return 内部协议串；不认识的返回 null
     */
    private String expandMintvUrl(String url) {
        // mintv://http://... 交给本地 HLS 代理先处理。
        // 必须用 substring 判断，不能靠 Uri.parse —— 那样 host 会是 "http"，
        // path 是 "//xxx"，拼不回来。
        String afterPrefix = url.substring("mintv://".length());
        String afterLow = afterPrefix.toLowerCase();
        if (afterLow.startsWith("http://") || afterLow.startsWith("https://")) {
            int port = remotePort > 0
                    ? remotePort : com.mintv.tv.data.RemoteServer.PORT_MAIN;
            // 开了源力加速才带频道 hash，让分片请求能参与 P2P。
            // 拿不到频道名就退化成纯代理，不影响播放。
            String ch = null;
            if (selections[ROW_P2P_BOOST] == 1) ch = p2pChannel();
            String enc = android.net.Uri.encode(
                    com.mintv.tv.data.HlsProxy.b64(afterPrefix));
            String out = "http://127.0.0.1:" + port + "/mintv/m3u8?u=" + enc
                    + (ch == null ? "" : "&ch=" + ch)
                    + "&s=" + com.mintv.tv.data.RemoteServer.sign(afterPrefix);
            android.util.Log.w("MinTV", "mintv 展开 port=" + port
                    + " remotePort=" + remotePort + " ch=" + ch);
            return out;
        }

        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String kind = u.getHost();
            if (kind == null) return null;
            String k = kind.toLowerCase();

            // 脚本处理器：mintv://script/name?args
            if ("script".equals(k)) {
                java.util.List<String> seg = u.getPathSegments();
                if (seg.isEmpty()) return null;
                String name = seg.get(0);
                if (name == null || name.isEmpty()) return null;
                String q = u.getQuery();
                return "script:" + name + (q == null || q.isEmpty() ? "" : "?" + q);
            }

            // 其余都是"把 kind 当协议名，原样拼回去"
            String proto = k;
            if (!"tvbus".equals(proto) && !"mitv".equals(proto)
                    && !proto.matches("p[3-9]p") && !"p2p".equals(proto)) {
                android.util.Log.w("MinTV", "mintv:// 不认识的类型: " + kind);
                return null;
            }

            StringBuilder sb = new StringBuilder(proto).append("://");
            java.util.List<String> seg = u.getPathSegments();
            for (int i = 0; i < seg.size(); i++) {
                if (i > 0) sb.append('/');
                sb.append(seg.get(i));
            }
            if (u.getQuery() != null && !u.getQuery().isEmpty()) {
                sb.append('?').append(u.getQuery());
            }
            return sb.toString();
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "展开 mintv:// 失败", t);
            return null;
        }
    }

    /**
     * 取当前播放频道的 P2P hash，并确保引擎已经在跑。
     *
     * <p>开关没开、或拿不到频道名，都返回 null —— 调用方退化成纯代理。
     */
    private String p2pChannel() {
        try {
            if (playingChannel < 0 || playingChannel >= channels.size()) return null;
            PlaylistParser.Channel c = channels.get(playingChannel);
            if (c == null || c.name == null || c.name.trim().isEmpty()) return null;

            com.mintv.tv.data.P2PEngine engine =
                    com.mintv.tv.data.P2PEngine.get(getApplicationContext());
            if (!engine.running() || !c.name.equals(engine.channelName())) {
                engine.start(c.name, lastNatResult);
            }
            return com.mintv.tv.data.ChannelHash.id(c.name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 跑脚本协议：{@code mintv://script/name?args}。
     *
     * <p>脚本放在 {@code <存储根>/scripts/name.js}，执行后返回播放地址。
     * 这样遇到没见过的私有协议，写个 JS 就能接，不用发版。
     *
     * @param spec {@code name?args} 形式
     * @return 播放地址；失败返回 null
     */
    private String runScriptProtocol(String spec) {
        try {
            String name = spec;
            String query = "";
            int q = spec.indexOf('?');
            if (q >= 0) {
                name = spec.substring(0, q);
                query = spec.substring(q + 1);
            }
            if (!name.endsWith(".js")) name = name + ".js";

            // 脚本执行需要 File，先把授权目录里的脚本同步到私有目录
            com.mintv.tv.data.ScriptStore store =
                    new com.mintv.tv.data.ScriptStore(getApplicationContext());
            store.syncFromStorage(getApplicationContext());

            java.io.File f = new java.io.File(store.dir(), name);
            if (!f.isFile()) {
                android.util.Log.w("MinTV", "脚本不存在: " + f.getAbsolutePath());
                return null;
            }

            org.json.JSONObject item = new org.json.JSONObject();
            item.put("url", "mintv://script/" + spec);
            item.put("query", query);
            // 把 query 展开成 params，脚本里用 item.params.xxx 取
            org.json.JSONObject params = new org.json.JSONObject();
            if (!query.isEmpty()) {
                for (String kv : query.split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) params.put(kv.substring(0, eq),
                            android.net.Uri.decode(kv.substring(eq + 1)));
                }
            }
            item.put("params", params);

            com.mintv.tv.data.ScriptEngine engine =
                    new com.mintv.tv.data.ScriptEngine(getApplicationContext());
            com.mintv.tv.data.ScriptEngine.Result r = engine.invoke(f, "main", item);
            if (r == null || !r.ok || r.url == null || r.url.isEmpty()) {
                android.util.Log.w("MinTV", "脚本 " + name + " 没返回地址"
                        + (r == null ? "" : " · " + r.error));
                return null;
            }
            android.util.Log.w("MinTV", "script " + name + " -> " + r.url);
            return r.url;
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "跑脚本协议失败", t);
            return null;
        }
    }

    /**
     * 构造 P2P 的本地播放地址，并先发一次 switch_chan 控制请求。
     *
     * <p>从猫影 PlayerActivity.a(String,String) 还原：
     * <pre>
     *   uri   = Uri.parse(url)
     *   last  = uri.getLastPathSegment()
     *   控制   http://127.0.0.1:port/cmd.xml?cmd=switch_chan
     *          &amp;server=host:port&amp;id=last去掉扩展名[&amp;原始query]
     *   播放   http://127.0.0.1:port/last[.ts]
     * </pre>
     * last 里没有 "." 时追加 .ts —— 这是猫影的分支，照抄。
     *
     * @return 播放地址；参数不合法返回 null
     */
    private String forceTvPlayUrl(String url, int port) {
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String last = uri.getLastPathSegment();
            if (last == null || last.isEmpty()) {
                return null;
            }

            String playUrl;
            String id;
            int dot = last.indexOf('.');
            if (dot != -1) {
                id = last.substring(0, dot);
                playUrl = "http://127.0.0.1:" + port + "/" + last;
            } else {
                id = last;
                playUrl = "http://127.0.0.1:" + port + "/" + last + ".ts";
            }

            StringBuilder cmd = new StringBuilder("http://127.0.0.1:" + port);
            cmd.append("/cmd.xml?cmd=switch_chan&server=");
            cmd.append(uri.getHost() == null ? "" : uri.getHost());
            cmd.append(":");
            cmd.append(uri.getPort());
            cmd.append("&id=");
            cmd.append(id);
            if (uri.getQuery() != null && !uri.getQuery().isEmpty()) {
                cmd.append("&").append(uri.getQuery());
            }

            // 发控制请求（失败也继续，有些引擎不需要）
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(cmd.toString()).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                android.util.Log.w("MinTV", "switch_chan " + code + " · " + cmd);
                conn.disconnect();
            } catch (Throwable t) {
                android.util.Log.w("MinTV", "switch_chan 失败: " + cmd, t);
            }

            return playUrl;
        } catch (Throwable t) {
            return null;
        }
    }

    /** OSD 提示占位，暂时不弹，避免解析期间刷屏。 */
    private boolean osdShown(String s) { return true; }

    /**
     * 播放入口。**所有外部调用都从这里进**，不要再直接调 playUrl。
     *
     * <p>这一层只做一件事：地址变换（配了代理就把 http 源包成 mintv://）。
     * {@link #playUrl} 负责执行，且<b>不做任何变换</b>。
     *
     * <p>为什么必须分开：playUrl 会被自己递归调用 —— 私有协议解析完
     * 拿着结果再调一次 playUrl。如果变换写在 playUrl 里，那一轮也会变换：
     * <pre>
     *   http://xxx
     *   → mintv://http://xxx
     *   → 解析出 http://127.0.0.1:18899/mintv/m3u8?u=&lt;base64&gt;
     *   → 这个地址也是 http 开头 → 再包一层
     *   → base64 每轮 ×1.33，几十轮就是几十 MB → OutOfMemoryError
     * </pre>
     * 分离之后，递归那一轮走的是 playUrl，天然不会再变换。
     */
    private void playUrlEntry(String url, java.util.Map<String, String> headers,
                              PlaybackBackend.Mode mode) {
        playUrl(applyProxyWrapper(url), headers, mode);
    }

    /**
     * 配了代理且目标是外网 http(s) 时，包一层 mintv:// 交给 HlsProxy。
     *
     * <p>为什么要包：VLC 的 --http-proxy 只作用于它自己发起的请求，
     * 302 跳转和 m3u8 里嵌套的子 playlist 会直连（实测桥只收到一次请求）。
     * 全部经 HlsProxy 才能真正做到「一个不漏」。
     *
     * <p>没配代理时原样返回，行为跟以前完全一样。
     */
    private String applyProxyWrapper(String url) {
        if (url == null || url.isEmpty()) return url;
        String low = url.toLowerCase(java.util.Locale.US);
        // 已经是私有协议（包括已经包过的），不再处理
        if (low.startsWith("mintv://")) return url;
        // 只管 http(s)，rtsp/udp/rtp 这些不经过 HTTP 代理
        if (!low.startsWith("http://") && !low.startsWith("https://")) return url;
        // 本机后端不包装。用解析 host 判断，不用字符串包含 ——
        // 包装后的 URL 里 u=<base64>，base64 完全可能恰好含 "127.0.0.1"。
        if (isLoopbackUrl(url)) return url;
        // 只要有代理配置就包装。具体协议（socks5 / http）由 HlsProxy 自己处理。
        if (com.mintv.tv.data.NetProxy.rawProxy(this) == null) return url;
        String wrapped = "mintv://" + url;
        android.util.Log.w("MinTV", "配了代理，包装: " + brief(wrapped));
        return wrapped;
    }

    private void playUrl(String url, java.util.Map<String, String> headers, PlaybackBackend.Mode mode) {
        if (url == null || url.isEmpty()) return;
        if (playerManager == null) return;

        // 配了代理时，把普通 http 源自动包一层 mintv://，
        // 让 HlsProxy 统一拉取 —— VLC 自己的代理对 302 跳转和 m3u8 里
        // 嵌套的子 playlist 不生效，只有全部经过 HlsProxy 才不漏。
        //
        // 没配代理时**完全不变**，行为和以前一模一样。
        // 私有协议转标准 http —— VLC 内核不认 tvbus:// 和 p2p://。
        //
        // tvbus：libtvcore.so 在本机起 HTTP 服务，返回 http://127.0.0.1:port/mpegts_live
        // p2p~p9p：libpNp.so 同样起本地服务
        //
        // 这一步是阻塞的（tvbus 最坏等 30 秒），但 playUrl 本身就在 IO 线程，
        // 不会卡主线程。失败就原样往下走，让 VLC 自己报错。
        // 私有协议解析必须完全异步。
        //
        // 之前的做法是"主线程起线程 + latch.await"，结果是主线程被自己锁住，
        // ANR 从 native 的 6 秒变成锁等待的 5 秒 —— 换了个地方卡而已。
        //
        // 现在：主线程立刻返回（先给个提示），后台解析完再 post 回来继续。
        // resolved 已经是 http，二次进入不会再走这个分支。
        // 从 tvbus/p2p 切到 http 源：先把私有引擎停掉，
        // 否则它在后台继续拉 P2P 流，白占带宽和端口。
        if (!isPrivateProtocol(url) && curPrivateUrl != null) {
            android.util.Log.i("MinTV", "切到普通源，停私有引擎");
            stopPrivateEngines();
        }

        if (isPrivateProtocol(url)) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                if (osd != null) osd.toast("正在解析源地址…");
                final String pendingUrl = url;
                final java.util.Map<String, String> pendingHeaders = headers;
                final PlaybackBackend.Mode pendingMode = mode;
                new Thread(new Runnable() {
                    @Override public void run() {
                        String r = null;
                        try {
                            r = resolvePrivateProtocol(pendingUrl);
                        } catch (Throwable t) {
                            android.util.Log.w("MinTV", "协议解析失败", t);
                        }
                        final String finalUrl = (r != null) ? r : pendingUrl;
                        // 递归保护。
                        //
                        // playUrl 解析完会再调 playUrl，如果解析结果和输入一模一样，
                        // 说明这一层什么都没干成（多半是 expandMintvUrl 返回 null），
                        // 再往下就是死循环。之前那个 49MB OOM 就是这么来的。
                        if (finalUrl.equals(pendingUrl) && isPrivateProtocol(finalUrl)) {
                            android.util.Log.w("MinTV", "解析无进展，停止重试: "
                                    + brief(pendingUrl));
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (osd != null) osd.toast("源地址无法解析，换一个试试");
                                }
                            });
                            return;
                        }
                        // 解析结果一定要打出来：抓包看不到请求时，第一个要确认的
                        // 就是 VLC 到底拿到了什么地址。没有这行，只能猜。
                        android.util.Log.w("MinTV", "解析完成 " + brief(pendingUrl)
                                + " -> " + brief(finalUrl));
                        if (r == null) {
                            android.util.Log.w("MinTV", "解析返回 null，"
                                    + "回退用原地址播放");
                        }
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (isFinishing()) return;
                                android.util.Log.w("MinTV", "交给播放器: " + brief(finalUrl));
                                playUrl(finalUrl, pendingHeaders, pendingMode);
                            }
                        });
                    }
                }, "proto-resolve").start();
                return;   // 主线程不等
            }
            String r = resolvePrivateProtocol(url);
            if (r != null) url = r;
        }

        // 现在只有一个内核，udp/rtp/rtsp 天然支持，不需要再切。
        java.util.Map<String, String> merged = HttpHeaderProfile.merge(globalHeaders, headers);
        if (!merged.isEmpty()) {
            String lost = HttpHeaderProfile.vlcUnsupported(merged);
            if (!lost.isEmpty()) osd.toast("VLC 不支持请求头 " + lost + "，可能影响播放");
        }
        // 每次起播都同步一次解码方式。
        //
        // 漏了这一步的后果：某个源触发过"无画面"，把本次尝试临时改成软解；
        // 用户接着手动换台，attempt 重建、设置显示"自动"，但播放器里
        // 还留着上一次的软解值 —— 显示和实际不一致，而且软解更吃 CPU。
        playerManager.setDecoderMode(effectiveDecoder());
        playerManager.play(getApplicationContext(), url, merged, mode);
    }

    /** 用户主动换台 / 换分类：自愈进度清零。 */
    /**
     * 重置「本次播放尝试」的全部降级状态。
     *
     * <p>注意它**不会**改动用户设置里的解码方式 —— 自动降级只活在
     * 这个 attempt 对象里，换台就没了。
     */
    private void resetNoVideoStage() {
        attempt = null;
    }

    /** 当前真正生效的解码方式（本次尝试的临时覆盖优先）。 */
    private int effectiveDecoder() {
        return attempt != null ? attempt.decoderMode() : selections[ROW_DECODER];
    }

    private boolean autoSwitchSource() { return selections[ROW_TIMEOUT] != 0; }

    // ================= 本次播放尝试 =================
    //
    // 以前这里散着 playing / watchdogFired / retryDone / noVideoStage /
    // tempDecoderOverride / stallSince+stallLastPos 六个字段，由六处交叉读写，
    // 没有任何一处能完整描述「这次播放到哪一步了」。漏复位就出 bug
    // （改解码方式后播不了、切回来没声音，都是这个病）。
    // 现在全部收进 data.PlaybackAttempt，那里有单测覆盖。
    private com.mintv.tv.data.PlaybackAttempt attempt;

    /** 懒创建：任何调用点都不用担心顺序。 */
    private com.mintv.tv.data.PlaybackAttempt attempt() {
        if (attempt == null) {
            int n = 1;
            if (playingChannel >= 0 && playingChannel < channels.size()) {
                n = channels.get(playingChannel).sources.size();
            }
            attempt = new com.mintv.tv.data.PlaybackAttempt(
                    selections[ROW_DECODER], switchTimeoutMs(), n, autoSwitchSource());
        }
        return attempt;
    }

    /** 是否已经出过画面（原 playing 字段的唯一用途）。 */
    private boolean hasFirstFrame() {
        return attempt != null && attempt.hasFirstFrame();
    }

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
    /**
     * 降级动作的公共出口：换线路 / 放弃。
     *
     * <p>位置判据（完全冻住）和速度判据（越来越慢）走同一个出口，
     * 免得两处各写一遍换源逻辑。
     */
    private void handleDegrade(com.mintv.tv.data.PlaybackAttempt.Action act,
                               String switchMsg, String giveMsg) {
        PlaylistParser.Channel ch = (playingChannel >= 0 && playingChannel < channels.size())
                ? channels.get(playingChannel) : null;
        if (act == com.mintv.tv.data.PlaybackAttempt.Action.SWITCH_SOURCE
                && ch != null && ch.sources.size() > 1) {
            currentLine = (currentLine + 1) % ch.sources.size();
            osd.toast(switchMsg + " " + (currentLine + 1) + "/" + ch.sources.size());
            playChannelInternal(playingChannel, true);
        } else if (act == com.mintv.tv.data.PlaybackAttempt.Action.GIVE_UP) {
            osd.toast(giveMsg);
        }
    }

    private void checkStall(long pos) {
        long limit = switchTimeoutMs();
        com.mintv.tv.data.PlaybackAttempt.Action act =
                attempt().onPosition(pos, SystemClock.uptimeMillis());
        if (act == com.mintv.tv.data.PlaybackAttempt.Action.NONE) return;

        handleDegrade(act,
                "播放卡住 " + (limit / 1000) + " 秒，切换线路",
                "播放卡住，该频道只有一条线路");
    }

    /** 起播后调用：8 秒没出画面就自愈。 */
    private void armWatchdog() {
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
            sb.append("状态: ").append(playerManager.tag()).append('\n');
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
    // ================= 手机助手：HTTP 服务 =================

    private void startRemoteServer() {
        if (remote != null && remote.running()) return;
        remote = new com.mintv.tv.data.RemoteServer(this, new com.mintv.tv.data.RemoteServer.Host() {
            @Override public String stateJson() { return buildStateJson(); }
            @Override public String onCommand(String type, String value) {
                return handleRemoteCommand(type, value);
            }
            @Override public String subsJson() { return buildSubsJson(); }
            @Override public String epgJson(String ch) { return buildEpgJson(ch); }
            @Override public String searchJson(String q, String by) { return buildSearchJson(q, by); }
            @Override public String channelsJson(int cat) { return buildChannelsJson(cat); }
            @Override public String onSubsAction(String action, org.json.JSONObject payload) {
                return handleSubsAction(action, payload);
            }
        });
        remotePort = remote.start();
        // HlsProxy 的上下文以前从来没注入过（ctxRef 一直是 null），
        // 导致改写后的分片地址固定用 18899、请求头永远为空。
        // 同时把代理也接进去：VLC 自己的代理对 302 和嵌套 playlist 不生效，
        // 由 HlsProxy 统一拉取才能做到所有请求都走代理。
        com.mintv.tv.data.HlsProxy.init(new com.mintv.tv.data.HlsProxy.Context0() {
            @Override public int localPort() {
                return remotePort > 0 ? remotePort
                        : com.mintv.tv.data.RemoteServer.PORT_MAIN;
            }
            @Override public java.util.Map<String, String> headers() {
                return headersOfActiveSub();
            }
            @Override public String proxy() {
                // 返回原始配置（socks5://host:port），不是本地桥地址。
                // HlsProxy 是 Java，HttpURLConnection 原生支持 SOCKS，
                // 不需要中间套一层 HTTP 桥 —— 那层桥正是 404 的来源。
                return com.mintv.tv.data.NetProxy.rawProxy(MainActivity.this);
            }
        });
        if (remotePort < 0) {
            // 这里可能在 osd 还没创建时被调用（服务在 UI 之前启动），必须判空
            android.util.Log.w("MinTV", "手机助手启动失败：18899/18900 端口都被占用");
            if (osd != null) osd.toast("手机助手启动失败：端口被占用");
        } else {
            android.util.Log.i("MinTV", "手机助手 http://" + com.mintv.tv.data.LocalIp.get(MainActivity.this)
                    + ":" + remotePort + "/");
        }
        ui.post(helperTicker);
    }

    /** 手机助手页开着时，每 2 秒刷新一次连接状态。 */
    private final Runnable helperTicker = new Runnable() {
        @Override public void run() {
            if (dialogRoot != null && dialogRoot.getVisibility() == View.VISIBLE
                    && currentTab == TAB_HELPER) {
                refreshHelperState();
            }
            ui.postDelayed(this, 2000);
        }
    };

    private final Runnable helperTick = new Runnable() {
        @Override public void run() {
            if (helperPage == null || helperPage.getVisibility() != View.VISIBLE) return;
            rebuildHelperSubs();                 // 手机端改了订阅，盒子这页跟着变
            ui.postDelayed(this, 2000);
        }
    };

    private void refreshHelperPage() {
        if (helperPage == null) return;
        // 每次进这一页都做两件事：
        //   1) 重读订阅配置 —— 用户可能在文件管理器里手改过 subscriptions.json
        //   2) 重扫 sources/ + upload/ —— 可能刚丢进新文件
        // 只在启动和授权时做一次会导致"改了文件但界面不更新"。
        try {
            com.mintv.tv.data.SubscriptionStore.reload();
            loadLocalSources();
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "刷新订阅失败", t);
        }
        // LocalIp.get 取不到时可能返回 null 也可能抛异常，两种情况都兜住，
        // 否则整个 refreshHelperPage 中断，地址和二维码都不会更新。
        String ip = null;
        try {
            ip = com.mintv.tv.data.LocalIp.get(MainActivity.this);
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "取本机 IP 失败", t);
        }
        int port = remotePort > 0 ? remotePort : com.mintv.tv.data.RemoteServer.PORT_MAIN;
        String url = "http://" + (ip == null ? "" : ip) + ":" + port + "/";
        android.util.Log.w("MinTV", "订阅页地址 ip=" + ip + " port=" + port);
        // 地址是动态的：IP 随网络变化，端口在 18899 被占用时会切 18900。
        //
        // 以前只显示 ip:port，而且 ip 取不到时显示成 ":18899"，
        // 用户看不出哪里不对。现在给出完整 URL，取不到 IP 时明说原因。
        if (ip == null || ip.isEmpty()) {
            // 取不到局域网 IP 时用回环地址兜底，界面不留空。
            // 注意：127.0.0.1 只有本机能访问，手机扫码后打不开 ——
            // 这时候真正能用的前提是盒子已连上局域网。
            url = "http://127.0.0.1:" + port + "/";
            helperAddr.setText(url);
            helperAddr.setTextColor(0xFFFFA53D);
            android.util.Log.w("MinTV", "未取到局域网 IP，地址回落到 " + url);
        } else {
            helperAddr.setText(url);
            helperAddr.setTextColor(Theme.FOCUS_BLUE);
        }
        // 兜底：任何分支都没写进 helperAddr 时，这里强制补一次。
        // 之前出现过二维码正常、地址栏空白的现象，加这层保证界面不留白。
        if (helperAddr != null && (helperAddr.getText() == null
                || helperAddr.getText().length() == 0)) {
            helperAddr.setText(url);
            helperAddr.setTextColor(Theme.FOCUS_BLUE);
        }
        android.util.Log.w("MinTV", "地址已写入: " + url
                + " len=" + (helperAddr == null || helperAddr.getText() == null
                        ? -1 : helperAddr.getText().length()));
        // 诊断：helperAddr 在界面上一直不显示，把 URL 也拼进状态行，
        // 这样一定能看到算出来的地址是什么。
        if (helperState != null) {
            helperState.setText("● 等待手机扫码   " + url);
        }
        if (qrView != null) {
            android.graphics.Bitmap bmp = com.mintv.tv.data.QrUtil.encode(url, Theme.px(240));
            if (bmp != null) qrView.setImageBitmap(bmp);
        }
        refreshHelperState();
        rebuildHelperSubs();
    }

    private void refreshHelperState() {
        if (helperState == null) return;
        // 状态行带上地址：helperAddr 那个独立控件在这台设备上一直不显示，
        // 合成一行既省空间又保证用户一定能看到访问地址。
        String addr = helperAddr == null || helperAddr.getText() == null
                ? "" : helperAddr.getText().toString();
        String suffix = addr.isEmpty() ? "" : ("   " + addr);
        if (remote == null || !remote.running()) {
            helperState.setText("● 服务未启动" + suffix);
            return;
        }
        boolean on = remote.idleMs() < 15000;
        helperState.setText((on ? "● 手机已连接 · 正在浏览" : "● 等待手机扫码") + suffix);
        helperState.setTextColor(on ? Theme.SEL_GREEN : 0xFFFFA53D);
    }

    /**
     * 重建订阅列表。
     *
     * <p>照 HTML 原型做成卡片：圆角、当前项带蓝框和「使用中」绿标、
     * 右侧显示频道数，别的行给「按 OK 切换」提示。
     * 之前是一整条平铺的 TextView，既没卡片感也没有操作提示。
     */
    private String helperSig = "";

    /**
     * 长按订阅 → 确认删除。
     *
     * <p>除了删订阅记录，本地源（local://）对应的文件也一起删，
     * 否则 samples/ 目录里会留下没人引用的孤儿文件。
     */
    private void confirmDeleteSub(final com.mintv.tv.data.SubscriptionStore.Sub sub) {
        if (sub == null) return;
        final String label = sub.name == null || sub.name.isEmpty() ? "未命名" : sub.name;
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("删除订阅");
        b.setMessage("确定删除「" + label + "」吗？\n本地源文件也会一起删掉。");
        b.setNegativeButton("取消", null);
        b.setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                boolean isLocal = sub.url != null && sub.url.startsWith("local://");
                String fileName = isLocal ? sub.url.substring("local://".length()) : null;

                // 脚本订阅要记黑名单，否则下次启动扫描会重新加回来
                dismissSub(sub.id);
                com.mintv.tv.data.SubscriptionStore.remove(MainActivity.this, sub.id);

                if (fileName != null) {
                    try {
                        com.mintv.tv.data.StorageDir.delete(
                                MainActivity.this,
                                com.mintv.tv.data.StorageDir.SUB_SOURCES, fileName);
                        android.util.Log.w("MinTV", "已删除本地源文件: " + fileName);
                    } catch (Throwable t) {
                        android.util.Log.w("MinTV", "删除本地源文件失败: " + fileName, t);
                    }
                }

                // 删的是当前订阅 → 切到第一个还能用的
                java.util.List<com.mintv.tv.data.SubscriptionStore.Sub> rest =
                        com.mintv.tv.data.SubscriptionStore.list(MainActivity.this);
                if (rest.isEmpty()) {
                    channels.clear();
                    playlist = null;
                    rebuildCategoryColumn();
                    refreshChannelSubs();
                    osd.toast("订阅已全部删除");
                } else {
                    String act = com.mintv.tv.data.SubscriptionStore.activeId(MainActivity.this);
                    if (act == null || !hasSub(rest, act)) {
                        switchSubscription(rest.get(0).id);
                    } else {
                        rebuildHelperSubs();
                    }
                }
                // 左侧订阅列必须重建，否则删完那一行还在。
                // rebuildHelperSubs 刷的是「手机助手」页里的列表，不是这里。
                fillSubColumn();
                osd.toast("已删除「" + label + "」");
            }
        });
        b.show();
    }

    /**
     * 用户手动删掉的脚本订阅。
     *
     * <p>脚本订阅是启动时自动扫描注册的 —— 删掉列表里那一行，下次启动
     * 扫描又会把它加回来，表现为「删不掉」。所以在删除时把 id 记下来，
     * 扫描时跳过。脚本文件本身被删掉时，这个标记也就没用了（自然失效）。
     */
    private static final String KEY_DISMISSED_SUBS = "dismissed_script_subs";

    private java.util.Set<String> dismissedSubs() {
        java.util.Set<String> out = new java.util.HashSet<>();
        String raw = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_DISMISSED_SUBS, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String x : raw.split("\n")) if (!x.trim().isEmpty()) out.add(x.trim());
        return out;
    }

    private void dismissSub(String id) {
        if (id == null || !id.startsWith("script:")) return;
        java.util.Set<String> s = dismissedSubs();
        s.add(id);
        StringBuilder sb = new StringBuilder();
        for (String x : s) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x);
        }
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString(KEY_DISMISSED_SUBS, sb.toString()).apply();
    }

    private boolean hasSub(java.util.List<com.mintv.tv.data.SubscriptionStore.Sub> list, String id) {
        for (com.mintv.tv.data.SubscriptionStore.Sub t : list) {
            if (t.id != null && t.id.equals(id)) return true;
        }
        return false;
    }

    private void rebuildHelperSubs() {
        if (helperSubs == null) return;
        // 数据没变就别重建。每 2 秒无脑重建会让整页闪一下，
        // 而且会把用户刚移到的焦点冲掉。
        java.util.List<com.mintv.tv.data.SubscriptionStore.Sub> all =
                com.mintv.tv.data.SubscriptionStore.list(this);
        String activeId = com.mintv.tv.data.SubscriptionStore.activeId(this);
        StringBuilder sig = new StringBuilder();
        for (com.mintv.tv.data.SubscriptionStore.Sub t : all) {
            sig.append(t.id).append('|').append(t.name).append('|')
               .append(t.url).append('|').append(t.channelCount).append(';');
        }
        sig.append('#').append(activeId);
        if (sig.toString().equals(helperSig)) return;
        helperSig = sig.toString();

        helperSubs.removeAllViews();
        java.util.List<com.mintv.tv.data.SubscriptionStore.Sub> subs =
                com.mintv.tv.data.SubscriptionStore.list(this);
        String active = com.mintv.tv.data.SubscriptionStore.activeId(this);

        for (final com.mintv.tv.data.SubscriptionStore.Sub sub : subs) {
            final boolean on = sub.id != null && sub.id.equals(active);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Theme.px(20), 0, Theme.px(20), 0);
            row.setBackground(new SubCardBg(on));
            // 记下"是不是当前订阅"，setPageFocus 要靠它重画圆角底
            row.setTag(Boolean.valueOf(on));
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!on) switchSubscription(sub.id);
                }
            });
            // 长按删除：连文件一起删（local:// 的源文件也清掉），
            // 否则用户删了订阅，sources/ 目录里还留着孤儿文件。
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    confirmDeleteSub(sub);
                    return true;
                }
            });
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    v.setBackground(new SubCardBg(on, has));
                }
            });

            // 名称
            TextView name = new TextView(this);
            name.setText(sub.name == null || sub.name.isEmpty() ? "未命名" : sub.name);
            name.setTextColor(Theme.TEXT);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(21));
            name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            row.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 使用中 / 格式标
            if (on) {
                TextView badge = new TextView(this);
                badge.setText("使用中");
                badge.setTextColor(0xFF2EA85A);
                badge.setBackgroundColor(0x2A2EA85A);
                badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(14));
                badge.setPadding(Theme.px(10), Theme.px(4), Theme.px(10), Theme.px(4));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.leftMargin = Theme.px(14);
                row.addView(badge, blp);
            }

            String fmt = SubFmt.of(sub.url);
            if (fmt != null) {
                TextView tag = new TextView(this);
                tag.setText(fmt);
                tag.setTextColor(Theme.TEXT_DIM);
                tag.setBackgroundColor(Theme.PANEL_ITEM);
                tag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(13));
                tag.setPadding(Theme.px(10), Theme.px(3), Theme.px(10), Theme.px(3));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.leftMargin = Theme.px(10);
                row.addView(tag, tlp);
            }

            // 右侧：频道数 / 按 OK 切换
            TextView rightTxt = new TextView(this);
            rightTxt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(15));
            if (on) {
                rightTxt.setText(sub.channelCount > 0 ? sub.channelCount + " 个频道" : "当前使用");
                rightTxt.setTextColor(Theme.TEXT_SUB);
            } else {
                rightTxt.setText("按 OK 切换");
                rightTxt.setTextColor(Theme.FOCUS_BLUE);
            }
            rightTxt.setGravity(Gravity.END);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            rlp.leftMargin = Theme.px(16);
            row.addView(rightTxt, rlp);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(76));
            lp.bottomMargin = Theme.px(10);
            helperSubs.addView(row, lp);
        }

        // 列表每 2 秒重建一次，重建后把焦点恢复到原来的位置，
        // 否则用户在订阅列表里按上下，2 秒后焦点自己跑回第一行。
        if (currentTab == TAB_HELPER) {
            final int keep = pageFocusIdx;
            ui.post(new Runnable() {
                @Override public void run() {
                    rebuildPageFocus();
                    if (keep > 0 && keep < pageFocus.size()) setPageFocus(keep);
                }
            });
        }

        if (subs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有订阅。\n用手机扫码添加 m3u / txt 源，或在本页手动填地址。");
            empty.setTextColor(Theme.TEXT_SUB);
            empty.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(17));
            empty.setLineSpacing(Theme.px(8), 1f);
            helperSubs.addView(empty);
        }
    }

    /** 订阅卡片的圆角底：当前项加蓝描边，焦点项加亮底。 */
    private static final class SubCardBg extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint stroke = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final boolean active;
        private final boolean focused;

        SubCardBg(boolean active) { this(active, false); }

        SubCardBg(boolean active, boolean focused) {
            this.active = active;
            this.focused = focused;
            stroke.setStyle(android.graphics.Paint.Style.STROKE);
            stroke.setStrokeWidth(Theme.px(2));
        }

        @Override public void draw(android.graphics.Canvas c) {
            r.set(0, 0, getBounds().width(), getBounds().height());
            float rad = Theme.px(10);
            if (active) {
                fill.setColor(Theme.FOCUS_BG);
                c.drawRoundRect(r, rad, rad, fill);
                stroke.setColor(Theme.FOCUS_BLUE);
                r.inset(Theme.px(1), Theme.px(1));
                c.drawRoundRect(r, rad, rad, stroke);
            } else {
                fill.setColor(focused ? Theme.PANEL_ACTIVE : Theme.PANEL_ITEM);
                c.drawRoundRect(r, rad, rad, fill);
                if (focused) {
                    stroke.setColor(0x66FFFFFF);
                    r.inset(Theme.px(1), Theme.px(1));
                    c.drawRoundRect(r, rad, rad, stroke);
                }
            }
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); stroke.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { fill.setColorFilter(f); }
        @SuppressWarnings("deprecation")
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 从 URL 猜格式标：只做展示用。 */
    private static final class SubFmt {
        static String of(String url) {
            if (url == null) return null;
            String u = url.toLowerCase(java.util.Locale.US);
            int q = u.indexOf('?');
            if (q > 0) u = u.substring(0, q);
            if (u.endsWith(".m3u8")) return "m3u8";
            if (u.endsWith(".m3u")) return "m3u";
            if (u.endsWith(".txt")) return "txt";
            return null;
        }
    }

    private static String hostOfUrl(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 切换订阅：设为当前 + 重拉源。 */
    /** 上次加载 EPG 用的是哪个源（订阅的 epgUrl，空=全局默认）。 */
    private String lastEpgSource = null;

    /**
     * 需要重载 EPG 吗。
     *
     * <p>EPG 是按频道名 / tvg-id 匹配的 —— 换了订阅但频道名一样的话，
     * 同一份节目单照样能用，没必要重读 1MB 文件再解析一遍。
     * 只有「新订阅指定了不同的 epgUrl」或者「还没加载过」才重来。
     */
    private boolean needReloadEpg(String subEpgUrl) {
        String src = (subEpgUrl == null) ? "" : subEpgUrl.trim();
        if (epg == null) return true;
        if (lastEpgSource == null) return true;
        return !src.equals(lastEpgSource);
    }

    private void switchSubscription(String id) {
        com.mintv.tv.data.SubscriptionStore.setActive(this, id);
        com.mintv.tv.data.SubscriptionStore.Sub s = com.mintv.tv.data.SubscriptionStore.active(this);
        if (s == null) return;
        // 命中缓存：直接换内存里的列表，不碰网络
        PlaylistParser.Playlist cached = sourceCache.get(id);
        java.util.List<PlaylistParser.Channel> cachedCh = channelCache.get(id);
        if (cached != null && cachedCh != null && !cachedCh.isEmpty()) {
            playlist = cached;
            channels = cachedCh;
            focus.setChanIndexSilently(0);
            rebuildCategories();
            // 分类和数据必须同源。
            //
            // 原来这里是 activeCategory = 0 然后直接 bindChannels()，
            // 隐含假设 categories[0] 就是「全部」。0.3.62 把冗余的「全部」
            // 省掉之后，categories[0] 可能变成「我的收藏」，于是界面上
            // 高亮收藏、列表却显示全部频道，下一次任何分类操作都会按
            // 收藏过滤成空。
            activeCategory = defaultCategoryIndex();
            rebuildCategoryColumn();
            applyCategoryChannels(activeCategory);
            updateChannelFocus(0);
            // EPG 只在源变了才重载 —— 换订阅但频道名相同时复用现有节目单，
            // 省掉一次 1MB 文件的读盘 + 解析。
            if (needReloadEpg(s.epgUrl)) {
                epg = null;
                lastEpgSource = (s.epgUrl == null) ? "" : s.epgUrl.trim();
                loadEpgAsync();
            }
            rebuildHelperSubs();
            osd.toast("已切换到 " + s.name + " · " + channels.size() + " 个频道");
            return;
        }

        // 没缓存（第一次切过去）：走原来的联网流程
        osd.toast("正在切换到 " + s.name + "…");
        // 每个订阅可以绑自己的 EPG。但源没变就不用重载 ——
        // EPG 是按频道名匹配的，同一份数据对同名频道照样有效。
        if (needReloadEpg(s.epgUrl)) {
            epg = null;
            lastEpgSource = (s.epgUrl == null) ? "" : s.epgUrl.trim();
            loadEpgAsync();
        }
        SourceStore.saveBody(this, "#EXTM3U\n");
        // 清空列表让用户看到「正在加载」，而不是继续显示上一个订阅的频道
        // —— 那样点下去会播错台。
        //
        // 这里只重建这一次；网络数据回来时 applyPlaylist 会再重建一次，
        // 总共两次。之前 onSubClicked 里还有一次重复调用，加起来三次。
        applyPlaylist("#EXTM3U\n");
        fetchSourceAsync(s.url);
        rebuildHelperSubs();
    }

    // ---------------- 状态 / 命令 JSON ----------------

    /** 给手机遥控页的"最近频道"：只取前 8 个，够点就行。 */
    private org.json.JSONArray recentChannels() {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            int n = Math.min(8, channels.size());
            for (int i = 0; i < n; i++) {
                PlaylistParser.Channel c = channels.get(i);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("no", num4(c.number));
                o.put("name", c.name == null ? "" : c.name);
                o.put("group", c.group == null ? "" : c.group);
                a.put(o);
            }
        } catch (Throwable ignored) { }
        return a;
    }

    private static String num4(int n) {
        String s = String.valueOf(n);
        while (s.length() < 4) s = "0" + s;
        return s;
    }

    private String buildStateJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            PlaylistParser.Channel ch = (playingChannel >= 0 && playingChannel < channels.size())
                    ? channels.get(playingChannel) : null;
            if (ch != null) {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("no", String.format(java.util.Locale.US, "%04d", ch.number));
                j.put("name", ch.name);
                j.put("group", ch.group == null ? "" : ch.group);
                o.put("ch", j);
            }
            org.json.JSONObject line = new org.json.JSONObject();
            line.put("cur", currentLine + 1);
            line.put("total", Math.max(1, lineTotal));
            o.put("line", line);

            org.json.JSONObject video = new org.json.JSONObject();
            video.put("res", lastResolution == null ? "" : lastResolution);
            video.put("fps", lastFps == null ? "" : lastFps);
            video.put("vcodec", lastVCodec == null ? "" : lastVCodec);
            o.put("video", video);

            org.json.JSONObject prog = new org.json.JSONObject();
            prog.put("now", epgNowText == null ? "" : epgNowText);
            prog.put("next", epgNextText == null ? "" : epgNextText);
            prog.put("progress", epgProgress);
            o.put("program", prog);

            o.put("speed", speedMeter.text());
            o.put("playing", hasFirstFrame());
            o.put("panel", panelVisible);

            com.mintv.tv.data.SubscriptionStore.Sub sub =
                    com.mintv.tv.data.SubscriptionStore.active(this);
            org.json.JSONObject pl = new org.json.JSONObject();
            pl.put("name", sub == null ? "" : sub.name);
            pl.put("total", channels == null ? 0 : channels.size());
            o.put("playlist", pl);
            o.put("recent", recentChannels());
            o.put("ip", com.mintv.tv.data.LocalIp.get(this));
            o.put("port", remotePort);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    /**
     * 订阅数据（手机端读取）。
     *
     * 不只返回订阅数组：EPG 和全局请求头也在同一份里，
     * 手机页面一次请求就能渲染全部，不用打三个接口。
     */
    /**
     * 当前生效的 EPG 地址。
     *
     * <p>优先用**当前订阅自己绑的** EPG；没绑才回落到全局默认。
     * 不同源用的 EPG 往往不一样（有的源自带 xml，有的要配 51zmt），
     * 绑在订阅上才不会切源之后节目单还是上一家的。
     */
    private static String epgUrlOf(android.content.Context c) {
        com.mintv.tv.data.SubscriptionStore.Sub sub =
                com.mintv.tv.data.SubscriptionStore.active(c);
        if (sub != null && sub.epgUrl != null && !sub.epgUrl.isEmpty()) {
            return sub.epgUrl;
        }
        String v = c.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE)
                .getString("epg_url", null);
        return v == null ? "" : v;
    }

    /** 全局请求头条数（一行一条），手机端直接显示/编辑。 */
    private String globalHeadersText() {
        StringBuilder sb = new StringBuilder();
        if (globalHeaders != null) {
            for (java.util.Map.Entry<String, String> e : globalHeaders.entrySet()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
        }
        return sb.toString();
    }

    /**
     * 手机遥控命令分发。
     *
     * <p>所有动作都转成对现有方法的调用（moveChannel / handleVolumeKey /
     * switchSource …），不另写一套播放逻辑 —— 避免手机端和遥控器行为不一致。
     */
    /** 按台号切台（手机端点击频道用）。台号在源里唯一，比下标稳。 */
    private void playByNumber(String no) {
        if (no == null || no.isEmpty() || playlist == null || playlist.all == null) return;
        int want;
        try { want = Integer.parseInt(no); } catch (Throwable t) { return; }
        for (int i = 0; i < channels.size(); i++) {
            if (channels.get(i).number == want) {
                playChannel(i);
                return;
            }
        }
        // 不在当前分类里：从全部里找，找到就先切到它所在分类再播
        for (int i = 0; i < playlist.all.size(); i++) {
            PlaylistParser.Channel c = playlist.all.get(i);
            if (c.number == want) {
                if (!channels.contains(c)) channels = playlist.all;
                playChannel(channels.indexOf(c));
                return;
            }
        }
    }

    /**
     * 往自己的按键分发里注入一个 KeyEvent。
     *
     * <p>为什么不直接调 moveChannel / setZone：
     * 那些是"结果"，而用户按方向键要的是"经过完整判定后的结果"。
     * 直接调结果方法会跳过层级、跳过焦点引擎、跳过节目单/设置的拦截，
     * 于是手机遥控和实体遥控器的行为对不上。
     *
     * <p>走 dispatchKeyEvent 就保证了只有一条代码路径。
     */
    private void injectKey(final int keyCode) {
        ui.post(new Runnable() {
            @Override public void run() {
                long now = android.os.SystemClock.uptimeMillis();
                android.view.KeyEvent down = new android.view.KeyEvent(
                        now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
                dispatchKeyEvent(down);
                android.view.KeyEvent up = new android.view.KeyEvent(
                        now, now + 30, android.view.KeyEvent.ACTION_UP, keyCode, 0);
                dispatchKeyEvent(up);
            }
        });
    }

    private String handleRemoteCommand(String type, String value) {
        if (type == null) return "未知命令";
        final String t = type;
        final String v = value == null ? "" : value;
        ui.post(new Runnable() {
            @Override public void run() {
                try {
                    if ("CH_UP".equals(t)) moveChannel(+1);
                    else if ("CH_DOWN".equals(t)) moveChannel(-1);
                    else if ("VOL_UP".equals(t)) handleVolumeKey(android.view.KeyEvent.KEYCODE_VOLUME_UP);
                    else if ("VOL_DOWN".equals(t)) handleVolumeKey(android.view.KeyEvent.KEYCODE_VOLUME_DOWN);
                    else if ("MUTE".equals(t)) handleVolumeKey(android.view.KeyEvent.KEYCODE_VOLUME_MUTE);
                    else if ("LINE".equals(t)) switchSource(+1);
                    // MENU / BACK / HOME 也统统注入真实按键。
                    // 它们以前各自判断 dialogOpen()/panelVisible，等于又写了一套
                    // 层级逻辑，和 KeyRouter 里的规则迟早对不上。
                    else if ("MENU".equals(t)) injectKey(android.view.KeyEvent.KEYCODE_MENU);
                    else if ("BACK".equals(t)) injectKey(android.view.KeyEvent.KEYCODE_BACK);
                    else if ("HOME".equals(t)) injectKey(android.view.KeyEvent.KEYCODE_HOME);
                    // OK 也走完整路由：设置开着时它是"确认当前行"，
                    // 节目单开着时是"播放选中节目"，面板收起时才是"唤回面板"。
                    // 以前这里写死 setPanelVisible(true)，设置开着按 OK
                    // 会把频道列表调到弹窗下面 —— 就是"设置还在却冒出频道列"。
                    else if ("OK".equals(t)) injectKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
                    // ── 方向键：注入真实 KeyEvent，走和遥控器完全一样的路由 ──
                    //
                    // 以前这里是 moveChannel()，直接换台 —— 它绕过了焦点引擎，
                    // 所以手机上按左右永远进不了分类列（后端压根没处理 LEFT/RIGHT），
                    // 按上下也只会换台、不会在列表里移动。
                    //
                    // 现在统一走 dispatchKeyEvent，行为与实体遥控器 100% 一致，
                    // 也就不存在"手机和遥控器行为不一样"这种问题。
                    else if ("UP".equals(t)) injectKey(android.view.KeyEvent.KEYCODE_DPAD_UP);
                    else if ("DOWN".equals(t)) injectKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                    else if ("LEFT".equals(t)) injectKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT);
                    else if ("RIGHT".equals(t)) injectKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
                    else if ("PLAYCH".equals(t)) {
                        playByNumber(v);
                    }
                    else if ("CAT".equals(t)) {
                        int ci = -1;
                        try { ci = Integer.parseInt(v); } catch (Throwable ignored) { }
                        if (ci >= 0 && ci < categories.size()) {
                            focus.setNavIndexSilently(ci);
                            switchCategory(ci);
                            updateNavFocus(ci);
                        }
                    }
                    else if ("PROG".equals(t)) {
                        int idx = -1;
                        try { idx = Integer.parseInt(v); } catch (Throwable ignored) { }
                        int ch = (playingChannel >= 0 && playingChannel < channels.size())
                                ? playingChannel : focus.chanIndex();
                        EpgParser.Program prog = programAt(ch, idx);
                        if (prog == null) { osd.toast("没有这个节目"); return; }
                        long nowMs = System.currentTimeMillis();
                        if (prog.startMs <= nowMs && prog.stopMs > nowMs) {
                            returnToLive(ch);
                        } else if (prog.stopMs <= nowMs) {
                            playReplay(ch, idx);
                        } else {
                            osd.toast("该节目还没开始");
                        }
                    }
                    else if ("DIGIT".equals(t)) {
                        for (int i = 0; i < v.length(); i++) {
                            char c = v.charAt(i);
                            if (c >= '0' && c <= '9') appendChannelDigit(c - '0');
                        }
                    }
                } catch (Throwable ignored) { }
            }
        });
        return "OK";
    }

    private boolean dialogOpen() {
        return dialogRoot != null && dialogRoot.getVisibility() == View.VISIBLE;
    }

    /**
     * 清掉早期版本自动塞进订阅列表的默认源。
     *
     * <p>背景：老代码在没配置源时返回硬编码的 guovin 地址，
     * 迁移逻辑把它当成用户自己的订阅存了下来。结果用户从没添加过订阅，
     * 列表里却有一条"默认订阅"。这里一次性移除，
     * 判断条件严格限定在"名字是 默认订阅 且 地址是这个域名 且 从没更新成功过"，
     * 用户自己加的 guovin 源（有更新时间或频道数）不会被动。
     */
    private void purgeLegacyDefaultSub() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences(PREF, MODE_PRIVATE);
            if (sp.getBoolean("legacy_sub_purged", false)) return;
            for (com.mintv.tv.data.SubscriptionStore.Sub s :
                    com.mintv.tv.data.SubscriptionStore.list(this)) {
                String url = s.url == null ? "" : s.url;
                if ("默认订阅".equals(s.name) && url.contains("guovin.github.io")
                        && s.lastUpdate == 0 && s.channelCount == 0) {
                    com.mintv.tv.data.SubscriptionStore.remove(this, s.id);
                    android.util.Log.i("MinTV", "已移除遗留的默认订阅");
                }
            }
            sp.edit().putBoolean("legacy_sub_purged", true).apply();
        } catch (Throwable ignored) { }
    }

    /**
     * 当前频道的节目单（手机端展开"正在播放"卡片时读）。
     *
     * <p>复用 EPG 面板那份 epgWindow，不另建一套解析 ——
     * 手机和电视看到的节目必须完全一致，否则用户会怀疑数据是假的。
     */
    /**
     * 分类与频道列表（手机端"频道"页读）。
     *
     * <p>cat 参数是分类下标（0 = 全部，从 1 开始是各分组），
     * 返回该分类下的频道。**不会真的切换盒子上的分类** ——
     * 手机浏览不该影响电视正在放的东西，只有用户点了具体的台才切。
     */
    private String buildChannelsJson(int cat) {
        try {
            org.json.JSONArray cats = new org.json.JSONArray();
            for (int i = 0; i < categories.size(); i++) {
                DemoData.Category c = categories.get(i);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("i", i);
                o.put("name", c.name);
                o.put("count", c.count);
                cats.put(o);
            }

            java.util.List<PlaylistParser.Channel> list;
            String catName;
            if (cat <= 0) {
                list = playlist == null ? null : playlist.all;
                catName = "全部";
            } else if (cat - 1 < categories.size()) {
                DemoData.Category c = categories.get(cat);
                catName = c.name;
                if (FAV_CATEGORY.equals(c.name)) {
                    list = favoriteChannels();
                } else {
                    list = playlist == null ? null : playlist.byGroup.get(c.name);
                }
            } else {
                list = null;
                catName = "";
            }

            org.json.JSONArray arr = new org.json.JSONArray();
            String nowNo = (playingChannel >= 0 && playingChannel < channels.size())
                    ? num4(channels.get(playingChannel).number) : "";
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    PlaylistParser.Channel c = list.get(i);
                    org.json.JSONObject o = new org.json.JSONObject();
                    String no = num4(c.number);
                    o.put("no", no);
                    o.put("name", c.name == null ? "" : c.name);
                    o.put("group", c.group == null ? "" : c.group);
                    o.put("playing", no.equals(nowNo));
                    arr.put(o);
                }
            }

            org.json.JSONObject root = new org.json.JSONObject();
            root.put("cat", cat);
            root.put("catName", catName);
            root.put("cats", cats);
            root.put("list", arr);
            return root.toString();
        } catch (Throwable t) {
            return "{\"cats\":[],\"list\":[]}";
        }
    }

    /**
     * 搜索频道。
     *
     * <p>两路匹配：
     * <ul>
     *   <li>频道名包含关键字；</li>
     *   <li>该频道**当前正在播**的节目名包含关键字。</li>
     * </ul>
     * 第二路是"我想看新闻"这种真实需求 —— 用户不记得哪个台在播新闻。
     * 只匹配"当前节目"，不扫全天节目单：600 个台 × 全天几十个节目
     * 在低端盒子上要几百毫秒，搜一次卡一下不值得。
     */
    /**
     * 搜索。
     *
     * <p>by：all = 频道名 + 节目名；name = 只搜频道名；prog = 只搜节目名。
     *
     * <p>按**节目**搜时匹配该频道的**全天节目单**（过去和未来的都算），
     * 不只是"正在播" —— 用户搜「春晚」可能是想看今晚的，也可能是想回看
     * 昨晚的，只看当前播放会把这两种都漏掉。
     *
     * <p>返回结果的主行永远是**频道名**，节目名只作为副行说明命中原因。
     */
    private String buildSearchJson(String q, String by) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            if (q == null) q = "";
            q = q.trim();
            if (q.isEmpty() || playlist == null || playlist.all == null) {
                return "{\"q\":\"\",\"list\":[]}";
            }
            String lower = q.toLowerCase(java.util.Locale.CHINA);
            long now = System.currentTimeMillis();
            int limit = 150;
            boolean byName = !"prog".equals(by);
            boolean byProg = !"name".equals(by);

            for (int i = 0; i < playlist.all.size() && arr.length() < limit; i++) {
                PlaylistParser.Channel c = playlist.all.get(i);
                boolean hitName = byName && c.name != null
                        && c.name.toLowerCase(java.util.Locale.CHINA).contains(lower);

                String hitProg = null;      // 命中的节目名
                long hitStart = 0;
                boolean hitLive = false;
                if (!hitName && byProg && epg != null) {
                    String id = epgIdOf.get(i);
                    if (id != null) {
                        java.util.List<EpgParser.Program> all = epg.of(id);
                        if (all != null) {
                            for (EpgParser.Program p : all) {
                                if (p.title == null) continue;
                                if (!p.title.toLowerCase(java.util.Locale.CHINA).contains(lower)) continue;
                                // 全表里挑最"有用"的一条：正在播的 > 未来的 > 更近的过去
                                boolean live = p.startMs <= now && p.stopMs > now;
                                if (hitProg == null
                                        || (live && !hitLive)
                                        || (live == hitLive && hitStart > 0
                                            && Math.abs(p.startMs - now) < Math.abs(hitStart - now))) {
                                    hitProg = p.title;
                                    hitStart = p.startMs;
                                    hitLive = live;
                                }
                            }
                        }
                    }
                }
                if (!hitName && hitProg == null) continue;

                org.json.JSONObject o = new org.json.JSONObject();
                o.put("no", num4(c.number));
                o.put("name", c.name == null ? "" : c.name);
                o.put("group", c.group == null ? "" : c.group);
                o.put("playing", i == playingChannel);
                if (hitProg != null) {
                    o.put("prog", hitProg);
                    o.put("live", hitLive);
                    o.put("progTime", EpgParser.hm(hitStart));
                }
                arr.put(o);
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("q", q);
            root.put("list", arr);
            return root.toString();
        } catch (Throwable t) {
            return "{\"q\":\"\",\"list\":[]}";
        }
    }

    /** 收藏分类的频道列表。 */
    private java.util.List<PlaylistParser.Channel> favoriteChannels() {
        java.util.List<PlaylistParser.Channel> out = new java.util.ArrayList<>();
        if (playlist == null || playlist.all == null) return out;
        for (PlaylistParser.Channel c : playlist.all) {
            if (isFavorite(c)) out.add(c);
        }
        return out;
    }

    /** EPG 查询：传台号则查那个台，不传则查当前在播的台。 */
    private String buildEpgJson(String no) {
        try {
            int ch = -1;
            if (no != null && !no.isEmpty()) {
                try {
                    int want = Integer.parseInt(no.replaceFirst("^0+", ""));
                    for (int i = 0; i < channels.size(); i++) {
                        if (channels.get(i).number == want) { ch = i; break; }
                    }
                } catch (Throwable ignored) { }
            }
            if (ch < 0) {
                ch = (playingChannel >= 0 && playingChannel < channels.size())
                        ? playingChannel : focus.chanIndex();
            }
            if (epgWindow.isEmpty() || ch != playingChannel) fillEpg(ch);
            long now = System.currentTimeMillis();
            PlaylistParser.Channel c = (ch >= 0 && ch < channels.size()) ? channels.get(ch) : null;
            boolean canReplay = hasReplay(ch);
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("name", c == null ? "" : c.name);
            root.put("canReplay", canReplay);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (int i = 0; i < epgWindow.size(); i++) {
                EpgParser.Program p = epgWindow.get(i);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("i", i);
                o.put("time", EpgParser.hm(p.startMs));
                o.put("title", p.title);
                boolean live = p.isLive(now);
                boolean ended = p.stopMs <= now;
                o.put("live", live);
                o.put("ended", ended);
                o.put("replay", ended && canReplay);
                o.put("start", p.startMs);
                arr.put(o);
            }
            root.put("list", arr);
            return root.toString();
        } catch (Throwable t) {
            return "{\"list\":[]}";
        }
    }

    private String buildSubsJson() {
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            String active = com.mintv.tv.data.SubscriptionStore.activeId(this);
            for (com.mintv.tv.data.SubscriptionStore.Sub s :
                    com.mintv.tv.data.SubscriptionStore.list(this)) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("headers", s.headers == null ? "" : s.headers);
                o.put("lastUpdate", s.lastUpdate);
                o.put("count", s.channelCount);
                o.put("epgUrl", s.epgUrl == null ? "" : s.epgUrl);
                o.put("active", s.id != null && s.id.equals(active));
                arr.put(o);
            }
            org.json.JSONObject root = new org.json.JSONObject();
            root.put("subs", arr);
            root.put("epgUrl", epgUrlOf(this));
            root.put("headers", globalHeadersText());
            root.put("port", remotePort);
            root.put("ip", com.mintv.tv.data.LocalIp.get(this));
            return root.toString();
        } catch (Throwable t) {
            return "{\"subs\":[],\"epgUrl\":\"\",\"headers\":\"\"}";
        }
    }

    private String handleSubsAction(String action, org.json.JSONObject p) {
        try {
            if ("activate".equals(action)) {
                com.mintv.tv.data.SubscriptionStore.setActive(this, p.optString("id"));
                ui.post(new Runnable() {
                    @Override public void run() {
                        switchSubscription(com.mintv.tv.data.SubscriptionStore.activeId(
                                MainActivity.this));
                    }
                });
                return "{\"ok\":true}";
            }
            if ("reload".equals(action)) {
                final String id = p.optString("id");
                ui.post(new Runnable() {
                    @Override public void run() {
                        com.mintv.tv.data.SubscriptionStore.Sub s =
                                com.mintv.tv.data.SubscriptionStore.find(MainActivity.this, id);
                        if (s != null) fetchSourceAsync(s.url);
                    }
                });
                return "{\"ok\":true}";
            }
            if ("remove".equals(action)) {
                com.mintv.tv.data.SubscriptionStore.remove(this, p.optString("id"));
                ui.post(new Runnable() {
                    @Override public void run() { rebuildHelperSubs(); }
                });
                return "{\"ok\":true}";
            }
            if ("epg".equals(action)) {
                final String epgUrl = p.optString("url", "");
                final String targetId = p.optString("id", "");
                com.mintv.tv.data.SubscriptionStore.Sub target =
                        targetId.isEmpty()
                                ? com.mintv.tv.data.SubscriptionStore.active(this)
                                : com.mintv.tv.data.SubscriptionStore.find(this, targetId);
                if (target != null) {
                    target.epgUrl = epgUrl;
                    com.mintv.tv.data.SubscriptionStore.update(this, target);
                } else {
                    // 没有任何订阅时退回全局
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putString("epg_url", epgUrl).apply();
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        epg = null;
                        loadEpgAsync();
                    }
                });
                return "{\"ok\":true}";
            }
            if ("headers".equals(action)) {
                final String h = p.optString("headers", "");
                getSharedPreferences(PREF, MODE_PRIVATE).edit()
                        .putString("global_headers", h).apply();
                ui.post(new Runnable() {
                    @Override public void run() { loadHeaders(); }
                });
                return "{\"ok\":true}";
            }
            if ("save".equals(action)) {
                com.mintv.tv.data.SubscriptionStore.Sub s =
                        new com.mintv.tv.data.SubscriptionStore.Sub();
                s.id = p.optString("id", null);
                s.name = p.optString("name", "未命名");
                s.url = p.optString("url", "");
                s.headers = p.optString("headers", "");
                s.epgUrl = p.optString("epgUrl", "");
                if (s.url.isEmpty()) return "{\"ok\":false,\"msg\":\"地址不能为空\"}";
                com.mintv.tv.data.SubscriptionStore.upsert(this, s);
                ui.post(new Runnable() {
                    @Override public void run() { rebuildHelperSubs(); }
                });
                return "{\"ok\":true}";
            }
            return "{\"ok\":false,\"msg\":\"未知操作\"}";
        } catch (Throwable t) {
            return "{\"ok\":false}";
        }
    }

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
        osd.setTags(src, null, lastResolution, lastVCodec, lastACodec, lastChannels,
                lastFps, lastBitrate, speed);
    }

    // ================= EPG =================

    /**
     * 加载 EPG。
     *
     * <p><b>读文件也必须在这条后台线程里</b>：之前 EpgStore.cached(this)
     * 写在方法最前面、跑在主线程上 —— EPG 有 1MB 上下，走 SAF 读一次
     * 要几百毫秒。而它每次切订阅都会被调用，这就是切订阅卡顿的主因。
     *
     * <p>方法名早就是 Async 了，但真正异步的只有「解析」，读盘一直在主线程。
     */
    private void loadEpgAsync() {
        new Thread(new Runnable() {
            @Override public void run() {
                final byte[] cached;
                final boolean fresh;
                try {
                    cached = EpgStore.cached(MainActivity.this);
                    fresh = EpgStore.isFresh(MainActivity.this);
                } catch (Throwable t) {
                    android.util.Log.w("MinTV", "读 EPG 缓存失败", t);
                    return;
                }

                if (cached != null) {
                    final EpgParser.Epg e = EpgParser.parse(cached);
                    ui.post(new Runnable() {
                        @Override public void run() { applyEpg(e); }
                    });
                }
                if (cached != null && fresh) return;

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
            applyConfiguredHeaders(conn, globalHeaders);
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
    /**
     * 没有订阅时的空状态。
     *
     * <p>不再用假频道填满列表 —— 空着但说清楚"怎么变成有内容"，
     * 比拿演示数据糊住用户诚实得多。
     */
    private void showChannelEmptyHint() {
        if (chanColumn == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Theme.px(28), Theme.px(40), Theme.px(28), Theme.px(20));

        TextView t1 = new TextView(this);
        t1.setText("还没有添加订阅");
        t1.setTextColor(Theme.TEXT);
        t1.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(22));
        t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(t1);

        TextView t2 = new TextView(this);
        boolean hasRemote = remotePort > 0;
        String ip = com.mintv.tv.data.LocalIp.get(this);
        t2.setText(hasRemote
                ? "用手机浏览器打开\n" + ip + ":" + remotePort + "\n粘贴 m3u / txt 源地址即可添加。"
                : "按 MENU → 手机助手 查看添加地址。");
        t2.setTextColor(Theme.TEXT_SUB);
        t2.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(17));
        t2.setLineSpacing(Theme.px(8), 1f);
        t2.setPadding(0, Theme.px(14), 0, 0);
        box.addView(t2);

        if (hasRemote) {
            TextView t3 = new TextView(this);
            t3.setText("盒子地址：" + ip + ":" + remotePort);
            t3.setTextColor(Theme.FOCUS_BLUE);
            t3.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(16));
            t3.setTypeface(android.graphics.Typeface.MONOSPACE);
            t3.setPadding(0, Theme.px(16), 0, 0);
            box.addView(t3);
        }

        chanColumn.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(230)));
    }

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
            // 「可回看」只在焦点行显示：八百多行每行都挂一个灰绿胶囊，
            // 信息噪音太大，反而看不清台名。
            v.setBadge((hasReplay(idx) && idx == focus.chanIndex()) ? "可回看" : null);
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
        if (id == null) {
            epgNowText = null; epgNextText = null; epgProgress = -1f;
            osd.setProgram(null, null, -1f);
            return;
        }
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
        epgNowText = nowText;
        epgNextText = nextText;
        epgProgress = prog;
        osd.setProgram(nowText, nextText, prog);
    }

    /** 展开抽屉：分类收进去，露出「频道列表 + EPG 节目」。 */
    private void showEpgPanel(int channelIndex) {
        // 设置开着的时候不弹节目单：两层同时存在，遥控必然打架
        if (dialogRoot != null && dialogRoot.getVisibility() == View.VISIBLE) return;
        fillEpg(channelIndex);
        // 把节目单的光标落在"正在播"那一行，用户一进来就知道自己在哪
        if (epgPanel != null) {
            int live = epgPanel.liveIndex();
            epgCursor = live >= 0 ? live : 0;
            epgPanel.setFocus(epgCursor);
            scrollEpgTo(epgCursor);
        }
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

        // 纯流式：GONE 不占位，后面的列自动补齐。
        // 不加任何 translationX —— 之前 GONE + 位移两套一起用，
        // 频道列被推了两次，出屏/压缩/左边黑一块都是这么来的。
        navPanel.animate().alpha(0f).setDuration(Theme.T_PANEL).start();
        navDivider.animate().alpha(0f).setDuration(Theme.T_PANEL).start();
        railView.animate().alpha(0f).setDuration(Theme.T_PANEL).start();
        if (subColumn != null) {
            subColumn.animate().alpha(0f).setDuration(Theme.T_PANEL).start();
        }
        for (NavItemView v : navViews) v.setClickable(false);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!epgPanelOpen) return;
                navPanel.setVisibility(View.GONE);          // 不占位 → 频道列自动到最左
                navDivider.setVisibility(View.GONE);
                railView.setVisibility(View.GONE);
                if (subColumn != null) subColumn.setVisibility(View.GONE);
            }
        }, Theme.T_PANEL + 40);
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

        // 恢复各列：VISIBLE 之后流式自动把频道列推回原位，不用位移
        navPanel.setVisibility(View.VISIBLE);
        if (subColumn != null) subColumn.setVisibility(View.VISIBLE);
        navDivider.setVisibility(View.VISIBLE);
        railView.setVisibility(View.VISIBLE);
        navPanel.animate().alpha(1f).setDuration(Theme.T_PANEL).start();
        navDivider.animate().alpha(1f).setDuration(Theme.T_PANEL).start();
        railView.animate().alpha(1f).setDuration(Theme.T_PANEL).start();
        if (subColumn != null) subColumn.animate().alpha(1f).setDuration(Theme.T_PANEL).start();
        for (NavItemView v : navViews) v.setClickable(true);
        layoutVideoTap();
        updateOsdInset();

        // 焦点必须回到频道列。
        // 少了这一步，关掉节目单后焦点引擎还停在"节目单已开"的旧状态，
        // 再按左右键既不动分类也不动频道 —— 看起来就是"界面卡住了"。
        if (focus.zone() != FocusEngine.Zone.CHAN) {
            focus.setZone(FocusEngine.Zone.CHAN);
        }
        updateNavFocus(focus.navIndex());
        updateChannelFocus(focus.chanIndex());
    }

    /** ③ 纯播放：整套面板淡出，只留视频。任意方向键/点击恢复。 */
    private void enterFullPlayer() {
        // 以前这里有个"没起播就不许收面板"的门控，本意是防止误入死状态。
        // 但点击唤回已经补上了兜底，这个门控反而把用户锁在面板上：
        // 频道卡在缓冲（playing=false）时永远收不起来，只能先换台 —— 这就是
        // "必须换一个台再隐藏"的原因。现在无条件允许收起。
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
            // 面板打开时才补做延后的列表重建（无感刷新留下的）
            if (channelsDirty) {
                channelsDirty = false;
                try {
                    rebuildCategoryColumn();
                    bindChannels();
                    updateChannelFocus(Math.min(focus.chanIndex(),
                            Math.max(0, channels.size() - 1)));
                    fillSubColumn();
                    android.util.Log.i("MinTV", "补重建频道列表（无感刷新延后）");
                } catch (Throwable t) {
                    android.util.Log.w("MinTV", "补重建失败", t);
                }
            }
            // 先恢复可见，再做淡入
            setPanelsInteractive(true);
            navPanel.setVisibility(View.VISIBLE);
            if (subColumn != null) subColumn.setVisibility(View.VISIBLE);
            navDivider.setVisibility(View.VISIBLE);
            chanContainer.setVisibility(View.VISIBLE);
            railView.setVisibility(View.VISIBLE);
            if (subColumn != null) subColumn.setVisibility(View.VISIBLE);
            scrim.setVisibility(View.VISIBLE);
        }

        // 整体控制容器，不再逐列算位移。
        //
        // 逐列 translationX 的问题是每列都要算"前面有多少列"，
        // 加一列就有一处漏算 —— 全屏时订阅列不消失、抽屉态频道列出屏
        // 都是这么来的。容器整体淡出，谁也不会漏。
        if (colsRow != null) {
            colsRow.animate().alpha(to).setDuration(Theme.T_PANEL).start();
            colsRow.setVisibility(to > 0f ? View.VISIBLE : View.VISIBLE);  // 保持 VISIBLE，靠 alpha 隐藏
            if (!visible) {
                colsRow.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (colsRow != null && !panelVisible) colsRow.setVisibility(View.GONE);
                    }
                }, Theme.T_PANEL + 40);
            }
        }
        navPanel.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        navDivider.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        chanContainer.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        railView.animate().alpha(to).setDuration(Theme.T_PANEL).start();
        // 订阅列在主面板里，跟着一起显隐。
        // 之前漏了它：全屏播放时订阅列留在左边占着 200px。
        if (subColumn != null) {
            subColumn.animate().alpha(to).setDuration(Theme.T_PANEL).start();
            if (!visible) {
                navPanel.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (subColumn != null && !panelVisible) {
                            // 同样是 INVISIBLE：GONE 会让后面的列前移，
                            // 而位移动画是按"订阅列占位"算的
                            subColumn.setVisibility(View.INVISIBLE);
                        }
                    }
                }, Theme.T_PANEL + 40);
            }
        }
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
                    // INVISIBLE 不是 GONE：GONE 会让 LinearLayout 重新排布，
            // 后面的列自动前移，而 chanContainer 的 translationX 是按
            // "订阅列还占着 200px"算的 —— 结果频道列被推出屏幕，
            // 看起来就是抽屉态左边一片空白。
            if (subColumn != null) subColumn.setVisibility(View.INVISIBLE);
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

        // ── 焦点状态跟面板一起切换 ──
        //
        // 这是"左键进不去分类列"的真正原因：面板收起期间 focus.onKey 被
        // L4 守卫拦住，但 focus.zone 里存的还是上一次的值。面板重新可见时
        // 如果 zone 残留在 NAV（上次停的），左键就会走"导航列最左：返回
        // false"那条路 —— 于是没有任何反应。
        //
        // 明确规则：面板刚恢复可见，焦点一律落在「频道列」。
        if (visible) {
            if (focus.zone() != FocusEngine.Zone.CHAN) {
                focus.setZone(FocusEngine.Zone.CHAN);
            }
            updateNavFocus(focus.navIndex());
            updateChannelFocus(focus.chanIndex());
        }
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
        playUrlEntry(url, null, PlaybackBackend.Mode.TIMESHIFT);
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
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // 左右都关闭：从频道列按右进来了，按右再回去最符合直觉，
                // 按左也对（和列表方向一致）。不用逼用户记方向。
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
        // 屏幕正中：之前是 TOP + topMargin 50，弹窗粘在屏幕上方，
        // 内容少的时候下面一大片空，看起来就是"不居中"。
        cardLp.gravity = Gravity.CENTER;
        cardLp.topMargin = 0;

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
            // 初始色也跟主题走，避免首帧闪一下灰白
            tab.setBackgroundColor(i == 1
                    ? ((Theme.FOCUS_BLUE & 0x00FFFFFF) | 0x2E000000)
                    : Color.TRANSPARENT);
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

            // 分组标题：滚动时能看出「这里换了一组」
            for (int g = 0; g < GROUP_START.length; g++) {
                if (GROUP_START[g] != i) continue;
                rowColumn.addView(buildGroupHeader(GROUP_NAME[g]));
                break;
            }

            SettingRowView row = new SettingRowView(this);
            // 有些行右侧该显示「当前值」而不是固定文案：
            //   主题颜色 → 现在选的色名
            //   脚本目录 → 现在用的目录名
            // 文案是 SettingDef 里写死的（"选择…"），这里替换掉。
            row.bind(DemoData.ROW_TITLES[i][0], hintFor(i),
                    optionLabelsFor(i), selections[i]);
            row.setHintColor(
                    (i == ROW_P2P_BOOST && selections[i] == 1
                            && lastNatResult != null && lastNatResult.canServe())
                            ? 0xFF2EA85A : 0);
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
            // 行高按"有没有副标题"自适应。
            // 以前固定 82px，而副标题基线画在 y=62、字号 20，底部就到 82+，
            // 直接被裁掉一半 —— 截图里"请求头"那行副标题看着只有上半截。
            boolean hasDesc = DemoData.ROW_TITLES[i][1] != null
                    && !DemoData.ROW_TITLES[i][1].isEmpty();
            int rowH = hasDesc ? Theme.px(96) : Theme.px(72);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowH);
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
        // 内容不满一屏时垂直居中，别顶在上面留一大片空。
        // 超过一屏时 ScrollView 的 fillViewport 不起作用，gravity 也失效，
        // 自然回到顶对齐 + 滚动 —— 不会切掉顶部。
        // 只有垂直方向居中：内容不满一屏时别顶在上面。
        // 水平方向保持左对齐 —— 版本号、条款这类文字居中后换行会参差。
        infoPage.setGravity(Gravity.CENTER_VERTICAL);
        infoPage.setPadding(Theme.px(28), Theme.px(20), Theme.px(28), Theme.px(20));
        infoPage.setVisibility(View.GONE);
        infoPageText = new TextView(this);
        infoPageText.setTextColor(0xFFD6D6DA);
        infoPageText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(19));
        infoPageText.setLineSpacing(Theme.px(6), 1f);
        infoPage.addView(infoPageText);

        // 免责声明。
        //
        // 之前是「淡背景块 + 左侧竖条」，那个 5% 白叠在深色底上会发灰，
        // 看起来像一块没洗干净的补丁。现在改成一条通栏细线 + 左对齐文字，
        // 也就是微信/B站那种法律条款的排法：不加框，靠留白和分隔线区分。
        disclaimerBox = new LinearLayout(this);
        disclaimerBox.setOrientation(LinearLayout.VERTICAL);
        disclaimerBox.setVisibility(View.GONE);

        View dRule = new View(this);
        dRule.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(1));
        ruleLp.topMargin = Theme.px(26);
        ruleLp.bottomMargin = Theme.px(20);
        disclaimerBox.addView(dRule, ruleLp);

        TextView dTitle = new TextView(this);
        dTitle.setText("免责声明");
        dTitle.setTextColor(Theme.FOCUS_BLUE);
        dTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(17));
        dTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        dTitle.setLetterSpacing(0.08f);
        disclaimerBox.addView(dTitle);

        TextView dBody = new TextView(this);
        dBody.setText("一、本应用仅为本地播放工具，不提供、不存储、不分发任何音视频内容。\n"
                + "二、所有直播源、EPG 数据与脚本均由用户自行添加，其来源与本应用无关。\n"
                + "三、用户应确保所添加内容的来源合法，并自行承担使用过程中的全部责任。\n"
                + "四、本应用按「现状」提供，不对可用性、稳定性及适用性作任何担保。\n"
                + "五、本应用不主张任何频道内容的著作权，相关权利归原权利人所有。\n"
                + "六、使用本应用即表示已阅读并同意以上条款；如不接受，请停止使用并卸载。");
        dBody.setTextColor(0xFF8A939C);
        dBody.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(15));
        dBody.setLineSpacing(Theme.px(9), 1.1f);
        LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dbLp.topMargin = Theme.px(12);
        disclaimerBox.addView(dBody, dbLp);

        infoPage.addView(disclaimerBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 检测更新。放在关于页最下面 —— 低频操作，不该占显眼位置。
        updateBtn = new TextView(this);
        updateBtn.setText("检测更新");
        updateBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(15));
        updateBtn.setGravity(Gravity.CENTER);
        updateBtn.setPadding(Theme.px(22), Theme.px(11), Theme.px(22), Theme.px(11));
        android.graphics.drawable.GradientDrawable ubBg =
                new android.graphics.drawable.GradientDrawable();
        ubBg.setCornerRadius(Theme.px(11));
        ubBg.setColor(0x16FFFFFF);
        ubBg.setStroke(Theme.px(1), 0x2AFFFFFF);
        updateBtn.setBackground(ubBg);
        updateBtn.setTextColor(Theme.TEXT);
        updateBtn.setFocusable(true);
        updateBtn.setClickable(true);
        updateBtn.setVisibility(View.GONE);
        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { checkUpdate(); }
        });
        LinearLayout.LayoutParams ubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ubLp.topMargin = Theme.px(28);
        ubLp.bottomMargin = Theme.px(8);
        updateBtn.setLayoutParams(ubLp);
        infoPage.addView(updateBtn);

        // 内容可能超出一屏（关于页加了免责声明之后就超了），所以必须能滚。
        //
        // 原来这里是 LinearLayout + 上下弹性空白做垂直居中，
        // 内容是裁在固定高度里的 —— 一屏放不下就直接看不见，还不报错。
        // 换成 ScrollView 之后，顶部对齐、超出滚动，内容再长也不会丢。
        android.widget.ScrollView infoWrap = new android.widget.ScrollView(this);
        infoWrap.setVisibility(View.GONE);
        infoWrap.setFillViewport(true);
        infoWrap.addView(infoPage, new android.view.ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        infoPageWrap = infoWrap;
        card.addView(infoWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowsLp.height));

        // ---------- 手机助手页：左二维码 + 右订阅列表 ----------
        //
        // 布局照 HTML 原型走：左边 QR 340px + 地址 + 说明，
        // 中间一条竖线，右边标题 + 状态 + 订阅卡片。
        // 之前只是把元素堆上去，比例和层次都没对上，看着像半成品。
        helperPage = new LinearLayout(this);
        helperPage.setOrientation(LinearLayout.HORIZONTAL);
        helperPage.setVisibility(View.GONE);
        helperPage.setPadding(Theme.px(28), Theme.px(10), Theme.px(28), 0);

        // ---- 左：二维码 + 地址 ----
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.setPadding(0, Theme.px(30), 0, 0);

        qrView = new android.widget.ImageView(this);
        qrView.setBackgroundColor(0xFFF7F7F7);
        qrView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        int qrSize = Theme.px(300);
        LinearLayout.LayoutParams qrLp =
                new LinearLayout.LayoutParams(qrSize, qrSize);
        left.addView(qrView, qrLp);

        // 地址：QR 正下方，等宽字体、主题蓝
        helperAddr = new TextView(this);
        helperAddr.setTextColor(Theme.FOCUS_BLUE);
        helperAddr.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(21));
        helperAddr.setTypeface(android.graphics.Typeface.MONOSPACE);
        helperAddr.setGravity(Gravity.CENTER);
        helperAddr.setPadding(0, Theme.px(18), 0, 0);
        helperAddr.setSingleLine(true);
        helperAddr.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        left.addView(helperAddr, new LinearLayout.LayoutParams(
                Theme.px(360), ViewGroup.LayoutParams.WRAP_CONTENT));

        helperState = new TextView(this);
        helperState.setTextColor(Theme.TEXT_SUB);
        helperState.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(16));
        helperState.setGravity(Gravity.CENTER);
        helperState.setPadding(0, Theme.px(10), 0, 0);
        left.addView(helperState);

        helperPage.addView(left, new LinearLayout.LayoutParams(
                Theme.px(380), ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 竖分隔线 ----
        View divider = new View(this);
        divider.setBackgroundColor(Theme.LINE);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                Math.max(1, Theme.px(1)), ViewGroup.LayoutParams.MATCH_PARENT);
        dLp.leftMargin = Theme.px(22);
        dLp.rightMargin = Theme.px(34);
        dLp.bottomMargin = Theme.px(70);
        helperPage.addView(divider, dLp);

        // ---- 右：标题 + 状态 + 订阅列表 ----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(0, Theme.px(24), 0, Theme.px(10));

        TextView title = new TextView(this);
        title.setText("切换订阅");
        title.setTextColor(Theme.TEXT);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(27));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        right.addView(title);

        TextView tip = new TextView(this);
        tip.setText("手机扫码可导入 m3u / txt 源、改 EPG 和请求头");
        tip.setTextColor(Theme.TEXT_SUB);
        tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(15));
        tip.setPadding(0, Theme.px(8), 0, Theme.px(16));
        right.addView(tip);

        helperSubs = new LinearLayout(this);
        helperSubs.setOrientation(LinearLayout.VERTICAL);
        ScrollView subsScroll = new ScrollView(this);
        subsScroll.setVerticalScrollBarEnabled(false);
        subsScroll.addView(helperSubs);
        right.addView(subsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView rf = new TextView(this);
        rf.setText("用遥控器  ↑ ↓  选择，OK 切换订阅");
        rf.setTextColor(Theme.TEXT_SUB);
        rf.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(15));
        rf.setPadding(0, Theme.px(14), 0, 0);
        right.addView(rf);

        helperPage.addView(right, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        card.addView(helperPage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowsLp.height));

        // 底栏
        TextView foot = new TextView(this);
        foot.setText("ⓘ ← → 直接改值（即时生效）    ↑ 进顶部标签页    OK 打开/确认    ESC 返回");
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
    /**
     * 重绘 Tab 条的三种状态。
     *
     * <p>之前只区分「当前页」和「其它页」，没有任何「焦点在 Tab 条上」的视觉反馈 ——
     * 用户按上键进了 Tab 条却看不出来，以为按键没反应。
     * 现在：焦点在 Tab 条时当前页用实心蓝底 + 白字，没焦点时用淡底。
     */
    /** 收集当前页可聚焦的控件，并把焦点落在第一个。 */
    private void rebuildPageFocus() {
        pageFocus.clear();
        if (currentTab == TAB_ABOUT) {
            if (updateBtn != null && updateBtn.getVisibility() == View.VISIBLE) {
                pageFocus.add(updateBtn);
            }
        } else if (currentTab == TAB_HELPER && helperSubs != null) {
            for (int i = 0; i < helperSubs.getChildCount(); i++) {
                View v = helperSubs.getChildAt(i);
                if (v.isFocusable() || v.isClickable()) pageFocus.add(v);
            }
        }
        setPageFocus(pageFocus.isEmpty() ? -1 : 0);
    }

    /** 高亮第 idx 个控件，其余恢复常态。 */
    private void setPageFocus(int idx) {
        pageFocusIdx = idx;
        for (int i = 0; i < pageFocus.size(); i++) {
            View v = pageFocus.get(i);
            boolean on = (i == idx);
            Object tag = v.getTag();
            if (v instanceof LinearLayout && tag instanceof Boolean) {
                // 订阅卡片：自己的圆角底，带 focus 参数重画
                v.setBackground(new SubCardBg((Boolean) tag, on));
            } else {
                v.setBackgroundColor(on ? Theme.FOCUS_BLUE : Theme.DLG_OPT_BG);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(on ? 0xFFFFFFFF : Theme.TEXT);
                }
            }
        }
    }

    private void paintTabs() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean cur = (i == currentTab);
            if (tabFocus) {
                t.setTextColor(cur ? 0xFFFFFFFF : 0xFFD6D6D8);
                t.setBackgroundColor(cur ? Theme.FOCUS_BLUE : 0x1FFFFFFF);
            } else {
                // 当前 tab 的底也要跟主题色走 —— 原来写死白色 6%，
                // 换了主题色只有焦点态是新的，非焦点态还是灰白。
                // 当前 tab 文字用白 —— 主题色可能很暗，当文字会看不见
                t.setTextColor(cur ? 0xFFFFFFFF : 0xFFD6D6D8);
                t.setBackgroundColor(cur
                        ? ((Theme.FOCUS_BLUE & 0x00FFFFFF) | 0x2E000000)
                        : Color.TRANSPARENT);
            }
        }
    }

    private void showTab(int index) {
        currentTab = index;
        paintTabs();
        boolean isSettings = (index == TAB_SETTINGS);
        boolean isHelper = (index == TAB_HELPER);
        rowScrollView.setVisibility(isSettings ? View.VISIBLE : View.GONE);
        if (helperPage != null) {
            helperPage.setVisibility(isHelper ? View.VISIBLE : View.GONE);
            if (isHelper) {
                refreshHelperPage();
                ui.post(new Runnable() {
                    @Override public void run() { rebuildPageFocus(); }
                });
                ui.removeCallbacks(helperTick);
                ui.postDelayed(helperTick, 2000);
            } else {
                ui.removeCallbacks(helperTick);
            }
        }
        if (!isSettings && !isHelper) {
            fillInfoPage(index);
            if (updateBtn != null) {
                updateBtn.setVisibility(index == TAB_ABOUT ? View.VISIBLE : View.GONE);
            }
            infoPage.setVisibility(View.VISIBLE);
            if (infoPageWrap != null) infoPageWrap.setVisibility(View.VISIBLE);
            ui.post(new Runnable() {
                @Override public void run() { rebuildPageFocus(); }
            });
        } else {
            // 播放设置 / 手机助手页：说明页整个隐藏。
            // 外层 wrapper 必须一起收，否则从「关于」切回「播放设置」时
            // 那层空容器还盖在列表上面，遥控器看起来"点不动"。
            infoPage.setVisibility(View.GONE);
            if (infoPageWrap != null) infoPageWrap.setVisibility(View.GONE);
        }
    }


    // ================= 检测更新 =================
    //
    // 版本信息放在**产物仓库** mintv-apk 里，不是主仓库。
    // 主仓库总有一天会转私有，那时 raw 链接对匿名用户全是 401；
    // 而产物仓库本来就该公开，客户端也只需要看到它。
    //
    // version.json 由 CI 里的 .ci/gen_version.py 从 git log 生成，
    // 带最近几个版本的更新要点，格式：
    //   { versionCode, versionName, date, changes, history[], apk }

    private static final String UPDATE_JSON =
            "https://raw.githubusercontent.com/XiaoZhang5656/mintv-apk/main/version.json";

    /** 系统 Toast。OSD 在设置页里被压在下面，提示看不到。 */
    private void tip(String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            if (osd != null) osd.toast(msg);
        }
    }

    private void checkUpdate() {
        tip("正在检查更新…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String body = httpGet(UPDATE_JSON);
                ui.post(new Runnable() {
                    @Override public void run() { onUpdateChecked(body); }
                });
            }
        }, "check-update").start();
    }

    private void onUpdateChecked(String json) {
        if (json == null || json.trim().isEmpty()) {
            tip("检查更新失败：网络不可达");
            return;
        }
        int remote;
        String apkUrl;
        final org.json.JSONObject o;
        try {
            o = new org.json.JSONObject(json);
            remote = o.optInt("versionCode", -1);
            apkUrl = o.optString("apk", "");
            if (remote <= 0 || apkUrl.isEmpty()) {
                tip("检查更新失败：版本信息不完整");
                return;
            }
        } catch (Throwable t) {
            tiplog("解析版本信息失败", t);
            return;
        }

        int local = versionCode();
        if (remote <= local) {
            tip("已是最新版本（" + versionName() + "）");
            return;
        }

        showUpdateDialog(o, apkUrl);
    }

    /**
     * 更新对话框。
     *
     * <p>不用 setMessage：系统默认深色底在深色主题下接近纯黑，字挤在一起，
     * 而且没法放进度条。这里自己搭一个：深灰卡片 + 圆角 + 可滚动正文 +
     * 一条默认隐藏的下载进度条。
     */
    /**
     * 更新对话框。
     *
     * <p>自己搭布局，不用 setMessage：系统默认消息区是纯文本，版本号、日期、
     * 要点全挤成一坨，长内容还会溢出。这里按区块渲染：
     *
     * <pre>
     *   ┌────────────────────────────┐
     *   │ 发现新版本        0.3.88 [新]│
     *   ├────────────────────────────┤
     *   │ 2026-11-07 20:15            │
     *   │  · OSD 进度条重做            │
     *   │  · 底轨统一成深灰实心         │   ← ScrollView
     *   │                             │
     *   │ 2026-11-07 19:50            │
     *   │  · ...                      │
     *   ├────────────────────────────┤
     *   │ ▓▓▓▓▓░░░░░░  12.3 / 48.2 MB │   ← 点更新后出现
     *   │            稍后   立即更新   │
     *   └────────────────────────────┘
     * </pre>
     */
    private void showUpdateDialog(final org.json.JSONObject info, final String apkUrl) {
        final String remoteName = info.optString("versionName", "");

        final android.widget.LinearLayout body =
                new android.widget.LinearLayout(this);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);

        // ── 顶部：版本号徽标 + 日期 ──
        final android.widget.LinearLayout head =
                new android.widget.LinearLayout(this);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(Theme.px(22), Theme.px(16), Theme.px(22), Theme.px(2));

        android.widget.TextView ver = new android.widget.TextView(this);
        ver.setText(remoteName);
        ver.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(21));
        ver.setTextColor(0xFFF2F5FA);
        ver.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(ver);

        android.widget.TextView badge = new android.widget.TextView(this);
        badge.setText("新版本");
        badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(11));
        badge.setTextColor(0xFF7AA8FF);
        badge.setBackground(makeTagBg());
        badge.setPadding(Theme.px(7), Theme.px(2), Theme.px(7), Theme.px(2));
        android.widget.LinearLayout.LayoutParams blp0 =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        blp0.leftMargin = Theme.px(10);
        head.addView(badge, blp0);

        body.addView(head, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── 中间：可滚动的更新日志 ──
        android.widget.LinearLayout log = new android.widget.LinearLayout(this);
        log.setOrientation(android.widget.LinearLayout.VERTICAL);
        log.setPadding(Theme.px(22), Theme.px(6), Theme.px(22), Theme.px(8));

        org.json.JSONArray hist = info.optJSONArray("history");
        boolean first = true;
        if (hist != null && hist.length() > 0) {
            for (int i = 0; i < hist.length() && i < 6; i++) {
                org.json.JSONObject h = hist.optJSONObject(i);
                if (h == null) continue;
                if (!first) log.addView(makeDivider());
                first = false;
                addLogBlock(log, h.optString("date", ""),
                        h.optJSONArray("changes"));
            }
        } else {
            addLogBlock(log, info.optString("date", ""),
                    info.optJSONArray("changes"));
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(log, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(sv, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));   // weight=1 撑满剩余

        // ── 底部：下载进度（初始隐藏）──
        final android.widget.LinearLayout foot =
                new android.widget.LinearLayout(this);
        foot.setOrientation(android.widget.LinearLayout.VERTICAL);
        foot.setPadding(Theme.px(22), Theme.px(4), Theme.px(22), Theme.px(2));
        foot.setVisibility(View.GONE);

        final android.widget.ProgressBar bar = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        bar.setProgressDrawable(makeBarBg());
        foot.addView(bar, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(5)));

        final android.widget.TextView status = new android.widget.TextView(this);
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(12));
        status.setTextColor(0xFF9AA0AA);
        android.widget.LinearLayout.LayoutParams slp0 =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        slp0.topMargin = Theme.px(7);
        foot.addView(status, slp0);

        body.addView(foot);

        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("发现新版本")
                .setView(body)
                .setNegativeButton("稍后", null)
                .setPositiveButton("立即更新", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        foot.setVisibility(View.VISIBLE);
                        status.setText("正在下载…");
                        downloadAndInstall(apkUrl, bar, status);
                    }
                })
                .show();
    }

    /** 一个版本的日志区块：日期小字 + 圆点要点。 */
    private void addLogBlock(android.widget.LinearLayout parent, String date,
                             org.json.JSONArray changes) {
        if (date != null && !date.isEmpty()) {
            android.widget.TextView d = new android.widget.TextView(this);
            d.setText(date);
            d.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(11));
            d.setTextColor(0xFF6E7887);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Theme.px(10);
            lp.bottomMargin = Theme.px(6);
            parent.addView(d, lp);
        }
        if (changes == null) return;
        for (int k = 0; k < changes.length(); k++) {
            String line = changes.optString(k, "");
            if (line.isEmpty()) continue;

            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);

            android.widget.TextView dot = new android.widget.TextView(this);
            dot.setText("·");
            dot.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(15));
            dot.setTextColor(0xFF4E8FEB);
            dot.setPadding(0, 0, Theme.px(8), 0);
            row.addView(dot);

            android.widget.TextView t = new android.widget.TextView(this);
            t.setText(line);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Theme.sp(13));
            t.setTextColor(0xFFC6CDD6);
            t.setLineSpacing(Theme.px(3), 1f);
            row.addView(t, new android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Theme.px(4);
            parent.addView(row, lp);
        }
    }

    /** 版本之间的细分隔线。 */
    private View makeDivider() {
        View v = new View(this);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(1));
        lp.topMargin = Theme.px(8);
        lp.bottomMargin = Theme.px(2);
        v.setLayoutParams(lp);
        v.setBackgroundColor(0x14FFFFFF);
        return v;
    }

    /** 「新版本」小标签的底。 */
    private android.graphics.drawable.Drawable makeTagBg() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Theme.px(4));
        g.setColor(0x2E4E8FEB);
        return g;
    }

    /** 下载进度条：圆角 + 主题色。 */
    private android.graphics.drawable.Drawable makeBarBg() {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(Theme.px(3));
        bg.setColor(0x24FFFFFF);

        android.graphics.drawable.GradientDrawable fill =
                new android.graphics.drawable.GradientDrawable();
        fill.setCornerRadius(Theme.px(3));
        fill.setColor(Theme.FOCUS_BLUE);

        android.graphics.drawable.ClipDrawable clip =
                new android.graphics.drawable.ClipDrawable(
                        fill, android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);

        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{bg, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }


    private void tiplog(String msg, Throwable t) {
        android.util.Log.w("MinTV", msg, t);
        tip(msg);
    }

    /**
     * 下载 APK 并调起系统安装器。
     *
     * <p>带进度：50MB 的包在盒子上要下一会儿，只给个 toast 用户不知道卡没卡死。
     * 进度通过 Content-Length 算，拿不到总长度就只报已下载 MB 数。
     */
    private void downloadAndInstall(final String url,
                                    final android.widget.ProgressBar bar,
                                    final android.widget.TextView status) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                java.io.File out = new java.io.File(getCacheDir(), "update.apk");
                java.net.HttpURLConnection conn = null;
                try {
                    conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    // 走 Cloudflare 之类会分块传输，但它一般也给 Content-Length
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.setRequestProperty("User-Agent", "MinTV/" + versionName());
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        ui.post(new Runnable() { @Override public void run() {
                            status.setText("下载失败：HTTP 错误");
                        }});
                        return;
                    }
                    final long total = conn.getContentLength();
                    java.io.InputStream in = conn.getInputStream();
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                    try {
                        byte[] buf = new byte[65536];
                        int n;
                        long done = 0;
                        long lastPost = 0;
                        while ((n = in.read(buf)) > 0) {
                            fo.write(buf, 0, n);
                            done += n;
                            long now = System.currentTimeMillis();
                            if (now - lastPost > 250) {          // 节流，别刷爆主线程
                                lastPost = now;
                                final long d = done;
                                ui.post(new Runnable() { @Override public void run() {
                                    if (total > 0) {
                                        bar.setProgress((int) (d * 1000 / total));
                                        status.setText(String.format(java.util.Locale.US,
                                                "正在下载 %d%%  ·  %.1f / %.1f MB",
                                                (int) (d * 100 / total),
                                                d / 1048576.0, total / 1048576.0));
                                    } else {
                                        status.setText(String.format(java.util.Locale.US,
                                                "正在下载 %.1f MB", d / 1048576.0));
                                    }
                                }});
                            }
                            if (done > 200L * 1024 * 1024) break;
                        }
                        fo.flush();
                        ok = done > 1024 * 1024;      // 小于 1MB 肯定是错误页
                    } finally {
                        fo.close();
                        in.close();
                    }
                } catch (Throwable t) {
                    android.util.Log.w("MinTV", "下载更新失败", t);
                    final String why = t.getClass().getSimpleName();
                    ui.post(new Runnable() { @Override public void run() {
                        status.setText("下载失败：" + why);
                    }});
                } finally {
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
                }

                final boolean success = ok;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!success) {
                            bar.setVisibility(View.GONE);
                            if (status.getText().length() == 0) status.setText("下载失败");
                            return;
                        }
                        bar.setProgress(1000);
                        status.setText("下载完成，正在调起安装器…");
                        installApk(out);
                    }
                });
            }
        }, "update-download").start();
    }

    /** 调起系统安装器。Android 7+ 必须给 FileProvider 的 uri，直接 file:// 会抛异常。 */
    private void installApk(java.io.File apk) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            android.net.Uri uri;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", apk);
                i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = android.net.Uri.fromFile(apk);
            }
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "调起安装器失败", t);
            tip("无法调起安装器，请手动到 GitHub 下载");
        }
    }

    /** 极简 GET。只需要拿一个几百字节的 JSON。 */
    private String httpGet(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "MinTV/" + versionName());
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 256 * 1024) break;
                }
            } finally {
                in.close();
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "httpGet 失败", t);
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 从 PackageManager 读版本号，不依赖 BuildConfig（javac 直编时没有它）。 */
    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 取这一行的按钮文案。
     *
     * <p>ACTION 行的按钮文字是 SettingDef 里写死的，永远不会变 ——
     * 用户选了紫色，那行还写着「选择…」，看不出当前是什么。
     * 对"有当前值"的行这里换成真实值。
     */
    /**
     * 每行的说明文字。
     *
     * <p>存储目录这种需要展示完整路径的，放在说明行里 —— 按钮宽度有限，
     * 塞进去不是折成看不懂的省略号，就是溢出。
     */
    private String hintFor(int rowIndex) {
        String base = DemoData.ROW_TITLES[rowIndex][1];
        String id;
        try { id = com.mintv.tv.ui.setting.Settings.ALL[rowIndex].id; }
        catch (Throwable t) { return base; }

        if ("net_proxy".equals(id)) {
            // 副标题显示当前实际链路。SOCKS 会写成
            //   http://127.0.0.1:45231 → socks5://host:port
            // 让人看出播放器用的是本地桥，不是直连。
            String sum = com.mintv.tv.data.NetProxy.summary(this);
            if ("直连".equals(sum)) return base;
            return "当前：" + sum;
        }
        if ("storage_dir".equals(id)) {
            android.net.Uri root = com.mintv.tv.data.StorageDir.rootUri(this);
            if (root == null) return "未授权：脚本和本地源存在 App 私有目录，文件管理器看不到";
            String p = com.mintv.tv.data.StorageDir.readablePath(root);
            return "当前：" + p + "（脚本 scripts/ 订阅 sources/）";
        }
        if ("p2p_boost".equals(id)) {
            // 开启后副标题改成显示网络类型，让用户知道自己在加速里能扮演什么角色。
            // 没检测过就提示一下，不显示"未知"这种没信息量的字。
            if (selections[rowIndex] != 1) return base;
            if (lastNatResult == null) return "已开启 · 正在检测网络…";
            // 两种情况都带地址：用户想看到自己作为节点的出口。
            // 差别只在前面那截 —— 能用是"加速中…"，不能用报类型。
            boolean hasAddr = lastNatResult.publicIp != null
                    && lastNatResult.publicIp.length() > 0;
            String addr = hasAddr
                    ? " " + lastNatResult.publicIp + ":" + lastNatResult.localPort
                    : "";
            if (lastNatResult.canServe()) return "加速中…" + addr;
            return "已开启 · " + natShortName(lastNatResult) + addr;
        }
        return base;
    }

    /** NAT 类型的短名称，用在设置项副标题里（不能太长，一行放不下）。 */
    private static String natShortName(com.mintv.tv.data.NetCheck.Result r) {
        switch (r.natType) {
            case com.mintv.tv.data.NetCheck.NAT_FULL_CONE:  return "全锥形";
            case com.mintv.tv.data.NetCheck.NAT_RESTRICTED: return "地址受限";
            case com.mintv.tv.data.NetCheck.NAT_SYMMETRIC:
                // 过滤宽松的对称型能被打进来（只是对方得先知道端口），
                // 跟"双向都对称"不是一回事，副标题上分开显示。
                return r.filterOpen ? "对称型·宽松" : "对称型";
            case com.mintv.tv.data.NetCheck.NAT_BLOCKED:    return "连不上 STUN";
            default: return "未知";
        }
    }

    private String[] optionLabelsFor(int rowIndex) {
        String[] base = DemoData.ROW_OPTIONS[rowIndex];
        String id;
        try { id = com.mintv.tv.ui.setting.Settings.ALL[rowIndex].id; }
        catch (Throwable t) { return base; }

        if ("theme".equals(id)) {
            String[] out = base.clone();
            out[0] = Theme.ACCENT_NAMES[Theme.accentIndex];
            return out;
        }
        if ("net_proxy".equals(id)) {
            // 右侧按钮显示状态而不是固定「设置…」：
            //   有代理 → 「代理」
            //   没代理 → 「直连」
            // 具体地址在副标题里，这里只给一眼能看懂的状态。
            String[] out = base.clone();
            String sum = com.mintv.tv.data.NetProxy.summary(this);
            out[0] = "直连".equals(sum) ? "直连" : "代理";
            return out;
        }
        if ("storage_dir".equals(id)) {
            String[] out = base.clone();
            android.net.Uri root = com.mintv.tv.data.StorageDir.rootUri(this);
            if (root == null) {
                out[0] = "未授权（用私有目录）";
            } else {
                String p = com.mintv.tv.data.StorageDir.readablePath(root);
                // 按钮只放最后一段目录名（mintv），完整路径放下面那行说明里。
                // 路径折成 /storage/e…intv 读不出是哪一层，不如直接给名字。
                int slash = p.lastIndexOf('/');
                out[0] = slash >= 0 && slash < p.length() - 1
                        ? p.substring(slash + 1) : p;
            }
            return out;
        }
        return base;
    }

    /** 应用名（从包信息读，改名自动跟随）。 */
    private String appName() {
        try {
            return String.valueOf(getApplicationInfo().loadLabel(getPackageManager()));
        } catch (Throwable t) {
            return "MinTV";
        }
    }

    /** 上次播放的频道名，没有就返回 null。 */
    private String lastChannelName() {
        try {
            if (playingChannel >= 0 && playingChannel < channels.size()) {
                return channels.get(playingChannel).name;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.1.0";
        }
    }

    /** 去掉台号前导零，"0009" → "9"；全零时留一个 0。 */
    private static String stripLeadingZeros(String s) {
        if (s == null || s.isEmpty()) return s;
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }

    private static String fmtMs(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }


    /**
     * 显示系统弹窗，并在显示期间把按键交还给系统。
     *
     * <p>不包这一层的话，弹窗里的按钮遥控器点不到 ——
     * 因为 Activity.onKeyDown 会先一步吃掉方向键和 OK。
     */
    private android.app.AlertDialog showSystemDialog(android.app.AlertDialog.Builder b) {
        final android.app.AlertDialog d = b.create();
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) {
                sysDialogOpen = false;
            }
        });
        d.show();
        sysDialogOpen = true;
        return d;
    }

    private String infoText(int tab) {
        if (tab == 0) {
            return "账户登录\n\n"
                    + "本版本是纯本地播放器：不注册、不上传、不联网校验账号。\n"
                    + "直播源地址存在本机，没有服务器，所以这里暂时没有要登录的东西。\n\n"
                    + "将来接后端时会在这里放扫码登录 + 设备绑定。";
        }
        if (tab == TAB_ABOUT) {
            String ver = versionName();
            int vc = versionCode();
            String model = android.os.Build.MODEL == null ? "未知" : android.os.Build.MODEL;
            String brand = android.os.Build.BRAND == null ? "" : android.os.Build.BRAND;
            return "MinTV  一个纯 Java 的电视直播播放器\n\n"
                    + "版本      " + ver + "（" + vc + "）\n"
                    + "设备      " + brand + " " + model + "\n"
                    + "系统      Android " + android.os.Build.VERSION.RELEASE
                    + "（API " + android.os.Build.VERSION.SDK_INT + "）\n"
                    + "播放内核  libVLC 3.7.7\n"
                    + "界面      全部自绘，零第三方 UI 控件\n\n"
                    + "本应用不收集任何数据，不做联网统计。";
        }
        return "手机助手\n\n"
                + "同一 Wi-Fi 下用手机浏览器遥控这台盒子。\n\n"
                + "需要做的：\n"
                + "  1. 盒子上开一个 HTTP 服务（监听 0.0.0.0）\n"
                + "  2. 手机上访问  http://<盒子IP>:端口\n"
                + "  3. 页面里发「换台 / 音量 / 返回」指令，盒子轮询执行\n\n"
                + "当前状态：未实现。";
    }

    private void fillInfoPage(int tab) {
        if (infoPageText == null) return;
        infoPageText.setText(infoText(tab));
        // 免责声明只在「关于」页出现
        if (disclaimerBox != null) {
            disclaimerBox.setVisibility(tab == TAB_ABOUT ? View.VISIBLE : View.GONE);
        }
    }

    private void openSettings() {
        if (sysDialogOpen) return;                       // 系统弹窗在上面，先处理它
        if (dialogRoot != null && dialogRoot.getVisibility() == View.VISIBLE) return;  // 已开，幂等
        // 上层优先：设置弹窗要盖住节目单，先把它收掉。
        // 不然关掉设置后节目单还留着，而焦点已经退回主界面，状态对不上。
        if (epgPanelOpen) closeEpgPanel();
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
        if (rowIndex < 0) {
            // Tab 条接管焦点：所有行的白框都去掉，视觉上才清楚"现在在 Tab 上"
            for (int i = 0; i < rowViews.size(); i++) {
                rowViews.get(i).setRowFocused(false);
            }
            return;
        }
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

    /**
     * 重新绑一行设置。
     *
     * <p>设置行只在 buildSettingRows 时 bind 一次，之后不会自动刷新 ——
     * 所以凡是「不在行内改、而是弹对话框改」的项（代理、脚本目录……）
     * 保存后都必须手动调这里，否则界面还显示旧值，看起来像没保存。
     */
    private void refreshSettingRow(int i) {
        if (i < 0 || i >= rowViews.size() || i >= DemoData.ROW_TITLES.length) return;
        try {
            rowViews.get(i).bind(DemoData.ROW_TITLES[i][0], hintFor(i),
                    optionLabelsFor(i), selections[i]);
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "刷新设置行失败: " + i, t);
        }
    }

    /** 设置项写入后调用：只处理需要立刻生效的项。 */
    private void onSettingCommitted(int row, int value) {
        selections[row] = value;
        saveSelections();
        if (row == ROW_TIMEOUT) {
            // 新秒数要同步进当前这次播放尝试，否则位置判据和速度判据
            // 还在按旧值计时（改设置等于没改）。
            if (attempt != null) attempt.setTimeoutMs(switchTimeoutMs());
            armWatchdog();     // 立即按新策略重新计时
        } else if (row == ROW_DECODER) {
            // 解码方式是 VLC 的启动参数，改了要重建 LibVLC 实例。
            // setDecoderMode 只记下值，不重建 LibVLC；下次 play 作为 Media 选项生效。
            if (playerManager != null) {
                playerManager.setDecoderMode(value);
                playChannel(playingChannel);
            }
        } else if (row == ROW_HEADERS) {
            openHeaderEditor();
        } else if (row == ROW_AUTOPLAY) {
            saveAutoStartFlag();
            if (value == 0) showAutoStartGuide();
        } else if (row == ROW_NET_PROXY) {
            showProxyDialog();
        } else if (row == ROW_P2P_BOOST) {
            // 直接切换，不弹窗。设置项里那行"和同频道观众共享带宽"
            // 就是告知，用户点开关是主动行为。
            applyP2pBoost(value == 1);
        } else if (row == ROW_STORAGE_DIR) {
            pickStorageDir();
        } else if (row == ROW_GROUP_MGR) {
            openGroupManager();
        } else if (row == ROW_THEME) {
            openThemePicker();
        } else if (row == ROW_SHOW_SUBS) {
            // 就地加减订阅列，**不 recreate()**。
            //
            // 之前这里整个 Activity 重建，代价有三个：
            //   1. onCreate 里的开屏又放一遍（用户感觉"怎么又开机了"）
            //   2. 播放器被销毁重建，正在看的节目中断
            //   3. 频道列表、EPG、焦点全部重来
            // 而这件事本质上只是"在最左边插一列 / 去掉一列"。
            applyShowSubs(selections[ROW_SHOW_SUBS] == 1);
        } else if (row == ROW_EPG_POS || row == ROW_SWITCH_UI) {
            applyOsdPrefs();
        } else if (row == ROW_OUTPUT) {
            // 换渲染目标等于换输出管线：直接重建播放器最干净。
            // 不重建的话旧实例可能还持有已隐藏的 surface，
            // 表现为切过去黑屏、切回来也不恢复。
            if (playerManager != null) playerManager.release();
            applyOutputMode();
            playChannelInternal(playingChannel, true);
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
        // 通知内核：VLC 必须用自己的 ScaleType 才能生效
        if (player != null) player.setAspectMode(mode);
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                SurfaceHolder h = surfaceView.getHolder();
                if (h.getSurface() != null && h.getSurface().isValid()) {
                    player.setSurface(h.getSurface());   // 尺寸变了必须重绑
                }
            }
        }, 60);
    }

    private int lastHintZone = -1;

    @Override
    public void onZoneChanged(FocusEngine.Zone zone) {
        updateNavFocus(focus.navIndex());
        updateChannelFocus(focus.chanIndex());
        // 第一次把焦点移进分类列时给一句操作提示。
        // 没有提示的话，用户按左键看到蓝框跳过去，但列表没变，
        // 会以为"切换分类没反应"（其实要先按 OK 才切）。
        if (zone == FocusEngine.Zone.NAV && lastHintZone != 1 && navViews.size() > 1) {
            lastHintZone = 1;
            osd.toast("↑↓ 选择分类，OK 确认");
        } else if (zone == FocusEngine.Zone.CHAN && lastHintZone != 0) {
            lastHintZone = 0;
        }
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
    /**
     * 当前"活动层"。任何时刻只可能有一个，遥控按键和触摸都先问它。
     *
     * <p>为什么要这个东西：以前各层各判各的 —— 按键走 if 链，
     * 触摸走 onVideoTapped，两边条件不一致。结果就是设置弹窗开着的时候
     * 点屏幕还能触发下面的面板逻辑，用户按遥控器也分不清现在在操作谁。
     *
     * <p>层级从高到低：系统弹窗 > 设置弹窗 > 节目单 > 主界面面板 > 纯播放。
     */
    private static final int LAYER_SYS_DIALOG = 5;
    private static final int LAYER_SETTINGS = 4;
    private static final int LAYER_EPG = 3;
    private static final int LAYER_PANEL = 2;
    private static final int LAYER_PLAYER = 1;

    private int activeLayer() {
        if (sysDialogOpen) return LAYER_SYS_DIALOG;
        if (dialogRoot != null && dialogRoot.getVisibility() == View.VISIBLE) return LAYER_SETTINGS;
        if (epgPanelOpen) return LAYER_EPG;
        if (panelVisible) return LAYER_PANEL;
        return LAYER_PLAYER;
    }

    private void onVideoTapped() {
        int layer = activeLayer();
        // 上层开着时，点画面不该穿透到底下：
        //   系统弹窗 / 设置弹窗 → 完全忽略（由它们自己处理）
        //   节目单           → 收起节目单（等于"点空白关掉抽屉"）
        if (layer == LAYER_SYS_DIALOG || layer == LAYER_SETTINGS) return;
        if (layer == LAYER_EPG) { closeEpgPanel(); return; }

        // 分区边界读实际布局，不用常量累加 —— 加列/换分辨率都不会错位
        View boundary = epgPanelOpen ? epgScroll : railView;
        boolean left = boundary != null
                ? tapX < boundary.getRight()
                : tapX < getResources().getDisplayMetrics().widthPixels / 2f;

        if (layer == LAYER_PLAYER) {
            // 纯播放态：左边唤回面板，右边只管信息条
            if (left) {
                if (osd != null) osd.hideNow();
                showPanel();
                return;
            }
            if (osd != null && osd.isVisible()) { osd.hideNow(); return; }
            if (osd != null && osd.reshow()) return;
            showPanel();
            return;
        }

        // LAYER_PANEL：面板可见
        if (osd != null && osd.isVisible()) { osd.hideNow(); return; }
        if (osd != null && osd.reshow()) return;
        enterFullPlayer();
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
        // 和 OSD 一样：读最后一列的实际右边界，不用常量累加。
        // 不同分辨率/缩放下常量算出来的值和实际布局会差，触摸区就错位。
        View last = epgPanelOpen ? epgScroll : railView;
        int left = last != null ? Math.max(0, last.getRight()) : 0;
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
        if (channels.isEmpty()) return;
        reconnectSeq.incrementAndGet();      // 作废待执行的重连
        clearRestorePending();
        resetNoVideoStage();
        int n = focus.chanIndex() + step;
        // 首尾循环，不是顶到边界就停。
        //
        // 原来写的是 n<0→0、n>size-1→size-1：看到最后一个台再按"下一个"，
        // 界面一动不动 —— 用户的第一反应是"按键坏了 / 卡住了"。
        // 电视遥控的 CH+/- 一直是循环的，这里对齐。
        int size = channels.size();
        if (n < 0) n = size - 1;
        if (n >= size) n = 0;
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

    /** 按键路由：只做三件事 —— 建层、转发、兜底。 */
    private com.mintv.tv.ui.key.KeyRouter keyRouter;

    private void buildKeyRouter() {
        keyRouter = new com.mintv.tv.ui.key.KeyRouter()
                // 顺序 = 优先级，从上到下。加新的一层就 add 一行。
                .add(new com.mintv.tv.ui.key.SystemDialogLayer(
                        new com.mintv.tv.ui.key.SystemDialogLayer.Host() {
                    @Override public boolean isSystemDialogShowing() { return sysDialogOpen; }
                }))
                .add(new com.mintv.tv.ui.key.SettingsLayer(
                        new com.mintv.tv.ui.key.SettingsLayer.Host() {
                    @Override public boolean isSettingsVisible() {
                        return dialogRoot != null && dialogRoot.getVisibility() == View.VISIBLE;
                    }
                    @Override public boolean handleSettingsKey(KeyEvent e) {
                        return MainActivity.this.handleSettingsKey(e.getKeyCode(), e);
                    }
                }))
                .add(new com.mintv.tv.ui.key.EpgLayer(
                        new com.mintv.tv.ui.key.EpgLayer.Host() {
                    @Override public boolean isEpgOpen() { return epgPanelOpen; }
                    @Override public boolean handleEpgKey(KeyEvent e) {
                        return MainActivity.this.handleEpgKey(e.getKeyCode());
                    }
                    @Override public void closeEpg() { closeEpgPanel(); }
                }))
                .add(new com.mintv.tv.ui.key.PanelLayer(
                        new com.mintv.tv.ui.key.PanelLayer.Host() {
                    @Override public boolean isPanelVisible() { return panelVisible; }
                    @Override public boolean handlePanelKey(KeyEvent e) {
                        return handlePanelKeyInner(e);
                    }
                    @Override public boolean tryOpenEpg() {
                        if (focus.zone() == FocusEngine.Zone.NAV) return false;
                        if (focus.chanIndex() < 0) return false;
                        showEpgPanel(focus.chanIndex());
                        return true;
                    }
                    @Override public void hidePanel() { MainActivity.this.hidePanel(); }
                }))
                .add(new com.mintv.tv.ui.key.PlayerLayer(
                        new com.mintv.tv.ui.key.PlayerLayer.Host() {
                    @Override public boolean isPanelVisible() { return panelVisible; }
                    @Override public void showPanel() { MainActivity.this.showPanel(); }
                    @Override public void onSeekStep(int d) { }
                    @Override public boolean startSeek(boolean f) {
                        return MainActivity.this.startSeek(f);
                    }
                    @Override public boolean isSeeking() { return seeking; }
                    @Override public void endSeek() { MainActivity.this.endSeek(); }
                    @Override public void appendDigit(int d) { appendChannelDigit(d); }
                    @Override public void jumpToLastChannel() {
                        MainActivity.this.jumpToLastChannel();
                    }
                    @Override public void channelUp() { moveChannel(+1); }
                    @Override public void channelDown() { moveChannel(-1); }
                    @Override public boolean canSeek() {
                        return player != null && player.canSeek();
                    }
                    @Override public void seekStep(int d) {
                        if (player != null && player.canSeek()) {
                            player.seekTo(Math.max(0, player.positionMs() + d * 10_000L));
                        }
                    }
                    @Override public boolean isOsdVisible() {
                        return osd != null && osd.isVisible();
                    }
                    @Override public void hideOsd() { if (osd != null) osd.hideNow(); }
                    @Override public void reshowOsd() { if (osd != null) osd.reshow(); }
                }));
    }

    /** 面板可见时的按键：焦点引擎 + OK/BACK。 */
    private boolean handlePanelKeyInner(KeyEvent event) {
        int keyCode = event.getKeyCode();
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
            default:
                return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyRouter == null) buildKeyRouter();

        // ── 全局约定：音量键最高优先，任何界面都能调 ──
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            handleVolumeKey(keyCode);
            return true;
        }

        // ── 全局约定：MENU 短按开设置、长按开诊断 ──
        // 这两个不属于任何界面层，是"外壳级"的行为，所以留在路由之前。
        // 放在这里也避免了"某个层忘了处理 MENU"导致的失灵。
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0 && !event.isLongPress()) {
                event.startTracking();
                menuPressed = true;
            }
            return true;
        }

        // ── 全局约定：INFO / GUIDE 调出 OSD ──
        if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_GUIDE) {
            if (osd != null) osd.reshow();
            return true;
        }

        // ── 订阅列焦点 ──
        // 不走 keyRouter：它不是"层"，是最左侧一列，处理逻辑只有上下移动和
        // 左右进出，塞进层体系反而绕。
        if (subColumn != null && subColumn.getVisibility() == View.VISIBLE) {
            if (focus.zone() == FocusEngine.Zone.SUB) {
                if (handleSubColumnKey(keyCode)) return true;
            } else if (focus.zone() == FocusEngine.Zone.NAV
                    && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // 分类列按左 → 进订阅列
                enterSubColumn();
                return true;
            }
        }

        if (keyRouter.onKeyDown(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            menuPressed = false;
            showDiagnostics();
            return true;
        }
        if (keyRouter == null) buildKeyRouter();
        if (keyRouter.onKeyLongPress(event)) return true;
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && menuPressed) {
            menuPressed = false;
            if (!event.isCanceled() && event.isTracking()) openSettings();
            return true;
        }
        if (keyRouter == null) buildKeyRouter();
        if (keyRouter.onKeyUp(event)) return true;
        return super.onKeyUp(keyCode, event);
    }


    /**
     * 这一行是「多选一的配置值」还是「一个按钮」。
     *
     * 值行：左右直接改；动作行：只有按 OK 才触发（否则左右一扫就会
     * 反复弹出编辑框、反复发网络请求）。
     */
    private boolean isValueRow(int row) {
        return row != ROW_SOURCE && row != ROW_HEADERS && row != ROW_GROUPMGR;
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
                paintTabs();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                currentTab = (currentTab + 1) % DemoData.TABS.length;
                showTab(currentTab);
                paintTabs();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                tabFocus = false;
                paintTabs();
                applyRowFocus(focus.rowIndex());
                refreshSettingsFocus();
                return true;
            }
            return true;
        }
        // 「关于 / 手机助手」页：方向键在自己那页的控件里走，OK 触发。
        // 这两页不在 rowColumn 里，以前按键全被下面的逻辑吃掉，
        // 「检测更新」按钮、订阅列表在遥控器上完全点不到。
        if (currentTab != TAB_SETTINGS) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (pageFocusIdx > 0) setPageFocus(pageFocusIdx - 1);
                else { tabFocus = true; paintTabs(); }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (pageFocusIdx + 1 < pageFocus.size()) setPageFocus(pageFocusIdx + 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (pageFocusIdx >= 0 && pageFocusIdx < pageFocus.size()) {
                    pageFocus.get(pageFocusIdx).performClick();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return true;      // 这两页没有横向选项
            }
            // 「关于 / 手机助手」页：其它键（数字、音量、播放键…）一律吞掉。
            // 原来这里不 return，事件会落到下面的 rowColumn 逻辑上 ——
            // 用户在「关于」页按个方向键，隐藏的设置列表焦点却被改动了，
            // 切回「播放设置」就发现焦点莫名其妙跳了。
            return true;
        }

        // 按「上」进 Tab 条。
        //
        // 以前条件是 rowIndex()==0，只有焦点在第一行时才进得去 ——
        // 用户在列表第 13 行（画面输出）想切页，按上键只会往上挪一行，
        // 按十几次才到顶。而设置页本来就没有"上下进入别的区域"的需求，
        // 上下键只是行间移动，所以 Tab 条应该永远可以用一个明确的键够到。
        //
        // 现在的规则：上键在 Tab 条之外 → 先正常上移一行；
        //              已经在第一行/或按住不放 → 进 Tab 条。
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (focus.rowIndex() == 0 || event.getRepeatCount() > 0) {
                tabFocus = true;
                paintTabs();
                applyRowFocus(-1);
                return true;
            }
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

        // ← →：移动选项，并**立即生效**。
        //
        // 以前是按左右只挪白框，还要再按一次 OK 才应用 ——
        // 用户看着选项变了、实际没变，就会问"这些选择怎么切换"。
        // 现在照 TV 设置页的通行做法：选中即应用。
        // 动作行（切源 / 请求头 / 分组管理）不适用：
        // 它们只有一个"按钮"，OK 才触发。
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int row = focus.rowIndex();
            int before = focus.optIndex();
            boolean handled2 = focus.onKey(keyCode);
            int after = focus.optIndex();
            android.util.Log.d("MinTVKey", "LR row=" + row + " opt " + before
                    + "->" + after + " handled=" + handled2
                    + " rows=" + rowViews.size()
                    + " optCountsLen=" + (DemoData.ROW_OPTIONS.length)
                    + " thisRowOpts=" + (row >= 0 && row < DemoData.ROW_OPTIONS.length
                        ? DemoData.ROW_OPTIONS[row].length : -1));
            if (row >= 0 && row < rowViews.size()) {
                // 行本身必须保持"被聚焦"状态，否则按一下左右键整行白框消失，
                // 看上去像焦点丢了、也没选中 —— 用户会以为左右键不起作用。
                rowViews.get(row).setRowFocused(!tabFocus);
                rowViews.get(row).setOptionFocused(after);
            }
            if (after != before && isValueRow(row)) {
                onSettingCommitted(row, after);
                rowViews.get(row).setSelected(after);
            }
            return true;
        }

        // TAB 键（有的遥控器有）直接进/出 Tab 条
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            tabFocus = !tabFocus;
            paintTabs();
            applyRowFocus(tabFocus ? -1 : focus.rowIndex());
            return true;
        }

        boolean handled = focus.onKey(keyCode);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            applyRowFocus(focus.rowIndex());
        }
        return handled || true;
    }

    private void switchCategory(int index) {
        resetNoVideoStage();
        activeCategory = index;
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

    // ================= 数字键换台 =================

    /**
     * 数字键累计输入。
     *
     * <p>原来的策略是"每次按键都重置 1.2 秒计时器，到点才跳台"。
     * 问题很明显：按一个「5」想看 5 台，得干等 1.2 秒；按「123」
     * 已经按满三位了，还要再等 1.2 秒。手感就是"按键没反应"。
     *
     * <p>现在改成：按完立刻判断「还有没有更长的台号能匹配上」。
     * <ul>
     *   <li>没有 → 输入已经唯一确定，立即切台（0 延迟）</li>
     *   <li>有（比如按了 5，还存在 50/51/52）→ 等 700ms 让用户按第二位</li>
     * </ul>
     */
    private void appendChannelDigit(int digit) {
        if (numBuf.length() >= 4) numBuf.setLength(0);
        numBuf.append((char) ('0' + digit));
        osd.showNumberInput(numBuf.toString());
        ui.removeCallbacks(numCommit);
        if (!hasLongerNumberMatch(numBuf.toString())) {
            commitChannelNumber();     // 唯一确定，立即执行
            return;
        }
        ui.postDelayed(numCommit, 700);
    }

    /** 当前输入是否还可能被更长的台号匹配上（决定要不要等下一键）。 */
    private boolean hasLongerNumberMatch(String prefix) {
        for (int i = 0; i < channels.size(); i++) {
            String n = String.valueOf(channels.get(i).number);
            if (n.length() > prefix.length() && n.startsWith(prefix)) return true;
        }
        return false;
    }

    private void commitChannelNumber() {
        String s = numBuf.toString();
        numBuf.setLength(0);
        if (s.isEmpty()) return;
        int num;
        try { num = Integer.parseInt(s); } catch (NumberFormatException e) { return; }
        if (num <= 0) return;

        int idx = indexOfNumber(channels, num);
        if (idx < 0) {
            int allIdx = indexOfCategory("\u5168\u90e8");
            if (allIdx >= 0 && !"\u5168\u90e8".equals(currentCategoryName())) {
                focus.setNavIndexSilently(allIdx);
                switchCategory(allIdx);
                updateNavFocus(allIdx);
            }
            idx = indexOfNumber(channels, num);
        }
        if (idx < 0) { osd.toast("\u6ca1\u6709 " + num + " \u8fd9\u4e2a\u53f0\u53f7"); return; }
        onChannelClicked(idx);
        focus.setChanIndexSilently(idx);
        updateChannelFocus(idx);
    }

    private static int indexOfNumber(List<PlaylistParser.Channel> list, int num) {
        if (list == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).number == num) return i;
        }
        return -1;
    }

    // ================= \u4e0a\u4e00\u9891\u9053 =================

    private void jumpToLastChannel() {
        if (lastChannelNum <= 0) { osd.toast("\u8fd8\u6ca1\u6709\u4e0a\u4e00\u4e2a\u53f0"); return; }
        int idx = indexOfNumber(channels, lastChannelNum);
        if (idx < 0) {
            int allIdx = indexOfCategory("\u5168\u90e8");
            if (allIdx >= 0 && !"\u5168\u90e8".equals(currentCategoryName())) {
                focus.setNavIndexSilently(allIdx);
                switchCategory(allIdx);
                updateNavFocus(allIdx);
            }
            idx = indexOfNumber(channels, lastChannelNum);
        }
        if (idx < 0) { osd.toast("\u4e0a\u4e00\u4e2a\u53f0\u5df2\u7ecf\u4e0d\u5728\u5217\u8868\u91cc\u4e86"); return; }
        onChannelClicked(idx);
        focus.setChanIndexSilently(idx);
        updateChannelFocus(idx);
    }

    /** \u63a5\u7ba1\u97f3\u91cf\u952e\uff1a\u8c03 STREAM_MUSIC \u5e76\u663e\u793a\u97f3\u91cf\u6761\u3002 */
    private void handleVolumeKey(int keyCode) {
        android.media.AudioManager am =
                (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                    cur == 0 ? Math.max(1, max / 3) : 0, 0);
        } else {
            int dir = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 1 : -1;
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, 0);
        }
        cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int pct = max <= 0 ? 0 : Math.round(cur * 100f / max);
        osd.showVolume(pct, cur == 0);
    }

    private static boolean isDpad(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // ⚠ 顺序很重要：先摘掉 VLC 挂在 TextureView 上的回调，再停播。
        //
        // 只调 stop() 是不够的：TextureView 真正销毁时（返回键退出、
        // 配置变更、被系统回收），VLC 的 AWindow 回调还在上面，会在主线程
        // 同步执行 setVideoTrack → 等内核锁。实测卡 5.9 秒直接 ANR：
        //   libvlc_video_set_track+28 / nativeSetVideoTrack
        //   AWindow.onSurfaceDestroyed ← TextureView 销毁触发
        //
        // detachForBackground 内部先换安全 listener（主线程、不碰 native），
        // 再把 detachViews 丢到后台线程。
        if (playerManager != null) playerManager.detachForBackground();
        // 只停播，不销毁 —— 回来时接着播当前台，不用重新拉流
        if (player != null) player.stop();
    }

    /**
     * 从后台回来要**重新起播**。
     *
     * <p>之前只有 onPause 里的 stop()，没有对应的恢复：
     * 按 HOME 出去再回来，画面就是全黑、也没声音，
     * 如果面板恰好是收起状态，看起来就是"黑屏且点不动"。
     * 这里把输出面重新绑一次，再重开当前频道。
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (playerManager == null) return;
        // 重新绑定画面输出（Surface/TextureView 在后台可能已经失效）
        if (selections[ROW_OUTPUT] == OUTPUT_TEXTURE) {
            playerManager.setTextureView(textureView);
        } else {
            // 同样：VLC 只认 SurfaceView 组件，裸 Surface 绑不上
            playerManager.setTextureView(null);
            if (surfaceView != null) playerManager.setSurfaceView(surfaceView);
        }
        playerManager.setCallback(playerCallback);
        // 只有"已经播过一次"才自动恢复，冷启动交给 surfaceCreated
        if (currentPlayUrl != null && playingChannel >= 0 && playingChannel < channels.size()) {
            playChannelInternal(playingChannel, true);
        }
        updateOsdInset();
    }

    @Override
    protected void onDestroy() {
        // 停掉自动刷新：它是匿名 Runnable，持有 Activity。
        // 不清掉的话 Activity 销毁了定时器还在跑，既泄漏又会在后台发请求。
        try { stopAutoRefresh(); } catch (Throwable ignored) { }
        // 退出时通知 tracker 摘掉自己。
        //
        // 不做的话要等 peer_timeout（30 秒）服务端才清理，
        // 这 30 秒里别的 peer 还会拿到这个已经死掉的地址去连，白白失败。
        try { com.mintv.tv.data.P2PEngine.get(getApplicationContext()).stop(); }
        catch (Throwable ignored) { }
        // 释放 tvbus / p2p 的 native 资源。
        //
        // 不做的话：native 线程、socket、以及 UPnP 端口映射（so 里调了
        // UPNP_AddPortMapping）都留在路由器上，用户看不到但会占端口。
        try {
            // 先停 p2p 再关 tvbus，两个都走 stopPrivateEngines；
            // 单独再 close 一次是兜底（比如 p2p 从没起过但 tvbus 起过）
            stopPrivateEngines();
            com.tvbus.engine.TVCore.close();
        } catch (Throwable t) {
            android.util.Log.w("MinTV", "TVCore.close 失败", t);
        }
        super.onDestroy();
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(speedTick);
        // 这三个都是自续命的 Runnable（内部再 postDelayed 自己），
        // 不清掉的话 Activity 销毁后还在跑，既泄漏 Activity 又白耗电。
        //   playbackWatchdog   等起播超时
        //   autoRefreshLoop    订阅自动刷新
        //   netCheckLoop       NAT 类型定时重测
        try { ui.removeCallbacks(playbackWatchdog); } catch (Throwable ignored) { }
        try { stopAutoRefresh(); } catch (Throwable ignored) { }
        try { ui.removeCallbacks(netCheckLoop); } catch (Throwable ignored) { }
        if (remote != null) {
            remote.stop();
            remote = null;
        }
        ui.removeCallbacks(helperTicker);
        if (player != null) {
            player.release();
            player = null;
        }
        logos.shutdown();
    }
}
