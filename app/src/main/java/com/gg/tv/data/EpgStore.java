package com.gg.tv.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * EPG 数据源与本地缓存。
 *
 * 主源用「老张的 EPG」（epg.51zmt.top），国内直连可用；
 * 备源再来一份，主源失败时自动切换。
 */
public final class EpgStore {

    private static final String SP = "tvui_epg";
    private static final String K_RAW = "epg_raw";
    private static final String K_TIME = "epg_time";

    public static final String[] SOURCES = {
            "https://epg.51zmt.top:8001/e.xml.gz",
            "https://epg.51zmt.top:8001/e1.xml.gz",
    };

    /** 缓存有效期 6 小时，EPG 一天更新一次，够用。 */
    private static final long TTL_MS = 6 * 3600_000L;

    private EpgStore() {}

    public static byte[] cached(Context c) {
        SharedPreferences sp = sp(c);
        String b64 = sp.getString(K_RAW, null);
        if (b64 == null) return null;
        try {
            return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isFresh(Context c) {
        long t = sp(c).getLong(K_TIME, 0L);
        return t > 0 && System.currentTimeMillis() - t < TTL_MS;
    }

    public static void save(Context c, byte[] raw) {
        if (raw == null || raw.length == 0) return;
        sp(c).edit()
                .putString(K_RAW, android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP))
                .putLong(K_TIME, System.currentTimeMillis())
                .apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
