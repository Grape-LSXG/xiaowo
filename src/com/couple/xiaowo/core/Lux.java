package com.couple.xiaowo.core;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * 一次性读环境光。
 *
 * 为什么单独写一个：切换到「跟随环境光」模式时，如果等传感器的持续回调，
 * 用户会觉得"点了没反应"。这里注册一个只读一次的监听，
 * 拿到第一个有效值就注销，立刻给出结论。
 *
 * 传感器可能几秒都没有回调（部分机型在休眠时），所以带超时兜底：
 * 拿不到就返回"不确定"，由调用方按时间判断。
 */
public final class Lux {

    /** 超过这个亮度算"亮"，与 Theme 的滞回阈值保持一致 */
    private static final float BRIGHT_LUX = 120f;
    /** 最多等这么久 */
    private static final long TIMEOUT_MS = 900L;

    private Lux() {
    }

    /**
     * 当前环境是否够亮。
     * 没有光线传感器、或读取超时，回落到"按时间判断"。
     */
    public static boolean isBright(Context c) {
        Float v = readOnce(c);
        if (v == null) {
            int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            return h >= 7 && h < 19;
        }
        return v >= BRIGHT_LUX;
    }

    /** 读一次，拿不到返回 null */
    public static Float readOnce(Context c) {
        final SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) {
            return null;
        }
        final Sensor light = sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light == null) {
            return null;
        }
        final Object lock = new Object();
        final float[] out = new float[]{Float.NaN};
        final SensorEventListener l = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                if (e != null && e.values != null && e.values.length > 0) {
                    synchronized (lock) {
                        if (Float.isNaN(out[0])) {
                            out[0] = e.values[0];
                            lock.notifyAll();
                        }
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor s, int a) {
            }
        };
        try {
            sm.registerListener(l, light, SensorManager.SENSOR_DELAY_FASTEST);
        } catch (Exception ignore) {
            return null;
        }
        try {
            synchronized (lock) {
                if (Float.isNaN(out[0])) {
                    lock.wait(TIMEOUT_MS);
                }
            }
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                sm.unregisterListener(l);
            } catch (Exception ignore) {
            }
        }
        return Float.isNaN(out[0]) ? null : Float.valueOf(out[0]);
    }
}
