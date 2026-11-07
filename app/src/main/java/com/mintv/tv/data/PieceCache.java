package com.mintv.tv.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2P 分片缓存：内存 LRU + 落盘。
 *
 * <p><b>容量全部由服务器下发</b>（见 {@link TrackerClient.Cfg}），不在本地写死：
 * <pre>
 *   mem_max_segs / disk_max_segs      片数上限
 *   mem_max_bytes / disk_max_bytes    字节上限
 *   window_size                       播放位置往前多少片之外丢弃
 * </pre>
 * 之前 MEM_MAX=10 / DISK_MAX=20 是写死的，跟服务器的 window_size=20 脱节，
 * 而且按片数算不按字节算 —— 9.35MB 一片的源，20 片就是 187MB。
 *
 * <p>只缓存**最近窗口内**的分片：直播是滚动的，老分片没人要。
 */
public final class PieceCache {

    /**
     * 兜底上限。
     *
     * <p>服务器还没下发配置时（第一次 join 之前）用这个，避免无限增长。
     * join 成功后会被服务器下发的值覆盖。
     */
    private static final int DEF_MEM_SEGS = 10;
    private static final int DEF_DISK_SEGS = 30;
    private static final long DEF_MEM_BYTES = 32L * 1024 * 1024;
    private static final long DEF_DISK_BYTES = 200L * 1024 * 1024;

    private int memMaxSegs = DEF_MEM_SEGS;
    private int diskMaxSegs = DEF_DISK_SEGS;
    private long memMaxBytes = DEF_MEM_BYTES;
    private long diskMaxBytes = DEF_DISK_BYTES;

    /** 一个分片。 */
    public static final class Piece {
        public int seq;
        public byte[] data;
        public String sha256;
        public long ts;
    }

    /** 内存缓存：LinkedHashMap 按访问顺序（accessOrder=true），最老的在头部。 */
    private final LinkedHashMap<Integer, Piece> mem =
            new LinkedHashMap<Integer, Piece>(16, 0.75f, true);

    /** 磁盘上的分片：序号 → 字节数。 */
    private final LinkedHashMap<Integer, Long> disk =
            new LinkedHashMap<Integer, Long>(16, 0.75f, true);

    private long memBytes = 0;
    private long diskBytes = 0;

    /**
     * 缓存目录：{@code <存储根>/cache/p2p/}。
     *
     * <p>以前放 {@code getFilesDir()/p2p}，App 私有目录 —— 用户看不到、
     * 清不掉、卸载就没了。现在跟 EPG、源快照一起放在 cache/ 下，
     * 文件管理器能看见，也不会占 App 私有空间。
     *
     * <p>代价：SAF 读写要过 ContentResolver，比直接 File 慢几倍
     * （9MB 一片大约 200~500ms）。读取仍在调用线程上做，
     * 但只有「内存未命中」才会走到磁盘，实际影响很小。
     */
    private static final String SUB_DIR = StorageDir.SUB_CACHE + "/mintv";

    /**
     * 旧目录 {@code cache/p2p}。
     *
     * <p>名字里带 p2p 太扎眼，文件管理器里一眼能看出这 app 在做点对点传输。
     * 改成 cache/mintv 后跟 EPG、源快照混在一起，看不出用途。
     *
     * <p>旧目录里的分片是可再生的（丢了会自动重新下载），所以不做迁移，
     * 直接清掉，避免磁盘上留一个明显是旧版本的目录。
     */
    private static final String OLD_DIR = StorageDir.SUB_CACHE + "/p2p";

    private final android.content.Context ctx;
    private int minSeq = 0;      // 窗口下界，低于它的直接丢弃

    public PieceCache(android.content.Context ctx) {
        this.ctx = ctx.getApplicationContext();
        purgeOldDir();
        loadDiskIndex();
    }

    /** 清掉 0.3.68 之前用的 cache/p2p 目录。分片可再生，不迁移。 */
    private void purgeOldDir() {
        try {
            java.util.List<String> names = StorageDir.listPath(ctx, OLD_DIR);
            if (names == null || names.isEmpty()) return;
            int n = 0;
            for (String f : names) {
                if (f == null) continue;
                try { StorageDir.deletePath(ctx, OLD_DIR + "/" + f); n++; }
                catch (Throwable ignored) { }
            }
            android.util.Log.w("PieceCache", "已清理旧缓存目录 cache/p2p：" + n + " 个文件");
        } catch (Throwable ignored) { }
    }

    /** 启动时扫一遍磁盘，把已有分片登记进索引（上次运行留下的）。 */
    private synchronized void loadDiskIndex() {
        java.util.List<String> names = StorageDir.listPath(ctx, SUB_DIR);
        if (names == null || names.isEmpty()) return;
        for (String n : names) {
            if (n == null || !n.startsWith("p") || !n.endsWith(".bin")) continue;
            try {
                int seq = Integer.parseInt(n.substring(1, n.length() - 4));
                byte[] probe = StorageDir.readBytesPath(ctx, SUB_DIR + "/" + n);
                long len = probe == null ? 0 : probe.length;
                if (len <= 0) continue;
                disk.put(seq, len);
                diskBytes += len;
            } catch (Throwable ignored) { }
        }
        if (!disk.isEmpty()) {
            android.util.Log.i("PieceCache", "磁盘已有 " + disk.size()
                    + " 片，共 " + (diskBytes / 1024 / 1024) + "MB");
        }
        enforceDiskLimits();
    }

    /**
     * 应用服务器下发的容量。
     *
     * <p>每次 join 都会收到一份，值没变就直接返回，避免无谓的淘汰扫描。
     */
    public synchronized void setLimits(int memSegs, int diskSegs,
                                       long memBytes, long diskBytes) {
        boolean changed = memSegs != memMaxSegs || diskSegs != diskMaxSegs
                || memBytes != memMaxBytes || diskBytes != diskMaxBytes;
        if (!changed) return;
        this.memMaxSegs = Math.max(1, memSegs);
        this.diskMaxSegs = Math.max(1, diskSegs);
        this.memMaxBytes = Math.max(1024 * 1024, memBytes);
        this.diskMaxBytes = Math.max(1024 * 1024, diskBytes);
        android.util.Log.i("PieceCache", "容量更新: 内存 " + memMaxSegs + "片/"
                + (memMaxBytes / 1024 / 1024) + "MB, 磁盘 " + diskMaxSegs + "片/"
                + (diskMaxBytes / 1024 / 1024) + "MB");
        evictMem();
        enforceDiskLimits();
    }

    /** 服务器下发了新窗口，把窗口外的清掉。 */
    public synchronized void setWindow(int minSeq) {
        if (minSeq <= this.minSeq) return;
        this.minSeq = minSeq;
        List<Integer> drop = new ArrayList<>();
        for (Integer s : mem.keySet()) if (s < minSeq) drop.add(s);
        for (Integer s : drop) {
            Piece p = mem.remove(s);
            if (p != null && p.data != null) memBytes -= p.data.length;
        }
        drop.clear();
        for (Integer s : disk.keySet()) if (s < minSeq) drop.add(s);
        for (Integer s : drop) {
            Long len = disk.remove(s);
            if (len != null) diskBytes -= len;
            StorageDir.deletePath(ctx, pathOf(s));
        }
    }

    /**
     * 落盘队列。
     *
     * <p>为什么要异步：evictMem() 里的 writeToDisk 走 SAF，9MB 一片要
     * 200~500ms。而 put() 是在「把分片发给 VLC 之前」被调的 ——
     * 同步写盘意味着每片都多等几百毫秒，播放直接卡。
     *
     * <p>单线程是因为要在后台维持 LRU 淘汰顺序，多线程反而会乱。
     */
    private final java.util.concurrent.ExecutorService diskWriter =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "piece-disk");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** 正在落盘、还没写成功的分片序号。get() 读到这些要等，不能当孤儿删掉。 */
    private final java.util.Set<Integer> diskPending =
            new java.util.HashSet<>();

    /** 已排队但还没落盘的字节数，避免队列无限涨。 */
    private final java.util.concurrent.atomic.AtomicLong pendingBytes =
            new java.util.concurrent.atomic.AtomicLong(0);

    public synchronized void put(int seq, byte[] data) {
        if (data == null || data.length == 0) return;
        if (seq < minSeq) return;          // 窗口外的不要

        // 已经在内存里就不重复算字节
        Piece old = mem.get(seq);
        if (old != null && old.data != null) memBytes -= old.data.length;

        Piece p = new Piece();
        p.seq = seq;
        p.data = data;
        p.sha256 = sha256(data);
        p.ts = System.currentTimeMillis();
        mem.put(seq, p);
        memBytes += data.length;

        // 放不下就淘汰最老的 —— 淘汰前先落盘
        evictMem();
    }

    /**
     * 内存超限时淘汰最老的，并尝试落盘。
     *
     * <p>之前这块是空的：put 只写内存，淘汰时直接丢，磁盘缓存等于不存在。
     * 现在被淘汰的分片先试写磁盘，写成功才登记进索引。
     */
    private void evictMem() {
        while ((mem.size() > memMaxSegs || memBytes > memMaxBytes) && !mem.isEmpty()) {
            Integer oldest = mem.keySet().iterator().next();
            Piece p = mem.remove(oldest);
            if (p == null) break;
            if (p.data != null) memBytes -= p.data.length;
            // 落盘：磁盘还有空间才排队。
            //
            // 注意这里是**提交任务**而不是直接写 —— 调用方（HTTP 请求线程）
            // 正等着把分片发给 VLC，不能让它等 SAF。
            if (p.data != null && canWriteDisk(p.data.length)) {
                final int fSeq = p.seq;
                final byte[] fData = p.data;
                final long fLen = fData.length;
                // 只有「写成功」才登记进 disk 索引。
                //
                // 之前是先登记再写，结果 get() 在写入完成前读到空文件，
                // 把索引删了 —— 写完之后文件还在、索引没了，成了孤儿文件。
                diskPending.add(fSeq);
                pendingBytes.addAndGet(fLen);
                try {
                    diskWriter.execute(new Runnable() {
                        @Override public void run() {
                            boolean ok = false;
                            try {
                                ok = writeToDisk(fSeq, fData);
                            } catch (Throwable t) {
                                android.util.Log.w("PieceCache", "落盘异常 seq=" + fSeq, t);
                            } finally {
                                synchronized (PieceCache.this) {
                                    diskPending.remove(fSeq);
                                    pendingBytes.addAndGet(-fLen);
                                    if (ok) {
                                        disk.put(fSeq, fLen);
                                        diskBytes += fLen;
                                        enforceDiskLimits();
                                    }
                                }
                            }
                        }
                    });
                } catch (Throwable t) {
                    diskPending.remove(fSeq);
                    pendingBytes.addAndGet(-fLen);
                }
            }
        }
    }

    private boolean canWriteDisk(int len) {
        if (disk.containsKey(-1)) return false;   // 占位，永不成立
        if (disk.size() + 1 > diskMaxSegs) return false;
        if (diskBytes + len > diskMaxBytes) return false;
        return true;
    }

    private void enforceDiskLimits() {
        while ((disk.size() > diskMaxSegs || diskBytes > diskMaxBytes) && !disk.isEmpty()) {
            Integer oldest = disk.keySet().iterator().next();
            Long len = disk.remove(oldest);
            if (len != null) diskBytes -= len;
            StorageDir.deletePath(ctx, pathOf(oldest));
        }
    }

    /** 取分片：先内存，再磁盘；都没有返回 null。 */
    public synchronized Piece get(int seq) {
        Piece p = mem.get(seq);
        if (p != null) return p;
        Long len = disk.get(seq);
        if (len == null) return null;
        byte[] data = readFile(seq);
        if (data == null) {
            // 还在写盘队列里就等等，别把索引删了 —— 写完之后文件还在、
            // 索引没了，就成孤儿文件了
            if (diskPending.contains(seq)) return null;
            disk.remove(seq);
            diskBytes -= len;
            return null;
        }
        Piece q = new Piece();
        q.seq = seq;
        q.data = data;
        q.sha256 = sha256(data);
        q.ts = System.currentTimeMillis();
        mem.put(seq, q);
        memBytes += data.length;
        evictMem();
        return q;
    }

    public synchronized boolean has(int seq) {
        return mem.containsKey(seq) || disk.containsKey(seq);
    }

    /** 当前缓存的所有序号，用于上报 have[]。 */
    public synchronized List<Integer> seqs() {
        List<Integer> out = new ArrayList<>(mem.keySet());
        for (Integer s : disk.keySet()) if (!mem.containsKey(s)) out.add(s);
        java.util.Collections.sort(out);
        return out;
    }

    public synchronized int size() { return mem.size() + disk.size(); }

    /** 内存里的片数，用来判断缓冲够不够。 */
    public synchronized int memSize() { return mem.size(); }

    public synchronized void clear() {
        mem.clear();
        disk.clear();
        memBytes = 0;
        diskBytes = 0;
        java.util.List<String> names = StorageDir.listPath(ctx, SUB_DIR);
        if (names != null) for (String n : names) {
            StorageDir.deletePath(ctx, SUB_DIR + "/" + n);
        }
    }

    // ---------------- 磁盘 ----------------

    private String pathOf(int seq) {
        return SUB_DIR + "/p" + seq + ".bin";
    }

    private boolean writeToDisk(int seq, byte[] data) {
        // 走存储目录（SAF 或私有回退都由 StorageDir 处理）
        return StorageDir.writeBytesPath(ctx, pathOf(seq), data);
    }

    private byte[] readFile(int seq) {
        return StorageDir.readBytesPath(ctx, pathOf(seq));
    }

    /** 分片校验值：用于告诉别人"我这片是对的"。 */
    static String sha256(byte[] data) {
        try {
            java.security.MessageDigest md =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
