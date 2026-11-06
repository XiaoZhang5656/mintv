package com.mintv.tv.data;

import java.net.InetAddress;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 统一的 DNS 解析层。
 *
 * <p>为什么需要：{@code InetAddress.getByName} 不受任何 socket 超时约束。
 * 网络切换、DNS 服务器抽风那几秒，能卡住调用线程几秒到几十秒。
 * 表现出来是「起播慢」「脚本超时」「代理不生效」，但根子都在 DNS。
 *
 * <p>这里做三件事：
 * <ul>
 *   <li><b>超时</b>：解析丢线程池，最多等 {@code timeoutMs}，到点就放弃</li>
 *   <li><b>缓存</b>：解析成功的记 60 秒，一个频道几十个分片只解析一次</li>
 *   <li><b>改写</b>：把 URL 里的域名换成 IP，调用方补个 Host 头</li>
 * </ul>
 *
 * <p>限制：只对 http 有效。https 换成 IP 会证书不匹配，那部分保持原样 ——
 * 代价是 https 源的 DNS 仍然没有超时保护，但 https 源本来就不多。
 */
public final class Net {

    private static final String TAG = "Net";

    /** 默认解析预算。DNS 正常时 50ms 内就回来，3 秒已经非常宽松。 */
    /**
     * 单次解析预算。
     *
     * <p>3 秒实测不够 —— tracker 走 Cloudflare，移动网络首次解析
     * 经常要 4~5 秒。给 6 秒，超时后调用方仍有降级路径。
     */
    public static final long DNS_TIMEOUT_MS = 6000L;

    /** 解析结果缓存多久。太短没意义，太长换网络后不刷新。 */
    private static final long CACHE_TTL_MS = 60_000L;

    private static final class Entry {
        final InetAddress addr;
        final long expireAt;
        Entry(InetAddress a, long e) { addr = a; expireAt = e; }
    }

    private static final ConcurrentHashMap<String, Entry> CACHE =
            new ConcurrentHashMap<>();

    private static final ExecutorService POOL =
            Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "net-dns");
                    t.setDaemon(true);
                    return t;
                }
            });

    private Net() { }

    /** 是不是点分十进制 IPv4。 */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return false;
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /**
     * 带超时和缓存的解析。
     *
     * @return 解析成功返回地址；超时或失败返回 null（调用方要快速放弃，
     *         不要再自己去 {@code getByName}）
     */
    public static InetAddress resolve(String host, long timeoutMs) {
        if (host == null || host.isEmpty()) return null;

        // 已经是 IP，不用解析
        if (isIpLiteral(host)) {
            try {
                return InetAddress.getByName(host);
            } catch (Throwable t) {
                return null;
            }
        }

        long now = System.currentTimeMillis();
        Entry ce = CACHE.get(host);
        if (ce != null) {
            if (ce.expireAt > now) return ce.addr;
            CACHE.remove(host);
        }

        Future<InetAddress> f = POOL.submit(new java.util.concurrent.Callable<InetAddress>() {
            @Override public InetAddress call() throws Exception {
                return InetAddress.getByName(host);
            }
        });
        try {
            InetAddress a = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (a != null) {
                CACHE.put(host, new Entry(a, System.currentTimeMillis() + CACHE_TTL_MS));
            }
            return a;
        } catch (java.util.concurrent.TimeoutException te) {
            // 取消不掉阻塞中的解析，那个线程会在后台自己结束，不占调用方时间
            f.cancel(true);
            android.util.Log.w(TAG, "解析超时 " + timeoutMs + "ms: " + host);
            return null;
        } catch (Throwable t) {
            f.cancel(true);
            android.util.Log.w(TAG, "解析失败: " + host + " : " + t);
            return null;
        }
    }

    /** 解析结果，附带改写后的结果。 */
    public static final class Prepared {
        /** 给 URL 用的地址（域名已换成 IP）。 */
        public final String url;
        /** 需要补的 Host 头；null 表示不用补（本来就是 IP 或 https）。 */
        public final String hostHeader;
        /** 原始 host，日志用。 */
        public final String originHost;

        Prepared(String url, String hostHeader, String originHost) {
            this.url = url;
            this.hostHeader = hostHeader;
            this.originHost = originHost;
        }
    }

    /**
     * 把 URL 的域名换成已解析的 IP，为 {@code HttpURLConnection} 提前排掉 DNS。
     *
     * <p>只处理 http。https 换成 IP 会证书不匹配，原样返回 ——
     * 宁可让它慢一点，也不能让所有 https 源都连不上。
     *
     * @return null 表示解析失败，调用方应该直接放弃这条请求
     */
    public static Prepared prepare(String url) {
        return prepare(url, DNS_TIMEOUT_MS);
    }

    public static Prepared prepare(String url, long timeoutMs) {
        if (url == null || url.isEmpty()) return null;
        try {
            URL u = new URL(url);
            String scheme = u.getProtocol();
            String host = u.getHost();
            if (host == null || host.isEmpty()) return null;

            // https 保持原样：换 IP 证书会挂
            if (!"http".equalsIgnoreCase(scheme)) {
                if (!isIpLiteral(host)) {
                    // 至少探一次，让失败快点暴露；成功也不用结果
                    resolve(host, timeoutMs);
                }
                return new Prepared(url, null, host);
            }

            if (isIpLiteral(host)) {
                return new Prepared(url, null, host);
            }

            InetAddress addr = resolve(host, timeoutMs);
            if (addr == null) return null;

            String ip = addr.getHostAddress();
            // 用 IP 替换掉 host 部分（只替换 authority 里的，端口保留）
            int from = url.indexOf("://") + 3;
            int to = from;
            while (to < url.length()) {
                char c = url.charAt(to);
                if (c == '/' || c == '?' || c == '#') break;
                to++;
            }
            String authority = url.substring(from, to);
            int colon = authority.lastIndexOf(':');
            String portPart = colon > 0 ? authority.substring(colon) : "";
            String newUrl = url.substring(0, from) + ip + portPart + url.substring(to);
            return new Prepared(newUrl, host, host);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "prepare 失败: " + url + " : " + t);
            return null;
        }
    }

    /** 清空缓存。换网络或者用户改代理时调。 */
    public static void clearCache() {
        CACHE.clear();
    }
}
