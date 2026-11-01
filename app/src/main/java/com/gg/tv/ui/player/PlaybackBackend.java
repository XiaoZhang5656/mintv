package com.gg.tv.ui.player;

import android.content.Context;
import android.view.Surface;

public interface PlaybackBackend {

    enum State { IDLE, BUFFERING, READY, RECONNECTING, ERROR }

    interface Callback {
        void onState(State state);
        void onError(String msg);
        void onFirstFrame();
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
