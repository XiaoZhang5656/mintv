package com.mintv.tv.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3U / M3U8 / TXT 直播源解析器。
 *
 * 支持的格式都来自国内直播源的实际样本：
 *
 * <pre>
 * #EXTM3U x-tvg-url="..."
 * #EXTINF:-1 tvg-id="CCTV1" tvg-name="CCTV-1 综合" tvg-logo="https://x/logo.png" group-title="央视",CCTV-1 综合
 * http://example.com/cctv1.m3u8
 *
 * #EXTINF:-1 group-title="卫视",湖南卫视
 * http://example.com/hunan.m3u8|User-Agent:okhttp|Referer:http://x/
 *
 * 央视,#genre#                 ← TXT 格式的分类行
 * CCTV-1 综合,http://x/1.m3u8
 * 湖南卫视,http://x/2.m3u8#线路2     ← 多个线路，# 后面可能是注释也可能是 #EXTINF
 * </pre>
 *
 * 要点：
 * 1. 分类来自 group-title（M3U）或 "#genre#" 行（TXT），没有分类的归到「未分类」
 * 2. 图标只认 tvg-logo，没有就是 null —— 不从台名去猜图标
 * 3. 同名频道视为同一频道的多条线路，合并进 channels 的 sources 列表
 * 4. 播放参数（|User-Agent=...）保留在 URL 上，由播放器剥出来设置请求头
 */
public final class PlaylistParser {

    public static final class Source {
        public final String url;
        public final Map<String, String> headers;

        Source(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    public static final class Channel {
        public final String name;
        public final String group;      // 分类，来自 group-title 或 #genre#
        public final String logo;       // tvg-logo，可能为 null
        public final String tvgId;      // tvg-id，用于 EPG 匹配，可能为 null
        /**
         * 回看方式，来自 #EXTINF 的 catchup 属性：
         * default / append / shift / vod，null 表示该频道不支持回看。
         */
        public String catchup;
        /** catchup-source 模板，如 ?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss} */
        public String catchupSource;
        /** catchup-correction（有的源用它表示时区修正） */
        public String catchupCorrection;
        public final int number;        // 自动生成的台号，从 1 开始
        public final List<Source> sources = new ArrayList<>();

        Channel(String name, String group, String logo, String tvgId, int number) {
            this.name = name;
            this.group = group;
            this.logo = logo;
            this.tvgId = tvgId;
            this.number = number;
        }

        /** EPG 匹配用，可能为空 */
        public String tvgId() { return tvgId; }
    }

    public static final class Playlist {
        /** 分类 → 频道（保持源文件顺序） */
        public final Map<String, List<Channel>> byGroup = new LinkedHashMap<>();
        public final List<Channel> all = new ArrayList<>();

        /**
         * #EXTM3U 头部的全局 catchup 声明。头部的值对整份源生效，
         * 频道的 #EXTINF 没有单独写 catchup 时就用它。
         */
        public String globalCatchup;
        public String globalCatchupSource;
        public String globalCatchupCorrection;

        public List<String> groups() {
            return new ArrayList<>(byGroup.keySet());
        }
    }

    private static final String DEFAULT_GROUP = "未分类";

    /** 这些分类是源的元信息行（更新时间、公告），不是频道，直接丢弃。 */
    private static boolean isMetaGroup(String g) {
        if (g == null) return false;
        return g.contains("更新时间") || g.contains("公告") || g.contains("通知")
                || g.equalsIgnoreCase("update") || g.contains("版本");
    }

    /** 清掉分类名里的 emoji / 装饰符号，纯文字更好排版。 */
    public static String cleanGroup(String g) {
        if (g == null || g.isEmpty()) return DEFAULT_GROUP;
        StringBuilder sb = new StringBuilder(g.length());
        for (int i = 0; i < g.length(); ) {
            int cp = g.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x2000) {                      // ASCII 与常见符号
                sb.appendCodePoint(cp);
            } else if (cp >= 0x4E00 && cp <= 0x9FFF) { // 汉字
                sb.appendCodePoint(cp);
            } else if (cp >= 0x3400 && cp <= 0x4DBF) {
                sb.appendCodePoint(cp);
            } else if (cp >= 0xFF00 && cp <= 0xFFEF) { // 全角
                sb.appendCodePoint(cp);
            }
            // 其余（emoji、变体选择符、零宽连接符）丢弃
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? DEFAULT_GROUP : out;
    }

    public static Playlist parse(String text) {
        if (text == null) return new Playlist();
        // 有大写 #EXTM3U 就走 M3U 分支，否则按 TXT 解析
        boolean isM3u = text.contains("#EXTM3U") || text.contains("#EXTINF");
        String cleaned = stripBom(text);
        return isM3u ? parseM3u(cleaned) : parseTxt(cleaned);
    }

    private static String stripBom(String s) {
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    // ---------------- M3U ----------------

    private static Playlist parseM3u(String text) {
        Playlist pl = new Playlist();
        // 第一行 #EXTM3U 可能带全局 catchup 声明
        int nl = text.indexOf('\n');
        String header = nl > 0 ? text.substring(0, nl) : text;
        if (header.startsWith("#EXTM3U")) {
            pl.globalCatchup = attr(header, "catchup");
            pl.globalCatchupSource = attr(header, "catchup-source");
            pl.globalCatchupCorrection = attr(header, "catchup-correction");
        }
        Map<String, Channel> byName = new LinkedHashMap<>();

        String pendingName = null;
        String pendingGroup = DEFAULT_GROUP;
        String pendingLogo = null;
        String pendingTvgId = null;
        String pendingCatchup = null, pendingCatchupSrc = null, pendingCatchupCorr = null;

        String[] lines = text.split("\r?\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF")) {
                pendingName = extractTitle(line);
                String g = attr(line, "group-title");
                pendingGroup = (g == null || g.isEmpty()) ? DEFAULT_GROUP : cleanGroup(g);
                pendingLogo = attr(line, "tvg-logo");
                pendingTvgId = attr(line, "tvg-id");
                pendingCatchup = attr(line, "catchup");
                pendingCatchupSrc = attr(line, "catchup-source");
                pendingCatchupCorr = attr(line, "catchup-correction");
                if (pendingName == null || pendingName.isEmpty()) pendingName = attr(line, "tvg-name");
                continue;
            }

            if (line.startsWith("#")) continue;   // 其它指令忽略

            // 到这里就是地址行
            if (isMetaGroup(pendingGroup)) { pendingName = null; pendingLogo = null; pendingTvgId = null; pendingGroup = DEFAULT_GROUP; continue; }
            String url = line;
            String name = pendingName;
            if (name == null || name.isEmpty()) name = nameFromUrl(url);
            addSource(pl, byName, name, pendingGroup, pendingLogo, pendingTvgId, url);

            // 回看属性绑到刚建/命中的频道上
            Channel just = byName.get(name + "|" + pendingGroup);
            if (just != null) {
                if (pendingCatchup != null) {
                    just.catchup = pendingCatchup;
                    just.catchupSource = pendingCatchupSrc;
                    just.catchupCorrection = pendingCatchupCorr;
                } else if (pl.globalCatchup != null) {
                    // 头部全局声明对该频道生效
                    just.catchup = pl.globalCatchup;
                    just.catchupSource = pl.globalCatchupSource;
                    just.catchupCorrection = pl.globalCatchupCorrection;
                }
            }

            pendingName = null;
            pendingCatchup = null;
            pendingCatchupSrc = null;
            pendingCatchupCorr = null;
            pendingLogo = null;
            pendingTvgId = null;
            pendingGroup = DEFAULT_GROUP;
        }
        return pl;
    }

    /** 取逗号后那一段作为频道名。属性里可能有逗号，所以找最后一个不在引号内的逗号。 */
    private static String extractTitle(String line) {
        int comma = -1;
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') inQuote = !inQuote;
            else if (ch == ',' && !inQuote) comma = i;
        }
        if (comma < 0 || comma == line.length() - 1) return null;
        return line.substring(comma + 1).trim();
    }

    /** 从 #EXTINF 里抠 name="value" 属性。 */
    private static String attr(String line, String key) {
        String needle = key + "=\"";
        int i = line.indexOf(needle);
        if (i < 0) {
            needle = key + "=";   // 允许不带引号
            i = line.indexOf(needle);
            if (i < 0) return null;
            int start = i + needle.length();
            int end = start;
            while (end < line.length() && line.charAt(end) != ' ' && line.charAt(end) != ',') end++;
            return line.substring(start, end).trim();
        }
        int start = i + needle.length();
        int end = line.indexOf('"', start);
        if (end < 0) return null;
        return line.substring(start, end).trim();
    }

    // ---------------- TXT ----------------

    private static Playlist parseTxt(String text) {
        Playlist pl = new Playlist();
        Map<String, Channel> byName = new LinkedHashMap<>();
        String currentGroup = DEFAULT_GROUP;

        String[] lines = text.split("\r?\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") && !line.contains(",")) continue;

            int comma = line.indexOf(',');
            if (comma <= 0) continue;

            String left = line.substring(0, comma).trim();
            String right = line.substring(comma + 1).trim();

            // 分类行： xxx,#genre#
            if ("#genre#".equalsIgnoreCase(right)) {
                currentGroup = left.isEmpty() ? DEFAULT_GROUP : cleanGroup(left);
                continue;
            }
            if (isMetaGroup(currentGroup)) continue;

            // 普通频道行： 名称,地址
            if (right.isEmpty()) continue;
            addSource(pl, byName, left, currentGroup, null, null, right);
        }
        return pl;
    }

    // ---------------- 公共 ----------------

    private static void addSource(Playlist pl, Map<String, Channel> byName,
                                  String name, String group, String logo, String tvgId, String url) {
        String key = name + "|" + group;
        Channel ch = byName.get(key);
        if (ch == null) {
            ch = new Channel(name, group, logo, tvgId, pl.all.size() + 1);
            byName.put(key, ch);
            pl.all.add(ch);
            List<Channel> list = pl.byGroup.get(group);
            if (list == null) {
                list = new ArrayList<>();
                pl.byGroup.put(group, list);
            }
            list.add(ch);
        }
        ch.sources.add(new Source(url, parseHeaders(url)));
    }

    /**
     * 从 URL 尾部解析 #EXTINF 之外的播放参数，两种写法都认：
     *   http://x/a.m3u8|User-Agent=xx|Referer=yy
     *   http://x/a.m3u8$User-Agent=xx&Referer=yy
     */
    static Map<String, String> parseHeaders(String url) {
        Map<String, String> h = null;
        int bar = url.indexOf('|');
        int dollar = url.indexOf('$');
        int cut = -1;
        if (bar > 0) cut = bar;
        if (dollar > 0 && (cut < 0 || dollar < cut)) cut = dollar;
        if (cut < 0) return null;

        String tail = url.substring(cut + 1);
        String[] parts = tail.split("\\|");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if (h == null) h = new LinkedHashMap<>();
            h.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return h;
    }

    /**
     * 按 catchup 规范拼回看地址。
     *
     * <p>支持的模板占位符（与 iptv-api / tvbox 生态一致）：
     * <pre>
     * ${(b)yyyyMMddHHmmss}   开始时间
     * ${(e)yyyyMMddHHmmss}   结束时间
     * ${utc}  ${utcend}      开始 / 结束的 Unix 秒
     * ${start} ${end}        起止时间的毫秒时间戳
     * {start} {end}          同上，另一种写法
     * </pre>
     *
     * @return 回看播放地址；该频道不支持回看或模板为空时返回 null
     */
    public static String buildCatchupUrl(Channel ch, long startMs, long endMs) {
        if (ch == null || ch.catchup == null || ch.sources.isEmpty()) return null;
        if (endMs <= startMs) return null;
        String base = cleanUrl(ch.sources.get(0).url);
        String src = ch.catchupSource;

        if (src == null || src.isEmpty()) {
            // catchup="default" 或只写了 catchup 没给模板：多数源用这个通用写法
            src = "?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}";
        }

        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyyMMddHHmmss", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));

        String out = src;
        out = out.replace("${(b)yyyyMMddHHmmss}", f.format(new java.util.Date(startMs)));
        out = out.replace("${(e)yyyyMMddHHmmss}", f.format(new java.util.Date(endMs)));
        out = out.replace("${(b)yyyy-MM-dd HH:mm:ss}", fmt(startMs));
        out = out.replace("${(e)yyyy-MM-dd HH:mm:ss}", fmt(endMs));
        out = out.replace("${utc}", String.valueOf(startMs / 1000));
        out = out.replace("${utcend}", String.valueOf(endMs / 1000));
        out = out.replace("${start}", String.valueOf(startMs));
        out = out.replace("${end}", String.valueOf(endMs));
        out = out.replace("{start}", String.valueOf(startMs));
        out = out.replace("{end}", String.valueOf(endMs));

        String mode = ch.catchup.toLowerCase(java.util.Locale.US);
        if (mode.contains("append")) {
            // 直接拼到直播地址后面
            return base + out;
        }
        if (mode.contains("shift")) {
            // 替换地址最后一段（常见于 /live/xxx/1234.m3u8 里再拼时移参数）
            return base + out;
        }
        if (mode.contains("vod")) {
            return out.startsWith("http") ? out : base + out;
        }
        // default / 其它：能拼就拼
        return base + out;
    }

    private static String fmt(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new java.util.Date(ms));
    }

    /**
     * 源里是否<b>声明</b>了回看能力（不代表服务器真的支持）。
     * 真支持与否要用 {@link CatchupProbe} 探测。
     */
    public static boolean declaredReplay(Channel ch) {
        return ch != null && ch.catchup != null && !ch.sources.isEmpty();
    }

    /** 兼容旧名。 */
    public static boolean canReplay(Channel ch) {
        return declaredReplay(ch);
    }

    /** 去掉 URL 上的参数尾巴，只留真实地址。 */
    public static String cleanUrl(String url) {
        int bar = url.indexOf('|');
        int dollar = url.indexOf('$');
        int cut = -1;
        if (bar > 0) cut = bar;
        if (dollar > 0 && (cut < 0 || dollar < cut)) cut = dollar;
        return cut < 0 ? url : url.substring(0, cut);
    }

    private static String nameFromUrl(String url) {
        String clean = cleanUrl(url);
        int slash = clean.lastIndexOf('/');
        String s = slash >= 0 ? clean.substring(slash + 1) : clean;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        return s.isEmpty() ? "未知频道" : s;
    }
}
