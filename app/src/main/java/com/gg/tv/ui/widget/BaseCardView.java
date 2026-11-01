package com.gg.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;

import com.gg.tv.ui.Theme;

/**
 * 自绘圆角卡片基类。
 *
 * 刻意不用 MaterialCardView：
 *   它每次 focus 变化会 setLayerType(SOFTWARE) 建一层离屏 buffer，
 *   低端盒子（S905/S912 一代）上焦点一动就是 20fps。
 * 这里纯粹在宿主 canvas 上画两个圆角矩形，零 layer、零分配。
 */
public abstract class BaseCardView extends android.view.View {

    protected final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    protected final RectF rect = new RectF();
    protected final Path  path = new Path();

    protected float radius = Theme.px(Theme.RADIUS_ITEM);
    protected float strokeW = Theme.px(Theme.FOCUS_STROKE);

    /** 0 = 常态，1 = 焦点；由 animateFocus 驱动 */
    protected float focusProgress = 0f;

    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);

    public BaseCardView(Context c) {
        super(c);
        setWillNotDraw(false);
        setFocusable(false);   // 焦点由 FocusEngine 统一管理，View 本身不抢
        setFocusableInTouchMode(false);
        setClickable(false);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(strokeW);

        animator.setDuration(Theme.T_FOCUS);
        animator.setInterpolator(new DecelerateInterpolator(2f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                focusProgress = (Float) a.getAnimatedValue();
                applyFocusScale();
                invalidate();
            }
        });
    }

    /** 焦点态由引擎推送，不做自己的 onFocusChange。 */
    public void setFocused(boolean focused, boolean animate) {
        float target = focused ? 1f : 0f;
        animator.cancel();
        if (!animate) {
            focusProgress = target;
            applyFocusScale();
            invalidate();
            return;
        }
        animator.setFloatValues(focusProgress, target);
        animator.start();
    }

    public boolean isFocusedCard() { return focusProgress > 0.5f; }

    protected void buildRoundRect() {
        rect.set(0, 0, getWidth(), getHeight());
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
    }

    protected void drawBg(Canvas c, int normalColor, int focusColor) {
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(focusProgress <= 0f ? normalColor : blend(normalColor, focusColor, focusProgress));
        c.drawPath(path, bgPaint);
    }

    protected void drawFocusStroke(Canvas c, int color) {
        if (focusProgress <= 0f) return;
        strokePaint.setColor(Theme.alpha(color, focusProgress));
        strokePaint.setStrokeWidth(strokeW * focusProgress);
        // 内缩半个描边宽，描边才不会被裁掉
        float inset = strokeW * focusProgress * 0.5f;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        c.drawPath(path, strokePaint);
        buildRoundRect();
    }

    protected static int blend(int from, int to, float t) {
        int a = (int) (((to >>> 24) - (from >>> 24)) * t + (from >>> 24));
        int r = (int) ((((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t + ((from >> 16) & 0xFF));
        int g = (int) ((((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t + ((from >> 8) & 0xFF));
        int b = (int) (((to & 0xFF) - (from & 0xFF)) * t + (from & 0xFF));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 焦点时的轻微放大。直接改 View 的 scale，走 RenderThread，不重排。 */
    protected void applyFocusScale() {
        float s = 1f + 0.015f * focusProgress;
        setScaleX(s);
        setScaleY(s);
    }
}
