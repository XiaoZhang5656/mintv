package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.mintv.tv.ui.Theme;

/**
 * 左侧导航的一个分类项：图标 + 名称 + 计数胶囊。
 *
 * 图标不引 vector 库，用一个 22x22 的「线框字形」代替（见 {@link #drawGlyph}），
 * 主线版本可以直接 setIcon(Bitmap) 换成真实台标/图标。
 */
public class NavItemView extends BaseCardView {

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String name = "";
    private int count = 0;
    private boolean active = false;
    private int glyph = 0;

    private final float padH = Theme.px(16);
    private final float iconSize = Theme.px(30);
    private final float textSize = Theme.sp(Theme.TS_NAV_ITEM);
    private final float pillTextSize = Theme.sp(15);

    public NavItemView(Context c) {
        super(c);
        radius = Theme.px(Theme.RADIUS_ITEM);
        textPaint.setTypeface(Typeface.DEFAULT);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(Theme.px(1.9f));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        pillPaint.setStyle(Paint.Style.FILL);
    }

    public void bind(String name, int count, int glyphId) {
        this.name = name == null ? "" : name;
        this.count = count;
        this.glyph = glyphId;
        invalidate();
    }

    public void setActive(boolean a) {
        if (active != a) {
            active = a;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        buildRoundRect();
        int base = active ? Theme.PANEL_ACTIVE : 0x00000000;
        drawBg(c, base, Theme.FOCUS_BG);

        float cy = getHeight() / 2f;
        float x = padH;

        // ---- 图标 ----
        int iconColor = focusProgress > 0.5f ? Theme.TEXT : (active ? Theme.TEXT : 0xFFDEDEE0);
        iconPaint.setColor(iconColor);
        drawGlyph(c, glyph, x, cy - iconSize / 2f, iconSize);

        // ---- 名称 ----
        textPaint.setColor(focusProgress > 0.5f || active ? Theme.TEXT : 0xFFDEDEE0);
        textPaint.setTextSize(textSize);
        textPaint.setFakeBoldText(active || focusProgress > 0.5f);
        float tx = x + iconSize + Theme.px(16);
        float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        c.drawText(name, tx, ty, textPaint);

        // ---- 计数胶囊 ----
        drawCountPill(c, cy);

        drawFocusStroke(c, Theme.FOCUS_BLUE);
    }

    private void drawCountPill(Canvas c, float cy) {
        pillPaint.setTextSize(pillTextSize);
        String s = String.valueOf(count);
        float tw = textMeasure(pillTextSize, s);
        float ph = Theme.px(26);
        float pw = Math.max(Theme.px(44), tw + Theme.px(20));
        float right = getWidth() - padH;
        float left = right - pw;
        float top = cy - ph / 2f;

        boolean foc = focusProgress > 0.5f;
        // 底
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(foc ? Theme.FOCUS_BLUE : Theme.GOLD);
        rect.set(left, top, right, top + ph);
        path.reset();
        path.addRoundRect(rect, ph / 2f, ph / 2f, android.graphics.Path.Direction.CW);
        c.drawPath(path, bgPaint);

        // 字
        textPaint.setColor(foc ? Theme.TEXT : Theme.BG);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(pillTextSize);
        float bty = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        c.drawText(s, left + (pw - tw) / 2f, bty, textPaint);
        textPaint.setFakeBoldText(false);
        buildRoundRect();
    }

    /** 测量用：复用 textPaint，避免额外分配 */
    private float textMeasure(float size, String s) {
        float old = textPaint.getTextSize();
        textPaint.setTextSize(size);
        textPaint.setFakeBoldText(true);
        float w = textPaint.measureText(s);
        textPaint.setTextSize(old);
        textPaint.setFakeBoldText(false);
        return w;
    }

    /** 极简线框图标库，索引对应内容类型。图省事不引 vector-drawable。 */
    private void drawGlyph(Canvas c, int id, float x, float y, float s) {
        float u = s / 24f;
        float cx = x + s / 2f, cy = y + s / 2f;
        switch (id % 8) {
            case 0: // 全部：叠层
                c.drawRect(x + 3 * u, y + 5 * u, x + 21 * u, y + 15 * u, iconPaint);
                c.drawLine(x + 3 * u, y + 19 * u, x + 21 * u, y + 19 * u, iconPaint);
                break;
            case 1: // 省内：定位
                c.drawCircle(cx, y + 10 * u, 4.2f * u, iconPaint);
                c.drawLine(cx, y + 14 * u, cx, y + 21 * u, iconPaint);
                break;
            case 2: // 电台：波纹
                c.drawCircle(cx, cy, 2.2f * u, iconPaint);
                c.drawArc(x + 4 * u, y + 4 * u, x + 20 * u, y + 20 * u, -50, 100, false, iconPaint);
                c.drawArc(x + 4 * u, y + 4 * u, x + 20 * u, y + 20 * u, 130, 100, false, iconPaint);
                break;
            case 3: // 央视：电视
                c.drawRect(x + 3 * u, y + 6 * u, x + 21 * u, y + 19 * u, iconPaint);
                c.drawLine(x + 8 * u, y + 2 * u, cx, y + 6 * u, iconPaint);
                c.drawLine(x + 16 * u, y + 2 * u, cx, y + 6 * u, iconPaint);
                break;
            case 4: // 卫视：地球
                c.drawCircle(cx, cy, 9 * u, iconPaint);
                c.drawLine(x + 3 * u, cy, x + 21 * u, cy, iconPaint);
                c.drawOval(x + 8 * u, y + 3 * u, x + 16 * u, y + 21 * u, iconPaint);
                break;
            case 5: // 4K / 新闻：文件
                c.drawRect(x + 5 * u, y + 3 * u, x + 19 * u, y + 21 * u, iconPaint);
                c.drawLine(x + 8 * u, y + 10 * u, x + 16 * u, y + 10 * u, iconPaint);
                c.drawLine(x + 8 * u, y + 14 * u, x + 16 * u, y + 14 * u, iconPaint);
                break;
            case 6: // 电影：胶片
                c.drawRect(x + 3 * u, y + 5 * u, x + 21 * u, y + 19 * u, iconPaint);
                c.drawLine(x + 8 * u, y + 5 * u, x + 8 * u, y + 19 * u, iconPaint);
                c.drawLine(x + 16 * u, y + 5 * u, x + 16 * u, y + 19 * u, iconPaint);
                break;
            default: // 体育/儿童/其它：圆
                c.drawCircle(cx, cy, 9 * u, iconPaint);
                c.drawArc(x + 7 * u, y + 7 * u, x + 17 * u, y + 17 * u, 200, 140, false, iconPaint);
                break;
        }
    }
}
