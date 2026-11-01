package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import com.mintv.tv.ui.Theme;

/**
 * 节目单飞出层 —— 点一个频道展开「一周回看」式的时段列表。
 *
 * <pre>
 * ┌─────────────────────────────────────┐
 * │ CCTV-1 综合 · 一周回看               │
 * │ 16:40 太行谣第1集          可回看    │
 * │ 17:26 太行谣第2集          可回看    │
 * │ 20:51 伟大的长征第39集     直播中    │  ← 当前节目高亮
 * │ 21:40 非遗里的中国-MV                │
 * └─────────────────────────────────────┘
 * </pre>
 *
 * 自绘 + 手写裁剪，不用 RecyclerView：这个列表最多几十条，
 * 一屏只画可见的十来条，比 RecyclerView 少一层 measure 遍历。
 */
public class EpgPanelView extends android.view.View {

    public static final class Item {
        public final String time;
        public final String title;
        public final boolean live;      // 正在播
        public final boolean replay;    // 可回看

        public Item(String time, String title, boolean live, boolean replay) {
            this.time = time;
            this.title = title;
            this.live = live;
            this.replay = replay;
        }
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private String header = "";
    private Item[] items = new Item[0];
    private int focusIndex = -1;

    private final float padH = Theme.px(22);
    private final float headerH = Theme.px(58);
    private final float itemH = Theme.px(52);
    private final float timeW = Theme.px(64);

    public EpgPanelView(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        p.setTypeface(Typeface.DEFAULT);
        fill.setStyle(Paint.Style.FILL);
    }

    /** 第三栏自己负责滚动，这里告诉外面总高度。 */
    public float contentHeight() {
        return headerH + items.length * itemH + Theme.px(12);
    }

    public void setData(String header, Item[] items) {
        this.header = header == null ? "" : header;
        this.items = items == null ? new Item[0] : items;
        this.focusIndex = -1;
        invalidate();
    }

    public void setFocus(int i) {
        focusIndex = i;
        invalidate();
    }

    public int itemAt(float y) {
        float top = headerH;
        int i = (int) ((y - top) / itemH);
        return (i >= 0 && i < items.length) ? i : -1;
    }

    public int count() { return items.length; }

    public static float heightFor(int count) {
        // 标题 + 行 + 底部留白
        return Theme.px(64) + Math.max(1, Math.min(count, 10)) * Theme.px(56) + Theme.px(16);
    }

    @Override
    protected void onDraw(Canvas c) {
        // 标题
        p.setColor(0xFFECECEE);
        p.setTextSize(Theme.sp(21));
        p.setFakeBoldText(false);
        c.drawText(fit(header, getWidth() - padH * 2, Theme.sp(21)), padH, Theme.px(36), p);

        // 标题分隔线
        fill.setColor(0x1AFFFFFF);
        c.drawRect(padH, headerH, getWidth() - padH, headerH + Theme.px(1), fill);

        float y = headerH;
        for (int i = 0; i < items.length; i++) {
            if (y > getHeight()) break;
            Item it = items[i];

            // 当前/焦点行底色
            if (it.live) {
                fill.setColor(0x332F7BFF);
                r.set(padH - Theme.px(8), y, getWidth() - padH + Theme.px(8), y + itemH);
                c.drawRoundRect(r, Theme.px(4), Theme.px(4), fill);
            } else if (i == focusIndex) {
                fill.setColor(0x1AFFFFFF);
                r.set(padH - Theme.px(8), y, getWidth() - padH + Theme.px(8), y + itemH);
                c.drawRoundRect(r, Theme.px(4), Theme.px(4), fill);
            }

            float baseline = y + itemH / 2f + Theme.px(7);

            // 时间
            p.setColor(it.live ? Theme.FOCUS_BLUE : 0xFF9A9A9E);
            p.setTextSize(Theme.sp(18));
            c.drawText(it.time, padH, baseline, p);

            // 标题
            p.setColor(it.live ? Theme.TEXT : 0xFFE2E2E6);
            p.setTextSize(Theme.sp(19));
            float tx = padH + timeW;
            float maxW = getWidth() - tx - padH - Theme.px(70);
            c.drawText(fit(it.title, maxW, Theme.sp(19)), tx, baseline, p);

            // 右侧标记
            String mark = it.live ? "直播中" : (it.replay ? "可回看" : null);
            if (mark != null) {
                p.setTextSize(Theme.sp(14));
                p.setColor(it.live ? Theme.FOCUS_BLUE : 0xFF9A9A9E);
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(mark, getWidth() - padH, baseline, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            y += itemH;
        }
    }

    private String fit(String s, float maxW, float size) {
        if (s == null) return "";
        p.setTextSize(size);
        if (p.measureText(s) <= maxW) return s;
        float ellW = p.measureText("…");
        int n = s.length();
        while (n > 0 && p.measureText(s.substring(0, n)) + ellW > maxW) n--;
        return s.substring(0, n) + "…";
    }
}
