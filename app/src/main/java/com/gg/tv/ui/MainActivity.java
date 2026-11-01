package com.gg.tv.ui;

import android.app.Activity;
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

import com.gg.tv.ui.focus.FocusEngine;
import com.gg.tv.ui.player.Media3Backend;
import com.gg.tv.ui.player.PlaybackBackend;
import com.gg.tv.ui.widget.ChannelView;
import com.gg.tv.ui.widget.ClockView;
import com.gg.tv.ui.widget.NavItemView;
import com.gg.tv.ui.widget.RailView;
import com.gg.tv.ui.widget.ScrimView;
import com.gg.tv.ui.widget.SettingRowView;

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
    private View navPanel, chanPanel, railView, scrim;
    private ClockView clock;

    // ---- 状态 ----
    private final FocusEngine focus = new FocusEngine();
    private List<DemoData.Category> categories;
    private List<DemoData.Channel> channels;
    private boolean panelVisible = true;
    private int playingChannel = 4;

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

        int sw = getResources().getDisplayMetrics().widthPixels;
        Theme.init(sw);

        categories = DemoData.categories();
        channels = DemoData.bj();

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
                osd.setStatus(OsdView.stateText(s));
            }
            @Override public void onError(String msg) {
                osd.setStatus("源失效：" + msg);
            }
            @Override public void onFirstFrame() { }
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
    }

    // ================= 布局 =================

    private void buildPlayer(FrameLayout root) {
        surfaceView = new SurfaceView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        root.addView(surfaceView, lp);
    }

    private void buildOverlay(FrameLayout root) {
        int navW = Theme.px(Theme.NAV_W);
        int chanW = Theme.px(Theme.CHAN_W);
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
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(Theme.NAV_ITEM_H));
            p.bottomMargin = Theme.px(2);
            navList.addView(v, p);
            navViews.add(v);
        }
        navScroll.addView(navList);
        nav.addView(navScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 分隔线
        View navLine = new View(this);
        navLine.setBackgroundColor(Theme.LINE);
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

        ScrollView chanScroll = new ScrollView(this);
        chanScroll.setVerticalScrollBarEnabled(false);
        chanScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chanColumn = new LinearLayout(this);
        chanColumn.setOrientation(LinearLayout.VERTICAL);
        chanScroll.addView(chanColumn);
        chan.addView(chanScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        chanPanel = chan;

        FrameLayout.LayoutParams chanLp = new FrameLayout.LayoutParams(
                chanW, FrameLayout.LayoutParams.MATCH_PARENT);
        chanLp.leftMargin = navW + Theme.px(1);
        root.addView(chan, chanLp);

        // --- 竖轨 ---
        View rail = new RailView(this);
        FrameLayout.LayoutParams railLp = new FrameLayout.LayoutParams(
                railW, FrameLayout.LayoutParams.MATCH_PARENT);
        railLp.leftMargin = navW + chanW + Theme.px(1);
        railView = rail;
        root.addView(rail, railLp);

        // --- 时钟 ---
        clock = new ClockView(this);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                Theme.px(360), Theme.px(90));
        clockLp.gravity = Gravity.TOP | Gravity.END;
        clockLp.rightMargin = Theme.px(36);
        clockLp.topMargin = Theme.px(18);
        root.addView(clock, clockLp);

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

    private void bindChannels() {
        chanColumn.removeAllViews();
        chanViews.clear();
        for (int i = 0; i < channels.size(); i++) {
            DemoData.Channel ch = channels.get(i);
            ChannelView v = new ChannelView(this);
            v.setNumber(ch.no);
            v.setName(ch.name);
            v.setSub(ch.now);
            v.setBadge(ch.badge);
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
    }

    private void updateNavFocus(int index) {
        for (int i = 0; i < navViews.size(); i++) {
            NavItemView v = navViews.get(i);
            v.setActive(i == focus.navIndex());
            v.setFocused(i == index && focus.zone() == FocusEngine.Zone.NAV, true);
        }
    }

    private void updateChannelFocus(int index) {
        for (int i = 0; i < chanViews.size(); i++) {
            ChannelView v = chanViews.get(i);
            v.setFocused(i == index && focus.zone() == FocusEngine.Zone.CHAN, true);
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
        DemoData.Channel ch = channels.get(index);
        osd.showChannelOsd(ch.no, ch.name);
        player.play(getApplicationContext(), ch.url, true);
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
        cardLp.topMargin = Theme.px(Theme.DLG_TOP);

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
        card.addView(rowColumn);

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
    }

    @Override
    public void onSettingRowChanged(int rowIndex, int optIndex) {
        applyRowFocus(rowIndex);
    }

    @Override
    public void onZoneChanged(FocusEngine.Zone zone) {
        updateNavFocus(focus.navIndex());
        updateChannelFocus(focus.chanIndex());
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

        if (!panelVisible) {
            // 面板收起时，任意方向键先唤出面板
            if (isDpad(keyCode)) {
                showPanel();
                return true;
            }
        }

        if (focus.onKey(keyCode)) return true;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (focus.zone() == FocusEngine.Zone.NAV) {
                    switchCategory(focus.navIndex());
                } else {
                    playChannel(focus.chanIndex());
                    hidePanel();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (panelVisible) {
                    hidePanel();
                } else {
                    finish();
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private boolean handleSettingsKey(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            closeSettings();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            int row = focus.rowIndex();
            selections[row] = focus.optIndex();
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
        channels = ("全部".equals(name) || "省内".equals(name)) ? DemoData.bj() : DemoData.all();
        bindChannels();
        focus.resetChannelFocus();
        updateChannelFocus(0);
    }

    private void hidePanel() {
        panelVisible = false;
        animatePanel(0f);
    }

    private void showPanel() {
        panelVisible = true;
        animatePanel(1f);
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
    }
}
