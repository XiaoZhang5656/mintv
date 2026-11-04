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
    /** 实时倍率（Δ播放时间/Δ墙钟），<0 表示还没数据。 */
    private float realtimeSpeed = -1f;

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
    /** 播放控制条命中区与状态。 */
    private final android.graphics.RectF ctlRect = new android.graphics.RectF();
    private boolean ctlShown;
    private boolean paused;
    /** 控制条按下中：保证整段手势都被消费，不漏给下层。 */
    private boolean ctlDown;
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
    /** 播放控制回调。 */
    public interface CtlListener {
        void onPrevChannel();
        void onNextChannel();
        void onTogglePause();
    }
    private CtlListener ctlListener;
    public void setCtlListener(CtlListener l) { ctlListener = l; }
    /** 供外部同步暂停图标。 */
    public void setPaused(boolean p) { paused = p; invalidate(); }

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

    /**
     * 一次性提示。
     *
     * <p>现在统一走 {@link com.mintv.tv.ui.widget.NotifyBar} —— 顶部居中、
     * 四种语义、宽度随内容。以前在 OSD 自己画，位置在底部，会和进度条撞；
     * 而且 OSD 淡出时它会跟着消失，用户经常没看见。
     */
    public void toast(String text) {
        if (text == null || text.isEmpty()) return;
        com.mintv.tv.ui.widget.NotifyBar.info(text);
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

    /**
     * 暂停时 OSD 常驻。
     *
     * <p>理由：暂停意味着用户要"操作"，这时候界面自己消失等于把人甩在
     * 一个黑着的画面上。主流播放器（YouTube / Netflix / 机顶盒）都是
     * 暂停时常显控件，只有播放态才自动隐藏。
     */
    private void kick() {
        if (paused) {
            removeCallbacks(fadeTask);
            osdAlpha = 1f;
            osdUntil = Long.MAX_VALUE;
            invalidate();
            return;
        }
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
        // OSD 已经淡出时，barShown / barRect 还是上一次绘制留下的旧值。
        // 不判可见性的话，点在"已经看不见的进度条"位置上会被这里吃掉，
        // 导致点视频区收不起面板 —— 表现就是"要点一下别的才恢复"。
        if (osdAlpha <= 0f) return false;
        float x = ev.getX(), y = ev.getY();

        // ---- 播放控制条（上一台 / 暂停 / 下一台）----
        //
        // ⚠ 必须整段手势都吃掉（DOWN/MOVE/UP 全部 return true）。
        // 以前只在 ACTION_DOWN 分支里处理并 return，ACTION_UP 会落到
        // 下面的进度条逻辑、最终 return false —— 事件穿透到铺满全屏的
        // videoTap，触发"点视频区"，把 OSD 收掉。
        // 表现就是"点暂停，按钮没反应反而整个 OSD 消失"。
        if (ctlShown && ctlRect.contains(x, y)) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    ctlDown = true;
                    // 按**实际按钮矩形**判断，不是把整段三等分。
                    // 间距 30px 之后三等分会导致：点在按钮之间的空隙
                    // 也会命中相邻按钮（例如点暂停右边 8px 的空白触发暂停）。
                    float bw = Theme.px(96), gapB = Theme.px(30);
                    int idx = -1;
                    for (int i = 0; i < 3; i++) {
                        float l = ctlRect.left + i * (bw + gapB);
                        if (x >= l && x <= l + bw) { idx = i; break; }
                    }
                    if (idx >= 0 && ctlListener != null) {
                        if (idx == 0) ctlListener.onPrevChannel();
                        // 不在 UI 里猜状态：交给 Activity 按后端真实状态决定，
                        // 然后回调 setPaused() 同步图标。
                        else if (idx == 1) ctlListener.onTogglePause();
                        else ctlListener.onNextChannel();
                    }
                    holdOsd();
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    ctlDown = false;
                    return true;
                default:
                    // MOVE 等：落在按钮上就继续吃掉
                    if (ctlDown) return true;
                    break;
            }
        }

        if (!barShown || seekListener == null) return false;
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

    /**
     * 画播放器通用图标。
     *
     * <p>0 = 上一集（◀◀ 双三角 + 竖线），1 = 暂停/播放，2 = 下一集。
     * 全部用 Path 画，不依赖字体里的三角符号 —— 不同设备上
     * "▶" 这类字符宽度和基线与中文差别很大，对不齐。
     */
    private void drawPlayIcon(Canvas c, int which, float cx, float cy, int color) {
        float s = Theme.px(10);          // 三角形半高
        float w = Theme.px(9);           // 三角形底宽
        android.graphics.Path path = new android.graphics.Path();
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);

        if (which == 1) {
            if (paused) {
                // 播放：右向三角（尖朝右）
                path.reset();
                path.moveTo(cx - w * 0.45f, cy - s);
                path.lineTo(cx + w * 0.75f, cy);
                path.lineTo(cx - w * 0.45f, cy + s);
                path.close();
                c.drawPath(path, fill);
            } else {
                // 暂停：两条圆角竖条
                float bw2 = Theme.px(6), bh2 = s * 1.9f;
                r.set(cx - Theme.px(9), cy - bh2 / 2f, cx - Theme.px(9) + bw2, cy + bh2 / 2f);
                c.drawRoundRect(r, Theme.px(2), Theme.px(2), fill);
                r.set(cx + Theme.px(3), cy - bh2 / 2f, cx + Theme.px(3) + bw2, cy + bh2 / 2f);
                c.drawRoundRect(r, Theme.px(2), Theme.px(2), fill);
            }
            return;
        }

        // 上一集 / 下一集：两个同向三角 + 外侧一条竖线（业界通行画法）
        // which=0 朝左，which=1... 实际上是 0=prev 朝左、2=next 朝右
        boolean left = (which == 0);
        float dir = left ? -1f : 1f;
        float step = w + Theme.px(3);
        // 两个三角：靠内的一枚 + 靠外的一枚
        for (int k = 0; k < 2; k++) {
            float base = cx - dir * Theme.px(7) + dir * (k * step);
            path.reset();
            // 三角尖朝 dir 方向
            path.moveTo(base - dir * w * 0.5f, cy - s);
            path.lineTo(base + dir * w * 0.5f, cy);
            path.lineTo(base - dir * w * 0.5f, cy + s);
            path.close();
            c.drawPath(path, fill);
        }
        // 外侧竖线（表示"到顶/到头"）
        float barX = cx + dir * Theme.px(14);
        r.set(left ? barX : barX - Theme.px(3), cy - s, left ? barX + Theme.px(3) : barX, cy + s);
        c.drawRoundRect(r, Theme.px(1.5f), Theme.px(1.5f), fill);
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

    /**
     * 参考稿 1:1 实现（osd-ref.html）。
     *
     * 设计要点：
     *  · 卡片 900×自适应，左边 4px 蓝条，圆角 12
     *  · 五段式：台号台名分类 / 正在播 / 进度 / 控制 / 技术标签
     *  · 控制条居中，中间那颗蓝色实心（暂停是最高频操作）
     *  · 直播时进度条画虚线纹理 + 独立「直播」标签，不写进进度里
     *  · 全部分段顺序排版，不再有"钉死在底部"的行（会撞）
     */
    private void drawOsd(Canvas c, float h) {
        final float padH = Theme.px(24);
        final float cardW = Theme.px(1040);
        final float LEFT_BAR = Theme.px(4);

        // ---- 字号 ----
        final float fsName = Theme.sp(29);
        final float fsNo   = Theme.sp(19);
        final float fsCat  = Theme.sp(15);
        final float fsLabel= Theme.sp(16);
        final float fsProg = Theme.sp(18);
        final float fsChip = Theme.sp(15);

        // ---- 间距 ----
        final float gap1 = Theme.px(14);   // 标题行后
        final float gap2 = Theme.px(12);   // 节目行后
        final float gap3 = Theme.px(16);   // 进度后
        final float gap4 = Theme.px(16);   // 控制条后
        final float barH = Theme.px(6);
        final float chipH = Theme.px(30);
        final float noH = Theme.px(30);
        final float ctlH = Theme.px(44);
        final float padV = Theme.px(20);

        // ---- 高度：顺序累加，每一段都算进去 ----
        float contentH = padV
                + noH + gap1                 // 第一行
                + (nowText != null ? fsProg + gap2 : 0)
                + barH + gap3                // 进度条（常驻）
                + (nextText != null ? fsProg + gap2 : 0)   // 接下来（进度条下方）
                + ctlH + gap4                // 控制条（常驻）
                + chipH + padV;              // 技术标签
        float bottom = h - Theme.px(56);
        float top = bottom - contentH;

        // ---- 卡片底 ----
        float avail = getWidth() - leftInset;
        float left = leftInset + Math.max(0f, (avail - cardW) / 2f);
        r.set(left, top, left + cardW, bottom);
        fill.setColor(Theme.OSD_CARD_BG);
        c.drawRoundRect(r, Theme.px(12), Theme.px(12), fill);

        // 左侧蓝条（圆角内缩，避免超出圆角）
        fill.setColor(Theme.FOCUS_BLUE);
        r.set(left, top + Theme.px(12), left + LEFT_BAR, bottom - Theme.px(12));
        c.drawRoundRect(r, LEFT_BAR / 2f, LEFT_BAR / 2f, fill);

        float x = left + padH;
        float y = top + padV;                 // 当前段的**顶部**

        // ═══════════ 第 1 行：台号 + 台名 + 分类 ═══════════
        float noTop = y;
        float noW = Theme.px(66);
        fill.setColor(Theme.FOCUS_BLUE);
        r.set(x, noTop, x + noW, noTop + noH);
        c.drawRoundRect(r, Theme.px(5), Theme.px(5), fill);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(fsNo);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(no == null ? "" : no, x + noW / 2f,
                noTop + noH / 2f - (p.ascent() + p.descent()) / 2f, p);
        p.setTextAlign(Paint.Align.LEFT);
        p.setFakeBoldText(false);

        float nameX = x + noW + Theme.px(16);
        p.setColor(Theme.TEXT);
        p.setTextSize(fsName);
        float nameBaseline = noTop + noH / 2f - (p.ascent() + p.descent()) / 2f;

        // 分类胶囊（先量宽度，给台名留出空间）
        float catW = 0f;
        if (group != null && !group.isEmpty()) {
            p.setTextSize(fsCat);
            catW = p.measureText(group) + Theme.px(22);
        }
        float nameMaxW = cardW - padH * 2 - noW - Theme.px(16)
                - (catW > 0 ? catW + Theme.px(12) : 0);
        p.setTextSize(fsName);
        c.drawText(fit(name == null ? "" : name, nameMaxW, fsName),
                nameX, nameBaseline, p);

        if (catW > 0) {
            float catX = left + cardW - padH - catW;
            fill.setColor(Theme.OSD_CAT_BG);
            r.set(catX, noTop + Theme.px(3), catX + catW, noTop + noH - Theme.px(3));
            c.drawRoundRect(r, Theme.px(5), Theme.px(5), fill);
            p.setColor(Theme.OSD_CAT_TXT);
            p.setTextSize(fsCat);
            c.drawText(group, catX + Theme.px(11),
                    noTop + noH / 2f - (p.ascent() + p.descent()) / 2f, p);
        }
        y += noH + gap1;

        // ═══════════ 第 2 行：正在播 ═══════════
        if (nowText != null) {
            p.setTextSize(fsLabel);
            p.setColor(Theme.TEXT_SUB);
            c.drawText("正在播", x, y + fsProg * 0.8f, p);
            float labelW = p.measureText("正在播");
            p.setTextSize(fsProg);
            p.setColor(Theme.OSD_PROG_TXT);
            c.drawText(fit(nowText, cardW - padH * 2 - labelW - Theme.px(12), fsProg),
                    x + labelW + Theme.px(12), y + fsProg * 0.8f, p);
            y += fsProg + gap2;
        }

        // ═══════════ 第 3 行：进度条 ═══════════
        // 直播没有时间轴：画虚线纹理槽 + 右侧独立「直播」标签。
        // 不把"直播"写进进度百分比的位置 —— 那样会让人以为有进度可拖。
        {
            float liveTagW = Theme.px(56);
            float barW = cardW - padH * 2 - liveTagW - Theme.px(14);
            barRect.set(x, y - Theme.px(12), x + barW, y + barH + Theme.px(12));
            barShown = true;

            // 槽
            fill.setColor(Theme.OSD_BAR_TRACK);
            r.set(x, y, x + barW, y + barH);
            c.drawRoundRect(r, barH / 2f, barH / 2f, fill);

            if (progress >= 0f) {
                // 回看：蓝色实心
                fill.setColor(Theme.FOCUS_BLUE);
                r.set(x, y, x + barW * Math.min(1f, progress), y + barH);
                c.drawRoundRect(r, barH / 2f, barH / 2f, fill);
                p.setColor(Theme.TEXT_SUB);
                p.setTextSize(fsChip);
                String pct = Math.round(progress * 100) + "%";
                c.drawText(pct, x + barW + Theme.px(14),
                        y + barH / 2f - (p.ascent() + p.descent()) / 2f, p);
            } else {
                // 直播：虚线纹理（用短条交替铺，模拟 repeating-linear-gradient）
                fill.setColor(Theme.OSD_BAR_DASH);
                float seg = Theme.px(12), gapSeg = Theme.px(12);
                float px0 = x;
                while (px0 < x + barW) {
                    float px1 = Math.min(px0 + seg, x + barW);
                    r.set(px0, y, px1, y + barH);
                    c.drawRoundRect(r, barH / 2f, barH / 2f, fill);
                    px0 += seg + gapSeg;
                }
                // 「直播」标签
                float ltX = x + barW + Theme.px(14);
                fill.setColor(Theme.OSD_LIVE_BG);
                r.set(ltX, y - Theme.px(8), ltX + liveTagW, y + barH + Theme.px(8));
                c.drawRoundRect(r, Theme.px(4), Theme.px(4), fill);
                p.setColor(Theme.OSD_LIVE_TXT);
                p.setTextSize(Theme.sp(15));
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("直播", ltX + liveTagW / 2f,
                        y + barH / 2f - (p.ascent() + p.descent()) / 2f, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            y += barH + gap3;
        }

        // ═══════════ 第 3.5 行：接下来 ═══════════
        //
        // 版式按「正在播 → 进度条 → 接下来」：
        //   正在播  21:50 大先生
        //   ━━━━━━━━━━━━━━━━━━ 63%
        //   接下来  22:40 新闻联播
        //
        // 原来 nextText 一直在 setProgram() 里传进来，但 draw 里只画了
        // nowText，这条数据等于白传 —— 用户看不到下一个节目是什么。
        if (nextText != null) {
            p.setTextSize(fsLabel);
            p.setColor(Theme.TEXT_SUB);
            c.drawText("接下来", x, y + fsProg * 0.8f, p);
            float labelW = p.measureText("接下来");
            p.setTextSize(fsProg);
            // 比"正在播"暗一档：它是未来内容，不该抢当前节目的视觉权重
            p.setColor((Theme.OSD_PROG_TXT & 0x00FFFFFF) | 0xB3000000);  // 同色 70% 不透明，弱一档
            c.drawText(fit(nextText, cardW - padH * 2 - labelW - Theme.px(12), fsProg),
                    x + labelW + Theme.px(12), y + fsProg * 0.8f, p);
            y += fsProg + gap2;
        }

        // ═══════════ 第 4 行：播放控制（居中）═══════════
        {
            // 键间距 30：暂停是主操作，两侧留白把它和切台键分开。
            // 原来 10 三个键糊成一条，遥控器按左右也不容易判断焦点在哪。
            float bw = Theme.px(96), gapB = Theme.px(30);
            float totalW = bw * 3 + gapB * 2;
            float bx = left + (cardW - totalW) / 2f;
            for (int i = 0; i < 3; i++) {
                float l = bx + i * (bw + gapB);
                boolean primary = (i == 1);
                r.set(l, y, l + bw, y + ctlH);
                fill.setColor(primary ? Theme.FOCUS_BLUE : Theme.OSD_CTL_BG);
                c.drawRoundRect(r, ctlH / 2f, ctlH / 2f, fill);
                drawPlayIcon(c, i, l + bw / 2f, y + ctlH / 2f,
                        primary ? 0xFFFFFFFF : 0xFFE6E9EE);
            }
            ctlRect.set(bx, y - Theme.px(8), bx + totalW, y + ctlH + Theme.px(8));
            ctlShown = true;
            y += ctlH + gap4;
        }

        // ═══════════ 第 5 行：技术标签 + 时钟 ═══════════
        {
            float chipTop = y;
            float cx = x;
            for (int i = 0; i < tags.length; i++) {
                if (!tagOn[i] || tags[i] == null || tags[i].isEmpty()) continue;
                p.setTextSize(fsChip);
                float tw = p.measureText(tags[i]) + Theme.px(20);
                if (cx + tw > left + cardW - padH - Theme.px(90)) break;   // 给时钟留位
                fill.setColor(chipColor(i));
                r.set(cx, chipTop, cx + tw, chipTop + chipH);
                c.drawRoundRect(r, Theme.px(5), Theme.px(5), fill);
                p.setColor(i == 0 ? Theme.OSD_CHIP_SRC_TXT : Theme.OSD_CHIP_TXT);
                c.drawText(tags[i], cx + Theme.px(10),
                        chipTop + chipH / 2f - (p.ascent() + p.descent()) / 2f, p);
                cx += tw + Theme.px(8);
            }
            // 实时倍率：只在不正常时显眼，正常时低调
            if (realtimeSpeed >= 0f && !paused) {
                String sp = String.format(java.util.Locale.US, "%.2fx", realtimeSpeed);
                p.setTextSize(fsChip);
                float sw = p.measureText(sp);
                float sx = left + cardW - padH - Theme.px(78) - sw;
                int col;
                if (realtimeSpeed >= 0.95f)      col = 0xFF7ED9A8;   // 跟得上
                else if (realtimeSpeed >= 0.90f) col = 0xFFE0A863;   // 略滞后
                else                             col = 0xFFE0605E;   // 明显落后
                p.setColor(col);
                c.drawText(sp, sx,
                        chipTop + chipH / 2f - (p.ascent() + p.descent()) / 2f, p);
            }
            // 时钟右下
            if (clockText != null) {
                p.setColor(Theme.OSD_CLOCK_TXT);
                p.setTextSize(fsChip);
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(clockText, left + cardW - padH,
                        chipTop + chipH / 2f - (p.ascent() + p.descent()) / 2f, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            y += chipH;
        }
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

    /** 实时倍率。传负数表示暂无数据（不显示）。 */
    public void setRealtimeSpeed(float x) {
        if (Math.abs(x - realtimeSpeed) < 0.01f) return;   // 变化太小不重绘
        realtimeSpeed = x;
        invalidate();
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
