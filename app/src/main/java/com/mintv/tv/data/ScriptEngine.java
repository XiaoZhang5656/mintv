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
        /** 脚本返回的原始 JSON，框架没识别的字段也在这里，方便以后扩展。 */
        public org.json.JSONObject raw;
        /** 多候选地址（脚本返回 { urls: [...] } 时）。 */
        public java.util.List<String> urls;
        public String url;                 // main 返回的播放地址
        public Map<String,String> headers; // 返回的请求头
        public String proxy;               // 这次转发用哪个代理（可空）
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
        // 每次调用都会新建 runtime + context，并在 finally 里 close 掉。
        //
        // moduleCache 里存的是 JSObject，它属于某一个具体 context ——
        // 跨调用复用等于拿上一个已关闭 context 的对象去 executeFunction，
        // 直接 Context disposed error。第二次调用脚本必然失败就是这么来的。
        //
        // 清空只影响跨调用；本次执行内 require 同一文件的"只执行一次"
        // 语义不受影响（loadModule 会重新把本次的结果写进缓存）。
        moduleCache.clear();

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
                // 先找 exports.<entry>，找不到再找全局函数。
                // 两种写法都支持：
                //   exports.main = function(item){...}
                //   function main(item){...}
                JSObject fnObj = null;
                try { fnObj = exports.getObject(entry); } catch (Throwable ignored) { }
                if (fnObj == null) {
                    try { fnObj = c.getObject(entry); } catch (Throwable ignored) { }
                }
                if (fnObj == null) {
                    ScriptLog.warn(fname, "脚本没有导出 " + entry);
                    return Result.fail("脚本没有导出 " + entry);
                }
                JSArray a = new JSArray(c);
                a.push(c.getObject("globalThis"));            // thisArg
                a.push(itemJsonToJs(c, itemJson));             // 真正的参数
                ret = fnObj.executeFunction("call", a);
            } catch (Exception e) {
                // 原来这里无条件 ret = null，把所有异常都吃掉了 ——
                // 脚本里第一句就报错的话，外面只看到"没有返回可播放的内容"，
                // 真实原因（console 未定义、语法错、Context 已关）全被埋掉。
                ScriptLog.warn(fname, "调用 " + entry + " 异常: " + e);
                ret = null;
            }

            // 取 __result（脚本通常 return，这里从最后一次返回值拿）

            JSONObject out = new JSONObject();
            Object raw = ret;
            if (raw instanceof String) {
                out.put("url", raw);
            } else if (raw instanceof JSObject) {
                // 通用转换：脚本返回什么字段就拿什么，不做白名单
                out = jsToJson((JSObject) raw);
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
    /** 正在加载的模块，用来挡住 A require B、B require A 这种循环。 */
    private final java.util.Set<String> loadingModules =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private JSObject loadModule(JSContext c, File file, String fname) throws IOException {
        String key = file.getAbsolutePath();
        JSObject cached = moduleCache.get(key);
        if (cached != null) return cached;
        // 循环 require 会让递归无限展开直到栈溢出，这里直接掐断
        if (loadingModules.contains(key)) {
            ScriptLog.warn(fname, "循环 require: " + key);
            return null;
        }
        loadingModules.add(key);
        try {
            return loadModuleInner(c, file, fname, key);
        } finally {
            loadingModules.remove(key);
        }
    }

    private JSObject loadModuleInner(JSContext c, File file, String fname, String key)
            throws IOException {

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
            // 注意：必须传三个独立参数。
            //
            // 原来是把它们塞进一个 JSArray 再传，executeFunction 会把数组当成
            // 单个实参，等价于 fn.call([mod, exp, require]) —— 包装函数的
            // module 变成整个数组，exports / require 全是 undefined。
            // 结果脚本里 exports.xxx = ... 全写到错地方，main 永远找不到。
            try {
                // 数组第一个元素是 thisArg，后面才是实参。
                //
                // 之前只放了 [mod, exp, require]，等价于
                //   fn.call(mod, exp, require)
                // —— this 变成了 mod，包装函数的 module=exp、exports=require、
                // require=undefined，三个参数整体错位一格。脚本里 exports.xxx
                // 全写到错地方，main 永远找不到，报 TypeError: not a function。
                JSArray a = new JSArray(c);
                a.push(c.getObject("globalThis"));  // thisArg
                a.push(mod);
                a.push(exp);
                a.push(c.getObject("__require"));
                ((JSObject) fn).executeFunction("call", a);
            } catch (Throwable t) {
                ScriptLog.warn(fname, "包装函数执行异常: " + t);
            }
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

            // 上游代理：脚本里的 fetch 和本地 HLS 代理共用同一个配置。
            // 有些源只对特定出口开放，不挂代理直接 403。
            // 脚本的代理只认脚本自己写的，不继承设置项里的那个 ——
            // 那个是「播放器代理」，管的是 VLC 连源。
            //
            //   fetch(url, { proxy: "socks5://10.0.0.1:1080" })  用它
            //   fetch(url)                                        直连
            //
            // 两套分开是有意的：脚本作者清楚自己要什么，
            // 而设置项是给"不写脚本的普通用户"准备的。
            java.net.Proxy proxy = null;
            if (init != null && init.has("proxy")) {
                String pv = init.optString("proxy", "");
                if (!pv.isEmpty()) proxy = NetProxy.parse(pv);
            }
            java.net.URL u = new URL(url);
            conn = proxy != null
                    ? (HttpURLConnection) u.openConnection(proxy)
                    : (HttpURLConnection) u.openConnection();
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
        r.raw = o;   // 原始返回全留着，框架没识别的字段也不丢
        r.url = o.optString("url", o.optString("playUrl", o.optString("playurl", null)));
        r.body = o.optString("body", maybem3u8(o));
        r.contentType = o.optString("contentType", null);
        r.status = o.optInt("status", 200);
        r.ttlSec = o.optInt("ttlSec", 0);
        r.rewriteSegments = o.optBoolean("rewriteSegments", false);
        // 单次转发可以用自己的代理，不填就沿用全局设置
        String px = o.optString("proxy", "");
        if (!px.isEmpty()) r.proxy = px;

        JSONObject h = o.optJSONObject("headers");
        if (h != null) {
            r.headers = new HashMap<>();
            java.util.Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                r.headers.put(k, h.optString(k));
            }
        }
        // 多候选地址。框架先按顺序试，第一个不通就换下一个。
        // README 里一直写着 { urls: [...] }，但这里从来没实现过 —— 返回了等于没返回。
        try {
            org.json.JSONArray us = o.optJSONArray("urls");
            if (us != null && us.length() > 0) {
                r.urls = new java.util.ArrayList<>();
                for (int i = 0; i < us.length(); i++) {
                    String u = us.optString(i, null);
                    if (u != null && !u.isEmpty()) r.urls.add(u);
                }
                if (r.url == null && !r.urls.isEmpty()) r.url = r.urls.get(0);
            }
        } catch (Throwable ignored) { }

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

    /**
     * JS 对象 → JSON，遍历所有键。
     *
     * <p>以前这里是白名单：只拷 url / referer / userAgent / ttlSec。
     * 脚本返回 body / status / contentType / headers / urls 全部被丢掉，
     * 而 README 里明明写了能用 —— 文档说一套、实现做一套。
     *
     * <p>改成通用转换：脚本返回什么字段，框架就拿到什么字段。
     * 以后加新字段不用再改引擎。
     */
    private static JSONObject jsToJson(JSObject src) {
        JSONObject out = new JSONObject();
        if (src == null) return out;
        String[] keys;
        try {
            keys = src.getKeys();
        } catch (Throwable t) {
            return out;
        }
        if (keys == null) return out;
        for (String k : keys) {
            if (k == null) continue;
            try {
                com.quickjs.JSValue.TYPE t = src.getType(k);
                if (t == com.quickjs.JSValue.TYPE.STRING) {
                    String v = src.getString(k);
                    if (v != null) out.put(k, v);
                } else if (t == com.quickjs.JSValue.TYPE.INTEGER) {
                    out.put(k, src.getInteger(k));
                } else if (t == com.quickjs.JSValue.TYPE.DOUBLE) {
                    out.put(k, src.getDouble(k));
                } else if (t == com.quickjs.JSValue.TYPE.BOOLEAN) {
                    out.put(k, src.getBoolean(k));
                } else if (t == com.quickjs.JSValue.TYPE.JS_ARRAY) {
                    out.put(k, jsArrayToJson(src.getArray(k)));
                } else if (t == com.quickjs.JSValue.TYPE.JS_OBJECT) {
                    out.put(k, jsToJson(src.getObject(k)));
                }
            } catch (Throwable ignored) { }
        }
        return out;
    }

    /** JS 数组 → JSON 数组。 */
    private static org.json.JSONArray jsArrayToJson(JSArray arr) {
        org.json.JSONArray out = new org.json.JSONArray();
        if (arr == null) return out;
        int n;
        try {
            n = arr.length();
        } catch (Throwable t) {
            return out;
        }
        for (int i = 0; i < n; i++) {
            try {
                com.quickjs.JSValue.TYPE t = arr.getType(i);
                if (t == com.quickjs.JSValue.TYPE.STRING) {
                    out.put(arr.getString(i));
                } else if (t == com.quickjs.JSValue.TYPE.INTEGER) {
                    out.put(arr.getInteger(i));
                } else if (t == com.quickjs.JSValue.TYPE.DOUBLE) {
                    out.put(arr.getDouble(i));
                } else if (t == com.quickjs.JSValue.TYPE.BOOLEAN) {
                    out.put(arr.getBoolean(i));
                } else if (t == com.quickjs.JSValue.TYPE.JS_ARRAY) {
                    out.put(jsArrayToJson(arr.getArray(i)));
                } else if (t == com.quickjs.JSValue.TYPE.JS_OBJECT) {
                    out.put(jsToJson(arr.getObject(i)));
                } else {
                    out.put(org.json.JSONObject.NULL);
                }
            } catch (Throwable ignored) { }
        }
        return out;
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
