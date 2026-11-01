package com.mintv.tv.ui.widget;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.DecelerateInterpolator;

import com.mintv.tv.ui.Theme;

/**
 * 焦点光晕卡片（早期版本，保留作参考）。
 *
 * <p>真正在用的基类是 {@link BaseCardView}，它把焦点动画收敛到一个 ValueAnimator，
 * 整个界面上只会有一个动画在跑。这个类演示的是「多层渐变描边模拟发光」的画法，
 * 想给卡片加外发光时把 {@link #onDraw} 里那段 glow 循环抄过去即可。
 *
 * <p>注意：真实项目里不要给每个卡片都叠三层光晕描边，低端 GPU 单卡三描边会掉帧。
 */
public class FocusCardView extends android.view.View {

    private static final int COL_FOCUS = 0xFFFFFFFF;
    private static final int COL_NORMAL = 0x1AFFFFFF;
    private static final int COL_GLOW = Theme.FOCUS_BLUE;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF roundRect = new RectF();

    private float radius = Theme.px(12);
    private float glowSize = Theme.px(24);
    private float focusProgress = 0f;

    private final DecelerateInterpolator interp = new DecelerateInterpolator(2f);

    public FocusCardView(android.content.Context c) {
        super(c);
        setWillNotDraw(false);
        setFocusable(false);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Theme.px(2));
        glowPaint.setStyle(Paint.Style.STROKE);
    }

    public void setFocused(boolean focused) {
        focusProgress = focused ? 1f : 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        roundRect.set(0, 0, getWidth(), getHeight());

        // 背景：斜向渐变
        bgPaint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                0x1AFFFFFF, Theme.FOCUS_BG, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(roundRect, radius, radius, bgPaint);

        // 描边：颜色随焦点插值
        borderPaint.setColor(blend(COL_NORMAL, COL_FOCUS, focusProgress));
        canvas.drawRoundRect(roundRect, radius, radius, borderPaint);

        // 外发光：三层，逐层加宽减淡（低端机建议只保留一层）
        if (focusProgress > 0f) {
            for (int i = 0; i < 3; i++) {
                float sw = Theme.px(2 + i * 4) * focusProgress;
                glowPaint.setStrokeWidth(sw);
                glowPaint.setColor(Theme.alpha(COL_GLOW, (0.45f - i * 0.13f) * focusProgress));
                canvas.drawRoundRect(roundRect, radius, radius, glowPaint);
            }
        }
    }

    private static int blend(int from, int to, float t) {
        float e = t;
        int a = (int) (((to >>> 24) - (from >>> 24)) * e + (from >>> 24));
        int r = (int) ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * e + ((from >> 16) & 0xFF));
        int g = (int) ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * e + ((from >> 8) & 0xFF));
        int b = (int) (((to & 0xFF) - (from & 0xFF)) * e + (from & 0xFF));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
