package com.mintv.tv.data;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 把 SOCKS5 代理包装成本地 HTTP 代理。
 *
 * <p>为什么需要：libVLC 的 {@code --socks} 参数只被 access_smb / access_ftp
 * 那几个模块读取，<b>HTTP 访问模块只认 {@code --http-proxy}</b>。所以用户填
 * {@code socks5://host:port} 时，HLS 流量根本不会走代理 —— 参数加了，
 * 但没有模块消费它。
 *
 * <p>做法：在本机起一个 HTTP 代理，收到请求后改用 SOCKS 连目标，
 * 然后把 {@code --http-proxy=http://127.0.0.1:<port>} 交给 VLC。
 *
 * <p>一个实例服务所有频道：{@code --http-proxy} 是 LibVLC 的实例级参数，
 * 切台不需要重建。
 */
public final class LocalSocksProxy {

    private static final String TAG = "LocalSocks";

    /** 单例状态。port <= 0 表示没启动。 */
    private static volatile int port = -1;
    private static ServerSocket server;
    private static Thread acceptThread;
    private static String curKey = "";

    private LocalSocksProxy() { }

    /**
     * 确保本地代理在跑。
     *
     * @param socksUrl 形如 {@code socks5://host:port}
     * @return 本地端口；失败返回 -1
     */
    public static synchronized int ensure(String socksUrl) {
        android.util.Log.w(TAG, "ensure 被调用: " + socksUrl);
        String host;
        int sp;
        boolean upstreamHttp;
        try {
            String v = socksUrl.trim();
            if (!v.contains("://")) v = "socks5://" + v;
            java.net.URI u = new java.net.URI(v);
            host = u.getHost();
            sp = u.getPort();
            if (host == null || sp <= 0) return -1;
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.US);
            // http / https 上游用 CONNECT 隧道；socks* 用 SOCKS 握手
            upstreamHttp = scheme.startsWith("http");
        } catch (Throwable t) {
            android.util.Log.w(TAG, "解析代理地址失败: " + socksUrl);
            return -1;
        }

        final boolean fUpstreamHttp = upstreamHttp;
        String key = (upstreamHttp ? "http://" : "socks://") + host + ":" + sp;
        if (port > 0 && key.equals(curKey) && server != null && !server.isClosed()) {
            return port;   // 已经在跑，复用
        }
        stop();

        try {
            final String fHost = host;
            final int fPort = sp;
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            server = ss;
            port = ss.getLocalPort();
            curKey = key;

            acceptThread = new Thread(new Runnable() {
                @Override public void run() {
                    acceptLoop(ss, fHost, fPort, fUpstreamHttp);
                }
            }, "socks-http-bridge");
            acceptThread.setDaemon(true);
            acceptThread.start();

            android.util.Log.i(TAG, "本地 HTTP 代理已启动 127.0.0.1:" + port
                    + " → socks5 " + key);
            return port;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "启动失败: " + t);
            stop();
            return -1;
        }
    }

    /** 本地桥地址，没启动返回 null。 */
    public static String localAddr() {
        return port > 0 ? "http://127.0.0.1:" + port : null;
    }

    /** 上游 SOCKS 地址 host:port，没启动返回 null。 */
    public static String upstream() {
        return port > 0 && !curKey.isEmpty() ? curKey : null;
    }

    /** 停掉。改代理地址或退出时调。 */
    public static synchronized void stop() {
        port = -1;
        curKey = "";
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        server = null;
        acceptThread = null;
    }

    private static void acceptLoop(ServerSocket ss, String socksHost, int socksPort,
                                   boolean upstreamHttp) {
        while (!ss.isClosed()) {
            try {
                final Socket c = ss.accept();
                new Thread(new Runnable() {
                    @Override public void run() {
                        handle(c, socksHost, socksPort, upstreamHttp);
                    }
                }, "socks-http-conn").start();
            } catch (Throwable t) {
                if (ss.isClosed()) break;
            }
        }
    }

    /**
     * 处理一个客户端连接。
     *
     * <p>两种请求：
     * <ul>
     *   <li>{@code GET http://host:port/path HTTP/1.1} —— 普通 HTTP，
     *       改成相对路径后转发，响应原样吐回</li>
     *   <li>{@code CONNECT host:port HTTP/1.1} —— HTTPS 隧道，连上后
     *       双向复制字节</li>
     * </ul>
     */
    private static void handle(Socket c, String socksHost, int socksPort,
                               boolean upstreamHttp) {
        Socket remote = null;
        try {
            c.setSoTimeout(20000);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            String reqLine = readLine(in);
            if (reqLine == null || reqLine.isEmpty()) return;

            // 先把请求头读完（后面要么转发，要么丢弃）
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }

            String[] parts = reqLine.split(" ");
            if (parts.length < 3) return;
            String method = parts[0];
            String target = parts[1];

            if ("CONNECT".equalsIgnoreCase(method)) {
                // HTTPS 隧道
                int c2 = target.lastIndexOf(':');
                if (c2 <= 0) { writeErr(out, 400); return; }
                String h = target.substring(0, c2);
                int p = Integer.parseInt(target.substring(c2 + 1));

                remote = openUpstream(socksHost, socksPort, h, p, upstreamHttp);
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("ISO-8859-1"));
                out.flush();
                pump(in, out, remote);
                return;
            }

            // 普通 HTTP：target 是绝对地址 http://host:port/path
            String url = target;
            if (!url.toLowerCase().startsWith("http://")) {
                writeErr(out, 400);
                return;
            }
            String rest = url.substring(7);
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);

            String h;
            int p = 80;
            int colon = hostPort.lastIndexOf(':');
            if (colon > 0) {
                h = hostPort.substring(0, colon);
                try { p = Integer.parseInt(hostPort.substring(colon + 1)); }
                catch (Throwable ignored) { }
            } else {
                h = hostPort;
            }

            // 自己跟随重定向。
            //
            // 关键：VLC 拿到 302 之后会去请求 Location 指向的地址，
            // 而那个地址（比如 39.134.66.206）直连不通，必须也走代理。
            // 实测 VLC 不会为跳转后的地址重新走 media 级代理，直接直连失败。
            // 所以这里由桥跟到底，VLC 只看到最终的 200 + 正文。
            doGet(out, method, h, p, path, hostPort, headers,
                    socksHost, socksPort, upstreamHttp, 0);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "转发失败: " + t);
        } finally {
            try { if (remote != null) remote.close(); } catch (Throwable ignored) { }
            try { c.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 通过上游代理连目标。
     *
     * <p>Java 的 {@code Socket(Proxy)} 两种都支持：
     * <ul>
     *   <li>{@code Proxy.Type.SOCKS} —— SOCKS5 握手</li>
     *   <li>{@code Proxy.Type.HTTP} —— 发 CONNECT 建隧道</li>
     * </ul>
     * 所以 socks5:// 和 http(s):// 上游走同一段代码，只是类型不同。
     */
    /**
     * 转发一次 GET，遇到 3xx 自己跟下去。
     *
     * @param depth 已跟随的重定向次数，超过 5 次放弃
     */
    private static void doGet(OutputStream out, String method,
                              String host, int port, String path, String hostPort,
                              java.util.Map<String, String> headers,
                              String socksHost, int socksPort,
                              boolean upstreamHttp, int depth) {
        if (depth > 5) {
            writeErr(out, 508);
            return;
        }
        Socket remote = null;
        try {
            remote = openUpstream(socksHost, socksPort, host, port, upstreamHttp);
            remote.setSoTimeout(25000);

            StringBuilder sb = new StringBuilder(256);
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            boolean hasHost = false;
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                if ("proxy-connection".equalsIgnoreCase(k)) continue;
                if ("connection".equalsIgnoreCase(k)) continue;
                if ("host".equalsIgnoreCase(k)) hasHost = true;
                sb.append(k).append(": ").append(e.getValue()).append("\r\n");
            }
            if (!hasHost) sb.append("Host: ").append(hostPort).append("\r\n");
            sb.append("Connection: close\r\n\r\n");

            OutputStream ro = remote.getOutputStream();
            ro.write(sb.toString().getBytes("ISO-8859-1"));
            ro.flush();

            InputStream ri = new java.io.BufferedInputStream(remote.getInputStream(), 16384);

            // 先读状态行和响应头，判断是不是重定向
            String statusLine = readLine(ri);
            if (statusLine == null) {
                writeErr(out, 502);
                return;
            }
            java.util.Map<String, String> respHeaders = new java.util.LinkedHashMap<>();
            String line;
            while ((line = readLine(ri)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) respHeaders.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }

            int code = 0;
            try {
                String[] parts = statusLine.split(" ");
                code = Integer.parseInt(parts[1]);
            } catch (Throwable ignored) { }

            if (code >= 300 && code < 400) {
                String loc = null;
                for (java.util.Map.Entry<String, String> e : respHeaders.entrySet()) {
                    if ("location".equalsIgnoreCase(e.getKey())) { loc = e.getValue(); break; }
                }
                if (loc != null && loc.toLowerCase().startsWith("http://")) {
                    // 解析新的目标
                    String rest = loc.substring(7);
                    int slash = rest.indexOf('/');
                    String hp = slash < 0 ? rest : rest.substring(0, slash);
                    String np = slash < 0 ? "/" : rest.substring(slash);
                    String nh;
                    int nport = 80;
                    int c2 = hp.lastIndexOf(':');
                    if (c2 > 0) {
                        nh = hp.substring(0, c2);
                        try { nport = Integer.parseInt(hp.substring(c2 + 1)); }
                        catch (Throwable ignored) { }
                    } else {
                        nh = hp;
                    }
                    android.util.Log.i(TAG, "跟随重定向 " + code + " → " + loc.substring(0, Math.min(80, loc.length())));
                    doGet(out, "GET", nh, nport, np, hp, headers,
                            socksHost, socksPort, upstreamHttp, depth + 1);
                    return;
                }
            }

            // 非重定向：原样把状态行 + 头 + body 吐回去
            StringBuilder head = new StringBuilder(256);
            head.append(statusLine).append("\r\n");
            for (java.util.Map.Entry<String, String> e : respHeaders.entrySet()) {
                head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes("ISO-8859-1"));
            out.flush();

            byte[] buf = new byte[16384];
            int n;
            while ((n = ri.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "转发失败: " + t);
            try { writeErr(out, 502); } catch (Throwable ignored) { }
        } finally {
            try { if (remote != null) remote.close(); } catch (Throwable ignored) { }
        }
    }

    private static Socket openUpstream(String upHost, int upPort,
                                       String host, int port,
                                       boolean upstreamHttp) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(upHost, upPort), 10000);
        s.setSoTimeout(10000);
        if (upstreamHttp) {
            httpConnect(s, host, port);
        } else {
            socks5Connect(s, host, port);
        }
        s.setSoTimeout(0);
        return s;
    }

    /**
     * 自己发 SOCKS5 握手，把<b>域名</b>交给代理解析（ATYP=0x03）。
     *
     * <p>为什么不用 {@code Socket(Proxy.Type.SOCKS)}：那个实现强制先在
     * 本地解析域名，再把 IP 发过去。app 进程里的 DNS 偶尔会卡住，
     * 10 秒连接预算全耗在解析上，最后报的是「连不上代理」——
     * 实际代理好好的，是本地 DNS 拖死的。
     *
     * <p>把域名交给代理，语义等价于 curl 的 {@code socks5h://}，
     * 完全跳过本地 DNS。
     */
    private static void socks5Connect(Socket s, String host, int port) throws Exception {
        java.io.OutputStream o = s.getOutputStream();
        java.io.InputStream in = s.getInputStream();

        // 1) 握手：VER=5, NMETHODS=1, METHOD=0(无认证)
        o.write(new byte[]{0x05, 0x01, 0x00});
        o.flush();
        byte[] r = new byte[2];
        readFully(in, r, 2);
        if (r[0] != 0x05) throw new java.io.IOException("非 SOCKS5 响应");
        if (r[1] != 0x00) throw new java.io.IOException("代理要求认证 method=" + r[1]);

        // 2) CONNECT，ATYP=0x03 表示后面跟的是域名
        byte[] h = host.getBytes("UTF-8");
        if (h.length > 255) throw new java.io.IOException("域名过长");
        java.io.ByteArrayOutputStream req = new java.io.ByteArrayOutputStream(32);
        req.write(0x05);          // VER
        req.write(0x01);          // CMD = CONNECT
        req.write(0x00);          // RSV
        req.write(0x03);          // ATYP = 域名
        req.write(h.length);
        req.write(h);
        req.write((port >> 8) & 0xFF);
        req.write(port & 0xFF);
        o.write(req.toByteArray());
        o.flush();

        // 3) 响应：VER REP RSV ATYP + BND.ADDR + BND.PORT
        byte[] head = new byte[4];
        readFully(in, head, 4);
        if (head[0] != 0x05) throw new java.io.IOException("SOCKS5 响应版本错");
        if (head[1] != 0x00) {
            throw new java.io.IOException("SOCKS5 CONNECT 被拒 rep=" + head[1]
                    + "（1=一般错误 3=网络不可达 4=主机不可达 5=连接被拒）");
        }
        int atyp = head[3] & 0xFF;
        if (atyp == 0x01) {
            skip(in, 4);
        } else if (atyp == 0x03) {
            int n = in.read();
            if (n > 0) skip(in, n);
        } else if (atyp == 0x04) {
            skip(in, 16);
        } else {
            throw new java.io.IOException("SOCKS5 未知 ATYP=" + atyp);
        }
        skip(in, 2);   // BND.PORT
    }

    /** HTTP 代理：发 CONNECT 建隧道。 */
    private static void httpConnect(Socket s, String host, int port) throws Exception {
        java.io.OutputStream o = s.getOutputStream();
        String req = "CONNECT " + host + ":" + port + " HTTP/1.1"
                + "\r\n"
                + "Host: " + host + ":" + port
                + "\r\n\r\n";
        o.write(req.getBytes("ISO-8859-1"));
        o.flush();
        java.io.InputStream in = s.getInputStream();
        String status = readLine(in);
        if (status == null || !status.contains(" 200")) {
            throw new java.io.IOException("HTTP CONNECT 失败: " + status);
        }
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            // 丢弃剩余头
        }
    }

    private static void readFully(java.io.InputStream in, byte[] buf, int n) throws java.io.IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r <= 0) throw new java.io.IOException("连接中断");
            got += r;
        }
    }

    private static void skip(java.io.InputStream in, int n) throws java.io.IOException {
        int left = n;
        byte[] tmp = new byte[32];
        while (left > 0) {
            int r = in.read(tmp, 0, Math.min(tmp.length, left));
            if (r <= 0) throw new java.io.IOException("连接中断");
            left -= r;
        }
    }

    /** CONNECT 隧道的双向复制。 */
    private static void pump(InputStream in, OutputStream out, Socket remote) {
        final Socket r = remote;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream ri = r.getInputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = ri.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Throwable ignored) { }
            }
        }, "socks-http-pump");
        t.setDaemon(true);
        t.start();
        try {
            OutputStream ro = remote.getOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                ro.write(buf, 0, n);
                ro.flush();
            }
        } catch (Throwable ignored) { }
    }

    private static String readLine(InputStream in) {
        try {
            StringBuilder sb = new StringBuilder(64);
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    int n = sb.length();
                    if (n > 0 && sb.charAt(n - 1) == '\r') sb.setLength(n - 1);
                    return sb.toString();
                }
                if (sb.length() > 8192) return sb.toString();
                sb.append((char) c);
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeErr(OutputStream out, int code) {
        try {
            out.write(("HTTP/1.1 " + code + " Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes("ISO-8859-1"));
            out.flush();
        } catch (Throwable ignored) { }
    }
}
