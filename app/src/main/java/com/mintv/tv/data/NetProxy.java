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
     * 转成 libVLC 的启动参数。
     *
     * <p>VLC 有两个入口，**互斥**：同时给只有一个会生效。
     * <ul>
     *   <li>{@code --socks=socks5://host:port}  SOCKS5</li>
     *   <li>{@code --http-proxy=http://host:port}  HTTP/HTTPS</li>
     * </ul>
     *
     * <p>这样播放器本身也走代理 —— 不只是脚本。
     * 组播（udp/rtp）不走 HTTP/SOCKS，不受影响。
     */
    public static java.util.List<String> vlcArgs(Context c) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String s = url(c);
            if (s == null || s.isEmpty()) return out;
            String v = s.trim();
            String low = v.toLowerCase(java.util.Locale.US);
            // http:// 上游 VLC 原生支持，直接给 --http-proxy。
            // https:// 和 socks*:// 走本地桥：前者 VLC 对 https 上游支持不确定，
            // 后者 VLC 的 HTTP 模块根本不读 --socks。
            boolean needBridge = low.startsWith("socks")
                    || low.startsWith("https://")
                    || (!v.contains("://") && !low.startsWith("http"));
            boolean socks = needBridge;
            if (socks) {
                if (!v.contains("://")) v = "socks5://" + v;
                // 关键：--socks 只被 access_smb / access_ftp / access_sftp 读取，
                // access_http 只认 --http-proxy。所以 HLS 走 SOCKS 必须转一道。
                //
                // 在本机起一个 HTTP 代理把请求转成 SOCKS，再把 --http-proxy
                // 指向它。一个实例服务所有频道（--http-proxy 是实例级参数）。
                int lp = com.mintv.tv.data.LocalSocksProxy.ensure(v);
                if (lp > 0) {
                    out.add("--http-proxy=http://127.0.0.1:" + lp);
                } else {
                    // 本地桥起不来，退回原行为（至少 smb/ftp 还能用）
                    out.add("--socks=" + v);
                }
            } else {
                if (!v.contains("://")) v = "http://" + v;
                out.add("--http-proxy=" + v);
            }
            android.util.Log.w("NetProxy", "vlcArgs 配置=[" + s + "] 结果=" + out);
        } catch (Throwable t) {
            // 以前这里是 catch (Throwable ignored)，出问题一点痕迹都没有。
            // 代理不生效时最需要看到的就是这一行。
            android.util.Log.w("NetProxy", "vlcArgs 失败 配置=[" + url(c) + "]", t);
        }
        return out;
    }

    /**
     * 给界面显示的一句话。
     *
     * <p>SOCKS 会额外显示本地桥，让人一眼看出"填的是 socks，实际给播放器
     * 用的是本地 http"：
     * <pre>
     *   http://127.0.0.1:45231 → socks5://120.48.182.33:18002
     * </pre>
     * 桥还没启动（没播放过）时显示成"播放时转本地 http"。
     */
    public static String summary(Context c) {
        String s = url(c);
        if (s == null || s.isEmpty()) return "直连";
        String v = s.trim();
        String low = v.toLowerCase(java.util.Locale.US);
        boolean socks = low.startsWith("socks")
                || low.startsWith("https://")
                || (!v.contains("://") && !low.startsWith("http"));
        if (socks) {
            if (!v.contains("://")) v = "socks5://" + v;
            String local = com.mintv.tv.data.LocalSocksProxy.localAddr();
            if (local != null) {
                return local + " → " + v;
            }
            return v + "（播放时转本地 http）";
        }
        return v;
    }
}
