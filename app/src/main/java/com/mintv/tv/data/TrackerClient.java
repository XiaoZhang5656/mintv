package com.mintv.tv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2P tracker 客户端。
 *
 * <p>对接的接口（都走 HTTP，不需要认证）：
 * <pre>
 *   POST /api/join   加入频道 + 心跳 + 上报 have[]
 *   POST /api/peers  查某个分片有哪些节点有
 *   POST /api/leave  离开频道
 * </pre>
 *
 * <p>这个类只负责跟服务器说话，不碰分片内容。
 */
public final class TrackerClient {

    /**
     * tracker 地址。
     *
     * <p>走域名而不是 IP：换服务器时客户端不用改。
     * 这个域名解析到 Cloudflare（2086 在 CF 支持的 HTTP 端口列表里，
     * 所以代理能过），好处是隐藏源站 IP，坏处是 CF 免费版有请求数限制 ——
     * 人数上去之后要么降心跳频率，要么改回直连 IP。
     */
    public static final String BASE = "http://yun.spup.yunshipin.bbroot.com:2086";

    private static final int TIMEOUT = 6000;

    /** 服务器下发的配置，join 时拿到。 */
    public static final class Cfg {
        public int peerTimeout = 30;
        public int windowSize = 20;
        public int maxPeersReturn = 3;
        public int minBufferSegs = 2;
        public int uploadMinSegs = 3;
    }

    /** 一个候选节点。 */
    public static final class Peer {
        public String addr;
        public int port;
        public int rtt;
        public double load;
    }

    private final android.content.Context appCtx;
    private final String peerId;
    private Cfg cfg = new Cfg();

    public TrackerClient(android.content.Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        // 设备级稳定 ID：存一次就一直用，避免每次启动换身份
        this.peerId = loadOrCreatePeerId(appCtx);
    }

    public String peerId() { return peerId; }

    public Cfg config() { return cfg; }

    /**
     * 加入频道 / 心跳 / 上报缓存清单。
     *
     * <p>三种情况共用这一个接口：服务端按 peerId 更新最后活跃时间和 have。
     *
     * @param channel 频道 ID（ChannelHash.id(名称)）
     * @param pos     当前播放到的分片序号
     * @param have    自己缓存了哪些分片序号（可为 null）
     * @param addr    自己的可连接地址（对称型 NAT 时传 null）
     * @param port    自己的端口（同上）
     * @param nat     本机 NAT 类型名
     * @return 服务器下发的配置；失败返回 null（不影响播放）
     */
    public Cfg join(String channel, int pos, List<Integer> have,
                    String addr, int port, String nat) {
        try {
            JSONObject b = new JSONObject();
            b.put("peerId", peerId);
            b.put("channel", channel);
            b.put("pos", pos);
            b.put("nat", nat == null ? "unknown" : nat);
            if (addr != null && port > 0) {
                b.put("addr", addr);
                b.put("port", port);
            }
            if (have != null && !have.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (Integer s : have) arr.put(s.intValue());
                b.put("have", arr);
            }

            JSONObject r = post("/api/join", b);
            if (r == null || !r.optBoolean("ok", false)) return null;

            JSONObject c = r.optJSONObject("config");
            if (c != null) {
                cfg.peerTimeout     = c.optInt("peer_timeout", cfg.peerTimeout);
                cfg.windowSize      = c.optInt("window_size", cfg.windowSize);
                cfg.maxPeersReturn  = c.optInt("max_peers_return", cfg.maxPeersReturn);
                cfg.minBufferSegs   = c.optInt("min_buffer_segs", cfg.minBufferSegs);
                cfg.uploadMinSegs   = c.optInt("upload_min_segs", cfg.uploadMinSegs);
            }
            return cfg;
        } catch (Throwable t) {
            android.util.Log.w("Tracker", "join 失败", t);
            return null;
        }
    }

    /**
     * 问：第 seq 片谁有？
     *
     * @return 候选节点列表（最多 maxPeersReturn 个）；失败返回空列表
     */
    public List<Peer> peers(String channel, int seq) {
        List<Peer> out = new ArrayList<>();
        try {
            JSONObject b = new JSONObject();
            b.put("channel", channel);
            b.put("seq", seq);

            JSONObject r = post("/api/peers", b);
            if (r == null) return out;
            JSONArray arr = r.optJSONArray("peers");
            if (arr == null) return out;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Peer p = new Peer();
                p.addr = o.optString("addr", "");
                p.port = o.optInt("port", 0);
                p.rtt = o.optInt("rtt", 0);
                p.load = o.optDouble("load", 0);
                // 自己也在列表里，跳过
                if (p.addr.isEmpty() || p.port <= 0) continue;
                out.add(p);
            }
        } catch (Throwable t) {
            android.util.Log.w("Tracker", "peers 失败", t);
        }
        return out;
    }

    /** 离开频道：服务端立刻把自己摘掉，不用等心跳超时。 */
    public void leave() {
        try {
            JSONObject b = new JSONObject();
            b.put("peerId", peerId);
            post("/api/leave", b);
        } catch (Throwable ignored) { }
    }

    // ---------------- 底层 HTTP ----------------

    private JSONObject post(String path, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Connection", "close");

            byte[] data = body.toString().getBytes("UTF-8");
            OutputStream o = conn.getOutputStream();
            try { o.write(data); o.flush(); } finally { o.close(); }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                android.util.Log.w("Tracker", path + " HTTP " + code);
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2048);
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            android.util.Log.w("Tracker", path + " 失败", t);
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }

    // ---------------- peerId 持久化 ----------------

    private static String loadOrCreatePeerId(android.content.Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "peer_id.txt");
            if (f.isFile() && f.length() > 0) {
                byte[] buf = new byte[(int) f.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                try { in.read(buf); } finally { in.close(); }
                String s = new String(buf, "UTF-8").trim();
                if (s.length() >= 8) return s;
            }
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            java.io.FileOutputStream o = new java.io.FileOutputStream(f);
            try { o.write(id.getBytes("UTF-8")); } finally { o.close(); }
            return id;
        } catch (Throwable t) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }
}
