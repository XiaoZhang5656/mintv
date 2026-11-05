package com.mintv.tv.data;

import android.content.Context;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * 脚本目录 —— 现在只是 {@link StorageDir} 的一层薄封装。
 *
 * <p>统一存储之后不再有"脚本目录"这个概念：脚本、本地订阅、上传文件
 * 都在同一个授权根下面的不同子目录里。保留这个类只是为了不动
 * ScriptStore 和网页端那几十处调用。
 *
 * <p>授权根没设的时候，{@link StorageDir} 会自动回落到 App 私有目录，
 * 所以这里不需要再判断"是不是 SAF"。
 */
public final class ScriptDir {

    private ScriptDir() { }

    /** 授权根（脚本实际位置是它下面的 scripts/）。没授权返回 null。 */
    public static Uri treeUri(Context ctx) {
        return StorageDir.rootUri(ctx);
    }

    public static void setTreeUri(Context ctx, Uri uri) {
        StorageDir.setRoot(ctx, uri);
    }

    public static void clearTreeUri(Context ctx) {
        StorageDir.clearRoot(ctx);
    }

    /**
     * 是否已授权外部目录。
     *
     * <p>保留这个方法是因为调用方拿它决定"要不要提示用户去授权"，
     * 而不是决定"能不能用" —— 两种情况都能用，差别只是文件管理器看不看得见。
     */
    public static boolean isSaf(Context ctx) {
        return StorageDir.hasRoot(ctx);
    }

    /** 给人看的路径，网页和设置页显示用。 */
    public static String displayPath(Context ctx) {
        Uri root = StorageDir.rootUri(ctx);
        if (root == null) {
            return "私有目录/" + StorageDir.SUB_SCRIPTS;
        }
        String p = StorageDir.readablePath(root);
        return p.isEmpty() ? p : p + "/" + StorageDir.SUB_SCRIPTS;
    }

    /** 目录里的 .js 文件。name 带子目录时形如 "lib/x.js"。 */
    public static List<String> listNames(Context ctx) {
        return new ArrayList<>(StorageDir.list(ctx, StorageDir.SUB_SCRIPTS));
    }

    public static String read(Context ctx, String name) throws Exception {
        String s = StorageDir.read(ctx, StorageDir.SUB_SCRIPTS, name);
        if (s == null) throw new java.io.FileNotFoundException(name);
        return s;
    }

    /** modified 参数保留兼容，SAF 目录改不了时间戳，忽略。 */
    public static boolean write(Context ctx, String name, String content, long modified) {
        return StorageDir.write(ctx, StorageDir.SUB_SCRIPTS, name, content);
    }

    public static boolean delete(Context ctx, String name) {
        return StorageDir.delete(ctx, StorageDir.SUB_SCRIPTS, name);
    }

    public static long sizeOf(Context ctx, String name) {
        String s = StorageDir.read(ctx, StorageDir.SUB_SCRIPTS, name);
        return s == null ? 0 : s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    public static long modifiedOf(Context ctx, String name) {
        return StorageDir.modified(ctx, StorageDir.SUB_SCRIPTS, name);
    }
}
