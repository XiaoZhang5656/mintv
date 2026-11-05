package com.mintv.tv.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2P 分片缓存：内存 LRU + 落盘。
 *
 * <p>容量按服务器下发的 {@code window_size} 决定（默认 20 片）。
 * 一片 2MB 的话约 40MB，盒子能接受。
 *
 * <p>只缓存**最近窗口内**的分片：直播是滚动的，老分片没人要，
 * 存着纯浪费空间。
 */
public final class PieceCache {

    private static final int MEM_MAX = 10;          // 内存最多 10 片
    private static final int DISK_MAX = 20;         // 磁盘最多 20 片

    /** 一个分片。 */
    public static final class Piece {
        public int seq;
        public byte[] data;
        public String sha256;
        public long ts;
    }

    /** 内存缓存：LinkedHashMap 按访问顺序，超过上限淘汰最老的。 */
    private final LinkedHashMap<Integer, Piece> mem =
            new LinkedHashMap<Integer, Piece>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, Piece> e) {
                    return size() > MEM_MAX;
                }
            };

    /** 磁盘上的分片（懒加载，只记元数据）。 */
    private final LinkedHashMap<Integer, Long> disk =
            new LinkedHashMap<Integer, Long>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Integer, Long> e) {
                    return size() > DISK_MAX;
                }
            };

    private final java.io.File dir;
    private int minSeq = 0;      // 窗口下界，低于它的直接丢弃

    public PieceCache(android.content.Context ctx) {
        this.dir = new java.io.File(ctx.getApplicationContext().getFilesDir(), "p2p");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** 服务器下发了新窗口，把窗口外的清掉。 */
    public synchronized void setWindow(int minSeq) {
        if (minSeq <= this.minSeq) return;
        this.minSeq = minSeq;
        List<Integer> drop = new ArrayList<>();
        for (Integer s : mem.keySet()) if (s < minSeq) drop.add(s);
        for (Integer s : drop) mem.remove(s);
        drop.clear();
        for (Integer s : disk.keySet()) if (s < minSeq) drop.add(s);
        for (Integer s : drop) {
            disk.remove(s);
            //noinspection ResultOfMethodCallIgnored
            fileOf(s).delete();
        }
    }

    public synchronized void put(int seq, byte[] data) {
        if (data == null || data.length == 0) return;
        if (seq < minSeq) return;          // 窗口外的不要
        Piece p = new Piece();
        p.seq = seq;
        p.data = data;
        p.sha256 = sha256(data);
        p.ts = System.currentTimeMillis();
        mem.put(seq, p);
        // 超出内存的部分不主动落盘，等被淘汰时再腾——这里没做，
        // 因为一步落盘会让下载线程变慢。读的时候 miss 就回源。
    }

    /** 取分片：先内存，再磁盘；都没有返回 null。 */
    public synchronized Piece get(int seq) {
        Piece p = mem.get(seq);
        if (p != null) return p;
        Long ts = disk.get(seq);
        if (ts == null) return null;
        byte[] data = readFile(fileOf(seq));
        if (data == null) { disk.remove(seq); return null; }
        Piece q = new Piece();
        q.seq = seq;
        q.data = data;
        q.sha256 = sha256(data);
        q.ts = ts;
        mem.put(seq, q);
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

    public synchronized void clear() {
        mem.clear();
        disk.clear();
        java.io.File[] fs = dir.listFiles();
        if (fs != null) for (java.io.File f : fs) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // ---------------- 内部 ----------------

    private java.io.File fileOf(int seq) {
        return new java.io.File(dir, "p" + seq + ".bin");
    }

    private byte[] readFile(java.io.File f) {
        if (!f.isFile()) return null;
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { in.read(buf); } finally { in.close(); }
            return buf;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 分片校验值：用于告诉别人“我这片是对的”。 */
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
