package com.mintv.tv.data;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码生成。
 *
 * <p>只引了 zxing-core（纯算法，不含任何 Android UI），
 * 六百来 KB，比手写 QR 编码靠谱得多。
 *
 * <p>纠错等级用 M（15%）：电视上二维码是给人扫的，
 * 屏幕反光、距离远，L(7%) 容易扫不出，H(30%) 又会让码点变密。
 */
public final class QrUtil {

    private QrUtil() { }

    public static Bitmap encode(String text, int sizePx) {
        if (text == null || text.isEmpty() || sizePx <= 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            // 一圈静区，扫码器需要
            hints.put(EncodeHintType.MARGIN, 1);

            com.google.zxing.common.BitMatrix m =
                    new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int off = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[off + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }
}
