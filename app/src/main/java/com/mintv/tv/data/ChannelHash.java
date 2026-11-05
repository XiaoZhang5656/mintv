package com.mintv.tv.data;

import java.security.MessageDigest;

/**
 * 频道标识：直接对频道名称做 SHA-256。
 *
 * <p>取值规则只有一条：{@code sha256(名称)} 取前 16 位十六进制。
 * 不做归一化，名称怎么写就怎么算 —— 所以同一个台如果在不同订阅里
 * 名字不同（"CCTV-1" vs "中央1台"），算出来的 ID 也不同，
 * 互相认不出来。这是有意保持简单。
 */
public final class ChannelHash {

    private ChannelHash() { }

    /**
     * @param name 频道名称
     * @return 16 位十六进制；名称为空返回 null
     */
    public static String id(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(n.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
