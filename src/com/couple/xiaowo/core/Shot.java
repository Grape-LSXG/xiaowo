package com.couple.xiaowo.core;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.io.ByteArrayOutputStream;

/**
 * 点单端屏幕截图。
 *
 * 需求：接单端需要时，把点单端的屏幕内容发过去。
 *
 * 实现选型：**截取点单端自己的 View 树**（view.draw(Canvas)），
 * 而不是 MediaProjection。原因：
 *   · MediaProjection 需要系统级录屏授权（每次都要用户确认），
 *     而且只能截整个屏幕，包含状态栏和别的 App，既不隐私也不符合需求。
 *   · 点单端自己的 View 树就是"点单界面"，正好是要给对方看的内容。
 *   · 零权限、零弹窗，稳定可靠。
 *
 * 图片走 JPEG（有损但足够小），压缩到 ~80% 质量、限制一边最长 720px，
 * 避免 6MB 量级的 PNG 把中转通道撑爆。
 */
public final class Shot {

    private static final int MAX_EDGE = 720;
    private static final int JPEG_QUALITY = 80;

    private Shot() {
    }

    /** 截一个 View 树，返回 JPEG 字节；失败返回 null */
    public static byte[] capture(View root) {
        if (root == null) {
            return null;
        }
        try {
            int w = root.getWidth();
            int h = root.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            root.draw(c);
            // 缩放：限制最长边
            int max = Math.max(w, h);
            if (max > MAX_EDGE) {
                float scale = (float) MAX_EDGE / max;
                Bitmap small = Bitmap.createScaledBitmap(bmp,
                        Math.round(w * scale), Math.round(h * scale), true);
                if (small != bmp) {
                    bmp.recycle();
                }
                bmp = small;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            bmp.recycle();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 直接给 base64 字符串（用于塞进 JSON） */
    public static String captureBase64(View root) {
        byte[] jpg = capture(root);
        if (jpg == null) {
            return null;
        }
        return android.util.Base64.encodeToString(jpg, android.util.Base64.NO_WRAP);
    }
}