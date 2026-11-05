package com.forcetech.android;

/**
 * P2P 引擎封装（p2p / p3p ~ p9p）。
 *
 * <p>同一套接口，不同版本的 so 各自实现：{@link #start(String, int)}
 * 的第一个参数就是 so 名字（"p2p"、"p3p"…"p9p"），第二个是本地端口。
 * 引擎起一个 HTTP 服务，P2P 流从 {@code http://127.0.0.1:port/xxx} 出来，
 * 播放器当普通 http 播就行。
 *
 * <p><b>类名必须是 com.forcetech.android.ForceTV</b> ——
 * so 里的 JNI 符号写死了。
 *
 * <p>从猫影的 smali 还原，原实现的 start(String,int) 就是：
 * <pre>
 *   System.loadLibrary(libName);
 *   start(port, 0x1400000);   // 第二参数是 20MB 缓冲
 * </pre>
 */
public class ForceTV {

    /** 原实现里传入的缓冲大小 0x1400000 = 20MB。 */
    private static final int BUF_BYTES = 0x1400000;

    public ForceTV() { }

    /** 当前实例占用的端口，stop 时要释放。 */
    private int curPort = -1;

    private native int start(int port, int bufBytes);
    private native int stop();

    /**
     * 启动 P2P 引擎。
     *
     * @param libName so 名字，不含 lib 前缀和 .so 后缀，如 "p3p"
     * @param port    本地监听端口
     */
    public void start(String libName, int port) {
        try {
            System.loadLibrary(libName);
        } catch (Throwable t) {
            android.util.Log.w("ForceTV", "lib" + libName + ".so 加载失败（ABI 不匹配？）", t);
            return;
        }
        try {
            // 换源前先停掉旧的，否则 native 引擎会累积
            if (curPort > 0) stop2();
            curPort = port;
            start(port, BUF_BYTES);
        } catch (Throwable t) {
            android.util.Log.w("ForceTV", "start 失败", t);
        }
    }

    public void stop2() {
        try { stop(); } catch (Throwable ignored) { }
        curPort = -1;
    }

    /**
     * 按协议前缀选 so 名字。
     *
     * <p>前缀就是 p2p / p3p / p4p … p9p，各自对应 libp2p.so … libp9p.so。
     * 没有 pxp 这个协议。
     */
    /**
     * 每种前缀对应一个固定端口，值抄自猫影 PlayerActivity 的 switch 分支。
     *
     * <pre>
     *   P2p  → 9001      （大写 P，另一套引擎）
     *   mitv → 9002
     *   p2p  → 9906
     *   p3p  → 9907
     *   p4p  → 9908
     *   p5p  → 9909
     *   p6p  → 9910
     *   p7p  → 9911
     *   p8p  → 9912
     *   p9p  → 9913
     * </pre>
     * 规律：pNp 的端口 = 9904 + N。端口不能随便换，
     * 换了 cmd.xml 的控制请求就打到空气里去了。
     */
    public static int portFor(String url) {
        if (url == null) return 0;
        if (url.startsWith("mitv")) return 9002;
        if (url.startsWith("P2p")) return 9001;          // 大写 P
        if (url.startsWith("p")) {
            // p2p ~ p9p：取中间的数字
            if (url.length() >= 3 && url.charAt(2) == 'p') {
                char c = url.charAt(1);
                if (c >= '2' && c <= '9') return 9904 + (c - '0');
            }
        }
        return 0;
    }

    public static String libFor(String url) {
        if (url == null) return null;
        // mitv 用的是 libmitv.so（不是 libpNp）
        if (url.startsWith("mitv:")) return "mitv";
        String u = url.toLowerCase();
        // P2p（大写）没有独立 so，复用小写 p2p 那套，只是端口不同
        if (url.startsWith("P2p:")) return "p2p";
        for (int i = 9; i >= 2; i--) {
            if (u.startsWith("p" + i + "p:")) return "p" + i + "p";
        }
        return null;
    }

    /** 是不是 p2p ~ p9p 系的地址。 */
    public static boolean isP2P(String url) {
        return libFor(url) != null;
    }
}
