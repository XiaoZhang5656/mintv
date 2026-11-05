package com.tvbus.engine;

import android.content.Context;
import android.util.Log;

/**
 * TVBus 引擎封装。
 *
 * <p>协议本身不公开，靠 libtvcore.so 实现：它在本机起一个 HTTP 服务，
 * 把 {@code tvbus://xxx} 流转成普通 http 流，播放器再去拉
 * {@code http://127.0.0.1:port/xxx}。所以我们不需要碰解码，
 * 只要把地址换掉就行。
 *
 * <p><b>包名和类名必须是 com.tvbus.engine.TVCore</b> ——
 * so 里的 JNI 符号是 {@code Java_com_tvbus_engine_TVCore_xxx}，
 * 改一个字母就找不到。
 *
 * <p><b>只有 armeabi-v7a。</b> arm64 进程里加载这个 so 会失败，
 * 所以整个 App 的 ABI 被固定成 32 位（见 app/build.gradle）。
 *
 * <p>实现参考了猫影 smali 里的调用顺序，逻辑等价：
 * start(url) → 等 listener 回调写入 c → 返回 c。
 */
public final class TVCore {

    private static final String TAG = "TVCore";
    private static final String LIB = "tvcore";

    /** 单例。 */
    private static TVCore b;
    /** native 回调里写入的播放地址，playTVBUSUrl 轮询它。 */
    private static String c;
    private static boolean d;
    /** native handle，-1 表示没初始化。 */
    private static long e = -1;
    private static long f = -1;

    private boolean a;
    private d listener;

    private TVCore() { }

    /** 等播放地址的超时。由宿主按用户设置（超时换源）传入。 */
    private static volatile int sTimeoutMs = 8000;

    /** 设置超时毫秒数。宿主在每次解析前调用。 */
    public static void setTimeoutMs(int ms) {
        sTimeoutMs = ms > 0 ? ms : 8000;
    }

    public static synchronized TVCore get() {
        if (b == null) b = new TVCore();
        return b;
    }

    static {
        try {
            System.loadLibrary(LIB);
        } catch (Throwable t) {
            Log.w(TAG, "lib" + LIB + ".so 加载失败（设备可能是 arm64-only）", t);
        }
    }

    // ---------- native ----------

    private native long initialise();
    private native int  init(long h, Context ctx);
    private native int run(long h);
    private native void quit(long h);
    private native void setPlayPort(long h, int port);
    private native void setServPort(long h, int port);
    private native void start(long h, String url);
    private native void start2(long h, String url, String extra);
    private static native void stop(long h);
    private native void setListener(long h, d listener);

    // ---------- 对外 ----------

    /** 初始化 native 引擎并挂上回调。可以重复调用，内部只做一次。 */
    public synchronized void init(Context ctx) {
        if (e != -1) return;
        e = initialise();
        if (e == -1) {
            Log.w(TAG, "initialise() 返回 -1，引擎不可用");
            return;
        }
        if (listener != null) setListener(e, listener);
        try {
            // 必须是 applicationContext：TVCore 是全局单例，
            // 持有 Activity 会泄漏整个界面，而且 Activity 销毁后
            // native 回调还会打到已死的对象上。
            Context app = ctx.getApplicationContext();
            init(e, app != null ? app : ctx);
        } catch (Throwable t) {
            Log.w(TAG, "init 失败", t);
        }
        a = true;
    }

    public void setListener(d l) {
        listener = l;
        if (e != -1) setListener(e, l);
    }

    public void setPlayPort(int port) {
        if (e != -1) setPlayPort(e, port);
    }

    public void setServPort(int port) {
        if (e != -1) setServPort(e, port);
    }

    public void start(String url) {
        if (e != -1) start(e, url);
    }

    public void stop() {
        if (e != -1) stop(e);
    }

    public void quit() {
        if (e != -1) {
            try { quit(e); } catch (Throwable ignored) { }
            e = -1;
            a = false;
        }
    }

    public int p2p_run() {
        return e != -1 ? run(e) : -1;
    }

    /**
     * 把 {@code tvbus://xxx} 转成本机可播的 http 地址。
     *
     * <p>阻塞调用：起服务要等 native 回调，实测一般 0.3~3 秒，
     * 最坏 30 秒（超时就返回 null）。<b>不要在主线程调。</b>
     *
     * @return 形如 {@code http://127.0.0.1:port/xxx}，失败返回 null
     */
    public String playTVBUSUrl(String tvbusUrl) {
        c = null;
        // 换台时先把上一路停掉。
        // 不停的话 native 侧的 socket、解码缓冲、UPnP 端口映射都会累积，
        // 连着换十几个台之后内存和端口都会出问题。
        stop();
        try {
            start(tvbusUrl);
        } catch (Throwable t) {
            Log.w(TAG, "start 失败: " + tvbusUrl, t);
            return null;
        }
        // 超时由宿主传入（用户设置里的"超时换源"）。
        // 原来写死 30 秒 —— 用户设了 8 秒却要干等 30 秒，逻辑上说不通。
        int loops = Math.max(10, sTimeoutMs / 100);
        for (int i = 0; i < loops; i++) {
            String v = c;
            if (v != null) return v;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Log.w(TAG, "等播放地址超时: " + tvbusUrl);
        stop();
        return null;
    }

    /** 带额外参数的版本，部分源要在第二个参数里带 token。 */
    public String playUrl(String url, String extra) {
        c = null;
        try {
            if (extra == null) {
                if (e != -1) start(e, url);
            } else {
                if (e != -1) start2(e, url, extra);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start2 失败", t);
            return null;
        }
        for (int i = 0; i < Math.max(10, sTimeoutMs / 100); i++) {
            String v = c;
            if (v != null) return v;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stop();
        return null;
    }

    public static void stopPlay() {
        if (e != -1) {
            try { stop(e); } catch (Throwable ignored) { }
        }
    }

    public static synchronized boolean close() {
        if (b == null) return true;
        b.quit();
        b = null;
        return true;
    }

    public boolean isonInited() { return a; }

    // ---------- 给 native 回调用的入口 ----------
    //
    // native 层会通过 listener 回调，但也要能直接写 c。
    // 这几个包内可见的方法照 smali 里的行为保留。

    static void setPlayUrl(String url) { c = url; }
    static String getPlayUrl() { return c; }
}
