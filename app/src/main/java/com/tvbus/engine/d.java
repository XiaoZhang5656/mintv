package com.tvbus.engine;

/**
 * TVCore 的回调接口。
 *
 * <p><b>类名必须叫 d。</b> libtvcore.so 里的 JNI 签名是写死的
 * {@code Lcom/tvbus/engine/d;}，改名就找不到类，直接 UnsatisfiedLinkError。
 * 这是从猫影的 smali 里反推出来的，不是命名习惯差。
 */
public interface d {
    void onInfo(String s);
    void onInited(String s);
    void onPrepared(String s);
    void onQuit(String s);
    void onStart(String s);
    void onStop(String s);
}
