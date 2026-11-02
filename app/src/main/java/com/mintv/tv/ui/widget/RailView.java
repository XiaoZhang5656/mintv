package com.mintv.tv.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.mintv.tv.ui.Theme;

/**
 * 频道列与播放区之间那条竖轨。
 *
 * <p>原来是把「节目 » 信息」逐字往下画，56px 宽的轨道里每个字占一行，
 * 结果加上空格和 » 一共要断成六七行，看着像一列散字。
 * 现在改成**整行旋转 -90°**（从下往上读），一条完整的文字，
 * 和两边面板的视觉重量也匹配。
 */
public class RailView extends android.view.View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String text = "节目 » 信息";

    public RailView(Context c) {
        super(c);
        setWillNotDraw(false);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(0xFFE8E8EA);
        p.setTextSize(Theme.sp(Theme.TS_RAIL));
        p.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        // 左侧 1px 分隔线
        p.setColor(Theme.LINE);
        c.drawRect(0, 0, Math.max(1, Theme.px(1)), getHeight(), p);

        p.setColor(0xFFE8E8EA);
        c.save();
        c.rotate(-90f, cx, cy);              // 逆时针 90°，文字从下往上读
        float base = cy - (p.descent() + p.ascent()) / 2f;
        c.drawText(text, cx, base, p);
        c.restore();
    }
}
