package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.mintv.tv.ui.Theme;

/**
 * 频道列表的一行：台号块 +（可选）台标 + 台名 + 状态徽标 + 正在播出的节目。
 *
 * <p>关键改动：**台标不是必选项**
 * <ul>
 *   <li>源里写了 <code>tvg-logo</code> → 加载并绘制台标，台名右移
 *   <li>没写 → 图标位整段不占宽度，台名直接跟在台号后面
 * </ul>
 * 不从台名去猜图标，也不画占位方块 —— 源里没有就是没有。
 *
 * <p>高 92 设计稿像素。所有文本都是外部一次性喂进来的 String，
 * onDraw 里绝不 format，避免滚动时分配 String 造成 GC 抖动。
 */
public class ChannelView extends BaseCardView {

    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int number = 0;
    private String numberText = "";
    private String name = "";
    private String sub = "";
    private String badge;
    private Bitmap logo;

    private final float padH = Theme.px(16);
    private final float numW = Theme.px(62), numH = Theme.px(30);
    private final float nameSize = Theme.sp(Theme.TS_CHAN_TITLE);
    private final float subSize = Theme.sp(Theme.TS_CHAN_SUB);
    private final float numSize = Theme.sp(Theme.TS_NUM);

    public ChannelView(Context c) {
        super(c);
        radius = Theme.px(Theme.RADIUS_ITEM);
        t.setTypeface(Typeface.DEFAULT);
        numBg.setStyle(Paint.Style.FILL);
        badgeBg.setStyle(Paint.Style.FILL);
    }

    /** 台号来自解析器的自增序号，四位补零（0076 那种）。 */
    public void setNumber(int n) {
        number = n;
        String s = String.valueOf(n);
        while (s.length() < 4) s = "0" + s;
        numberText = s;
    }

    public void setName(String n) { name = n == null ? "" : n; }

    /** 节目信息。源里没有 EPG 时传空串，副行就不画。 */
    public void setSub(String s) { sub = s == null ? "" : s; }

    public void setBadge(String b) { badge = b; }

    /** null = 源里没有台标，图标位不占宽度。 */
    public void setLogo(Bitmap b) {
        if (logo != b) {
            logo = b;
            invalidate();
        }
    }

    public void commit() { invalidate(); }

    /** 有台标才为 true，供列表计算台名起始位置时复用。 */
    public boolean hasLogo() { return logo != null && !logo.isRecycled(); }

    private String fit(String text, float maxW, float size) {
        if (text == null) return "";
        t.setTextSize(size);
        if (t.measureText(text) <= maxW) return text;
        float ellW = t.measureText("…");
        int n = text.length();
        while (n > 0 && t.measureText(text.substring(0, n)) + ellW > maxW) n--;
        return text.substring(0, n) + "…";
    }

    @Override
    protected void onDraw(Canvas c) {
        buildRoundRect();
        drawBg(c, Theme.PANEL_ITEM, Theme.FOCUS_BG);

        float cy = getHeight() / 2f;
        float x = padH;

        // ---- 台号块 ----
        numBg.setColor(0x1AFFFFFF);
        rect.set(x, cy - numH / 2f, x + numW, cy + numH / 2f);
        path.reset();
        path.addRoundRect(rect, Theme.px(4), Theme.px(4), android.graphics.Path.Direction.CW);
        c.drawPath(path, numBg);

        t.setColor(0xFFB9B9BD);
        t.setTextSize(numSize);
        t.setFakeBoldText(false);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(numberText, x + numW / 2f, cy - (t.descent() + t.ascent()) / 2f, t);
        t.setTextAlign(Paint.Align.LEFT);

        float tx = x + numW + Theme.px(14);

        // ---- 台标（有才画，没有不留白）----
        if (hasLogo()) {
            float lh = Theme.px(52);
            float lw = lh * logo.getWidth() / (float) logo.getHeight();
            if (lw > Theme.px(92)) lw = Theme.px(92);
            rect.set(tx, cy - lh / 2f, tx + lw, cy + lh / 2f);
            c.drawBitmap(logo, null, rect, null);
            tx += lw + Theme.px(12);
        }

        // ---- 台名 ----
        float nameBaseline = sub.isEmpty() ? cy - (t.descent() + t.ascent()) / 2f : cy - Theme.px(10);
        t.setColor(Theme.TEXT);
        t.setTextSize(nameSize);
        t.setFakeBoldText(false);

        // ---- 徽标 ----
        float nameW = t.measureText(name);
        if (badge != null && !badge.isEmpty()) {
            float bs = Theme.sp(12);
            t.setTextSize(bs);
            float bw = t.measureText(badge) + Theme.px(16);
            float bh = Theme.px(22);
            // 徽标贴右对齐，不和台名挤在一起
            float bx = getWidth() - padH - bw;
            badgeBg.setColor("播放中".equals(badge) ? Theme.FOCUS_BLUE : 0x29FFFFFF);
            rect.set(bx, nameBaseline - bh + Theme.px(3), bx + bw, nameBaseline + Theme.px(3));
            path.reset();
            path.addRoundRect(rect, bh / 2f, bh / 2f, android.graphics.Path.Direction.CW);
            c.drawPath(path, badgeBg);
            t.setColor(Theme.TEXT);
            t.setTextAlign(Paint.Align.CENTER);
            c.drawText(badge, bx + bw / 2f, nameBaseline - Theme.px(2), t);
            t.setTextAlign(Paint.Align.LEFT);
            t.setTextSize(nameSize);
        }

        t.setColor(Theme.TEXT);
        t.setTextSize(nameSize);
        float nameMax = getWidth() - tx - padH - (badge != null ? Theme.px(90) : Theme.px(4));
        c.drawText(fit(name, nameMax, nameSize), tx, nameBaseline, t);

        // ---- 副行（只在有节目信息时画）----
        if (!sub.isEmpty()) {
            t.setColor(Theme.TEXT_SUB);
            t.setTextSize(subSize);
            c.drawText(sub, tx, cy + Theme.px(30), t);
        }

        drawFocusStroke(c, Theme.FOCUS_BLUE);
    }
}
