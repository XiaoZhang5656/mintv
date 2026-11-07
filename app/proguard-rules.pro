# ============================================================
# JNI 相关：so 里写死了 Java 类名/方法名，混淆或裁剪都会崩
# ============================================================

# libtvcore.so 里的 JNI 符号是 Java_com_tvbus_engine_TVCore_*
# 而且 listener 接口 com.tvbus.engine.d 是通过 JNI 回调的，
# 名字被 R8 改掉就再也调不回来了。
-keep class com.tvbus.engine.** { *; }

# libp2p.so 里的 JNI 符号是 Java_com_forcetech_android_ForceTV_*
-keep class com.forcetech.android.** { *; }

# PieceCrypto 的 native 方法名必须和 libmintvcrypt.so 一致
-keep class com.mintv.tv.data.PieceCrypto { *; }

# 所有带 native 方法的类，类名和方法名都保留
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================
# libVLC
# ============================================================
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }
-dontwarn org.videolan.**

-dontwarn androidx.media3.**
