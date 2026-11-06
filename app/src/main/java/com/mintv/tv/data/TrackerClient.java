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

        // 缓存容量也由服务器控制。
        //
        // 之前 PieceCache 里写死 MEM_MAX=10 / DISK_MAX=20，跟服务器下发的
        // window_size=20 完全脱节 —— 服务器说窗口 20 片，本地只留 10 片，
        // 一半的 peer 请求注定落空。而且按片数算不按字节算，
        // 现在这种 9.35MB 一片的源，20 片就是 187MB，4K 源能到 600MB+。
        public int memMaxSegs = 10;
        public int diskMaxSegs = 30;
        public long memMaxBytes = 32L * 1024 * 1024;
        public long diskMaxBytes = 200L * 1024 * 1024;

        /**
         * P2P 分片请求的签名密钥。
         *
         * <p>拉片时带 {@code &t=<毫秒>&s=<hmac>}，双方用同一个 key 校验。
         * 随机扫描器和手写脚本算不出 hmac，拿不到分片。
         *
         * <p>局限：key 在客户端里，反编译能拿到。这是提高门槛，不是绝对安全。
         */
        public String p2pSecret = "";
    }

    /** 一个候选节点。 */
    public static final class Peer {
        public String addr;
        public int port;
        public int rtt;
        public double load;
        /**
         * 服务端给的"播放位置距离" = |peer.pos - 请求的 seq|。
         *
         * <p>越小说明对方手上那片越新鲜、越可能还没被清理。直播场景下
         * 这个比 rtt 更该优先 —— 位置差太远的分片可能已经过期。
         * 服务端没给时保持 -1。
         */
        public int dist = -1;
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
                cfg.memMaxSegs      = c.optInt("mem_max_segs", cfg.memMaxSegs);
                cfg.diskMaxSegs     = c.optInt("disk_max_segs", cfg.diskMaxSegs);
                cfg.memMaxBytes     = c.optLong("mem_max_bytes", cfg.memMaxBytes);
                cfg.diskMaxBytes    = c.optLong("disk_max_bytes", cfg.diskMaxBytes);
                cfg.p2pSecret       = c.optString("p2p_secret", cfg.p2pSecret);
            }
            return cfg;
        } catch (Throwable t) {
            // 把异常类型拼进消息里 —— 之前只有 "join 失败"，栈又被系统的
            // GC 日志冲掉，根本看不出是超时、DNS 还是 401。
            android.util.Log.w("Tracker", "join 失败: " + t, t);
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
            // 注意：不是 /api/peers。
            //
            // /api/peers   是后台监控面板接口（返回全节点明细），线上还有
            //              Basic Auth 保护，客户端调它只会拿到 401。
            // /api/fetch   才是候选调度，而且参数从 query string 读，
            //              不读 JSON body。
            StringBuilder u = new StringBuilder("/api/fetch?channel=");
            u.append(java.net.URLEncoder.encode(channel, "UTF-8"));
            u.append("&seq=").append(seq);
            u.append("&peerId=").append(java.net.URLEncoder.encode(peerId, "UTF-8"));

            JSONObject r = post(u.toString(), new JSONObject());
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
                p.dist = o.has("dist") ? o.optInt("dist", -1) : -1;
                // 自己也在列表里，跳过
                if (p.addr.isEmpty() || p.port <= 0) continue;
                out.add(p);
            }
        } catch (Throwable t) {
            android.util.Log.w("Tracker", "peers 失败: " + t, t);
        }
        return out;
    }

    /**
     * 立刻上报「我刚拿到了哪些分片」。
     *
     * <p>为什么要单独做：心跳是 15 秒一次，B 从 A 那里拉到分片之后，
     * C/D/E 要等最多 15 秒才知道 B 也有 —— 这 15 秒里所有请求继续压在 A 身上，
     * 热点节点就是这么被打爆的。
     *
     * <p>拿到新分片后异步调一次，几百字节的请求，开销可以忽略。
     */
    public void reportHave(String channel, int pos, List<Integer> have) {
        if (channel == null || have == null || have.isEmpty()) return;
        try {
            JSONObject b = new JSONObject();
            b.put("peerId", peerId);
            b.put("channel", channel);
            b.put("pos", pos);
            JSONArray arr = new JSONArray();
            for (Integer s : have) arr.put(s.intValue());
            b.put("have", arr);
            post("/api/join", b);
        } catch (Throwable t) {
            android.util.Log.w("Tracker", "reportHave 失败: " + t);
        }
    }

    /**
     * 上报这段时间的吞吐和请求数。
     *
     * <p>服务端靠这个算「谁真的有带宽」。没有它，调度只能按
     * 「谁闲」（hits 少）来分，会挑到闲但慢的节点。
     *
     * <p>上报的是**增量**，不是累计值 —— 服务端按增量/时间算瞬时带宽。
     */
    public void reportStat(String channel, long upBytes, long downBytes,
                           long originBytes, int upReq, int downReq) {
        if (channel == null) return;
        if (upBytes <= 0 && downBytes <= 0 && originBytes <= 0
                && upReq <= 0 && downReq <= 0) return;
        try {
            JSONObject b = new JSONObject();
            b.put("peerId", peerId);
            b.put("channel", channel);
            b.put("up", upBytes);
            b.put("down", downBytes);
            // 回源字节。跟 down 分开算 —— 单台设备没有 peer 时 down 恒为 0，
            // 只有 origin 能看出链路是活的；两者比例也就是 P2P 的实际收益。
            b.put("origin", originBytes);
            b.put("upReq", upReq);
            b.put("downReq", downReq);
            post("/api/stat", b);
        } catch (Throwable t) {
            android.util.Log.w("Tracker", "reportStat 失败: " + t);
        }
    }

    /** 上报一次拉片失败，服务端会降低该节点的优先级。 */
    public void reportPeerFail(String channel, String failAddr) {
        if (channel == null || failAddr == null) return;
        try {
            JSONObject b = new JSONObject();
            b.put("peerId", peerId);
            b.put("channel", channel);
            b.put("failAddr", failAddr);
            post("/api/stat", b);
        } catch (Throwable ignored) { }
    }

    /**
     * 给分片请求算签名。
     *
     * <p>算法：{@code hmac_sha256(secret, channel + ":" + seq + ":" + t)} 前 16 位十六进制。
     * 带时间戳是为了让签名有时效 —— 服务端拒掉 60 秒前的请求，
     * 抓包重放也就 60 秒的窗口。
     */
    public static String signPiece(String secret, String channel, int seq, long t) {
        if (secret == null || secret.isEmpty()) return "";
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes("UTF-8"), "HmacSHA256"));
            byte[] d = mac.doFinal((channel + ":" + seq + ":" + t).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable e) {
            return "";
        }
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
            // 先试着自己解析（带超时 + 缓存），把 DNS 从连接预算里摘出去。
            //
            // 但**解析失败不能直接放弃** —— 之前写 return null，
            // 结果 DNS 慢一点就整个 tracker 连不上（online 变 0）。
            // 解析不出来就交给 HttpURLConnection 自己去解，它至少会按
            // setConnectTimeout 超时，而不是我们主动放弃。
            String full = BASE + path;
            Net.Prepared np = Net.prepare(full);
            if (np != null) {
                conn = (HttpURLConnection) new URL(np.url).openConnection();
                if (np.hostHeader != null) conn.setRequestProperty("Host", np.hostHeader);
            } else {
                android.util.Log.w("Tracker", "预解析失败，交给系统解析: " + full);
                conn = (HttpURLConnection) new URL(full).openConnection();
            }
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Connection", "close");

            // 敏感接口加密请求体。
            //
            // /api/join 带 channel（能反查在看哪个台）、have[]（缓存清单）、
            // addr/port（公网地址）；/api/stat 带流量。这些不该明文过网。
            //
            // /api/fetch 不加密：它是高频调用（每次拉片都问），
            // RSA 解密对服务端有开销，而它返回的只是 peer 地址，
            // 而且已经有 HMAC 签名挡住爬虫。
            final String[] sessKeyHolder = new String[1];
            byte[] sessKey = null;
            String payload = body.toString();
            if (path.startsWith("/api/join") || path.startsWith("/api/stat")) {
                // 敏感接口**必须**加密。加密失败就不发 ——
                // 之前是失败回落明文，等于加密形同虚设：
                // 只要在传输链路上做点手脚（比如注入证书失败、改包），
                // 就会静默降级成明文，用户完全无感。
                //
                // 现在失败直接报错，宁可连不上也不能明文泄露
                // channel / have / addr 这些信息。
                if (!PayloadCrypto.available()) {
                    android.util.Log.w("Tracker", path + " 跳过：公钥不可用");
                    return null;
                }
                PayloadCrypto.Enc er = PayloadCrypto.encrypt2(payload);
                if (er == null) {
                    android.util.Log.w("Tracker", path + " 跳过：加密失败");
                    return null;
                }
                payload = er.payload;
                sessKey = er.aesKey;      // 留着解响应
            }
            byte[] data = payload.getBytes("UTF-8");
            OutputStream o = conn.getOutputStream();
            try { o.write(data); o.flush(); } finally { o.close(); }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                android.util.Log.w("Tracker", path + " HTTP " + code
                        + " u=" + BASE);
                return null;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(2048);
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String respText = new String(bos.toByteArray(), "UTF-8");
            // 响应用请求时的会话 key 解回来
            respText = PayloadCrypto.decryptResponse(sessKey, respText);
            return new JSONObject(respText);
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
