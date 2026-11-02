-keep class com.mintv.tv.** { *; }
-dontwarn androidx.media3.**

# ---- libVLC ----
# VLCObject / MediaPlayer / LibVLC 全是 JNI 类，且 Media.Event 里的常量
# 在 native 侧按名字查找
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# native 方法本身不能被重命名
-keepclasseswithmembernames class * {
    native <methods>;
}
