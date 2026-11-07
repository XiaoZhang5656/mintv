package com.mintv.tv.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;

/**
 * 上游网络代理。
 *
 * <p>不配的话走直连。配了之后：
 * <ul>
 *   <li>脚本里的 {@code fetch} 走它</li>
 *   <li>本地 HLS 代理拉 m3u8 / ts 分片也走它</li>
 * </ul>
 *
 * <p><b>为什么要有这个</b>：有些源只对特定出口 IP 开放，或者在内网深处。
 * 做法不是给播放器配代理（VLC 那套参数只管 HTTP、组播根本不走代理），
 * 而是让播放器全程只访问 127.0.0.1，由宿主带代理去回源 ——
 * 这样不管上游是 HTTP 还是 SOCKS5、是公网还是内网，对播放器都一样。
 *
 * <p>格式：{@code http://host:port} 或 {@code socks5://host:port}。
 */
public final class NetProxy {

    private static final String PREF = "net_proxy";
    private static final String KEY = "url";

    private NetProxy() { }

    public static String url(Context c) {
        // 写过就直接返回，空串也算 —— 「留空 = 直连」是有效设置。
        //
        // 之前是 getString(...) != null 才返回，而 getString 会把空串转成
        // 默认值 null，于是清空之后掉进下面的迁移分支，把 SharedPreferences
        // 里的旧值捞回来，代理永远清不掉。
        if (SettingsStore.has(c, "proxy")) {
            String v = SettingsStore.getString(c, "proxy", "");
            return v == null ? "" : v;
        }
        // 从来没写过：从旧的 SharedPreferences 迁移一次
        String legacy = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "");
        if (legacy != null && !legacy.isEmpty()) {
            SettingsStore.putString(c, "proxy", legacy);
            return legacy;
        }
        return "";
    }

    public static void save(Context c, String url) {
        SettingsStore.putString(c, "proxy", url == null ? "" : url.trim());
    }

    /** 解析成 java.net.Proxy；没配或格式不对返回 null（直连）。 */
    public static Proxy get(Context c) {
        return parse(url(c));
    }

    /** 把 "socks5://ip:port" 解析成 Proxy。空串或格式不对返回 null。 */
    public static Proxy parse(String s) {
        try {
            if (s == null || s.isEmpty()) return null;

            Proxy.Type type = Proxy.Type.HTTP;
            String rest = s.trim();
            int sep = s.indexOf("://");
            if (sep > 0) {
                String scheme = s.substring(0, sep).toLowerCase(java.util.Locale.US);
                rest = s.substring(sep + 3);
                if (scheme.startsWith("socks")) type = Proxy.Type.SOCKS;
                else if (!scheme.startsWith("http")) return null;   // 不认识的协议，直连
            }
            // 去掉可能的路径
            int slash = rest.indexOf('/');
            if (slash >= 0) rest = rest.substring(0, slash);

            String host;
            int port;
            int colon = rest.lastIndexOf(':');
            if (colon > 0) {
                host = rest.substring(0, colon);
                port = Integer.parseInt(rest.substring(colon + 1));
            } else {
                host = rest;
                port = type == Proxy.Type.SOCKS ? 1080 : 8080;
            }
            if (host.isEmpty() || port <= 0 || port > 65535) return null;

            SocketAddress addr = new InetSocketAddress(host, port);
            return new Proxy(type, addr);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 原始代理配置（不经过本地桥）。
     *
     * <p>给 HlsProxy 用：它是 Java 代码，HttpURLConnection 原生支持
     * Proxy.Type.SOCKS，不需要中间再套一层 HTTP 桥。
     * 桥只留给 VLC（VLC 不认 SOCKS）。
     *
     * @return 形如 socks5://host:port 或 http://host:port；null 表示直连
     */
    public static String rawProxy(Context c) {
        String s = url(c);
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        if (!v.contains("://")) v = "socks5://" + v;
        return v;
    }

    /**
     * 给界面显示的一句话。
     *
     * <p>不再经过本地桥之后，这里直接显示用户填的配置 ——
     * 播放器实际用的就是它，没有中间层可展示。
     */
    public static String summary(Context c) {
        String s = url(c);
        if (s == null || s.isEmpty()) return "直连";
        String v = s.trim();
        if (v.isEmpty()) return "直连";
        if (!v.contains("://")) v = "socks5://" + v;
        return v;
    }
}
