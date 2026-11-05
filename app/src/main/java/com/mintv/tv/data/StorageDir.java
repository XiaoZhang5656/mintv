package com.mintv.tv.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统一的存储目录。
 *
 * <p>用户授权一个根目录（SAF），下面分三块：
 * <pre>
 *   授权根/
 *   ├─ scripts/   脚本
 *   ├─ sources/   本地订阅 m3u / txt
 *   └─ upload/    手机后台上传落点
 * </pre>
 *
 * <p>未授权时全部回落到 App 私有目录，功能不受影响，只是文件管理器看不到。
 *
 * <p><b>为什么不用 java.io.File</b>：SAF 目录拿不到真实路径，只能走
 * DocumentFile / ContentResolver。所以对外统一用「子目录名 + 文件名」，
 * 不暴露 File 和绝对路径。
 *
 * <p><b>已知限制</b>：SAF 里的文件不能直接交给 VLC（它要真实路径）。
 * 本地源是"解析成频道列表"，不是当媒体文件播，所以不受影响。
 */
public final class StorageDir {

    public static final String SUB_SCRIPTS = "scripts";
    public static final String SUB_SOURCES = "sources";
    public static final String SUB_UPLOAD  = "upload";

    private static final String PREF = "storage_dir";
    private static final String KEY_TREE = "tree_uri";

    private StorageDir() { }

    // ---------- 授权 ----------

    /** 保存用户授权的根目录，并取持久化权限。 */
    public static void setRoot(Context c, Uri treeUri) {
        if (treeUri == null) return;
        try {
            c.getContentResolver().takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            android.util.Log.w("StorageDir", "取持久权限失败", t);
        }
        prefs(c).edit().putString(KEY_TREE, treeUri.toString()).apply();
        // 立刻建好三个子目录，用户去文件管理器就能看到
        for (String sub : new String[]{SUB_SCRIPTS, SUB_SOURCES, SUB_UPLOAD}) {
            ensureSub(c, sub);
        }
        android.util.Log.i("StorageDir", "授权根 = " + treeUri + " 可读路径 = " + readablePath(treeUri));
    }

    public static Uri rootUri(Context c) {
        String s = prefs(c).getString(KEY_TREE, null);
        if (s == null || s.isEmpty()) return null;
        try {
            return Uri.parse(s);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean hasRoot(Context c) { return rootUri(c) != null; }

    /** 撤销授权，回落到私有目录。 */
    public static void clearRoot(Context c) {
        Uri u = rootUri(c);
        if (u != null) {
            try {
                c.getContentResolver().releasePersistableUriPermission(u,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Throwable ignored) { }
        }
        prefs(c).edit().remove(KEY_TREE).apply();
    }

    /**
     * 把 SAF 的 tree uri 转成用户看得懂的路径。
     *
     * <p>{@code content://.../tree/primary:Download/mintv} → {@code /sdcard/Download/mintv}
     *
     * <p>不是官方 API，但显示给用户必须用真实路径 —— 让用户在文件管理器里
     * 找 "com.android.externalstorage.documents/tree/primary%3ADownload" 是不可能的。
     */
    public static String readablePath(Uri treeUri) {
        if (treeUri == null) return "";
        String docId = null;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Throwable t) {
            String s = treeUri.toString();
            int i = s.indexOf("/tree/");
            if (i > 0) docId = Uri.decode(s.substring(i + 6));
        }
        if (docId == null) return treeUri.toString();
        // primary:Download/mintv
        int colon = docId.indexOf(':');
        if (colon < 0) return docId;
        String vol = docId.substring(0, colon);
        String path = docId.substring(colon + 1);
        String base = "primary".equalsIgnoreCase(vol)
                ? "/sdcard"
                : "/storage/" + vol;
        return path.isEmpty() ? base : base + "/" + path;
    }

    // ---------- 目录 ----------

    /** 保证子目录存在，返回它的 tree uri；失败返回 null。 */
    /**
     * 确保 {@code sub} 子目录存在，返回它的 tree uri。
     *
     * <p>之前的实现用 {@code exists()} 判断目录是否已建好，但那个检查
     * 依赖 {@code buildDocumentUriUsingTree} 拼出的 URI 能被 query 到 ——
     * 实测在部分设备上拼出来的 URI 查不到，于是误判成"目录不存在"，
     * 又去 createDocument，创建失败返回 null，最后调用方回落到私有目录，
     * 表现就是"授权了但读不到文件"。
     *
     * <p>现在改成：先列 root 的子项，按名字找；找不到才创建。
     * 这条路径只用 {@code buildChildDocumentsUriUsingTree}，不依赖单个
     * document URI 的可查性，稳得多。
     */
    public static Uri ensureSub(Context c, String sub) {
        Uri root = rootUri(c);
        if (root == null) return null;
        String rootDoc = docId(root);
        if (rootDoc == null) return null;

        try {
            // 1) 先列 root 下所有子项，找同名目录
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, rootDoc);
            String foundId = null;
            try (android.database.Cursor cur = c.getContentResolver().query(children,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        String id = cur.getString(0);
                        String name = cur.getString(1);
                        if (sub.equals(name)) { foundId = id; break; }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("StorageDir", "列 root 子项失败", t);
            }

            if (foundId != null) {
                return DocumentsContract.buildTreeDocumentUri(root.getAuthority(), foundId);
            }

            // 2) 没找到就创建（parent 必须是 document uri）
            Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(root, rootDoc);
            Uri created = DocumentsContract.createDocument(
                    c.getContentResolver(), parentDoc,
                    DocumentsContract.Document.MIME_TYPE_DIR, sub);
            if (created == null) {
                android.util.Log.w("StorageDir", "createDocument 返回 null: " + sub);
                return null;
            }
            String newId = DocumentsContract.getDocumentId(created);
            return DocumentsContract.buildTreeDocumentUri(root.getAuthority(), newId);

        } catch (Throwable t) {
            android.util.Log.w("StorageDir", "ensureSub 异常 " + sub, t);
            return null;
        }
    }

    private static String docId(Uri uri) {
        try {
            return DocumentsContract.getTreeDocumentId(uri);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean exists(Context c, Uri uri) {
        try (android.database.Cursor cur = c.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return cur != null && cur.getCount() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------- 读写 ----------

    /** 列子目录下的文件名。授权目录优先，没有则回落到私有目录。 */
    public static List<String> list(Context c, String sub) {
        List<String> out = new ArrayList<>();
        Uri dir = ensureSub(c, sub);
        if (dir != null) {
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                        dir, DocumentsContract.getTreeDocumentId(dir));
                try (android.database.Cursor cur = c.getContentResolver().query(children,
                        new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                    if (cur != null) {
                        while (cur.moveToNext()) out.add(cur.getString(0));
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("StorageDir", "列目录失败 " + sub, t);
            }
        } else {
            java.io.File d = privateSub(c, sub);
            String[] names = d.list();
            if (names != null) Collections.addAll(out, names);
        }
        return out;
    }

    public static String read(Context c, String sub, String name) {
        Uri dir = ensureSub(c, sub);
        if (dir == null) {
            java.io.File f = new java.io.File(privateSub(c, sub), name);
            return readFile(f);
        }
        try {
            Uri doc = findChild(c, dir, name);
            if (doc == null) return null;
            try (InputStream in = c.getContentResolver().openInputStream(doc)) {
                return readAll(in);
            }
        } catch (Throwable t) {
            android.util.Log.w("StorageDir", "读失败 " + sub + "/" + name, t);
            return null;
        }
    }

    public static boolean write(Context c, String sub, String name, String content) {
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Uri dir = ensureSub(c, sub);
        if (dir == null) {
            java.io.File d = privateSub(c, sub);
            if (!d.exists() && !d.mkdirs()) return false;
            try (OutputStream o = new java.io.FileOutputStream(new java.io.File(d, name))) {
                o.write(data);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        try {
            Uri doc = findChild(c, dir, name);
            if (doc == null) {
                doc = DocumentsContract.createDocument(c.getContentResolver(), dir,
                        mime(name), name);
            }
            if (doc == null) return false;
            try (OutputStream o = c.getContentResolver().openOutputStream(doc, "wt")) {
                if (o == null) return false;
                o.write(data);
                return true;
            }
        } catch (Throwable t) {
            android.util.Log.w("StorageDir", "写失败 " + sub + "/" + name, t);
            return false;
        }
    }

    public static boolean delete(Context c, String sub, String name) {
        Uri dir = ensureSub(c, sub);
        if (dir == null) {
            java.io.File f = new java.io.File(privateSub(c, sub), name);
            return f.delete();
        }
        try {
            Uri doc = findChild(c, dir, name);
            return doc != null && DocumentsContract.deleteDocument(c.getContentResolver(), doc);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 修改时间（ms）。用于检测本地源是否被改过。找不到返回 0。 */
    public static long modified(Context c, String sub, String name) {
        Uri dir = ensureSub(c, sub);
        if (dir == null) {
            java.io.File f = new java.io.File(privateSub(c, sub), name);
            return f.exists() ? f.lastModified() : 0L;
        }
        try {
            Uri doc = findChild(c, dir, name);
            if (doc == null) return 0L;
            try (android.database.Cursor cur = c.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                    null, null, null)) {
                if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
            }
        } catch (Throwable ignored) { }
        return 0L;
    }

    // ---------- 内部 ----------

    private static Uri findChild(Context c, Uri dir, String name) {
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    dir, DocumentsContract.getTreeDocumentId(dir));
            try (android.database.Cursor cur = c.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        if (name.equals(cur.getString(1))) {
                            return DocumentsContract.buildDocumentUriUsingTree(dir, cur.getString(0));
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static java.io.File privateSub(Context c, String sub) {
        return new java.io.File(c.getFilesDir(), sub);
    }

    private static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".m3u") || n.endsWith(".m3u8")) return "audio/x-mpegurl";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static String readFile(java.io.File f) {
        if (!f.exists()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
