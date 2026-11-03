package com.mintv.tv.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 直播源地址与本地缓存。
 *
 * <p><b>为什么源正文不进 SharedPreferences</b>：
 * 一份 839 频道的 m3u 有 200KB 左右，而 SharedPreferences 的读写单位是
 * 「整个 XML 文件」—— 保存时要序列化全部内容再整份落盘，启动时整串读进内存。
 * 放在 SP 里等于每次保存都重写几百 KB，低端盒子上一次可见的卡顿。
 * 现在正文写私有目录文件，SP 只留地址、时间戳和 ETag 这几个小字段。
 *
 * <p>同时顺带支持条件请求：记住 ETag / Last-Modified，源没变时服务端回 304，
 * 不再每次启动都硬拉 200KB。
 */
public final class SourceStore {

    private static final String SP = "tvui";
    private static final String K_URL = "source_url";
    private static final String K_TIME = "source_time";
    private static final String K_ETAG = "source_etag";
    private static final String K_LAST_MOD = "source_lastmod";
    /** 老版本把正文存在这里，保留 key 只为了迁移一次。 */
    private static final String K_LEGACY_BODY = "source_body";

    private static final String FILE_NAME = "source.m3u";

    // 这里刻意**不内置任何直播源地址**。
    // 早期版本写死过一个 guovin.github.io 的地址，结果是：
    //   · 用户从没配过源，却被当成"默认订阅"塞进订阅列表；
    //   · 那个地址哪天挂了，所有人打开都是空的，还以为是 App 坏了。
    // 现在语义是：没配置 = 空字符串，界面用内置示范数据兜底，
    // 真正的源由用户自己在手机上添加。

    private SourceStore() {}

    /**
     * 用户是否**真的**填过源地址。
     *
     * <p>迁移到订阅列表时必须先问这个：早期版本的 url() 在没配置时
     * 会返回硬编码的默认地址，直接迁移会把那个内置源当成
     * 用户自己的订阅塞进列表 —— 表现就是"我从来没添加过订阅，
     * 列表里却有一条默认订阅"。
     */
    public static boolean hasUserUrl(Context c) {
        return sp(c).contains(K_URL);
    }

    public static String url(Context c) {
        // 没设置过就返回空串，由调用方决定用示范数据
        String v = sp(c).getString(K_URL, "");
        return v == null ? "" : v;
    }

    public static void setUrl(Context c, String url) {
        sp(c).edit().putString(K_URL, url).apply();
    }

    private static File bodyFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** 读取缓存的源正文。文件不存在时返回 null（并尝试从旧的 SP 迁移一次）。 */
    public static String cachedBody(Context c) {
        File f = bodyFile(c);
        if (f.exists() && f.length() > 0) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            } catch (Throwable t) {
                return null;
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) { }
            }
        }
        // 一次性迁移：老版本正文在 SP 里
        SharedPreferences p = sp(c);
        String legacy = p.getString(K_LEGACY_BODY, null);
        if (legacy != null && !legacy.isEmpty()) {
            saveBody(c, legacy);
            p.edit().remove(K_LEGACY_BODY).apply();
            return legacy;
        }
        return null;
    }

    public static long cachedTime(Context c) {
        return sp(c).getLong(K_TIME, 0L);
    }

    public static String etag(Context c) {
        return sp(c).getString(K_ETAG, null);
    }

    public static long lastModified(Context c) {
        return sp(c).getLong(K_LAST_MOD, 0L);
    }

    public static void saveBody(Context c, String body) {
        saveBody(c, body, null, 0L);
    }

    /** 保存正文 + 更新校验信息。etag / lastModified 传空表示服务端没给。 */
    public static void saveBody(Context c, String body, String etag, long lastModified) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(bodyFile(c));
            out.write(body.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
        }
        SharedPreferences.Editor e = sp(c).edit().putLong(K_TIME, System.currentTimeMillis());
        if (etag != null && !etag.isEmpty()) e.putString(K_ETAG, etag);
        else e.remove(K_ETAG);
        if (lastModified > 0) e.putLong(K_LAST_MOD, lastModified);
        else e.remove(K_LAST_MOD);
        e.apply();
    }

    /** 服务端回 304：只刷新时间戳，不动正文。 */
    public static void touch(Context c) {
        sp(c).edit().putLong(K_TIME, System.currentTimeMillis()).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
