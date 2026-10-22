package com.couple.xiaowo.ui;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.core.Protocol;
import com.couple.xiaowo.core.Remind;

/**
 * 提醒中心：把"对方发来东西"这件事，按用户选的提醒程度送到用户眼前。
 *
 *   噪音级 OFF     → 什么都不做，只在 App 内更新
 *   通知级 NOTIFY  → 系统通知栏（Android 8+ 的 Channel）
 *   悬浮窗 OVERLAY → 系统级悬浮窗 + 爱心粒子，直接飘在屏幕上（需 SYSTEM_ALERT_WINDOW）
 *
 * 悬浮窗是"非触摸"的（FLAG_NOT_TOUCHABLE），只做视觉提示，
 * 点击会穿透到下面的 App，不会挡住用户操作。
 */
public final class AlertCenter {

    private static final String CHANNEL_ID = "xiaowo.love";
    private static final String CHANNEL_NAME = "小窝 · 爱心与消息";
    private static final int NOTIF_ID = 1001;

    /** 三级降级：先试悬浮窗，不行就发通知，还不行就只在 App 内用提示条 */
    public static void push(final Activity act, int level, final String title, final String body,
                            final int burstCount) {
        if (level <= Remind.OFF) {
            return;
        }
        if (level >= Remind.OVERLAY && canOverlay(act)) {
            showOverlay(act, title, body, burstCount);
            return;
        }
        if (level >= Remind.NOTIFY) {
            if (SystemNotify.hasPermission(act)) {
                SystemNotify.post(act, title, body, burstCount > 0);
                return;
            }
        }
        // 兜底：App 内的提示条
        Toast2.love(act, title + "：" + body);
    }

    // ================================================================== 悬浮窗

    public static boolean canOverlay(Context c) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return Settings.canDrawOverlays(c);
    }

    /** 引导用户去开悬浮窗权限 */
    public static void requestOverlay(final Activity act) {
        if (canOverlay(act)) {
            Toast2.ok(act, "悬浮窗权限已经有了");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + act.getPackageName()));
            act.startActivity(i);
            Toast2.info(act, "在列表里找到「小窝」，打开「显示在其他应用上层」");
        } catch (Exception e) {
            Toast2.warn(act, "打不开权限页，请到系统设置里手动授权");
        }
    }

    private static FrameLayout overlayHolder;
    private static int overlayToken = 0;

    private static void showOverlay(final Activity act, String title, String body, int burstCount) {
        try {
            final android.view.WindowManager wm =
                    (android.view.WindowManager) act.getSystemService(Context.WINDOW_SERVICE);

            // 新的一条来了：先把上一条撤干净，否则旧的气泡会永远留在屏幕上
            if (overlayHolder != null) {
                overlayToken++;
                removeOverlay(wm, overlayHolder);
            }

            final FrameLayout root = new FrameLayout(act);
            root.setClipChildren(false);

            // 爱心粒子层
            final HeartBurstView hearts = new HeartBurstView(act);
            root.addView(hearts, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 气泡卡片
            LinearLayout card = Ui.horizontal(act);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(act, 14), dp(act, 12), dp(act, 18), dp(act, 12));
            GradientDrawable bg = Ui.gradient(0xFFFFF4F8, 0xFFEDE6FE, dp(act, 22));
            bg.setStroke(Math.max(1, dp(act, 1)), 0x33EC4899);
            card.setBackground(bg);
            card.setElevation(dp(act, 12));

            ImageView av = new ImageView(act);
            Bitmap bm = CatImage.bitmap(act, 1);
            if (bm != null) {
                av.setImageBitmap(bm);
            }
            av.setScaleType(ImageView.ScaleType.CENTER_CROP);
            final int r = dp(act, 22);
            av.setClipToOutline(true);
            av.setOutlineProvider(new android.view.ViewOutlineProvider() {
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
            card.addView(av, new LinearLayout.LayoutParams(dp(act, 44), dp(act, 44)));

            LinearLayout texts = Ui.vertical(act);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.weight = 1f;
            tLp.leftMargin = dp(act, 10);
            texts.setLayoutParams(tLp);
            texts.addView(Ui.tv(act, title, 13.5f, Ui.TEXT, true));
            TextView b = Ui.tv(act, body, 12.5f, Ui.TEXT_SUB, false);
            b.setMaxLines(2);
            texts.addView(b, top(act, 2));
            card.addView(texts);

            // 用 MATCH_PARENT + 左右 margin，保证气泡总是占满合理宽度，不会缩成一小坨
            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.gravity = Gravity.TOP;
            cardLp.topMargin = dp(act, 76);
            cardLp.leftMargin = dp(act, 18);
            cardLp.rightMargin = dp(act, 18);
            card.setLayoutParams(cardLp);
            root.addView(card);

            try {
                wm.addView(root, overlayParams());
            } catch (Exception e) {
                Toast2.love(act, title + "：" + body);
                return;
            }

            overlayHolder = root;
            final int myToken = ++overlayToken;

            hearts.burst(burstCount <= 0 ? 26 : burstCount);

            card.setAlpha(0f);
            card.setTranslationY(-dp(act, 30));
            card.animate().alpha(1f).translationY(0f).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();

            // 4 秒后自动撤掉（用主线程 Handler，不依赖 View 是否附着）
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                public void run() {
                    if (myToken != overlayToken) {
                        // 已被更新的消息接管，那边会负责撤掉这一条
                        removeOverlay(wm, root);
                        return;
                    }
                    card.animate().alpha(0f).translationY(-dp(act, 24)).setDuration(260)
                            .withEndAction(new Runnable() {
                                public void run() {
                                    removeOverlay(wm, root);
                                }
                            }).start();
                }
            }, 4000);

        } catch (Exception e) {
            Toast2.love(act, title + "：" + body);
        }
    }

    private static android.view.WindowManager.LayoutParams overlayParams() {
        int flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        android.view.WindowManager.LayoutParams p = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : android.view.WindowManager.LayoutParams.TYPE_PHONE,
                flags,
                android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 0;
        p.y = 0;
        return p;
    }

    private static void removeOverlay(android.view.WindowManager wm, View v) {
        if (v == null) {
            return;
        }
        try {
            if (v.getParent() != null) {
                wm.removeViewImmediate(v);
            }
        } catch (Exception ignore) {
            try {
                wm.removeView(v);
            } catch (Exception ignore2) {
            }
        }
        if (overlayHolder == v) {
            overlayHolder = null;
        }
    }

    /** 页面销毁时兜底清掉悬浮窗，避免 Activity 泄漏 */
    public static void dismissOverlay() {
        if (overlayHolder != null) {
            try {
                android.view.WindowManager wm = (android.view.WindowManager)
                        overlayHolder.getContext().getSystemService(Context.WINDOW_SERVICE);
                removeOverlay(wm, overlayHolder);
            } catch (Exception ignore) {
            }
            overlayHolder = null;
        }
        overlayToken++;
    }

    // ================================================================== 系统通知

    public static final class SystemNotify {

        public static boolean hasPermission(Context c) {
            if (Build.VERSION.SDK_INT < 33) {
                return true;
            }
            try {
                return c.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            } catch (Exception e) {
                return true;
            }
        }

        public static void request(Activity act) {
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    act.requestPermissions(
                            new String[]{"android.permission.POST_NOTIFICATIONS"}, 42);
                } catch (Exception ignore) {
                }
            }
        }

        private static NotificationManager nm(Context c) {
            return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        private static void ensureChannel(Context c) {
            if (Build.VERSION.SDK_INT < 26) {
                return;
            }
            NotificationManager m = nm(c);
            if (m == null) {
                return;
            }
            if (m.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("猫猫和张老师之间的点单、公告、聊天提醒");
                ch.enableVibration(true);
                ch.setShowBadge(true);
                m.createNotificationChannel(ch);
            }
        }

        public static void post(Activity act, String title, String body, boolean love) {
            try {
                ensureChannel(act);
                NotificationManager m = nm(act);
                if (m == null) {
                    return;
                }

                Intent open = new Intent(act, MainActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                PendingIntent pi = PendingIntent.getActivity(act, 0, open,
                        Build.VERSION.SDK_INT >= 23
                                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                                : PendingIntent.FLAG_UPDATE_CURRENT);

                Notification.Builder nb;
                if (Build.VERSION.SDK_INT >= 26) {
                    nb = new Notification.Builder(act, CHANNEL_ID);
                } else {
                    nb = new Notification.Builder(act);
                }

                nb.setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new Notification.BigTextStyle().bigText(body))
                        .setAutoCancel(true)
                        .setContentIntent(pi);

                if (Build.VERSION.SDK_INT >= 23) {
                    Bitmap big = heartBitmap(192, love);
                    if (big != null) {
                        nb.setLargeIcon(big);
                    }
                }
                if (Build.VERSION.SDK_INT < 26) {
                    nb.setPriority(love ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
                }
                if (love) {
                    nb.setVibrate(new long[]{0, 40, 60, 40});
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    nb.setColorized(false);
                }

                m.notify(NOTIF_ID, nb.build());
            } catch (Exception ignore) {
            }
        }

        /** 画一个爱心当大图标 */
        public static Bitmap heartBitmap(int size, boolean love) {
            try {
                Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bm);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                if (love) {
                    p.setShader(new android.graphics.LinearGradient(0, 0, size, size,
                            0xFFEC4899, 0xFF8B5CF6, android.graphics.Shader.TileMode.CLAMP));
                } else {
                    p.setColor(0xFF8B5CF6);
                }
                Path path = new Path();
                float s = size;
                path.moveTo(s * 0.5f, s * 0.80f);
                path.cubicTo(s * 0.10f, s * 0.52f, s * 0.02f, s * 0.22f, s * 0.34f, s * 0.16f);
                path.cubicTo(s * 0.44f, s * 0.14f, s * 0.50f, s * 0.22f, s * 0.5f, s * 0.28f);
                path.cubicTo(s * 0.50f, s * 0.22f, s * 0.56f, s * 0.14f, s * 0.66f, s * 0.16f);
                path.cubicTo(s * 0.98f, s * 0.22f, s * 0.90f, s * 0.52f, s * 0.5f, s * 0.80f);
                path.close();
                cv.drawPath(path, p);
                return bm;
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static int dp(Activity act, float v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }

    private static LinearLayout.LayoutParams top(Activity act, int dpValue) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(act, dpValue);
        return p;
    }

    private AlertCenter() {
    }
}
