package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.mintv.tv.ui.Theme;

/**
 * 统一通知条。
 *
 * <p>顶部居中，宽度随内容，四种语义（info / ok / warn / error）。
 * 全应用只有这一个通知入口 —— 以前散着 OSD 里的 toast、面板里的 toast，
 * 位置不一样、寿命不一样，用户看到的提示没有统一节奏。
 *
 * <p>为什么放顶部：底部要留给 OSD 信息条和进度条，两者会撞。而且电视上
 * 用户视线本来就在画面上部，顶部更容易被看到。
 */
public final class NotifyBar extends View {

    public enum Kind { INFO, OK, WARN, ERROR }

    private static final long SHOW_MS = 2600;      // 停留时长
    private static final long ANIM_MS = 180;       // 进出动画

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String text;
    private String sub;
    private Kind kind = Kind.INFO;
    private float alpha;

    private static NotifyBar INSTANCE;

    public NotifyBar(Context c) {
        super(c);
        setWillNotDraw(false);
        p.setTypeface(Typeface.DEFAULT);
        fill.setStyle(Paint.Style.FILL);
        alpha = 0f;
    }

    // ================= 全局入口 =================

    /**
     * 挂到 Activity 的根容器上。
     *
     * <p>每个 Activity 重建（换主题会 recreate）都要重新挂 —— 旧的跟着
     * 旧界面一起销毁了。
     */
    public static NotifyBar attach(FrameLayout root) {
        NotifyBar bar = new NotifyBar(root.getContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.px(140));
        lp.topMargin = Theme.px(64);
        lp.gravity = android.view.Gravity.TOP;
        root.addView(bar, lp);
        INSTANCE = bar;
        return bar;
    }

    public static void info(String msg)   { show(Kind.INFO, msg, null); }
    public static void ok(String msg)     { show(Kind.OK, msg, null); }
    public static void warn(String msg)   { show(Kind.WARN, msg, null); }
    public static void warn(String msg, String sub) { show(Kind.WARN, msg, sub); }
    public static void error(String msg)  { show(Kind.ERROR, msg, null); }
    public static void error(String msg, String sub) { show(Kind.ERROR, msg, sub); }

    public static void show(Kind k, String msg, String subtitle) {
        NotifyBar b = INSTANCE;
        if (b == null) return;
        b.showNow(k, msg, subtitle);
    }

    // ================= 显示逻辑 =================

    private final Runnable hideTask = new Runnable() {
        @Override public void run() { fade(false); }
    };

    private void showNow(Kind k, String msg, String subtitle) {
        // OSD 是后加的，会盖在通知条上面 —— 每次显示前把自己提到最上层
        bringToFront();
        this.kind = k;
        this.text = msg;
        this.sub = subtitle;
        ui.removeCallbacks(hideTask);
        fade(true);
        ui.postDelayed(hideTask, SHOW_MS);
        invalidate();
    }

    private void fade(final boolean in) {
        animate().cancel();
        animate().alpha(in ? 1f : 0f)
                .translationY(in ? 0 : -Theme.px(16))
                .setDuration(ANIM_MS)
                .start();
        if (in) invalidate();
    }

    // ================= 绘制 =================

    private float measureWidth() {
        float padH = Theme.px(26), iconW = Theme.px(26), gap = Theme.px(14);
        p.setTextSize(Theme.sp(21));
        float w1 = p.measureText(text == null ? "" : text);
        float w2 = 0;
        if (sub != null && !sub.isEmpty()) {
            p.setTextSize(Theme.sp(15));
            w2 = p.measureText(sub);
        }
        float inner = Math.max(w1, w2);
        float total = padH * 2 + iconW + gap + inner;
        float max = getWidth() * 0.62f;
        return Math.min(Math.max(total, Theme.px(200)), max);
    }

    private float measureHeight() {
        float padV = Theme.px(16);
        float h = Theme.sp(21) * 1.45f;
        if (sub != null && !sub.isEmpty()) h += Theme.sp(15) * 1.45f + Theme.px(4);
        return h + padV * 2;
    }

    @Override protected void onDraw(Canvas c) {
        if (text == null || text.isEmpty()) return;
        if (getWidth() == 0) return;

        float w = measureWidth();
        float h = measureHeight();
        float left = (getWidth() - w) / 2f;
        float top = Theme.px(10);
        r.set(left, top, left + w, top + h);

        // 卡片
        fill.setColor(0xF0161A20);
        c.drawRoundRect(r, Theme.px(14), Theme.px(14), fill);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Theme.px(1));
        p.setColor(0x1AFFFFFF);
        c.drawRoundRect(r, Theme.px(14), Theme.px(14), p);
        p.setStyle(Paint.Style.FILL);

        float padH = Theme.px(26);
        float iconCx = left + padH + Theme.px(13);
        float iconCy = top + h / 2f;

        // 图标圆底 + 符号
        int accent = accentOf(kind);
        fill.setColor((accent & 0x00FFFFFF) | 0x30000000);
        c.drawCircle(iconCx, iconCy, Theme.px(13), fill);
        p.setColor(accent);
        p.setTextSize(Theme.sp(15));
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        String sym = symbolOf(kind);
        c.drawText(sym, iconCx, iconCy - (p.ascent() + p.descent()) / 2f, p);
        p.setTypeface(Typeface.DEFAULT);

        // 文字
        float tx = left + padH + Theme.px(26) + Theme.px(14);
        float textMax = left + w - Theme.px(26) - tx;
        if (sub != null && !sub.isEmpty()) {
            float line1 = top + Theme.px(16) + Theme.sp(21);
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(0xFFEDF0F4);
            p.setTextSize(Theme.sp(21));
            c.drawText(ellipsize(text, textMax, Theme.sp(21)), tx, line1, p);
            p.setColor(0xFF8A939C);
            p.setTextSize(Theme.sp(15));
            c.drawText(ellipsize(sub, textMax, Theme.sp(15)),
                    tx, line1 + Theme.px(4) + Theme.sp(15) * 1.35f, p);
        } else {
            p.setTextAlign(Paint.Align.LEFT);
            p.setColor(0xFFEDF0F4);
            p.setTextSize(Theme.sp(21));
            c.drawText(ellipsize(text, textMax, Theme.sp(21)),
                    tx, top + h / 2f - (p.ascent() + p.descent()) / 2f, p);
        }
        p.setTextAlign(Paint.Align.LEFT);
    }

    private String ellipsize(String s, float max, float size) {
        if (s == null) return "";
        p.setTextSize(size);
        if (p.measureText(s) <= max) return s;
        String dots = "…";
        float dw = p.measureText(dots);
        int n = s.length();
        while (n > 0 && p.measureText(s.substring(0, n)) + dw > max) n--;
        return s.substring(0, n) + dots;
    }

    private static int accentOf(Kind k) {
        switch (k) {
            case OK:    return 0xFF5FD08A;
            case WARN:  return 0xFFE8A85C;
            case ERROR: return 0xFFF27D80;
            default:    return 0xFF7FB2FF;
        }
    }

    private static String symbolOf(Kind k) {
        switch (k) {
            case OK:    return "✓";
            case WARN:  return "!";
            case ERROR: return "×";
            default:    return "i";
        }
    }
}
