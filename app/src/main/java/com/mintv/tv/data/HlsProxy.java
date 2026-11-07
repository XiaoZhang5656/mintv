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
    /**
     * 单个分片的大小上限。
     *
     * <p>原来是 8MB，太低了 —— 实测移动那路源（ottrrs.hl.chinamobile.com）
     * 每片 9.35MB，固定被这里判成「超过上限」直接丢弃，看起来像源坏了。
     * 4K 源的 HLS 分片常见 10~20MB。
     *
     * <p>放宽到 24MB 是「防止失控」而不是「限制正常内容」：
     * 正常分片不会到这个量级，真超了说明拿到的不是分片（比如整段点播文件）。
     */
    /**
     * 日志里用的短地址。
     *
     * <p>完整 URL 会暴露具体频道（路径里常带频道 ID 和分片序号）。
     * 只保留 host + 路径前 12 字符，足够判断"哪台服务器出的问题"，
     * 又看不出在看什么台。
     */
    private static String brief(String url) {
        if (url == null) return "null";
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            String path = u.getPath();
            if (path == null) path = "";
            if (path.length() > 12) path = path.substring(0, 12) + "…";
            return (host == null ? "?" : host) + path;
        } catch (Throwable t) {
            return url.length() > 28 ? url.substring(0, 28) + "…" : url;
        }
    }

    private static final int MAX_BYTES = 24 * 1024 * 1024;

    /**
     * 同时在拉的分片数上限。
     *
     * <p>为什么必须限：RemoteServer 的线程池是 cached(2..32) +
     * SynchronousQueue，VLC 一并发预取就开新线程。每个线程都要把整片
     * 读进 byte[]，9.35MB 一片 —— 32 个线程同时跑就是 300MB 瞬时内存，
     * 1G 盒子上直接 LMK。
     *
     * <p>限 3 够用：HLS 播放通常只预取 2~3 片，再多也不会更快，
     * 只是把带宽和内存同时摊薄。
     */
    private static final java.util.concurrent.Semaphore FETCH_LIMIT =
            new java.util.concurrent.Semaphore(3, true);
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
        // 列表不抢令牌，避免分片占满并发时刷不出播放列表
        byte[] raw = fetch(remoteUrl, false);
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
        return fetch(remoteUrl, true);
    }

    /**
     * @param limit 是否受 {@link #FETCH_LIMIT} 约束。
     *
     * <p>播放列表（1~2KB）不该抢分片的令牌 —— 分片占满 3 个槽位时，
     * VLC 每 10 秒要刷新的 m3u8 会等满 5 秒然后失败，表现就是播放中途断流。
     * 限流的目的是控制 9MB 分片的并发内存，拿去限制 2KB 的列表没有意义。
     */
    private static byte[] fetch(String remoteUrl, boolean limit) {
        HttpURLConnection conn = null;
        boolean acquired = false;
        // 是否成功读完。只有读完了才让连接回池；失败时直接 disconnect，
        // 半截连接留着反而会污染池子。
        boolean finished = false;
        try {
            // 限并发：拿不到令牌就等，等不到就让调用方失败重试。
            // 5 秒是单个分片能容忍的上限，再久 VLC 也已经判超时了。
            if (limit) {
                acquired = FETCH_LIMIT.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS);
                if (!acquired) {
                    android.util.Log.w("HlsProxy", "并发已满，跳过本次拉取: " + brief(remoteUrl));
                    return null;
                }
            }
            // 配了代理就走代理，否则直连 —— 没配代理时行为跟以前完全一样。
            //
            // 这里用**原始配置**，不走本地桥：
            // HttpURLConnection 原生支持 Proxy.Type.SOCKS，直接用它就行。
            // 绕一层 HTTP 桥反而出问题 —— 桥会把 HttpURLConnection 写好的
            // Host 头（目标域名）原样转给 CDN，而移动 CDN 只认 IP 形式的 Host，
            // 于是 302 之后的每一跳都 404。
            // 代理解析统一走 resolveProxy()。
            // 之前这段是内联的，openStream() 复制时漏了参数，
            // 结果 m3u8 走代理、ts 直连，抓包表现为打到 CDN 后超时。
            java.net.Proxy px = resolveProxy();
            if (px != null) {
                android.util.Log.w("HlsProxy", "代理 " + px.type() + " " + px.address());
                // 走代理时**不能**把域名换成 IP —— 代理要按域名去连目标。
                // 而且本地桥自己做过 SOCKS5 握手，不依赖本地 DNS。
                conn = (HttpURLConnection) new URL(remoteUrl).openConnection(px);
            } else {
                // 直连：先带超时解析域名，把 IP 塞进 URL —— HttpURLConnection
                // 内部的 DNS 不受 setConnectTimeout 约束，网络切换能卡十几秒。
                Net.Prepared np = Net.prepare(remoteUrl);
                if (np == null) {
                    android.util.Log.w("HlsProxy", "域名解析失败/超时: " + brief(remoteUrl));
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
                android.util.Log.w("HlsProxy", "HTTP " + code + " " + brief(remoteUrl));
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
                    // 把实际大小也打出来 —— 之前只说"超过上限"，看不出超了多少，
                    // 也判断不了是分片太大还是拿错了东西
                    android.util.Log.w("HlsProxy", "分片超过上限 " + MAX_BYTES
                            + " 字节，已读到 " + total + "，放弃: " + remoteUrl);
                    return null;
                }
                bos.write(buf, 0, n);
            }
            // 读完了，把流关掉让连接回池。
            //
            // 这一步是连接复用的关键：HttpURLConnection 只有在「整块读完 +
            // 流关闭」之后才把连接还给池子。不关的话下一片还得重新
            // TCP 握手（走代理还要再握一次 SOCKS），每片白花 50~200ms。
            finished = true;
            try { in.close(); } catch (Throwable ignored) { }
            return bos.toByteArray();
        } catch (Throwable t) {
            // 异常类型拼进消息里，免得栈被系统日志冲掉后什么都看不出来
            android.util.Log.w("HlsProxy", "拉取失败 " + brief(remoteUrl) + " : " + t);
            return null;
        } finally {
            // 成功读完就不 disconnect —— 那样会把可复用的连接掐掉。
            // 失败路径必须 disconnect，半开的连接不能还给池子。
            if (conn != null && !finished) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
            if (acquired) try { FETCH_LIMIT.release(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 边下边吐的一次会话。
     *
     * <p>为什么要有它：{@link #fetch} 会把整片读完才返回。移动那路源一片
     * 9.35MB，代理只有 500KB/s，要等 18 秒才开始给 VLC —— 起播极慢，
     * 而且 VLC 那段等待里界面是黑的。
     *
     * <p>改成「连上并读到第一段就交给调用方」，VLC 拿到几百 KB 就能开始
     * 解码，起播时间从十几秒降到一两秒。整片照样攒齐，P2P 缓存不受影响。
     *
     * <p>先把响应码和开头一小段读到手，失败的话调用方还能正常回 502 ——
     * 不会出现「已经给客户端发了 200 却挤不出数据」。
     */
    public static final class Session implements java.io.Closeable {
        private final HttpURLConnection conn;
        private final InputStream in;
        private final ByteArrayOutputStream acc;
        private final String url;
        private boolean acquired;
        /** 是否读到了正常结尾。读完整才让连接回池，中途失败必须断开。 */
        private boolean finished;

        Session(HttpURLConnection c, InputStream i, byte[] head, int headLen,
                String url, boolean acquired) throws java.io.IOException {
            this.conn = c;
            this.in = i;
            this.url = url;
            this.acquired = acquired;
            this.acc = new ByteArrayOutputStream(Math.max(16384, headLen * 4));
            this.acc.write(head, 0, headLen);
        }

        /** 累计已收到的字节数。 */
        public int received() { return acc.size(); }

        /**
         * 把剩下的数据抽干，边读边交给 sink。
         *
         * @return 完整数据；中途失败返回 null（此时已吐出去的部分收不回来，
         *         调用方应直接断开，让客户端重试）
         */
        public byte[] pump(Sink sink) {
            byte[] buf = new byte[16384];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (acc.size() + n > MAX_BYTES) {
                        android.util.Log.w("HlsProxy", "分片超过上限 " + MAX_BYTES
                                + " 字节，已读到 " + (acc.size() + n) + ": " + url);
                        return null;
                    }
                    acc.write(buf, 0, n);
                    if (sink != null) sink.write(buf, 0, n);
                }
                finished = true;   // 读到 EOF，连接可以复用
                return acc.toByteArray();
            } catch (Throwable t) {
                android.util.Log.w("HlsProxy", "流式拉取中断 " + brief(url) + " : " + t);
                return null;
            } finally {
                close();
            }
        }

        /** 交给 sink 的接口。实现方负责 HTTP 分块封帧。 */
        public interface Sink {
            void write(byte[] b, int off, int len) throws java.io.IOException;
        }

        @Override public void close() {
            // 关流让连接回池；只有没读完的才 disconnect。
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            if (!finished) {
                try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) { }
            }
            if (acquired) { acquired = false; try { FETCH_LIMIT.release(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * 建立流式会话：连上、检查状态码、预读至多 32KB。
     *
     * <p>预读是为了让「源站 404 / 连不上」这类失败在调用方写响应头之前
     * 就暴露出来 —— 这样还能正常回 502。
     *
     * @return 会话；失败返回 null
     */
    /**
     * 按当前配置解析出 java.net.Proxy。
     *
     * <p>抽出来是因为 fetch() 和 openStream() 都要用 —— 之前 openStream()
     * 漏了这一步，读 m3u8 走代理、读 ts 直连，抓包表现为请求打到 CDN IP
     * 后 3 秒超时，界面报 502。
     */
    private static java.net.Proxy resolveProxy() {
        try {
            String proxyUrl = (ctxRef == null) ? null : ctxRef.proxy();
            if (proxyUrl == null || proxyUrl.isEmpty()) return null;
            java.net.URI pu = new java.net.URI(proxyUrl);
            String ph = pu.getHost();
            int pp = pu.getPort();
            String scheme = pu.getScheme() == null ? ""
                    : pu.getScheme().toLowerCase(java.util.Locale.US);
            if (ph == null || pp <= 0) return null;
            java.net.Proxy.Type type = scheme.startsWith("socks")
                    ? java.net.Proxy.Type.SOCKS
                    : java.net.Proxy.Type.HTTP;
            return new java.net.Proxy(type, new java.net.InetSocketAddress(ph, pp));
        } catch (Throwable t) {
            return null;
        }
    }

    public static Session openStream(String remoteUrl) {
        boolean acquired = false;
        HttpURLConnection conn = null;
        try {
            acquired = FETCH_LIMIT.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) {
                android.util.Log.w("HlsProxy", "并发已满，跳过: " + brief(remoteUrl));
                return null;
            }
            java.net.Proxy px = resolveProxy();
            if (px != null) {
                // 走代理必须用原始 URL：域名交给代理解析。
                // 换成 IP 会让代理按 IP 直连，302 之后拿到的又是 IP，
                // 后续每一跳都失去域名的意义。
                conn = (HttpURLConnection) new URL(remoteUrl).openConnection(px);
                android.util.Log.w("HlsProxy", "流式代理 " + px.type()
                        + " " + px.address());
            } else {
                Net.Prepared np = Net.prepare(remoteUrl);
                if (np == null) return null;
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
                android.util.Log.w("HlsProxy", "HTTP " + code + " " + brief(remoteUrl));
                return null;
            }
            InputStream in = conn.getInputStream();
            byte[] head = new byte[32768];
            int headLen = 0;
            // 尽量读满 32KB 或多到 EOF。读满就立刻开始吞吐，
            // 读不满（小分片）也照样返回，不用等。
            while (headLen < head.length) {
                int n = in.read(head, headLen, head.length - headLen);
                if (n <= 0) break;
                headLen += n;
            }
            return new Session(conn, in, head, headLen, remoteUrl, acquired);
        } catch (Throwable t) {
            android.util.Log.w("HlsProxy", "建立流式会话失败 " + brief(remoteUrl) + " : " + t);
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
            if (acquired) try { FETCH_LIMIT.release(); } catch (Throwable ignored) { }
            return null;
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
            android.util.Log.w("HlsProxy", "转发失败 " + brief(remoteUrl), t);
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
