package com.mintv.tv.ui.player;

/**
 * 内置播放内核。
 *
 * <p>三家各有所长，谁也不能通吃，所以全内置、可切换：
 * <ul>
 *   <li>{@link #EXO} —— Media3/ExoPlayer。HLS 最稳、起播最快，日常默认；</li>
 *   <li>{@link #IJK} —— FFmpeg 软解打底。AVS2/AVS3、MP2 音轨、奇怪封装的
 *       国内源，Exo 解不了它能解；</li>
 *   <li>{@link #VLC} —— libVLC，全格式覆盖最广，网络层重连最激进，
 *       前面两个都黑屏时的最后一张牌。</li>
 * </ul>
 *
 * <p>「有声音没画面」的自愈链路就是按这个顺序降级，而不是瞎试线路。
 */
public enum PlaybackEngine {
    EXO("ExoPlayer"),
    IJK("IJK"),
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
            case EXO: return IJK;
            case IJK: return VLC;
            default: return VLC;
        }
    }
}
