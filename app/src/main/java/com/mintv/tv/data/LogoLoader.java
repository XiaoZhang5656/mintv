package com.mintv.tv.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 台标加载器。
 *
 * <p>只负责 tvg-logo 的下载与解码，**不猜图标** —— 源里没写 tvg-logo 就返回 null，
 * 界面上就不画图标位。
 *
 * <p>低端盒子几条硬规矩：
 * <ol>
 *   <li><b>不整表预加载</b>。346 个频道同时入队会打爆线程池（上一版就是这样崩的：
 *       RejectedExecutionException）。改成列表滚动 / 绑定时按需请求，只加载真正会显示的行。</li>
 *   <li>队列满了就<b>直接丢弃</b>，不抛异常 —— 下次滚回来还会再请求。</li>
 *   <li>解码尺寸锁死 96px 高，原图直入必 OOM。</li>
 *   <li>并发 3，内存 LRU 64 张，不落盘。</li>
 * </ol>
 */
public final class LogoLoader {

    private static final int TARGET_PX = 96;
    private static final int MAX_QUEUE = 24;

    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(64) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };
    private final LruCache<String, Boolean> failed = new LruCache<>(256);
    /** 正在下载或排队中的 URL，避免重复入队。 */
    private final Set<String> inFlight = new HashSet<>();

    private final AtomicInteger rejects = new AtomicInteger();

    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            3, 3, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(MAX_QUEUE),
            new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "tvui-logo");
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            },
            // 队列满 → 丢弃，绝不抛 RejectedExecutionException
            new java.util.concurrent.RejectedExecutionHandler() {
                @Override public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                    rejects.incrementAndGet();
                }
            });

    public interface Callback {
        void onLogo(String url, Bitmap bmp);
    }

    /** 同步取缓存，命中直接返回，不走网络。 */
    public Bitmap peek(String url) {
        return url == null ? null : mem.get(url);
    }

    /**
     * 异步加载。可安全重复调用：正在排队的同一 URL 不会重复入队。
     * 队列满时静默丢弃，调用方下次重新绑定时会再请求。
     */
    public void load(final String url, final Callback cb) {
        if (url == null || url.isEmpty()) return;
        Bitmap hit = mem.get(url);
        if (hit != null) {
            cb.onLogo(url, hit);
            return;
        }
        if (failed.get(url) != null) return;

        synchronized (inFlight) {
            if (inFlight.contains(url)) return;
            inFlight.add(url);
        }

        boolean accepted;
        try {
            final Callback callback = cb;
            pool.execute(new Runnable() {
                @Override public void run() {
                    try {
                        Bitmap bmp = download(url);
                        if (bmp == null) {
                            failed.put(url, Boolean.TRUE);
                            return;
                        }
                        mem.put(url, bmp);
                        callback.onLogo(url, bmp);
                    } finally {
                        synchronized (inFlight) { inFlight.remove(url); }
                    }
                }
            });
            accepted = true;
        } catch (RuntimeException e) {
            accepted = false;
        }
        if (!accepted) {
            synchronized (inFlight) { inFlight.remove(url); }
        }
    }

    public int rejectedCount() { return rejects.get(); }

    public void shutdown() {
        pool.shutdownNow();
        mem.evictAll();
        synchronized (inFlight) { inFlight.clear(); }
    }

    private Bitmap download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 TVUI/1.0");
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 512 * 1024) return null;   // 台标不可能这么大
                }
                byte[] data = bos.toByteArray();

                BitmapFactory.Options probe = new BitmapFactory.Options();
                probe.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, probe);
                int w = probe.outWidth, h = probe.outHeight;
                if (w <= 0 || h <= 0) return null;

                int sample = 1;
                while (Math.max(w, h) / (sample * 2) >= TARGET_PX) sample *= 2;

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                if (raw == null) return null;

                float scale = TARGET_PX / (float) raw.getHeight();
                int tw = Math.max(1, Math.min((int) (raw.getWidth() * scale), TARGET_PX * 2));
                Bitmap out = Bitmap.createScaledBitmap(raw, tw, TARGET_PX, true);
                if (out != raw) raw.recycle();
                return out;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
