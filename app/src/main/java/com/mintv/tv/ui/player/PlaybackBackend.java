package com.mintv.tv.ui.player;

import android.content.Context;
import android.view.Surface;

public interface PlaybackBackend {

    enum State { IDLE, BUFFERING, READY, RECONNECTING, ERROR }

    interface Callback {
        void onState(State state);
        void onError(String msg);
        void onFirstFrame();

        /** 解码器输出的视频格式，拿不到就各传 null。 */
        void onVideoFormat(String resolution, String videoCodec, String audioCodec,
                           String channels, String fps);
    }

    void setCallback(Callback cb);

    void setSurface(Surface surface);

    /** 切台入口。实现里必须先 stop 再 prepare，不能 seek。 */
    void play(Context appCtx, String url, boolean live);

    void stop();

    void release();

    /** 当前缓冲位置，用于断流检测。 */
    long bufferedPositionMs();
}
