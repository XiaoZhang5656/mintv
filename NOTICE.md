# 第三方组件与授权

## 本仓库自有代码
除下述第三方依赖外，`app/src/main/java/com/gg/tv/**` 下的全部代码由本项目作者编写，
以 MIT 协议发布，见 [LICENSE](LICENSE)。

**UI 层零第三方依赖**：所有控件（导航项、频道行、遮罩、竖排轨、时钟、分段选择器、
设置行）都是直接继承 `android.view.View` 自绘，没有引用 Material Components、
Leanback、ConstraintLayout、Glide/Picasso 等任何 UI 库。

## 第三方依赖

| 组件 | 版本 | 协议 | 用途 |
|---|---|---|---|
| androidx.media3:media3-exoplayer | 1.3.1 | Apache-2.0 | 播放内核 |
| androidx.media3:media3-exoplayer-hls | 1.3.1 | Apache-2.0 | HLS 直播切片 |
| androidx.media3:media3-ui | 1.3.1 | Apache-2.0 | 仅用其 SurfaceView 类型，未用控制器 |

Apache-2.0 全文：https://www.apache.org/licenses/LICENSE-2.0

## 素材

- `res/drawable/banner.png`、`res/mipmap-*/ic_launcher.png`：本项目自制占位图，MIT。
- 演示频道名与节目名仅为界面示意，不包含任何视频流地址。
  仓库内 `DemoData.java` 里指向的 Mux 测试流属于公开测试素材。

## 关于电视直播内容

本项目只提供**播放器界面与播放框架**，不附带、不分发任何直播源。
界面里出现的频道名称（如「北京卫视」）仅用于还原设计稿的排版效果。
