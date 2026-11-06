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

    /** 脚本返回 ttlSec 时缓存结果，避免每个分片都重新调一次上游。 */
    private static final class CacheEntry {
        String url;
        String body;
        String contentType;
        int status;
        long expireAt;
    }

    private final java.util.Map<String, CacheEntry> respCache =
            new java.util.concurrent.ConcurrentHashMap<>();

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
                // m.start() 指向 ':id' 前面那个 '/'，必须把它一起保留在
                // 字面量里。原来切到 m.start() 就停了，等于把 '/' 吃掉，
                // 生成 ^\Q/migu\E([^/]+)$ —— 结果 /migu.m3u 能匹配，
                // 真正的 /migu/1 反而匹配不上。
                sb.append(Pattern.quote(pattern.substring(last, m.start() + 1)));
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

    /** 脚本有没有声明 exports.proxy —— 有的话它返回的是"真实地址"，由框架转发。 */
    private static boolean hasProxyExport(File f) {
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 65536)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { in.read(b); } finally { in.close(); }
            String src = new String(b, "UTF-8");
            // 先剥掉注释再判断。否则脚本里写一行
            //   // exports.proxy = function(req){...}  ← 示例
            // 就会被误判成代理脚本，走错分支。
            src = src.replaceAll("(?s)/\\*.*?\\*/", " ")
                     .replaceAll("//[^\n]*", " ");
            return src.contains("exports.proxy")
                    || src.matches("(?s).*\\bfunction\\s+proxy\\s*\\(.*");
        } catch (Throwable t) {
            return false;
        }
    }

    // ================= 执行 =================

    /**
     * 处理一个请求。
     *
     * <p>成功时写 302（main 返回地址）或 200 + 正文（handler）。
     * 失败写 502，并记一条脚本日志 —— 用户在手机页面上能看到原因。
     */
    public void handle(String path, String query,
                       java.util.Map<String, String> reqHeaders,
                       OutputStream out) {
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
            String qs = (query == null || query.isEmpty() ? "" : "?" + query);
            item.put("url", path + qs);
            item.put("path", path);
            item.put("name", r.name);
            // pageUrl：完整请求地址，脚本拿它拼同源链接用（端口可能切到 18900）
            item.put("pageUrl", "http://127.0.0.1:18899" + path + qs);

            JSONObject params = parseQuery(query);
            for (int i = 0; i < r.paramNames.size(); i++) {
                String v = i + 1 <= m.groupCount() ? m.group(i + 1) : "";
                params.put(r.paramNames.get(i), safeDecode(v));
            }
            item.put("params", params);

            // id：第一个路径参数。
            //
            // 以前只有 params.<name>，脚本里写 item.id 永远拿到 undefined ——
            // README 把 id 列为 item 的字段，实际根本没塞进去。
            if (r.paramNames.size() > 0) {
                String first = params.optString(r.paramNames.get(0), "");
                if (!first.isEmpty()) item.put("id", first);
            }
            // 把真实请求头交给脚本。以前这里是 new JSONObject()，
            // 永远是空的 —— 需要 Referer/Cookie 的源在脚本里没法处理。
            JSONObject hdrs = new JSONObject();
            if (reqHeaders != null) {
                for (java.util.Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    try { hdrs.put(e.getKey(), e.getValue()); } catch (Throwable ignored) { }
                }
            }
            item.put("headers", hdrs);
        } catch (Exception ignored) { }

        File f = new File(r.file);

        // 脚本声明了 exports.proxy：返回的是"真实地址"，由框架拉取并转发二进制。
        // 这条路径下播放器永远不知道真实地址 —— 解密、拼接、防盗链都在脚本里。
        boolean isProxy = hasProxyExport(f);

        // 缓存 key 用 脚本名 + 完整路径 + query，不同频道互不干扰
        final String cacheKey = r.name + "|" + path
                + "|" + (query == null ? "" : query);
        try {
            CacheEntry ce = respCache.get(cacheKey);
            if (ce != null) {
                if (ce.expireAt > System.currentTimeMillis()) {
                    if (ce.body != null) {
                        write(out, ce.status <= 0 ? 200 : ce.status,
                                ce.contentType != null ? ce.contentType
                                        : "application/vnd.apple.mpegurl; charset=utf-8",
                                ce.body);
                        return;
                    }
                    if (ce.url != null) {
                        writeRedirect(out, ce.url);
                        return;
                    }
                } else {
                    respCache.remove(cacheKey);
                }
            }
        } catch (Throwable ignored) { }

        long t0 = System.currentTimeMillis();
        ScriptEngine.Result res = engine.invoke(f, isProxy ? "proxy" : "main", item);
        long cost = System.currentTimeMillis() - t0;

        if (!res.ok) {
            ScriptLog.error(r.name, "解析失败: " + res.error);
            write(out, 502, "text/plain; charset=utf-8",
                  "script error: " + (res.error == null ? "unknown" : res.error));
            return;
        }

        ScriptLog.info(r.name, "解析成功 · " + cost + "ms"
                + (res.url != null ? (" · " + res.url) : " · 返回正文"));
        // 正常脚本几十毫秒。超过 5 秒基本是死循环或者上游卡死，
        // QuickJS 没有中断接口，只能靠这条日志事后定位是哪个脚本。
        if (cost > 5000) {
            ScriptLog.warn(r.name, "执行耗时 " + cost + "ms，检查是否有死循环或上游超时");
        }

        // 代理模式：流式拉取并原样吐回去
        if (isProxy && res.url != null) {
            streamBinary(res.url, res.headers, res.proxy, out);
            return;
        }

        // 脚本声明了 ttlSec 才缓存。以前这个字段返回了也没人读，
        // 每个分片请求都要重新走一遍签名流程（咪咕那种要 800ms）。
        if (res.ttlSec > 0) {
            try {
                CacheEntry ce = new CacheEntry();
                ce.url = res.url;
                ce.body = res.body;
                ce.contentType = res.contentType;
                ce.status = res.status;
                ce.expireAt = System.currentTimeMillis() + res.ttlSec * 1000L;
                respCache.put(cacheKey, ce);
                // 顺手清掉过期的，别让表无限长
                if (respCache.size() > 200) {
                    long now = System.currentTimeMillis();
                    java.util.Iterator<java.util.Map.Entry<String, CacheEntry>> it =
                            respCache.entrySet().iterator();
                    while (it.hasNext()) {
                        if (it.next().getValue().expireAt <= now) it.remove();
                    }
                }
            } catch (Throwable ignored) { }
        }

        if (res.body != null) {
            String ct = res.contentType != null ? res.contentType
                    : "application/vnd.apple.mpegurl; charset=utf-8";
            write(out, res.status <= 0 ? 200 : res.status, ct, res.body);
            return;
        }

        // main 模式：302 过去，请求头交给播放器（VLC 只认 Referer/UA/Cookie）
        writeRedirect(out, res.url);
    }

    /**
     * 把脚本算出来的地址拉下来，原样转发给播放器。
     *
     * <p>必须流式：一个 ts 分片 500KB~2MB，4K 的能到 5MB，
     * 整个读进内存会让延迟和内存都炸。
     *
     * <p>请求头只带脚本给的那几个 —— 框架不塞任何默认 UA。
     */
    private void streamBinary(String url, Map<String, String> headers,
                              String proxySpec, OutputStream out) {
        java.net.HttpURLConnection conn = null;
        try {
            // 只认脚本这次指定的，不继承设置项 —— 那个是播放器代理
            java.net.Proxy proxy = (proxySpec == null || proxySpec.isEmpty())
                    ? null : NetProxy.parse(proxySpec);
            java.net.URL u = new java.net.URL(url);
            conn = proxy != null
                    ? (java.net.HttpURLConnection) u.openConnection(proxy)
                    : (java.net.HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    try { conn.setRequestProperty(e.getKey(), e.getValue()); }
                    catch (Throwable ignored) { }
                }
            }

            int code = conn.getResponseCode();
            if (code >= 400) {
                write(out, 502, "text/plain; charset=utf-8", "upstream " + code);
                return;
            }
            String ct = conn.getContentType();
            if (ct == null || ct.isEmpty()) ct = "application/octet-stream";
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
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 32 * 1024 * 1024) break;
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
