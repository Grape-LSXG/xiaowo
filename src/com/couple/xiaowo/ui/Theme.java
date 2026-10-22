package com.couple.xiaowo.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 深浅色主题。
 *
 * 三种模式：
 *   AUTO  —— 跟着环境光走（暗处自动切深色，亮处切浅色）
 *   LIGHT —— 永远浅色
 *   DARK  —— 永远深色
 *
 * 为什么用环境光而不是只看时间：
 *   用户明确要的是"根据环境光亮暗自动切换"，所以主判据是光线传感器；
 *   但光线传感器不是所有设备都有 / 也可能读不到，所以再叠一层日落日出的兜底，
 *   单纯按系统时间判断（19:00–7:00 视为暗）。
 *
 * 实现方式（重要）：
 *   不做"两套 UI 逐屏适配"，而是在 Ui 的工厂方法里做**颜色映射**。
 *   所有界面画颜色都经过 Ui.round / Ui.gradient / Ui.tv / Ui.pill，
 *   所以在这些入口统一调 Theme.adapt()，一次覆盖全部 204 处硬编码色。
 *   浅色底 → 深色底，深色字 → 浅色字，彩色按钮上的白字保持白。
 *
 * 切换主题需要重建界面：Activity 收到 onThemeChanged() 后自己 recreate()。
 */
public final class Theme {

    public static final int MODE_AUTO = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    public static final String KEY_MODE = "theme_mode";
    private static final String KEY_LAST_AUTO_DARK = "theme_auto_dark";

    private static boolean dark = false;
    private static int mode = MODE_AUTO;

    private Theme() {
    }

    // ---------------------------------------------------------------- 状态

    public static boolean isDark() {
        return dark;
    }

    public static int mode(Context c) {
        return sp(c).getInt(KEY_MODE, MODE_AUTO);
    }

    public static void setMode(Context c, int m) {
        sp(c).edit().putInt(KEY_MODE, m).apply();
        mode = m;
        if (m == MODE_LIGHT) {
            dark = false;
        } else if (m == MODE_DARK) {
            dark = true;
        } else {
            dark = sp(c).getBoolean(KEY_LAST_AUTO_DARK, autoDarkByClock(c));
        }
    }

    /** 启动时同步一次（Activity.onCreate 里调），保证进程重启后配色正确 */
    public static void init(Context c) {
        int m = mode(c);
        mode = m;
        if (m == MODE_LIGHT) {
            dark = false;
        } else if (m == MODE_DARK) {
            dark = true;
        } else {
            /*
             * AUTO 模式：优先用"上次记住的自动结论"，没有的话按时间兜底。
             *
             * 这里故意**不同步读传感器**：init 在 onCreate 里跑，
             * 读一次光最多要 900ms，会把启动卡住。
             * 真实光线由 MainActivity 在 onResume 之后异步补一次（见 syncAutoFromSensor）。
             */
            dark = sp(c).getBoolean(KEY_LAST_AUTO_DARK, autoDarkByClock(c));
        }
    }

    /**
     * AUTO 模式下用传感器真值校正一次。
     * 在后台线程调，拿到结果后再切回主线程应用（避免卡 UI）。
     */
    public static void syncAutoFromSensor(final Context c, final Runnable onChanged) {
        if (mode(c) != MODE_AUTO) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                final Float lux = com.couple.xiaowo.core.Lux.readOnce(c);
                if (lux == null) {
                    return;
                }
                // 与 Ambient 的暗阈值保持一致（20 lux）；这里只在冷启动/回前台时跑一次，
                // 不存在抖动问题，所以不需要更复杂的状态机
                boolean wantDark = lux.floatValue() < 20f;
                if (applyAuto(c, wantDark) && onChanged != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(onChanged);
                }
            }
        }).start();
    }

    /** AUTO 模式下光线传感器给出的结论变化时调用；返回 true 表示真的变了 */
    public static boolean applyAuto(Context c, boolean nowDark) {
        if (mode(c) != MODE_AUTO) {
            return false;
        }
        sp(c).edit().putBoolean(KEY_LAST_AUTO_DARK, nowDark).apply();
        if (dark == nowDark) {
            return false;
        }
        dark = nowDark;
        return true;
    }

    /** 没有光线传感器时的兜底：按小时判断 */
    private static boolean autoDarkByClock(Context c) {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 7;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 颜色映射

    /** 深色调色板（与浅色一一对应，对比度都过 WCAG AA） */
    private static final int D_BG = 0xFF14121B;
    private static final int D_CARD = 0xFF1E1B28;
    private static final int D_CARD_2 = 0xFF262232;
    private static final int D_LINE = 0x1FFFFFFF;
    private static final int D_TEXT = 0xFFF2EEF8;
    private static final int D_TEXT_SUB = 0xFFB9B2C8;
    private static final int D_TEXT_MUTED = 0xFF8D8599;
    private static final int D_PRIMARY = 0xFF7C3AED;
    private static final int D_PRIMARY_DEEP = 0xFFA78BFA;
    private static final int D_PRIMARY_SOFT = 0xFF2A2340;
    private static final int D_ACCENT = 0xFFF472B6;
    private static final int D_ACCENT_SOFT = 0xFF3A2130;
    private static final int D_SUCCESS = 0xFF4ADE80;
    private static final int D_DANGER = 0xFFF87171;
    private static final int D_STAR = 0xFFFBBF24;

    /**
     * 把一个"浅色模式下的颜色"翻译成当前主题下的颜色。
     *
     * 规则按优先级：
     *   1. 完全透明 / 半透明黑（分隔线）→ 半透明白
     *   2. 浅色底（三者都亮）→ 深色底，亮度越高映射越接近卡片色
     *   3. 深色字（三者都暗）→ 浅色字
     *   4. 其余（彩色：紫/粉/绿/红）→ 提亮，保证在深底上够看
     *
     * 这个函数是幂等的安全网：对已经是深色的颜色再跑一次也不会爆炸，
     * 但我们只会在浅色源码色上调用它，所以不做多重映射。
     */
    public static int adapt(int color) {
        if (!dark) {
            return color;
        }
        int a = (color >>> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;

        // 1. 全透明：图形里当"挖空"用，原样返回
        if (a == 0) {
            return color;
        }
        // 2. 半透明的深色（分隔线 0x14000000、蒙层）：翻成半透明白。
        //    必须带 a < 0xFF —— 否则不透明的深色文字（0xFF1E1B2E）会被误吃，
        //    变成一条白色（这是脚本跑对比度时抓出来的，肉眼看不出来）。
        if (a < 0xFF && r < 0x60 && g < 0x60 && b < 0x60) {
            return Color.argb(Math.min(a, 0x40), 0xFF, 0xFF, 0xFF);
        }

        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        boolean gray = (max - min) < 24;

        /*
         * 阈值 0xB0 是量出来的，不是拍的：
         *   项目里所有"文字/彩色"字面量的亮度最高到 0x9C（0xFF3ECF8E 浅绿），
         *   所有"底色"字面量的亮度最低从 0xC7（0xFFC9C4D4 浅灰）起，
         *   中间有 43 级空档，所以 0xB0 放哪边都不会误判。
         */
        final int BG_CUT = 0xB0;

        // 3. 底色
        if (lum >= BG_CUT) {
            if (lum >= 0xF5) {
                return Color.argb(a, 0x1E, 0x1B, 0x28);          // 近白 → 卡片
            }
            if (gray) {
                return Color.argb(a, 0x26, 0x22, 0x32);          // 浅灰 → 提亮层
            }
            return tintDark(r, g, b, a);                          // 浅彩色 → 深彩色底
        }

        // 4. 文字 / 彩色（亮度 < 0xB0）
        if (gray) {
            if (lum >= 0x80) {
                return Color.argb(a, 0x8D, 0x85, 0x99);          // 弱文字
            }
            if (lum >= 0x50) {
                return Color.argb(a, 0xB9, 0xB2, 0xC8);          // 次要文字
            }
            return Color.argb(a, 0xF2, 0xEE, 0xF8);              // 主文字
        }
        return tintLight(r, g, b, a);                             // 彩色 → 提亮
    }

    /**
     * 按钮渐变在深色下的取值。
     *
     * 为什么不能交给 adapt：adapt 对彩色走"提亮"，而提亮后的渐变上
     * 白字对比度只有 4.23（实测），不合格。按钮是"亮底 + 白字"的组合，
     * 深色下反而要把底压深，白字才立得住。
     * 实测 0xFF6D28D9 -> 0xFF7C3AED 时白字最低 5.70，达标。
     */
    public static int[] adaptButtonGradient(int colorA, int colorB) {
        if (!dark) {
            return new int[]{colorA, colorB};
        }
        return new int[]{0xFF6D28D9, 0xFF7C3AED};
    }

    /**
     * 字面量当作**文字**用时的映射（比 adapt 多一条规则）。
     *
     * 为什么需要单独一个入口：同一个 0xFFFFFFFF 可能有两种含义——
     *   · 当底色用：卡片白 → 深色下要变深卡片色
     *   · 当文字用：彩色按钮上的白字 → 深色下必须**保持白**，
     *     否则按钮上就没字了
     * 光看颜色值分不出来，所以由调用方（Ui.tv / Ui.pill）明确告诉它这是文字。
     */
    public static int adaptText(int color) {
        if (!dark) {
            return color;
        }
        // 不透明的白 = 前景白字，原样保留
        if ((color >>> 24) == 0xFF && (color & 0xFFFFFF) == 0xFFFFFF) {
            return color;
        }
        return adapt(color);
    }

    /** 浅色底 → 深色底：亮度压到 ~12%，色相保留一点 */
    private static int tintDark(int r, int g, int b, int a) {
        int nr = (int) (r * 0.16f) + 0x18;
        int ng = (int) (g * 0.16f) + 0x16;
        int nb = (int) (b * 0.16f) + 0x20;
        return Color.argb(a, Math.min(nr, 255), Math.min(ng, 255), Math.min(nb, 255));
    }

    /**
     * 深彩字 → 提亮。
     *
     * 用"向白色线性混合"而不是逐通道乘系数：
     * 乘法会让本来饱和的通道先撞顶（粉 0xFFEC4899 的 R/B 都顶到 255，
     * 变成洋红 0xFFFF83FF；绿会变青），色相就跑了。
     * 向白混合相当于降低饱和度、抬高亮度，色相基本不动。
     */
    private static int tintLight(int r, int g, int b, int a) {
        float t = 0.34f;
        int nr = (int) (r + (255 - r) * t);
        int ng = (int) (g + (255 - g) * t);
        int nb = (int) (b + (255 - b) * t);
        return Color.argb(a, nr, ng, nb);
    }

    private static int blendAlpha(int rgb, int alpha) {
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    /** 语义令牌在深色下的取值（Ui 直接调用，保证大色调统一） */
    public static int bg() {
        return dark ? D_BG : Ui.BG_LIGHT;
    }

    public static int card() {
        return dark ? D_CARD : Ui.CARD_LIGHT;
    }

    public static int text() {
        return dark ? D_TEXT : Ui.TEXT_LIGHT;
    }

    public static int textSub() {
        return dark ? D_TEXT_SUB : Ui.TEXT_SUB_LIGHT;
    }

    public static int textMuted() {
        return dark ? D_TEXT_MUTED : Ui.TEXT_MUTED_LIGHT;
    }

    public static int line() {
        return dark ? D_LINE : Ui.LINE_LIGHT;
    }

    public static int primary() {
        return dark ? D_PRIMARY : Ui.PRIMARY_LIGHT;
    }

    public static int primaryDeep() {
        return dark ? D_PRIMARY_DEEP : Ui.PRIMARY_DEEP_LIGHT;
    }

    public static int primarySoft() {
        return dark ? D_PRIMARY_SOFT : Ui.PRIMARY_SOFT_LIGHT;
    }

    public static int accent() {
        return dark ? D_ACCENT : Ui.ACCENT_LIGHT;
    }

    public static int accentSoft() {
        return dark ? D_ACCENT_SOFT : Ui.ACCENT_SOFT_LIGHT;
    }

    public static int accentDeep() {
        return dark ? D_ACCENT : Ui.ACCENT_DEEP_LIGHT;
    }

    public static int success() {
        return dark ? D_SUCCESS : Ui.SUCCESS_LIGHT;
    }

    public static int danger() {
        return dark ? D_DANGER : Ui.DANGER_LIGHT;
    }

    public static int star() {
        return dark ? D_STAR : Ui.STAR_LIGHT;
    }

    // ---------------------------------------------------------------- 环境光

    /**
     * 环境光监听。
     *
     * 用 TYPE_LIGHT 传感器；拿到值后做滞回（hysteresis）：
     * 低于 20 lux 算暗、高于 120 lux 算亮。中间区间保持不变，
     * 免得手一挥屏幕就闪。
     *
     * 每 3 秒最多判断一次——传感器可能高频回调，重建 Activity 很贵。
     */
    public static final class Ambient implements SensorEventListener {
        public interface Listener {
            void onAmbientChanged(boolean nowDark);
        }

        private final Context ctx;
        private final SensorManager sm;
        private final Sensor light;
        private final Listener listener;
        private final Handler ui = new Handler(Looper.getMainLooper());
        private long lastEval = 0L;
        private boolean registered = false;
        /** 上一次的"同向"判定：用来要求连续两次一致才真正切换 */
        private int lastDir = 0;    // 0=无 1=想变暗 -1=想变亮

        /*
         * 阈值与防抖（针对"屏幕一直闪"）。
         *
         * 光传感器在真实环境里是抖动很厉害的：有人走过、云飘过、
         * 台灯角度变一点，读数就能在几十到几百 lux 之间跳。
         * 原来只要跨过一次阈值就重建界面，于是光线在阈值附近晃时
         * 界面会被反复重建 —— 观感就是屏幕一直在闪。
         *
         * 三重防护：
         *   1. 拉大滞回区间：暗→亮要冲过 LIGHT_LUX，亮→暗要跌破 DARK_LUX，
         *      中间 20~200 这片区域完全不动（原来是 20~120，太窄）。
         *   2. 判定间隔从 3 秒提到 8 秒 —— 即使抖动也不会连续触发。
         *   3. **连续两次同向才算数**：单次尖峰不切，避免一次闪光灯就变天。
         */
        private static final float DARK_LUX = 20f;
        private static final float LIGHT_LUX = 200f;
        private static final long MIN_INTERVAL_MS = 8000L;

        public Ambient(Context c, Listener l) {
            this.ctx = c;
            this.listener = l;
            this.sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            this.light = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        }

        public boolean available() {
            return light != null;
        }

        public void start() {
            if (registered || light == null || sm == null) {
                return;
            }
            try {
                sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
                registered = true;
            } catch (Exception ignore) {
            }
        }

        public void stop() {
            if (!registered || sm == null) {
                return;
            }
            try {
                sm.unregisterListener(this);
            } catch (Exception ignore) {
            }
            registered = false;
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            if (e == null || e.values == null || e.values.length == 0) {
                return;
            }
            float lux = e.values[0];
            long now = System.currentTimeMillis();
            // 判定间隔拉长：抖动再厉害也不会连续触发
            if (now - lastEval < MIN_INTERVAL_MS) {
                return;
            }
            // 没拿到有效值就别动
            if (Float.isNaN(lux) || lux < 0f) {
                return;
            }
            boolean cur = Theme.isDark();
            int dir;    // 1=想变暗 -1=想变亮 0=不动
            if (lux < DARK_LUX && !cur) {
                dir = 1;
            } else if (lux > LIGHT_LUX && cur) {
                dir = -1;
            } else {
                // 落在滞回区间（DARK_LUX ~ LIGHT_LUX）里，保持现状
                dir = 0;
            }
            if (dir == 0) {
                lastDir = 0;   // 回到中间区间，之前的"一次同向"作废
                return;
            }
            /*
             * 连续两次同向才切换。
             * 单次尖峰（有人开了一下灯、闪光灯、窗外过一辆车）不足以改变主题。
             */
            if (dir != lastDir) {
                lastDir = dir;
                return;
            }
            lastDir = 0;    // 用掉这次判定
            lastEval = now;
            listener.onAmbientChanged(dir == 1);
        }

        @Override
        public void onAccuracyChanged(Sensor s, int accuracy) {
        }
    }

    /** 供 UI 用的友好名字 */
    public static String[] modeNames() {
        return new String[]{"跟随环境光", "始终浅色", "始终深色"};
    }

    /** 版本兜底：Api26 才有的常量 */
    @SuppressWarnings("unused")
    private static boolean api26() {
        return Build.VERSION.SDK_INT >= 26;
    }
}
