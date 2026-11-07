package com.mintv.tv.data;

import android.util.Base64;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * tracker 请求体的传输加密。
 *
 * <p><b>为什么不是对称加密</b>：密钥放客户端，被扒一次全网失效，
 * 而且历史版本也全废。非对称才是正解 —— 客户端只有<b>公钥</b>，
 * 私钥留在服务端永不下发。客户端被拆也只拿到公钥，解不开自己的流量。
 *
 * <p><b>混合加密</b>：RSA-2048 只能加密 ~245 字节，而 /api/join 的
 * {@code have[]} 可能有几十个序号。所以标准做法是：
 * <pre>
 *   ① 随机生成 32 字节 AES key（每次请求都换）
 *   ② 用服务端公钥 RSA 加密这个 AES key
 *   ③ 用 AES-256-CTR 加密 body，IV 随机
 *   ④ 发 {"k":"&lt;RSA(AESkey)&gt;", "d":"&lt;IV||密文&gt;"}
 * </pre>
 * 每次请求换 AES key，前向安全 —— 某一次被破不影响其他请求。
 *
 * <p><b>兼容</b>：加密失败或者服务端没配私钥时，回落到明文发送。
 * 服务端认得出两种格式（有 k/d 就解，否则当明文）。
 */
public final class PayloadCrypto {

    private static final String TAG = "PayloadCrypto";

    /**
     * 服务端公钥（SPKI/DER 的 base64，去掉 PEM 头尾）。
     *
     * <p>公钥是公开的，写在代码里没问题 —— 它只能用来加密，
     * 解不开任何东西。
     */
    private static final String PUB_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAvBdh6v8uctXz9VH88htR"
          + "OZR9Ncmbv8UIiKt0NOZEVeBtX+z7oUiaz1ttBc44ZGsQqGeEOYkQRb3xsX6OaQZ5"
          + "+o3hNhIWFiffPCJrhlHiYs+wAGiqmKr8kD5mAdGfFfgTXC0MhP/kUZyN5UHyV8tD"
          + "iqE3rCVfbtZUls0UMkeycdfnutl+WsnnCbH5TVhzczwI8TUsvHmbZ8RMgRMrf4fW"
          + "ZyYDEhwtumm2ThpC8y0rwpvKagnqGvfifkol9hXMDKRRQv4LyW3JhXI4isKU340T"
          + "1+GtvOpxvZQv5ZSdFOJ1nBnv9M3aG0kGLsrcNxrJSFw1MhslYMA4dOt1xW765us/"
          + "FQIDAQAB";

    private static PublicKey pubKey = null;
    private static boolean inited = false;

    private PayloadCrypto() { }

    private static synchronized PublicKey key() {
        if (inited) return pubKey;
        inited = true;
        try {
            byte[] der = Base64.decode(PUB_B64, Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            pubKey = KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Throwable t) {
            pubKey = null;
        }
        return pubKey;
    }

    /** 加密是否可用。 */
    public static boolean available() { return key() != null; }

    /** 加密结果：payload 发给服务端，aesKey 用来解它回来的响应。 */
    public static final class Enc {
        public String payload;
        public byte[] aesKey;
    }

    /**
     * 加密请求，**同时保留会话 key** —— 响应用同一个 key 加密回来。
     *
     * <p>这就是 TLS 的做法：一次握手一把会话密钥，双向都用它。
     * 之前只加密请求、响应还是明文，`p2p_secret` 直接暴露在 body 里，
     * 等于签名和加密都白做。
     */
    public static Enc encrypt2(String plainJson) {
        if (plainJson == null) return null;
        PublicKey pk = key();
        if (pk == null) return null;
        try {
            byte[] aesKey = new byte[32];
            new SecureRandom().nextBytes(aesKey);

            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, pk);
            byte[] encKey = rsa.doFinal(aesKey);

            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            byte[] ct = aes.doFinal(plainJson.getBytes("UTF-8"));

            byte[] payload = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ct, 0, payload, iv.length, ct.length);

            org.json.JSONObject o = new org.json.JSONObject();
            o.put("k", Base64.encodeToString(encKey, Base64.NO_WRAP));
            o.put("d", Base64.encodeToString(payload, Base64.NO_WRAP));

            Enc e = new Enc();
            e.payload = o.toString();
            e.aesKey = aesKey;
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解密服务端响应。
     *
     * <p>格式：{@code {"e":"<base64(IV||密文)>"}}。
     * 不是这个格式就当明文原样返回（兼容没升级的服务端）。
     */
    public static String decryptResponse(byte[] aesKey, String body) {
        if (aesKey == null || body == null) return body;
        String t = body.trim();
        if (!t.startsWith("{")) return body;
        try {
            org.json.JSONObject o = new org.json.JSONObject(t);
            String e = o.optString("e", null);
            if (e == null || e.isEmpty()) return body;   // 明文响应
            byte[] raw = Base64.decode(e, Base64.DEFAULT);
            if (raw.length < 17) return body;
            byte[] iv = new byte[16];
            System.arraycopy(raw, 0, iv, 0, 16);
            Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
            aes.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            byte[] plain = aes.doFinal(raw, 16, raw.length - 16);
            return new String(plain, "UTF-8");
        } catch (Throwable t2) {
            return body;
        }
    }

    /**
     * 把 JSON 明文加密成 {@code {"k":"...","d":"..."}}。
     *
     * @return 加密后的 JSON 字符串；失败返回 null（调用方回落到明文）
     */
    public static String encrypt(String plainJson) {
        if (plainJson == null) return null;
        PublicKey pk = key();
        if (pk == null) return null;
        try {
            // ① 本次请求专属的 AES key
            byte[] aesKey = new byte[32];
            new SecureRandom().nextBytes(aesKey);

            // ② RSA 加密 AES key
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, pk);
            byte[] encKey = rsa.doFinal(aesKey);

            // ③ AES-256-CTR 加密 body，IV 随机放在密文最前面
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            byte[] ct = aes.doFinal(plainJson.getBytes("UTF-8"));

            byte[] payload = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ct, 0, payload, iv.length, ct.length);

            org.json.JSONObject o = new org.json.JSONObject();
            o.put("k", Base64.encodeToString(encKey, Base64.NO_WRAP));
            o.put("d", Base64.encodeToString(payload, Base64.NO_WRAP));
            return o.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
