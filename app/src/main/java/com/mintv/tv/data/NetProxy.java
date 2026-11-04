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
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "");
    }

    public static void save(Context c, String url) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, url == null ? "" : url.trim()).apply();
    }

    /** 解析成 java.net.Proxy；没配或格式不对返回 null（直连）。 */
    public static Proxy get(Context c) {
        try {
            String s = url(c);
            if (s == null || s.isEmpty()) return null;

            Proxy.Type type = Proxy.Type.HTTP;
            String rest = s;
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

    /** 给界面显示的一句话。 */
    public static String summary(Context c) {
        String s = url(c);
        if (s == null || s.isEmpty()) return "直连";
        return s;
    }
}
