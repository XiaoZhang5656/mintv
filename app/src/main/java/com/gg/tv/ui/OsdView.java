package com.gg.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import com.gg.tv.ui.player.PlaybackBackend;

/**
 * 屏幕上那层「软 UI」：
 *   · 换台 OSD（台号 + 台名），2.5 秒后淡出
 *   · 缓冲 / 重连 / 源失效状态
 *
 * 关键点：播放画面走 SurfaceView 的独立图层，跟这层 View 完全不重叠绘制，
 * 所以列表滚动时视频不会跟着重绘 —— 这是低端盒子不卡的根本原因。
 *
 * 注意：OSD 淡出不用 postInvalidateOnAnimation 死循环（那样会把 View 锁在
 * 60fps 重绘，低端机上白白吃掉一整个 vsync 周期），而是用一个单次 postDelayed
 * 触发消失。
 */
public class OsdView extends View {

    private static final long OSD_MS = 2500;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private String osdNo, osdName;
    private long osdUntil = 0;
    private float osdAlpha = 0f;

    private String statusText;

    private final Runnable fadeTask = new Runnable() {
        @Override public void run() {
            // 简单两帧淡出不需要 ValueAnimator，直接一次置零重绘
            osdNo = null;
            osdName = null;
            osdAlpha = 0f;
            invalidate();
        }
    };

    public OsdView(Context c) {
        super(c);
        setWillNotDraw(false);
        p.setTypeface(Typeface.DEFAULT);
        box.setStyle(Paint.Style.FILL);
    }

    public void showChannelOsd(String no, String name) {
        osdNo = no;
        osdName = name;
        osdAlpha = 1f;
        osdUntil = SystemClock.uptimeMillis() + OSD_MS;
        removeCallbacks(fadeTask);
        postDelayed(fadeTask, OSD_MS);
        invalidate();
    }

    public void setStatus(String s) {
        if ((s == null && statusText == null) || (s != null && s.equals(statusText))) return;
        statusText = s;
        invalidate();
    }

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

        // ---- 换台 OSD：左下角 ----
        if (osdNo != null && osdAlpha > 0f) {
            float left = Theme.px(60), bottom = h - Theme.px(76);
            float bw = Theme.px(320), bh = Theme.px(104);
            r.set(left, bottom - bh, left + bw, bottom);
            box.setColor(0xD90B0B0C);
            c.drawRoundRect(r, Theme.px(6), Theme.px(6), box);

            // 左侧一道蓝色竖条，跟焦点色统一
            box.setColor(Theme.FOCUS_BLUE);
            r.set(left, bottom - bh, left + Theme.px(4), bottom);
            c.drawRoundRect(r, Theme.px(2), Theme.px(2), box);

            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(Theme.TEXT);
            p.setTextSize(Theme.sp(32));
            p.setFakeBoldText(true);
            c.drawText(osdNo == null ? "" : osdNo, left + Theme.px(20), bottom - Theme.px(58), p);

            p.setColor(0xFFCFCFCF);
            p.setTextSize(Theme.sp(20));
            p.setFakeBoldText(false);
            c.drawText(fit(osdName, bw - Theme.px(40), Theme.sp(20)),
                    left + Theme.px(20), bottom - Theme.px(24), p);
        }

        // ---- 状态：右下角 ----
        if (statusText != null) {
            p.setColor(Theme.TEXT);
            p.setTextSize(Theme.sp(18));
            p.setTextAlign(Paint.Align.RIGHT);
            p.setShadowLayer(Theme.px(3), 0, 0, 0xCC000000);
            c.drawText(statusText, w - Theme.px(36), h - Theme.px(48), p);
            p.setShadowLayer(0, 0, 0, 0);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** 文本超宽时截断加省略号，绝不溢出框。 */
    private String fit(String s, float maxW, float size) {
        if (s == null) return "";
        p.setTextSize(size);
        if (p.measureText(s) <= maxW) return s;
        String ell = "…";
        float ellW = p.measureText(ell);
        int n = s.length();
        while (n > 0 && p.measureText(s.substring(0, n)) + ellW > maxW) n--;
        return s.substring(0, n) + ell;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(fadeTask);
        super.onDetachedFromWindow();
    }
}
