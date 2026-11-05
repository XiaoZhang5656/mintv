package com.mintv.tv.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 脚本仓库。
 *
 * <p>脚本存在外部目录 {@code Android/data/<pkg>/files/scripts/}，不存私有目录 ——
 * 用户能用文件管理器直接看、备份、替换。代价是卸载会一起删，所以导出功能要跟上。
 *
 * <p>这里**不执行** JS，只读源码做静态解析，把 { exports.path } 这些声明
 * 用正则抠出来。真执行要等 QuickJS 接进来，但那不影响管理界面先上线。
 */
public final class ScriptStore {

    private static final String TAG = "MinTVScript";

    /** 脚本类型，决定列表里显示什么徽章。 */
    public enum Kind {
        SCRIPT,     // 有 main()，会接管播放
        HEADERS,    // 只有 headers，纯注入不跑 JS
        MODULE,     // 放在 lib/ 下，被 require，不注册路由
        UNKNOWN
    }

    public static final class Item {
        public String name;
        public long size;
        public long modified;
        public Kind kind = Kind.UNKNOWN;
        public String displayName;
        public int priority;
        public boolean guard;
        public boolean enabled = true;
        public final List<String> routes = new ArrayList<>();
        public final List<String> hosts = new ArrayList<>();
        /** 静态解析失败的原因，null 表示没问题。 */
        public String parseError;
    }

    private final File dir;
    private final Context appCtx;

    public ScriptStore(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        dir = new File(base, "scripts");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File lib = new File(dir, "lib");
        if (!lib.exists()) {
            //noinspection ResultOfMethodCallIgnored
            lib.mkdirs();
        }
    }

    /**
     * 把「存储根/scripts/」里的脚本同步到私有目录。
     *
     * <p>为什么需要这一步：ScriptEngine 用 {@code java.io.File} 读脚本，
     * 而 SAF 只给 Uri，拿不到 File。所以脚本的**执行**只能发生在私有目录。
     *
     * <p>做法是把授权目录当作"源"：用户在文件管理器里往
     * {@code <授权根>/scripts/} 丢脚本，这里同步一份到私有目录，
     * 引擎就能跑。两边内容一致，用户看得见、能备份。
     *
     * @return 同步的文件数
     */
    public int syncFromStorage(Context c) {
        java.util.List<String> names = StorageDir.listPath(c, StorageDir.SUB_SCRIPTS);
        if (names == null || names.isEmpty()) return 0;
        int n = 0;
        for (String name : names) {
            String lower = name.toLowerCase(java.util.Locale.US);
            if (!lower.endsWith(".js")) continue;
            String text = StorageDir.readPath(c, StorageDir.SUB_SCRIPTS + "/" + name);
            if (text == null) continue;
            File target = new File(dir, name);
            // 内容没变就不写，避免每次启动都碰磁盘
            String old = null;
            if (target.isFile()) {
                try {
                    byte[] buf = new byte[(int) target.length()];
                    java.io.FileInputStream in = new java.io.FileInputStream(target);
                    try { in.read(buf); } finally { in.close(); }
                    old = new String(buf, "UTF-8");
                } catch (Throwable ignored) { }
            }
            if (text.equals(old)) continue;
            try {
                java.io.FileOutputStream o = new java.io.FileOutputStream(target);
                try { o.write(text.getBytes("UTF-8")); } finally { o.close(); }
                n++;
            } catch (Throwable t) {
                android.util.Log.w("ScriptStore", "同步脚本失败 " + name, t);
            }
        }
        if (n > 0) android.util.Log.w("ScriptStore", "从存储目录同步 " + n + " 个脚本");
        return n;
    }

    public File dir() { return dir; }

    /** 给人看的目录路径（SAF 模式返回用户选的目录名）。 */
    public String displayPath() { return ScriptDir.displayPath(appCtx); }

    // ================= 读 =================

    /** 列出所有脚本（含 lib/ 下的模块），按目录 + 文件名排序。 */
    public List<Item> list() {
        List<Item> out = new ArrayList<>();
        if (ScriptDir.isSaf(appCtx)) return listSaf();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".js")) out.add(parse(f, null, Kind.MODULE));
            }
        }
        File lib = new File(dir, "lib");
        File[] libs = lib.listFiles();
        if (libs != null) {
            for (File f : libs) {
                if (f.isFile() && f.getName().endsWith(".js")) {
                    out.add(parse(f, "lib/", Kind.MODULE));
                }
            }
        }
        // 不用 lambda：android.jar 是桩实现，编译期的 LambdaMetafactory 找不到
        Collections.sort(out, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if ((a.kind == Kind.MODULE) != (b.kind == Kind.MODULE)) {
                    return a.kind == Kind.MODULE ? 1 : -1;   // 模块排后面
                }
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    /** SAF 模式下列表：文件在用户自选目录里。 */
    private List<Item> listSaf() {
        List<Item> out = new ArrayList<>();
        for (String name : ScriptDir.listNames(appCtx)) {
            Item it = new Item();
            it.name = name;
            it.size = ScriptDir.sizeOf(appCtx, name);
            it.modified = ScriptDir.modifiedOf(appCtx, name);
            String src;
            try { src = ScriptDir.read(appCtx, name); }
            catch (Exception e) { it.parseError = "读不到文件"; out.add(it); continue; }
            analyze(it, src, name.startsWith("lib/"));
            out.add(it);
        }
        Collections.sort(out, new java.util.Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                if ((a.kind == Kind.MODULE) != (b.kind == Kind.MODULE)) {
                    return a.kind == Kind.MODULE ? 1 : -1;
                }
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    /** 读一个脚本的源码。name 支持 "x.js" 和 "lib/x.js"。 */
    public String read(String name) throws IOException {
        if (ScriptDir.isSaf(appCtx)) {
            try { return ScriptDir.read(appCtx, name); }
            catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        File f = resolve(name);
        if (f == null || !f.isFile()) throw new IOException("脚本不存在: " + name);
        return new String(readAll(f), StandardCharsets.UTF_8);
    }

    public boolean exists(String name) {
        File f = resolve(name);
        return f != null && f.isFile();
    }

    // ================= 写 =================

    /** 新建或覆盖。返回 false 表示名字非法。 */
    public boolean write(String name, String content) throws IOException {
        if (!isSafeName(name)) return false;
        if (ScriptDir.isSaf(appCtx)) {
            boolean ok = ScriptDir.write(appCtx, name, content, System.currentTimeMillis());
            if (ok) Log.i(TAG, "写入脚本 " + name + " (" + content.length() + " 字符)");
            return ok;
        }
        File f = resolve(name);
        if (f == null) return false;
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            try { out.close(); } catch (IOException ignored) { }
        }
        Log.i(TAG, "写入脚本 " + name + " (" + content.length() + " 字符)");
        return true;
    }

    public boolean delete(String name) {
        if (ScriptDir.isSaf(appCtx)) {
            boolean ok = ScriptDir.delete(appCtx, name);
            Log.i(TAG, "删除脚本 " + name + " -> " + ok);
            return ok;
        }
        File f = resolve(name);
        if (f == null || !f.isFile()) return false;
        boolean ok = f.delete();
        Log.i(TAG, "删除脚本 " + name + " -> " + ok);
        return ok;
    }

    // ================= 路径安全 =================

    /**
     * 只允许 a-z A-Z 0-9 _ - . 以及一级 "lib/" 前缀。
     *
     * <p>必须拦 {@code ..}、斜杠、反斜杠 —— 否则 HTTP 接口可以被用来
     * 读写脚本目录以外的任意文件。
     */
    private static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 120) return false;
        if (name.contains("..") || name.contains("\\")) return false;
        if (name.indexOf('/') >= 0) {
            if (!name.startsWith("lib/")) return false;
            String rest = name.substring(4);
            if (rest.isEmpty() || rest.indexOf('/') >= 0) return false;
            return rest.matches("[A-Za-z0-9_.\\-]+\\.js");
        }
        return name.matches("[A-Za-z0-9_.\\-]+\\.js");
    }

    private File resolve(String name) {
        if (!isSafeName(name)) return null;
        return new File(dir, name);
    }

    // ================= 静态解析 =================

    private Item parse(File f, String prefix, Kind defaultKind) {
        Item it = new Item();
        it.name = (prefix == null ? "" : prefix) + f.getName();
        it.size = f.length();
        it.modified = f.lastModified();
        it.displayName = f.getName();
        String src;
        try {
            src = new String(readAll(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            it.parseError = "读不到文件";
            return it;
        }
        analyze(it, src, defaultKind == Kind.MODULE && prefix != null);
        return it;
    }

    /**
     * 静态解析源码，填 Item 的类型和路由信息。
     *
     * <p>不执行 JS —— 管理界面要能立刻显示"这个脚本管哪些路径"，
     * 为这个启动一次引擎太重了。
     */
    private void analyze(Item it, String src, boolean isLib) {
        if (it.displayName == null) it.displayName = it.name;

        String code = src.replaceAll("(?s)/\\*.*?\\*/", " ");

        boolean hasMain = Pattern.compile("\\bfunction\\s+main\\s*\\(").matcher(code).find()
                || Pattern.compile("exports\\.main\\s*=").matcher(code).find();
        boolean hasGuard = Pattern.compile("exports\\.guard\\s*=\\s*true").matcher(code).find();
        boolean hasHeaders = Pattern.compile("exports\\.headers\\s*=").matcher(code).find();

        if (isLib) {
            it.kind = Kind.MODULE;
        } else if (hasGuard || hasMain) {
            it.kind = Kind.SCRIPT;
        } else if (hasHeaders) {
            it.kind = Kind.HEADERS;
        } else {
            it.kind = Kind.UNKNOWN;
        }
        it.guard = hasGuard;

        it.priority = readInt(code, "exports\\.priority");
        it.displayName = readString(code, "exports\\.name", it.displayName);

        collectStrings(code, "exports\\.path", it.routes);
        collectStrings(code, "exports\\.host", it.hosts);

        Matcher rm = Pattern.compile("\\bpath\\s*:\\s*[\"']([^\"']+)[\"']").matcher(code);
        while (rm.find()) it.routes.add(rm.group(1));

        if (it.routes.isEmpty() && it.hosts.isEmpty()
                && it.kind == Kind.SCRIPT && !hasGuard) {
            it.parseError = "没有声明 exports.path 或 exports.host";
        }
    }

    /** 从 "exports.path = "x"" 或 "exports.path = ["a","b"]" 里抠所有字符串。 */
    private static void collectStrings(String code, String key, List<String> out) {
        Pattern p = Pattern.compile(key + "\\s*=\\s*(\\[[^\\]]*\\]|\"[^\"]*\"|'[^']*')");
        Matcher m = p.matcher(code);
        if (!m.find()) return;
        Matcher q = Pattern.compile("[\"']([^\"']+)[\"']").matcher(m.group(1));
        while (q.find()) {
            String v = q.group(1);
            if (!out.contains(v)) out.add(v);
        }
    }

    private static int readInt(String code, String key) {
        Matcher m = Pattern.compile(key + "\\s*=\\s*(\\d+)").matcher(code);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String readString(String code, String key, String def) {
        Matcher m = Pattern.compile(key + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(code);
        return m.find() ? m.group(1) : def;
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    // ================= 给 HTTP 接口用 =================

    public JSONArray toJson(List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", it.name);
                o.put("displayName", it.displayName);
                o.put("size", it.size);
                o.put("modified", it.modified);
                o.put("kind", it.kind.name().toLowerCase());
                o.put("priority", it.priority);
                o.put("guard", it.guard);
                o.put("enabled", it.enabled);
                o.put("routes", new JSONArray(it.routes));
                o.put("hosts", new JSONArray(it.hosts));
                if (it.parseError != null) o.put("error", it.parseError);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        return arr;
    }
}
