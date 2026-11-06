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
        String host;
        int sp;
        try {
            String v = socksUrl.trim();
            if (!v.contains("://")) v = "socks5://" + v;
            java.net.URI u = new java.net.URI(v);
            host = u.getHost();
            sp = u.getPort();
            if (host == null || sp <= 0) return -1;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "解析 socks 地址失败: " + socksUrl);
            return -1;
        }

        String key = host + ":" + sp;
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
                    acceptLoop(ss, fHost, fPort);
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

    /** 停掉。改代理地址或退出时调。 */
    public static synchronized void stop() {
        port = -1;
        curKey = "";
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        server = null;
        acceptThread = null;
    }

    private static void acceptLoop(ServerSocket ss, String socksHost, int socksPort) {
        while (!ss.isClosed()) {
            try {
                final Socket c = ss.accept();
                new Thread(new Runnable() {
                    @Override public void run() {
                        handle(c, socksHost, socksPort);
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
    private static void handle(Socket c, String socksHost, int socksPort) {
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

                remote = openSocks(socksHost, socksPort, h, p);
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

            remote = openSocks(socksHost, socksPort, h, p);
            remote.setSoTimeout(25000);

            // 拼转发请求：请求行换成相对路径，Host 头补上
            StringBuilder sb = new StringBuilder(256);
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            boolean hasHost = false;
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                if ("proxy-connection".equalsIgnoreCase(e.getKey())) continue;
                if ("host".equalsIgnoreCase(e.getKey())) hasHost = true;
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            if (!hasHost) sb.append("Host: ").append(hostPort).append("\r\n");
            sb.append("Connection: close\r\n\r\n");

            OutputStream ro = remote.getOutputStream();
            ro.write(sb.toString().getBytes("ISO-8859-1"));
            ro.flush();

            // 响应原样吐回
            InputStream ri = remote.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = ri.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "转发失败: " + t);
        } finally {
            try { if (remote != null) remote.close(); } catch (Throwable ignored) { }
            try { c.close(); } catch (Throwable ignored) { }
        }
    }

    /** 通过 SOCKS 连目标。Java 的 Socket(Proxy) 原生支持 SOCKS5。 */
    private static Socket openSocks(String socksHost, int socksPort,
                                    String host, int port) throws Exception {
        Proxy px = new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(socksHost, socksPort));
        Socket s = new Socket(px);
        s.connect(new InetSocketAddress(host, port), 10000);
        return s;
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
