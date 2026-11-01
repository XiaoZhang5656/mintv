package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.mintv.tv.ui.Theme;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 右上角时钟。两行右对齐，带黑色阴影 —— TV 上背景画面可能是纯白（新闻演播室），
 * 没有阴影这行字就直接没了。
 */
public class ClockView extends android.view.View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint p2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat fDate = new SimpleDateFormat("yyyy年M月d日", Locale.CHINA);
    private final SimpleDateFormat fWeek = new SimpleDateFormat("EEEE HH:mm", Locale.CHINA);

    private final float shadow = Theme.px(3);

    public ClockView(Context c) {
        super(c);
        setWillNotDraw(false);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextAlign(Paint.Align.RIGHT);
        p.setTextSize(Theme.sp(Theme.TS_CLOCK));
        p.setColor(Theme.TEXT);
        p.setShadowLayer(shadow, 0, Theme.px(1), 0xCC000000);

        p2.setTypeface(Typeface.DEFAULT);
        p2.setTextAlign(Paint.Align.RIGHT);
        p2.setTextSize(Theme.sp(Theme.TS_CLOCK_SUB));
        p2.setColor(0xFFCFCFCF);
        p2.setShadowLayer(shadow, 0, Theme.px(1), 0xCC000000);
    }

    /** 每分钟由 Activity 调一次，不自己起 Timer。 */
    public void refresh() { invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        Date now = new Date();
        float right = getWidth();
        c.drawText(fDate.format(now), right, Theme.px(22), p);
        c.drawText(fWeek.format(now), right, Theme.px(50), p2);
    }
}
