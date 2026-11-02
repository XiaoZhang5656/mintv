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

        // 顺序按 TV 遥控器的使用频率排：先「继续用」，再「复制」，最后才是「退出」。
        // 原来只有复制/退出，等于强制用户看完日志就滚蛋 —— 大多数时候用户
        // 只是想赶紧回去看片，日志事后还能从 logcat 取。
        btns.addView(mkBtn("继续使用", 0xFF2F7BFF, new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 回到主界面。本页在 :crash 进程里，直接拉起主进程的 Activity，
                // 再把自己关掉，两个进程互不干扰。
                try {
                    Intent i = new Intent();
                    i.setClassName(CrashActivity.this,
                            "com.mintv.tv.ui.MainActivity");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(i);
                } catch (Throwable ignored) { }
                finish();
            }
        }));

        btns.addView(mkBtn("复制全部", 0xFF3A3B3E, new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("mintv-crash", bodyView.getText()));
                Toast.makeText(CrashActivity.this, "已复制，粘贴发给我", Toast.LENGTH_SHORT).show();
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
            final View first = btns.getChildAt(0);
            first.post(new Runnable() {
                @Override public void run() { first.requestFocus(); }
            });
        }
    }

    /** 返回键 = 继续使用，别让用户只能靠退出跑掉。 */
    @Override
    public void onBackPressed() {
        try {
            Intent i = new Intent();
            i.setClassName(this, "com.mintv.tv.ui.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
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
