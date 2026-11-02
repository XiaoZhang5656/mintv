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
    public static int BG = 0xFF0B0B0C;
    public static int TEXT = 0xFFFFFFFF;
    public static int TEXT_SUB = 0xFF9A9A9E;
    public static int TEXT_DIM = 0xFF77777A;
    public static int FOCUS_BLUE = 0xFF2F7BFF;
    public static int GOLD = 0xFF8B8B6A;
    /** 选中的分类计数胶囊底色。绿色＝已选，蓝色＝焦点，暗金＝普通。 */
    public static int SEL_GREEN = 0xFF3FA85A;

    // 面板
    public static int PANEL_NAV = 0x0AFFFFFF;   // 左导航前景层
    public static int PANEL_ITEM = 0x0DFFFFFF;   // 频道项常态底
    public static int PANEL_ACTIVE = 0x1AFFFFFF;   // 当前分类底
    public static int FOCUS_BG = 0x292F7BFF;   // 焦点底（蓝 16%）
    public static int LINE = 0x12FFFFFF;   // 分隔线

    // 遮罩横向渐变：从最左到 1180px 处淡出到全透明
    public static int[] SCRIM_COLORS   = {0xF0000000, 0xE0000000, 0x8C000000, 0x00000000};
    public static final float[] SCRIM_STOPS  = {0f, 0.52f, 0.82f, 1f};

    // ---------- 皮肤色（三套主题各自的取值见 applyPalette）----------
    /** OSD 信息卡底 */
    public static int CARD_BG        = 0xE0101216;
    /** 「可回看」这类小徽标底 */
    public static int BADGE_BG       = 0x29FFFFFF;
    /** EPG 里「正在播」那行底 */
    public static int EPG_LIVE_BG    = 0x332F7BFF;
    /** EPG 焦点行底 */
    public static int EPG_FOCUS_BG   = 0x1AFFFFFF;
    /** 面板小标题文字（「节目 » 信息」那种） */
    public static int HEADER_TEXT    = 0xFFECECEE;
    /** 竖轨文字 */
    public static int RAIL_TEXT      = 0xFFE8E8EA;

    /** 当前主题 id：0 暗黑 / 1 盒子蓝 / 2 暖色 */
    public static int palette = 0;

    /**
     * 切换调色板。
     *
     * <p>只换颜色，不动布局和交互 —— 这三套主题共用同一套骨架，
     * 所以切换时零回归风险：焦点、滚动、命中测试一行都不用改。
     *
     * @param id 0=暗黑（默认）1=盒子蓝 2=暖色
     */
    public static void applyPalette(int id) {
        palette = id;
        switch (id) {
            case 1:   // 盒子蓝：面板更透、蓝更亮，接近运营商机顶盒的观感
                BG           = 0xFF06101A;
                TEXT         = 0xFFFFFFFF;
                TEXT_SUB     = 0xFF9FB6CC;
                TEXT_DIM     = 0xFF6F8399;
                FOCUS_BLUE   = 0xFF1E9BFF;
                GOLD         = 0xFF7F93A8;
                SEL_GREEN    = 0xFF2FA36B;
                PANEL_NAV    = 0x24FFFFFF;
                PANEL_ITEM   = 0x1CFFFFFF;
                PANEL_ACTIVE = 0x3A1E9BFF;
                FOCUS_BG     = 0x331E9BFF;
                LINE         = 0x22FFFFFF;
                CARD_BG      = 0xE60A1A26;
                BADGE_BG     = 0x33FFFFFF;
                EPG_LIVE_BG  = 0x331E9BFF;
                EPG_FOCUS_BG = 0x1EFFFFFF;
                HEADER_TEXT  = 0xFFEAF4FF;
                RAIL_TEXT    = 0xFFCFE6FF;
                SCRIM_COLORS = new int[]{0xF0000812, 0xE0000812, 0x8C000812, 0x00000000};
                break;
            case 2:   // 暖色：米白字 + 琥珀焦点，夜里看着不刺眼
                BG           = 0xFF14100C;
                TEXT         = 0xFFFFF6EC;
                TEXT_SUB     = 0xFFC4B39E;
                TEXT_DIM     = 0xFF93826E;
                FOCUS_BLUE   = 0xFFFFA53D;
                GOLD         = 0xFF9C8A70;
                SEL_GREEN    = 0xFF8FB05A;
                PANEL_NAV    = 0x24FFE9C8;
                PANEL_ITEM   = 0x1CFFE9C8;
                PANEL_ACTIVE = 0x3AFFA53D;
                FOCUS_BG     = 0x33FFA53D;
                LINE         = 0x22FFE9C8;
                CARD_BG      = 0xE61A1410;
                BADGE_BG     = 0x33FFE9C8;
                EPG_LIVE_BG  = 0x33FFA53D;
                EPG_FOCUS_BG = 0x1EFFE9C8;
                HEADER_TEXT  = 0xFFFFF1E0;
                RAIL_TEXT    = 0xFFF0DCC4;
                SCRIM_COLORS = new int[]{0xF01A0F06, 0xE01A0F06, 0x8C1A0F06, 0x00000000};
                break;
            default:  // 暗黑（原始配色）
                BG           = 0xFF0B0B0C;
                TEXT         = 0xFFFFFFFF;
                TEXT_SUB     = 0xFF9A9A9E;
                TEXT_DIM     = 0xFF77777A;
                FOCUS_BLUE   = 0xFF2F7BFF;
                GOLD         = 0xFF8B8B6A;
                SEL_GREEN    = 0xFF3FA85A;
                PANEL_NAV    = 0x0AFFFFFF;
                PANEL_ITEM   = 0x0DFFFFFF;
                PANEL_ACTIVE = 0x1AFFFFFF;
                FOCUS_BG     = 0x292F7BFF;
                LINE         = 0x12FFFFFF;
                CARD_BG      = 0xE0101216;
                BADGE_BG     = 0x29FFFFFF;
                EPG_LIVE_BG  = 0x332F7BFF;
                EPG_FOCUS_BG = 0x1AFFFFFF;
                HEADER_TEXT  = 0xFFECECEE;
                RAIL_TEXT    = 0xFFE8E8EA;
                SCRIM_COLORS = new int[]{0xF0000000, 0xE0000000, 0x8C000000, 0x00000000};
                break;
        }
    }

    // ---------- 布局（1920x1080 基准）----------
    public static final int NAV_W        = 360;
    public static final int CHAN_W       = 476;
    public static final int RAIL_W       = 56;
    public static final int SCRIM_W      = 1180;

    public static final int NAV_ITEM_H   = 72;
    public static final int CHAN_ITEM_H  = 116;
    public static final int CHAN_ITEM_GAP = 10;

    public static final int RADIUS_ITEM  = 10;
    public static final int FOCUS_STROKE = 2;

    // ---------- 字号（px @1080p）----------
    public static final int TS_NAV_TITLE = 29;
    public static final int TS_NAV_ITEM  = 24;
    public static final int TS_CHAN_TITLE = 25;
    public static final int TS_CHAN_SUB  = 16;
    public static final int TS_NUM       = 14;
    public static final int TS_CLOCK     = 21;
    public static final int TS_CLOCK_SUB = 18;
    public static final int TS_RAIL      = 22;

    // ---------- 设置弹窗 ----------
    /** 节目单栏宽（抽屉展开时的第三栏） */
    public static final int EPG_W        = 422;

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

    /**
     * @param longSidePx 屏幕长边像素（不是 onCreate 时的 widthPixels ——
     *                   强制横屏的应用在 onCreate 里拿到的可能是竖屏值）
     */
    public static void init(int longSidePx) {
        sScale = longSidePx / 1920f;
        if (sScale <= 0f) sScale = 1f;
    }

    public static float scale() { return sScale; }

    /** 设计稿尺寸 → 实际像素 */
    public static int px(float design) { return Math.round(design * sScale); }

    /** 设计稿字号 → 实际像素 */
    /** 低分辨率下字号的绝对下限（实际像素）。低于这个值，远看就是一团灰。 */
    private static final float MIN_FONT_PX = 12f;

    public static float sp(float design) {
        // TV 上 dpi 常被厂商写乱，字号统一按屏宽缩放，不用 sp 避免被系统字体放大破坏版式
        float v = design * sScale;
        // 1024 长边这种老盒子 scale≈0.53，16px 的副标题会缩到 8.5px，
        // 接大电视根本看不清。这里给个绝对下限，宁可让排版略微挤一点。
        return v < MIN_FONT_PX ? MIN_FONT_PX : v;
    }

    public static int alpha(int color, float a) {
        int A = (int) (Color.alpha(color) * a);
        return (A << 24) | (color & 0x00FFFFFF);
    }
}
