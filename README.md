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
设置 → 存储目录   选一个外部目录（SAF），文件管理器可见
                  默认 /sdcard/mintv，脚本放它的 scripts/ 子目录
```

`.js` 文件放进去自动加载，`lib/` 子目录放公共模块（不会被当成路由脚本）。

### 引擎限制

基于 QuickJS，**不是 Node**：

```
没有 require('fs') / require('https') / Buffer / process
没有 setTimeout / setInterval
第三方库只能把源码打包进脚本
```

**不能写 async/await** —— 引擎不驱动微任务，`async` 函数返回的 Promise 永远 pending。
所有 API 都是同步返回。

**不能写无界循环**。QuickJS 的 Java 绑定没有暴露中断接口，脚本里
`while(true){}` 会永久占死一个线程，只能靠线程池隔离，事后在
`/api/logs` 里看到「执行耗时 xxxx ms」才发现。要循环就带 break 条件。

### 声明

```js
exports.name     = "咪咕直播";     // 显示名，默认用文件名
exports.priority = 100;            // 越大越先匹配，默认 0

// 路由
exports.path = "/cctv/:id";                  // 单条
exports.path = ["/a/:id", "/b.m3u"];         // 数组，共用 main
exports.host = ["*.example.com"];            // 按域名

// 订阅清单入口：声明后框架自动把它注册成一个订阅源，
// 用户不用手动往订阅里加 http://127.0.0.1:18899/xxx.m3u
exports.list = "/migu.m3u";
```

`:id` 这种路径参数会进 `item.id`，也会进 `item.params.id`。

### 入口函数

**必须挂在 exports 上**：

```js
exports.main = function (item) {
    return "https://cdn/x.m3u8";
};
```

写 `function main(item){}` 这种全局函数**不生效**，调用时会报
`UnsupportedOperationException`。

### item 里有什么

```js
item.url      // 完整请求路径 + query，如 "/migu/1?x=2"
item.path     // 只有路径，如 "/migu/1"
item.id       // 第一个路径参数（:id 的值）
item.params   // 路径参数 + query 参数，都是字符串
item.headers  // 这次请求的真实请求头（Referer / Cookie / UA 等）
item.name     // 脚本名（exports.name）
item.pageUrl  // 完整地址 http://127.0.0.1:<port><path>?<query>
```

### 返回值

```js
// 1. 直接给地址（302 给播放器）
return "https://cdn/x.m3u8";
return { url: "https://cdn/x.m3u8", headers: { "Referer": "..." } };

// 2. 多候选，框架按顺序试
return { urls: ["https://a/x.m3u8", "https://b/x.m3u8"] };

// 3. 自己造内容（清单入口、加密播放列表都走这条）
return {
    status: 200,
    contentType: "application/vnd.apple.mpegurl",
    body: "#EXTM3U\n#EXTINF:-1,台\nhttp://..."
};

// 4. 解析失败
return null;
```

可选字段：

```
headers        请求头对象，播放器会带上
ttlSec         结果缓存多少秒。声明后同样的 path+query 在有效期内直接返回缓存，
               不再执行脚本 —— 签名类源（每次要 800ms）靠这个才不卡
proxy          这次转发走哪个代理，如 "socks5://ip:port"
rewriteSegments  true 时框架会把 m3u8 里的分片改写成绝对地址
```

### 代理模式

脚本声明 `exports.proxy`（有这个名字就行），框架改用 proxy 入口：

```js
exports.proxy = function (req) {
    return {
        url: decrypt(req.params.token),        // 真实地址
        headers: { "Referer": "https://site/" },
        proxy: "socks5://10.0.0.1:1080"        // 可选
    };
};
```

**播放器永远不知道真实地址**，适合分片要解密、要带防盗链、要走代理的场景。

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
console.log / info / warn / error        // 写进 /api/logs
require("./lib/util.js")                 // CommonJS，同一文件只执行一次
```

`fetch` 内部是 `HttpURLConnection`，**会自动跟随 302**，`r.url` 是最终地址。

框架**不塞任何默认请求头** —— 有些源恰恰不允许带 UA。带什么头是脚本的决定。

### 看日志

脚本里 `console.log` 的输出不进 logcat，走内存环形缓冲：

```
GET  http://127.0.0.1:18899/api/logs          最近 500 条
DELETE /api/logs                              清空
GET  /scripts                                 手机助手里的脚本管理页
```

### 完整例子

```js
exports.name = "示例源";
exports.path = ["/demo/:id", "/demo.m3u"];
exports.list = "/demo.m3u";

exports.main = function (item) {
    // 清单入口：没带 id 就返回全部频道
    if (!item.id) {
        var lines = ["#EXTM3U"];
        lines.push("#EXTINF:-1,CCTV-1");
        lines.push(item.pageUrl.replace("/demo.m3u", "/demo/1"));
        return {
            status: 200,
            contentType: "application/vnd.apple.mpegurl",
            body: lines.join("\n")
        };
    }

    // 单个频道：解析出播放地址
    var r = fetch("https://api.example.com/live?id=" + item.id, {
        headers: { "Referer": item.headers["Referer"] || "https://example.com/" }
    });
    if (!r.ok) {
        console.warn("上游 " + r.status);
        return null;
    }
    var j = r.json();
    return {
        url: j.url,
        headers: { "Referer": "https://example.com/" },
        ttlSec: 600          // 这个地址 10 分钟内有效，期间不重复解析
    };
};
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
