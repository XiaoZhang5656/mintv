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

    // 根目录结构：
    //   config/   系统配置（订阅列表、请求头、隐藏分类）
    //   scripts/  脚本
    //   sources/  本地订阅（用户自己丢的 m3u/txt）
    //   upload/   手机后台上传的
    //   cache/    EPG、源正文快照（可再生，丢了不影响数据）
    //
    // 分开放的理由：用户打开文件夹一眼知道哪些能改、哪些能删。
    public static final String SUB_CONFIG  = "config";
    public static final String SUB_SCRIPTS = "scripts";
    public static final String SUB_SOURCES = "sources";
    public static final String SUB_UPLOAD  = "upload";
    public static final String SUB_CACHE   = "cache";

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
        for (String sub : new String[]{SUB_CONFIG, SUB_SCRIPTS, SUB_SOURCES, SUB_UPLOAD, SUB_CACHE}) {
            ensureSub(c, sub);
        }
        // 把授权前写在私有目录里的东西搬过来。
        //
        // 不搬的话会出现两套数据：授权前手机上传的源在私有目录，
        // 授权后新丢的源在授权目录，界面上看是两份，用户分不清。
        // 迁移后统一读授权目录，私有目录只作为"没授权时的落点"保留。
        int moved = migratePrivateToRoot(c);
        // 老版本把配置文件散在根目录，先归位再说
        int reorganized = migrateLegacyLayout(c);
        android.util.Log.w("StorageDir", "授权根 = " + readablePath(treeUri)
                + " · 迁移 " + moved + " 个文件 · 整理 " + reorganized + " 个");
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
    /**
     * 把私有目录里的文件复制到授权目录（同名跳过，不删私有副本）。
     *
     * <p>只处理文本文件（脚本、m3u、txt 都是文本）。
     * 将来如果这三个目录里出现二进制内容，要改成流式拷贝。
     *
     * @return 实际复制的文件数
     */
    /**
     * 把「早期版本散在根目录的配置文件」搬进 config/ 和 cache/。
     *
     * <p>只处理私有目录里的老文件；目标位置已有内容就不覆盖
     * （用户可能在新位置手工改过）。
     *
     * @return 搬动的文件数
     */
    public static int migrateLegacyLayout(Context c) {
        int moved = 0;
        for (String[] pair : MIGRATE_MAP) {
            String oldPath = pair[0];
            String newPath = pair[1];
            java.io.File oldFile = new java.io.File(privateRoot(c), oldPath);
            if (!oldFile.isFile() || oldFile.length() == 0) continue;
            // 新位置已经有内容就不动
            String existing = readPath(c, newPath);
            if (existing != null && !existing.isEmpty()) continue;
            String text = readFile(oldFile);
            if (text != null && writePath(c, newPath, text)) {
                moved++;
                android.util.Log.w("StorageDir", "迁移 " + oldPath + " -> " + newPath);
            }
        }
        return moved;
    }

    public static int migratePrivateToRoot(Context c) {
        int moved = 0;
        // 根目录下的配置文件（subscriptions.json 等）也要一起搬，
        // 否则授权后订阅列表会"变空" —— 因为读的位置变了。
        for (String rootFile : ROOT_FILES) {
            java.io.File src = new java.io.File(privateRoot(c), rootFile);
            if (!src.isFile() || src.length() == 0) continue;
            // 授权目录里已经有就不覆盖（用户可能手工改过）
            if (readPath(c, rootFile) != null) continue;
            String text = readFile(src);
            if (text != null && writePath(c, rootFile, text)) moved++;
        }
        // 三个子目录
        for (String sub : new String[]{SUB_SCRIPTS, SUB_SOURCES, SUB_UPLOAD}) {
            java.io.File priv = privateSub(c, sub);
            java.io.File[] files = priv.listFiles();
            if (files == null || files.length == 0) continue;

            java.util.Set<String> have = new java.util.HashSet<>(list(c, sub));

            for (java.io.File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                if (have.contains(name)) continue;
                String text = readFile(f);
                if (text == null) continue;
                if (write(c, sub, name, text)) moved++;
            }
        }
        return moved;
    }

    /**
     * 迁移清单：{老路径, 新路径}。
     *
     * <p>早期版本配置文件直接放根目录，后来收进 config/ 和 cache/。
     * 这里把老位置的文件搬到新位置，用户升级后数据不丢。
     */
    public static final String[][] MIGRATE_MAP = {
            {"subscriptions.json", SUB_CONFIG + "/subscriptions.json"},
            {"hidden_cats.txt",    SUB_CONFIG + "/hidden_cats.txt"},
            {"headers.txt",        SUB_CONFIG + "/headers.txt"},
            {"epg.xml",            SUB_CACHE  + "/epg.xml"},
            {"source.m3u",         SUB_CACHE  + "/source.m3u"},
    };

    /** 需要跟着授权目录一起走的文件（新路径）。 */
    public static final String[] ROOT_FILES = {
            SUB_CONFIG + "/subscriptions.json",
            SUB_CONFIG + "/hidden_cats.txt",
            SUB_CONFIG + "/headers.txt",
            SUB_CACHE  + "/epg.xml",
            SUB_CACHE  + "/source.m3u",
    };

    // ================= 路径级读写（统一入口） =================
    //
    // 设计：私有目录和授权目录**结构完全同构**。
    //   未授权 → getFilesDir()/xxx
    //   已授权 → <授权根>/xxx
    // 上层只关心相对路径，不关心根在哪，也不关心是 File 还是 SAF。

    public static String readPath(Context c, String path) {
        String v = readPathRaw(c, path);
        if (v != null && !v.isEmpty()) return v;
        // writePath 在 SAF 上会先把旧文件改名成 .bak 再写新的。
        // 如果写完但进程刚好死掉，主文件可能缺失 —— 退到 .bak 至少不丢数据。
        return readPathRaw(c, path + ".bak");
    }

    private static String readPathRaw(Context c, String path) {
        Uri root = rootUri(c);
        if (root == null) {
            return readFile(new java.io.File(privateRoot(c), path));
        }
        try {
            Uri doc = docUriFor(root, path);
            if (doc == null) return null;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(doc)) {
                return in == null ? null : readAll(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读任意相对路径的原始字节（用于 EPG 这类 gzip 数据）。 */
    public static byte[] readBytesPath(Context c, String path) {
        Uri root = rootUri(c);
        if (root == null) {
            java.io.File f = new java.io.File(privateRoot(c), path);
            if (!f.exists()) return null;
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                return readAllBytes(in);
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            Uri doc = docUriFor(root, path);
            if (doc == null) return null;
            try (java.io.InputStream in = c.getContentResolver().openInputStream(doc)) {
                return in == null ? null : readAllBytes(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** 写原始字节。 */
    public static boolean writeBytesPath(Context c, String path, byte[] data) {
        Uri root = rootUri(c);
        if (root == null) {
            java.io.File f = new java.io.File(privateRoot(c), path);
            java.io.File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            try (java.io.OutputStream o = new java.io.FileOutputStream(f)) {
                o.write(data);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        String rootDoc = docId(root);
        if (rootDoc == null) return false;
        try {
            String[] parts = path.split("/");
            Uri curDir = DocumentsContract.buildDocumentUriUsingTree(root, rootDoc);
            String curDoc = rootDoc;
            for (int i = 0; i < parts.length - 1; i++) {
                String nextId = curDoc + "/" + parts[i];
                Uri nextDoc = DocumentsContract.buildDocumentUriUsingTree(root, nextId);
                if (!docExists(c, nextDoc)) {
                    Uri created = DocumentsContract.createDocument(c.getContentResolver(),
                            curDir, DocumentsContract.Document.MIME_TYPE_DIR, parts[i]);
                    if (created == null) return false;
                    nextDoc = created;
                    nextId = DocumentsContract.getDocumentId(created);
                }
                curDir = nextDoc;
                curDoc = nextId;
            }
            String fileName = parts[parts.length - 1];
            Uri fileDoc = DocumentsContract.buildDocumentUriUsingTree(root, curDoc + "/" + fileName);
            if (!docExists(c, fileDoc)) {
                fileDoc = DocumentsContract.createDocument(c.getContentResolver(), curDir,
                        mimeOf(fileName), fileName);
                if (fileDoc == null) return false;
            }
            try (java.io.OutputStream o =
                         c.getContentResolver().openOutputStream(fileDoc, "wt")) {
                if (o == null) return false;
                o.write(data);
                o.flush();
                return true;
            }
        } catch (Throwable t) {
            android.util.Log.w("StorageDir", "写字节失败 " + path, t);
            return false;
        }
    }

    private static byte[] readAllBytes(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    public static boolean writePath(Context c, String path, String content) {
        byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Uri root = rootUri(c);
        // 私有目录：写 .tmp 再 rename，同分区 rename 是原子的。
        if (root == null) {
            java.io.File f = new java.io.File(privateRoot(c), path);
            java.io.File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return false;
            java.io.File tmp = new java.io.File(f.getAbsolutePath() + ".tmp");
            try {
                java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
                try {
                    o.write(data);
                    o.flush();
                    o.getFD().sync();
                } finally { o.close(); }
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            return true;
        }

        // SAF：没有原子的 rename-覆盖，用「写 tmp → 旧文件转 .bak → tmp 改名」三步。
        // 任何一步失败都能从 .bak 恢复，不会两头落空。
        String rootDoc = docId(root);
        if (rootDoc == null) return false;
        try {
            String[] parts = path.split("/");
            Uri curDir = DocumentsContract.buildDocumentUriUsingTree(root, rootDoc);
            String curDoc = rootDoc;
            for (int i = 0; i < parts.length - 1; i++) {
                String nextDocId = curDoc + "/" + parts[i];
                Uri nextDoc = DocumentsContract.buildDocumentUriUsingTree(root, nextDocId);
                if (!docExists(c, nextDoc)) {
                    Uri created = DocumentsContract.createDocument(c.getContentResolver(),
                            curDir, DocumentsContract.Document.MIME_TYPE_DIR, parts[i]);
                    if (created == null) return false;
                    nextDoc = created;
                    nextDocId = DocumentsContract.getDocumentId(created);
                }
                curDir = nextDoc;
                curDoc = nextDocId;
            }
            String fileName = parts[parts.length - 1];
            String tmpName = fileName + ".tmp";
            String bakName = fileName + ".bak";

            // 1) 写临时文件
            Uri tmpDoc = DocumentsContract.buildDocumentUriUsingTree(root, curDoc + "/" + tmpName);
            if (!docExists(c, tmpDoc)) {
                tmpDoc = DocumentsContract.createDocument(c.getContentResolver(), curDir,
                        mimeOf(fileName), tmpName);
                if (tmpDoc == null) return false;
            }
            try (java.io.OutputStream o =
                         c.getContentResolver().openOutputStream(tmpDoc, "wt")) {
                if (o == null) return false;
                o.write(data);
                o.flush();
            }

            // 2) 旧文件改名成 .bak
            Uri fileDoc = DocumentsContract.buildDocumentUriUsingTree(root, curDoc + "/" + fileName);
            if (docExists(c, fileDoc)) {
                try {
                    DocumentsContract.renameDocument(c.getContentResolver(), fileDoc, bakName);
                } catch (Throwable t) {
                    android.util.Log.w("StorageDir", "备份旧文件失败，继续", t);
                }
            }

            // 3) tmp 改名成正式名
            DocumentsContract.renameDocument(c.getContentResolver(), tmpDoc, fileName);
            return true;

        } catch (Throwable t) {
            android.util.Log.w("StorageDir", "写失败 " + path, t);
            return false;
        }
    }

    public static List<String> listPath(Context c, String path) {
        List<String> out = new ArrayList<>();
        Uri root = rootUri(c);
        if (root == null) {
            java.io.File dir = (path == null || path.isEmpty())
                    ? privateRoot(c) : new java.io.File(privateRoot(c), path);
            String[] names = dir.list();
            if (names != null) Collections.addAll(out, names);
            return out;
        }
        String rootDoc = docId(root);
        if (rootDoc == null) return out;
        try {
            String doc = (path == null || path.isEmpty()) ? rootDoc : rootDoc + "/" + path;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, doc);
            try (android.database.Cursor cur = c.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cur != null) {
                    while (cur.moveToNext()) out.add(cur.getString(0));
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("StorageDir", "列目录失败 " + path, t);
        }
        return out;
    }

    public static boolean deletePath(Context c, String path) {
        Uri root = rootUri(c);
        if (root == null) {
            java.io.File f = new java.io.File(privateRoot(c), path);
            //noinspection ResultOfMethodCallIgnored
            return f.delete();
        }
        try {
            Uri doc = docUriFor(root, path);
            return doc != null && DocumentsContract.deleteDocument(c.getContentResolver(), doc);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Uri docUriFor(Uri root, String path) {
        String rootDoc = docId(root);
        if (rootDoc == null) return null;
        return DocumentsContract.buildDocumentUriUsingTree(root, rootDoc + "/" + path);
    }

    private static boolean docExists(Context c, Uri doc) {
        try (android.database.Cursor cur = c.getContentResolver().query(doc,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            return cur != null && cur.getCount() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String mimeOf(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".js")) return "application/javascript";
        return "text/plain";
    }

    /** 未授权时的私有根（和授权根同构）。 */
    private static java.io.File privateRoot(Context c) {
        return c.getApplicationContext().getFilesDir();
    }

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
    // ================= 兼容层 =================
    //
    // 下面这些是"子目录 + 文件名"的旧写法。
    // 全部转成路径级调用，保证只有**一套**读写实现 ——
    // 两套并存时行为容易分叉（旧的走 ensureSub，新的走路径解析），
    // 出问题查起来要命。

    public static List<String> list(Context c, String sub) {
        return listPath(c, sub);
    }

    public static String read(Context c, String sub, String name) {
        return readPath(c, sub + "/" + name);
    }

    public static boolean write(Context c, String sub, String name, String content) {
        return writePath(c, sub + "/" + name, content);
    }

    public static boolean delete(Context c, String sub, String name) {
        return deletePath(c, sub + "/" + name);
    }

    public static long modified(Context c, String sub, String name) {
        // 文件修改时间：SAF 要 query，未授权直接看 File。
        // 目前只用于判断"源文件有没有变"，精度要求不高。
        Uri root = rootUri(c);
        if (root == null) {
            java.io.File f = new java.io.File(privateSub(c, sub), name);
            return f.exists() ? f.lastModified() : 0L;
        }
        try {
            Uri doc = docUriFor(root, sub + "/" + name);
            if (doc == null) return 0L;
            try (android.database.Cursor cur = c.getContentResolver().query(doc,
                    new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                    null, null, null)) {
                if (cur != null && cur.moveToFirst()) return cur.getLong(0);
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
