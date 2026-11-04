package com.mintv.tv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 脚本运行日志。
 *
 * <p>内存环形缓冲，只留最近 {@link #CAPACITY} 条 —— 电视盒子内存紧，
 * 脚本日志又可能刷得很快，不设上限迟早吃掉几十 MB。
 *
 * <p>为什么要有这个东西：脚本是用户写的，出错时如果只能看 logcat，
 * 那对普通用户等于黑盒。页面上的日志是他们唯一的排查入口。
 */
public final class ScriptLog {

    public static final int CAPACITY = 500;

    public enum Level { INFO, WARN, ERROR }

    public static final class Entry {
        public long id;
        public long time;
        public Level level;
        public String script;
        public String message;
        public String stack;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("time", time);
                o.put("level", level.name().toLowerCase(Locale.US));
                o.put("script", script == null ? "" : script);
                o.put("message", message == null ? "" : message);
                if (stack != null) o.put("stack", stack);
            } catch (Exception ignored) { }
            return o;
        }

        /** 纯文本形式，用于"复制"和导出。 */
        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(fmt(time)).append("] ")
              .append(level.name()).append(' ');
            if (script != null && !script.isEmpty()) sb.append('[').append(script).append("] ");
            sb.append(message == null ? "" : message);
            if (stack != null && !stack.isEmpty()) {
                sb.append('\n');
                for (String line : stack.split("\n")) sb.append("  ").append(line).append('\n');
            }
            return sb.toString();
        }

        private static String fmt(long ms) {
            return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
        }
    }

    private static final ArrayDeque<Entry> BUFFER = new ArrayDeque<>();
    private static long nextId = 1;

    private ScriptLog() { }

    public static void info(String script, String msg) { add(Level.INFO, script, msg, null); }
    public static void warn(String script, String msg) { add(Level.WARN, script, msg, null); }
    public static void error(String script, String msg) { add(Level.ERROR, script, msg, null); }
    public static void error(String script, String msg, Throwable t) {
        add(Level.ERROR, script, msg, t == null ? null : stackOf(t));
    }

    public static synchronized void add(Level lv, String script, String msg, String stack) {
        Entry e = new Entry();
        e.id = nextId++;
        e.time = System.currentTimeMillis();
        e.level = lv;
        e.script = script;
        e.message = msg;
        e.stack = stack;
        BUFFER.addLast(e);
        while (BUFFER.size() > CAPACITY) BUFFER.removeFirst();
    }

    /** @param sinceId 只取比它新的；0 表示全部 */
    public static synchronized List<Entry> query(long sinceId, Level minLevel, String script) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : BUFFER) {
            if (e.id <= sinceId) continue;
            if (minLevel != null && e.level.ordinal() < minLevel.ordinal()) continue;
            if (script != null && !script.isEmpty() && !script.equals(e.script)) continue;
            out.add(e);
        }
        return out;
    }

    public static synchronized int size() { return BUFFER.size(); }

    public static synchronized int countOf(Level lv) {
        int n = 0;
        for (Entry e : BUFFER) if (e.level == lv) n++;
        return n;
    }

    public static synchronized void clear() { BUFFER.clear(); }

    public static synchronized String exportText(List<Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) sb.append(e.toText());
        return sb.toString();
    }

    public static JSONArray toJson(List<Entry> list) {
        JSONArray arr = new JSONArray();
        for (Entry e : list) arr.put(e.toJson());
        return arr;
    }

    private static String stackOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
        int n = 0;
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append("  at ").append(el).append('\n');
            if (++n >= 8) break;
        }
        return sb.toString().trim();
    }
}
