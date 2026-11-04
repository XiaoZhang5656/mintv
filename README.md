# MinTV — Android TV 直播播放器

全 Java、零第三方 UI 控件的电视直播播放器。单内核 libVLC，支持脚本扩展、本地源导入、局域网手机遥控。

---

## 功能

| 能力 | 说明 |
|---|---|
| 播放 | libVLC 3.5.1 单内核，解码三档（自动 / 内置硬解 / 软件解码） |
| 源格式 | M3U / M3U8 / TXT，多线路（`#` 分隔）、`#EXTVLCOPT` 请求头 |
| 协议 | HTTP(S) / HLS / UDP / RTP / RTSP（组播已申请 MulticastLock） |
| 本地源 | 从本机 / U 盘导入，内容复制到 App 目录，不依赖网络 |
| 脚本 | QuickJS 引擎，自定义路由、签名、解密、转发 |
| 播放器代理 | HTTP / SOCKS5，只作用于播放器连源 |
| EPG | XMLTV，按 `tvg-id` 匹配 |
| 自愈 | 超时换源、卡顿换源、低速换源（速度低于 0.9 持续 N 秒） |
| 遥控 | 局域网 HTTP 服务，手机浏览器扫码控制 |
| 主题 | 深色 + 10 种强调色 |

---

## 脚本引擎

### 脚本放哪

```
设置 → 脚本目录     选一个外部目录（SAF），文件管理器可见
                    不选则用 Android/data/<包名>/files/scripts
```

目录下的 `.js` 文件自动加载，`lib/` 子目录放公共模块（不会被当成路由脚本）。

### 引擎限制

基于 QuickJS，**不是 Node**：

```
没有 require('fs') / require('https') / Buffer / process
没有 setTimeout
第三方库只能把源码打包进脚本
```

而且**不能写 async/await** —— 引擎不驱动微任务，`async` 函数返回的 Promise 永远 pending。
所有 API 都是同步返回。

### 声明

```js
exports.name     = "央视系";      // 显示名
exports.guard    = false;         // true = 全局守卫，所有请求都先过它
exports.priority = 100;           // 越大越先匹配，默认 0

// 路由：四种写法可以混用
exports.path     = "/cctv/:id";                    // 单条
exports.path     = ["/a/:id", "/b/:id"];           // 数组，共用 main
exports.host     = ["*.example.com"];              // 按域名
exports.urlRegex = "\\.m3u8";

// 或者逐条配置
exports.routes = [
    { path: "/demo/:id" },
    { path: "/demo/hd/:id", handler: "mainHd", priority: 90 },
    { host: ["*.cdn.com"], headers: { "Referer": "..." } },   // 纯请求头，不跑 JS
];
```

### 三种处理函数

```js
// 1. main —— 返回播放地址（302 给播放器）
function main(item) {
    return "https://cdn/x.m3u8";
}

// 2. handler —— 自己造内容
function handler(req) {
    return {
        status: 200,
        contentType: "application/vnd.apple.mpegurl",
        body: "#EXTM3U\n..."
    };
}

// 3. proxy —— 返回真实地址，由框架拉取并转发二进制
function proxy(req) {
    return {
        url: decrypt(req.params.token),
        headers: { "Referer": "https://site/" },
        proxy: "socks5://10.0.0.1:1080"      // 可选，不填用全局设置
    };
}
```

`proxy` 模式的关键：**播放器永远不知道真实地址**。适合分片要解密、要带防盗链、要走代理的场景。

### 能用的 API

```js
fetch(url, {
    headers: { "Referer": "..." },
    method: "GET",              // GET / POST / PUT
    body: "...",                // POST/PUT 的正文
    proxy: "socks5://ip:port"   // 单次代理；"" = 强制直连；不填用全局
})
  → { ok, status, url, headers.get(k), text(), json() }

md5(text)
storage.get(key) / set(key, value, ttlMs) / delete(key)
console.log / info / warn / error
require("./lib/util.js")        // CommonJS，同一文件只执行一次

// 标准 JS：JSON / Math / Date / RegExp / Map / Set / URL 等
```

框架**不塞任何默认请求头** —— 有些源恰恰不允许带 UA。带什么头是脚本的决定。

### 返回值

```js
return "https://cdn/x.m3u8";                    // 地址
return { url, headers, ttlSec, proxy };         // 带请求头
return { urls: [...] };                         // 多个候选
return "#EXTM3U\n..." ;                         // m3u8 文本
return { status, contentType, body };           // 自定义响应
return null;                                    // 解析失败
```

### 在订阅里用

```
CCTV-3,http://127.0.0.1:18899/cctv/cctv3
```

`18899` 是手机助手的端口（被占用时自动切 18900，见设置页显示的地址）。

### 示例：签名 + 请求头

```js
exports.name = "某源";
exports.path = "/src/:id";

const sign = require("./lib/sign.js");

function main(item) {
    const r = fetch("https://api.example.com/live?id=" + item.id, {
        headers: {
            "Referer": "https://www.example.com/",
            "User-Agent": "Mozilla/5.0 (Linux; Android 12)"
        }
    });
    if (!r.ok) return null;

    const data = r.json();
    const ts = String(Date.now()).substring(0, 10);
    return {
        url: data.url + "&t=" + ts + "&sign=" + sign.make(item.id, ts, data.salt),
        headers: { "Referer": "https://www.example.com/" },
        ttlSec: 60
    };
}
```

---

## 播放器代理

```
设置 → 播放器代理    socks5://192.168.1.2:1080
                    http://192.168.1.2:8080
                    留空 = 直连
```

**只作用于播放器**（VLC 连源，转成 `--socks` 或 `--http-proxy`）。

脚本不受它影响 —— 脚本要代理就在脚本里自己写：

```js
fetch(url, { proxy: "socks5://10.0.0.1:1080" });   // 走代理
fetch(url2);                                        // 直连
```

两套分开是有意的：

```
设置项      给不写脚本的普通用户，管播放器
脚本 proxy  给脚本作者，逐次指定，清楚自己要什么
```

组播（udp/rtp）不走 HTTP/SOCKS，这套代理对它无效。

---

## 设置项

| 项 | 说明 |
|---|---|
| 切源 | 手动切下一条线路 |
| 画面比例 | 自动 / 16:9 / 4:3 / 原始 / 铺满 |
| 超时换源 | 关闭 / 5 / 8 / 12 / 20 秒 |
| 解码方式 | 自动 / 内置硬解码 / 软件解码 |
| 画面输出 | TextureView / SurfaceView |
| 网络代理 | HTTP / SOCKS5，播放器和脚本都走 |
| 脚本目录 | SAF 选目录 |
| 导入本地源 | SAF 选文件 |
| 主题颜色 | 10 种强调色 |
| 频道分组管理 | 隐藏不看的分类 |

---

## 构建

```bash
./gradlew assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

CI 自动出包，产物在 `public/apk/`。

### 环境

```
JDK 17
Android SDK 34
minSdk 19 / targetSdk 34
```

---

## 按键

| 键 | 播放态 | 面板态 |
|---|---|---|
| 上 / 下 | 上一个 / 下一个频道 | 移动焦点 |
| 左 / 右 | 查看 EPG 回看 / 切源 | 移动焦点 / 改值 |
| OK | 唤回面板 | 确认 |
| BACK | 收面板 | 返回上一级 |
| 数字键 | 直接输入台号 | 同 |

---

## 目录

```
app/src/main/java/com/mintv/tv/
├── data/          数据与网络
│   ├── PlaylistParser      M3U / TXT 解析
│   ├── ScriptEngine        QuickJS 封装
│   ├── ScriptRouter        脚本路由分发
│   ├── ScriptStore         脚本文件读写
│   ├── ScriptDir           脚本目录（File / SAF 双后端）
│   ├── NetProxy            上游代理
│   ├── PlaybackAttempt     播放状态机（可单测）
│   └── RemoteServer        手机助手 HTTP 服务
├── ui/
│   ├── MainActivity        主界面
│   ├── OsdView             OSD
│   ├── Theme               配色
│   ├── player/             播放内核封装
│   └── widget/             自绘控件
└── assets/remote/          手机端网页
```

---

## 授权

见 [NOTICE.md](NOTICE.md)。

本应用仅为本地播放工具，不提供、不存储、不分发任何音视频内容。
所有直播源、EPG 数据与脚本均由用户自行添加。
