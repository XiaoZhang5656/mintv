package com.mintv.tv.data;

import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * P2P 分片传输加解密。
 *
 * <p>目的：peer 之间传的是密文，中间人抓包看不到内容。
 * 分片在本地缓存里仍是明文（VLC 要直接播），只有过网的时候加密。
 *
 * <p>算法：AES-128-CTR。选 CTR 而不是 CBC 是因为：
 * <ul>
 *   <li>不需要 padding —— 分片长度不固定，CBC 要补齐再裁掉，容易出错</li>
 *   <li>加解密是同一个操作（XOR keystream），代码只有一份</li>
 *   <li>可以随机访问（按 seq 派生 nonce），不用从头解</li>
 * </ul>
 *
 * <p>nonce 用分片序号派生 —— 双方不用额外协商 IV，
 * 同一个 seq 一定得到同一个 keystream。
 *
 * <p><b>局限</b>：密钥由 tracker 下发、存在客户端里。反编译能拿到。
 * 这是「防止抓包直接看内容」和「提高门槛」，不是绝对安全。
 */
public final class PieceCrypto {

    private static final String TAG = "PieceCrypto";

    /**
     * native 库能不能用。
     *
     * <p>编不出来、或者手机 ABI 不匹配时这里是 false —— 调用方会回落到
     * tracker 下发的 key。**必须有这个降级**，否则 so 一出问题整个
     * P2P 就瘫了，而这层加密本来就是锦上添花。
     */
    private static final boolean NATIVE_OK;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("mintvcrypt");
            // 顺手验一下版本，防止拿到旧 so
            ok = nativeVersion() >= 1;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "native 库不可用，回落到 tracker 密钥: " + t);
        }
        NATIVE_OK = ok;
    }

    private static native String nativeKey();
    private static native int nativeVersion();

    /** native 是否可用（日志/诊断用）。 */
    public static boolean nativeAvailable() { return NATIVE_OK; }

    /**
     * 取主密钥。
     *
     * <p>优先用 .so 里的（反编译 dex 拿不到）；so 不可用时返回 null，
     * 调用方去用 tracker 下发的。
     */
    public static byte[] nativeOrNull() {
        if (!NATIVE_OK) return null;
        try {
            return parseKey(nativeKey());
        } catch (Throwable t) {
            android.util.Log.w(TAG, "nativeKey 调用失败", t);
            return null;
        }
    }

    private PieceCrypto() { }

    /**
     * 从十六进制字符串解析密钥。
     *
     * @return 16 字节 key；格式不对返回 null（调用方当成不加密）
     */
    public static byte[] parseKey(String hex) {
        if (hex == null || hex.length() < 32) return null;
        try {
            byte[] k = new byte[16];
            for (int i = 0; i < 16; i++) {
                k[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return k;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 有有效密钥才算启用加密。 */
    public static boolean enabled(byte[] key) {
        return key != null && key.length == 16;
    }

    /**
     * 加解密（CTR 模式两者是同一个操作）。
     *
     * @param key  16 字节
     * @param seq  分片序号，用来派生 nonce
     * @param data 输入
     * @return 输出；出错返回 null
     */
    public static byte[] process(byte[] key, int seq, byte[] data) {
        if (!enabled(key) || data == null || data.length == 0) return data;
        try {
            // IV = 8 字节 seq + 8 字节固定标记。
            // 双方按同样的规则算，所以不用在请求里带 IV。
            byte[] iv = new byte[16];
            long s = seq & 0xFFFFFFFFL;
            for (int i = 0; i < 8; i++) {
                iv[i] = (byte) ((s >>> ((7 - i) * 8)) & 0xFF);
            }
            byte[] marker = "MinTVp2p".getBytes("UTF-8");
            System.arraycopy(marker, 0, iv, 8, Math.min(marker.length, 8));

            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "加解密失败 seq=" + seq, t);
            return null;
        }
    }

    /** 给日志用的短摘要，别把 key 打出来。 */
    public static String fingerprint(byte[] key) {
        if (!enabled(key)) return "无";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(key);
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
