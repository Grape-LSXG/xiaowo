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

    /**
     * vivo 专属引导：锁屏显示 + 后台弹出界面 + 自启动。
     *
     * 这三项在 vivo（OriginOS/FuntouchOS）上属于「权限管理 → 其他权限」
     * 和「电池 → 后台耗电管理」体系，没有统一的系统 Intent 能一键打开，
     * 所以跳 App 详情页，让用户在那个页面里逐项开（很多条目也在同一页）。
     */
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
            // 优先打开 vivo/iQOO 手机管家（i管家），自启动/后台管理入口都集中在那
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
            // 找不到 i管家，退回 App 详情页
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

    private PermissionGate() {
    }

    /** 返回当前缺失的第一项权限；null 表示全部就绪 */
    public static Missing firstMissing(Activity act) {
        if (!PixelKeeper.canOverlay(act)) {
            return OVERLAY;
        }
        if (!Updater.canInstall(act)) {
            return INSTALL;
        }
        /*
         * 通知权限单独提示（SystemNotify.request 会弹系统框），
         * 这里不重复；但 vivo 的锁屏显示 / 后台弹出 / 自启动
         * 没有可编程检测的手段，只在首次进入时引导一次。
         */
        return null;
    }

    /** 检查；全部就绪返回 true，否则弹不可取消引导并返回 false */
    public static boolean ensure(final Activity act) {
        final Missing m = firstMissing(act);
        if (m == null) {
            // 硬权限都齐了，再引导一次 vivo 后台设置（只引导一次）
            if (shouldShowVivoGuide(act)) {
                showVivoGuide(act);
                return false;
            }
            return true;
        }
        Dialog dlg = DialogKit.customBlocking(act, m.title(), m.desc(),
                "去开启", new Runnable() {
                    public void run() {
                        m.go(act);
                    }
                });
        if (dlg == null) {
            // 极端情况：对话框都弹不出来，直接自己跳
            m.go(act);
        }
        return false;
    }

    /** vivo 后台引导是否还没展示过（每个 App 只引导一次） */
    private static boolean shouldShowVivoGuide(Activity act) {
        return !act.getSharedPreferences("xiaowo", android.content.Context.MODE_PRIVATE)
                .getBoolean("vivo_guide_shown", false);
    }

    private static void showVivoGuide(final Activity act) {
        Dialog dlg = DialogKit.customBlocking(act, VIVO_AUTOSTART.title(), VIVO_AUTOSTART.desc(),
                "去设置", new Runnable() {
                    public void run() {
                        VIVO_AUTOSTART.go(act);
                        act.getSharedPreferences("xiaowo", android.content.Context.MODE_PRIVATE)
                                .edit().putBoolean("vivo_guide_shown", true).apply();
                    }
                });
        // 加了 onKeyListener 锁返回键；用户如果强行返回不走按钮，也算引导过
        if (dlg != null) {
            dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(android.content.DialogInterface d) {
                    act.getSharedPreferences("xiaowo", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("vivo_guide_shown", true).apply();
                }
            });
        }
    }
}