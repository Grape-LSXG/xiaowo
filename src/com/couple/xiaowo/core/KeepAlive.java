package com.couple.xiaowo.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * 保活前台服务。
 *
 * 为什么需要它：Android 会把后台 App 的进程随时回收，一旦进程没了，
 * 接单端就收不到单、点单端也收不到「震一下」。而**前台服务 + 常驻通知**
 * 是 Android 官方唯一允许的「长期在后台活着」的方式，不会被随手杀掉。
 *
 * 它做三件事：
 *   1. 挂一条不可划掉的常驻通知（这是保活的核心，系统把它当"正在运行"对待）
 *   2. 持一把 PARTIAL_WAKE_LOCK —— 让 CPU 别睡死
 *   3. 通知点一下就回到 App
 *
 * 第 2 条是实测补上的：只有前台通知时，App 退到后台几分钟后，
 * 接单端的监听端口就不再应答（本机 loopback 也超时），
 * 表现就是点单端报「没连上张老师」。加了唤醒锁之后同样的场景恢复 200。
 * PARTIAL_WAKE_LOCK 只保 CPU、不亮屏，耗电很小，且只在保活打开时持有。
 *
 * 注意：Android 13+ 需要 POST_NOTIFICATIONS 权限，没有的话通知不显示、
 * 保活效果也打折，所以 App 启动时已经请求过。
 */
public final class KeepAlive extends Service {

    public static final String CHANNEL_ID = "xiaowo.keepalive";
    public static final String CHANNEL_NAME = "小窝 · 后台保活";
    public static final int NOTIF_ID = 2001;

    public static final String EXTRA_ROLE = "role";
    public static final String EXTRA_HINT = "hint";

    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) {
                return;
            }
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaowo:keepalive");
            wakeLock.setReferenceCounted(false);
            // 不设超时：由 stopService / onDestroy 释放，跟着开关走
            wakeLock.acquire();
        } catch (Exception ignore) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignore) {
        }
        wakeLock = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String role = intent == null ? "" : intent.getStringExtra(EXTRA_ROLE);
        String hint = intent == null ? "" : intent.getStringExtra(EXTRA_HINT);
        if (role == null) {
            role = "";
        }
        if (hint == null || hint.length() == 0) {
            hint = Protocol.ROLE_RECV.equals(role)
                    ? "正在等猫猫点单" : "正在等着张老师";
        }
        startForeground(NOTIF_ID, buildNotification(this, role, hint));
        acquireWakeLock();
        // START_STICKY：被系统杀掉后尽量重启
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    private static Notification buildNotification(Context c, String role, String hint) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager m = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (m != null && m.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW);   // LOW = 不吵，但不可滑掉
                ch.setDescription("保持小窝在后台运行，随时接收点单和消息");
                ch.setShowBadge(false);
                ch.enableVibration(false);
                ch.setSound(null, null);
                m.createNotificationChannel(ch);
            }
        }

        Intent open = new Intent();
        open.setClassName(c.getPackageName(), "com.couple.xiaowo.ui.MainActivity");
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL_ID)
                : new Notification.Builder(c);

        String title = Protocol.ROLE_RECV.equals(role) ? "小窝 · 张老师在接单" : "小窝 · 猫猫在线";
        nb.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(hint)
                .setOngoing(true)              // 不可滑掉
                .setAutoCancel(false)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true);

        if (Build.VERSION.SDK_INT < 26) {
            nb.setPriority(Notification.PRIORITY_LOW);
        }
        return nb.build();
    }

    /** 更新常驻通知上的文字（比如显示穿透地址、已收单数） */
    public static void update(Context c, String role, String hint) {
        try {
            NotificationManager m = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            if (m != null) {
                m.notify(NOTIF_ID, buildNotification(c, role, hint));
            }
        } catch (Exception ignore) {
        }
    }

    public static void start(Context c, String role, String hint) {
        try {
            Intent i = new Intent(c, KeepAlive.class);
            i.putExtra(EXTRA_ROLE, role);
            i.putExtra(EXTRA_HINT, hint);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception ignore) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, KeepAlive.class));
        } catch (Exception ignore) {
        }
    }
}
