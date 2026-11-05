package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.mintv.tv.ui.Theme;

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

    /** 点右侧某个选项时的回调。 */
    public interface OnOptionClick {
        void onOption(SettingRowView row, int optionIndex);
    }

    private OnOptionClick onOptionClick;

    /**
     * 动作行：选项是「上一条 / 下一条」这种一次性动作，没有"当前值"。
     * 这种行不该常驻绿底勾 —— 否则用户永远看到 ◀ 被选中，以为选错了。
     */
    private boolean actionRow = false;

    public void setActionRow(boolean v) {
        this.actionRow = v;
        if (v) setSelected(-1);
    }

    public void setOnOptionClick(OnOptionClick l) { this.onOptionClick = l; }

    /**
     * 分段控件是手动 layout 的，没有 addView 进视图树，
     * 所以它自己永远收不到触摸事件。命中测试只能在这里做：
     * 触摸点落在 seg 的矩形里就转成 seg 的局部坐标去问 indexAt()。
     */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (seg == null || ev.getActionMasked() != android.view.MotionEvent.ACTION_UP) {
            return true;   // 按下/移动吃掉，抬手再判定，避免滑动误触
        }
        float x = ev.getX() - seg.getLeft();
        float y = ev.getY() - seg.getTop();
        if (x >= 0 && x <= seg.getWidth() && y >= 0 && y <= seg.getHeight()) {
            int idx = seg.indexAt(x);
            if (idx >= 0) {
                if (!actionRow) setSelected(idx);   // 动作行不留选中态
                if (onOptionClick != null) onOptionClick.onOption(this, idx);
                return true;
            }
        }
        // 没点到选项 → 当作"选中这一行"
        performClick();
        return true;
    }

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
        // 基线用行高的比例算，不再写死 px(34)/px(62)。
        // 写死的问题：行高一改文字就被裁，而且高低不同的行基线会跳。
        if (desc.isEmpty()) {
            t.setColor(Theme.TEXT);
            t.setTextSize(titleSize);
            c.drawText(title, padH,
                    getHeight() / 2f - (t.descent() + t.ascent()) / 2f, t);
        } else {
            float titleY = getHeight() * 0.36f;
            t.setColor(Theme.TEXT);
            t.setTextSize(titleSize);
            c.drawText(title, padH, titleY, t);

            t.setColor(0xFF93939A);
            t.setTextSize(descSize);
            t.setFakeBoldText(false);
            // desc 是单行绘制，不换行也不自动截断 —— 内容一长就画到卡片外面去。
            // 按可用宽度截断，超出部分用省略号。可用宽度保守取卡片的一半，
            // 右边留给选项控件。
            float avail = getWidth() * 0.52f - padH;
            if (avail > 0) {
                int fit = t.breakText(desc, true, avail, null);
                if (fit < desc.length()) {
                    String cut = desc.substring(0, Math.max(0, fit - 1)) + "…";
                    c.drawText(cut, padH, getHeight() * 0.72f, t);
                } else {
                    c.drawText(desc, padH, getHeight() * 0.72f, t);
                }
            } else {
                c.drawText(desc, padH, getHeight() * 0.72f, t);
            }
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
