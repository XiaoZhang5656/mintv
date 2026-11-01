package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.mintv.tv.ui.Theme;

/**
 * 左侧压暗遮罩：横向渐变，从全黑 94% 淡出到透明。
 * 宽度 1180 设计稿像素，比三栏总宽 840 多出 340 —— 那 340 是列表边缘的淡出区，
 * 没有它，列表右侧就是一刀硬切的边。
 */
public class ScrimView extends android.view.View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.LinearGradient gradient;

    public ScrimView(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        gradient = new android.graphics.LinearGradient(
                0, 0, w, 0,
                Theme.SCRIM_COLORS, Theme.SCRIM_STOPS,
                android.graphics.Shader.TileMode.CLAMP);
        p.setShader(gradient);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), p);
    }
}
