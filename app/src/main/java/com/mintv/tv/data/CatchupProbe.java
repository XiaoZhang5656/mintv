package com.mintv.tv.data;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * 回看能力探测。
 *
 * <p>为什么需要探测：国内大部分直播源（包括 iptv-api 的输出）<b>不写</b> catchup 属性，
 * 但服务端实际支持时移。光看 m3u 字段会得出「全部不能回看」的错误结论。
 *
 * <p>探测方式：拿一个已经播完的节目，按常见模板构造时移地址，
 * 发一次 GET 只读前几个字节 —— 能正常返回 200 且有数据就认为支持。
 *
 * <p>模板按国内源的实际写法依次尝试：
 * <pre>
 * 1. ?playseek=yyyyMMddHHmmss-yyyyMMddHHmmss          （最常见）
 * 2. ?starttime=yyyyMMddHHmmss&amp;endtime=yyyyMMddHHmmss  （电信/联通系）
 * 3. ?utc=unix秒&amp;lutc=unix秒                          （某些 CDN）
 * 4. /timeshift/yyyyMMddHHmmss/时长分钟/index.m3u8      （部分地方台）
 * </pre>
 *
 * 结果按「host」缓存，同一台服务器的频道只需要探测一次，不会对每个频道都发请求。
 */
public final class CatchupProbe {

    /** host → 该服务器支持的模板序号，-1 表示不支持 */
    private static final Map<String, Integer> hostResult = new HashMap<>();
    /** host → 该服务器支持的模板样例，用于回看地址构造 */
    private static final Map<String, String> hostTemplate = new HashMap<>();

    private static final String[] TEMPLATES = {
            "?playseek=${b}-${e}",
            "?starttime=${b}&endtime=${e}",
            "?utc=${ub}&lutc=${ue}",
    };

    private static android.content.SharedPreferences sp;

    /** 初始化持久化缓存（应用启动时调用一次）。 */
    public static void init(android.content.Context ctx) {
        sp = ctx.getApplicationContext()
                .getSharedPreferences("minv_catchup", android.content.Context.MODE_PRIVATE);
    }

    private CatchupProbe() {}

    public interface Callback {
        /** @param ok 是否支持回看；ok 时 url 是可用的回看地址样例 */
        void onResult(boolean ok, String url);
    }

    /**
     * 探测某个源是否真能回看。
     *
     * @param declaredSource 源里 catchup-source 写的模板（可能为 null）
     * @param declaredMode   catchup 的值（default/append/shift/vod，可能为 null）
     */
    public static boolean probe(String host, String baseUrl,
                                String declaredSource, String declaredMode,
                                long startMs, long endMs) {
        synchronized (hostResult) {
            Integer cached = hostResult.get(host);
            if (cached != null) return cached >= 0;
        }

        // 这里只判断「这个模板能不能拿到有效内容」，不再比对分片是否和直播不同。
        // 原因：很多源对未知参数一律返回 200 + 正常直播列表，比对分片会把
        // 它们全判成不支持；但用户真正要的是「按这个时间点去请求，能不能播」，
        // 能不能播出结果由播放器自己报错，比事先猜准得多。

        // 1) 源里声明了 catchup-source，先按它拼
        if (declaredSource != null && !declaredSource.isEmpty()) {
            String url = baseUrl + fill(declaredSource, startMs, endMs);
            if (fetchOk(url)) {
                synchronized (hostResult) {
                    hostResult.put(host, 0);
                    hostTemplate.put(host, declaredSource);
                }
                persist(host, 0, declaredSource);
                return true;
            }
            // 拼法可能是整段 URL（vod 模式），直接试
            String full = fill(declaredSource, startMs, endMs);
            if (full.startsWith("http") && fetchOk(full)) {
                synchronized (hostResult) {
                    hostResult.put(host, 0);
                    hostTemplate.put(host, declaredSource);
                }
                persist(host, 0, declaredSource);
                return true;
            }
        }

        // 2) 没有声明或声明无效 → 用通用模板挨个试
        for (int i = 0; i < TEMPLATES.length; i++) {
            String url = baseUrl + fill(TEMPLATES[i], startMs, endMs);
            if (fetchOk(url)) {
                synchronized (hostResult) {
                    hostResult.put(host, i);
                    hostTemplate.put(host, TEMPLATES[i]);
                }
                persist(host, i, TEMPLATES[i]);
                return true;
            }
        }
        synchronized (hostResult) {
            hostResult.put(host, -1);
        }
        persist(host, -1, null);
        return false;
    }

    /** 该 host 已经探测过的结论；null 表示还没探测。 */
    public static Boolean cached(String host) {
        synchronized (hostResult) {
            Integer r = hostResult.get(host);
            if (r != null) return r >= 0;
        }
        // 内存没有就读磁盘——探测结果跨进程重启保留，避免每次开应用重新试
        if (sp != null) {
            // key 带版本：以前只判 HTTP 200 的做法把一堆「不支持时移」的源
            // 记成了支持，那些结论必须整体作废，否则永远显示假「回看」。
            int v = sp.getInt("v2#" + host, -2);
            if (v != -2) {
                synchronized (hostResult) { hostResult.put(host, v); }
                return v >= 0;
            }
        }
        return null;
    }

    private static void persist(String host, int templateIndex, String tpl) {
        if (sp == null) return;
        android.content.SharedPreferences.Editor e = sp.edit().putInt(host, templateIndex);
        if (tpl != null) e.putString(host + "#tpl", tpl);
        e.apply();
    }

    /** 用探测出来的模板给这个 host 拼回看地址。 */
    public static String build(String host, String baseUrl, long startMs, long endMs) {
        String tpl;
        synchronized (hostResult) {
            tpl = hostTemplate.get(host);
        }
        if (tpl == null && sp != null) tpl = sp.getString(host + "#tpl", null);
        if (tpl == null) tpl = TEMPLATES[0];
        String suffix = fill(tpl, startMs, endMs);
        return suffix.startsWith("http") ? suffix : baseUrl + suffix;
    }

    // ---------------- 内部 ----------------

    /**
     * 抓一段响应正文当指纹。
     *
     * <p>为什么不能只看状态码：实测 `?playseek=...` 在很多国内 CDN 上
     * 返回 200 + 一份**正常的直播列表**（服务端直接忽略未知参数）。
     * 只看 200 就会把「不支持时移」误判成「支持」，于是 EPG 上所有老节目
     * 都挂上「回看」标记，点进去放的还是直播 —— 用户看到的就是
     * 「点了回看没播对应的节目」。
     */
    private static String fetchSample(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 MinTV/1.0");
            conn.setRequestProperty("Range", "bytes=0-1535");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) return null;
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[1536];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8").trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 m3u8 文本里抽「分片指纹」：第一个不是 # 开头的行。 */
    private static String segmentOf(String body) {
        if (body == null) return null;
        for (String line : body.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            return t;
        }
        return null;
    }

    /**
     * 时移地址是否真的返回了**不同**的切片。
     *
     * <p>判据是分片路径不同。同一个直播窗口的两个请求，切片名必然相同
     * （就算 MEDIA-SEQUENCE 恰好没动）；真时移则一定指到别的时间段。
     */
    private static boolean fetchDiffers(String liveSeg, String url) {
        String body = fetchSample(url);
        if (body == null) return false;
        if (!body.contains("#EXTM3U") && !body.contains("#EXTINF")
                && !(body.length() > 0 && body.charAt(0) == 0x47)) {
            return false;
        }
        String seg = segmentOf(body);
        if (seg == null) return false;
        return liveSeg == null || !seg.equals(liveSeg);
    }

    /** 只读前 2KB 就断开：足够判断服务端有没有真的吐 m3u8/ts。 */
    private static boolean fetchOk(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 MinTV/1.0");
            conn.setRequestProperty("Range", "bytes=0-511");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) return false;
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[512];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return false;

            String head = new String(buf, 0, Math.min(n, 300), "UTF-8").trim();

            // 判定标准只看内容本身，不看 MIME、不看状态码以外的花活：
            //   · 真正的 HLS 播放列表一定含 #EXTM3U（fMP4 / ts / 加密切片都一样）
            //   · 有的源直接回一段 #EXTINF 也是有效列表
            //   · 不支持的服务器实测返回 200 + "sign error" 这类短文本，被这里排除
            if (head.contains("#EXTM3U") || head.contains("#EXTINF")) return true;

            // 少数源回的是裸媒体流（TS 同步字节 0x47 / fMP4 的 ftyp box）
            if (head.length() > 0 && (head.charAt(0) == 0x47
                    || (head.length() > 8 && head.substring(4, 8).equals("ftyp")))) {
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 填充模板占位符。支持 ${(b)yyyyMMddHHmmss} 等写法。 */
    public static String fill(String tpl, long startMs, long endMs) {
        String b = fmt(startMs), e = fmt(endMs);
        String out = tpl;
        out = out.replace("${(b)yyyyMMddHHmmss}", b);
        out = out.replace("${(e)yyyyMMddHHmmss}", e);
        out = out.replace("${b}", b);
        out = out.replace("${e}", e);
        out = out.replace("${ub}", String.valueOf(startMs / 1000));
        out = out.replace("${ue}", String.valueOf(endMs / 1000));
        out = out.replace("{start}", String.valueOf(startMs));
        out = out.replace("{end}", String.valueOf(endMs));
        return out;
    }

    private static String fmt(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyyMMddHHmmss", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new java.util.Date(ms));
    }
}
