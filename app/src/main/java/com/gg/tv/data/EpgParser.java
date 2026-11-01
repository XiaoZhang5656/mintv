package com.gg.tv.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * XMLTV EPG 解析器（Android 自带的 SAX，不引第三方库）。
 *
 * <pre>
 * &lt;tv&gt;
 *   &lt;channel id="CCTV1"&gt;&lt;display-name&gt;CCTV-1 综合&lt;/display-name&gt;&lt;/channel&gt;
 *   &lt;programme start="20261101200000 +0800" stop="20261101204800 +0800" channel="CCTV1"&gt;
 *     &lt;title&gt;伟大的长征第38集&lt;/title&gt;
 *     &lt;desc&gt;...&lt;/desc&gt;
 *   &lt;/programme&gt;
 * &lt;/tv&gt;
 * </pre>
 *
 * 国内 EPG 源（51zmt 等）的时间格式固定是 <code>yyyyMMddHHmmss +0800</code>，按这个解。
 * 频道 id 与播放源的 tvg-id 不一定完全一致，所以额外建一张「规范化名称 → channelId」表，
 * 用频道名做兜底匹配。
 */
public final class EpgParser {

    public static final class Program {
        public final long startMs;
        public final long stopMs;
        public final String title;

        Program(long s, long e, String t) {
            startMs = s;
            stopMs = e;
            title = t;
        }

        public boolean isLive(long now) { return now >= startMs && now < stopMs; }
    }

    /** EPG 解析结果：节目表 + 台名映射。 */
    public static final class Epg {
        public final Map<String, List<Program>> byChannel = new HashMap<>();
        /** 规范化台名 → channelId，用于名称兜底匹配 */
        public final Map<String, String> nameToId = new HashMap<>();

        public List<Program> of(String channelId) {
            return channelId == null ? null : byChannel.get(channelId);
        }
    }

    /** 解析 XMLTV。返回的 Epg 永远非 null，失败就是空表。 */
    public static Epg parse(byte[] raw) {
        byte[] xml = gunzipIfNeeded(raw);
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            // 关键：XMLTV 头部带 <!DOCTYPE tv SYSTEM "xmltv.dtd">，
            // 不关掉外部 DTD 加载，解析器会去拉这个文件，拉不到就整个文档作废。
            setFeatureQuietly(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            setFeatureQuietly(f, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureQuietly(f, "http://xml.org/sax/features/external-parameter-entities", false);
            SAXParser p = f.newSAXParser();
            Handler h = new Handler();

            InputSource src = new InputSource(new ByteArrayInputStream(xml));
            src.setEncoding("UTF-8");
            p.parse(src, h);
            Epg epg = h.epg;
            for (List<Program> list : epg.byChannel.values()) {
                Collections.sort(list, new java.util.Comparator<Program>() {
                    @Override public int compare(Program a, Program b) {
                        return Long.compare(a.startMs, b.startMs);
                    }
                });
            }
            return epg;
        } catch (Exception e) {
            android.util.Log.w("TVUI", "EPG 解析失败: " + e, e);
            return new Epg();
        }
    }

    private static void setFeatureQuietly(SAXParserFactory f, String name, boolean value) {
        try { f.setFeature(name, value); } catch (Exception ignored) { }
    }

    private static byte[] gunzipIfNeeded(byte[] raw) {
        if (raw == null || raw.length < 2) return new byte[0];
        if ((raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(raw.length * 4);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } catch (Exception e) {
                return raw;   // 当作明文继续尝试
            }
        }
        return raw;
    }

    private static final class Handler extends DefaultHandler {
        final Map<String, List<Program>> byChannel = new HashMap<>();
        final Map<String, String> nameToId = new HashMap<>();

        final Epg epg = new Epg();
        String curChannelId;
        boolean inDisplayName;

        // programme 上下文
        String curChannel;
        long curStart, curStop;
        String curTitle;
        boolean inTitle;

        @Override
        public void startElement(String uri, String local, String qName, Attributes a) {
            if ("programme".equals(qName)) {
                curChannel = a.getValue("channel");
                curStart = parseTime(a.getValue("start"));
                curStop = parseTime(a.getValue("stop"));
                curTitle = null;
                inTitle = false;
            } else if ("title".equals(qName)) {
                inTitle = true;
            } else if ("channel".equals(qName)) {
                curChannelId = a.getValue("id");
            } else if ("display-name".equals(qName)) {
                inDisplayName = curChannelId != null;
            }
        }

        @Override
        public void characters(char[] ch, int start, int len) {
            if (inDisplayName && curChannelId != null) {
                String n = new String(ch, start, len).trim();
                if (!n.isEmpty()) epg.nameToId.put(normalize(n), curChannelId);
            }
            if (inTitle) {
                String s = new String(ch, start, len).trim();
                if (!s.isEmpty()) curTitle = curTitle == null ? s : curTitle + s;
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) {
            if ("title".equals(qName)) {
                inTitle = false;
            } else if ("display-name".equals(qName)) {
                inDisplayName = false;
            } else if ("channel".equals(qName)) {
                curChannelId = null;
            } else if ("programme".equals(qName)) {
                if (curChannel != null && curTitle != null && curStop > curStart) {
                    List<Program> list = epg.byChannel.get(curChannel);
                    if (list == null) {
                        list = new ArrayList<>();
                        epg.byChannel.put(curChannel, list);
                    }
                    list.add(new Program(curStart, curStop, curTitle));
                }
                curChannel = null;
                curTitle = null;
            }
        }
    }

    /** 20261101200000 +0800 → 毫秒时间戳（public 便于单元测试） */
    public static long parseTime(String s) {
        if (s == null || s.length() < 14) return 0L;
        try {
            String date = s.substring(0, 14);
            String tz = s.length() > 15 ? s.substring(15).trim() : "+0800";
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("GMT" + (tz.startsWith("+") ? tz : "+0800")));
            Date d = f.parse(date);
            return d == null ? 0L : d.getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    // ================= 频道匹配 =================

    /**
     * 把播放源的频道映射到 EPG 的 channelId。
     * 顺序：tvg-id 精确 → 规范化名称精确 → 包含匹配。
     */
    public static String matchChannelId(String tvgId, String channelName, Epg epg) {
        if (epg == null) return null;
        if (tvgId != null && epg.byChannel.containsKey(tvgId)) return tvgId;

        String key = normalize(channelName);
        if (key.isEmpty()) return null;

        // 1) 台名表精确命中（51zmt 的 display-name 就是台名）
        String byName = epg.nameToId.get(key);
        if (byName != null) return byName;

        // 2) channel id 本身是台名的情况
        for (String id : epg.byChannel.keySet()) {
            if (normalize(id).equals(key)) return id;
        }
        // 3) 包含匹配：CCTV1 vs CCTV1综合
        for (Map.Entry<String, String> e : epg.nameToId.entrySet()) {
            if (e.getKey().contains(key) || key.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** 去掉空格、连字符、CCTV 后缀差异等，只留汉字和数字字母。 */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 0x4E00 && c <= 0x9FFF)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 取当前正在播的节目。 */
    public static Program live(List<Program> list, long now) {
        if (list == null) return null;
        for (Program p : list) {
            if (p.isLive(now)) return p;
        }
        return null;
    }

    /** 取 live 之后的下一个节目。 */
    public static Program next(List<Program> list, long now) {
        if (list == null) return null;
        for (Program p : list) {
            if (p.startMs > now) return p;
        }
        return null;
    }

    public static String hm(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(ms));
    }
}
