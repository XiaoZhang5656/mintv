package com.mintv.tv.ui.player;

/**
 * 内置播放内核。
 *
 * <p>三家各有所长，谁也不能通吃，所以全内置、可切换：
 * <ul>
 *   <li>{@link #EXO} —— Media3/ExoPlayer。HLS 最稳、起播最快，日常默认；</li>
 *   <!-- IJK 已移除，原因见下 -->
 *   <li>{@link #VLC} —— libVLC，全格式覆盖最广，网络层重连最激进，
 *       前面两个都黑屏时的最后一张牌。</li>
 * </ul>
 *
 * <p>「有声音没画面」的自愈链路就是按这个顺序降级，而不是瞎试线路。
 *
 * <p><b>为什么没有 IJK</b>：bilibili 的 ijkplayer 0.8.8（2017 年的最后一版）
 * 预编译 .so 在 Android 13+ 上会原生崩溃 —— 实测 tombstones：
 * {@code libijkffmpeg.so av_application_on_http_event+24}，
 * fault addr 是字符串 "packet-" 的 ASCII 值，即 native 侧把选项字符串
 * 当函数指针调用（ABI/结构体布局错位）。这属于 .so 内部问题，
 * Java 层无论怎么调参数都改不掉，只能整库下线。
 * 它原本负责的 AVS2/AVS3/MP2 这类冷门编码，libVLC 覆盖得更全，功能不缺。
 */
public enum PlaybackEngine {
    EXO("ExoPlayer"),
    VLC("VLC");

    public final String label;

    PlaybackEngine(String label) { this.label = label; }

    public static PlaybackEngine of(int index) {
        PlaybackEngine[] v = values();
        return (index >= 0 && index < v.length) ? v[index] : EXO;
    }

    /** 下一张牌。已经到 VLC 就返回自己。 */
    public PlaybackEngine next() {
        switch (this) {
            case EXO: return VLC;
            default: return VLC;
        }
    }
}
