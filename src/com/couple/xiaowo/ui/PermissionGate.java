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
        return null;
    }

    /** 检查；全部就绪返回 true，否则弹不可取消引导并返回 false */
    public static boolean ensure(final Activity act) {
        final Missing m = firstMissing(act);
        if (m == null) {
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
}