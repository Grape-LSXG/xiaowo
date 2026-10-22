package com.couple.xiaowo.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 设计系统底座 —— Soft UI Evolution。
 *
 * 依据 UI/UX 设计智能库里的 soft-ui-evolution：
 *   · 改进对比度的粉紫色系（不是纯新拟物那种低对比）
 *   · 柔和多层阴影（比扁平柔、比新拟物清晰）
 *   · 8–18dp 圆角、200–300ms 缓动、焦点态可见
 *   · 正文对比度 ≥ 4.5:1，次要文字 ≥ 3:1
 *
 * 所有界面只从这里取色和取形状，不再逐屏硬编码。
 */
public final class Ui {

    // ---------------------------------------------------------------- 语义色令牌

    /** 背景：极浅的暖粉，带一点紫调，避免纯白刺眼 */
    public static final int BG = 0xFFFDF7FA;
    /** 卡片/浮层 */
    public static final int CARD = 0xFFFFFFFF;

    /** 主色（CTA、选中态） */
    public static final int PRIMARY = 0xFF7C3AED;
    public static final int PRIMARY_DEEP = 0xFF6D3FD6;
    public static final int PRIMARY_SOFT = 0xFFF3EEFE;

    /** 强调粉（爱心、价格、徽标） */
    public static final int ACCENT = 0xFFEC4899;
    public static final int ACCENT_SOFT = 0xFFFDF2F8;
    public static final int ACCENT_DEEP = 0xFFBE185D;

    /** 文字 */
    public static final int TEXT = 0xFF1E1B2E;
    public static final int TEXT_SUB = 0xFF6B6478;
    public static final int TEXT_MUTED = 0xFF958DA3;
    /** 兼容旧代码 */
    public static final int SUB = TEXT_SUB;

    public static final int STAR = 0xFFB45309;
    public static final int SUCCESS = 0xFF16A34A;
    public static final int SUCCESS_SOFT = 0xFFECFDF5;
    public static final int DANGER = 0xFFDC2626;
    public static final int DANGER_SOFT = 0xFFFEF2F2;
    public static final int WARN_SOFT = 0xFFFFFBEB;
    public static final int WARN = 0xFFB45309;

    /**
     * 分隔线：用带透明度的黑，深浅底都能用。
     *
     * 这组令牌不是 final：换主题时 Ui.applyTheme() 会把它们的值改写成
     * 当前主题对应的颜色，这样所有界面「一处切换、全屏生效」。
     * 代价是不能把它们写进 switch-case（Java 要求 case 是常量），实际也没有这么用过。
     */
    public static final int LINE = 0x14000000;
    /** 旧代码引用 */
    public static final int HEART = ACCENT;
    public static final int PURPLE = PRIMARY;
    public static final int PURPLE_DEEP = PRIMARY_DEEP;
    public static final int PURPLE_SOFT = PRIMARY_SOFT;
    public static final int PINK_SOFT = ACCENT_SOFT;

    // ------------------------------------------------------- 浅色基准值
    // Theme 靠这些常量在深浅之间做映射 / 还原，改之前先想清楚。

    public static final int BG_LIGHT = 0xFFFDF7FA;
    public static final int CARD_LIGHT = 0xFFFFFFFF;
    public static final int PRIMARY_LIGHT = 0xFF7C3AED;
    public static final int PRIMARY_DEEP_LIGHT = 0xFF6D3FD6;
    public static final int PRIMARY_SOFT_LIGHT = 0xFFF3EEFE;
    public static final int ACCENT_LIGHT = 0xFFEC4899;
    public static final int ACCENT_SOFT_LIGHT = 0xFFFDF2F8;
    public static final int ACCENT_DEEP_LIGHT = 0xFFBE185D;
    public static final int TEXT_LIGHT = 0xFF1E1B2E;
    public static final int TEXT_SUB_LIGHT = 0xFF6B6478;
    public static final int TEXT_MUTED_LIGHT = 0xFF958DA3;
    public static final int STAR_LIGHT = 0xFFB45309;
    public static final int SUCCESS_LIGHT = 0xFF16A34A;
    public static final int DANGER_LIGHT = 0xFFDC2626;
    public static final int LINE_LIGHT = 0x14000000;

    /**
     * 历史遗留入口。
     *
     * 现在**所有令牌都保持浅色原值**，颜色映射只由 Theme.adapt() 在做，
     * 发生在 Ui.round / Ui.gradient / Ui.tv / Ui.pill 这些工厂方法里。
     *
     * 为什么改成这样：之前是"令牌在深色下改成深色值 + 工厂再 adapt 一次"，
     * 等于对同一个颜色映射了两次。深色卡片色 0xFF1E1B28 被 adapt 当成深色文字，
     * 翻回了浅色 0xFFF2EEF8 —— 结果卡片变白、文字变深，全看不见了。
     * 单次映射原则是唯一正确的做法。
     */
    public static void applyTheme() {
        // 故意留空：令牌不再需要运行时改写
    }

    // ---------------------------------------------------------------- 圆角令牌

    public static final int R_CARD = 18;
    public static final int R_TILE = 14;
    public static final int R_CHIP = 999;

    private Ui() {
    }

    // ---------------------------------------------------------------- 单位

    public static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int dpi(Context c, float v) {
        return Math.round(dp(c, v));
    }

    // ---------------------------------------------------------------- 形状

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Theme.adapt(color));
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static GradientDrawable round(int color, float radiusPx, int strokePx, int strokeColor) {
        GradientDrawable d = round(color, radiusPx);
        if (strokePx > 0) {
            d.setStroke(strokePx, Theme.adapt(strokeColor));
        }
        return d;
    }

    public static GradientDrawable roundTop(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Theme.adapt(color));
        d.setCornerRadii(new float[]{radiusPx, radiusPx, radiusPx, radiusPx, 0, 0, 0, 0});
        return d;
    }

    public static GradientDrawable roundBottom(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(Theme.adapt(color));
        d.setCornerRadii(new float[]{0, 0, 0, 0, radiusPx, radiusPx, radiusPx, radiusPx});
        return d;
    }

    public static GradientDrawable gradient(int[] colors) {
        int[] mapped = new int[colors.length];
        for (int i = 0; i < colors.length; i++) {
            mapped[i] = Theme.adapt(colors[i]);
        }
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, mapped);
        d.setShape(GradientDrawable.RECTANGLE);
        return d;
    }

    public static GradientDrawable gradient(int colorA, int colorB, float radiusPx) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Theme.adapt(colorA), Theme.adapt(colorB)});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        return d;
    }

    // ---------------------------------------------------------------- 柔和阴影

    public static void cardShadow(View v, Context c) {
        v.setElevation(dp(c, 2f));
    }

    public static void floatShadow(View v, Context c) {
        v.setElevation(dp(c, 5f));
    }

    // ---------------------------------------------------------------- 文字

    public static TextView tv(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Theme.adaptText(color));
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    // ---------------------------------------------------------------- 容器

    public static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(Context c, float wDp, float hDp) {
        return new LinearLayout.LayoutParams(dpi(c, wDp), dpi(c, hDp));
    }

    public static View spacer(Context c, float hDp) {
        View v = new View(c);
        v.setLayoutParams(lp(c, 0, hDp));
        return v;
    }

    public static View hspacer(Context c, float wDp) {
        View v = new View(c);
        v.setLayoutParams(lp(c, wDp, 0));
        return v;
    }

    /** 带上方间距的分隔线（高度写死 1px，绝不能被 WRAP_CONTENT 拉高） */
    public static View dividerTop(Context c, float topDp) {
        View v = new View(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dpi(c, 1f)));
        p.topMargin = dpi(c, topDp);
        v.setLayoutParams(p);
        v.setBackgroundColor(LINE);
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dpi(c, 1f)));
        v.setLayoutParams(p);
        v.setBackgroundColor(LINE);
        return v;
    }

    // ---------------------------------------------------------------- 组件

    public static TextView chip(Context c, String text, int bg, int fg, float sp) {
        TextView t = tv(c, text, sp, fg, true);
        t.setPadding(dpi(c, 8), dpi(c, 3), dpi(c, 8), dpi(c, 3));
        t.setBackground(round(bg, dp(c, 8)));
        return t;
    }

    public static TextView pill(Context c, String text, int bg, int fg, float sp,
                                float padH, float padV, boolean bold) {
        TextView t = tv(c, text, sp, fg, bold);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dpi(c, padH), dpi(c, padV), dpi(c, padH), dpi(c, padV));
        t.setBackground(round(bg, dp(c, R_CHIP)));
        t.setClickable(true);
        t.setMinimumHeight(dpi(c, 44));
        a11y(t, null);
        return t;
    }

    /** 主按钮：渐变紫 + 柔光阴影 */
    public static TextView primaryButton(Context c, String text) {
        // 白字显式走 adaptText：彩色按钮上的白字在深色下必须保持白
        TextView t = tv(c, text, 15, 0xFFFFFFFF, true);
        t.setTextColor(Theme.adaptText(0xFFFFFFFF));
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dpi(c, 24), dpi(c, 14), dpi(c, 24), dpi(c, 14));
        t.setBackground(gradient(Theme.adaptButtonGradient(PRIMARY, 0xFF9333EA)[0],
                Theme.adaptButtonGradient(PRIMARY, 0xFF9333EA)[1], dp(c, R_CHIP)));
        floatShadow(t, c);
        t.setClickable(true);
        return t;
    }

    /** 次要按钮：浅紫渐变 */
    public static TextView secondaryButton(Context c, String text) {
        TextView t = tv(c, text, 13, PRIMARY_DEEP, true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dpi(c, 20), dpi(c, 11), dpi(c, 20), dpi(c, 11));
        t.setBackground(gradient(0xFFF6F1FF, 0xFFEDE4FE, dp(c, R_CHIP)));
        t.setClickable(true);
        t.setMinimumHeight(dpi(c, 48));
        a11y(t, null);
        return t;
    }

    // ---------------------------------------------------------------- 无障碍 & 触摸

    /**
     * 给可点元素补上无障碍标签和最小触摸目标。
     * 规范要求：图标按钮必须有 accessible label；触摸目标 ≥48dp。
     */
    public static void a11y(View v, String label) {
        if (label != null && label.length() > 0) {
            v.setContentDescription(label);
        }
        if (v instanceof TextView && ((TextView) v).getText() != null
                && ((TextView) v).getText().length() > 0 && label == null) {
            v.setContentDescription(((TextView) v).getText());
        }
    }

    /** 把高度补到 48dp（不改变视觉，只扩热区） */
    public static void expandTouch(final View v, final int minDp) {
        final int min = dpi(v.getContext(), minDp);
        v.post(new Runnable() {
            public void run() {
                int h = v.getHeight();
                if (h > 0 && h < min) {
                    ViewGroup.LayoutParams p = v.getLayoutParams();
                    if (p != null) {
                        p.height = min;
                        v.setLayoutParams(p);
                    }
                }
            }
        });
    }

    // ---------------------------------------------------------------- 骨架屏

    /** 卡片骨架屏：网络请求期间先占位，避免"白屏没反应" */
    public static LinearLayout skeleton(Context c, int rows) {
        LinearLayout box = vertical(c);
        for (int i = 0; i < rows; i++) {
            LinearLayout card = horizontal(c);
            card.setBackground(round(0xFFF6F3FB, dp(c, R_CARD)));
            card.setPadding(dpi(c, 12), dpi(c, 12), dpi(c, 12), dpi(c, 12));

            View thumb = new View(c);
            thumb.setBackground(round(0xFFEDE7FA, dp(c, R_TILE)));
            card.addView(thumb, new LinearLayout.LayoutParams(dpi(c, 54), dpi(c, 54)));

            LinearLayout lines = vertical(c);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lLp.weight = 1f;
            lLp.leftMargin = dpi(c, 10);
            lines.setLayoutParams(lLp);
            for (int k = 0; k < 3; k++) {
                View line = new View(c);
                line.setBackground(round(0xFFF0EBFA, dp(c, 6)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        k == 0 ? dpi(c, 90) : (k == 1 ? dpi(c, 130) : dpi(c, 70)), dpi(c, 10));
                lp.topMargin = dpi(c, 6);
                lines.addView(line, lp);
            }
            card.addView(lines);

            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.bottomMargin = dpi(c, 10);
            box.addView(card, cLp);
        }
        return box;
    }

    // ---------------------------------------------------------------- 动效

    /** 80–150ms 的按压缩放反馈（Soft UI Evolution 建议 0.97） */
    public static void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.97f).scaleY(0.97f)
                                .setDuration(90).setInterpolator(new DecelerateInterpolator()).start();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f)
                                .setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
                        break;
                }
                return false;
            }
        });
    }

    /** 页面切换的淡入 + 轻微上移（260ms） */
    public static void fadeUp(final View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 10f));
        v.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /** 数字滚动，用于「合计 ❤N」 */
    public static void animateNumber(final TextView tv, final int from, final int to,
                                     final String prefix) {
        if (from == to) {
            tv.setText(prefix + to);
            return;
        }
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(260);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator anim) {
                tv.setText(prefix + anim.getAnimatedValue());
            }
        });
        a.start();
    }

    /** 真实版本号，避免页面里手写版本字符串写漏 */
    public static String appVersion(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0);
            return "v" + pi.versionName;
        } catch (Exception e) {
            return "v?";
        }
    }

    public static int blend(int c1, int c2, float t) {
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }
}
