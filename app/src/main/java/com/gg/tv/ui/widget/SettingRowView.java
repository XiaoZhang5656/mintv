package com.gg.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.gg.tv.ui.Theme;

/**
 * 设置页的一行：标题 + 副标题 + 右侧分段选择器。
 *
 * 焦点语义跟设计稿一致：
 *   · 整行焦点（焦点在行上）→ 行外框白描边
 *   · 焦点进入选项 → 选项白框，行框消失
 */
public class SettingRowView extends android.view.View {

    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF rect = new android.graphics.RectF();
    private final android.graphics.Path path = new android.graphics.Path();

    private String title = "", desc = "";
    private final SegmentedView seg;

    private boolean rowFocused = false;
    private boolean optFocused = false;

    private final float padH = Theme.px(18);
    private final float labelW = Theme.px(340);
    private final float titleSize = Theme.sp(23);
    private final float descSize = Theme.sp(16);

    public SettingRowView(Context c) {
        super(c);
        setWillNotDraw(false);
        setFocusable(false);
        t.setTypeface(Typeface.DEFAULT);
        bg.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Theme.px(2));

        seg = new SegmentedView(c);
        // 手动摆放，不用 layout 参数 —— 固定尺寸，省一次 measure 遍历
    }

    public void bind(String title, String desc, String[] options, int selected) {
        this.title = title == null ? "" : title;
        this.desc = desc == null ? "" : desc;
        seg.setLabels(options);
        seg.setSelected(selected);
        invalidate();
    }

    public SegmentedView segment() { return seg; }
    public int selected() { return segResult; }

    private int segResult = 0;

    public void setSelected(int i) { seg.setSelected(i); segResult = i; }

    public void setRowFocused(boolean f) { rowFocused = f; if (f) optFocused = false; seg.setFocusedOption(-1); invalidate(); }

    public void setOptionFocused(int i) {
        optFocused = i >= 0;
        seg.setFocusedOption(i);
        invalidate();
    }

    /** onDraw 之前把分段控件摆到右边，尺寸交给它自己。 */
    private void layoutSeg() {
        int w = (int) seg.measuredWidth();
        int h = (int) (getHeight() * 0.62f);
        int left = getWidth() - (int) padH - w;
        int top = (getHeight() - h) / 2;
        seg.layout(left, top, left + w, top + h);
    }

    @Override
    protected void onDraw(Canvas c) {
        layoutSeg();

        // 行底
        bg.setColor(Theme.DLG_ROW_BG);
        rect.set(0, 0, getWidth(), getHeight());
        path.reset();
        path.addRoundRect(rect, Theme.px(4), Theme.px(4), android.graphics.Path.Direction.CW);
        c.drawPath(path, bg);

        // 左侧文本区
        t.setColor(Theme.TEXT);
        t.setTextSize(titleSize);
        t.setFakeBoldText(true);
        float ty = desc.isEmpty()
                ? getHeight() / 2f - (t.descent() + t.ascent()) / 2f
                : Theme.px(34);
        c.drawText(title, padH, ty, t);

        if (!desc.isEmpty()) {
            t.setColor(0xFF93939A);
            t.setTextSize(descSize);
            t.setFakeBoldText(false);
            c.drawText(desc, padH, Theme.px(62), t);
        }

        // 把分段控件画进来（它已经 layout 好了）
        c.save();
        c.translate(seg.getLeft(), seg.getTop());
        seg.draw(c);
        c.restore();

        // 行焦点白框
        if (rowFocused) {
            stroke.setColor(0xFFFFFFFF);
            rect.set(Theme.px(1), Theme.px(1), getWidth() - Theme.px(1), getHeight() - Theme.px(1));
            path.reset();
            path.addRoundRect(rect, Theme.px(4), Theme.px(4), android.graphics.Path.Direction.CW);
            c.drawPath(path, stroke);
        }
    }
}
