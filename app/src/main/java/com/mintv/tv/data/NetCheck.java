package com.mintv.tv.data;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 网络能力检测：判断本机 NAT 类型 + 能否当 P2P 上传节点。
 *
 * <p>用 STUN 协议（RFC 5389）自己拼包，不引入任何库。
 *
 * <p>判定方法（RFC 5780 的简化版）：用**同一个本地 UDP 端口**向多个
 * STUN 服务器发 Binding Request，比较返回的映射地址：
 *
 * <pre>
 *   映射 IP 和端口都相同   → 全锥形，打洞成功率最高
 *   IP 相同、端口不同      → 对称型，打洞基本不可行
 *   IP 都不同              → 多出口或负载均衡
 *   全都没响应             → 被墙或 CGNAT
 * </pre>
 *
 * <p><b>关键</b>：必须用同一个 socket。每次新建 socket 源端口不同，
 * 映射当然不同，那种测法得不出任何结论 —— 这是最容易踩的坑。
 */
public final class NetCheck {

    /** 公开 STUN，全部实测可用。小米那个是国内的，优先。 */
    private static final String[][] STUN_SERVERS = {
            {"stun.miwifi.com", "3478"},
            {"stun.chat.bilibili.com", "3478"},
            {"stun.hitv.com", "3478"},
    };

    private static final int MAGIC = 0x2112A442;

    /** 单个服务器的总预算（DNS + UDP 往返）。 */
    private static final long PROBE_BUDGET_MS = 4000L;

    /** daemon 线程池，用完不阻塞进程退出。 */
    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newCachedThreadPool(
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "stun-probe");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /** NAT 类型。 */
    public static final int NAT_UNKNOWN = 0;
    public static final int NAT_FULL_CONE = 1;
    public static final int NAT_RESTRICTED = 2;
    public static final int NAT_SYMMETRIC = 3;
    public static final int NAT_BLOCKED = 4;

    public static final class Result {
        public int localPort;
        public int natType = NAT_UNKNOWN;
        public String publicIp;            // 拿到的公网 IP（可能为 null）
        public final List<String> mappings = new ArrayList<>();
        public int responded;              // 有应答的服务器数
        public String note = "";           // 结论说明

        /** 能不能当上传节点：只有全锥形和地址受限有机会。 */
        public boolean canServe() {
            return natType == NAT_FULL_CONE || natType == NAT_RESTRICTED;
        }

        public String typeName() {
            switch (natType) {
                case NAT_FULL_CONE:  return "全锥形（可当节点）";
                case NAT_RESTRICTED: return "地址/端口受限（有机会）";
                case NAT_SYMMETRIC:  return "对称型（打洞不可行）";
                case NAT_BLOCKED:    return "无法连接 STUN（被墙或 CGNAT）";
                default:             return "未知";
            }
        }
    }

    private NetCheck() { }

    /**
     * 执行检测。会阻塞几秒，必须放在后台线程调。
     */
    public static Result run() {
        Result r = new Result();
        DatagramSocket sock = null;
        try {
            // new DatagramSocket() 已经绑好了一个随机本地端口，再 bind 会抛
            // "Already bound"，整轮检测直接失败 —— 之前一直显示"连不上 STUN"就是这个。
            sock = new DatagramSocket();
            sock.setSoTimeout(3000);
            r.localPort = sock.getLocalPort();

            Set<String> ips = new HashSet<>();
            Set<Integer> ports = new HashSet<>();

            for (String[] srv : STUN_SERVERS) {
                int[] mapped;
                try {
                    mapped = probeWithTimeout(sock, srv[0], Integer.parseInt(srv[1]));
                } catch (ProbeTimeout pt) {
                    // 线程取消不掉，继续用这个 sock 会收到上一轮迟到的包而误判。
                    r.note = "探测超时（DNS 或 UDP 无响应），已停止";
                    break;
                }
                if (mapped == null) continue;
                String ip = ipToString(mapped[0]);
                int port = mapped[1];
                r.responded++;
                r.mappings.add(srv[0] + " → " + ip + ":" + port);
                ips.add(ip);
                ports.add(port);
                if (r.publicIp == null) r.publicIp = ip;
            }

            if (r.responded == 0) {
                r.natType = NAT_BLOCKED;
                if (r.note.isEmpty()) {
                    r.note = "所有 STUN 都没应答：可能被墙、或运营商 CGNAT 完全阻断";
                }
            } else if (ips.size() > 1) {
                r.natType = NAT_UNKNOWN;
                r.note = "出口 IP 不一致（多出口/负载均衡），需要更多数据判断";
            } else if (ports.size() == 1) {
                r.natType = NAT_FULL_CONE;
                r.note = "同一本地端口映射到固定公网端口，打洞成功率最高";
            } else {
                r.natType = NAT_SYMMETRIC;
                r.note = "同一本地端口映射到 " + ports.size()
                        + " 个不同公网端口 —— 对方无法预知，打洞必然失败";
            }
        } catch (Throwable t) {
            r.natType = NAT_BLOCKED;
            r.note = "检测异常：" + t.getMessage();
        } finally {
            if (sock != null) try { sock.close(); } catch (Throwable ignored) { }
        }
        return r;
    }

    /**
     * 带硬超时的探测。
     *
     * <p>为什么需要：{@code InetAddress.getByName} 做 DNS 解析时不受
     * SoTimeout 约束，弱网下能卡几十秒。三个服务器累加会超过一分钟，
     * 界面上就一直停在"正在检测网络…"。
     *
     * <p>做法是把整个 probe（含 DNS）丢进线程池，主线程只等固定时间。
     * 超时就放弃这个服务器 —— 检测本来就是"能测出来最好，测不出不影响播放"。
     */
    private static int[] probeWithTimeout(final DatagramSocket sock,
                                          final String host, final int port) {
        java.util.concurrent.Future<int[]> f =
                POOL.submit(new java.util.concurrent.Callable<int[]>() {
                    @Override public int[] call() { return probe(sock, host, port); }
                });
        try {
            return f.get(PROBE_BUDGET_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            // 取消不掉阻塞中的 DNS/recv，那个线程可能稍后还会往 sock 里塞包。
            // 所以不只放弃这个服务器，是整轮放弃 —— 见调用处的 catch。
            f.cancel(true);
            android.util.Log.w("NetCheck", host + " 超时 "
                    + PROBE_BUDGET_MS + "ms（DNS 卡住或 UDP 无响应）");
            throw new ProbeTimeout();
        } catch (Throwable t) {
            f.cancel(true);
            return null;
        }
    }

    /** 探测超时。见 {@link #probeWithTimeout} 里的说明。 */
    private static final class ProbeTimeout extends RuntimeException {
        ProbeTimeout() { super("stun probe timeout"); }
    }

    /**
     * 发一次 Binding Request，返回 {IPv4 整数, 端口}；失败返回 null。
     */
    private static int[] probe(DatagramSocket sock, String host, int port) {
        try {
            byte[] pkt = buildRequest();
            InetAddress addr = InetAddress.getByName(host);
            sock.send(new DatagramPacket(pkt, pkt.length, addr, port));

            byte[] buf = new byte[1024];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            sock.receive(resp);
            return parseResponse(buf, resp.getLength());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 20 字节头：type=0x0001, length=0, magic, 12 字节随机事务 ID。 */
    private static byte[] buildRequest() {
        byte[] pkt = new byte[20];
        pkt[0] = 0x00; pkt[1] = 0x01;                 // Binding Request
        pkt[2] = 0x00; pkt[3] = 0x00;                 // length = 0
        pkt[4] = 0x21; pkt[5] = 0x12;
        pkt[6] = (byte) 0xA4; pkt[7] = 0x42;          // magic cookie
        byte[] tid = new byte[12];
        new Random().nextBytes(tid);
        System.arraycopy(tid, 0, pkt, 8, 12);
        return pkt;
    }

    /**
     * 解析响应，找 XOR-MAPPED-ADDRESS(0x0020) 或 MAPPED-ADDRESS(0x0001)。
     *
     * @return {IPv4 整数, 端口}；解析不出返回 null
     */
    private static int[] parseResponse(byte[] data, int len) {
        if (len < 20) return null;
        int magic = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16)
                | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        if (magic != MAGIC) return null;

        int bodyLen = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int i = 20;
        int end = Math.min(20 + bodyLen, len);
        while (i + 4 <= end) {
            int type = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            int alen = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            int vStart = i + 4;
            if (vStart + alen > len) break;

            if (type == 0x0020 && alen >= 8) {
                // XOR-MAPPED-ADDRESS：端口和 IP 都跟 magic cookie 异或
                int xport = (((data[vStart + 2] & 0xFF) << 8)
                        | (data[vStart + 3] & 0xFF)) ^ (MAGIC >>> 16);
                int xip = 0;
                for (int k = 0; k < 4; k++) {
                    int b = data[vStart + 4 + k] & 0xFF;
                    int m = (MAGIC >>> (24 - 8 * k)) & 0xFF;
                    xip = (xip << 8) | (b ^ m);
                }
                return new int[]{xip, xport};
            }
            if (type == 0x0001 && alen >= 8) {
                // 老式 MAPPED-ADDRESS，明文
                int p = ((data[vStart + 2] & 0xFF) << 8) | (data[vStart + 3] & 0xFF);
                int ip = 0;
                for (int k = 0; k < 4; k++) {
                    ip = (ip << 8) | (data[vStart + 4 + k] & 0xFF);
                }
                return new int[]{ip, p};
            }
            // 属性按 4 字节对齐
            i = vStart + alen + ((4 - alen % 4) % 4);
        }
        return null;
    }

    private static String ipToString(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "."
                + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }
}
