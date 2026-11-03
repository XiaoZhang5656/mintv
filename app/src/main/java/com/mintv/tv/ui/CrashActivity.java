package com.mintv.tv.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 崩溃日志页 —— 独立进程。
 *
 * <p>为什么必须独立进程（用户提的这条是对的）：
 * 主进程 {@code uncaughtException} 之后，主线程 looper 已经要停了，
 * 在这个进程里再弹任何 View 都是赌运气 —— 布局消息可能永远排不到。
 * 起一个 {@code :crash} 进程就完全绕开这个问题：崩溃信息通过 Intent 传过去，
 * 那边是干净可用的 looper，面板一定能画出来，也一定点得到「复制」。
 *
 * <p>另外它天然避开了「崩溃发生在启动路径上、主界面根本建不起来」的情况。
 */
public class CrashActivity extends Activity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_DETAIL = "detail";

    /** 从任意进程/线程拉起，不会抛。 */
    public static void launch(Context ctx, String title, String detail) {
        try {
            Intent i = new Intent();
            i.setClassName(ctx, "com.mintv.tv.ui.CrashActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            i.putExtra(EXTRA_TITLE, title);
            i.putExtra(EXTRA_DETAIL, detail);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String detail = getIntent().getStringExtra(EXTRA_DETAIL);
        if (title == null) title = "出错了";
        if (detail == null) detail = "";

        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101114);
        root.setPadding((int) (28 * d), (int) (24 * d), (int) (28 * d), (int) (20 * d));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFFF6B6B);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);

        TextView sub = new TextView(this);
        sub.setText("完整堆栈如下，可复制后发给我定位");
        sub.setTextColor(0xFF8A8A90);
        sub.setTextSize(13);
        sub.setPadding(0, (int) (6 * d), 0, (int) (12 * d));
        root.addView(sub);

        final TextView bodyView = new TextView(this);
        bodyView.setText(detail);
        bodyView.setTextColor(0xFFD6D6DA);
        bodyView.setTextSize(12);
        bodyView.setTypeface(Typeface.MONOSPACE);
        bodyView.setTextIsSelectable(true);
        bodyView.setPadding((int) (10 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF17181B);
        sc.addView(bodyView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.bottomMargin = (int) (16 * d);
        root.addView(sc, slp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);

        // 三个按钮，**默认焦点在「重新进入」**：
        //   重新进入 —— 最常见诉求就是"赶紧回去继续看"，而且它做的是
        //               真正的重启（重置运行状态），不会卡在同一个坏状态里；
        //   复制     —— 需要上报时才用；
        //   退出     —— 兜底。
        // 原来第一个是"继续使用"，但它只是再起一个 Activity，
        // 崩溃现场的状态还留在内存里，经常一点进去就又崩 —— 那才是"卡死"的来源。
        btns.addView(mkBtn("重新进入", 0xFF2F7BFF, new View.OnClickListener() {
            @Override public void onClick(View v) {
                restartApp();
            }
        }));

        btns.addView(mkBtn("复制", 0xFF3A3B3E, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("mintv-crash", bodyView.getText()));
                Toast.makeText(CrashActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            }
        }));

        btns.addView(mkBtn("退出", 0xFF5A2E2E, new View.OnClickListener() {
            @Override public void onClick(View v) {
                finishAffinity();
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }));

        root.addView(btns);
        setContentView(root);
        if (btns.getChildCount() > 0) {
            // 默认焦点给「重新进入」
            final View first = btns.getChildAt(0);
            first.post(new Runnable() {
                @Override public void run() { first.requestFocus(); }
            });
        }
    }

    /**
     * 重新进入 —— 真正的重启，不是"再开一个界面"。
     *
     * <p>差别在哪：崩溃后主进程里残留着坏掉的状态（播放器句柄、Surface 绑定、
     * 半初始化的引擎）。只 startActivity 的话，新界面会接着用这些残留，
     * 大概率立刻二次崩溃 —— 用户看到的就是"点了没反应 / 一直卡住"。
     *
     * <p>这里做两件事：
     * <ol>
     *   <li>写一个 {@code restart_clean} 标记，主界面启动时读到它会
     *       跳过「恢复上次频道」、强制回到 Exo 内核、展开面板 ——
     *       也就是从最保守的安全状态开始；</li>
     *   <li>用 CLEAR_TASK 把旧任务栈整个清掉再拉起，避免旧 Activity 实例被复用。</li>
     * </ol>
     */
    private void restartApp() {
        try {
            getSharedPreferences("tvui", MODE_PRIVATE).edit()
                    .putBoolean("restart_clean", true).apply();
        } catch (Throwable ignored) { }

        try {
            Intent i = new Intent();
            i.setClassName(this, "com.mintv.tv.ui.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        } catch (Throwable ignored) { }
        finish();
    }

    private Button mkBtn(String text, int bg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(bg);
        b.setOnClickListener(l);
        b.setFocusable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = (int) (10 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(p);
        return b;
    }
}
