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
        public final boolean replay;    // 已播完且源支持回看 → 可点
        public final boolean ended;     // 已播完（不管源支不支持）
        public final long startMs;
        public final long stopMs;

        public Item(String time, String title, boolean live, boolean replay,
                    boolean ended, long startMs, long stopMs) {
            this.time = time;
            this.title = title;
            this.live = live;
            this.replay = replay;
            this.ended = ended;
            this.startMs = startMs;
            this.stopMs = stopMs;
        }
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private String header = "";
    private Item[] items = new Item[0];
    private int focusIndex = -1;

    private final float padH = Theme.px(20);
    private final float headerH = Theme.px(66);
    private final float itemH = Theme.px(62);
    private final float timeW = Theme.px(72);

    public EpgPanelView(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        p.setTypeface(Typeface.DEFAULT);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
    }

    /** 内容总高度，供 ScrollView 正确测量。 */
    public float contentHeight() {
        return headerH + items.length * itemH + Theme.px(12);
    }

    /**
     * ScrollView 里放 MATCH_PARENT 高度的子 View 会被测量成 0，
     * 必须自己按内容算高度（WRAP_CONTENT 模式）。
     */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) contentHeight();
        int mode = MeasureSpec.getMode(heightSpec);
        if (mode == MeasureSpec.EXACTLY) {
            h = MeasureSpec.getSize(heightSpec);
        }
        setMeasuredDimension(w, Math.max(h, Theme.px(120)));
    }

    public void setData(String header, Item[] items) {
        this.header = header == null ? "" : header;
        this.items = items == null ? new Item[0] : items;
        this.focusIndex = -1;
        requestLayout();
        invalidate();
    }

    /** 正在播的那条的下标，-1 表示没有。用于把列表滚到当前节目。 */
    public int liveIndex() {
        for (int i = 0; i < items.length; i++) {
            if (items[i].live) return i;
        }
        return -1;
    }

    /** 正在回放的那一条（黄色高亮）。-1 = 没有。 */
    private int replayIndex = -1;

    public void setReplayIndex(int i) {
        if (replayIndex == i) return;
        replayIndex = i;
        invalidate();
    }

    public int replayIndex() { return replayIndex; }

    public void setFocus(int i) {
        focusIndex = i;
        invalidate();
    }

    /** 第 i 行的顶部 y（含标题高度），给方向键滚动定位用。 */
    public float topOf(int i) { return headerH + i * itemH; }

    /** 单行高度。 */
    public float itemHeightPx() { return itemH; }

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
        p.setColor(Theme.HEADER_TEXT);
        p.setTextSize(Theme.sp(24));
        p.setFakeBoldText(false);
        c.drawText(fit(header, getWidth() - padH * 2, Theme.sp(24)), padH, Theme.px(42), p);

        // 标题分隔线
        fill.setColor(Theme.EPG_FOCUS_BG);
        c.drawRect(padH, headerH, getWidth() - padH, headerH + Theme.px(1), fill);

        float y = headerH;
        for (int i = 0; i < items.length; i++) {
            if (y > getHeight()) break;
            Item it = items[i];

            // 光标白框：不管这行能不能回看都要画。
            // 以前只有"可回看 + 焦点"才画底色，方向键移到未播/直播行时
            // 屏幕没有任何变化，用户会以为按键没反应。
            boolean cursor = (i == focusIndex);

            // 当前/焦点行底色。回放中的那一行优先级最高，画成黄色，
            // 让用户一眼看到"现在放的是哪一条"，而不是只能靠猜。
            if (i == replayIndex) {
                fill.setColor(0x40FFC53D);
                r.set(padH - Theme.px(8), y, getWidth() - padH + Theme.px(8), y + itemH);
                c.drawRoundRect(r, Theme.px(4), Theme.px(4), fill);
                fill.setColor(0xFFFFC53D);
                r.set(padH - Theme.px(8), y, padH - Theme.px(6), y + itemH);
                c.drawRect(r, fill);       // 左侧黄条
            } else if (it.live) {
                fill.setColor(Theme.EPG_LIVE_BG);
                r.set(padH - Theme.px(8), y, getWidth() - padH + Theme.px(8), y + itemH);
                c.drawRoundRect(r, Theme.px(4), Theme.px(4), fill);
            } else if (i == focusIndex && it.replay) {
                fill.setColor(Theme.EPG_FOCUS_BG);
                r.set(padH - Theme.px(8), y, getWidth() - padH + Theme.px(8), y + itemH);
                c.drawRoundRect(r, Theme.px(4), Theme.px(4), fill);
            }

            float baseline = y + itemH / 2f + Theme.px(7);

            // 时间
            p.setColor(i == replayIndex ? 0xFFFFC53D
                    : (it.live ? Theme.FOCUS_BLUE : Theme.TEXT_DIM));
            p.setTextSize(Theme.sp(20));
            c.drawText(it.time, padH, baseline, p);

            // 标题
            p.setColor(i == replayIndex ? 0xFFFFE3A3
                    : (it.live ? Theme.TEXT : Theme.TEXT));
            p.setTextSize(Theme.sp(21));
            float tx = padH + timeW;
            float maxW = getWidth() - tx - padH - Theme.px(76);
            c.drawText(fit(it.title, maxW, Theme.sp(21)), tx, baseline, p);

            // 右侧标记：直播中 / 回看（绿=可点，灰=源不支持）
            String mark;
            if (i == replayIndex) mark = "回放中";
            else if (it.live) mark = "直播中";
            else if (it.replay) mark = "回看";
            else if (it.ended) mark = "回看";     // 源不支持时也给按钮，灰掉
            else mark = null;                      // 还没播的节目不显示
            if (mark != null) {
                // 「回看」画成胶囊按钮，直播中只给文字
                if (!it.live) {
                    p.setTextSize(Theme.sp(16));
                    float mw = p.measureText(mark) + Theme.px(20);
                    float mh = Theme.px(30);
                    float bx = getWidth() - padH - mw;
                    float by = y + itemH / 2f - mh / 2f;
                    fill.setColor(it.replay ? 0x2E6FD08A : 0x14FFFFFF);
                    r.set(bx, by, bx + mw, by + mh);
                    c.drawRoundRect(r, Theme.px(4), Theme.px(4), fill);
                    p.setColor(it.replay ? 0xFF6FD08A : 0xFF5A5A60);
                    p.setTextAlign(Paint.Align.CENTER);
                    c.drawText(mark, bx + mw / 2f, baseline, p);
                    p.setTextAlign(Paint.Align.LEFT);
                    y += itemH;
                    continue;
                }
                p.setTextSize(Theme.sp(16));
                p.setColor(it.live ? Theme.FOCUS_BLUE
                        : (it.replay ? 0xFF6FD08A : 0xFF5A5A60));
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(mark, getWidth() - padH, baseline, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            // 光标描边
            if (cursor) {
                stroke.setColor(0xFFFFFFFF);
                stroke.setStrokeWidth(Theme.px(2));
                r.set(padH - Theme.px(8), y + Theme.px(1),
                        getWidth() - padH + Theme.px(8), y + itemH - Theme.px(1));
                c.drawRoundRect(r, Theme.px(4), Theme.px(4), stroke);
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
