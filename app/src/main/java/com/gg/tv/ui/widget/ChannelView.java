package com.gg.tv.ui.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.gg.tv.ui.Theme;

/**
 * 频道列表的一行：台号块 + 台名 + 状态徽标 + 正在播出的节目。
 *
 * 高 92 设计稿像素。所有文本都是成员变量拼好的 String，
 * onDraw 里绝不 format，避免每次滚动分配 String 造成 GC 抖动。
 */
public class ChannelView extends BaseCardView {

    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String number = "";
    private String name = "";
    private String sub = "";         // "正在播放 · 19:00 新闻联播"
    private String badge = null;     // null / "播放中" / "可回看"
    private Bitmap logo;

    private final float padH = Theme.px(16);
    private final float numW = Theme.px(52), numH = Theme.px(26);
    private final float nameSize = Theme.sp(Theme.TS_CHAN_TITLE);
    private final float subSize = Theme.sp(Theme.TS_CHAN_SUB);
    private final float numSize = Theme.sp(Theme.TS_NUM);

    private int colTitle, colSub, colNum, colBadgeText;

    public ChannelView(Context c) {
        super(c);
        radius = Theme.px(Theme.RADIUS_ITEM);
        t.setTypeface(Typeface.DEFAULT);
        numBg.setStyle(Paint.Style.FILL);
        badgeBg.setStyle(Paint.Style.FILL);
        colTitle = Theme.TEXT;
        colSub = Theme.TEXT_SUB;
        colNum = 0xFFB9B9BD;
        colBadgeText = Theme.TEXT;
    }

    public void setNumber(String n) { number = n == null ? "" : n; }

    public void setName(String n) { name = n == null ? "" : n; }

    public void setSub(String s) { sub = s == null ? "" : s; }

    public void setBadge(String b) { badge = b; }

    public void setLogo(Bitmap b) { logo = b; }

    public void commit() { invalidate(); }

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

        t.setColor(colNum);
        t.setTextSize(numSize);
        t.setFakeBoldText(false);
        t.setTextAlign(Paint.Align.CENTER);
        c.drawText(number, x + numW / 2f, cy - (t.descent() + t.ascent()) / 2f, t);
        t.setTextAlign(Paint.Align.LEFT);

        // ---- 台标（可选，96x96 解码）----
        float tx = x + numW + Theme.px(14);
        if (logo != null && !logo.isRecycled()) {
            float lh = Theme.px(40);
            rect.set(tx, cy - lh / 2f, tx + lh, cy + lh / 2f);
            c.drawBitmap(logo, null, rect, null);
            tx += lh + Theme.px(12);
        }

        // ---- 台名 ----
        float nameBaseline = cy - Theme.px(6);
        t.setColor(colTitle);
        t.setTextSize(nameSize);
        t.setFakeBoldText(false);
        c.drawText(name, tx, nameBaseline, t);

        // ---- 徽标 ----
        if (badge != null) {
            float nameW = t.measureText(name);
            float bs = Theme.sp(12);
            t.setTextSize(bs);
            float bw = t.measureText(badge) + Theme.px(16);
            float bh = Theme.px(22);
            float bx = tx + nameW + Theme.px(12);
            badgeBg.setColor("播放中".equals(badge) ? Theme.FOCUS_BLUE : 0x29FFFFFF);
            rect.set(bx, nameBaseline - bh + Theme.px(3), bx + bw, nameBaseline + Theme.px(3));
            path.reset();
            path.addRoundRect(rect, bh / 2f, bh / 2f, android.graphics.Path.Direction.CW);
            c.drawPath(path, badgeBg);
            t.setColor(colBadgeText);
            c.drawText(badge, bx + Theme.px(8), nameBaseline - Theme.px(2), t);
        }

        // ---- 副行 ----
        t.setColor(colSub);
        t.setTextSize(subSize);
        c.drawText(sub, x + numW + Theme.px(14), cy + Theme.px(26), t);

        drawFocusStroke(c, Theme.FOCUS_BLUE);
    }
}
