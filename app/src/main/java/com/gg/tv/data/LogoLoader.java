package com.gg.tv.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 台标加载器。
 *
 * <p>只负责 tvg-logo 的下载与解码，**不猜图标** —— 源里没写 tvg-logo 就返回 null，
 * 界面上就不画图标位，频道名自然左对齐。
 *
 * <p>低端盒子三条硬规矩：
 * 1. 并发限 3，否则几十个频道的台标同时下载会把 GPU 上传队列打满
 * 2. 解码尺寸锁死 96px，原图直入必 OOM
 * 3. 内存 LRU 48 张，磁盘不落盘（台标是小图，改源就失效）
 */
public final class LogoLoader {

    private static final int TARGET_PX = 96;

    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(48) {
        @Override protected int sizeOf(String k, Bitmap v) { return v.getByteCount(); }
    };
    private final LruCache<String, Boolean> failed = new LruCache<>(128);
    private final ExecutorService pool = new ThreadPoolExecutor(
            3, 3, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(64),
            new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "logo");
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }
            });

    public interface Callback {
        void onLogo(String url, Bitmap bmp);
    }

    /** 同步取缓存，命中就直接返回，不触发网络。 */
    public Bitmap peek(String url) {
        return url == null ? null : mem.get(url);
    }

    /** 异步加载，回调在主线程之外——调用方自己 post 回 UI。 */
    public void load(final String url, final Callback cb) {
        if (url == null || url.isEmpty()) return;
        Bitmap hit = mem.get(url);
        if (hit != null) { cb.onLogo(url, hit); return; }
        if (failed.get(url) != null) return;

        pool.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = download(url);
                if (bmp == null) {
                    failed.put(url, Boolean.TRUE);
                    return;
                }
                mem.put(url, bmp);
                cb.onLogo(url, bmp);
            }
        });
    }

    public void shutdown() {
        pool.shutdownNow();
        mem.evictAll();
    }

    private Bitmap download(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "TVUI/1.0");
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

                // 先量尺寸再按 inSampleSize 解码，避免大图进内存
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

                // 等比缩到 96 高，宽不超 96（台标多是 1:1 或 4:3）
                float scale = TARGET_PX / (float) raw.getHeight();
                int tw = Math.max(1, (int) (raw.getWidth() * scale));
                int th = TARGET_PX;
                if (tw > TARGET_PX * 2) { tw = TARGET_PX * 2; }
                Bitmap out = Bitmap.createScaledBitmap(raw, tw, th, true);
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
