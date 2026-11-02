-keep class com.mintv.tv.** { *; }
-dontwarn androidx.media3.**

# ---- IJK ----
# IjkMediaPlayer 的 native 层按符号名回调 Java，混淆后直接 UnsatisfiedLinkError
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep interface tv.danmaku.ijk.media.player.** { *; }
-dontwarn tv.danmaku.ijk.**

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
