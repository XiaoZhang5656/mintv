package com.mintv.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import com.mintv.tv.ui.player.PlaybackBackend;

/**
 * 换台 OSD —— 参考主流 TV 播放器的信息密度重做。
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │ [0001]  CCTV-1 综合                        [央视]    │
 * │ 目前播放: 20:03 伟大的长征第38集                      │
 * │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░  77%                   │
 * │ 下个节目: 20:53 前情提要-伟大的长征-第39集            │
 * │ [源 3/5] [回看] [1280x720] [H264] [AAC] [立体声] [25fps]  20:42:15 │
 * └──────────────────────────────────────────────────────┘
 * </pre>
 *
 * 所有信息都来自播放器实时回调；拿不到的项目（EPG、分辨率）就把那一段隐藏，
 * 不画占位符也不编假数据。
 */
public class OsdView extends View {

    private static final long OSD_MS = 4000;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    // ---- 频道 / 节目 ----
    private String no, name, group;
    private String nowText;       // "20:03 伟大的长征第38集"
    private String nextText;      // "20:53 前情提要"
    private float progress = -1f; // 0~1，<0 不画进度条

    // ---- 技术信息小标签 ----
    private final String[] tags = new String[8];   // 源 / 回看 / 分辨率 / 视频编码 / 音频编码 / 声道 / 帧率 / 网速
    private final boolean[] tagOn = new boolean[8];

    private String clockText;
    private String statusText;

    private long osdUntil;
    private float osdAlpha;

    private final Runnable fadeTask = new Runnable() {
        @Override public void run() {
            osdAlpha = 0f;
            invalidate();
        }
    };

    public OsdView(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        p.setTypeface(Typeface.DEFAULT);
        fill.setStyle(Paint.Style.FILL);
    }

    // ================= 外部喂数据 =================

    public void showChannel(String no, String name, String group) {
        this.no = no;
        this.name = name;
        this.group = group;
        this.nowText = null;
        this.nextText = null;
        this.progress = -1f;
        for (int i = 0; i < tagOn.length; i++) tagOn[i] = false;
        kick();
    }

    /** EPG：目前播放 / 下个节目 / 进度。没有 EPG 就不调用。 */
    public void setProgram(String now, String next, float progress01) {
        nowText = now;
        nextText = next;
        progress = progress01;
        invalidate();
    }

    /** 技术标签。null 或空串表示该项不可用，直接不画。 */
    public void setTags(String source, String replay, String resolution,
                        String vCodec, String aCodec, String channels, String fps) {
        setTags(source, replay, resolution, vCodec, aCodec, channels, fps, null);
    }

    /** 带网速的版本。speed 形如 "1.2 MB/s"，null 表示不显示。 */
    public void setTags(String source, String replay, String resolution,
                        String vCodec, String aCodec, String channels, String fps,
                        String speed) {
        String[] v = {source, replay, resolution, vCodec, aCodec, channels, fps, speed};
        for (int i = 0; i < tags.length; i++) {
            boolean on = v[i] != null && !v[i].isEmpty();
            tagOn[i] = on;
            if (on) tags[i] = v[i];
        }
        invalidate();
    }

    private String toastText;
    private final Runnable toastTask = new Runnable() {
        @Override public void run() {
            toastText = null;
            invalidate();
        }
    };

    /** 一次性提示，2 秒后自动消失，不会像状态文本那样一直挂着。 */
    public void toast(String text) {
        toastText = text;
        removeCallbacks(toastTask);
        postDelayed(toastTask, 2000);
        invalidate();
    }

    public void setStatus(String s) {
        if ((s == null && statusText == null) || (s != null && s.equals(statusText))) return;
        statusText = s;
        invalidate();
    }

    public void tick() { invalidate(); }

    /** 供日志/自检用：当前 OSD 是否可见。 */
    public boolean isVisible() { return osdAlpha > 0f && name != null; }

    public String debugLine() {
        return "visible=" + isVisible() + " alpha=" + osdAlpha + " no=" + no
                + " name=" + name + " tagOn0=" + tagOn[0];
    }

    private void kick() {
        android.util.Log.d("TVUI", "kick name=" + name + " no=" + no);
        osdAlpha = 1f;
        osdUntil = SystemClock.uptimeMillis() + OSD_MS;
        removeCallbacks(fadeTask);
        postDelayed(fadeTask, OSD_MS);
        invalidate();
    }

    public static String stateText(PlaybackBackend.State st) {
        switch (st) {
            case BUFFERING: return "缓冲中…";
            case RECONNECTING: return "重连中…";
            case ERROR: return "源失效";
            default: return null;
        }
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();

        if (osdAlpha > 0f && name != null) {
            drawOsd(c, h);
        }

        if (toastText != null) {
            p.setColor(Theme.TEXT);
            p.setTextSize(Theme.sp(19));
            p.setTextAlign(Paint.Align.RIGHT);
            p.setShadowLayer(Theme.px(4), 0, 0, 0xDD000000);
            c.drawText(toastText, w - Theme.px(40), h - Theme.px(90), p);
            p.setShadowLayer(0, 0, 0, 0);
            p.setTextAlign(Paint.Align.LEFT);
        }

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

    private void drawOsd(Canvas c, float h) {
        float padH = Theme.px(22);
        float cardW = Theme.px(1080);

        // 先量高度：标题行 + 目前 + 进度 + 下个 + 标签行
        float lineTitle = Theme.sp(28);
        float lineSub = Theme.sp(18);
        float barH = Theme.px(6);
        float chipH = Theme.px(26);
        float gap = Theme.px(10);

        float contentH = padH
                + lineTitle + gap
                + (nowText != null ? lineSub + gap : 0)
                + (progress >= 0 ? barH + gap : 0)
                + (nextText != null ? lineSub + gap : 0)
                + chipH + padH;
        float cardH = contentH;
        float left = (getWidth() - cardW) / 2f;   // 底部居中
        float bottom = h - Theme.px(56);
        float top = bottom - cardH;

        // 卡片底
        r.set(left, top, left + cardW, bottom);
        fill.setColor(0xE0101216);
        c.drawRoundRect(r, Theme.px(8), Theme.px(8), fill);
        // 左侧蓝条
        fill.setColor(Theme.FOCUS_BLUE);
        r.set(left, top, left + Theme.px(3), bottom);
        c.drawRoundRect(r, Theme.px(1), Theme.px(1), fill);

        float x = left + padH;
        float y = top + padH + lineTitle * 0.78f;

        // ---- 第 1 行：台号块 + 台名 + 分类胶囊 ----
        float noW = Theme.px(66), noH = Theme.px(30);
        float noTop = y - Theme.sp(28) * 0.78f + Theme.px(2);
        fill.setColor(Theme.FOCUS_BLUE);
        r.set(x, noTop, x + noW, noTop + noH);
        c.drawRoundRect(r, Theme.px(3), Theme.px(3), fill);
        p.setColor(Theme.TEXT);
        p.setTextSize(Theme.sp(18));
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(no == null ? "" : no, x + noW / 2f, noTop + noH / 2f + Theme.px(6), p);
        p.setTextAlign(Paint.Align.LEFT);

        p.setColor(Theme.TEXT);
        p.setTextSize(lineTitle);
        p.setFakeBoldText(false);
        float nameX = x + noW + Theme.px(16);
        c.drawText(name, nameX, y, p);

        // 分类胶囊靠右
        if (group != null && !group.isEmpty()) {
            float cs = Theme.sp(16);
            p.setTextSize(cs);
            float cw = p.measureText(group) + Theme.px(18);
            float cx = left + cardW - padH - cw;
            fill.setColor(0xFF3A6EA5);
            r.set(cx, noTop, cx + cw, noTop + noH);
            c.drawRoundRect(r, Theme.px(3), Theme.px(3), fill);
            p.setColor(Theme.TEXT);
            c.drawText(group, cx + Theme.px(9), noTop + noH / 2f + Theme.px(5), p);
        }

        y += gap;

        // ---- 第 2 行：目前播放 ----
        float rowX = x;
        if (nowText != null) {
            y += lineSub * 0.8f;
            p.setColor(0xFF8E8E93);
            p.setTextSize(Theme.sp(17));
            c.drawText("目前播放:", rowX, y, p);
            float labelW = p.measureText("目前播放:");
            p.setColor(0xFFE6E6E8);
            p.setTextSize(lineSub);
            c.drawText(fit(nowText, cardW - padH * 2 - labelW - Theme.px(10), lineSub),
                    rowX + labelW + Theme.px(10), y, p);
            y += gap;
        }

        // ---- 第 3 行：进度条 ----
        if (progress >= 0f) {
            float fullW = cardW - padH * 2;
            float barW = fullW - Theme.px(60);
            float barTop = y;
            fill.setColor(0x33FFFFFF);
            r.set(rowX, barTop, rowX + barW, barTop + barH);
            c.drawRoundRect(r, barH / 2f, barH / 2f, fill);
            fill.setColor(0xFF2F7BFF);
            r.set(rowX, barTop, rowX + barW * Math.min(1f, progress), barTop + barH);
            c.drawRoundRect(r, barH / 2f, barH / 2f, fill);
            p.setColor(0xFFB9B9BD);
            p.setTextSize(Theme.sp(16));
            c.drawText(Math.round(progress * 100) + "%", rowX + barW + Theme.px(12),
                    barTop + barH + Theme.px(4), p);
            y += barH + gap;
        }

        // ---- 第 4 行：下个节目 ----
        if (nextText != null) {
            y += lineSub * 0.8f;
            p.setColor(0xFF8E8E93);
            p.setTextSize(Theme.sp(17));
            c.drawText("下个节目:", rowX, y, p);
            float labelW = p.measureText("下个节目:");
            p.setColor(0xFFC9C9CE);
            p.setTextSize(Theme.sp(17));
            c.drawText(fit(nextText, cardW - padH * 2 - labelW - Theme.px(10), Theme.sp(17)),
                    rowX + labelW + Theme.px(10), y, p);
            y += gap;
        }

        // ---- 第 5 行：技术标签 + 时钟 ----
        float chipTop = bottom - padH - chipH;
        float cx = rowX;
        for (int i = 0; i < tags.length; i++) {
            if (!tagOn[i]) continue;
            p.setTextSize(Theme.sp(15));
            float tw = p.measureText(tags[i]) + Theme.px(16);
            fill.setColor(chipColor(i));
            r.set(cx, chipTop, cx + tw, chipTop + chipH);
            c.drawRoundRect(r, Theme.px(3), Theme.px(3), fill);
            p.setColor(Theme.TEXT);
            c.drawText(tags[i], cx + Theme.px(8), chipTop + chipH / 2f + Theme.px(5), p);
            cx += tw + Theme.px(8);
            if (cx > left + cardW - Theme.px(90)) break;   // 放不下就截断
        }

        // 右下角时钟
        String t = clockText != null ? clockText : nowClock();
        p.setColor(0xFF9A9A9E);
        p.setTextSize(Theme.sp(16));
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(t, left + cardW - padH, chipTop + chipH / 2f + Theme.px(5), p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private static int chipColor(int i) {
        switch (i) {
            case 0: return 0xFF1F5FA8;   // 源
            case 1: return 0xFF3A3B3E;   // 回看
            case 2: return 0xFF2B2C2E;   // 分辨率
            case 3: return 0xFF2E6B3E;   // H264
            case 4: return 0xFF7A4B1E;   // AAC
            case 5: return 0xFF5A2E6E;   // 声道
            default: return 0xFF2B2C2E;  // fps
        }
    }

    private String nowClock() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date());
    }

    public void setClock(String s) {
        clockText = s;
        invalidate();
    }

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
