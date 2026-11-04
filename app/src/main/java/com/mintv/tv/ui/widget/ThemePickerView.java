package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.mintv.tv.ui.Theme;

/**
 * 主题颜色选择器。
 *
 * <p>整屏覆盖层：先画一层半透明黑压暗底下，再在中间画色板卡片。
 *
 * <p>为什么不用 AlertDialog：它排不了色块网格，而且焦点逻辑在电视上不可控
 * （方向键会被 Dialog 自己的按钮抢走）。自绘之后焦点完全按我们的来。
 *
 * <p>交互：方向键移动、OK 选中并关闭、BACK 取消、点卡片外也可以取消。
 */
public final class ThemePickerView extends View {

    public interface Listener {
        /** 用户确认选了某个颜色。 */
        void onPicked(int index);
        /** 用户取消。 */
        void onCancelled();
    }

    private static final int COLS = 5;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF card = new RectF();

    private int focusIdx;
    private int selectedIdx;
    private Listener listener;

    private Runnable dismissAction;

    public ThemePickerView(Context c) {
        super(c);
        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        fill.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.DEFAULT);
        selectedIdx = Theme.accentIndex;
        focusIdx = selectedIdx;
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 关闭时要做什么（由宿主决定是 removeView 还是别的）。 */
    public void setDismissAction(Runnable a) { this.dismissAction = a; }

    // ================= 键盘 =================

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        int n = Theme.ACCENT_COLORS.length;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                focusIdx = (focusIdx - 1 + n) % n;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                focusIdx = (focusIdx + 1) % n;
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 只有两行，上下直接对跳；越界就停在原地
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focusIdx + COLS < n) {
                    focusIdx += COLS;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && focusIdx - COLS >= 0) {
                    focusIdx -= COLS;
                }
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                pick(focusIdx);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                cancel();
                return true;
            default:
                return super.onKeyDown(keyCode, e);
        }
    }

    private void pick(int idx) {
        selectedIdx = idx;
        if (listener != null) listener.onPicked(idx);
        close();
    }

    private void cancel() {
        if (listener != null) listener.onCancelled();
        close();
    }

    private void close() {
        if (dismissAction != null) {
            Runnable a = dismissAction;
            dismissAction = null;
            a.run();
        }
    }

    // ================= 触摸 =================

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float x = e.getX(), y = e.getY();
        // 点卡片外 = 取消
        if (!card.contains(x, y)) { cancel(); return true; }
        // 命中色块就直接选
        int idx = hitCell(x, y);
        if (idx >= 0) { focusIdx = idx; invalidate(); pick(idx); }
        return true;
    }

    private final RectF cellRect = new RectF();

    private int hitCell(float x, float y) {
        for (int i = 0; i < Theme.ACCENT_COLORS.length; i++) {
            cellBounds(i, cellRect);
            if (cellRect.contains(x, y)) return i;
        }
        return -1;
    }

    // ================= 绘制 =================

    private float cellW, cellH, gap, gridTop;

    private void cellBounds(int i, RectF out) {
        int row = i / COLS, col = i % COLS;
        float l = card.left + Theme.px(38) + col * (cellW + gap);
        float t = gridTop + row * (cellH + gap);
        out.set(l, t, l + cellW, t + cellH);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();

        // 遮罩
        c.drawColor(0xA6000000);

        // 卡片
        float cardW = Math.min(Theme.px(840), w - Theme.px(80));
        float cardH = Theme.px(430);
        card.left = (w - cardW) / 2f;
        card.top = (h - cardH) / 2f;
        card.right = card.left + cardW;
        card.bottom = card.top + cardH;
        fill.setColor(0xFF151A22);
        c.drawRoundRect(card, Theme.px(18), Theme.px(18), fill);

        float padX = Theme.px(38);
        float y = card.top + Theme.px(38);

        // 标题
        p.setColor(0xFFF2F5FA);
        p.setTextSize(Theme.sp(25));
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("主题颜色", card.left + padX, y, p);
        y += Theme.px(34);

        // 副标题
        p.setFakeBoldText(false);
        p.setColor(0xFF6E7583);
        p.setTextSize(Theme.sp(15));
        c.drawText("只改强调色，文字与背景不变 · 当前：" + Theme.ACCENT_NAMES[selectedIdx],
                card.left + padX, y, p);
        y += Theme.px(30);

        // 网格
        gap = Theme.px(18);
        cellW = (cardW - padX * 2 - gap * (COLS - 1)) / COLS;
        cellH = Theme.px(100);
        gridTop = y;

        for (int i = 0; i < Theme.ACCENT_COLORS.length; i++) {
            cellBounds(i, r);
            fill.setColor(Theme.ACCENT_COLORS[i]);
            c.drawRoundRect(r, Theme.px(12), Theme.px(12), fill);

            // 当前生效：白色内描边 + 右上角勾
            if (i == selectedIdx) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Theme.px(3));
                p.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, Theme.px(12), Theme.px(12), p);
                p.setStyle(Paint.Style.FILL);
                drawCheck(c, r.right - Theme.px(19), r.top + Theme.px(19));
            }
            // 焦点：更宽的外发光
            if (i == focusIdx) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Theme.px(4));
                p.setColor(0xFFFFFFFF);
                RectF o = new RectF(r);
                o.inset(-Theme.px(5), -Theme.px(5));
                c.drawRoundRect(o, Theme.px(16), Theme.px(16), p);
                p.setStyle(Paint.Style.FILL);
            }

            // 名字
            p.setColor(0xF2FFFFFF);
            p.setTextSize(Theme.sp(14));
            p.setFakeBoldText(true);
            p.setTextAlign(Paint.Align.CENTER);
            p.setShadowLayer(Theme.px(3), 0, 1, 0x80000000);
            c.drawText(Theme.ACCENT_NAMES[i], r.centerX(), r.bottom - Theme.px(11), p);
            p.setShadowLayer(0, 0, 0, 0);
        }

        // 底部提示
        float by = card.bottom - Theme.px(26);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(0xFF5F6673);
        p.setTextSize(Theme.sp(14));
        p.setFakeBoldText(false);
        c.drawText("←→↑↓ 移动    OK 选中并关闭    ESC 取消", card.left + padX, by, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("勾 = 当前生效", card.right - padX, by, p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    /** 右上角对勾。 */
    private void drawCheck(Canvas c, float cx, float cy) {
        fill.setColor(0xF2FFFFFF);
        c.drawCircle(cx, cy, Theme.px(11), fill);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Theme.px(2.4f));
        p.setColor(0xFF151A22);
        p.setStrokeCap(Paint.Cap.ROUND);
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(cx - Theme.px(5), cy);
        path.lineTo(cx - Theme.px(1.5f), cy + Theme.px(3.5f));
        path.lineTo(cx + Theme.px(5), cy - Theme.px(3.5f));
        c.drawPath(path, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
    }
}
