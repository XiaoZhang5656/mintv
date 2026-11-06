#include <jni.h>
#include <string.h>
#include <stdint.h>

/*
 * P2P 分片加密的密钥。
 *
 * 为什么要放 native：Java 层的字符串用 `strings classes.dex | grep` 就能捞到，
 * 放 .so 里至少得反汇编才能找到。这提高的是「逆向门槛」，
 * 不是绝对安全 —— 会拆 so 的人照样能拿到（你那 12 个 p2p so 就是这么分析的）。
 *
 * 存法：拆成 4 段常量，运行时异或组合。直接 strings 搜不到完整 key，
 * 静态分析也要跟一遍组合逻辑才看得出来。
 */
static const uint8_t S1[4] = {0x3A, 0x7F, 0x11, 0xC4};
static const uint8_t S2[4] = {0x92, 0x05, 0xE8, 0x6B};
static const uint8_t S3[4] = {0x4D, 0xB3, 0x27, 0x9A};
static const uint8_t S4[4] = {0xF1, 0x62, 0x8C, 0x3E};

/*
 * 组合出 16 字节密钥并写入 out。
 * 每段再异或一个位置相关的掩码 —— 单纯拼接太容易被认出来。
 */
static void build_key(uint8_t *out) {
    const uint8_t *segs[4] = {S1, S2, S3, S4};
    for (int s = 0; s < 4; s++) {
        for (int i = 0; i < 4; i++) {
            /* 掩码 = 段号 * 0x11 + 字节位置 * 0x07，随便但固定 */
            uint8_t mask = (uint8_t) ((s * 0x11 + i * 0x07) & 0xFF);
            out[s * 4 + i] = (uint8_t) (segs[s][i] ^ mask);
        }
    }
}

/*
 * 返回密钥的十六进制形式（32 字符）。
 *
 * 返回 Java String，调用方解析成 byte[]。
 * 每次调用都重新算一遍，不存静态变量 —— 减少被 dump 的机会。
 */
JNIEXPORT jstring JNICALL
Java_com_mintv_tv_data_PieceCrypto_nativeKey(JNIEnv *env, jclass clazz) {
    uint8_t key[16];
    build_key(key);

    char hex[33];
    static const char *H = "0123456789abcdef";
    for (int i = 0; i < 16; i++) {
        hex[i * 2]     = H[(key[i] >> 4) & 0x0F];
        hex[i * 2 + 1] = H[key[i] & 0x0F];
    }
    hex[32] = '\0';

    /* 用完立刻抹掉栈上的明文 */
    volatile uint8_t *p = key;
    for (int i = 0; i < 16; i++) p[i] = 0;

    return (*env)->NewStringUTF(env, hex);
}

/* 版本号，用来确认 so 是新编的（旧 so 不认这方法会报 UnsatisfiedLink） */
JNIEXPORT jint JNICALL
Java_com_mintv_tv_data_PieceCrypto_nativeVersion(JNIEnv *env, jclass clazz) {
    return 1;
}
