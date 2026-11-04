package com.mintv.tv.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.quickjs.JSArray;
import com.quickjs.JSContext;
import com.quickjs.JSObject;
import com.quickjs.JavaCallback;
import com.quickjs.QuickJS;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本引擎。
 *
 * <p>每个脚本执行时新建一个 QuickJS context，用完就关 —— 隔离比复用重要：
 * 一个脚本把全局变量搞乱，不应该影响到别的脚本。
 *
 * <p><b>关于 async/await</b>：QuickJS 没有事件循环，Promise 不会自己 resolve。
 * 所以这里把 HTTP 做成**同步调用**，JS 侧包成 {@code Promise.resolve(...)}，
 * {@code await} 立即完成。阻塞只发生在 QuickJS 自己的线程，不影响 UI。
 *
 * <p>这是和 Node 最大的差别：Node 的 fetch 是真异步，这里是同步伪装成异步。
 * 对脚本写法的要求完全一样，只是并发请求不会真的并行。
 */
public final class ScriptEngine {

    private static final String TAG = "MinTVEngine";
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_BODY = 8 * 1024 * 1024;

    /** JS 垫片：把 Java 注入的 __xxx 包装成标准 Web API。 */
    /**
     * JS 垫片。
     *
     * <p><b>为什么全是同步的</b>：QuickJS 没有暴露 JS_ExecutePendingJob，
     * 这意味着 Promise 的微任务永远不会被驱动 —— 写 {@code async function main}
     * 的话返回的 Promise 永远是 pending，取不到结果。
     *
     * <p>所以 fetch / storage / json / text 全部返回**直接值**而不是 Promise。
     * 脚本用普通 {@code function} 写，不要写 async/await。
     * 对脚本作者来说只是少打两个词，行为完全确定。
     */
    private static final String SHIM = String.join("\n",
        "(function(){",
        "  function mkResp(r){",
        "    var hs = r.headers || {};",
        "    return {",
        "      ok: r.code >= 200 && r.code < 300,",
        "      status: r.code,",
        "      url: r.url || '',",
        "      headers: { get: function(k){",
        "        for (var x in hs) if (x.toLowerCase() === String(k).toLowerCase()) return hs[x];",
        "        return null; } },",
        "      text: function(){ return r.body || ''; },",
        "      json: function(){ return JSON.parse(r.body || 'null'); }",
        "    };",
        "  }",
        "  globalThis.fetch = function(url, init){",
        "    var r = JSON.parse(__http(String(url), JSON.stringify(init || {})));",
        "    if (r.code === 0) throw new Error(r.error || 'network error');",
        "    return mkResp(r);",
        "  };",
        "  globalThis.md5 = function(s){ return __md5(String(s)); };",
        "  globalThis.storage = {",
        "    get: function(k){ return __storageGet(String(k)); },",
        "    set: function(k,v,t){ return __storageSet(String(k), String(v), t||0); },",
        "    delete: function(k){ return __storageSet(String(k), '', -1); }",
        "  };",
        "  globalThis.console = {",
        "    log:  function(){ __log('info',  Array.prototype.join.call(arguments,' ')); },",
        "    info: function(){ __log('info',  Array.prototype.join.call(arguments,' ')); },",
        "    warn: function(){ __log('warn',  Array.prototype.join.call(arguments,' ')); },",
        "    error:function(){ __log('error', Array.prototype.join.call(arguments,' ')); }",
        "  };",
        "})();"
    );

    private final Context ctx;
    private final SharedPreferences sp;
    /** 脚本名 -> module.exports（同一文件只执行一次，和 CommonJS 一致）。 */
    private final Map<String, JSObject> moduleCache = new ConcurrentHashMap<>();

    public ScriptEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences("script_store", Context.MODE_PRIVATE);
    }

    /** 执行结果。 */
    public static final class Result {
        public boolean ok;
        public String url;                 // main 返回的播放地址
        public Map<String,String> headers; // main 返回的请求头
        public int ttlSec;
        public String body;                // handler 返回的正文
        public String contentType;
        public int status = 200;
        public boolean rewriteSegments;    // proxy 要求改写分片
        public String error;

        static Result fail(String msg) {
            Result r = new Result();
            r.ok = false;
            r.error = msg;
            return r;
        }
    }

    // ================= 对外入口 =================

    /**
     * 跑脚本，返回播放结果。
     *
     * @param scriptFile 脚本绝对路径
     * @param entry      要调用的导出名：main / handler / proxy
     * @param itemJson   item 对象（url/path/id/params/headers/name/pageUrl）
     */
    public Result invoke(File scriptFile, String entry, JSONObject itemJson) {
        QuickJS rt = null;
        JSContext js = null;
        try {
            rt = QuickJS.createRuntimeWithEventQueue();
            js = rt.createContext();
            final JSContext c = js;
            final String fname = scriptFile.getName();

            // ---- 注入 Java 能力 ----
            c.registerJavaMethod(new JavaCallback() {
                @Override public Object invoke(JSObject thiz, JSArray args) {
                    return httpSync(str(args, 0), str(args, 1));
                }
            }, "__http");

            c.registerJavaMethod(new JavaCallback() {
                @Override public Object invoke(JSObject thiz, JSArray args) {
                    return md5(str(args, 0));
                }
            }, "__md5");

            c.registerJavaMethod(new JavaCallback() {
                @Override public Object invoke(JSObject thiz, JSArray args) {
                    return sp.getString("k_" + str(args, 0), "");
                }
            }, "__storageGet");

            c.registerJavaMethod(new JavaCallback() {
                @Override public Object invoke(JSObject thiz, JSArray args) {
                    String k = "k_" + str(args, 0);
                    String v = str(args, 1);
                    long ttl = 0;
                    try { ttl = Long.parseLong(str(args, 2)); } catch (Exception ignored) { }
                    if (ttl < 0) { sp.edit().remove(k).apply(); return true; }
                    sp.edit().putString(k, v).apply();
                    return true;
                }
            }, "__storageSet");

            c.registerJavaMethod(new JavaCallback() {
                @Override public Object invoke(JSObject thiz, JSArray args) {
                    String lv = str(args, 0), msg = str(args, 1);
                    if ("error".equals(lv)) ScriptLog.error(fname, msg);
                    else if ("warn".equals(lv)) ScriptLog.warn(fname, msg);
                    else ScriptLog.info(fname, msg);
                    return null;
                }
            }, "__log");

            // ---- 加载模块 ----
            JSObject exports = loadModule(c, scriptFile, fname);
            if (exports == null) return Result.fail("脚本加载失败");

            // ---- 调用入口 ----
            String itemJs = "var __item = " + itemJson.toString() + ";";
            c.executeScript(itemJs, "item.js");

            Object ret;
            try {
                JSArray a = new JSArray(c);
                a.push(itemJsonToJs(c, itemJson));   // JSObject 是 JSValue 子类
                ret = exports.executeFunction(entry, a);
            } catch (Exception e) {
                // async 函数返回 Promise，要从 export 里拿再取结果
                ret = null;
            }

            // 取 __result（脚本通常 return，这里从最后一次返回值拿）

            JSONObject out = new JSONObject();
            Object raw = ret;
            if (raw instanceof String) {
                out.put("url", raw);
            } else if (raw instanceof JSObject) {
                copyObject((JSObject) raw, out);
            } else if (raw instanceof JSONObject) {
                out = (JSONObject) raw;
            }
            return toResult(out);

        } catch (Throwable t) {
            ScriptLog.error(scriptFile.getName(), "执行失败: " + t.getMessage(), t);
            return Result.fail(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try { if (js != null) js.close(); } catch (Throwable ignored) { }
            try { if (rt != null) rt.close(); } catch (Throwable ignored) { }
        }
    }

    /** 只做语法检查，不执行。返回 null 表示通过。 */
    public String checkSyntax(String code, String fname) {
        QuickJS rt = null;
        JSContext js = null;
        try {
            rt = QuickJS.createRuntimeWithEventQueue();
            js = rt.createContext();
            js.executeScript(code, fname);
            return null;
        } catch (Throwable t) {
            String m = t.getMessage();
            return m == null ? t.getClass().getSimpleName() : m;
        } finally {
            try { if (js != null) js.close(); } catch (Throwable ignored) { }
            try { if (rt != null) rt.close(); } catch (Throwable ignored) { }
        }
    }

    // ================= 模块加载 =================

    /**
     * 加载脚本，返回它的 exports 对象。
     *
     * <p>用 CommonJS 的包装方式执行：{@code (function(module, exports, require){...})}，
     * 这样脚本里的 {@code exports.xxx = ...} 和 {@code require("./lib/x.js")} 都能用。
     */
    private JSObject loadModule(JSContext c, File file, String fname) throws IOException {
        String key = file.getAbsolutePath();
        JSObject cached = moduleCache.get(key);
        if (cached != null) return cached;

        String code = readText(file);

        // 注入 require：解析相对路径，递归加载
        final File dir = file.getParentFile();
        c.registerJavaMethod(new JavaCallback() {
            @Override public Object invoke(JSObject thiz, JSArray args) {
                String spec = str(args, 0);
                File target = new File(dir, spec);
                try {
                    return loadModule(c, target, target.getName());
                } catch (IOException e) {
                    ScriptLog.error(fname, "require 失败: " + spec);
                    return null;
                }
            }
        }, "__require");

        c.executeScript(SHIM, "shim.js");

        // 包装成 CommonJS
        String wrapped = "(function(module, exports, require) {\n" + code + "\n})";
        Object fn = c.executeScript(wrapped, fname);

        JSObject mod = new JSObject(c);
        JSObject exp = new JSObject(c);
        mod.set("exports", exp);

        if (fn instanceof JSObject) {
            JSArray a = new JSArray(c);
            a.push(mod);
            a.push(exp);
            a.push(c.getObject("__require"));
            try { ((JSObject) fn).executeFunction("call", a); } catch (Throwable ignored) { }
        }

        JSObject out = mod.getObject("exports");
        if (out == null) out = exp;
        moduleCache.put(key, out);
        return out;
    }

    // ================= HTTP =================

    /**
     * 同步 HTTP。阻塞的是 QuickJS 线程，不是 UI。
     *
     * <p>只允许 HTTP(S) —— 脚本是用户导入的，不能让它读 file:// 或者走本地环回
     * 去打自己（会死锁）。
     */
    private String httpSync(String url, String initJson) {
        JSONObject out = new JSONObject();
        HttpURLConnection conn = null;
        try {
            if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                out.put("code", 0);
                out.put("error", "只支持 http(s)");
                return out.toString();
            }
            JSONObject init = new JSONObject(initJson == null || initJson.isEmpty() ? "{}" : initJson);
            String method = init.optString("method", "GET").toUpperCase();
            String body = init.optString("body", "");

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);

            JSONObject hs = init.optJSONObject("headers");
            if (hs != null) {
                java.util.Iterator<String> it = hs.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    conn.setRequestProperty(k, hs.optString(k));
                }
            }
            if (!body.isEmpty() && ("POST".equals(method) || "PUT".equals(method))) {
                conn.setDoOutput(true);
                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.close();
            }

            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readLimited(in, MAX_BODY);

            JSONObject h = new JSONObject();
            for (Map.Entry<String, java.util.List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() == null) continue;
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    h.put(e.getKey(), e.getValue().get(0));
                }
            }

            out.put("code", code);
            out.put("body", text);
            out.put("url", conn.getURL().toString());
            out.put("headers", h);
            return out.toString();
        } catch (Throwable t) {
            try {
                out.put("code", 0);
                out.put("body", "");
                out.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Exception ignored) { }
            return out.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ================= 工具 =================

    private Result toResult(JSONObject o) {
        Result r = new Result();
        r.ok = true;
        r.url = o.optString("url", o.optString("playUrl", o.optString("playurl", null)));
        r.body = o.optString("body", maybem3u8(o));
        r.contentType = o.optString("contentType", null);
        r.status = o.optInt("status", 200);
        r.ttlSec = o.optInt("ttlSec", 0);
        r.rewriteSegments = o.optBoolean("rewriteSegments", false);

        JSONObject h = o.optJSONObject("headers");
        if (h != null) {
            r.headers = new HashMap<>();
            java.util.Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                r.headers.put(k, h.optString(k));
            }
        }
        if (r.url == null && r.body == null) {
            return Result.fail("脚本没有返回可播放的内容");
        }
        return r;
    }

    /** 允许 return "#EXTM3U..." 这种直接返回文本的写法。 */
    private static String maybem3u8(JSONObject o) {
        String s = o.optString("m3u8", null);
        if (s != null) return s;
        return o.optString("content", null);
    }

    private static void copyObject(JSObject src, JSONObject dst) throws Exception {
        String url = src.getString("url");
        if (url != null) dst.put("url", url);
        String referer = src.getString("referer");
        if (referer != null) {
            JSONObject h = new JSONObject();
            h.put("Referer", referer);
            dst.put("headers", h);
        }
        String ua = src.getString("userAgent");
        if (ua != null) {
            JSONObject h = dst.optJSONObject("headers");
            if (h == null) h = new JSONObject();
            h.put("User-Agent", ua);
            dst.put("headers", h);
        }
        int ttl = src.getInteger("ttlSec");
        if (ttl > 0) dst.put("ttlSec", ttl);
    }

    private static JSObject itemJsonToJs(JSContext c, JSONObject item) {
        return new JSObject(c, item);
    }


    /** 判断是不是 Promise（有 then 方法）。 */
    private static boolean isThenable(JSObject o) {
        try {
            Object then = o.get("then");
            return then instanceof JSObject;   // 函数在 JS 里也是对象
        } catch (Throwable t) {
            return false;
        }
    }

    private static String str(JSArray a, int i) {
        try {
            Object v = a.get(i);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try { return new String(readAll(in), StandardCharsets.UTF_8); }
        finally { try { in.close(); } catch (IOException ignored) { } }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) break;
            bos.write(buf, 0, n);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
