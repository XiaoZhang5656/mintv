package com.mintv.tv.data;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 统一的 HTTP 拉取。
 *
 * <p>以前「拉源 / 拉 EPG / 拉台标 / 探回看」四处各写一遍连接参数，
 * 超时、请求头、重定向、结构校验全是复制粘贴，改一处漏三处。
 * 现在收成一个地方。
 *
 * <p>两条硬规矩：
 * <ol>
 *   <li><b>没有配置就不发 User-Agent</b>。写死一个 "TVUI/1.0" 会让按 UA
 *       白名单过滤的源直接 403，而用户完全看不出是这个原因。</li>
 *   <li><b>先判断结构再决定读多少</b>。不设体积上限，而是边读边看：
 *       出现 {@code #EXTM3U/#EXTINF} 才认为是播放列表；开头是 HTML/JSON
 *       立刻中止，不让几十 MB 的错误页进内存。</li>
 * </ol>
 */
public final class Http {

    /** 结构判定：这是不是一份播放列表。 */
    public enum Kind { M3U, HTML, UNKNOWN }

    /** 拉取结果。 */
    public static final class Result {
        public final String body;      // 失败或结构不对时为 null
        public final String etag;
        public final long lastModified;
        public final int code;
        public final Kind kind;
        public final String error;     // 失败原因，成功时为 null

        Result(String body, String etag, long lm, int code, Kind kind, String error) {
            this.body = body; this.etag = etag; this.lastModified = lm;
            this.code = code; this.kind = kind; this.error = error;
        }
        public boolean ok() { return body != null; }
    }

    private Http() { }

    public static Result get(String url, Map<String, String> headers,
                             int connectMs, int readMs, boolean mustBePlaylist) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            // 不写死 UA：没配就不发
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    String k = e.getKey(), v = e.getValue();
                    if (k == null || v == null || k.isEmpty() || v.isEmpty()) continue;
                    try { conn.setRequestProperty(k, v); } catch (Throwable ignored) { }
                }
            }
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code == 304) {
                return new Result(null, null, 0L, 304, Kind.UNKNOWN, "not-modified");
            }
            if (code < 200 || code >= 400) {
                return new Result(null, null, 0L, code, Kind.UNKNOWN, "HTTP " + code);
            }

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16384];
            int n;
            boolean isM3u = false;
            boolean aborted = false;
            String head = null;
            int scanned = 0;

            while ((n = in.read(buf)) > 0) {
                if (!isM3u && !aborted && scanned < 8192) {
                    scanned += n;
                    String chunk = new String(buf, 0, n, "UTF-8");
                    if (head == null) head = chunk;
                    else if (head.length() < 8192) head = head + chunk;

                    if (head.indexOf("#EXTM3U") >= 0 || head.indexOf("#EXTINF") >= 0) {
                        isM3u = true;
                    } else if (looksMarkup(head)) {
                        aborted = true;
                        break;
                    }
                }
                bos.write(buf, 0, n);
            }
            in.close();

            if (aborted) {
                return new Result(null, null, 0L, code, Kind.HTML,
                        "返回内容不是播放列表（疑似网页/JSON）");
            }
            if (mustBePlaylist && !isM3u && head != null
                    && !head.trim().startsWith("#") && head.indexOf(',') < 0) {
                return new Result(null, null, 0L, code, Kind.UNKNOWN,
                        "返回内容不是播放列表");
            }

            return new Result(bos.toString("UTF-8"),
                    conn.getHeaderField("ETag"),
                    conn.getLastModified(),
                    code,
                    isM3u ? Kind.M3U : Kind.UNKNOWN,
                    null);
        } catch (Throwable t) {
            return new Result(null, null, 0L, -1, Kind.UNKNOWN,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /** 头部像网页/JSON 吗。 */
    private static boolean looksMarkup(String head) {
        if (head == null) return false;
        String t = head.trim();
        if (t.startsWith("<!") || t.startsWith("<?xml")
                || t.startsWith("{") || t.startsWith("[")) return true;
        String lower = t.length() > 512 ? t.substring(0, 512).toLowerCase() : t.toLowerCase();
        return lower.startsWith("<html") || lower.startsWith("<head")
                || lower.startsWith("<body") || lower.indexOf("<html") >= 0
                || lower.indexOf("<body") >= 0;
    }
}
