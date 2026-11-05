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
    /** 诊断只跑一次，避免每次拉分片都重复。 */
    private static volatile boolean diagDone = false;

    /**
     * 首次请求时启动一次对照诊断：同一个地址，分别用 HttpURLConnection
     * 和裸 socket 各拉一次，同时打印系统代理与当前 Network。
     *
     * <p>异步跑，不阻塞播放。目的是把「到底哪一层出问题」用日志钉死，
     * 而不是靠猜。
     */
    private static void diagnoseOnce(final String url) {
        if (diagDone) return;
        diagDone = true;
        new Thread(new Runnable() {
            @Override public void run() {
                // 1) 系统代理：HttpURLConnection 会读它，裸 socket 不会
                try {
                    java.net.Proxy px = java.net.ProxySelector.getDefault()
                            .select(new java.net.URI(url)).get(0);
                    android.util.Log.w("HlsDiag", "proxy=" + px.type()
                            + " " + (px == java.net.Proxy.NO_PROXY ? "(直连)" : px.address()));
                } catch (Throwable t) {
                    android.util.Log.w("HlsDiag", "proxy 查询异常: " + t);
                }

                // 2) HttpURLConnection
                long t0 = System.currentTimeMillis();
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                            new java.net.URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    int code = c.getResponseCode();
                    android.util.Log.w("HlsDiag", "HttpURLConnection OK code=" + code
                            + " " + (System.currentTimeMillis() - t0) + "ms"
                            + " 实际IP=" + c.getURL().getHost());
                    c.disconnect();
                } catch (Throwable t) {
                    android.util.Log.w("HlsDiag", "HttpURLConnection 失败 "
                            + (System.currentTimeMillis() - t0) + "ms : " + t);
                }

                // 3) 裸 socket
                t0 = System.currentTimeMillis();
                try {
                    byte[] r = fetchRaw(url, 0);
                    android.util.Log.w("HlsDiag", "裸socket "
                            + (r == null ? "失败" : "OK " + r.length + "B")
                            + " " + (System.currentTimeMillis() - t0) + "ms");
                } catch (Throwable t) {
                    android.util.Log.w("HlsDiag", "裸socket 失败 "
                            + (System.currentTimeMillis() - t0) + "ms : " + t);
                }
            }
        }, "hls-diag").start();
    }

    public static byte[] fetch(String remoteUrl) {
        diagnoseOnce(remoteUrl);
        // 走裸 socket，不用 HttpURLConnection。
        //
        // 实测（Android 15 / MIUI）：同一个 http://host:8080/... 的地址，
        // VLC 和 curl 都能秒连，但 HttpURLConnection 会
        // SocketTimeoutException: failed to connect ... after 8000ms。
        // 而且它在 DNS 阶段也可能卡住（不受 setConnectTimeout 约束）。
        //
        // 自己拼 HTTP/1.1 就没有这些中间层，行为跟 curl 一致。
        try {
            byte[] r = fetchRaw(remoteUrl, 0);
            if (r != null) return r;
        } catch (Throwable t) {
            android.util.Log.w("HlsProxy", "裸 socket 拉取失败 " + remoteUrl + " : " + t);
        }
        // 裸 socket 只做 http。https 仍交给系统栈兜底。
        if (remoteUrl != null && remoteUrl.toLowerCase().startsWith("https://")) {
            return fetchLegacy(remoteUrl);
        }
        return null;
    }

    /** 域名解析放线程里跑，超时就放弃 —— getByName 不受任何 socket 超时约束。 */
    private static java.net.InetAddress resolveWithTimeout(final String host, long ms) {
        java.util.concurrent.Future<java.net.InetAddress> f =
                DNS_POOL.submit(new java.util.concurrent.Callable<java.net.InetAddress>() {
                    @Override public java.net.InetAddress call() throws Exception {
                        return java.net.InetAddress.getByName(host);
                    }
                });
        try {
            return f.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            f.cancel(true);
            android.util.Log.w("HlsProxy", "解析 " + host + " 失败/超时: " + t);
            return null;
        }
    }

    private static final java.util.concurrent.ExecutorService DNS_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "hls-dns");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private static final long DNS_TIMEOUT = 5000L;

    /**
     * 手写 HTTP/1.1 GET。支持 Content-Length、chunked、3xx 重定向。
     *
     * @param redirects 已跟随的重定向次数
     */
    private static byte[] fetchRaw(String remoteUrl, int redirects) throws Exception {
        if (redirects > 4) {
            android.util.Log.w("HlsProxy", "重定向过多: " + remoteUrl);
            return null;
        }
        java.net.URL u = new java.net.URL(remoteUrl);
        String scheme = u.getProtocol();
        if (!"http".equalsIgnoreCase(scheme)) return null;

        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 80;
        String file = u.getFile();
        if (file == null || file.isEmpty()) file = "/";

        java.net.InetAddress addr = resolveWithTimeout(host, DNS_TIMEOUT);
        if (addr == null) return null;

        java.net.Socket sock = new java.net.Socket();
        try {
            sock.connect(new java.net.InetSocketAddress(addr, port), CONNECT_TIMEOUT);
            sock.setSoTimeout(READ_TIMEOUT);

            StringBuilder req = new StringBuilder(256);
            req.append("GET ").append(file).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host);
            if (port != 80) req.append(':').append(port);
            req.append("\r\n");
            req.append("User-Agent: MinTV/1.0\r\n");
            req.append("Accept: */*\r\n");
            req.append("Accept-Encoding: identity\r\n");
            req.append("Connection: close\r\n");
            java.util.Map<String, String> extra =
                    ctxRef == null ? null : ctxRef.headers();
            if (extra != null) {
                for (java.util.Map.Entry<String, String> e : extra.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
                }
            }
            req.append("\r\n");

            java.io.OutputStream o = sock.getOutputStream();
            o.write(req.toString().getBytes("ISO-8859-1"));
            o.flush();

            java.io.InputStream in =
                    new java.io.BufferedInputStream(sock.getInputStream(), 32768);

            String status = readLine(in);
            if (status == null || !status.startsWith("HTTP/")) {
                android.util.Log.w("HlsProxy", "响应头异常: " + status);
                return null;
            }
            int code;
            try {
                String[] parts = status.split(" ");
                code = Integer.parseInt(parts[1]);
            } catch (Throwable t) {
                return null;
            }

            java.util.Map<String, String> hdrs = new java.util.HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    hdrs.put(line.substring(0, c).trim().toLowerCase(),
                            line.substring(c + 1).trim());
                }
            }

            if (code >= 300 && code < 400) {
                String loc = hdrs.get("location");
                if (loc != null) {
                    return fetchRaw(new java.net.URL(u, loc).toString(), redirects + 1);
                }
                return null;
            }
            if (code < 200 || code >= 300) {
                android.util.Log.w("HlsProxy", "HTTP " + code + " " + remoteUrl);
                return null;
            }

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(32768);
            String te = hdrs.get("transfer-encoding");
            String cl = hdrs.get("content-length");
            if (te != null && te.toLowerCase().contains("chunked")) {
                copyChunked(in, bos);
            } else if (cl != null) {
                long want;
                try { want = Long.parseLong(cl.trim()); } catch (Throwable t) { want = -1; }
                if (want >= 0) copyFixed(in, bos, want);
                else copyAll(in, bos);
            } else {
                copyAll(in, bos);
            }
            return bos.toByteArray();
        } finally {
            try { sock.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readLine(java.io.InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int n = sb.length();
                if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                return sb.toString();
            }
            if (sb.length() > 16384) return sb.toString();
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void copyFixed(java.io.InputStream in,
                                  java.io.ByteArrayOutputStream out, long len) throws java.io.IOException {
        byte[] buf = new byte[32768];
        long left = len;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n <= 0) break;
            out.write(buf, 0, n);
            left -= n;
            if (out.size() > MAX_BYTES) throw new java.io.IOException("body too large");
        }
    }

    private static void copyAll(java.io.InputStream in,
                                java.io.ByteArrayOutputStream out) throws java.io.IOException {
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw new java.io.IOException("body too large");
        }
    }

    private static void copyChunked(java.io.InputStream in,
                                    java.io.ByteArrayOutputStream out) throws java.io.IOException {
        byte[] buf = new byte[32768];
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
            int size;
            try { size = Integer.parseInt(sizeLine.trim(), 16); }
            catch (Throwable t) { break; }
            if (size == 0) { readLine(in); break; }
            int left = size;
            while (left > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, left));
                if (n <= 0) return;
                out.write(buf, 0, n);
                left -= n;
                if (out.size() > MAX_BYTES) throw new java.io.IOException("body too large");
            }
            readLine(in);   // 每个 chunk 后面的 CRLF
        }
    }

    /** https 或裸 socket 失败时的兜底，保持原行为。 */
    private static byte[] fetchLegacy(String remoteUrl) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new java.net.URL(remoteUrl).openConnection();
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
            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(16384);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BYTES) return null;
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            android.util.Log.w("HlsProxy", "拉取失败 " + remoteUrl, t);
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
