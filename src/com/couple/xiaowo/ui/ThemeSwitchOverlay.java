package com.couple.xiaowo.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 切换深浅色时的全屏过渡遮罩。
 *
 * ── 为什么要这个 ──
 *
 * 环境光模式下，明暗切换是靠 recreate() 重建整个界面完成的，
 * 那一刻屏幕会"啪"地换一套颜色，很生硬；如果光线在阈值附近抖动，
 * 还会连续重建几次，看起来就是**屏幕一直在闪**。
 *
 * 现在插一个约 300ms 的遮罩：
 *   1. 先盖上一张近乎不透明的底，把"换色那一下"藏起来
 *   2. 中间显示一个太阳/月亮图标，淡入淡出
 *   3. 到时间后撤掉，露出来的已经是新主题
 *
 * 用户看到的是"一个日/月图标轻轻过了一下"，而不是界面闪。
 *
 * ── 为什么用 WindowManager 加窗口而不是加在本 Activity 的 View 树里 ──
 *
 * 本方案里切换要靠 recreate()，Activity 会被销毁重建 ——
 * 遮罩如果挂在那棵即将被销毁的 View 树上，会跟着一起消失，
 * 露出来的正好是重建的瞬间（还是会闪）。
 * 挂成独立的系统窗口就不受 Activity 生命周期影响，能完整覆盖整个过渡。
 */
public final class ThemeSwitchOverlay {

    /** 遮罩总时长：足够盖住 recreate，又不至于让用户等 */
    private static final long TOTAL_MS = 300L;
    /** 图标淡入用掉的时长 */
    private static final long FADE_IN_MS = 120L;

    private ThemeSwitchOverlay() {
    }

    /**
     * 播一次切换动画，并在合适的时机执行真正的主体重建。
     *
     * @param act  当前 Activity
     * @param toDark 切换后的目标主题（决定显示太阳还是月亮）
     * @param rebuild 真正做主题切换+重建的动作；会在遮罩完全盖住后执行
     */
    public static void play(final Activity act, boolean toDark, final Runnable rebuild) {
        if (act == null || act.isFinishing()) {
            if (rebuild != null) {
                rebuild.run();
            }
            return;
        }
        final WindowManager wm = (WindowManager) act.getSystemService(Activity.WINDOW_SERVICE);
        if (wm == null) {
            if (rebuild != null) {
                rebuild.run();
            }
            return;
        }

        // ---- 遮罩层：一个全屏 FrameLayout ----
        final FrameLayout root = new FrameLayout(act);
        // 底色随目标主题走：切深色时盖深底，切浅色时盖浅底，切换瞬间不跳色
        root.setBackgroundColor(toDark ? 0xFF14121B : 0xFFFDF7FA);

        TextView icon = new TextView(act);
        icon.setText(toDark ? "🌙" : "☀️");
        icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 54);
        icon.setGravity(Gravity.CENTER);
        icon.setAlpha(0f);
        FrameLayout.LayoutParams iLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        iLp.gravity = Gravity.CENTER;
        root.addView(icon, iLp);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // 不吃任何触摸：过渡期间不该挡住用户操作（虽然只有 300ms）
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.alpha = 0f;

        try {
            wm.addView(root, lp);
        } catch (Throwable t) {
            // 没有悬浮窗权限 / ROM 拦截：直接重建，退化成"没有动画"也比崩了好
            if (rebuild != null) {
                rebuild.run();
            }
            return;
        }

        // ---- 阶段一：遮罩淡入（约 120ms）----
        animateAlpha(root, lp, wm, 0f, 0.92f, FADE_IN_MS, true, new Runnable() {
            public void run() {
                // 这时屏幕已经被盖住了，可以安全地重建主题
                if (rebuild != null) {
                    try {
                        rebuild.run();
                    } catch (Throwable ignore) {
                    }
                }
                // ---- 阶段二：图标亮一下再一起淡出 ----
                root.postDelayed(new Runnable() {
                    public void run() {
                        animateAlpha(root, lp, wm, 0.92f, 0f, TOTAL_MS - FADE_IN_MS,
                                false, new Runnable() {
                                    public void run() {
                                        removeQuietly(wm, root);
                                    }
                                });
                    }
                }, 60L);
            }
        });

        // 图标在遮罩淡入后淡入
        root.postDelayed(new Runnable() {
            public void run() {
                animateAlpha(icon, null, null, 0f, 1f, 140L, false, null);
            }
        }, 40L);
    }

    /** 跑一个 alpha 动画（不依赖动画框架，用手写插值，保证在所有 ROM 上都稳） */
    private static void animateAlpha(final View v, final WindowManager.LayoutParams lp,
                                     final WindowManager wm, float from, float to,
                                     long duration, final boolean updateWindow,
                                     final Runnable onEnd) {
        final long start = System.currentTimeMillis();
        final Runnable[] step = new Runnable[1];
        step[0] = new Runnable() {
            public void run() {
                long elapsed = System.currentTimeMillis() - start;
                float t = Math.min(1f, duration <= 0 ? 1f : (float) elapsed / duration);
                // ease-out：开始快、结束慢，观感更轻
                float eased = 1f - (1f - t) * (1f - t);
                float a = from + (to - from) * eased;
                try {
                    if (updateWindow && lp != null && wm != null) {
                        lp.alpha = a;
                        wm.updateViewLayout(v, lp);
                    } else {
                        v.setAlpha(a);
                    }
                } catch (Throwable ignore) {
                }
                if (t < 1f) {
                    v.postDelayed(step[0], 16L);
                } else if (onEnd != null) {
                    onEnd.run();
                }
            }
        };
        step[0].run();
    }

    private static void removeQuietly(WindowManager wm, View v) {
        try {
            if (wm != null && v != null && v.getWindowToken() != null) {
                wm.removeView(v);
            }
        } catch (Throwable ignore) {
        }
    }
}
