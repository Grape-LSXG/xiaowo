package com.couple.xiaowo.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * 保活总闸 —— 强制默认开启、不可关闭、持续自检。
 *
 * ── 为什么改成「不可关闭」 ──
 *
 * 当初做开关是为了「用户自己决定要不要多一条常驻通知」。
 * 但实测结论很明确：**关掉保活 = 对方收不到**。
 * 接单端退到后台几秒，8765 端口就不再应答，点单端只会一直超时报「没连上」。
 * 既然关掉这个开关只会让 App 变成坏掉的，那它就不该存在。
 *
 * 所以现在的语义是：
 *   · 第一次启动就默认开启（不需要用户做什么）
 *   · 界面上不再提供关闭入口，只显示状态 + 「去补权限」
 *   · 每次 onResume / 每隔一段时间自检，发现掉了就自己拉回来
 *
 * ── 自检覆盖哪几件事 ──
 *
 *   1. 前台服务在跑吗（KeepAlive）
 *   2. 一像素窗口立着吗（PixelKeeper）—— 需要悬浮窗权限
 *   3. 无障碍截屏服务开着吗（ShotService）—— 截图的静默前提
 *   4. 通知权限 / 安装权限（交给 PermissionGate）
 *
 * 前三个是「掉了就自己修」，第四个是「掉了必须用户去点」，分开处理。
 */
public final class Keep {

    /** 一像素窗自愈的检查周期：和 PixelKeeper 内部的 self-heal 分开，这里是更高一层的兜底 */
    private static final long CHECK_INTERVAL_MS = 5 * 60 * 1000L;

    private static android.os.Handler ticker;
    private static Runnable task;

    private Keep() {
    }

    // ------------------------------------------------------------------ 同步

    /**
     * 拉起/修复所有保活手段。
     *
     * 幂等，随便调：Activity.onCreate、onResume、定时器里都可以。
     *
     * @return 是否需要用户去补权限（true 表示有缺口且不是代码能修的）
     */
    public static boolean sync(Activity act, boolean isRecv, String hint) {
        if (act == null) {
            return false;
        }
        Context c = act;

        // 1) 前台服务：无条件拉起（它内部对重复 start 是幂等的）
        KeepAlive.start(c, isRecv ? Protocol.ROLE_RECV : Protocol.ROLE_ORDER,
                hint == null || hint.length() == 0 ? defaultHint(isRecv) : hint);

        // 2) 一像素窗：有悬浮窗权限就立起来；没权限是「需要用户去点」
        boolean overlayMissing = false;
        if (PixelKeeper.canOverlay(c)) {
            PixelKeeper.start(c);
        } else {
            overlayMissing = true;
        }

        // 3) 无障碍截屏：开着就什么都不用做（系统自己维持），没开是「需要用户去点」
        boolean shotMissing = Build.VERSION.SDK_INT >= 30
                && !ShotService.isReady()
                && !ShotService.isEnabledInSettings(c);

        return overlayMissing || shotMissing;
    }

    /** 只做「代码能自己修」的部分，不打扰用户（定时器里用） */
    public static void heal(Context c) {
        if (c == null) {
            return;
        }
        KeepAlive.start(c, roleOf(c), defaultHint(!isOrderRole(c)));
        if (PixelKeeper.canOverlay(c) && !PixelKeeper.isRunning()) {
            PixelKeeper.start(c);
        }
    }

    private static String roleOf(Context c) {
        return isOrderRole(c) ? Protocol.ROLE_ORDER : Protocol.ROLE_RECV;
    }

    private static boolean isOrderRole(Context c) {
        // 不引 BuildInfo 静态依赖（core 层不依赖构建期生成类），用包名判断
        return c.getPackageName().endsWith(".order");
    }

    private static String defaultHint(boolean isRecv) {
        return isRecv ? "正在等猫猫点单" : "正在等着张老师";
    }

    // ------------------------------------------------------------------ 定时器

    /** 开始周期性自检。每个进程只注册一份。 */
    public static synchronized void startWatchdog(Context c) {
        // 用 applicationContext，别把 Activity 拿住
        final Context app = c.getApplicationContext() == null
                ? c : c.getApplicationContext();
        if (ticker != null) {
            return;
        }
        ticker = new android.os.Handler(android.os.Looper.getMainLooper());
        task = new Runnable() {
            public void run() {
                try {
                    heal(app);
                } catch (Throwable ignore) {
                }
                if (ticker != null) {
                    ticker.postDelayed(this, CHECK_INTERVAL_MS);
                }
            }
        };
        ticker.postDelayed(task, CHECK_INTERVAL_MS);
    }

    public static synchronized void stopWatchdog() {
        if (ticker != null && task != null) {
            ticker.removeCallbacks(task);
        }
        ticker = null;
        task = null;
    }

    // ------------------------------------------------------------------ 状态

    /**
     * 保活服务是不是在跑。
     *
     * 直接读 KeepAlive 的进程内标记 —— 服务就活在本进程里，
     * 这个标记比 ActivityManager.getRunningServices() 准得多
     * （后者在 Android 17 的 vivo 上对非 debuggable 应用会返回空列表）。
     */
    public static boolean serviceRunning(Context c) {
        return KeepAlive.isRunning();
    }

    /** 「去补悬浮窗权限」 */
    public static void goOverlay(Activity act) {
        PixelKeeper.requestOverlay(act);
    }

    /** 「去开无障碍截屏」 */
    public static void goShot(Activity act) {
        ShotService.requestEnable(act);
    }

    /** 跳到本 App 的应用详情页（后台限制 / 自启动都在那附近） */
    public static void goAppDetail(Activity act) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception ignore) {
        }
    }
}
