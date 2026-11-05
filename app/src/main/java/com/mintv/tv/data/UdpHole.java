package com.mintv.tv.data;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;

/**
 * UDP 打洞：证明"能不能和某个对端直连"。
 *
 * <p>原理：NAT 只放行"你先发出去过"的入站包。
 * 所以双方都要向对端的公网地址发包，各自在自己 NAT 上凿一个洞。
 *
 * <pre>
 *   A 的公网 addr:port ←──── 打洞包 ────  B
 *   A ──── 打洞包 ────→ B 的公网 addr:port
 *   双方 NAT 各自放行 → 之后的包能互达
 * </pre>
 *
 * <p><b>关键点</b>：
 * <ul>
 *   <li>必须双向同时发。单方发的话对方 NAT 会拦（视为未请求的入站）</li>
 *   <li>用固定端口。每次换端口等于重新打洞，路由器映射也要重建</li>
 *   <li>打洞包要带 nonce，收到对端的包才知道是打洞成功而不是野包</li>
 * </ul>
 *
 * <p>打洞成功后<b>不</b>在这里传数据 —— 交给 HTTP 走 /mintv/piece，
 * 因为可靠 UDP 传输（重传、乱序、流控）是另一个量级的工程。
 */
public final class UdpHole {

    /** 打洞包的魔数，用来从野包里认出自己发的包。 */
    private static final byte[] MAGIC = {'M', 'T', 'P', '2'};

    /** 窗口：连续发多久。NAT 映射通常几秒内建立。 */
    /**
     * 打洞窗口。双方几乎同时开始拉分片，1.5 秒足够几次往返。
     * 宁短勿长 —— 打不通就回源，回源比干等划算。
     */
    private static final long PUNCH_WINDOW_MS = 1500L;
    private static final long PUNCH_INTERVAL_MS = 150L;

    private final DatagramSocket sock;
    private final int localPort;

    /**
     * NAT 分配给这个打洞 socket 的对外地址。
     *
     * <p>必须由这个 socket 自己问 STUN 拿 —— 换任何别的 socket 问出来的
     * 都是另一条映射，报给别人就是死地址。
     */
    private volatile String publicAddr;
    private volatile int publicPort;
    private final byte[] nonce;

    private UdpHole(DatagramSocket sock, byte[] nonce) {
        this.sock = sock;
        this.localPort = sock.getLocalPort();
        this.nonce = nonce;
    }

    /**
     * 绑定一个本地 UDP 端口。
     *
     * @param preferPort 想用的端口（0 = 自动选）
     * @return 失败返回 null
     */
    public static UdpHole bind(int preferPort) {
        DatagramSocket s = null;
        try {
            if (preferPort > 0) {
                try {
                    s = new DatagramSocket(null);
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(preferPort));
                } catch (Throwable t) {
                    // 端口被占，回落到自动
                    if (s != null) try { s.close(); } catch (Throwable ignored) { }
                    s = null;
                }
            }
            if (s == null) s = new DatagramSocket();
            s.setSoTimeout(500);

            byte[] n = new byte[8];
            new Random().nextBytes(n);
            return new UdpHole(s, n);
        } catch (Throwable t) {
            android.util.Log.w("UdpHole", "bind 失败", t);
            if (s != null) try { s.close(); } catch (Throwable ignored) { }
            return null;
        }
    }

    public int localPort() { return localPort; }

    /** 对外 IP；没探测或探测失败返回 null。 */
    public String publicAddr() { return publicAddr; }

    /** 对外端口；没探测或探测失败返回 0。 */
    public int publicPort() { return publicPort; }

    /**
     * 问 STUN：这条 socket 在公网上长什么样。
     *
     * <p>必须 bind 之后调用。失败不影响打洞本身，只是别人不知道往哪发。
     *
     * <p>对称型 NAT 换目标就换端口，这里拿到的只是"当时那个目标"看到的端口。
     * 但过滤器宽松的对称型（映射随目标变、过滤不看目标）仍然能被打进来 ——
     * 对方往这个地址发包，NAT 会放行。所以对称型也要上报。
     *
     * @return 拿到返回 true
     */
    public boolean discover() {
        if (sock == null || sock.isClosed()) return false;
        int oldTmo;
        try {
            oldTmo = sock.getSoTimeout();
        } catch (Throwable t) {
            oldTmo = 0;
        }
        try {
            sock.setSoTimeout(1500);
            for (String[] srv : NetCheck.STUN_SERVERS) {
                int[] m = NetCheck.stunProbe(sock, srv[0], Integer.parseInt(srv[1]));
                if (m != null && m[0] != 0 && m[1] > 0) {
                    publicAddr = NetCheck.ipToString(m[0]);
                    publicPort = m[1];
                    android.util.Log.i("UdpHole", "对外地址 " + publicAddr + ":" + publicPort
                            + "（来自 " + srv[0] + "，本地 " + localPort + "）");
                    return true;
                }
            }
            android.util.Log.w("UdpHole", "STUN 全部无应答，本轮没有对外地址");
            return false;
        } catch (Throwable t) {
            android.util.Log.w("UdpHole", "discover 异常", t);
            return false;
        } finally {
            try { sock.setSoTimeout(oldTmo); } catch (Throwable ignored) { }
        }
    }

    /**
     * 向一个对端的公网地址打洞，并等待对方的包。
     *
     * <p>两侧都在拉同一个分片时会"自然相遇"——各自都在向对方发包，
     * 所以不需要额外的时机协调。这是本设计能省掉同步协议的原因。
     *
     * @param peerAddr 对端经 STUN 得到的公网地址
     * @return 打洞成功返回 true
     */
    public boolean punch(String peerAddr, int peerPort) {
        InetSocketAddress peer;
        try {
            peer = new InetSocketAddress(InetAddress.getByName(peerAddr), peerPort);
            if (peer.isUnresolved()) return false;
        } catch (Throwable t) {
            return false;
        }

        long deadline = System.currentTimeMillis() + PUNCH_WINDOW_MS;
        byte[] pkt = buildPunch();
        byte[] buf = new byte[256];

        try {
            while (System.currentTimeMillis() < deadline) {
                // 发
                try {
                    sock.send(new DatagramPacket(pkt, pkt.length, peer));
                } catch (Throwable ignored) { }

                // 收：等一小会儿，看有没有对端的包进来
                long waitUntil = Math.min(deadline,
                        System.currentTimeMillis() + PUNCH_INTERVAL_MS);
                while (System.currentTimeMillis() < waitUntil) {
                    try {
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        sock.receive(in);
                        if (isPunchFrom(in, peerAddr)) {
                            android.util.Log.w("UdpHole", "打洞成功 "
                                    + peerAddr + ":" + peerPort
                                    + " 本地端口 " + localPort);
                            return true;
                        }
                    } catch (java.net.SocketTimeoutException te) {
                        break;   // 这一轮没包，继续下一轮发
                    } catch (Throwable t) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("UdpHole", "punch 异常", t);
        }

        android.util.Log.w("UdpHole", "打洞失败 " + peerAddr + ":" + peerPort
                + "（对称型 NAT 或对端未在发包）");
        return false;
    }

    /** 发一个包出去（保活/测量用）。 */
    public void poke(String peerAddr, int peerPort) {
        try {
            InetAddress a = InetAddress.getByName(peerAddr);
            byte[] pkt = buildPunch();
            sock.send(new DatagramPacket(pkt, pkt.length, a, peerPort));
        } catch (Throwable ignored) { }
    }

    public void close() {
        try { sock.close(); } catch (Throwable ignored) { }
    }

    // ---------------- 内部 ----------------

    /** 包格式：MAGIC(4) + nonce(8)。足够小，也不容易被误判。 */
    private byte[] buildPunch() {
        byte[] p = new byte[MAGIC.length + nonce.length];
        System.arraycopy(MAGIC, 0, p, 0, MAGIC.length);
        System.arraycopy(nonce, 0, p, MAGIC.length, nonce.length);
        return p;
    }

    /**
     * 判断收到的包是不是打洞包。
     *
     * <p><b>不比对 nonce</b>：对端有自己的 nonce，我们不认识。
     * 只校验魔数 + 来源地址，够用 —— 目的是"确认能收到这个 IP 的 UDP"，
     * 不是加密通信。
     */
    private boolean isPunchFrom(DatagramPacket in, String expectAddr) {
        if (in.getLength() < MAGIC.length) return false;
        byte[] d = in.getData();
        for (int i = 0; i < MAGIC.length; i++) {
            if (d[i] != MAGIC[i]) return false;
        }
        String from = in.getAddress().getHostAddress();
        return expectAddr.equals(from);
    }
}
