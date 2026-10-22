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

    // ------------------------------------------------------------------ 电池优化

    /**
     * 系统是不是已经对本应用免除电池优化。
     *
     * 判定用 isIgnoringBatteryOptimizations()：返回 true 表示已免除，
     * 也就是系统不会在 Doze 里掐我们的网络和定时器。
     */
    public static boolean isIgnoringBattery(Context c) {
        if (c == null) {
            return true;    // 拿不到就当作没问题，别无端骚扰用户
        }
        try {
            if (Build.VERSION.SDK_INT < 23) {
                return true;    // 6.0 以前没有 Doze
            }
            android.os.PowerManager pm = (android.os.PowerManager)
                    c.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return true;
            }
            return pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 拉起系统的「电池优化」豁免对话框。
     *
     * 为什么用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 而不是
     * 直接跳设置列表：前者是系统弹一个"允许免除电池优化？"的确认框，
     * 用户点一下就完事；后者要用户自己在长列表里翻找，很容易放弃。
     *
     * 需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限（已在清单里声明）。
     * 有些 ROM 会把这条 Intent 拦掉，这时退回设置列表页。
     */
    public static void requestIgnoreBattery(Activity act) {
        if (act == null) {
            return;
        }
        if (isIgnoringBattery(act)) {
            try {
                android.widget.Toast.makeText(c0(act), "已允许后台运行",
                        android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception ignore) {
            }
            return;
        }
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            markBatteryHintShown(act);
            return;
        } catch (Exception ignore) {
        }
        // 兜底：打开电池优化列表，让用户自己找
        try {
            Intent i = new Intent(
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            markBatteryHintShown(act);
        } catch (Exception ignore) {
        }
    }

    private static Context c0(Activity act) {
        return act;
    }

    /** 电池优化提示是否已经提示过一次 */
    public static boolean batteryHintShown(Context c) {
        return c != null && c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .getBoolean("battery_hint_shown", false);
    }

    public static void markBatteryHintShown(Context c) {
        try {
            c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                    .edit().putBoolean("battery_hint_shown", true).apply();
        } catch (Exception ignore) {
        }
    }

    /**
     * 厂商后台限制的引导是否已经确认过。
     *
     * vivo/小米/OPPO 的「自启动」「后台高耗电」「锁屏显示」都没有公开 API
     * 能检测，只能确认一次"我带你去过那个页面"。
     */
    public static boolean oemGuideConfirmed(Context c) {
        return c != null && c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .getBoolean("oem_guide_ok", false);
    }

    public static void markOemGuideConfirmed(Context c) {
        try {
            c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                    .edit().putBoolean("oem_guide_ok", true).apply();
        } catch (Exception ignore) {
        }
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
        if (act == null) {
            return;
        }
        // 点过就算"带用户去过一次" —— 至于系统里那几个开关他开没开，
        // 没有任何公开 API 能读到，只能靠这次确认避免反复打扰。
        markOemGuideConfirmed(act);
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + act.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception ignore) {
        }
    }
}
