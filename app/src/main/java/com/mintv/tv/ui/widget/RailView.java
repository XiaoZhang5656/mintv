package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.mintv.tv.ui.Theme;

/**
 * 频道列与播放区之间那条 64px 竖轨：「节目 » 信息」。
 *
 * 竖排不做逐字 TextView（一屏要 10 个 View，低端机上测量开销不小），
 * 直接逐字 drowText + 手动换行，一个 View 搞定，且完全可控字距。
 */
public class RailView extends android.view.View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String text = "节目 » 信息";
    private final float size = Theme.sp(Theme.TS_RAIL);
    private final float letterSpace = Theme.px(10);

    public RailView(Context c) {
        super(c);
        setWillNotDraw(false);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(0xFFE8E8EA);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float step = size + letterSpace;
        float total = text.length() * step;
        float y = (getHeight() - total) / 2f + size;

        // 左侧 1px 分隔线
        p.setColor(Theme.LINE);
        c.drawRect(0, 0, Theme.px(1), getHeight(), p);

        p.setColor(0xFFE8E8EA);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') { y += step; continue; }   // 空格处断行，跟设计稿一致
            c.drawText(String.valueOf(ch), cx, y, p);
            y += step;
        }
    }
}
