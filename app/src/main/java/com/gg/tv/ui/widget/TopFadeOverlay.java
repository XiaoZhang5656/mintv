package com.gg.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import com.gg.tv.ui.Theme;

/**
 * 顶部渐变遮罩（早期版本，保留作参考）。
 *
 * <p>实际界面里用的是 {@link ScrimView}（横向、1180px、四段色标），
 * 这个是纵向版本，给「列表向上滚出屏幕时在顶部淡出」用的。
 */
public class TopFadeOverlay extends android.view.View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float heightPx;

    public TopFadeOverlay(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        heightPx = Theme.px(80);
    }

    public void setFadeHeight(float px) {
        heightPx = px;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() == 0) return;
        rect.set(0, 0, getWidth(), heightPx);
        paint.setShader(new LinearGradient(
                0, 0, 0, heightPx,
                Theme.BG,            // 不透明，与背景同色
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect, paint);
    }
}
