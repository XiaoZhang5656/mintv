package com.mintv.tv.data;

import java.util.List;

/**
 * P2P 引擎：把 tracker、分片缓存、对端拉取串起来。
 *
 * <p>设计原则：**P2P 永远是锦上添花**。
 * 任何一步失败都返回 null，让调用方回源，绝不阻塞播放。
 *
 * <p>生命周期：
 * <pre>
 *   start(channel)   开始心跳（3 秒一次），上报 have[]
 *   fetchPiece(seq)  取分片：缓存 → 对端 → null（调用方回源）
 *   stop()           停心跳，通知 tracker 离开
 * </pre>
 */
public final class P2PEngine {

    /** 心跳间隔。服务器 peer_timeout 是 30 秒，留 10 倍容错。 */
    private static final long HEARTBEAT_MS = 3000L;

    /** 从对端拉分片的超时。超了就换下一个节点，再不行回源。 */
    private static final int PEER_TIMEOUT = 2000;

    private static volatile P2PEngine inst;

    public static synchronized P2PEngine get(android.content.Context ctx) {
        if (inst == null) inst = new P2PEngine(ctx);
        return inst;
    }

    private final android.content.Context appCtx;
    private final TrackerClient tracker;
    private final PieceCache cache;

    private volatile String channelHash;
    private volatile String channelName;
    private volatile String natType = "unknown";
    private volatile String localAddr;
    private volatile int localPort;
    private volatile int pos;
    private volatile boolean running;
    private Thread heartbeat;
    /** UDP 打洞端口。固定复用，换端口等于重新打洞。 */
    private UdpHole hole;
    /** 已经打洞成功的对端，避免每次拉片都重打。 */
    private final java.util.Set<String> punched =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private P2PEngine(android.content.Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.tracker = new TrackerClient(appCtx);
        this.cache = new PieceCache(appCtx);
    }

    public PieceCache cache() { return cache; }

    public boolean running() { return running; }

    public String channelHash() { return channelHash; }

    /** 当前正在为哪个频道做 P2P（原始名称）。 */
    public String channelName() { return channelName; }

    /**
     * 开始为一个频道做 P2P。
     *
     * @param name      频道名（只用来算 hash）
     * @param natResult NAT 检测结果，决定要不要上报 addr/port
     */
    public synchronized void start(String name, NetCheck.Result natResult) {
        String h = ChannelHash.id(name);
        if (h == null) return;
        if (h.equals(channelHash) && running) return;   // 已经在为这个频道工作

        stop();

        this.channelHash = h;
        this.channelName = name;
        this.pos = 0;
        this.cache.clear();
        if (natResult != null) {
            this.natType = natResult.natType == NetCheck.NAT_FULL_CONE ? "full_cone"
                    : natResult.natType == NetCheck.NAT_RESTRICTED ? "restricted"
                    : natResult.natType == NetCheck.NAT_SYMMETRIC ? "symmetric" : "unknown";
            // 只有能被打洞的类型才上报地址，否则别人连不上，报了白占位
            if (natResult.canServe()) {
                this.localAddr = natResult.publicIp;
                this.localPort = natResult.localPort;
            } else {
                this.localAddr = null;
                this.localPort = 0;
            }
        }
        running = true;
        punched.clear();
        // 绑一个固定 UDP 端口用于打洞。失败不影响播放，只是没打洞能力。
        if (hole == null) hole = UdpHole.bind(0);

        heartbeat = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        TrackerClient.Cfg cfg = tracker.join(channelHash, pos,
                                cache.seqs(), localAddr, localPort, natType);
                        if (cfg != null) {
                            // 服务器说窗口多大，就把窗口外的清掉
                            cache.setWindow(Math.max(0, pos - cfg.windowSize));
                        }
                    } catch (Throwable t) {
                        android.util.Log.w("P2P", "心跳异常", t);
                    }
                    try { Thread.sleep(HEARTBEAT_MS); } catch (InterruptedException e) { break; }
                }
            }
        }, "p2p-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
        android.util.Log.w("P2P", "start ch=" + h + " nat=" + natType
                + " addr=" + localAddr + ":" + localPort);
    }

    /** 停止：通知服务端离开，清缓存。 */
    public synchronized void stop() {
        if (!running && heartbeat == null) return;
        running = false;
        if (heartbeat != null) { heartbeat.interrupt(); heartbeat = null; }
        try { tracker.leave(); } catch (Throwable ignored) { }
        channelHash = null;
        channelName = null;
        punched.clear();
        if (hole != null) { try { hole.close(); } catch (Throwable ignored) { } hole = null; }
        android.util.Log.w("P2P", "stop");
    }

    /** 播放进度变了：影响上报的 pos 和窗口。 */
    public void setPos(int seq) {
        if (seq > pos) pos = seq;
    }

    /** 从源站拿到的分片，存进缓存供别人拉。 */
    public void onPieceFetched(int seq, byte[] data) {
        if (!running || data == null) return;
        cache.put(seq, data);
    }

    /**
     * 取一个分片。
     *
     * @return 分片数据；拿不到返回 null（调用方应回源）
     */
    public byte[] fetchPiece(int seq) {
        if (!running || channelHash == null) return null;

        // 1) 自己缓存有 —— 直接用
        PieceCache.Piece local = cache.get(seq);
        if (local != null) return local.data;

        // 2) 问 tracker 谁有
        List<TrackerClient.Peer> peers = tracker.peers(channelHash, seq);
        if (peers.isEmpty()) return null;

        // 3) 依次试。先直连（成本最低），不行再打洞，打完再试一次。
        for (TrackerClient.Peer p : peers) {
            String key = p.addr + ":" + p.port;

            byte[] data = fetchFromPeer(p, seq);
            if (data != null) {
                cache.put(seq, data);
                android.util.Log.w("P2P", "直连 " + key + " 拿到第 " + seq + " 片");
                return data;
            }

            // 双方都在拉同一个分片，各自都会向对方发包，所以"自然相遇"，
            // 不需要额外的时机协调协议。
            if (hole != null && !punched.contains(key)) {
                boolean ok = false;
                try {
                    ok = hole.punch(p.addr, p.port);
                } catch (Throwable t) {
                    android.util.Log.w("P2P", "打洞异常 " + key, t);
                }
                if (ok) {
                    punched.add(key);
                    data = fetchFromPeer(p, seq);
                    if (data != null) {
                        cache.put(seq, data);
                        android.util.Log.w("P2P", "打洞后 " + key
                                + " 拿到第 " + seq + " 片");
                        return data;
                    }
                }
            }
        }
        return null;
    }

    /** 从一个对端拉分片。失败（超时/校验不过）返回 null。 */
    private byte[] fetchFromPeer(TrackerClient.Peer p, int seq) {
        java.net.HttpURLConnection conn = null;
        try {
            String url = "http://" + p.addr + ":" + p.port
                    + "/mintv/piece?ch=" + channelHash + "&seq=" + seq;
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(PEER_TIMEOUT);
            conn.setReadTimeout(PEER_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");

            if (conn.getResponseCode() != 200) return null;

            java.io.InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(2 << 20);
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 32 << 20) return null;   // 超过 32MB 判定异常
                bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();
            if (data.length == 0) return null;

            // 校验：对端如果带了哈希就比对，没带就相信它（首片靠源站建立信任）
            String expect = conn.getHeaderField("X-Piece-SHA256");
            if (expect != null && !expect.isEmpty()) {
                String actual = PieceCache.sha256(data);
                if (actual == null || !actual.equalsIgnoreCase(expect)) {
                    android.util.Log.w("P2P", "分片校验失败 seq=" + seq);
                    return null;
                }
            }
            return data;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }
}
