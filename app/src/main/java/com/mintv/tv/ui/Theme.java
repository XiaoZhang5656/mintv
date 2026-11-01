package com.mintv.tv.ui;

import android.graphics.Color;

/**
 * 全项目唯一的视觉常量出口。改皮肤只动这一个文件。
 *
 * 基准分辨率 1920x1080 —— 所有尺寸都是这个基准下的像素值，
 * 运行时通过 {@link #scale()} 换算到实际分辨率（1280x720 的盒子会乘以 2/3）。
 */
public final class Theme {

    private Theme() {}

    // ---------- 基础色 ----------
    public static final int BG           = 0xFF0B0B0C;
    public static final int TEXT         = 0xFFFFFFFF;
    public static final int TEXT_SUB     = 0xFF9A9A9E;
    public static final int TEXT_DIM     = 0xFF77777A;
    public static final int FOCUS_BLUE   = 0xFF2F7BFF;
    public static final int GOLD         = 0xFF8B8B6A;

    // 面板
    public static final int PANEL_NAV    = 0x0AFFFFFF;   // 左导航前景层
    public static final int PANEL_ITEM   = 0x0DFFFFFF;   // 频道项常态底
    public static final int PANEL_ACTIVE = 0x1AFFFFFF;   // 当前分类底
    public static final int FOCUS_BG     = 0x292F7BFF;   // 焦点底（蓝 16%）
    public static final int LINE         = 0x12FFFFFF;   // 分隔线

    // 遮罩横向渐变：从最左到 1180px 处淡出到全透明
    public static final int[] SCRIM_COLORS   = {0xF0000000, 0xE0000000, 0x8C000000, 0x00000000};
    public static final float[] SCRIM_STOPS  = {0f, 0.52f, 0.82f, 1f};

    // ---------- 布局（1920x1080 基准）----------
    public static final int NAV_W        = 330;
    public static final int CHAN_W       = 620;
    public static final int RAIL_W       = 56;
    public static final int SCRIM_W      = 1060;

    public static final int NAV_ITEM_H   = 72;
    public static final int CHAN_ITEM_H  = 116;
    public static final int CHAN_ITEM_GAP = 10;

    public static final int RADIUS_ITEM  = 10;
    public static final int FOCUS_STROKE = 2;

    // ---------- 字号（px @1080p）----------
    public static final int TS_NAV_TITLE = 29;
    public static final int TS_NAV_ITEM  = 25;
    public static final int TS_CHAN_TITLE = 26;
    public static final int TS_CHAN_SUB  = 16;
    public static final int TS_NUM       = 14;
    public static final int TS_CLOCK     = 21;
    public static final int TS_CLOCK_SUB = 18;
    public static final int TS_RAIL      = 22;

    // ---------- 设置弹窗 ----------
    public static final int DLG_W        = 1300;
    public static final int DLG_TOP      = 96;
    public static final int DLG_RADIUS   = 6;
    public static final int DLG_BG       = 0xFF232426;
    public static final int DLG_ROW_BG   = 0xFF2B2C2E;
    public static final int DLG_OPT_BG   = 0xFF3A3B3E;
    public static final int DLG_OPT_SEL  = 0xFF31543A;
    public static final int DLG_OPT_TXT_ON = 0xFFEAF7EE;
    public static final int DLG_CHECK    = 0xFF4ADE80;

    // ---------- 动画时长 ----------
    public static final int T_FOCUS   = 120;
    public static final int T_PANEL   = 240;
    public static final int T_SCROLL  = 220;

    /** 实际分辨率 / 1920 的比例。只在 Activity 里设一次。 */
    private static float sScale = 1f;

    public static void init(int widthPx) {
        sScale = widthPx / 1920f;
        if (sScale <= 0f) sScale = 1f;
    }

    public static float scale() { return sScale; }

    /** 设计稿尺寸 → 实际像素 */
    public static int px(float design) { return Math.round(design * sScale); }

    /** 设计稿字号 → 实际像素 */
    public static float sp(float design) {
        // TV 上 dpi 常被厂商写乱，字号统一按屏宽缩放，不用 sp 避免被系统字体放大破坏版式
        return design * sScale;
    }

    public static int alpha(int color, float a) {
        int A = (int) (Color.alpha(color) * a);
        return (A << 24) | (color & 0x00FFFFFF);
    }
}
