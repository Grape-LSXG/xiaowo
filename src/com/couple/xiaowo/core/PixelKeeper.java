package com.couple.xiaowo.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 一像素悬浮窗保活。
 *
 * ── 为什么需要它 ──
 *
 * 前台服务 + 常驻通知已经能让进程"活着"，但**保不住网络回调**：
 * 实测过，接单端退到后台几分钟后，监听端口就不再应答（本机 loopback 都超时），
 * 表现就是"必须打开 App 才能收到通知和弹窗"。
 *
 * 原因不在进程被杀，而在系统把 App 判定为"后台受限"，冻结了它的网络活动。
 * 而要打破这个判定，最有效的手段是：**持有一个可见的窗口**。
 * Android 对"屏幕上还有窗口的进程"会给更高的进程优先级，
 * 而且不会把它当作后台应用去冻结。
 *
 * ── 为什么是 1 像素 ──
 *
 * 用户不该看见它。做法：
 *   · 尺寸 1×1 px
 *   · 完全透明（alpha = 0）
 *   · 不接收触摸（FLAG_NOT_TOUCHABLE），点不到、也不挡任何操作
 *   · 贴在屏幕左上角外侧一点点（坐标 -1），几乎不可能被误看见
 *
 * 代价：需要「显示在其他应用上层」权限（SYSTEM_ALERT_WINDOW），
 * 必须用户手动授权 —— 这个不能静默请求。
 *
 * ── 和一像素相关的取舍 ──
 *
 * 也考虑过"1 像素 + 略微可见"。不这么做，因为情侣 App 挂一个常驻小点
 * 在屏幕上会让人困惑。完全透明 + 不可触摸是最不打扰的形态。
 */
public final class PixelKeeper {

    private static WindowManager wm;
    private static View pixel;
    private static WindowManager.LayoutParams lp;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** 保活期间把进程拉到前台优先级的兜底定时器 */
    private static Runnable ticker;

    private PixelKeeper() {
    }

    /** 有没有「显示在其他应用上层」权限 */
    public static boolean canOverlay(Context c) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;   // 6.0 以下这个权限默认给
        }
        return Settings.canDrawOverlays(c);
    }

    /** 跳去授权页 */
    public static void requestOverlay(Activity act) {
        if (act == null || canOverlay(act)) {
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception ignore) {
        }
    }

    /**
     * 立起一像素窗口。可以重复调用（幂等）。
     *
     * @return true 表示已经在跑
     */
    public static boolean start(final Context c) {
        if (c == null) {
            return false;
        }
        /*
         * addView() 必须在主线程调用，否则抛
         * "ViewRootImpl$CalledFromWrongThreadException"。
         * 启动路径里既有 MainActivity 主线程调用，也有 KeepAlive 服务的
         * onStartCommand（也可能被系统在别的线程回调），
         * 统一在这里切一次，调用方不用关心自己在哪个线程。
         */
        if (Looper.myLooper() != Looper.getMainLooper()) {
            UI.post(new Runnable() {
                public void run() {
                    start(c);
                }
            });
            // 异步执行，此刻还不知道成败；返回"已排期"的乐观值不影响自愈逻辑
            return pixel != null;
        }
        return startOnMain(c);
    }

    private static synchronized boolean startOnMain(final Context c) {
        if (pixel != null) {
            return true;
        }
        if (!canOverlay(c)) {
            return false;
        }
        try {
            wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return false;
            }

            /*
             * 用真正的 View 而不是空壳：某些 ROM 会把"没有内容的 View"
             * 优化掉或者不认为它在绘制，那样就起不到保活作用。
             * 这里给一个空的 TextView 并把文字色设成透明，确保它真的被绘制。
             */
            TextView v = new TextView(c);
            v.setText(".");
            v.setTextColor(0x00000000);
            v.setBackgroundColor(0x00000000);
            pixel = v;

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            lp = new WindowManager.LayoutParams(
                    1, 1,                       // 1×1 像素
                    type,
                    /*
                     * 关键 flag：
                     *   NOT_FOCUSABLE     —— 不抢输入焦点
                     *   NOT_TOUCHABLE     —— 点不到，不挡操作
                     *   LAYOUT_IN_SCREEN  —— 允许贴边
                     *   不加 FLAG_NOT_TOUCH_MODAL 保证完全不参与交互
                     */
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = -1;      // 挪到屏幕外一点点，进一步避免被看见
            lp.y = -1;
            lp.alpha = 0f;  // 完全透明

            wm.addView(pixel, lp);

            /*
             * 兜底：有些 ROM 会在一段时间后回收"不可见"的悬浮窗。
             * 每分钟检查一次，发现被移除了就重新加上。
             * 这个定时器本身也顺带让进程保持活跃。
             */
            ticker = new Runnable() {
                public void run() {
                    try {
                        if (pixel != null && pixel.getWindowToken() == null && wm != null) {
                            wm.addView(pixel, lp);
                        }
                    } catch (Exception ignore) {
                        // 已经加过了会抛，忽略
                    }
                    UI.postDelayed(this, 60000L);
                }
            };
            UI.postDelayed(ticker, 60000L);
            return true;
        } catch (Exception e) {
            pixel = null;
            return false;
        }
    }

    /** 撤掉窗口 */
    public static synchronized void stop() {
        if (ticker != null) {
            UI.removeCallbacks(ticker);
            ticker = null;
        }
        try {
            if (pixel != null && wm != null) {
                wm.removeView(pixel);
            }
        } catch (Exception ignore) {
        }
        pixel = null;
        wm = null;
        lp = null;
    }

    public static boolean isRunning() {
        return pixel != null;
    }
}
