package com.couple.xiaowo.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 漂亮的顶部提示条 —— 取代系统那个黑色小长条。
 *
 * 设计依据：
 *   · Soft UI Evolution：柔和多层阴影、圆角、260ms 缓动
 *   · UX 规范 Toast Notifications：3–5 秒自动消失，非阻断
 *   · 触控/可达性：不拦截点击（clickable=false），只做展示
 *
 * 四种类型各带自己的图标和配色，避免"什么都长一样"。
 */
public final class Toast2 {

    public static final int INFO = 0;
    public static final int SUCCESS = 1;
    public static final int WARN = 2;
    public static final int LOVE = 3;

    public static final int LOADING = 4;

    /** 真正挂在 root 上的容器。必须记它，否则撤不掉（之前误记 TextView 导致提示条永不消失） */
    private static View currentHolder;
    private static Runnable pendingDismiss;

    private Toast2() {
    }

    public static void info(Activity act, String msg) {
        show(act, INFO, msg);
    }

    public static void ok(Activity act, String msg) {
        show(act, SUCCESS, msg);
    }

    public static void warn(Activity act, String msg) {
        show(act, WARN, msg);
    }

    public static void love(Activity act, String msg) {
        show(act, LOVE, msg);
    }

    public static void show(Activity act, int type, String msg) {
        if (act == null || msg == null || msg.length() == 0) {
            return;
        }
        ViewGroup root = (ViewGroup) act.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        // 撤掉上一条：必须从 root 上移除 holder 本身
        if (pendingDismiss != null) {
            root.removeCallbacks(pendingDismiss);
            pendingDismiss = null;
        }
        if (currentHolder != null && currentHolder.getParent() instanceof ViewGroup) {
            ((ViewGroup) currentHolder.getParent()).removeView(currentHolder);
        }
        currentHolder = null;

        int accent;
        String icon;
        int bgTop;
        int bgBottom;
        switch (type) {
            case SUCCESS:
                accent = Ui.SUCCESS;
                icon = "✓";
                bgTop = 0xFFF2FDF7;
                bgBottom = 0xFFE9FAF1;
                break;
            case WARN:
                accent = Ui.WARN;
                icon = "!";
                bgTop = 0xFFFFFBF2;
                bgBottom = 0xFFFFF6E4;
                break;
            case LOVE:
                accent = Ui.ACCENT;
                icon = "❤";
                bgTop = 0xFFFFF4F8;
                bgBottom = 0xFFFFE9F2;
                break;
            case LOADING:
                accent = Ui.PRIMARY_DEEP;
                icon = "◌";
                bgTop = 0xFFF7F3FF;
                bgBottom = 0xFFF0E9FE;
                break;
            default:
                accent = Ui.PRIMARY;
                icon = "i";
                bgTop = 0xFFF7F3FF;
                bgBottom = 0xFFF0E9FE;
        }

        FrameLayout holder = new FrameLayout(act);
        holder.setClickable(false);
        holder.setFocusable(false);

        LinearLayout card = Ui.horizontal(act);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(act, 10), dp(act, 10), dp(act, 16), dp(act, 10));

        GradientDrawable bg = Ui.gradient(bgTop, bgBottom, dp(act, 16));
        bg.setStroke(Math.max(1, dp(act, 1)), withAlpha(accent, 0x40));
        card.setBackground(bg);
        card.setElevation(dp(act, 8));
        card.setClickable(false);

        // 圆形徽标
        TextView badge = Ui.tv(act, icon, 13, 0xFFFFFFFF, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(accent, dp(act, 999)));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(act, 26), dp(act, 26));
        bLp.rightMargin = dp(act, 10);
        card.addView(badge, bLp);

        TextView text = Ui.tv(act, msg, 13, Ui.TEXT, false);
        text.setMaxLines(4);
        card.addView(text);

        // 顶部细进度条，提示"这条会自己消失"
        View bar = new View(act);
        bar.setBackgroundColor(withAlpha(accent, 0xB0));

        LinearLayout column = Ui.vertical(act);
        column.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(act, 2))));

        GradientDrawable wrapBg = Ui.round(0xFFFFFFFF, dp(act, 16));
        wrapBg.setAlpha(0);
        column.setBackground(wrapBg);
        column.setElevation(dp(act, 8));

        FrameLayout.LayoutParams colLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.setMargins(dp(act, 16), 0, dp(act, 16), 0);
        colLp.gravity = Gravity.TOP;
        holder.addView(column, colLp);

        FrameLayout.LayoutParams holderLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        holderLp.gravity = Gravity.TOP;
        holderLp.topMargin = dp(act, 8) + statusBarHeight(act);
        root.addView(holder, holderLp);
        currentHolder = holder;

        // 进场：从上方滑入 + 淡入（260ms）
        column.setAlpha(0f);
        column.setTranslationY(-dp(act, 22));
        column.animate().alpha(1f).translationY(0f).setDuration(280)
                .setInterpolator(new DecelerateInterpolator(1.8f)).start();

        // 进度条在这段时间里缩到 0，暗示 3s 后消失
        bar.animate().scaleX(0f).setDuration(3000)
                .setInterpolator(new android.view.animation.LinearInterpolator())
                .setStartDelay(300).start();

        final ViewGroup froot = root;
        final FrameLayout fholder = holder;
        final View fcolumn = column;
        final int offY = dp(act, 16);
        pendingDismiss = new Runnable() {
            public void run() {
                fcolumn.animate().alpha(0f).translationY(-offY)
                        .setDuration(220).setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(new Runnable() {
                            public void run() {
                                froot.removeView(fholder);
                                if (currentHolder == fholder) {
                                    currentHolder = null;
                                }
                            }
                        }).start();
            }
        };
        // 所有类型都必须到点消失；loading 给久一点但它同样会走
        root.postDelayed(pendingDismiss, type == LOADING ? 6000 : 3000);
    }

    /** 状态栏高度：Activity 用了 setDecorFitsSystemWindows(false)，
     *  内容区会顶到状态栏下面，提示条必须自己让出这段高度，否则被状态栏盖住看不见。 */
    private static int statusBarHeight(Activity act) {
        try {
            int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                return act.getResources().getDimensionPixelSize(id);
            }
        } catch (Exception ignore) {
        }
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, 28,
                act.getResources().getDisplayMetrics()));
    }

    private static int dp(Activity act, float v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
