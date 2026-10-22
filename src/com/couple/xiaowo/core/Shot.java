package com.couple.xiaowo.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 屏幕截图 —— 三级降级，优先用「真·静默截屏」。
 *
 * ── 三级降级链 ──
 *
 *  ① 无障碍静默截屏（ShotService，API 30+）
 *    系统级的整屏截图，**点一次以后永久生效、没有任何确认弹窗**。
 *    截到的是真实屏幕：状态栏、弹层、甚至别的 App 都在。
 *    缺点：需要用户在系统设置里手动开一次无障碍服务（一次性的）。
 *
 *  ② App 内 View 树截图（view.draw(Canvas)）
 *    零权限、零配置，永远可用。但只能截自己的界面，
 *    而且 App 在后台 / 正在重建时可能拿到 0×0 → 失败。
 *    作为兜底，保证「就算无障碍没开也能用」。
 *
 *  ③ 返回 null，由调用方回一句「截图失败」，不要静默装死。
 *
 * ── 为什么必须走 ① 才能叫「单端静默」 ──
 *
 * 「静默」的定义是：被截屏那一端**不需要任何操作、不弹任何框**。
 * 只有无障碍 takeScreenshot() 满足 —— MediaProjection 要用户点「开始录制」，
 * 加了前台服务也还是要一次确认。
 */
public final class Shot {

    /**
     * 截出来的图上限：最长边 720px、JPEG 80%。
     * 原来用这个值是为了塞进 MQTT 中转（公共 broker 对单包大小有限制）。
     * 现在直连就够，但小图也传得快，保持。
     */
    private static final int MAX_EDGE = 720;
    private static final int JPEG_QUALITY = 80;

    /** 无障碍截屏的等待上限：系统回调通常几十毫秒，3 秒够宽松了 */
    private static final long A11Y_TIMEOUT_MS = 3000;

    private Shot() {
    }

    /** 一次截屏请求的载体（ShotService 的异步回调会往里写） */
    static final class Result {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Bitmap bitmap;
        volatile String error;
    }

    /**
     * 当前这台机器上最靠谱的那种截屏能不能用。
     * 给「设置」页显示状态用。
     */
    public static boolean hasSystemShot(Context c) {
        return ShotService.isReady() || ShotService.isEnabledInSettings(c);
    }

    /**
     * 主入口：截屏并返回 JPEG 字节。失败返回 null。
     *
     * @param fallbackRoot 截图 ② 兜底用的 View 树，没有就传 null
     */
    public static byte[] capture(Context c, View fallbackRoot) {
        Bitmap bmp = captureBitmap(c, fallbackRoot);
        if (bmp == null) {
            return null;
        }
        return toJpeg(bmp);
    }

    /** 直接给 base64（用于塞进 JSON） */
    public static String captureBase64(Context c, View fallbackRoot) {
        byte[] jpg = capture(c, fallbackRoot);
        if (jpg == null) {
            return null;
        }
        return android.util.Base64.encodeToString(jpg, android.util.Base64.NO_WRAP);
    }

    /**
     * 只拿 Bitmap（不压缩）。给需要显示原图的场景用。
     */
    public static Bitmap captureBitmap(Context c, View fallbackRoot) {
        // ① 无障碍静默截屏
        Bitmap sys = captureViaAccessibility();
        if (sys != null) {
            return sys;
        }
        // ② 自己的 View 树
        return captureView(fallbackRoot);
    }

    /** 走无障碍服务截屏；服务不可用或超时返回 null */
    private static Bitmap captureViaAccessibility() {
        if (!ShotService.isReady()) {
            return null;
        }
        Result r = new Result();
        ShotService.capture(r);
        try {
            if (!r.done.await(A11Y_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return null;    // 超时：系统可能在忙，交给 ② 兜底
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return r.bitmap;
    }

    /** 截一个 View 树（兜底方案） */
    public static Bitmap captureView(View root) {
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
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /** 缩放 + 压成 JPEG */
    public static byte[] toJpeg(Bitmap bmp) {
        if (bmp == null) {
            return null;
        }
        Bitmap work = bmp;
        try {
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int max = Math.max(w, h);
            if (max > MAX_EDGE) {
                float scale = (float) MAX_EDGE / max;
                Bitmap small = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(w * scale)),
                        Math.max(1, Math.round(h * scale)), true);
                if (small != bmp) {
                    work = small;
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            work.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (work != bmp) {
                    work.recycle();
                }
                bmp.recycle();
            } catch (Exception ignore) {
            }
        }
    }
}
