package com.couple.xiaowo.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Vibrator;

/**
 * 触感反馈小工具 —— 全部内部消化，调用方一行就够。
 *
 * <p>VIBRATE 权限已经在清单里，这里不请求任何新权限。
 * API 26+ 走 {@code VibrationEffect}，更老的走已废弃的 ms 接口；
 * 缺振动器 / 厂商限制 / 用户关了震动等情况一律静默失败，绝不影响主流程。
 *
 * <p>minSdk 是 24，而 VibrationEffect 是 26 才有的类。为了在 24/25 上
 * 加载本类时不被连坐，所有 VibrationEffect 的引用都关在 {@link Api26}
 * 这个嵌套类里 —— 它只在 SDK_INT &gt;= 26 的分支里被碰，24/25 根本不会加载它。
 *
 * <p>用法：
 * <pre>
 *   Haptics.tick(act);      // 长按期间每隔 ~110ms 调一次
 *   Haptics.pop(act);       // 松手
 *   Haptics.success(act);   // 下单成功
 * </pre>
 */
public final class Haptics {

    /** 短促 tick 时长（ms） */
    private static final long TICK_MS = 10L;
    /** 稍强的 pop 时长（ms） */
    private static final long POP_MS = 20L;
    /** tick 的振幅（1~255），比 pop 轻 */
    private static final int TICK_AMPLITUDE = 70;
    /** pop 的振幅 */
    private static final int POP_AMPLITUDE = 150;
    /** success 的两下震动：延时, 第一下, 间隔, 第二下 */
    private static final long[] SUCCESS_PATTERN = {0L, 16L, 70L, 30L};

    private Haptics() {
    }

    /** 一个短促 tick（约 10ms），用于长按期间每隔一段重复调用。 */
    public static void tick(Activity act) {
        vibrate(act, TICK_MS, TICK_AMPLITUDE);
    }

    /** 稍强的 pop（约 20ms），用于松手 / 下单成功。 */
    public static void pop(Activity act) {
        vibrate(act, POP_MS, POP_AMPLITUDE);
    }

    /** 两下轻震组合，用于成功提示。 */
    public static void success(Activity act) {
        if (act == null) return;
        Vibrator v = vibrator(act);
        if (v == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                // 第二下稍重，形成「哒—哒」的确认感
                int[] amps = {0, POP_AMPLITUDE, 0, POP_AMPLITUDE};
                Api26.waveform(v, SUCCESS_PATTERN, amps, -1);
            } else {
                v.vibrate(SUCCESS_PATTERN, -1);
            }
        } catch (Throwable ignored) {
            // 静默失败：触感只是加分项
        }
    }

    // ------------------------------------------------------------ 内部

    private static void vibrate(Activity act, long ms, int amplitude) {
        if (act == null) return;
        Vibrator v = vibrator(act);
        if (v == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Api26.oneShot(v, ms, amplitude);
            } else {
                v.vibrate(ms);
            }
        } catch (Throwable ignored) {
            // 静默失败
        }
    }

    private static Vibrator vibrator(Context c) {
        try {
            Object o = c.getSystemService(Context.VIBRATOR_SERVICE);
            return o instanceof Vibrator ? (Vibrator) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * API 26+ 才有 VibrationEffect。单独成类，24/25 上的设备永远不会加载它，
     * 也就不存在「引用了不存在的类」的隐患。
     */
    private static final class Api26 {
        static void oneShot(Vibrator v, long ms, int amplitude) {
            android.os.VibrationEffect e =
                    android.os.VibrationEffect.createOneShot(ms, amplitude);
            v.vibrate(e);
        }

        static void waveform(Vibrator v, long[] timings, int[] amplitudes, int repeat) {
            android.os.VibrationEffect e =
                    android.os.VibrationEffect.createWaveform(timings, amplitudes, repeat);
            v.vibrate(e);
        }
    }
}
