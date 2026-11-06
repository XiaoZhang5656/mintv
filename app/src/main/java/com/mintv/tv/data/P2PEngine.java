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
    /**
     * 心跳间隔。
     *
     * <p>原来 3 秒 —— 抓包看是一屏全是 /api/join，每个请求响应 900 多字节，
     * 一天下来光心跳就是几十 MB 流量，而且对 tracker 是无谓压力。
     *
     * <p>服务端 peer_timeout 是 30 秒，15 秒心跳有两次容错，
     * 网络抖动不会误判离线。
     */
    private static final long HEARTBEAT_MS = 15000L;

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

    // ---- 吞吐统计（用于上报给 tracker，让它算谁真的有带宽）----
    private volatile long statUpBytes = 0;
    private volatile long statDownBytes = 0;
    private volatile int statUpReq = 0;
    private volatile int statDownReq = 0;
    private volatile long statOriginBytes = 0;
    private volatile long statUpSent = 0;
    private volatile long statDownSent = 0;
    private volatile long statOriginSent = 0;
    private volatile int statUpReqSent = 0;
    private volatile int statDownReqSent = 0;

    /** 心跳里带上增量，服务端按 增量/时间 算带宽。 */
    private void flushStats() {
        final long up = statUpBytes - statUpSent;
        final long down = statDownBytes - statDownSent;
        final int ur = statUpReq - statUpReqSent;
        final int dr = statDownReq - statDownReqSent;
        final long origin = statOriginBytes - statOriginSent;
        if (up <= 0 && down <= 0 && origin <= 0 && ur <= 0 && dr <= 0) return;
        statUpSent = statUpBytes;
        statDownSent = statDownBytes;
        statOriginSent = statOriginBytes;
        statUpReqSent = statUpReq;
        statDownReqSent = statDownReq;
        final String ch = channelHash;
        new Thread(new Runnable() {
            @Override public void run() {
                tracker.reportStat(ch, up, down, origin, ur, dr);
            }
        }, "p2p-stat").start();
    }

    /**
     * 从源站拉了一片（不走 peer），记回源量。
     *
     * <p>单台设备没有 peer 时 down 恒为 0，只有这个能看出链路是活的，
     * 也能算出 P2P 到底省了多少带宽。
     */
    public void onOriginFetched(int bytes) {
        if (bytes > 0) statOriginBytes += bytes;
    }

    /** 别的节点从我这拿了一片，记上传量。 */
    public void onServed(int bytes) {
        statUpBytes += bytes;
        statUpReq++;
    }

    /**
     * 可信哈希表：seq → sha256。
     *
     * <p>来源只有两个，都不来自「发分片给我的那个节点」：
     * <ul>
     *   <li>我们自己从源站拉的（回源一次就记一次）</li>
     *   <li>tracker 聚合后的多数哈希（见 /api/fetch 的 sha 字段）</li>
     * </ul>
     *
     * <p>为什么必须有：对端在 X-Piece-SHA256 头里发的哈希是它自己算的，
     * 伪造内容 + 对应哈希能完全通过校验。那个头只能发现传输损坏，
     * 挡不住投毒。
     */
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> trustedSha =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** tracker 聚合出来的哈希（多数节点一致的那个）。 */
    private final java.util.concurrent.ConcurrentHashMap<Integer, String> serverSha =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 服务器下发的阈值，默认值只在第一次 join 之前有效。 */
    private volatile String p2pSecret = "";

    /**
     * 分片传输密钥。
     *
     * <p>优先用 .so 里的（反编译 dex 拿不到），so 不可用时回落到
     * tracker 下发的 p2p_secret 前 16 字节。两者都没有就不加密。
     */
    private volatile byte[] pieceKey = null;

    /** 供 RemoteServer 加密出口分片用。 */
    public byte[] pieceKey() { return pieceKey; }

    /** 给分片请求算签名；没拿到 secret 时返回空串（服务端会拒）。 */
    public String p2pSecret() { return p2pSecret; }

    private volatile int minBufferSegs = 2;
    private volatile int uploadMinSegs = 3;
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
        }
        running = true;
        punched.clear();

        // 绑一个固定 UDP 端口用于打洞。失败不影响播放，只是没打洞能力。
        if (hole == null) hole = UdpHole.bind(0);

        // 上报的必须是打洞 socket 自己的对外地址。
        //
        // 之前报的是 NetCheck 那个临时 socket 的 localPort —— 那个 socket
        // 检测完就 close 了，端口对应的映射早没了，别人按这个地址发过来
        // 是打不通的。
        //
        // 对称型也上报：映射端口虽然随目标变，但过滤器宽松的对称型
        // 照样能被陌生来源打进来，报一个"曾经有效"的映射比不报强。
        this.localAddr = null;
        this.localPort = 0;
        if (hole != null && hole.discover()) {
            this.localAddr = hole.publicAddr();
            this.localPort = hole.publicPort();
        }

        heartbeat = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        TrackerClient.Cfg cfg = tracker.join(channelHash, pos,
                                cache.seqs(), localAddr, localPort, natType);
                        if (cfg != null) {
                            // 服务器下发的一切都照做：
                            //   窗口 → 清掉播放位置之前的旧片
                            //   容量 → 内存/磁盘的片数和字节上限
                            cache.setWindow(Math.max(0, pos - cfg.windowSize));
                            cache.setLimits(cfg.memMaxSegs, cfg.diskMaxSegs,
                                    cfg.memMaxBytes, cfg.diskMaxBytes);
                            p2pSecret = cfg.p2pSecret == null ? "" : cfg.p2pSecret;
                            // 传输密钥：**只认 native**，不回落 tracker。
                            //
                            // 为什么不能回落：native 的 key 是固定的，tracker 的
                            // p2p_secret 每次重启都随机生成。如果 A 用 native、
                            // B 回落 tracker，两边 key 不同 —— 密文互相解不开，
                            // P2P 直接瘫。而且 tracker 一重启，所有回落的设备
                            // 全部失联。
                            //
                            // 宁可不加密，也不能出现「半加密」：so 不可用时
                            // 所有人都不加密，至少互通。
                            byte[] nk = PieceCrypto.nativeOrNull();
                            pieceKey = nk;
                            if (nk != null) {
                                android.util.Log.i("P2P", "传输加密已启用，key="
                                        + PieceCrypto.fingerprint(nk) + " (native)");
                            } else {
                                android.util.Log.i("P2P", "传输不加密：native 库不可用"
                                        + "（" + android.os.Build.SUPPORTED_ABIS[0]
                                        + " 没有对应的 so，只有 armeabi-v7a 版）");
                            }
                            flushStats();
                            P2PEngine.this.minBufferSegs = cfg.minBufferSegs;
                            P2PEngine.this.uploadMinSegs = cfg.uploadMinSegs;
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
        // 自己从源站拿到的，这份哈希可信，记下来供后续校验 peer 数据
        try {
            String h = PieceCache.sha256(data);
            if (h != null) {
                trustedSha.put(seq, h);
                // 报给 tracker 做多数聚合，别的节点拉这片时能拿它当基准
                final String ch = channelHash;
                if (ch != null) tracker.reportHash(ch, seq, h);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * 取一个分片。
     *
     * @return 分片数据；拿不到返回 null（调用方应回源）
     */
    /** 异步上报 have[]，不阻塞下载。同一秒内多次触发会合并。 */
    private volatile long lastReportAt = 0;

    private void reportHaveAsync() {
        final long now = System.currentTimeMillis();
        if (now - lastReportAt < 1000) return;   // 一秒最多一次，合并密集调用
        lastReportAt = now;
        final String ch = channelHash;
        final int ps = pos;
        if (ch == null) return;
        final List<Integer> have = cache.seqs();
        new Thread(new Runnable() {
            @Override public void run() {
                tracker.reportHave(ch, ps, have);
            }
        }, "p2p-report").start();
    }

    /**
     * 现在能不能对外提供分片。
     *
     * <p>服务器下发 {@code upload_min_segs}：缓存低于这个片数就不许上传。
     * 理由是自己都还不够看，先紧着本地播放 —— 否则刚拉下来的片被别的
     * 节点抢走，本地又要回源，两边都慢。
     */
    public boolean canUpload() {
        if (!running) return false;
        try {
            return cache.size() >= uploadMinSegs;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 服务器下发的缓冲下限：缓存少于这么多片时，新请求一律回源。 */
    public int minBufferSegs() { return minBufferSegs; }

    public byte[] fetchPiece(int seq) {
        if (!running || channelHash == null) return null;

        // 1) 自己缓存有 —— 直接用
        PieceCache.Piece local = cache.get(seq);
        if (local != null) return local.data;

        // 2) 缓冲不够就先顾自己。
        //
        // 服务器下发 min_buffer_segs：本地缓存低于这个数时，去 peer 拿
        // 还不如直接回源稳 —— peer 可能是死的、慢的、或者手上那片
        // 已经被淘汰。这时候优先保证播放流畅。
        if (cache.size() < minBufferSegs) {
            android.util.Log.i("P2P", "缓冲不足(" + cache.size() + "/"
                    + minBufferSegs + ")，跳过 peer 直接回源");
            return null;
        }

        // 3) 问 tracker 谁有
        List<TrackerClient.Peer> peers = tracker.peers(channelHash, seq);
        // tracker 如果对这片有聚合哈希，记下来当可信基准
        try {
            String sh = tracker.lastSeqSha();
            if (sh != null && !sh.isEmpty()) serverSha.put(seq, sh);
        } catch (Throwable ignored) { }
        if (peers.isEmpty()) return null;

        // 3) 排序之后再试。
        //
        // tracker 返回的 rtt / load 以前直接丢掉了，等于按服务器随手给的
        // 顺序瞎撞。先试近的、闲的，失败率明显低一截。
        //
        // 打分规则：
        //   - rtt 未知（0 或负数）当 800ms 处理，排到后面去，但别排除
        //   - load 是 0~1 的负载比例，乘 500 让它跟 rtt 同量级
        //   - 两项相加，小的先试
        java.util.List<TrackerClient.Peer> ordered =
                new java.util.ArrayList<>(peers);
        try {
            java.util.Collections.sort(ordered,
                    new java.util.Comparator<TrackerClient.Peer>() {
                        @Override public int compare(TrackerClient.Peer a,
                                                     TrackerClient.Peer b) {
                            return scorePeer(a) - scorePeer(b);
                        }
                    });
        } catch (Throwable t) {
            android.util.Log.w("P2P", "peer 排序失败，按原顺序试", t);
        }

        for (TrackerClient.Peer p : ordered) {
            String key = p.addr + ":" + p.port;

            byte[] data = fetchFromPeer(p, seq);
            if (data != null) {
                cache.put(seq, data);
                // peer 传过来的是密文，先解
                byte[] plain = PieceCrypto.process(pieceKey, seq, data);
                if (plain == null) {
                    android.util.Log.w("P2P", "解密失败 seq=" + seq + " from " + key);
                    return null;
                }
                data = plain;
                android.util.Log.w("P2P", "直连 " + key + " 拿到第 " + seq + " 片");
                statDownBytes += data.length;
                statDownReq++;
                reportHaveAsync();
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
                        reportHaveAsync();
                        return data;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 给候选节点打分，越小越优先。
     *
     * <p>rtt 未知时按 800ms 估，宁可排后面也别排除 —— 有些 tracker 不给 rtt，
     * 排掉了就等于永远只在自己那几个邻居里打转。
     */
    private static int scorePeer(TrackerClient.Peer p) {
        if (p == null) return Integer.MAX_VALUE;
        // 服务端调度给的是 dist（播放位置差），越小越新鲜，直播场景下优先用它。
        // 位置差太远的分片可能已经被对方淘汰了，试了也是白试。
        if (p.dist >= 0) {
            int rtt = p.rtt > 0 ? Math.min(p.rtt, 2000) : 0;
            return p.dist * 1000 + rtt;
        }
        int rtt = p.rtt > 0 ? Math.min(p.rtt, 2000) : 800;
        double load = p.load < 0 ? 0 : (p.load > 1 ? 1 : p.load);
        return rtt + (int) (load * 500);
    }

    /** 从一个对端拉分片。失败（超时/校验不过）返回 null。 */
    private byte[] fetchFromPeer(TrackerClient.Peer p, int seq) {
        java.net.HttpURLConnection conn = null;
        try {
            // 带时间戳 + HMAC，服务端验签。
            //
            // 没有签名的话，任何人知道 peer 的 IP:端口就能直接拉走分片，
            // 甚至不用装这个 app。加了之后，随机扫描器和手写爬虫都拿不到 ——
            // 它们算不出 hmac。
            long t = System.currentTimeMillis();
            String sig = com.mintv.tv.data.TrackerClient.signPiece(
                    p2pSecret, channelHash, seq, t);
            String url = "http://" + p.addr + ":" + p.port
                    + "/mintv/piece?ch=" + channelHash + "&seq=" + seq
                    + "&t=" + t + "&s=" + sig;
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(PEER_TIMEOUT);
            conn.setReadTimeout(PEER_TIMEOUT);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");

            if (conn.getResponseCode() != 200) {
                // 记一笔失败，服务端会给这个节点降权
                final String ch = channelHash;
                final String addr = p.addr;
                new Thread(new Runnable() {
                    @Override public void run() {
                        tracker.reportPeerFail(ch, addr);
                    }
                }, "p2p-fail").start();
                return null;
            }

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

            // 校验顺序：先比可信哈希（我们自己回源拿过的 / tracker 聚合的），
            // 再回头看对端自己报的 —— 后者只能发现传输损坏，不算安全边界。
            String actual = PieceCache.sha256(data);
            if (actual == null) return null;

            String trusted = trustedSha.get(seq);
            if (trusted == null) trusted = serverSha.get(seq);
            if (trusted != null) {
                if (!actual.equalsIgnoreCase(trusted)) {
                    android.util.Log.w("P2P", "分片与可信哈希不符，丢弃 seq=" + seq
                            + " 来自 " + p.addr + ":" + p.port
                            + " 期望 " + trusted.substring(0, 12)
                            + " 实际 " + actual.substring(0, 12));
                    // 记一笔失败，服务端会降低这个节点的优先级
                    try { tracker.reportPeerFail(channelHash, p.addr + ":" + p.port); }
                    catch (Throwable ignored) { }
                    return null;
                }
            } else {
                // 没有可信哈希可比 —— 首次接触，先采信但对端必须自证一致
                String expect = conn.getHeaderField("X-Piece-SHA256");
                if (expect != null && !expect.isEmpty()
                        && !actual.equalsIgnoreCase(expect)) {
                    android.util.Log.w("P2P", "分片自证不一致，丢弃 seq=" + seq);
                    return null;
                }
                trustedSha.put(seq, actual);
            }
            return data;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) { }
        }
    }
}
