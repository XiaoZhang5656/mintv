package com.mintv.tv.ui.player;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 自定义请求头的归一化层。
 *
 * <p>这是整个项目里最容易做错的一块，因为三种源写法混在一起、三个内核
 * 要的格式又各不相同。先统一成一份规范 Map，再按内核翻译：
 *
 * <pre>
 * 源里的写法                       归一化后
 * ------------------------------------------------------------------
 * url|User-Agent=xx|Referer=yy     {"User-Agent":"xx","Referer":"yy"}
 * url$User-Agent=xx&amp;Referer=yy     同上（$ 后 &amp; 分隔）
 * #EXTHTTP:{"User-Agent":"xx"}     同上（JSON，TVBox 系）
 * #EXTVLCOPT:http-user-agent=xx    同上（VLC 系）
 * #EXTVLCOPT:http-referrer=yy      同上
 * #EXTVLCOPT:http-origin=zz        同上
 * </pre>
 *
 * <p>翻译成各内核的形态：
 * <table border="1">
 *   <tr><th>规范名</th><th>Exo (Media3)</th><th>IJK (FFmpeg)</th><th>VLC</th></tr>
 *   <tr><td>User-Agent</td><td>请求头</td><td>format.user_agent</td><td>:http-user-agent</td></tr>
 *   <tr><td>Referer</td><td>请求头</td><td>format.headers</td><td>:http-referrer</td></tr>
 *   <tr><td>Cookie</td><td>请求头</td><td>format.headers</td><td>:http-cookies</td></tr>
 *   <tr><td>其它任意</td><td>请求头</td><td>format.headers</td><td>不支持</td></tr>
 * </table>
 *
 * <p>注意最后一行：libVLC 3.x 只认 user-agent / referrer / cookies 三个，
 * 其它头没有入口。所以 {@link #vlcSupported} 会把不支持的标出来，
 * UI 上给出明确提示，而不是静默丢头导致用户对着一片黑找原因。
 */
public final class HttpHeaderProfile {

    private HttpHeaderProfile() {}

    /** 大小写无关地取一份规范名。 */
    private static String canon(String name) {
        String n = name.trim();
        String l = n.toLowerCase(Locale.US);
        if (l.equals("user-agent") || l.equals("user_agent") || l.equals("ua")) return "User-Agent";
        if (l.equals("referer") || l.equals("referrer")) return "Referer";
        if (l.equals("origin")) return "Origin";
        if (l.equals("cookie")) return "Cookie";
        if (l.equals("host")) return "Host";
        if (l.equals("accept")) return "Accept";
        return n;
    }

    /** 合并多份头，后出现的覆盖先出现的（同名不同大小写视为同一个）。
     *
     * <p>永远返回非 null 的 Map。以前空的时候返回 null，调用方一句
     * {@code merged.isEmpty()} 就是 NPE —— 默认源（839 个频道、没写任何请求头）
     * 点第一个频道必崩，正是这个原因。空集合比 null 好用得多，
     * 「没有头」用 isEmpty() 表达就够了。
     */
    public static Map<String, String> merge(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> out = new LinkedHashMap<>();
        if (base != null) for (Map.Entry<String, String> e : base.entrySet()) {
            out.put(canon(e.getKey()), e.getValue());
        }
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) {
            out.put(canon(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * 解析头串。三种分隔符都认：竖线、amp、井号。
     *
     * <p>真实源里见过写成
     * {@code url#User-Agent=curl/8.5.0#Referer=http://a.com/} 的，
     * 也见过混合写 {@code url|UA=x&Referer=y}，统一按 {@code [|&#]} 切。
     */
    public static Map<String, String> parsePairs(String tail) {
        if (tail == null || tail.isEmpty()) return null;
        Map<String, String> out = new LinkedHashMap<>();
        String[] parts = tail.split("[|&#]");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            out.put(canon(part.substring(0, eq)), part.substring(eq + 1).trim());
        }
        return out.isEmpty() ? null : out;
    }

    /** 解析 {@code #EXTHTTP:{"k":"v"}} 的 JSON 体。 */
    public static Map<String, String> parseJson(String json) {
        if (json == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int n = json.length();
        while (i < n) {
            int q1 = json.indexOf('"', i);
            if (q1 < 0) break;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            String k = json.substring(q1 + 1, q2);
            int colon = json.indexOf(':', q2 + 1);
            if (colon < 0) break;
            int v1 = json.indexOf('"', colon + 1);
            if (v1 < 0) break;
            int v2 = json.indexOf('"', v1 + 1);
            if (v2 < 0) break;
            out.put(canon(k), json.substring(v1 + 1, v2));
            i = v2 + 1;
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * VLC 能不能表达这个头。
     * libVLC 3.5 只开放 http-user-agent / http-referrer / http-cookies 三个入口，
     * 其余一律无效 —— 与其假装支持，不如说清楚。
     */
    public static boolean vlcSupported(String name) {
        String c = canon(name);
        return "User-Agent".equals(c) || "Referer".equals(c) || "Cookie".equals(c);
    }

    /** 一次问清楚：这些头里有哪些 VLC 表达不了。 */
    public static String vlcUnsupported(Map<String, String> h) {
        if (h == null || h.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String k : h.keySet()) {
            if (!vlcSupported(k)) {
                if (sb.length() > 0) sb.append('/');
                sb.append(k);
            }
        }
        return sb.toString();
    }
}
