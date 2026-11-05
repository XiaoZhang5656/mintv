package com.mintv.tv.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * EPG 数据源与本地缓存。
 *
 * <p>主源用「老张的 EPG」（epg.51zmt.top），国内直连可用；
 * 备源再来一份，主源失败时自动切换。
 *
 * <p><b>为什么缓存不进 SharedPreferences</b>：
 * EPG 原始数据 100KB 上下，之前是 Base64 编码后塞进 SP 的 ——
 * 实测 `shared_prefs/tvui_epg.xml` 139742 字节。两个问题：
 * <ul>
 *   <li>Base64 让体积白白膨胀 33%；</li>
 *   <li>SP 的写单位是整个 XML，每次保存要序列化全部内容再整份落盘，
 *       启动时又整串读进内存再解码。低端盒子上一来一回就是几十毫秒的卡顿。</li>
 * </ul>
 * 现在正文存文件、SP 只留时间戳。文件是原始字节，不解码不膨胀，
 * 读到内存直接交给解析器。
 */
public final class EpgStore {

    private static final String SP = "tvui_epg";
    private static final String K_TIME = "epg_time";
    /** 老版本把 Base64 正文存在这里，保留 key 只为了迁移一次。 */
    private static final String K_LEGACY_RAW = "epg_raw";

    private static final String FILE_NAME =
            com.mintv.tv.data.StorageDir.SUB_CACHE + "/epg.xml";

    public static final String[] SOURCES = {
            "https://epg.51zmt.top:8001/e.xml.gz",
            "https://epg.51zmt.top:8001/e1.xml.gz",
    };

    /** 缓存有效期 6 小时，EPG 一天更新一次，够用。 */
    private static final long TTL_MS = 6 * 3600_000L;

    private EpgStore() {}

    private static File privateFile(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    public static byte[] cached(Context c) {
        // EPG 是 gzip 的 XML，走字节级 API
        byte[] data = StorageDir.readBytesPath(c, FILE_NAME);
        if (data != null && data.length > 0) return data;
        File f = privateFile(c);
        if (f.exists() && f.length() > 0) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } catch (Throwable t) {
                return null;
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) { }
            }
        }
        // 一次性迁移：老版本正文是 SP 里的 Base64
        SharedPreferences sp = sp(c);
        String legacy = sp.getString(K_LEGACY_RAW, null);
        if (legacy != null && !legacy.isEmpty()) {
            try {
                byte[] raw = android.util.Base64.decode(legacy, android.util.Base64.DEFAULT);
                save(c, raw);
                sp.edit().remove(K_LEGACY_RAW).apply();
                return raw;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    public static boolean isFresh(Context c) {
        long t = sp(c).getLong(K_TIME, 0L);
        return t > 0 && System.currentTimeMillis() - t < TTL_MS;
    }

    public static void save(Context c, byte[] raw) {
        if (raw == null || raw.length == 0) return;
        // EPG 跟着存储根走：未授权在 getFilesDir()/epg.xml，
        // 授权后在 <授权根>/epg.xml —— 用户可以自己换一份 EPG 丢进去。
        StorageDir.writeBytesPath(c, FILE_NAME, raw);
        sp(c).edit().putLong(K_TIME, System.currentTimeMillis()).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
