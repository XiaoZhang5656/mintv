package com.mintv.tv.data;

import android.content.Context;

import org.json.JSONObject;

/**
 * 设置项持久化 —— 存成存储目录里的一个 json 文件。
 *
 * <p>为什么不用 SharedPreferences：它在 {@code /data/data/<包名>/shared_prefs/}，
 * 文件管理器看不到、备份不了、换设备带不走。而订阅、隐藏分类、请求头
 * 早就放在 {@code <存储根>/config/} 下了 —— 设置项是唯一还在私有目录里的
 * 一类数据，属于历史遗留。
 *
 * <p>现在统一到：
 * <pre>
 *   /sdcard/mintv/config/settings.json
 *   {
 *     "selections": "decoder=0;timeout=2;...",
 *     "accent": 0,
 *     "proxy": "socks5://...",
 *     "storage_dir": "..."
 *   }
 * </pre>
 *
 * <h3>迁移</h3>
 * 首次读取时如果文件不存在，会返回调用方给的默认值；调用方负责在
 * 「文件没有但 SharedPreferences 有」的情况下把旧值写进来（见
 * {@link #migrateFrom}）。之后只认文件。
 *
 * <h3>为什么不整体读写</h3>
 * 每次改一个键都把整个 json 序列化一遍太浪费，但设置项总共就十几个，
 * 而且改的频率极低（用户点设置页才触发）。这里用「读-改-写」的简单模型，
 * 换代码可读性。
 */
public final class SettingsStore {

    private static final String TAG = "SettingsStore";
    private static final String FILE = StorageDir.SUB_CONFIG + "/settings.json";

    private SettingsStore() { }

    /**
     * 内存里那份。
     *
     * <p><b>必须有这一层</b>：之前每次 getString 都直接读文件，走
     * ContentResolver + 磁盘。设置页有 19 行、每行渲染要读好几次配置，
     * 一次界面刷新就是几十次 IO —— 设置页卡顿就是这么来的。
     *
     * <p>SharedPreferences 本身就有内存缓存，我把它换成文件时漏了这层。
     */
    private static volatile JSONObject cache = null;

    /** 读整个对象。命中内存直接返回；文件不存在或损坏返回空对象。 */
    private static JSONObject load(Context c) {
        JSONObject hit = cache;
        if (hit != null) return hit;
        synchronized (SettingsStore.class) {
            if (cache != null) return cache;
            JSONObject o;
            try {
                String text = StorageDir.readPath(c, FILE);
                o = (text == null || text.trim().isEmpty())
                        ? new JSONObject() : new JSONObject(text);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "读设置失败", t);
                o = new JSONObject();
            }
            cache = o;
            return o;
        }
    }

    private static void save(Context c, JSONObject o) {
        // 先更新内存，再落盘 —— 落盘失败也不影响本次会话
        cache = o;
        try {
            StorageDir.writePath(c, FILE, o.toString());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "写设置失败", t);
        }
    }

    /** 清掉内存缓存（切换存储根、或者外部改了文件时用）。 */
    public static void invalidate() {
        cache = null;
    }

    // ---------------- 对外 ----------------

    /** 有没有设置文件（判断是否已经迁移过）。 */
    public static boolean exists(Context c) {
        String text = StorageDir.readPath(c, FILE);
        return text != null && !text.trim().isEmpty();
    }

    public static String getString(Context c, String key, String def) {
        JSONObject o = load(c);
        String v = o.optString(key, null);
        return (v == null || v.isEmpty()) ? def : v;
    }

    public static void putString(Context c, String key, String value) {
        JSONObject o = load(c);
        try {
            o.put(key, value == null ? "" : value);
        } catch (Throwable ignored) { }
        save(c, o);
    }

    public static int getInt(Context c, String key, int def) {
        JSONObject o = load(c);
        return o.optInt(key, def);
    }

    public static void putInt(Context c, String key, int value) {
        JSONObject o = load(c);
        try {
            o.put(key, value);
        } catch (Throwable ignored) { }
        save(c, o);
    }

    /**
     * 从旧的 SharedPreferences 迁移一次。
     *
     * <p>只在文件不存在时执行，而且不会删除旧数据 —— 万一新逻辑有问题，
     * 用户退回到旧版本还能用。旧数据留着无害，就是几十字节。
     *
     * @param keys 要迁移的 键 → 值 对（调用方自己从 SharedPreferences 读）
     */
    public static void migrateFrom(Context c, java.util.Map<String, Object> keys) {
        if (exists(c)) return;
        if (keys == null || keys.isEmpty()) return;
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, Object> e : keys.entrySet()) {
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (v.isEmpty()) continue;
            try { o.put(e.getKey(), v); } catch (Throwable ignored) { }
        }
        if (o.length() == 0) return;
        save(c, o);
        android.util.Log.i(TAG, "设置已从 SharedPreferences 迁移到 " + FILE
                + "，共 " + o.length() + " 项");
    }
}
