package com.mintv.tv.data;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * HLS 代理：把远端 m3u8 拉下来，改写分片地址，让分片也走本地。
 *
 * <p>解决的是这几个问题：
 * <ul>
 *   <li>源要特定 Referer/UA，VLC 只认三个头（UA/Referer/Cookie），别的带不上</li>
 *   <li>源在某些网络下不通，需要中转</li>
 *   <li>需要统计/改写分片</li>
 * </ul>
 *
 * <p>用法：播放 {@code mintv://<完整URL>}，客户端展开成
 * {@code http://127.0.0.1:<port>/mintv/m3u8?u=<base64(url)>}，
 * 本类负责拉取并改写，VLC 拿到的是改写后的播放列表。
 */
public final class HlsProxy {

    /** 单次拉取上限，防止源返回巨物把内存吃爆。 */
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 15000;

    private static Context0 ctxRef;

    /** 避免直接依赖 Android Context 的构造，包一层便于单测。 */
    public interface Context0 {
        /** 本地服务端口，用于拼改写后的地址。 */
        int localPort();
        /** 当前生效的请求头（可能为 null）。 */
        Map<String, String> headers();
        /**
         * 当前生效的代理，形如 http://127.0.0.1:34647；null 表示直连。
         *
         * <p>为什么在 HlsProxy 里也要走代理：VLC 的 media 级代理只作用于
         * 它自己发起的请求，302 跳转和 m3u8 里嵌套的子 playlist 会直连 ——
         * 实测桥只收到一次请求，跳转后的地址全漏了。
         * 由 HlsProxy 统一拉取，才能真正做到「一个不漏」。
         */
        String proxy();
    }

    public static void init(Context0 c) { ctxRef = c; }

    private HlsProxy() { }

    /**
     * 拉取并改写 m3u8。
     *
     * @param remoteUrl 远端 m3u8 完整地址
     * @return 改写后的文本；失败返回 null
     */
    public static String fetchAndRewrite(String remoteUrl) {
        byte[] raw = fetch(remoteUrl);
        if (raw == null) return null;
        String text;
        try {
            text = new String(raw, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
        if (!text.startsWith("#EXTM3U")) {
            // 有的源返回的是二进制 TS（单条流），交给调用方处理
            return null;
        }
        return rewrite(text, remoteUrl);
    }

    /**
     * 拉取任意地址的原始字节（分片、密钥都用这个）。
     */
    public static byte[] fetch(String remoteUrl) {
        HttpURLConnection conn = null;
        try {
            // 配了代理就走代理，否则直连 —— 没配代理时行为跟以前完全一样。
            //
            // 这里用**原始配置**，不走本地桥：
            // HttpURLConnection 原生支持 Proxy.Type.SOCKS，直接用它就行。
            // 绕一层 HTTP 桥反而出问题 —— 桥会把 HttpURLConnection 写好的
            // Host 头（目标域名）原样转给 CDN，而移动 CDN 只认 IP 形式的 Host，
            // 于是 302 之后的每一跳都 404。
            java.net.Proxy px = null;
            String proxyUrl = (ctxRef == null) ? null : ctxRef.proxy();
            if (proxyUrl != null && !proxyUrl.isEmpty()) {
                try {
                    java.net.URI pu = new java.net.URI(proxyUrl);
                    String ph = pu.getHost();
                    int pp = pu.getPort();
                    String scheme = pu.getScheme() == null ? ""
                            : pu.getScheme().toLowerCase(java.util.Locale.US);
                    if (ph != null && pp > 0) {
                        java.net.Proxy.Type type = scheme.startsWith("socks")
                                ? java.net.Proxy.Type.SOCKS
                                : java.net.Proxy.Type.HTTP;
                        px = new java.net.Proxy(type, new java.net.InetSocketAddress(ph, pp));
                        android.util.Log.w("HlsProxy", "代理 " + type + " " + ph + ":" + pp);
                    }
                } catch (Throwable ignored) { }
            }

            if (px != null) {
                // 走代理时**不能**把域名换成 IP —— 代理要按域名去连目标。
                // 而且本地桥自己做过 SOCKS5 握手，不依赖本地 DNS。
                conn = (HttpURLConnection) new URL(remoteUrl).openConnection(px);
            } else {
                // 直连：先带超时解析域名，把 IP 塞进 URL —— HttpURLConnection
                // 内部的 DNS 不受 setConnectTimeout 约束，网络切换能卡十几秒。
                Net.Prepared np = Net.prepare(remoteUrl);
                if (np == null) {
                    android.util.Log.w("HlsProxy", "域名解析失败/超时: " + remoteUrl);
                    return null;
                }
                conn = (HttpURLConnection) new URL(np.url).openConnection();
                if (np.hostHeader != null) conn.setRequestProperty("Host", np.hostHeader);
            }
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            applyHeaders(conn);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                android.util.Log.w("HlsProxy", "HTTP " + code + " " + remoteUrl);
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BYTES) {
                    android.util.Log.w("HlsProxy", "超过上限 " + MAX_BYTES + " 字节，放弃");
                    return null;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            // 异常类型拼进消息里，免得栈被系统日志冲掉后什么都看不出来
            android.util.Log.w("HlsProxy", "拉取失败 " + remoteUrl + " : " + t);
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 流式转发分片到输出流。不整块缓冲，4K 分片也不会撑爆内存。
     */
    public static void stream(String remoteUrl, OutputStream out) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(remoteUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            applyHeaders(conn);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return;

            InputStream in = conn.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Throwable t) {
            android.util.Log.w("HlsProxy", "转发失败 " + remoteUrl, t);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    private static void applyHeaders(HttpURLConnection conn) {
        if (ctxRef == null) return;
        Map<String, String> h = ctxRef.headers();
        if (h == null) return;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            try { conn.setRequestProperty(e.getKey(), e.getValue()); } catch (Throwable ignored) { }
        }
    }

    /**
     * 改写 m3u8 内容。
     *
     * <p>三类地址要改：
     * <pre>
     *   裸分片行            seg.ts 或 https://.../seg.ts
     *   #EXT-X-KEY          URI="key.bin"（加密的 HLS）
     *   #EXT-X-MAP          URI="init.mp4"（fMP4）
     * </pre>
     * 相对路径要基于原 m3u8 的地址解析成绝对地址后再包。
     */
    static String rewrite(String text, String baseUrl) {
        return rewrite(text, baseUrl, null);
    }

    /**
     * 改写播放列表，并把分片序号编进代理地址。
     *
     * <p>HLS 分片本身没有序号字段，只能从 {@code #EXT-X-MEDIA-SEQUENCE}
     * 往后数。带上序号之后，分片请求就能和 P2P 缓存对上号。
     *
     * @param channel 频道 hash（可空，空则不启用 P2P）
     */
    static String rewrite(String text, String baseUrl, String channel) {
        StringBuilder sb = new StringBuilder(text.length() + 256);
        String[] lines = text.split("\n", -1);

        int seq = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    seq = Integer.parseInt(t.substring(t.indexOf(':') + 1).trim());
                } catch (Throwable ignored) { }
                break;
            }
        }

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) { sb.append(line).append('\n'); continue; }

            if (trimmed.startsWith("#")) {
                sb.append(rewriteTagUri(line, baseUrl, channel));
                sb.append('\n');
            } else {
                String abs = resolve(baseUrl, trimmed);
                sb.append(proxiedSegUrl(abs, channel, seq));
                seq++;
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 处理 #EXT-X-KEY / #EXT-X-MAP / #EXT-X-MEDIA 里的 URI="..."。 */
    private static String rewriteTagUri(String line, String baseUrl, String channel) {
        int idx = line.indexOf("URI=\"");
        if (idx < 0) return line;
        int start = idx + 5;
        int end = line.indexOf('"', start);
        if (end < 0) return line;
        String uri = line.substring(start, end);
        if (uri.startsWith("data:")) return line;   // 内嵌数据，不用代理
        String abs = resolve(baseUrl, uri);
        String replaced = line.substring(0, start)
                + proxiedSegUrl(abs, channel, -1) + line.substring(end);
        return replaced;
    }

    private static String proxiedSegUrl(String absUrl, String channel, int seq) {
        int port = ctxRef == null ? 18899 : ctxRef.localPort();
        StringBuilder u = new StringBuilder("http://127.0.0.1:" + port + "/mintv/seg?u=");
        u.append(android.net.Uri.encode(b64(absUrl)));
        if (channel != null && seq >= 0) {
            u.append("&ch=").append(channel).append("&seq=").append(seq);
        }
        // 带签名，否则这个端口就是任何人都能用的开放代理
        u.append("&s=").append(com.mintv.tv.data.RemoteServer.sign(absUrl));
        return u.toString();
    }

    /** 把相对路径按 m3u8 的地址解析成绝对地址。 */
    static String resolve(String baseUrl, String ref) {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        try {
            return new URL(new URL(baseUrl), ref).toString();
        } catch (Throwable t) {
            return ref;
        }
    }

    public static String b64(String s) {
        return android.util.Base64.encodeToString(
                s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
    }

    public static String unb64(String s) {
        try {
            byte[] b = android.util.Base64.decode(s,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
