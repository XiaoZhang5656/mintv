package com.mintv.tv.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import com.mintv.tv.ui.player.PlaybackBackend;

/**
 * 换台 OSD —— 参考主流 TV 播放器的信息密度重做。
 *
 * <pre>
 * ┌──────────────────────────────────────────────────────┐
 * │ [0001]  CCTV-1 综合                        [央视]    │
 * │ 目前播放: 20:03 伟大的长征第38集                      │
 * │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░  77%                   │
 * │ 下个节目: 20:53 前情提要-伟大的长征-第39集            │
 * │ [源 3/5] [回看] [1280x720] [H264] [AAC] [立体声] [25fps]  20:42:15 │
 * └──────────────────────────────────────────────────────┘
 * </pre>
 *
 * 所有信息都来自播放器实时回调；拿不到的项目（EPG、分辨率）就把那一段隐藏，
 * 不画占位符也不编假数据。
 */
public class OsdView extends View {

    private static final long OSD_MS = 4000;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    // ---- 频道 / 节目 ----
    private String no, name, group;
    private String nowText;       // "20:03 伟大的长征第38集"
    private String nextText;      // "20:53 前情提要"
    private float progress = -1f; // 0~1，<0 不画进度条

    // ---- 技术信息小标签 ----
    private final String[] tags = new String[9];   // 源/回看/分辨率/视频编码/音频编码/声道/帧率/码率/网速
    private final boolean[] tagOn = new boolean[9];

    private String clockText;

    // ---- 音量 OSD ----
    private int volumePercent = -1;      // <0 表示不显示
    private boolean volumeMuted = false;
    private long volumeUntil;
    private final Runnable volumeFade = new Runnable() {
        @Override public void run() { volumePercent = -1; invalidate(); }
    };

    // ---- 换台时的大号台号 ----
    private String channelPulse;
    private final Runnable pulseFade = new Runnable() {
        @Override public void run() { channelPulse = null; invalidate(); }
    };

    /** 换台时在右上角闪一下台号，1.6 秒淡出。跟数字键输入同一位置，风格一致。 */
    public void pulseChannelNumber(String no) {
        if (no == null || no.isEmpty()) return;
        channelPulse = no;
        removeCallbacks(pulseFade);
        postDelayed(pulseFade, 1600);
        invalidate();
    }

    // ---- 数字键输入提示 ----
    private String numberInput;
    private final Runnable numberFade = new Runnable() {
        @Override public void run() { numberInput = null; invalidate(); }
    };

    /** 遥控器数字键输入时，屏幕中央显示已经按了哪几位。 */
    public void showNumberInput(String digits) {
        numberInput = digits;
        removeCallbacks(numberFade);
        postDelayed(numberFade, 1400);
        invalidate();
    }

    /** 显示音量条，2 秒后自动消失。 */
    public void showVolume(int percent, boolean muted) {
        volumePercent = Math.max(0, Math.min(100, percent));
        volumeMuted = muted;
        removeCallbacks(volumeFade);
        postDelayed(volumeFade, 2000);
        invalidate();
    }
    private String statusText;

    /** 进度条在 View 坐标系里的矩形，拖动命中测试用。 */
    private final android.graphics.RectF barRect = new android.graphics.RectF();
    private boolean barShown = false;
    /** 左侧被面板挡住时，卡片要往右让，否则会盖在频道列表上。 */
    private float leftInset = 0f;

    /**
     * 台号框的水平锚点（屏幕坐标）。
     *
     * <p>不能靠"时钟宽 360 + 右边距 36"去推算时钟中心 —— ClockView 的实际
     * 宽度、内边距和文字对齐方式都跟这个假设不一致，算出来会偏一百多像素。
     * 由 Activity 直接量出时钟在屏幕上的中心点传进来，才真的对得齐。
     */
    private float numberAnchorX = -1f;

    public void setNumberAnchorX(float x) { numberAnchorX = x; }
    private boolean dragging = false;

    /** 进度条拖动回调。由 Activity 实现（它才知道播放器能不能 seek）。 */
    public interface SeekListener {
        boolean canSeek();
        long durationMs();
        void onPreview(long positionMs);
        void onCommit(long positionMs);
        /** 不可 seek（直播流）。用来提示用户为什么拖不动。 */
        void onSeekBlocked();
    }

    private SeekListener seekListener;

    public void setSeekListener(SeekListener l) { this.seekListener = l; }

    /**
     * 设置左侧占位宽度（面板可见时等于三栏总宽）。
     * 卡片在剩余区域里居中，不会压到频道列表上。
     */
    public void setLeftInset(float px) {
        if (Math.abs(leftInset - px) < 1f) return;
        leftInset = px;
        invalidate();
    }

    private long osdUntil;
    private float osdAlpha;

    private final Runnable fadeTask = new Runnable() {
        @Override public void run() {
            osdAlpha = 0f;
            invalidate();
        }
    };

    public OsdView(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        p.setTypeface(Typeface.DEFAULT);
        fill.setStyle(Paint.Style.FILL);
    }

    // ================= 外部喂数据 =================

    public void showChannel(String no, String name, String group) {
        this.no = no;
        this.name = name;
        this.group = group;
        this.nowText = null;
        this.nextText = null;
        this.progress = -1f;
        for (int i = 0; i < tagOn.length; i++) tagOn[i] = false;
        kick();
    }

    /** EPG：目前播放 / 下个节目 / 进度。没有 EPG 就不调用。 */
    public void setProgram(String now, String next, float progress01) {
        nowText = now;
        nextText = next;
        progress = progress01;
        invalidate();
    }

    /** 技术标签。null 或空串表示该项不可用，直接不画。 */
    public void setTags(String source, String replay, String resolution,
                        String vCodec, String aCodec, String channels, String fps) {
        setTags(source, replay, resolution, vCodec, aCodec, channels, fps, null, null);
    }

    /** 带码率和网速的版本。null 表示该项不显示。 */
    public void setTags(String source, String replay, String resolution,
                        String vCodec, String aCodec, String channels, String fps,
                        String bitrate, String speed) {
        String[] v = {source, replay, resolution, vCodec, aCodec, channels, fps, bitrate, speed};
        for (int i = 0; i < tags.length; i++) {
            boolean on = v[i] != null && !v[i].isEmpty();
            tagOn[i] = on;
            if (on) tags[i] = v[i];
        }
        invalidate();
    }

    private String toastText;
    private final Runnable toastTask = new Runnable() {
        @Override public void run() {
            toastText = null;
            invalidate();
        }
    };

    /** 一次性提示，2 秒后自动消失，不会像状态文本那样一直挂着。 */
    public void toast(String text) {
        toastText = text;
        removeCallbacks(toastTask);
        postDelayed(toastTask, 2000);
        invalidate();
    }

    public void setStatus(String s) {
        if ((s == null && statusText == null) || (s != null && s.equals(statusText))) return;
        statusText = s;
        invalidate();
    }

    public void tick() { invalidate(); }

    /** 立刻隐藏，不等 4 秒淡出。点屏幕收起时用。 */
    public void hideNow() {
        removeCallbacks(fadeTask);
        osdAlpha = 0f;
        invalidate();
    }

    /**
     * 重新显示一遍当前信息条（不换台、不重置数据）。
     *
     * <p>OSD 只在换台时弹一次、4 秒就淡出，之后想看进度就没入口了。
     * 点一下画面能把它叫回来，是这个交互唯一合理的做法。
     *
     * @return 有没有可显示的内容；false 时调用方应该去做别的（比如展开面板）
     */
    public boolean reshow() {
        if (name == null) return false;
        kick();
        return true;
    }

    /** 供日志/自检用：当前 OSD 是否可见。 */
    public boolean isVisible() { return osdAlpha > 0f && name != null; }

    public String debugLine() {
        return "visible=" + isVisible() + " alpha=" + osdAlpha + " no=" + no
                + " name=" + name + " tagOn0=" + tagOn[0];
    }

    private void kick() {
        android.util.Log.d("TVUI", "kick name=" + name + " no=" + no);
        osdAlpha = 1f;
        osdUntil = SystemClock.uptimeMillis() + OSD_MS;
        removeCallbacks(fadeTask);
        postDelayed(fadeTask, OSD_MS);
        invalidate();
    }

    public static String stateText(PlaybackBackend.State st) {
        switch (st) {
            case BUFFERING: return "缓冲中…";
            case RECONNECTING: return "重连中…";
            case ERROR: return "源失效";
            default: return null;
        }
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();

        if (osdAlpha > 0f && name != null) {
            drawOsd(c, h);
        }

        // 一次性提示：居中偏上，带半透明圆角底。
        //
        // 以前它和「状态文字」「右下角网速」全挤在屏幕右下角同一片区域，
        // 同时出现时三层字叠在一起，谁都看不清（实测：切换线路 5/5 +
        // 源失效 + 4 KB/s 直接糊成一团）。挪到上方后三者互不干扰。
        if (toastText != null) {
            p.setTextSize(Theme.sp(20));
            p.setTextAlign(Paint.Align.LEFT);
            float tw = p.measureText(toastText);
            float padX = Theme.px(22), padY = Theme.px(14);
            float bx = (w - tw) / 2f - padX;
            float by = h * 0.16f - p.getTextSize() - padY;
            float bw = tw + padX * 2, bh = p.getTextSize() + padY * 2;
            fill.setColor(0xB3000000);
            r.set(bx, by, bx + bw, by + bh);
            c.drawRoundRect(r, Theme.px(8), Theme.px(8), fill);
            p.setColor(Theme.TEXT);
            p.setShadowLayer(Theme.px(3), 0, 0, 0xCC000000);
            c.drawText(toastText, bx + padX, by + padY + p.getTextSize() * 0.85f, p);
            p.setShadowLayer(0, 0, 0, 0);
        }

        // 数字键输入提示 —— 按机顶盒/IPTV 的惯例放**右上角**：
        // 白色大号数字、半透明圆角底、右对齐，一眼看清已经按了几位，
        // 又不会挡住画面主体和下方 OSD 卡片。位置跟时钟错开（时钟在 topMargin 18），
        // 所以这里从上方 130px 开始往下排。
        // 台号 / 数字输入：在**日期时间那块的正下方、水平居中**。
        //
        // 时钟控件的布局是 width=360、rightMargin=36、topMargin=18，
        // 所以它的水平中心固定在 w - 36 - 180。台号框跟着这个中心走，
        // 视觉上就是"挂在日期下面"，而不是贴在屏幕右边缘。
        String bigNumber = (numberInput != null && !numberInput.isEmpty())
                ? numberInput : channelPulse;
        if (bigNumber != null && !bigNumber.isEmpty()) {
            p.setTextSize(Theme.sp(46));
            p.setTextAlign(Paint.Align.LEFT);
            p.setFakeBoldText(true);
            float tw = p.measureText(bigNumber);

            // 优先用 Activity 量出来的时钟中心；拿不到再退回原来的估算公式
            float clockCenterX = numberAnchorX > 0 ? numberAnchorX
                    : (w - Theme.px(36) - Theme.px(180));
            float padX = Theme.px(22);
            float boxW = Math.max(Theme.px(120), tw + padX * 2);
            float boxH = Theme.px(64);
            float top = Theme.px(118);
            float left = clockCenterX - boxW / 2f;

            fill.setColor(0xB3000000);
            r.set(left, top, left + boxW, top + boxH);
            c.drawRoundRect(r, Theme.px(10), Theme.px(10), fill);

            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(Theme.px(6), 0, 0, 0xAA000000);
            float baseline = top + boxH / 2f - (p.descent() + p.ascent()) / 2f;
            c.drawText(bigNumber, clockCenterX - tw / 2f, baseline, p);
            p.setShadowLayer(0, 0, 0, 0);
            p.setFakeBoldText(false);
        }

        // 音量条：右下角、压在网速标签上方。
        // 放底部居中的话会和 OSD 卡片撞（卡片底部在 h-56，高度约 300），
        // 放到右下角既符合 TV 习惯，又和网速(底部30)、数字输入(右上)分开。
        if (volumePercent >= 0) {
            float bw = Theme.px(300), bh = Theme.px(8);
            float right = w - Theme.px(40);
            float bx = right - bw;
            float by = h - Theme.px(150);
            fill.setColor(0xCC000000);
            r.set(bx - Theme.px(64), by - Theme.px(40),
                    right + Theme.px(12), by + bh + Theme.px(18));
            c.drawRoundRect(r, Theme.px(10), Theme.px(10), fill);

            p.setColor(Theme.TEXT);
            p.setTextSize(Theme.sp(16));
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(volumeMuted ? "静音" : "音量", bx - Theme.px(54), by + Theme.px(3), p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(volumePercent + "%", right, by + Theme.px(3), p);
            p.setTextAlign(Paint.Align.LEFT);

            fill.setColor(0x33FFFFFF);
            r.set(bx, by + Theme.px(14), bx + bw, by + Theme.px(14) + bh);
            c.drawRoundRect(r, bh / 2f, bh / 2f, fill);
            fill.setColor(volumeMuted ? 0xFF6B6B6B : Theme.FOCUS_BLUE);
            r.set(bx, by + Theme.px(14), bx + bw * (volumePercent / 100f), by + Theme.px(14) + bh);
            c.drawRoundRect(r, bh / 2f, bh / 2f, fill);
        }

        if (statusText != null) {
            p.setColor(Theme.TEXT);
            p.setTextSize(Theme.sp(18));
            p.setTextAlign(Paint.Align.RIGHT);
            p.setShadowLayer(Theme.px(3), 0, 0, 0xCC000000);
            // 往上让出一行给右下角的网速标签，两者不再重叠
            c.drawText(statusText, w - Theme.px(40), h - Theme.px(96), p);
            p.setShadowLayer(0, 0, 0, 0);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    /**
     * 进度条拖动。
     *
     * <p>只有回看 / 时移能拖 —— 直播是没有可跳转时间轴的，seek 只能回到直播点，
     * 那就不是「拖动」了。所以先问 canSeek()，不能就当场说清楚，别让用户拖半天没反应。
     *
     * <p>返回 false 时事件会落到下面的 videoTap（点视频区收起面板），
     * 所以只有真正点在进度条上才消费。
     */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        if (!barShown || seekListener == null) return false;
        float x = ev.getX(), y = ev.getY();
        boolean hit = barRect.contains(x, y);

        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                if (!hit) return false;
                if (!seekListener.canSeek()) {
                    seekListener.onSeekBlocked();
                    return true;                 // 吃掉，别再触发收起面板
                }
                dragging = true;
                holdOsd();                       // 拖动期间不许淡出
                seekListener.onPreview(pctToMs(x));
                return true;

            case android.view.MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                seekListener.onPreview(pctToMs(x));
                return true;

            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    seekListener.onCommit(pctToMs(x));
                }
                kick();                          // 拖完恢复 4 秒自动隐藏
                return true;
            default:
                return false;
        }
    }

    private long pctToMs(float x) {
        float left = barRect.left, right = barRect.right;
        if (right <= left) return 0;
        float p = (x - left) / (right - left);
        if (p < 0f) p = 0f;
        if (p > 1f) p = 1f;
        long dur = seekListener.durationMs();
        if (dur <= 0) return 0;
        return (long) (p * dur);
    }

    /** 拖动期间保持可见。 */
    private void holdOsd() {
        removeCallbacks(fadeTask);
        osdAlpha = 1f;
        invalidate();
    }

    private void drawOsd(Canvas c, float h) {
        float padH = Theme.px(22);
        float cardW = Theme.px(1080);

        // 先量高度：标题行 + 目前 + 进度 + 下个 + 标签行
        float lineTitle = Theme.sp(28);
        float lineSub = Theme.sp(18);
        float barH = Theme.px(6);
        float chipH = Theme.px(26);
        float gap = Theme.px(10);

        float contentH = padH
                + lineTitle + gap
                + (nowText != null ? lineSub + gap : 0)
                + (progress >= 0 ? barH + gap : 0)
                + (nextText != null ? lineSub + gap : 0)
                + chipH + padH;
        float cardH = contentH;
        // 底部居中，但要让开左侧面板：剩下的那块区域里居中。
        float avail = getWidth() - leftInset;
        float left = leftInset + Math.max(0f, (avail - cardW) / 2f);
        float bottom = h - Theme.px(56);
        float top = bottom - cardH;

        // 卡片底
        r.set(left, top, left + cardW, bottom);
        fill.setColor(0xE0101216);
        c.drawRoundRect(r, Theme.px(8), Theme.px(8), fill);
        // 左侧蓝条
        fill.setColor(Theme.FOCUS_BLUE);
        r.set(left, top, left + Theme.px(3), bottom);
        c.drawRoundRect(r, Theme.px(1), Theme.px(1), fill);

        float x = left + padH;
        float y = top + padH + lineTitle * 0.78f;

        // ---- 第 1 行：台号块 + 台名 + 分类胶囊 ----
        float noW = Theme.px(66), noH = Theme.px(30);
        float noTop = y - Theme.sp(28) * 0.78f + Theme.px(2);
        fill.setColor(Theme.FOCUS_BLUE);
        r.set(x, noTop, x + noW, noTop + noH);
        c.drawRoundRect(r, Theme.px(3), Theme.px(3), fill);
        p.setColor(Theme.TEXT);
        p.setTextSize(Theme.sp(18));
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(no == null ? "" : no, x + noW / 2f, noTop + noH / 2f + Theme.px(6), p);
        p.setTextAlign(Paint.Align.LEFT);

        p.setColor(Theme.TEXT);
        p.setTextSize(lineTitle);
        p.setFakeBoldText(false);
        float nameX = x + noW + Theme.px(16);
        c.drawText(name, nameX, y, p);

        // 分类胶囊靠右
        if (group != null && !group.isEmpty()) {
            float cs = Theme.sp(16);
            p.setTextSize(cs);
            float cw = p.measureText(group) + Theme.px(18);
            float cx = left + cardW - padH - cw;
            fill.setColor(0xFF3A6EA5);
            r.set(cx, noTop, cx + cw, noTop + noH);
            c.drawRoundRect(r, Theme.px(3), Theme.px(3), fill);
            p.setColor(Theme.TEXT);
            c.drawText(group, cx + Theme.px(9), noTop + noH / 2f + Theme.px(5), p);
        }

        y += gap;

        // ---- 第 2 行：目前播放 ----
        float rowX = x;
        if (nowText != null) {
            y += lineSub * 0.8f;
            p.setColor(0xFF8E8E93);
            p.setTextSize(Theme.sp(17));
            c.drawText("目前播放:", rowX, y, p);
            float labelW = p.measureText("目前播放:");
            p.setColor(0xFFE6E6E8);
            p.setTextSize(lineSub);
            c.drawText(fit(nowText, cardW - padH * 2 - labelW - Theme.px(10), lineSub),
                    rowX + labelW + Theme.px(10), y, p);
            y += gap;
        }

        // ---- 第 3 行：进度条 ----
        barShown = false;
        if (progress >= 0f) {
            float fullW = cardW - padH * 2;
            float barW = fullW - Theme.px(60);
            float barTop = y;
            barRect.set(rowX, barTop - Theme.px(14), rowX + barW, barTop + barH + Theme.px(14));
            barShown = true;
            fill.setColor(0x33FFFFFF);
            r.set(rowX, barTop, rowX + barW, barTop + barH);
            c.drawRoundRect(r, barH / 2f, barH / 2f, fill);
            fill.setColor(0xFF2F7BFF);
            r.set(rowX, barTop, rowX + barW * Math.min(1f, progress), barTop + barH);
            c.drawRoundRect(r, barH / 2f, barH / 2f, fill);
            p.setColor(0xFFB9B9BD);
            p.setTextSize(Theme.sp(16));
            c.drawText(Math.round(progress * 100) + "%", rowX + barW + Theme.px(12),
                    barTop + barH + Theme.px(4), p);
            y += barH + gap;
        }

        // ---- 第 4 行：下个节目 ----
        if (nextText != null) {
            y += lineSub * 0.8f;
            p.setColor(0xFF8E8E93);
            p.setTextSize(Theme.sp(17));
            c.drawText("下个节目:", rowX, y, p);
            float labelW = p.measureText("下个节目:");
            p.setColor(0xFFC9C9CE);
            p.setTextSize(Theme.sp(17));
            c.drawText(fit(nextText, cardW - padH * 2 - labelW - Theme.px(10), Theme.sp(17)),
                    rowX + labelW + Theme.px(10), y, p);
            y += gap;
        }

        // ---- 第 5 行：技术标签 + 时钟 ----
        float chipTop = bottom - padH - chipH;
        float cx = rowX;
        for (int i = 0; i < tags.length; i++) {
            if (!tagOn[i]) continue;
            p.setTextSize(Theme.sp(15));
            float tw = p.measureText(tags[i]) + Theme.px(16);
            fill.setColor(chipColor(i));
            r.set(cx, chipTop, cx + tw, chipTop + chipH);
            c.drawRoundRect(r, Theme.px(3), Theme.px(3), fill);
            p.setColor(Theme.TEXT);
            c.drawText(tags[i], cx + Theme.px(8), chipTop + chipH / 2f + Theme.px(5), p);
            cx += tw + Theme.px(8);
            if (cx > left + cardW - Theme.px(90)) break;   // 放不下就截断
        }

        // 右下角时钟
        String t = clockText != null ? clockText : nowClock();
        p.setColor(0xFF9A9A9E);
        p.setTextSize(Theme.sp(16));
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(t, left + cardW - padH, chipTop + chipH / 2f + Theme.px(5), p);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private static int chipColor(int i) {
        switch (i) {
            case 0: return 0xFF1F5FA8;   // 源
            case 1: return 0xFF3A3B3E;   // 回看
            case 2: return 0xFF2B2C2E;   // 分辨率
            case 3: return 0xFF2E6B3E;   // H264
            case 4: return 0xFF7A4B1E;   // AAC
            case 5: return 0xFF5A2E6E;   // 声道
            default: return 0xFF2B2C2E;  // fps
        }
    }

    private String nowClock() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date());
    }

    public void setClock(String s) {
        clockText = s;
        invalidate();
    }

    private String fit(String s, float maxW, float size) {
        if (s == null) return "";
        p.setTextSize(size);
        if (p.measureText(s) <= maxW) return s;
        String ell = "…";
        float ellW = p.measureText(ell);
        int n = s.length();
        while (n > 0 && p.measureText(s.substring(0, n)) + ellW > maxW) n--;
        return s.substring(0, n) + ell;
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(fadeTask);
        super.onDetachedFromWindow();
    }
}
