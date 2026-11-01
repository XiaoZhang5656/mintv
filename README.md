# TVUI — 纯 Java 的 Android TV 直播播放器界面

一个**零 UI 第三方依赖**的 Android TV 直播播放器界面实现，面向低性能电视盒子（1GB RAM / 早期 ARM SoC）。

- 全部代码 **纯 Java**，无 Kotlin、无协程、无 Compose、无数据绑定
- 所有控件 **继承 `android.view.View` 自绘**，不引 Material Components / Leanback / ConstraintLayout
- 焦点自己算（`FocusEngine`），不依赖 `nextFocusDown` 那套 XML 猜测
- 播放内核走 Media3，接口抽象，可换 IJK / LibVLC

## 界面

| 三栏主界面 | 播放设置 | 纯播放态 |
|---|---|---|
| 左侧分类 + 频道列 + 竖排信息轨 + 横向压暗遮罩 | 分段选择器 + 行焦点白框 | 面板收起，只剩画面与时钟 |

可交互原型：[`preview/proto.html`](preview/proto.html) —— 浏览器打开，方向键操作，
`E` 开设置页，`Esc` 收起面板 / 返回。

## 技术要点

### 1. 为什么不用 Leanback
Leanback 的 `Presenter` 层层封装 + 每帧 `findNextFocus` 遍历，在两栏网格上落点不可控。
本项目用 400 行的 `FocusEngine` 把焦点建模成「区域 + 索引」，按键只做算术：

```java
focus.onKey(KEYCODE_DPAD_DOWN);   // 一次算术，一次 requestFocus，零 layout 遍历
```

### 2. 为什么不用 MaterialCardView
`MaterialCardView` 在 focus 变化时会 `setLayerType(SOFTWARE)` 建离屏 buffer，
早期盒子（S905 一代）上焦点一动就掉到 20fps。这里纯粹在宿主 canvas 上画两个圆角矩形：

```java
drawBg(c, Theme.PANEL_ITEM, Theme.FOCUS_BG);   // 底色插值
drawFocusStroke(c, Theme.FOCUS_BLUE);          // 2px 内缩描边
```

### 3. 播放画面与 UI 完全分离
`SurfaceView` 走独立图层，列表滚动时视频层不参与重绘，这是低端盒子不卡的根本原因。

### 4. 直播调参
```java
new DefaultLoadControl.Builder().setBufferDurationsMs(
        3_000,   // minBufferMs
        8_000,   // maxBufferMs  —— 直播要快，不是要稳
        500,     // bufferForPlaybackMs
        800);    // bufferForPlaybackAfterRebufferMs
```

切台必须 `stop → clearMediaItems → setMediaSource → prepare`，
**不要用 `seekTo` 换源**，低端机必卡 2 秒。

### 5. 尺寸不写死
所有视觉常量集中在一个 `Theme.java`，以 1920×1080 为基准设计，
运行时按 `widthPx / 1920` 等比缩放，720p 盒子自动适配。

## 目录

```
app/src/main/java/com/gg/tv/
├── ui/
│   ├── MainActivity.java        播放器 + 三栏覆盖层 + 设置弹窗
│   ├── Theme.java               全部尺寸/颜色/动画时长常量
│   ├── DemoData.java            演示数据（换成你的 ChannelStore 即可）
│   ├── OsdView.java             换台 OSD、缓冲状态、网速
│   ├── focus/FocusEngine.java   焦点引擎
│   └── widget/
│       ├── BaseCardView.java    自绘圆角卡片基类（统一焦点动画）
│       ├── NavItemView.java     分类项（图标 + 名称 + 计数胶囊）
│       ├── ChannelView.java     频道行（台号 + 台名 + 徽标 + 节目）
│       ├── ScrimView.java       横向压暗遮罩
│       ├── RailView.java        竖排「节目 » 信息」
│       ├── ClockView.java       右上角时钟
│       ├── SegmentedView.java   分段选择器
│       └── SettingRowView.java  设置行
└── player/
    ├── PlaybackBackend.java     播放内核接口
    └── Media3Backend.java       Media3 实现
```

## 构建

**Android Studio**：直接打开根目录，Sync 后 Run。

**命令行**：
```bash
./gradlew assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接推到 GitHub，`.github/workflows/build.yml` 会在 Actions 里出 debug APK。

### 环境

| 项 | 值 |
|---|---|
| minSdk | 21（Android 5.0） |
| targetSdk | 34 |
| AGP | 8.5.2 |
| JDK | 17 |
| 语言 | Java 8 语法（无 lambda 以外的语法糖） |

## 按键

| 键 | 行为 |
|---|---|
| ↑ ↓ | 当前栏内移动 |
| ← → | 栏之间切换 / 设置行内选项切换 |
| OK / Enter | 选分类 / 切台 / 改设置 |
| Menu | 打开设置页 |
| Back | 收起面板 → 再按退出 |
| 面板收起时任意方向键 | 唤出面板 |

## 接入自己的源

只需实现一个 `List<Channel>` 数据源，替换 `DemoData`：

```java
public final class Channel {
    public final String no, name, now, badge, url;
}
```

`MainActivity.bindChannels()` 会把这些字段喂给 `ChannelView`。
频道数超过 200 时，把 `chanColumn` 换成 `RecyclerView` + `setItemAnimator(null)` 即可，
`bindChannels()` 之外的代码一行不用改。

## 授权

代码以 [MIT](LICENSE) 发布。第三方依赖与素材归属见 [NOTICE.md](NOTICE.md)。

本项目只提供播放器界面与播放框架，不附带任何直播源。
