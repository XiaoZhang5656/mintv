package com.mintv.tv.data;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 脚本目录的抽象层。
 *
 * <p>两种后端：
 * <ul>
 *   <li><b>默认</b>：App 私有外部目录 {@code Android/data/<pkg>/files/scripts}。
 *       免权限，但 Android 11+ 文件管理器打不开 Android/data。</li>
 *   <li><b>SAF</b>：用户自己选的目录（比如 {@code Download/mintv}）。
 *       文件管理器全程可见，代价是首次要手动选一次。</li>
 * </ul>
 *
 * <p>这里不用 androidx.documentfile —— 它的 AAR 在某些源上是空壳，
 * 直接用系统自带的 {@link DocumentsContract}，零依赖。
 */
public final class ScriptDir {

    private static final String PREF = "script_dir";
    private static final String KEY_URI = "tree_uri";

    private ScriptDir() { }

    // ================= 配置 =================

    public static Uri treeUri(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_URI, null);
        return s == null ? null : Uri.parse(s);
    }

    /** 记住用户选的目录，并保留持久化权限。 */
    public static void setTreeUri(Context ctx, Uri uri) {
        if (uri == null) return;
        try {
            ctx.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) { }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_URI, uri.toString()).apply();
    }

    public static void clearTreeUri(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_URI).apply();
    }

    public static boolean isSaf(Context ctx) { return treeUri(ctx) != null; }

    /** 给人看的路径，网页上显示用。 */
    public static String displayPath(Context ctx) {
        Uri u = treeUri(ctx);
        if (u == null) {
            return "Android/data/" + ctx.getPackageName() + "/files/scripts";
        }
        String name = resolveTreeName(ctx, u);
        return name == null ? u.toString() : name;
    }

    private static String resolveTreeName(Context ctx, Uri treeUri) {
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return null;
    }

    // ================= 统一读写 =================

    /** 目录里的 .js 文件。name 带子目录时形如 "lib/x.js"。 */
    public static List<String> listNames(Context ctx) {
        List<String> out = new ArrayList<>();
        Uri tree = treeUri(ctx);
        if (tree == null) return out;

        try {
            Uri root = docUri(tree, rootDocId(tree));
            for (Uri child : children(ctx, root)) {
                String nm = displayName(ctx, child);
                if (nm == null) continue;
                if (isDir(ctx, child)) {
                    if (!"lib".equals(nm)) continue;
                    for (Uri g : children(ctx, child)) {
                        String gn = displayName(ctx, g);
                        if (gn != null && gn.endsWith(".js")) out.add("lib/" + gn);
                    }
                } else if (nm.endsWith(".js")) {
                    out.add(nm);
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static String read(Context ctx, String name) throws Exception {
        Uri tree = treeUri(ctx);
        if (tree == null) throw new Exception("没有配置脚本目录");
        Uri f = find(ctx, tree, name);
        if (f == null) throw new Exception("文件不存在: " + name);
        ContentResolver cr = ctx.getContentResolver();
        InputStream in = cr.openInputStream(f);
        if (in == null) throw new Exception("打不开: " + name);
        try { return new String(readAll(in), StandardCharsets.UTF_8); }
        finally { in.close(); }
    }

    public static boolean write(Context ctx, String name, String content, long modified) {
        Uri tree = treeUri(ctx);
        if (tree == null) return false;
        try {
            String dirName = null, fileName = name;
            if (name.startsWith("lib/")) { dirName = "lib"; fileName = name.substring(4); }

            Uri parent = docUri(tree, rootDocId(tree));
            if (dirName != null) parent = ensureDir(ctx, tree, parent, dirName);
            if (parent == null) return false;

            Uri existing = findIn(ctx, parent, fileName);
            if (existing != null) {
                ContentResolver cr = ctx.getContentResolver();
                OutputStream os = cr.openOutputStream(existing, "wt");
                if (os == null) return false;
                try { os.write(content.getBytes(StandardCharsets.UTF_8)); } finally { os.close(); }
                return true;
            }
            ContentResolver cr = ctx.getContentResolver();
            Uri created = DocumentsContract.createDocument(cr, parent,
                    "application/javascript", fileName);
            if (created == null) return false;
            OutputStream os = cr.openOutputStream(created);
            if (os == null) return false;
            try { os.write(content.getBytes(StandardCharsets.UTF_8)); } finally { os.close(); }
            return true;
        } catch (Exception e) {
            ScriptLog.error(name, "写入失败: " + e.getMessage());
            return false;
        }
    }

    public static boolean delete(Context ctx, String name) {
        Uri tree = treeUri(ctx);
        if (tree == null) return false;
        try {
            Uri f = find(ctx, tree, name);
            if (f == null) return false;
            return DocumentsContract.deleteDocument(ctx.getContentResolver(), f);
        } catch (Exception e) {
            return false;
        }
    }

    public static long sizeOf(Context ctx, String name) {
        Uri tree = treeUri(ctx);
        if (tree == null) return 0;
        try {
            Uri f = find(ctx, tree, name);
            if (f == null) return 0;
            Cursor c = ctx.getContentResolver().query(f,
                    new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                long v = c.getLong(0);
                c.close();
                return v;
            }
            if (c != null) c.close();
        } catch (Exception ignored) { }
        return 0;
    }

    public static long modifiedOf(Context ctx, String name) {
        Uri tree = treeUri(ctx);
        if (tree == null) return 0;
        try {
            Uri f = find(ctx, tree, name);
            if (f == null) return 0;
            Cursor c = ctx.getContentResolver().query(f,
                    new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null);
            if (c != null && c.moveToFirst()) {
                long v = c.getLong(0);
                c.close();
                return v;
            }
            if (c != null) c.close();
        } catch (Exception ignored) { }
        return 0;
    }

    // ================= DocumentsContract 工具 =================

    private static String rootDocId(Uri tree) {
        return DocumentsContract.getTreeDocumentId(tree);
    }

    private static Uri docUri(Uri tree, String docId) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
    }

    private static String docIdOf(Uri doc) {
        return DocumentsContract.getDocumentId(doc);
    }

    /** 直接子项列表（URI 形式）。 */
    private static List<Uri> children(Context ctx, Uri parent) {
        List<Uri> out = new ArrayList<>();
        Uri tree = treeUri(ctx);
        if (tree == null) return out;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docIdOf(parent));
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(childrenUri,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
            if (c != null) {
                while (c.moveToNext()) out.add(docUri(tree, c.getString(0)));
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return out;
    }

    private static String displayName(Context ctx, Uri doc) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return null;
    }

    private static boolean isDir(Context ctx, Uri doc) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0));
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        return false;
    }

    private static Uri findIn(Context ctx, Uri parent, String name) {
        for (Uri c : children(ctx, parent)) {
            if (name.equals(displayName(ctx, c))) return c;
        }
        return null;
    }

    /** 在树里按 "x.js" 或 "lib/x.js" 找文件。 */
    private static Uri find(Context ctx, Uri tree, String name) {
        Uri root = docUri(tree, rootDocId(tree));
        if (name.startsWith("lib/")) {
            Uri lib = findIn(ctx, root, "lib");
            if (lib == null || !isDir(ctx, lib)) return null;
            return findIn(ctx, lib, name.substring(4));
        }
        return findIn(ctx, root, name);
    }

    private static Uri ensureDir(Context ctx, Uri tree, Uri parent, String name) {
        Uri found = findIn(ctx, parent, name);
        if (found != null) return isDir(ctx, found) ? found : null;
        try {
            Uri created = DocumentsContract.createDocument(ctx.getContentResolver(), parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, name);
            return created;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
