package com.mintv.tv.data;

import android.content.Context;
import android.content.SharedPreferences;

/** 直播源地址与本地缓存。 */
public final class SourceStore {

    private static final String SP = "tvui";
    private static final String K_URL = "source_url";
    private static final String K_BODY = "source_body";
    private static final String K_TIME = "source_time";

    /**
     * 默认源取自 Guovin/iptv-api 的 GitHub Pages 输出（每日自动更新 + 测速）。
     * 换成自己的源：改这里，或在应用里长按左侧「频道列表」标题输入。
     * 置空则使用内置示范数据。
     */
    public static final String DEFAULT_URL = "https://guovin.github.io/iptv-api/result.m3u";

    /** 备用源：同一份数据的 txt 版本，没有台标，用来验证「无图标」渲染路径。 */
    public static final String BACKUP_URL = "https://guovin.github.io/iptv-api/result.txt";

    private SourceStore() {}

    public static String url(Context c) {
        return sp(c).getString(K_URL, DEFAULT_URL);
    }

    public static void setUrl(Context c, String url) {
        sp(c).edit().putString(K_URL, url).apply();
    }

    public static String cachedBody(Context c) {
        return sp(c).getString(K_BODY, null);
    }

    public static long cachedTime(Context c) {
        return sp(c).getLong(K_TIME, 0L);
    }

    public static void saveBody(Context c, String body) {
        sp(c).edit()
                .putString(K_BODY, body)
                .putLong(K_TIME, System.currentTimeMillis())
                .apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }
}
