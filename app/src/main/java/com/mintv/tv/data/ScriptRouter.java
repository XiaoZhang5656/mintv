package com.mintv.tv.data;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本路由：把 {@code /cctv/cctv3} 这样的请求分发给对应脚本执行。
 *
 * <p>匹配的是脚本里 {@code exports.path} 声明的模式，语法和 Express 一样：
 * {@code /cctv/:id}、{@code /raw/*}。
 *
 * <p>扫描发生在每次请求时（而不是缓存路由表）—— 脚本可能刚被网页改过，
 * 立刻生效比省那几毫秒重要。目录里就几个文件，扫码开销可以忽略。
 */
public final class ScriptRouter {

    private static final String TAG = "MinTVRouter";

    private final Context ctx;
    private final ScriptStore store;
    private final ScriptEngine engine;

    public ScriptRouter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.store = new ScriptStore(this.ctx);
        this.engine = new ScriptEngine(this.ctx);
    }

    public ScriptStore store() { return store; }

    /** 一条编译好的路由。 */
    private static final class Route {
        String file;          // 脚本绝对路径
        String name;
        Pattern regex;
        List<String> paramNames = new ArrayList<>();
        String entry = "main";
        int priority;
    }

    // ================= 匹配 =================

    /** 这个路径有没有脚本能处理。 */
    public boolean canHandle(String path) {
        return match(path) != null;
    }

    private Route match(String path) {
        List<ScriptStore.Item> items = store.list();
        Route best = null;
        for (ScriptStore.Item it : items) {
            if (it.kind == ScriptStore.Kind.MODULE) continue;
            for (String pat : it.routes) {
                Route r = compile(it, pat);
                if (r == null) continue;
                if (!r.regex.matcher(path).matches()) continue;
                if (best == null || r.priority > best.priority) best = r;
            }
        }
        return best;
    }

    private Route compile(ScriptStore.Item it, String pattern) {
        try {
            Route r = new Route();
            r.name = it.name;
            r.priority = it.priority;
            File f = new File(store.dir(), it.name);
            if (!f.isFile()) return null;
            r.file = f.getAbsolutePath();

            // 有 proxy 导出的走代理，否则走 main
            r.entry = "main";

            StringBuilder sb = new StringBuilder("^");
            Matcher m = Pattern.compile("/:([A-Za-z_][A-Za-z0-9_]*)").matcher(pattern);
            int last = 0;
            while (m.find()) {
                sb.append(Pattern.quote(pattern.substring(last, m.start())));
                sb.append("([^/]+)");
                r.paramNames.add(m.group(1));
                last = m.end();
            }
            String tail = pattern.substring(last);
            if (tail.contains("*")) {
                int i = tail.indexOf('*');
                sb.append(Pattern.quote(tail.substring(0, i))).append(".*");
            } else {
                sb.append(Pattern.quote(tail));
            }
            sb.append("$");
            r.regex = Pattern.compile(sb.toString());
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    // ================= 执行 =================

    /**
     * 处理一个请求。
     *
     * <p>成功时写 302（main 返回地址）或 200 + 正文（handler）。
     * 失败写 502，并记一条脚本日志 —— 用户在手机页面上能看到原因。
     */
    public void handle(String path, String query, OutputStream out) {
        // 分片转发：脚本把 m3u8 里的 ts 地址改写成 /seg/xxx?src=<真实地址>，
        // 播放器来请求这里，宿主带代理去回源，原样吐回去。
        // 播放器全程只跟 127.0.0.1 打交道，所以上游是 HTTP / SOCKS5 /
        // 内网，对它都没区别。
        if (path.startsWith("/seg/")) {
            proxySegment(query, out);
            return;
        }
        Route r = match(path);
        if (r == null) {
            write(out, 404, "text/plain; charset=utf-8", "no script route: " + path);
            return;
        }

        Matcher m = r.regex.matcher(path);
        if (!m.matches()) {
            write(out, 404, "text/plain; charset=utf-8", "no match");
            return;
        }

        JSONObject item = new JSONObject();
        try {
            item.put("url", path + (query == null || query.isEmpty() ? "" : "?" + query));
            item.put("path", path);
            item.put("name", r.name);
            JSONObject params = parseQuery(query);
            for (int i = 0; i < r.paramNames.size(); i++) {
                String v = i + 1 <= m.groupCount() ? m.group(i + 1) : "";
                params.put(r.paramNames.get(i), safeDecode(v));
            }
            item.put("params", params);
            item.put("headers", new JSONObject());
        } catch (Exception ignored) { }

        long t0 = System.currentTimeMillis();
        ScriptEngine.Result res = engine.invoke(new File(r.file), r.entry, item);
        long cost = System.currentTimeMillis() - t0;

        if (!res.ok) {
            ScriptLog.error(r.name, "解析失败: " + res.error);
            write(out, 502, "text/plain; charset=utf-8",
                  "script error: " + (res.error == null ? "unknown" : res.error));
            return;
        }

        ScriptLog.info(r.name, "解析成功 · " + cost + "ms"
                + (res.url != null ? (" · " + res.url) : " · 返回正文"));

        if (res.body != null) {
            String ct = res.contentType != null ? res.contentType
                    : "application/vnd.apple.mpegurl; charset=utf-8";
            write(out, res.status <= 0 ? 200 : res.status, ct, res.body);
            return;
        }

        // 有地址：302 过去，请求头交给播放器（VLC 只认 Referer/UA/Cookie）
        writeRedirect(out, res.url);
    }

    /**
     * 分片转发。
     *
     * <p>必须**流式**转发：一个 ts 分片 500KB~2MB，4K 的能到 5MB，
     * 整个读进内存再写回去会让延迟和内存都炸。
     */
    private void proxySegment(String query, OutputStream out) {
        String src = qparam(query, "src");
        if (src == null || src.isEmpty()) {
            write(out, 400, "text/plain; charset=utf-8", "missing src");
            return;
        }
        java.net.HttpURLConnection conn = null;
        try {
            java.net.Proxy proxy = NetProxy.get(ctx);
            java.net.URL u = new java.net.URL(src);
            conn = proxy != null
                    ? (java.net.HttpURLConnection) u.openConnection(proxy)
                    : (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);

            // ⚠ 不写任何默认请求头。
            // 有些源恰恰不允许带 UA，硬塞一个 Mozilla 会被 403；
            // 带什么头是脚本的决定，框架只负责搬运。
            // 脚本想带就在改写地址时带上：
            //   /seg/xx?src=...&ua=<编码后的UA>&ref=<编码后的Referer>
            String ua = qparam(query, "ua");
            if (ua != null && !ua.isEmpty()) conn.setRequestProperty("User-Agent", ua);
            String ref = qparam(query, "ref");
            if (ref != null && !ref.isEmpty()) conn.setRequestProperty("Referer", ref);
            String ck = qparam(query, "ck");
            if (ck != null && !ck.isEmpty()) conn.setRequestProperty("Cookie", ck);

            int code = conn.getResponseCode();
            if (code >= 400) {
                write(out, 502, "text/plain; charset=utf-8", "upstream " + code);
                return;
            }
            String ct = conn.getContentType();
            if (ct == null || ct.isEmpty()) ct = "video/mp2t";
            long len = conn.getContentLength();

            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 200 OK\r\n");
            h.append("Content-Type: ").append(ct).append("\r\n");
            if (len > 0) h.append("Content-Length: ").append(len).append("\r\n");
            h.append("Access-Control-Allow-Origin: *\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes("UTF-8"));

            java.io.InputStream in = conn.getInputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 32 * 1024 * 1024) break;   // 单分片上限，防异常流
            }
            out.flush();
            try { in.close(); } catch (Throwable ignored) { }
        } catch (Throwable t) {
            try { write(out, 502, "text/plain; charset=utf-8", "proxy failed"); }
            catch (Throwable ignored) { }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String qparam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String p : query.split("&")) {
            int i = p.indexOf('=');
            if (i < 0) continue;
            if (!p.substring(0, i).equals(key)) continue;
            return safeDecode(p.substring(i + 1));
        }
        return null;
    }

    // ================= 输出 =================

    private void write(OutputStream out, int code, String ct, String body) {
        try {
            byte[] b = body == null ? new byte[0] : body.getBytes("UTF-8");
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
            h.append("Content-Type: ").append(ct).append("\r\n");
            h.append("Content-Length: ").append(b.length).append("\r\n");
            h.append("Access-Control-Allow-Origin: *\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes("UTF-8"));
            out.write(b);
            out.flush();
        } catch (Exception ignored) { }
    }

    private void writeRedirect(OutputStream out, String location) {
        try {
            String h = "HTTP/1.1 302 Found\r\n"
                    + "Location: " + location + "\r\n"
                    + "Content-Length: 0\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(h.getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) { }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 302: return "Found";
            case 404: return "Not Found";
            case 502: return "Bad Gateway";
            default: return "OK";
        }
    }

    // ================= 参数 =================

    private static JSONObject parseQuery(String query) {
        JSONObject o = new JSONObject();
        if (query == null || query.isEmpty()) return o;
        for (String p : query.split("&")) {
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i);
            String v = i < 0 ? "" : p.substring(i + 1);
            try { o.put(safeDecode(k), safeDecode(v)); } catch (Exception ignored) { }
        }
        return o;
    }

    private static String safeDecode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (UnsupportedEncodingException e) { return s; }
        catch (Exception e) { return s; }
    }
}
