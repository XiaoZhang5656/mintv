package com.mintv.tv.ui.player;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 宽容的 HTTP 数据源。
 *
 * <p>为什么需要：国内大量直播/CDN 站点的 HTTPS 证书是自签或用了系统不认的 CA，
 * 系统默认的 DefaultHttpDataSource 会直接抛 ERROR_CODE_IO_NETWORK_CONNECTION_FAILED，
 * 但 curl / 浏览器能正常取到流。电视盒子上这类源很多，不放宽就是一批频道打不开。
 *
 * <p>做法：自建 SSLContext，TrustManager 接受任意证书，并关闭域名校验。
 * 代价是失去中间人防护 —— 对公开直播流可以接受。
 *
 * <p>同时补齐 Range 请求、重定向和超时，保证 seek 时能分片下载。
 */
@UnstableApi
public final class LenientHttpDataSource extends BaseDataSource implements HttpDataSource {

    public static final class Factory implements HttpDataSource.Factory {
        private String userAgent = "MinTV/1.0";
        private int connectTimeoutMs = 8000;
        private int readTimeoutMs = 12000;
        private Map<String, String> defaultHeaders;

        @Override public HttpDataSource createDataSource() {
            return new LenientHttpDataSource(userAgent, connectTimeoutMs, readTimeoutMs, defaultHeaders);
        }

        public Factory setUserAgent(String ua) { this.userAgent = ua; return this; }

        public Factory setConnectTimeoutMs(int ms) { this.connectTimeoutMs = ms; return this; }

        public Factory setReadTimeoutMs(int ms) { this.readTimeoutMs = ms; return this; }

        public Factory setDefaultRequestProperties(Map<String, String> headers) {
            this.defaultHeaders = headers;
            return this;
        }
    }

    private static SSLContext lenientSsl() {
        try {
            TrustManager[] tms = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                        @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tms, new java.security.SecureRandom());
            return ctx;
        } catch (Exception e) {
            return null;
        }
    }

    private static final HostnameVerifier ACCEPT_ALL = new HostnameVerifier() {
        @Override public boolean verify(String hostname, SSLSession session) { return true; }
    };

    /** 这几个头由代码统一设置，不让自定义头覆盖，避免把 Range/UA 搞坏。 */
    private static boolean isProtected(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.equals("user-agent") || n.equals("range") || n.equals("accept-encoding")
                || n.equals("connection") || n.equals("host");
    }

    private final String userAgent;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final Map<String, String> defaultHeaders;
    private final Map<String, String> extraHeaders = new HashMap<>();

    private HttpURLConnection connection;
    private InputStream inputStream;
    private Uri uri;
    private long bytesRemaining;
    private boolean opened;
    private int responseCode;

    private LenientHttpDataSource(String ua, int ct, int rt, Map<String, String> headers) {
        super(true);
        this.userAgent = ua;
        this.connectTimeoutMs = ct;
        this.readTimeoutMs = rt;
        this.defaultHeaders = headers;
    }

    /** 跨主机重定向的最大跳数。超过就当源坏了，别无限转。 */
    private static final int MAX_REDIRECTS = 5;

    @Override
    public long open(DataSpec dataSpec) throws HttpDataSourceException {
        this.uri = dataSpec.uri;
        transferInitializing(dataSpec);

        try {
            URL url = new URL(dataSpec.uri.toString());
            int redirects = 0;

            // 手动跟跳转，不能交给 HttpURLConnection。
            //
            // 原因：setInstanceFollowRedirects(true) 在跨主机跳转时会**丢掉
            // 所有自定义请求头**（JDK 的 HttpURLConnection 只保留系统允许的那几个）。
            // 国内直播 CDN 基本都是「302 到另一台机器 + 靠 UA/Referer 鉴权」，
            // 一跳之后头没了，对端直接 403/404 —— Exo 报的就是
            // ERROR_CODE_IO_BAD_HTTP_STATUS，而且 curl 手动跟一遍是 200，
            // 排查时会误以为「源挂了」。这里逐跳自己打，每跳都重放请求头。
            while (true) {
                connection = (HttpURLConnection) url.openConnection();

                if (connection instanceof HttpsURLConnection) {
                    HttpsURLConnection https = (HttpsURLConnection) connection;
                    SSLContext ctx = lenientSsl();
                    if (ctx != null) {
                        https.setSSLSocketFactory(ctx.getSocketFactory());
                        https.setHostnameVerifier(ACCEPT_ALL);
                    }
                }

                connection.setConnectTimeout(connectTimeoutMs);
                connection.setReadTimeout(readTimeoutMs);
                connection.setInstanceFollowRedirects(false);
                String ua = userAgent;
                if (defaultHeaders != null && defaultHeaders.containsKey("User-Agent")) {
                    ua = defaultHeaders.get("User-Agent");
                }
                connection.setRequestProperty("User-Agent", ua);
                connection.setRequestProperty("Accept", "*/*");

                if (dataSpec.position != 0 || dataSpec.length != C.LENGTH_UNSET) {
                    long end = dataSpec.length == C.LENGTH_UNSET
                            ? -1 : dataSpec.position + dataSpec.length - 1;
                    connection.setRequestProperty("Range",
                            "bytes=" + dataSpec.position + "-" + (end == -1 ? "" : String.valueOf(end)));
                }

                // 自定义头每一跳都要重放，这是整段代码存在的理由
                if (defaultHeaders != null) {
                    for (Map.Entry<String, String> e : defaultHeaders.entrySet()) {
                        if (isProtected(e.getKey())) continue;
                        connection.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    if (isProtected(e.getKey())) continue;
                    connection.setRequestProperty(e.getKey(), e.getValue());
                }
                if (dataSpec.httpRequestHeaders != null) {
                    for (Map.Entry<String, String> e : dataSpec.httpRequestHeaders.entrySet()) {
                        if (isProtected(e.getKey())) continue;
                        connection.setRequestProperty(e.getKey(), e.getValue());
                    }
                }

                responseCode = connection.getResponseCode();

                if (responseCode == 301 || responseCode == 302
                        || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                    String loc = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (loc == null || redirects++ >= MAX_REDIRECTS) {
                        throw new HttpDataSource.HttpDataSourceException(
                                new java.io.IOException("redirect without location or too many hops"),
                                dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
                    }
                    // 相对跳转
                    url = new URL(url, loc);
                    // 关键：把最终地址记下来。Exo 用 getUri() 解析 m3u8 里的相对
                    // 片段路径（yss/11622.ts），不回传最终地址的话，片段会被
                    // 拼到第一跳的主机上 —— 那边根本没有这些文件，全部 404。
                    this.uri = Uri.parse(url.toString());
                    continue;
                }
                break;
            }

            if (responseCode < 200 || responseCode > 299) {
                throw new HttpDataSource.InvalidResponseCodeException(
                        responseCode, connection.getResponseMessage(), null,
                        connection.getHeaderFields(), dataSpec, null);
            }

            inputStream = connection.getInputStream();
            long contentLength = connection.getContentLengthLong();
            if (responseCode == 206 && contentLength > 0) {
                bytesRemaining = contentLength;
            } else if (contentLength > 0 && dataSpec.length == C.LENGTH_UNSET) {
                bytesRemaining = contentLength - dataSpec.position;
            } else {
                bytesRemaining = dataSpec.length == C.LENGTH_UNSET ? C.LENGTH_UNSET : dataSpec.length;
            }

            opened = true;
            transferStarted(dataSpec);
            return bytesRemaining;
        } catch (HttpDataSourceException e) {
            throw e;
        } catch (IOException e) {
            throw new HttpDataSource.HttpDataSourceException(
                    e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws HttpDataSourceException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
        try {
            int read = inputStream.read(buffer, offset, length);
            if (read == -1) return C.RESULT_END_OF_INPUT;
            if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= read;
            bytesTransferred(read);
            return read;
        } catch (IOException e) {
            throw new HttpDataSource.HttpDataSourceException(
                    e, null, HttpDataSource.HttpDataSourceException.TYPE_READ);
        }
    }

    @Override
    public Uri getUri() { return uri; }

    @Override
    public void close() throws HttpDataSourceException {
        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {
        } finally {
            inputStream = null;
            if (connection != null) connection.disconnect();
            connection = null;
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }

    @Override
    public int getResponseCode() { return responseCode; }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return connection == null ? Collections.<String, List<String>>emptyMap()
                : connection.getHeaderFields();
    }

    @Override
    public void setRequestProperty(String name, String value) { extraHeaders.put(name, value); }

    @Override
    public void clearRequestProperty(String name) { extraHeaders.remove(name); }

    @Override
    public void clearAllRequestProperties() { extraHeaders.clear(); }
}
