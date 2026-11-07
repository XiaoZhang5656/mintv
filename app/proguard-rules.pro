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

# ============================================================
# release 包移除调试级日志
# ============================================================
# Log.d / Log.i 只用于开发期观察，正式包里没必要留：
#   · 抓 logcat 能看到内部状态
#   · 每条日志都有字符串拼接和 IO，高频调用时是纯开销
#
# Log.w / Log.e 保留 —— 用户报问题时只有这些能查。
#
# 依赖 proguard-android-optimize.txt（build.gradle 里已指定），
# 只有那个配置文件才会执行 -assumenosideeffects。
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int i(...);
    public static int v(...);
}

# ============================================================
# StringFog 运行时
# ============================================================
# 插桩后每个字符串都变成 StringFogImpl.decrypt(...) 调用，
# 这个类被混淆或裁掉，所有字符串就解不出来了。
-keep class com.github.megatronking.stringfog.** { *; }
-dontwarn com.github.megatronking.stringfog.**
