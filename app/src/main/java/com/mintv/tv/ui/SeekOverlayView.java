package com.mintv.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * 长按快进的进度预览层。
 *
 * <p>设计目标：长按 → 键的那一刻，用户就能看到「从哪、到哪、快进了多久」。
 *
 * <pre>
 *         ┌──────────────────────────────────────────────┐
 *         │  00:12:30                        01:42:30    │
 *         │  ▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░   │
 *         │  ▲ 当前位置          ▲ 目标              ⏩ +90 分  │
 *         └──────────────────────────────────────────────┘
 * </pre>
 *
 * 三层信息：
 * 1. 底部整条进度轨 = 全片时长
 * 2. 已播部分实心蓝、目标位置有一条竖直游标
 * 3. 顶部左右显示「当前时间」和「目标时间」，右下角显示快进量
 *
 * 完全自绘、零分配，只在 speed 变化时 invalidate。
 */
public class SeekOverlayView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private boolean visible = false;
    private long positionMs = 0;
    private long targetMs = 0;
    private long durationMs = 0;

    private final float padH = Theme.px(40);
    private final float cardH = Theme.px(150);
    private final float barH = Theme.px(8);

    public SeekOverlayView(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        p.setTypeface(Typeface.DEFAULT);
        f.setStyle(Paint.Style.FILL);
    }

    public void show() {
        if (!visible) {
            visible = true;
            invalidate();
        }
    }

    public void hide() {
        if (visible) {
            visible = false;
            invalidate();
        }
    }

    public boolean isShowing() { return visible; }

    /** 更新预览。durationMs <= 0 时不画（直播无时长）。 */
    public void update(long positionMs, long targetMs, long durationMs) {
        this.positionMs = positionMs;
        this.targetMs = targetMs;
        this.durationMs = durationMs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!visible || durationMs <= 0) return;

        float w = getWidth(), h = getHeight();
        float top = h - Theme.px(56) - cardH;
        float left = padH;
        float right = w - padH;

        // 底板
        f.setColor(0xD9101216);
        r.set(left, top, right, top + cardH);
        c.drawRoundRect(r, Theme.px(10), Theme.px(10), f);

        float barLeft = left + Theme.px(24);
        float barRight = right - Theme.px(24);
        float barY = top + cardH * 0.58f;
        float barW = barRight - barLeft;

        float posRatio = clamp01(positionMs / (float) durationMs);
        float tgtRatio = clamp01(targetMs / (float) durationMs);

        // 整条轨道
        f.setColor(0x33FFFFFF);
        r.set(barLeft, barY, barRight, barY + barH);
        c.drawRoundRect(r, barH / 2f, barH / 2f, f);

        // 已播 + 目标之间的区间（高亮，一眼看出跳多远）
        float a = Math.min(posRatio, tgtRatio);
        float b = Math.max(posRatio, tgtRatio);
        f.setColor(0x662F7BFF);
        r.set(barLeft + barW * a, barY, barLeft + barW * b, barY + barH);
        c.drawRoundRect(r, barH / 2f, barH / 2f, f);

        // 已播实心
        f.setColor(0xFF2F7BFF);
        r.set(barLeft, barY, barLeft + barW * posRatio, barY + barH);
        c.drawRoundRect(r, barH / 2f, barH / 2f, f);

        // 目标游标
        float cx = barLeft + barW * tgtRatio;
        f.setColor(0xFFFFFFFF);
        c.drawCircle(cx, barY + barH / 2f, Theme.px(9), f);
        f.setColor(0xFF2F7BFF);
        c.drawCircle(cx, barY + barH / 2f, Theme.px(5), f);

        // 顶部时间
        p.setColor(0xFFB9B9BD);
        p.setTextSize(Theme.sp(18));
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(fmt(positionMs), barLeft, top + Theme.px(42), p);

        p.setColor(Theme.TEXT);
        p.setTextSize(Theme.sp(20));
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(fmt(targetMs), barRight, top + Theme.px(44), p);

        // 右下角：快进量
        long delta = targetMs - positionMs;
        if (delta != 0) {
            String tag = (delta > 0 ? "快进 " : "快退 ") + fmtDelta(Math.abs(delta));
            p.setTextSize(Theme.sp(17));
            p.setColor(delta > 0 ? 0xFF6FD08A : 0xFFE0A050);
            c.drawText(tag, barRight, top + cardH - Theme.px(22), p);
        }

        // 总时长，居中
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xFF7A7A80);
        p.setTextSize(Theme.sp(16));
        c.drawText("/ " + fmt(durationMs), (barLeft + barRight) / 2f, top + cardH - Theme.px(22), p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** 毫秒 → HH:MM:SS（不足一小时省略小时） */
    public static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long total = ms / 1000;
        long hh = total / 3600;
        long mm = (total % 3600) / 60;
        long ss = total % 60;
        if (hh > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", hh, mm, ss);
        return String.format(java.util.Locale.US, "%02d:%02d", mm, ss);
    }

    /** 快进量：90 秒 → "1 分 30 秒" */
    public static String fmtDelta(long ms) {
        long total = ms / 1000;
        long mm = total / 60;
        long ss = total % 60;
        if (mm > 0 && ss > 0) return mm + " 分 " + ss + " 秒";
        if (mm > 0) return mm + " 分";
        return ss + " 秒";
    }
}
