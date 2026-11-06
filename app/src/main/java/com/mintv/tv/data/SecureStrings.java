package com.mintv.tv.data;

/**
 * 需要藏起来的字符串。
 *
 * <p>只用于「不让 {@code strings classes.dex | grep http} 一眼看穿」这个级别。
 * 密钥就在 dex 里，稍微懂行的人照样能解出来 —— 这是方案本身的局限，
 * 真正防逆向要靠签名派生密钥 + 私有 keystore，暂时没做。
 *
 * <p>密钥拆成两半再做 XOR：单看 {@code K1} 或 {@code K2} 都还原不出真 key，
 * 至少挡住直接 dump 数组的人。
 */
public final class SecureStrings {

    private SecureStrings() { }

    /** 密钥的一半。 */
    private static final byte[] K1 = {
            (byte) 0x7E, (byte) 0x39, (byte) 0xD2, (byte) 0x43,
            (byte) 0xD4, (byte) 0xEB, (byte) 0x92, (byte) 0xF8,
            (byte) 0x25, (byte) 0xAE, (byte) 0x04, (byte) 0xDB,
            (byte) 0x6B, (byte) 0xB6, (byte) 0x00, (byte) 0x53
    };

    /** 密钥的另一半。真 key = K1 XOR K2。 */
    private static final byte[] K2 = {
            (byte) 0xC2, (byte) 0x2B, (byte) 0x14, (byte) 0x5D,
            (byte) 0x7F, (byte) 0x8A, (byte) 0x18, (byte) 0xE4,
            (byte) 0x0A, (byte) 0x5B, (byte) 0xB7, (byte) 0x1B,
            (byte) 0xEF, (byte) 0xB3, (byte) 0x66, (byte) 0x64
    };

    /** tracker 地址的密文。明文是 http://yun.spup.yunshipin.bbroot.com:2086 */
    private static final String C_TRACKER =
            "t0oae7bSxyGxCYihfiqAEd+/wBNab6IKbMDAli5NUL9NP7aF9uf7jfiM1lwlb6PH";

    /** 解密失败时的兜底，保证调用方拿到的是空串而不是乱码去发请求。 */
    private static final String FALLBACK = "";

    private static byte[] key() {
        byte[] k = new byte[K1.length];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (K1[i] ^ K2[i]);
        return k;
    }

    /**
     * AES/ECB/PKCS5 解密再按 UTF-8 还原。
     *
     * @return 明文；任何一步失败都返回空串
     */
    public static String dec(String base64) {
        if (base64 == null || base64.isEmpty()) return FALLBACK;
        try {
            byte[] ct = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key(), "AES"));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Throwable t) {
            android.util.Log.w("SecureStrings", "解密失败: " + t);
            return FALLBACK;
        }
    }

    /** tracker 地址（运行时才解出来）。 */
    public static String tracker() {
        return dec(C_TRACKER);
    }
}
