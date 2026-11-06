# 原来这里是 -keep class com.mintv.tv.** { *; } —— 把整个 app 的类名、方法名、
# 字段名全部保留，混淆等于没做。实际查下来自家代码几乎不用反射：
#   · 唯一的 Class.forName 指向 org.videolan.libvlc.LibVLC（属于下面已保留的包）
#   · UI 全部代码构建，没有 XML 布局，不需要保留 View 类名
#   · QuickJS 只注入 Java 方法引用，不按名字找类
# 所以收窄成只保留这几处必需的。

# JNI 方法名必须和 native 侧一致（Java_com_mintv_tv_data_PieceCrypto_xxx）
-keep class com.mintv.tv.data.PieceCrypto { *; }
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
