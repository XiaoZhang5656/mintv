package com.mintv.tv.ui.player;

import android.net.TrafficStats;

/**
 * 网速统计。
 *
 * <p>为什么用 {@link TrafficStats} 按 UID 统计，而不是问播放内核要：
 * <ul>
 *   <li>Exo 可以从 DataSource 层累加，但 IJK / VLC 的网络栈在 native 里，
 *       Java 侧拿不到；</li>
 *   <li>IJK 的 {@code _getPropertyLong(FFP_PROP_INT64_TCP_SPEED)} 是 private native，
 *       要么反射要么改库；</li>
 *   <li>VLC 的 {@code getStats().inputBitrate} 只有拿到 media 才有，且
 *       {@code Media.getStats()} 的语义是"本次 media 的统计"，切台后要重新取。</li>
 * </ul>
 *
 * <p>按 UID 统计三个内核一视同仁，代价是这个数字包含进程的**全部**流量
 * （台标、EPG 拉取也算在内）。对"这条线路稳不稳、码率多少"这个用途来说够用，
 * 而且它反映的正是用户实际在用的带宽 —— 反而比单看媒体流更真实。
 *
 * <p>用滑动窗口算瞬时速率，避免累计值除以总时长得到一个几乎不动的大数。
 */
public final class SpeedMeter {

    private static final long WINDOW_MS = 2000;

    private long lastBytes;
    private long lastTime;
    private long speedBps;
    private String cachedText = "";

    /** 每次时间轴回调（500ms 一次）调一下。 */
    public void sample() {
        long now = android.os.SystemClock.uptimeMillis();
        long bytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        if (bytes == TrafficStats.UNSUPPORTED) return;

        if (lastTime == 0) {
            lastBytes = bytes;
            lastTime = now;
            return;
        }
        long dt = now - lastTime;
        if (dt < 300) return;                 // 太短会抖，等够再算

        long delta = bytes - lastBytes;
        // 换台/断流时累计值可能回绕，负数是脏数据，丢掉
        if (delta < 0) {
            lastBytes = bytes;
            lastTime = now;
            return;
        }
        // 滑动平均，别让单次采样决定显示值
        long inst = delta * 1000 / dt;
        speedBps = speedBps == 0 ? inst : (speedBps * 2 + inst) / 3;

        lastBytes = bytes;
        lastTime = now;
    }

    /** 复位，切台时调用，避免把上一个频道的速率带过来。 */
    public void reset() {
        lastBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        lastTime = 0;
        speedBps = 0;
        cachedText = "";
    }

    /** 形如 "1.2 MB/s"。没数据返回空串。 */
    public String text() {
        long b = speedBps;
        if (b <= 0) return "";
        if (b >= 1024 * 1024) {
            cachedText = String.format(java.util.Locale.US, "%.1f MB/s", b / 1048576f);
        } else if (b >= 1024) {
            cachedText = String.format(java.util.Locale.US, "%.0f KB/s", b / 1024f);
        } else {
            cachedText = String.format(java.util.Locale.US, "%d B/s", b);
        }
        return cachedText;
    }

    public long bps() { return speedBps; }
}
