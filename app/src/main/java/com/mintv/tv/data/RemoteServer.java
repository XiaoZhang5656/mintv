package com.mintv.tv.data;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * 手机遥控 / 订阅管理用的极简 HTTP 服务。
 *
 * <p>为什么手写而不是引 NanoHTTPD：整个项目零第三方依赖，
 * 而这里只需要「读一行请求 + 回一段字节」，200 行够了。
 *
 * <p>端口：先试 {@link #PORT_MAIN}（18899），被占用再试 {@link #PORT_ALT}（18900）。
 * 不用 8080/8899 是因为这类端口在各种开发工具里太常见，撞上就起不来。
 *
 * <p>每次请求处理完就关闭连接（Connection: close）——
 * 手机端每秒轮询一次状态，重新建连的开销在局域网里可以忽略，
 * 换来的是完全不用管长连接的半关闭、超时、连接泄漏。
 */
public final class RemoteServer {

    public static final int PORT_MAIN = 18899;
    public static final int PORT_ALT  = 18900;

    private static final String TAG = "RemoteServer";
    private static final String ASSET_DIR = "remote/";

    /** 业务回调，由 Activity 实现。 */
    public interface Host {
        /** 当前播放状态 JSON */
        String stateJson();
        /** 执行一条遥控命令，返回给手机的一句话结果 */
        String onCommand(String type, String value);
        /** 订阅列表 JSON */
        String subsJson();

        /** 节目单 JSON；ch 为空表示当前在播的频道。 */
        String epgJson(String ch);

        /** 按频道名 / 当前节目名搜索。 */
        String searchJson(String q, String by);

        /** 分类 + 指定分类下的频道。 */
        String channelsJson(int cat);
        /** 订阅增删改切，返回 {"ok":true} 或 {"ok":false,"msg":"..."} */
        String onSubsAction(String action, org.json.JSONObject payload);
    }

    private final Context appCtx;
    private final Host host;
    /** 脚本路由，第一次用到时才建（省启动时间）。 */
    private ScriptRouter scriptRouter;
    private ServerSocket server;
    private int port = -1;
    private volatile boolean running = false;
    /** 最后一次收到请求的时间，用来判断「手机是否已连接」。 */
    private volatile long lastHit = 0L;
    private Thread acceptThread;

    public RemoteServer(Context ctx, Host host) {
        this.appCtx = ctx.getApplicationContext();
        this.host = host;
    }

    /** 上次同步脚本的时间，避免每个请求都碰一次目录。 */
    private long lastScriptSync = 0L;
    private static final long SCRIPT_SYNC_GAP_MS = 3000L;

    /** 取脚本路由，顺带懒加载。 */
    private synchronized ScriptRouter router() {
        if (scriptRouter == null) scriptRouter = new ScriptRouter(appCtx);
        // 定期把授权目录 scripts/ 里的脚本同步到私有目录。
        // ScriptEngine 只吃 File，SAF 给不了 File，执行必须在私有目录。
        // 之前只在首次建路由时同步一次，导致"丢了新脚本要重启才认"。
        long now = System.currentTimeMillis();
        if (now - lastScriptSync > SCRIPT_SYNC_GAP_MS) {
            lastScriptSync = now;
            try {
                scriptRouter.store().syncFromStorage(appCtx);
            } catch (Throwable t) {
                android.util.Log.w("RemoteServer", "同步脚本失败", t);
            }
        }
        return scriptRouter;
    }

    public int port() { return port; }

    /** 距上次收到请求多少毫秒；从没收到过返回 Long.MAX_VALUE。 */
    public long idleMs() {
        return lastHit == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastHit;
    }
    public boolean running() { return running; }

    /** 启动。成功返回端口号，两个端口都被占用返回 -1。 */
    public int start() {
        if (running) return port;
        int[] candidates = {PORT_MAIN, PORT_ALT};
        for (int p : candidates) {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(p), 8);
                port = p;
                break;
            } catch (Throwable t) {
                Log.w(TAG, "端口 " + p + " 不可用: " + t);
                closeQuietly();
            }
        }
        if (port < 0) return -1;

        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "remote-http");
        acceptThread.start();
        Log.i(TAG, "HTTP 服务已启动，端口 " + port);
        return port;
    }

    public void stop() {
        running = false;
        closeQuietly();
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        port = -1;
    }

    private void closeQuietly() {
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        server = null;
    }

    /**
     * 每个连接一个线程处理。
     *
     * <p>原来是 accept → handle 同步串行。播放时 VLC 会并发请求 3~5 个分片，
     * 一个 4.3MB 的分片还在下载，后面的 m3u8 请求就全卡在 accept 队列里，
     * 界面上表现为「正在解析源地址…」一直不动。
     *
     * <p>池子固定大小，避免被 VLC 的预加载打爆。
     */
    /**
     * 每连接一线程，空闲回收，上限 32。
     *
     * <p>原来固定 6 个。脚本里一个死循环（QuickJS 没有中断接口，
     * 详见 ScriptEngine 的注释）就会永久占死一个线程 —— 6 个脚本各卡一次，
     * 整个手机助手连 /api/logs 都打不开。
     *
     * <p>改成 cached + 上限 32：最坏情况占满 32 个线程，服务还能响应。
     * 线程回收不了是引擎的限制，这里只能保证不被拖死。
     */
    private final java.util.concurrent.ExecutorService workers =
            new java.util.concurrent.ThreadPoolExecutor(
                    2, 32, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<Runnable>(),
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "remote-http");
                            t.setDaemon(true);
                            return t;
                        }
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /**
     * 进程级随机盐，用来给 u 参数签名。
     *
     * <p>为什么需要：u 只是 Base64，不是加密。任何人构造
     * {@code /mintv/m3u8?u=<base64 任意地址>} 就能把这个 app 当开放的
     * HTTP 代理 —— 拉别人的内容、探自己内网。监听是 0.0.0.0，
     * 同一 WiFi 下的设备直接可访问。
     *
     * <p>盐每次进程启动重新生成，外面的链接一律失效。
     */
    private static final String SALT =
            Long.toHexString(new java.util.Random().nextLong())
                    + Long.toHexString(System.nanoTime());

    /** u 的签名，取 SHA-256 前 12 位十六进制。 */
    public static String sign(String u) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            md.update(SALT.getBytes("UTF-8"));
            if (u != null) md.update(u.getBytes("UTF-8"));
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 校验 u 的签名。没带签名或对不上都算无效。 */
    private static boolean signOk(String u, String sig) {
        String want = sign(u);
        return want.length() > 0 && want.equals(sig);
    }

    /** 目标地址放行判断。私网/回环/保留地址一律拒绝，防 SSRF。 */
    static boolean isAllowedTarget(String raw) {
        if (raw == null) return false;
        String low = raw.toLowerCase();
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false;
        try {
            java.net.URL u = new java.net.URL(raw);
            String host = u.getHost();
            if (host == null || host.isEmpty()) return false;
            if (host.equalsIgnoreCase("localhost")) return false;
            if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                String[] q = host.split("\\.");
                int a = Integer.parseInt(q[0]);
                int b = Integer.parseInt(q[1]);
                if (a == 10 || a == 127 || a == 0) return false;
                if (a == 172 && b >= 16 && b <= 31) return false;
                if (a == 192 && b == 168) return false;
                if (a == 169 && b == 254) return false;
                if (a >= 224) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                try {
                    s.setSoTimeout(8000);
                } catch (Throwable ignored) { }
                workers.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            handle(s);
                        } catch (Throwable t) {
                            android.util.Log.w(TAG, "handle 异常: " + t);
                        } finally {
                            try { s.close(); } catch (Throwable ignored) { }
                        }
                    }
                });
            } catch (Throwable t) {
                if (!running) break;
            }
        }
    }

    // ---------------- 单次请求 ----------------

    private void handle(Socket s) {
        lastHit = System.currentTimeMillis();
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];
            int q = path.indexOf('?');
            String query = q >= 0 ? path.substring(q + 1) : "";
            if (q >= 0) path = path.substring(0, q);

            // 读完请求头，拿到 Content-Length
            int contentLength = 0;
            // 请求头全收下来传给脚本。以前只取 Content-Length，
            // 脚本的 item.headers 永远是空对象 —— 需要 Referer/Cookie
            // 的源（防盗链）在脚本里根本没法处理。
            final java.util.Map<String, String> reqHeaders = new java.util.HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String hn = line.substring(0, c).trim();
                String hv = line.substring(c + 1).trim();
                if (hn.isEmpty()) continue;
                reqHeaders.put(hn, hv);
                if ("content-length".equalsIgnoreCase(hn)) {
                    try { contentLength = Integer.parseInt(hv); }
                    catch (Throwable ignored) { }
                }
            }
            String body = "";
            if (contentLength > 0) {
                byte[] buf = new byte[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = in.read(buf, read, contentLength - read);
                    if (n <= 0) break;
                    read += n;
                }
                body = new String(buf, 0, read, "UTF-8");
            }

            route(method, path, query, body, reqHeaders, out);
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "请求处理失败: " + t);
        }
    }

    private void route(String method, String path, String query, String body,
                       java.util.Map<String, String> reqHeaders, OutputStream out)
            throws IOException {
        // ---- API ----
        if ("/api/state".equals(path)) {
            send(out, 200, "application/json; charset=utf-8", host.stateJson());
            return;
        }
        if ("/api/cmd".equals(path)) {
            String type = jsonField(body, "type");
            String value = jsonField(body, "value");
            String result = host.onCommand(type, value);
            send(out, 200, "application/json; charset=utf-8",
                    "{\"ok\":true,\"msg\":" + quote(result) + "}");
            return;
        }
        if ("/api/channels".equals(path)) {
            int cat = 0;
            try {
                int i = query.indexOf("cat=");
                if (i >= 0) cat = Integer.parseInt(query.substring(i + 4).split("&")[0]);
            } catch (Throwable ignored) { }
            send(out, 200, "application/json; charset=utf-8", host.channelsJson(cat));
            return;
        }
        if ("/api/epg".equals(path)) {
            String ch = param(query, "ch");
            send(out, 200, "application/json; charset=utf-8", host.epgJson(ch));
            return;
        }
        if ("/api/search".equals(path)) {
            send(out, 200, "application/json; charset=utf-8",
                    host.searchJson(param(query, "q"), param(query, "by")));
            return;
        }
        if ("/api/subs".equals(path)) {
            if ("POST".equals(method)) {
                String resp = host.onSubsAction(jsonField(body, "action"), safeJson(body));
                send(out, 200, "application/json; charset=utf-8",
                        resp == null ? "{\"ok\":false}" : resp);
            } else {
                send(out, 200, "application/json; charset=utf-8", host.subsJson());
            }
            return;
        }

        // ---- 本地源上传 ----
        // 把手机选的文件内容写成一份本地源，返回 {ok, url:"local://xxx"}
        // 前端拿到 url 之后调 /api/subs 的 add 动作建订阅。
        if ("/api/subs/local".equals(path)) {
            if (!"POST".equals(method)) {
                send(out, 405, "application/json; charset=utf-8", "{\"ok\":false}");
                return;
            }
            try {
                String name = jsonField(body, "name");
                String content = jsonField(body, "content");
                if (name == null || name.isEmpty() || content == null) {
                    send(out, 400, "application/json; charset=utf-8",
                            "{\"ok\":false,\"msg\":\"缺少 name 或 content\"}");
                    return;
                }
                // 文件名只留安全字符，避免路径穿越
                String safe = name.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
                if (safe.length() > 120) safe = safe.substring(0, 120);

                // 写进统一存储的 upload/ 子目录。
                //
                // 之前这里直接写 getExternalFilesDir(null)/sources（App 私有目录），
                // 而扫描读的是 StorageDir（授权目录）—— 两套路径完全不通，
                // 结果"手机上传成功了，盒子上永远看不到"。
                boolean ok = com.mintv.tv.data.StorageDir.write(
                        appCtx, com.mintv.tv.data.StorageDir.SUB_UPLOAD, safe, content);
                if (!ok) {
                    send(out, 500, "application/json; charset=utf-8",
                            "{\"ok\":false,\"msg\":\"写入存储目录失败\"}");
                    return;
                }

                org.json.JSONObject o = new org.json.JSONObject();
                o.put("ok", true);
                o.put("url", "upload://" + safe);
                o.put("size", content.getBytes("UTF-8").length);
                send(out, 200, "application/json; charset=utf-8", o.toString());
            } catch (Exception e) {
                send(out, 500, "application/json; charset=utf-8",
                        "{\"ok\":false,\"msg\":\"" + esc(e.getMessage()) + "\"}");
            }
            return;
        }

        // ---- 脚本 ----
        if ("/api/scripts".equals(path)) {
            ScriptStore store = new ScriptStore(appCtx);
            try {
                if ("POST".equals(method)) {
                    String name = jsonField(body, "name");
                    String content = jsonField(body, "content");
                    if (name == null || name.isEmpty()) {
                        send(out, 400, "application/json; charset=utf-8",
                                "{\"ok\":false,\"msg\":\"缺少 name\"}");
                        return;
                    }
                    if (!store.write(name, content == null ? "" : content)) {
                        send(out, 400, "application/json; charset=utf-8",
                                "{\"ok\":false,\"msg\":\"文件名不合法\"}");
                        return;
                    }
                    ScriptLog.info(name, "已保存");
                    send(out, 200, "application/json; charset=utf-8", "{\"ok\":true}");
                    return;
                }
                if ("DELETE".equals(method)) {
                    String name = qparam(query, "name");
                    boolean ok = store.delete(name);
                    if (ok) ScriptLog.info(name, "已删除");
                    send(out, 200, "application/json; charset=utf-8",
                            "{\"ok\":" + ok + "}");
                    return;
                }
                // GET
                String name = qparam(query, "name");
                if (name != null && !name.isEmpty()) {
                    String content = store.read(name);
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("ok", true);
                    o.put("name", name);
                    o.put("content", content);
                    send(out, 200, "application/json; charset=utf-8", o.toString());
                    return;
                }
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("ok", true);
                o.put("items", store.toJson(store.list()));
                o.put("dir", store.displayPath());
                o.put("saf", com.mintv.tv.data.ScriptDir.isSaf(appCtx));
                send(out, 200, "application/json; charset=utf-8", o.toString());
            } catch (Exception e) {
                send(out, 500, "application/json; charset=utf-8",
                        "{\"ok\":false,\"msg\":\"" + esc(e.getMessage()) + "\"}");
            }
            return;
        }

        // ---- 脚本日志 ----
        if ("/api/logs".equals(path)) {
            if ("DELETE".equals(method)) {
                ScriptLog.clear();
                send(out, 200, "application/json; charset=utf-8", "{\"ok\":true}");
                return;
            }
            long since = 0;
            try { since = Long.parseLong(qparam(query, "since")); } catch (Exception ignored) { }
            String lv = qparam(query, "level");
            ScriptLog.Level min = "error".equals(lv) ? ScriptLog.Level.ERROR
                    : "warn".equals(lv) ? ScriptLog.Level.WARN : null;
            java.util.List<ScriptLog.Entry> list =
                    ScriptLog.query(since, min, qparam(query, "script"));
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("ok", true);
                o.put("items", ScriptLog.toJson(list));
                o.put("total", ScriptLog.size());
                o.put("errors", ScriptLog.countOf(ScriptLog.Level.ERROR));
                o.put("warns", ScriptLog.countOf(ScriptLog.Level.WARN));
                o.put("text", ScriptLog.exportText(list));
            } catch (Exception ignored) { }
            send(out, 200, "application/json; charset=utf-8", o.toString());
            return;
        }

        // ---- P2P 分片上传 ----
        // 别的节点来拉我缓存的分片：GET /mintv/piece?ch=<hash>&seq=<n>
        // 只有开了源力加速、且缓存里真有这一片才回应，否则 404。
        if (path.equals("/mintv/piece")) {
            String ch = qparam(query, "ch");
            int seq = -1;
            try { seq = Integer.parseInt(qparam(query, "seq")); } catch (Exception ignored) { }

            com.mintv.tv.data.P2PEngine p2p =
                    com.mintv.tv.data.P2PEngine.get(appCtx);
            if (!p2p.running() || ch == null || seq < 0
                    || !ch.equals(p2p.channelHash())) {
                send(out, 404, "text/plain; charset=utf-8", "not here");
                return;
            }
            // 服务器下发的 upload_min_segs：缓存不够时不上传，
            // 先保证自己的播放不被别的节点拖累。
            if (!p2p.canUpload()) {
                send(out, 503, "text/plain; charset=utf-8", "buffering");
                return;
            }
            com.mintv.tv.data.PieceCache.Piece piece = p2p.cache().get(seq);
            if (piece == null || piece.data == null) {
                send(out, 404, "text/plain; charset=utf-8", "no piece");
                return;
            }
            // 带上校验值，对方拿到能自检
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + piece.data.length + "\r\n"
                    + "X-Piece-SHA256: " + (piece.sha256 == null ? "" : piece.sha256) + "\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes("UTF-8"));
            out.write(piece.data);
            // 记上传量，心跳时报给 tracker
            try { p2p.onServed(piece.data.length); } catch (Throwable ignored) { }
            out.flush();
            return;
        }

        // ---- HLS 代理 ----
        // 播放 mintv://<url> 时，客户端展开成本地这两个端点：
        //   /mintv/m3u8?u=<base64>   拉取并改写播放列表
        //   /mintv/seg?u=<base64>    转发分片/密钥
        // 这样源要的任何请求头都由 App 带，VLC 不用管。
        if (path.equals("/mintv/m3u8")) {
            String rawU = qparam(query, "u");
            String u = HlsProxy.unb64(rawU == null ? "" : rawU);
            if (u == null) {
                send(out, 400, "text/plain; charset=utf-8", "bad url");
                return;
            }
            if (!signOk(u, qparam(query, "s"))) {
                send(out, 403, "text/plain; charset=utf-8", "bad sign");
                return;
            }
            if (!isAllowedTarget(u)) {
                send(out, 403, "text/plain; charset=utf-8", "target not allowed");
                return;
            }
            String m3u = HlsProxy.fetchAndRewrite(u);
            if (m3u == null) {
                send(out, 502, "text/plain; charset=utf-8", "fetch failed");
                return;
            }
            send(out, 200, "application/vnd.apple.mpegurl; charset=utf-8", m3u);
            return;
        }
        if (path.equals("/mintv/seg")) {
            String rawU = qparam(query, "u");
            String u = HlsProxy.unb64(rawU == null ? "" : rawU);
            if (u == null) {
                send(out, 400, "text/plain; charset=utf-8", "bad url");
                return;
            }
            if (!signOk(u, qparam(query, "s"))) {
                send(out, 403, "text/plain; charset=utf-8", "bad sign");
                return;
            }
            if (!isAllowedTarget(u)) {
                send(out, 403, "text/plain; charset=utf-8", "target not allowed");
                return;
            }

            // 带序号的才是媒体分片，才参与 P2P。
            // KEY / MAP 这类没有序号，直接转发不缓存。
            String segCh = qparam(query, "ch");
            int segSeq = -1;
            try { segSeq = Integer.parseInt(qparam(query, "seq")); } catch (Exception ignored) { }

            com.mintv.tv.data.P2PEngine p2p = null;
            if (segCh != null && segSeq >= 0) {
                p2p = com.mintv.tv.data.P2PEngine.get(appCtx);
                if (p2p.running() && segCh.equals(p2p.channelHash())) {
                    // 1) 本地缓存或对端有 → 直接用，省一次源站流量
                    byte[] hit = p2p.fetchPiece(segSeq);
                    if (hit != null && hit.length > 0) {
                        send(out, 200, "application/octet-stream", hit);
                        return;
                    }
                }
            }

            // 2) 回源：整块拉下来（要存缓存，所以不能纯流式）
            byte[] data = HlsProxy.fetch(u);
            if (data == null || data.length == 0) {
                send(out, 502, "text/plain; charset=utf-8", "fetch failed");
                return;
            }
            if (p2p != null && p2p.running() && segCh != null
                    && segCh.equals(p2p.channelHash())) {
                try {
                    p2p.onPieceFetched(segSeq, data);
                    p2p.setPos(segSeq);
                } catch (Throwable ignored) { }
            }
            send(out, 200, "application/octet-stream", data);
            return;
        }

        // ---- 脚本路由 ----
        // 放在静态页之前：脚本声明的 /cctv/:id 这类路径优先，
        // 都不匹配时才去找 assets 里的静态文件。
        if (!path.startsWith("/api/") && path.length() > 1) {
            try {
                ScriptRouter sr = router();
                if (sr.canHandle(path)) {
                    sr.handle(path, query, reqHeaders, out);
                    return;
                }
            } catch (Throwable t) {
                android.util.Log.w(TAG, "脚本路由异常: " + t.getMessage());
            }
        }

        // ---- 静态页 ----
        // 扫码进来先看到订阅管理 —— 用户扫这个码的目的通常是"把源加进去"，
        // 不是"拿手机当遥控器"。遥控页放在 /remote。
        String name;
        if (path.equals("/") || path.equals("/subs") || path.equals("/subscriptions")) {
            name = "subscriptions.html";
        } else if (path.equals("/remote") || path.equals("/control")) {
            name = "index.html";
        } else if (path.equals("/scripts")) {
            name = "scripts.html";
        } else if (path.equals("/channels")) {
            name = "channels.html";
        } else {
            name = path.substring(1);
        }
        if (name.contains("..")) { send(out, 403, "text/plain", "forbidden"); return; }
        byte[] data = readAsset(name);
        if (data == null) {
            send(out, 404, "text/plain; charset=utf-8", "not found");
            return;
        }
        send(out, 200, mimeOf(name), data);
    }

    private static String param(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        String[] parts = query.split("&");
        for (String p : parts) {
            int i = p.indexOf('=');
            if (i <= 0) continue;
            if (key.equals(p.substring(0, i))) {
                try {
                    return java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8");
                } catch (Throwable t) {
                    return p.substring(i + 1);
                }
            }
        }
        return "";
    }

    private byte[] readAsset(String name) {
        AssetManager am = appCtx.getAssets();
        InputStream in = null;
        try {
            in = am.open(ASSET_DIR + name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "text/plain; charset=utf-8";
    }

    private void send(OutputStream out, int code, String mime, String body) throws IOException {
        send(out, code, mime, body == null ? new byte[0] : body.getBytes("UTF-8"));
    }

    /**
     * 只发响应头，正文由调用方流式写。
     *
     * <p>不带 Content-Length：分片大小事先不知道，硬写会截断。
     * HTTP/1.1 里用 {@code Connection: close} 让客户端读到 EOF 即可。
     */
    private void sendStreamHead(OutputStream out, int code, String mime) throws IOException {
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.flush();
    }

    private void send(OutputStream out, int code, String mime, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 404 ? "Not Found" : "Error") + "\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
    }

    // ---------------- 工具 ----------------

    /** 逐字节读一行。用 InputStream 而不是 BufferedReader，避免它把后面的 body 也缓冲走。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            bos.write(c);
            if (bos.size() > 8192) break;
        }
        if (c == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), "UTF-8");
    }

    /** 极简 JSON 取值（够读手机发来的小对象）。 */
    /** 取查询参数，没找到返回 null。 */
    private static String qparam(String query, String key) {
        if (query == null || query.isEmpty() || key == null) return null;
        for (String p : query.split("&")) {
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i);
            if (!k.equals(key)) continue;
            String v = i < 0 ? "" : p.substring(i + 1);
            try { return java.net.URLDecoder.decode(v, "UTF-8"); }
            catch (Exception e) { return v; }
        }
        return null;
    }

    /** JSON 字符串转义。 */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static String jsonField(String json, String key) {
        if (json == null) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            return o.optString(key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static org.json.JSONObject safeJson(String body) {
        try {
            return new org.json.JSONObject(body);
        } catch (Throwable t) {
            return new org.json.JSONObject();
        }
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': break;
                default: sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 从表单/查询串取值，够 /api/cmd?type=xx 这种用。 */
    public static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
            } catch (Throwable ignored) { }
        }
        return m;
    }
}
