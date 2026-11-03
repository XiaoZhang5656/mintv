package com.mintv.tv.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 订阅（直播源）列表。
 *
 * <p>为什么从"一个 URL 字符串"改成列表：用户通常会备好几份源
 * （日常用的、酒店源、省内源），需要在电视或手机上随时切换。
 *
 * <p>存储用 {@code files/subscriptions.json}，不用 SharedPreferences ——
 * 同 SourceStore 的理由：SP 的读写单位是整个 XML，列表会随时改，
 * 放文件里干净得多。JSON 用系统自带的 org.json，零依赖。
 *
 * <p><b>请求头默认是空的</b>：很多源不能带 UA（带了反而 403），
 * 所以"空"必须表示"一个头都不发"，由播放内核用自己的默认值，
 * 绝不能在代码里兜一个 MinTV/1.0 上去。
 */
public final class SubscriptionStore {

    private static final String FILE_NAME = "subscriptions.json";

    public static final class Sub {
        public String id;
        public String name;
        /** 直播源地址（m3u / m3u8 / txt） */
        public String url;
        /** 自定义请求头，一行一条 "Name: value"；空表示不发送任何自定义头 */
        public String headers = "";
        /** 上次更新成功的时间戳，0 表示没更新过 */
        public long lastUpdate;
        /** 上次解析出的频道数，只用于界面展示（"839 个频道"）。 */
        public int channelCount;

        public Sub() { }

        public Sub(String id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }

        public boolean hasHeaders() {
            return headers != null && !headers.trim().isEmpty();
        }
    }

    private static List<Sub> cache;
    private static String activeId;
    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    // ---------------- 读取 ----------------

    public static synchronized List<Sub> list(Context c) {
        if (cache == null) load(c);
        return cache;
    }

    public static synchronized Sub active(Context c) {
        if (cache == null) load(c);
        for (Sub s : cache) {
            if (s.id != null && s.id.equals(activeId)) return s;
        }
        return cache.isEmpty() ? null : cache.get(0);
    }

    public static synchronized String activeId(Context c) {
        if (cache == null) load(c);
        Sub a = active(c);
        return a == null ? null : a.id;
    }

    // ---------------- 写入 ----------------

    /** 新增或按 id 覆盖。返回落库后的对象。 */
    public static synchronized Sub upsert(Context c, Sub sub) {
        if (cache == null) load(c);
        if (sub.id == null || sub.id.isEmpty()) sub.id = newId();
        for (int i = 0; i < cache.size(); i++) {
            if (sub.id.equals(cache.get(i).id)) {
                cache.set(i, sub);
                save(c);
                return sub;
            }
        }
        cache.add(sub);
        if (activeId == null) activeId = sub.id;
        save(c);
        return sub;
    }

    public static synchronized void remove(Context c, String id) {
        if (cache == null) load(c);
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(id)) {
                cache.remove(i);
                break;
            }
        }
        if (id != null && id.equals(activeId)) {
            activeId = cache.isEmpty() ? null : cache.get(0).id;
        }
        save(c);
    }

    /** 切换当前使用的订阅，并把地址同步给老代码用的 SourceStore。 */
    public static synchronized void setActive(Context c, String id) {
        if (cache == null) load(c);
        for (Sub s : cache) {
            if (s.id != null && s.id.equals(id)) {
                activeId = s.id;
                SourceStore.setUrl(c, s.url);
                save(c);
                return;
            }
        }
    }

    public static synchronized void touch(Context c, String id) {
        if (cache == null) load(c);
        for (Sub s : cache) {
            if (s.id != null && s.id.equals(id)) {
                s.lastUpdate = System.currentTimeMillis();
                save(c);
                return;
            }
        }
    }

    // ---------------- 内部 ----------------

    private static String newId() {
        return Long.toHexString(System.currentTimeMillis());
    }

    private static void load(Context c) {
        cache = new ArrayList<>();
        activeId = null;
        File f = file(c);
        if (f.exists() && f.length() > 0) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int n = in.read(buf);
                JSONObject root = new JSONObject(new String(buf, 0, Math.max(0, n), "UTF-8"));
                activeId = root.optString("active", null);
                JSONArray arr = root.optJSONArray("list");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        Sub s = new Sub();
                        s.id = o.optString("id", newId());
                        s.name = o.optString("name", "订阅 " + (i + 1));
                        s.url = o.optString("url", "");
                        s.headers = o.optString("headers", "");
                        s.lastUpdate = o.optLong("lastUpdate", 0L);
            s.channelCount = o.optInt("channelCount", 0);
                        if (!s.url.isEmpty()) cache.add(s);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (in != null) try { in.close(); } catch (Throwable ignored) { }
            }
        }

        // 迁移：老版本只有一个 source_url，转成第一条订阅。
        // 注意只在用户**真的存过**地址时迁移 —— SourceStore.url() 现在
        // 没配置会返回空串，不会再把内置的默认源当成用户的订阅带进来。
        if (cache.isEmpty()) {
            String url = SourceStore.hasUserUrl(c) ? SourceStore.url(c) : "";
            if (url != null && !url.isEmpty()) {
                Sub s = new Sub(newId(), "默认订阅", url);
                cache.add(s);
                activeId = s.id;
                save(c);
            }
        }
        if (activeId == null && !cache.isEmpty()) activeId = cache.get(0).id;
    }

    /** 按 id 找一条。找不到返回 null。 */
    public static Sub find(Context c, String id) {
        if (id == null) return null;
        for (Sub s : list(c)) {
            if (id.equals(s.id)) return s;
        }
        return null;
    }

    /** 用传入的条目替换同 id 的那条（用于回写频道数、更新时间等）。 */
    public static void update(Context c, Sub sub) {
        if (sub == null || sub.id == null) return;
        List<Sub> list = list(c);
        for (int i = 0; i < list.size(); i++) {
            if (sub.id.equals(list.get(i).id)) {
                list.set(i, sub);
                cache = list;
                save(c);
                return;
            }
        }
    }

    private static void save(Context c) {
        FileOutputStream out = null;
        try {
            JSONArray arr = new JSONArray();
            for (Sub s : cache) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("headers", s.headers == null ? "" : s.headers);
                o.put("lastUpdate", s.lastUpdate);
            o.put("channelCount", s.channelCount);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("active", activeId == null ? "" : activeId);
            root.put("list", arr);
            out = new FileOutputStream(file(c));
            out.write(root.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
        }
    }
}
