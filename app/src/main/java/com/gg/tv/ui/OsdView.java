package com.gg.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import com.gg.tv.ui.player.PlaybackBackend;

/**
 * 屏幕上那层「软 UI」：
 *   · 换台 OSD（台号 + 节目）
 *   · 网速 / 缓冲指示
 *   · 用 SurfaceView 铺底播放，这个 View 永远盖在上面
 *
 * 关键点：播放画面走 SurfaceView 的独立图层，跟这层 View 完全不重叠绘制，
 * 所以列表滚动时视频不会跟着重绘 —— 这是低端盒子不卡的根本原因。
 */
public class OsdView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String osdNo = null, osdName = null;
    private long osdUntil = 0;

    private String speed = null;
    private String statusText = null;

    public OsdView(Context c) {
        super(c);
        setWillNotDraw(false);
        p.setTypeface(Typeface.DEFAULT);
    }

    public void showChannelOsd(String no, String name) {
        osdNo = no;
        osdName = name;
        osdUntil = SystemClock.uptimeMillis() + 2500;
        postInvalidateOnAnimation();
    }

    public void setSpeed(String s) { speed = s; postInvalidateOnAnimation(); }

    public void setStatus(String s) { statusText = s; postInvalidateOnAnimation(); }

    public static String stateText(PlaybackBackend.State st) {
        switch (st) {
            case BUFFERING: return "缓冲中…";
            case RECONNECTING: return "重连中…";
            case READY: return null;
            case ERROR: return "源失效";
            case IDLE:
            default: return null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();

        // ---- 换台 OSD：左下角 ----
        if (osdNo != null && now < osdUntil) {
            float left = Theme.px(60), bottom = h - Theme.px(80);
            float bw = Theme.px(300), bh = Theme.px(96);
            box.setColor(0xCC0B0B0C);
            android.graphics.RectF r = new android.graphics.RectF(left, bottom - bh, left + bw, bottom);
            c.drawRoundRect(r, Theme.px(6), Theme.px(6), box);

            p.setColor(Theme.TEXT);
            p.setTextSize(Theme.sp(34));
            p.setFakeBoldText(true);
            c.drawText(osdNo, left + Theme.px(20), bottom - Theme.px(52), p);

            p.setColor(0xFFCFCFCF);
            p.setTextSize(Theme.sp(20));
            p.setFakeBoldText(false);
            c.drawText(osdName == null ? "" : osdName, left + Theme.px(20), bottom - Theme.px(20), p);

            postInvalidateOnAnimation();
        }

        // ---- 状态 / 网速：右下角 ----
        float rx = w - Theme.px(36);
        if (statusText != null) {
            p.setColor(Theme.TEXT);
            p.setTextSize(Theme.sp(18));
            p.setTextAlign(Paint.Align.RIGHT);
            p.setShadowLayer(Theme.px(3), 0, 0, 0xCC000000);
            c.drawText(statusText, rx, h - Theme.px(60), p);
            p.setShadowLayer(0, 0, 0, 0);
        }
        if (speed != null) {
            p.setColor(0xFFB9B9BD);
            p.setTextSize(Theme.sp(16));
            p.setTextAlign(Paint.Align.RIGHT);
            p.setShadowLayer(Theme.px(3), 0, 0, 0xCC000000);
            c.drawText(speed, rx, h - Theme.px(32), p);
            p.setShadowLayer(0, 0, 0, 0);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }
}
