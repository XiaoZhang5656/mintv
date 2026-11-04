package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.mintv.tv.ui.Theme;

/** 分段选择器：一排胶囊选项，选中打勾、焦点白框。高度 50 设计稿像素。 */
public class SegmentedView extends android.view.View {

    public static final class Option {
        final String label;
        Option(String l) { label = l; }
    }

    private final Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF rect = new android.graphics.RectF();
    private final android.graphics.Path path = new android.graphics.Path();

    private String[] labels = new String[0];
    private int selected = 0;
    private int focusedOpt = -1;   // -1 表示整行是焦点、但选项未进入

    private final float h = Theme.px(50);
    private final float gap = Theme.px(12);
    private final float padX = Theme.px(20);
    private final float size = Theme.sp(20);

    /** 测量结果缓存，避免每帧算 */
    private final float[] widths = new float[16];

    public SegmentedView(Context c) {
        super(c);
        setWillNotDraw(false);
        t.setTypeface(Typeface.DEFAULT);
        bg.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Theme.px(2));
    }

    public void setLabels(String[] ls) {
        labels = ls == null ? new String[0] : ls;
        measureWidths();
        requestLayout();
        invalidate();
    }

    public void setSelected(int i) {
        selected = i;
        invalidate();
    }

    public void setFocusedOption(int i) {
        focusedOpt = i;
        invalidate();
    }

    public int indexAt(float x) {
        float cur = 0;
        for (int i = 0; i < labels.length; i++) {
            if (x >= cur && x < cur + widths[i]) return i;
            cur += widths[i] + gap;
        }
        return -1;
    }

    private void measureWidths() {
        if (widths.length < labels.length) {
            // 极少见，直接忽略（选项最多 5 个）
        }
        t.setTextSize(size);
        t.setFakeBoldText(false);
        for (int i = 0; i < labels.length && i < widths.length; i++) {
            widths[i] = Math.max(Theme.px(86), t.measureText(labels[i]) + padX * 2 + Theme.px(18));
        }
    }

    public float measuredWidth() {
        float w = 0;
        for (int i = 0; i < labels.length; i++) w += widths[i] + (i == 0 ? 0 : gap);
        return w;
    }

    /** 选中态底色：主题色 30% 透明，每次绘制现算。 */
    private static int selectionBg() {
        int c = Theme.FOCUS_BLUE;
        return (c & 0x00FFFFFF) | 0x4D000000;   // 0x4D ≈ 30%
    }

    @Override protected void onDraw(Canvas c) {
        float x = 0;
        float top = (getHeight() - h) / 2f;
        for (int i = 0; i < labels.length; i++) {
            boolean sel = i == selected;
            boolean foc = i == focusedOpt;

            // 实时从 Theme.FOCUS_BLUE 现算，不用 DLG_OPT_SEL。
            //
            // DLG_OPT_SEL 是静态字段，被 applyPalette / applyAccent
            // 两处赋值，谁后跑谁赢 —— 出过"主题色变了但按钮还是绿的"
            // 和"按钮完全没有底色"两次事故。这里改成每次绘制时算，
            // 只依赖 FOCUS_BLUE 一个源头，不再可能出现两边不同步。
            bg.setColor(sel ? selectionBg() : Theme.DLG_OPT_BG);
            rect.set(x, top, x + widths[i], top + h);
            path.reset();
            path.addRoundRect(rect, Theme.px(4), Theme.px(4), android.graphics.Path.Direction.CW);
            c.drawPath(path, bg);

            t.setTextSize(size);

            // 「✓ + 文字」整体在这个按钮里居中。
            //
            // 原来勾画在最左边、文字紧跟着往右推，但按钮宽度没变 ——
            // 结果选中项的文字被推到右半边，视觉上明显不在中间。
            // 现在先算出总宽，再从居中位置开始画。
            float checkGap = Theme.px(7);
            float checkW = sel ? t.measureText("✓") + checkGap : 0f;
            float contentW = checkW + t.measureText(labels[i]);
            float textX = x + Math.max(padX, (widths[i] - contentW) / 2f);
            float baseline = top + h / 2f - (t.descent() + t.ascent()) / 2f;

            if (sel) {
                t.setColor(Theme.FOCUS_BLUE);
                c.drawText("✓", textX, baseline, t);
                textX += checkW;
                t.setColor(0xFFFFFFFF);
            } else {
                t.setColor(0xFFE6E6E8);
            }
            c.drawText(labels[i], textX, baseline, t);

            if (foc) {
                stroke.setColor(0xFFFFFFFF);
                rect.set(x + Theme.px(1), top + Theme.px(1), x + widths[i] - Theme.px(1), top + h - Theme.px(1));
                path.reset();
                path.addRoundRect(rect, Theme.px(4), Theme.px(4), android.graphics.Path.Direction.CW);
                c.drawPath(path, stroke);
            }
            x += widths[i] + gap;
        }
    }
}
