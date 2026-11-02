package com.mintv.tv.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 错误面板：任何异常（崩溃 / 播放失败 / 源解析失败）都弹这个框。
 *
 * <pre>
 * ┌──────────────────────────────────────┐
 * │ 出错了 · 播放失败                     │
 * │ ┌──────────────────────────────────┐ │
 * │ │ java.io.IOException: ...         │ │  ← 可滚动
 * │ │ at com.mintv...                  │ │
 * │ └──────────────────────────────────┘ │
 * │      [复制]   [关闭]   [退出]         │
 * └──────────────────────────────────────┘
 * </pre>
 *
 * 直接挂在 Activity 的 decorView 上，所以即使界面主流程抛异常也能显示。
 * 复制会把完整堆栈放进剪贴板，方便直接粘出来。
 */
public final class ErrorPanel {

    private final Activity activity;
    private View root;
    private TextView body;
    private TextView titleView;

    public ErrorPanel(Activity a) {
        this.activity = a;
    }

    public boolean isShowing() {
        return root != null && root.getVisibility() == View.VISIBLE;
    }

    /** 显示错误。title 例如「启动崩溃」「播放失败」。 */
    public void show(String title, String detail) {
        if (activity.isFinishing()) return;
        if (root == null) build();
        // 以前这里是 ((View) root.getTag()).findViewWithTag("title")：
        // 而 root.setTag() 存进去的是 String（title），强转 View 直接
        // ClassCastException —— 异常处理器自己再抛一次，面板永远弹不出来。
        // 直接持有 titleView 引用，别再玩 tag。
        if (titleView != null) titleView.setText(title);
        body.setText(detail == null ? "（无详细信息）" : detail);
        root.setVisibility(View.VISIBLE);
        root.bringToFront();
        activity.getWindow().getDecorView().post(new Runnable() {
            @Override public void run() {
                if (root != null) root.bringToFront();
            }
        });
    }

    public void hide() {
        if (root != null) root.setVisibility(View.GONE);
    }

    // ---------------- 构建界面 ----------------

    private void build() {
        float s = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (24 * s);

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setBackgroundColor(0xCC000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1B1C1F);
        card.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(activity);
        titleView = title;
        title.setText("出错了");
        title.setTextColor(0xFFFF6B6B);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, pad / 2);
        card.addView(title);

        TextView sub = new TextView(activity);
        sub.setText("完整信息如下，可复制后反馈");
        sub.setTextColor(0xFF8A8A90);
        sub.setTextSize(13);
        sub.setPadding(0, 0, 0, pad);
        card.addView(sub);

        // 日志区：等宽、可滚动、可选中
        body = new TextView(activity);
        body.setTextColor(0xFFD6D6DA);
        body.setTextSize(12);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);

        ScrollView logScroll = new ScrollView(activity);
        logScroll.setBackgroundColor(0xFF111214);
        logScroll.addView(body);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        logLp.bottomMargin = pad;
        card.addView(logScroll, logLp);

        // 按钮行
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);

        buttons.addView(makeButton("复制", 0xFF2F7BFF, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager)
                        activity.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("error", body.getText()));
                Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
            }
        }), buttonParams(pad));

        buttons.addView(makeButton("关闭", 0xFF3A3B3E, new View.OnClickListener() {
            @Override public void onClick(View v) { hide(); }
        }), buttonParams(pad));

        buttons.addView(makeButton("退出", 0xFF5A2E2E, new View.OnClickListener() {
            @Override public void onClick(View v) {
                activity.finishAffinity();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }), buttonParams(pad));

        card.addView(buttons);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.62f),
                (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.62f));
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(card, cardLp);

        ((FrameLayout) activity.getWindow().getDecorView()).addView(overlay,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        root = overlay;
        root.setVisibility(View.GONE);
    }

    private TextView makeButton(String text, int bg, View.OnClickListener l) {
        TextView b = new TextView(activity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(bg);
        b.setPadding(36, 18, 36, 18);
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams(int pad) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.rightMargin = pad / 2;
        return p;
    }

    // ---------------- 全局挂载 ----------------

    /**
     * 给一个 Activity 装上全局未捕获异常拦截：
     * 崩溃时把堆栈显示在面板上，而不是直接黑屏退出。
     */
    public static ErrorPanel install(final Activity activity) {
        final ErrorPanel panel = new ErrorPanel(activity);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, final Throwable ex) {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                final String detail = sw.toString();

                String title = "崩溃 · " + ex.getClass().getSimpleName();
                // 主进程里画面板是赌运气（looper 要停了），改由 :crash 独立进程接管。
                CrashActivity.launch(activity.getApplicationContext(), title, detail);
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    try {
                        panel.show(title, detail);
                    } catch (Throwable ignored) { }
                } else {
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            try { panel.show("崩溃 · " + ex.getClass().getSimpleName(), detail); }
                            catch (Throwable ignored) { }
                        }
                    });
                }
                try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
                // 不调用 previous：让面板停留在屏幕上，用户可复制后再点退出
            }
        });
        return panel;
    }
}
