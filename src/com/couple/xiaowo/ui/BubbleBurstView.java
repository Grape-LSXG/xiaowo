package com.couple.xiaowo.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * 全屏泡泡粒子特效：点单端长按「+」时持续喷泡泡，普通轻点时炸一小簇。
 *
 * <p>纯 Canvas 逐帧绘制，零图片、零第三方库。骨架照 {@link HeartBurstView} 的
 * {@link Choreographer} 驱动写法，区别在于：
 * <ul>
 *   <li>粒子是圆润的半透明泡泡（带左上角高光 + 半透明描边），不是爱心；</li>
 *   <li>运动是「缓慢上浮 + sin 左右摆动」，不是受重力掉落；</li>
 *   <li>支持长按期间的持续喷射（{@link #startStreaming()} / {@link #stopStreaming()}）。</li>
 * </ul>
 *
 * <p>性能：粒子对象全部走复用池，活跃列表是预分配的定长数组，稳态零 GC；
 * 粒子上限 {@value #MAX_PARTICLES}，超出时丢弃最早的那个；粒子空了就停止
 * Choreographer 回调，不在没粒子时空转。
 *
 * <p>它盖在最上层但 clickable=false，不会吃掉点击事件、不挡下面按钮。
 */
public class BubbleBurstView extends View implements Choreographer.FrameCallback {

    /** 活跃粒子上限，超出丢弃最早的 */
    private static final int MAX_PARTICLES = 220;
    /** emitPulse() 每次喷的泡泡数 */
    private static final int PULSE_COUNT = 8;
    /** 长按持续喷射的间隔 */
    private static final long STREAM_INTERVAL_MS = 55L;
    /** 单帧最大步进，防止卡顿后粒子瞬移 */
    private static final float MAX_DT_MS = 64f;

    private static final int BASE_PINK = 0xFFEC4899;
    private static final int BASE_VIOLET = 0xFF8B5CF6;
    private static final int BASE_SKY = 0xFF38BDF8;

    private static final float TAU = (float) (Math.PI * 2.0);

    /** 复用池（空闲对象），稳态下不再 new */
    private final Particle[] pool = new Particle[MAX_PARTICLES];
    /** 活跃粒子引用，FIFO 顺序，保证「丢弃最早的」语义 */
    private final Particle[] active = new Particle[MAX_PARTICLES];
    private int activeCount;
    private int poolCount;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();

    private float density;
    private float strokeWidth;

    /** 三色糖果调色板，setAccentColor 会就地改这 3 个值（不产生新对象） */
    private final int[] palette = {BASE_PINK, BASE_VIOLET, BASE_SKY};
    /** 选中各色的权重，合计 100 */
    private final int[] weights = {45, 35, 20};
    private int weightSum = 100;

    /** 喷射锚点（0~1 相对宽高），默认屏幕中下偏上，正好在「+」按钮上方一带 */
    private float anchorFx = 0.5f;
    private float anchorFy = 0.72f;

    // ---- 帧循环状态 ----
    /** 已把本 view 排进 Choreographer 队列 */
    private boolean frameScheduled;
    private long lastFrameNs;
    /** 长按持续喷射中 */
    private boolean streaming;
    private float streamAcc;
    /** 已 post 过一个「等布局完成再喷」的回调，防止重复 post */
    private boolean deferredSpawn;

    private static final class Particle {
        float x0, y0;        // 出生点
        float rise;          // 总上升高度 px
        float driftX;        // 总水平漂移 px
        float swayAmp;       // sin 摆动幅度 px
        float swayPhase;     // sin 初相
        float swayCycles;    // 生命期内摆动周期数
        float size;          // 半径 px
        float maxAlpha;      // 0.35 ~ 0.70
        int color;           // 0x00RRGGBB
        long life, ttl;

        // ---- 每帧在 doFrame 里算好，onDraw 直接用，避免重复计算 ----
        float dx, dy;        // 当前绘制位置
        float a;             // 当前吃掉了淡出后的实际 alpha（0~1）
    }

    public BubbleBurstView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        if (density <= 0f) density = 1f;
        strokeWidth = Math.max(1f, density * 0.9f);
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
        paint.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------ 公开 API

    /**
     * 强调色：会以不同比例混进粉/紫/天蓝三色里，保持糖果感的同时贴主题色。
     * 不调用则默认就是粉 + 紫 + 天蓝的混合。
     */
    public void setAccentColor(int color) {
        int accent = color | 0xFF000000;
        palette[0] = mix(accent, BASE_PINK, 0.45f);
        palette[1] = mix(accent, BASE_VIOLET, 0.30f);
        palette[2] = mix(accent, BASE_SKY, 0.20f);
        weights[0] = 48;
        weights[1] = 32;
        weights[2] = 20;
        weightSum = 100;
        invalidate();
    }

    /** 喷一小簇（约 8 个泡泡）。长按期间由 {@link #startStreaming()} 自动反复调用。 */
    public void emitPulse() {
        spawn(PULSE_COUNT, 0.7f);
    }

    /** 长按开始：每约 55ms 自动喷一簇，持续不断。重复调用是安全的。 */
    public void startStreaming() {
        if (streaming) return;
        streaming = true;
        streamAcc = STREAM_INTERVAL_MS;   // 立刻先喷一发，手感不迟滞
        ensureFrame();
    }

    /** 松手停止：只停「继续喷」，已经在飞的泡泡会自然飘完、淡出消失。 */
    public void stopStreaming() {
        streaming = false;
        streamAcc = 0f;
        // 帧循环不用停：doFrame 里会因为还有活跃粒子而自己续上，粒子空了自然停
    }

    /** 一次性炸一批（用于普通轻点）。 */
    public void burst(int count) {
        if (count <= 0) return;
        spawn(Math.min(count, MAX_PARTICLES), 1f);
    }

    /** 是否正在持续喷射 */
    public boolean isStreaming() {
        return streaming;
    }

    /**
     * 可选：自定义喷射锚点（相对宽高的百分比，0~1）。
     * 默认 (0.5, 0.72)，即屏幕中下部。传入非法值会被夹到 [0,1]。
     */
    public void setAnchorFraction(float fx, float fy) {
        anchorFx = clamp01(fx);
        anchorFy = clamp01(fy);
    }

    /** 恢复默认锚点 */
    public void resetAnchor() {
        anchorFx = 0.5f;
        anchorFy = 0.72f;
    }

    /** 立刻清空所有粒子（一般不需要，用于页面销毁/切页） */
    public void clearParticles() {
        for (int i = 0; i < activeCount; i++) {
            release(active[i]);
            active[i] = null;
        }
        activeCount = 0;
        invalidate();
    }

    // ------------------------------------------------------------ 粒子生成

    private void spawn(final int count, final float power) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            // 还没布局完：非持续喷射时补一帧再来（保留原来的 count / power，别丢参数）
            if (!streaming && !deferredSpawn) {
                deferredSpawn = true;
                post(new Runnable() {
                    @Override
                    public void run() {
                        deferredSpawn = false;
                        if (getWidth() > 0 && getHeight() > 0) {
                            spawn(count, power);
                        }
                        // 仍然没布局好就放弃这一发，避免无限 post
                    }
                });
            }
            // 持续喷射直接跳过：帧循环每 ~55ms 还会再喷，不会丢任何东西
            return;
        }
        float ax = w * anchorFx;
        float ay = h * anchorFy;
        for (int i = 0; i < count; i++) {
            addOne(ax, ay, power);
        }
        ensureFrame();
        invalidate();
    }

    private void addOne(float ax, float ay, float power) {
        if (activeCount >= MAX_PARTICLES) {
            // 上限：丢弃最早的
            release(active[0]);
            System.arraycopy(active, 1, active, 0, MAX_PARTICLES - 1);
            activeCount--;
        }
        Particle p = obtain();
        // 出生点：以锚点为中心的一小团，避免全挤在一条线
        p.x0 = ax + (random.nextFloat() - 0.5f) * density * 30f;
        p.y0 = ay + (random.nextFloat() - 0.5f) * density * 16f;
        // 上浮总高度：60~170dp（缓慢）
        p.rise = density * (60f + random.nextFloat() * 110f) * power;
        // 轻微水平漂移
        p.driftX = (random.nextFloat() - 0.5f) * density * 70f * power;
        // 左右摆动
        p.swayAmp = density * (3f + random.nextFloat() * 7f);
        p.swayPhase = random.nextFloat() * TAU;
        p.swayCycles = 0.7f + random.nextFloat() * 1.2f;
        // 尺寸：直径 8~26dp → 半径 4~13dp
        p.size = density * (4f + random.nextFloat() * 9f);
        // alpha 0.35 ~ 0.70
        p.maxAlpha = 0.35f + random.nextFloat() * 0.35f;
        p.color = pickColor() & 0x00FFFFFF;
        // 存活 1200 ~ 2200ms
        p.ttl = 1200L + (long) random.nextInt(1001);
        p.life = 0L;
        // 出生时先算好初始位置（alpha=0，第一帧才会淡入）
        p.dx = p.x0;
        p.dy = p.y0;
        p.a = 0f;
        active[activeCount++] = p;
    }

    private int pickColor() {
        int r = random.nextInt(weightSum);
        int acc = 0;
        for (int i = 0; i < palette.length; i++) {
            acc += weights[i];
            if (r < acc) return palette[i];
        }
        return palette[palette.length - 1];
    }

    // ------------------------------------------------------------ 帧循环

    private void ensureFrame() {
        if (!frameScheduled) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        frameScheduled = false;

        float dt;
        if (lastFrameNs == 0L) {
            dt = 16f;
        } else {
            dt = (frameTimeNanos - lastFrameNs) / 1000000f;
        }
        lastFrameNs = frameTimeNanos;
        if (dt < 0f) dt = 0f;
        if (dt > MAX_DT_MS) dt = MAX_DT_MS;

        // 长按持续喷射：按累计时间补发，帧率抖动也不影响手感
        if (streaming) {
            streamAcc += dt;
            int guard = 0;
            while (streamAcc >= STREAM_INTERVAL_MS && guard++ < 4) {
                streamAcc -= STREAM_INTERVAL_MS;
                spawn(PULSE_COUNT, 0.7f);
            }
        }

        // 推进 + 回收（原地压缩，保持 FIFO 顺序，无新对象）
        int write = 0;
        for (int i = 0; i < activeCount; i++) {
            Particle p = active[i];
            p.life += (long) dt;
            if (p.life >= p.ttl) {
                release(p);
                continue;
            }
            // 位置与透明度在这里算一次，onDraw 只负责画
            float k = (float) p.life / p.ttl;
            float eased = k * k * (3f - 2f * k);          // 慢 → 快 → 慢
            float sway = (float) Math.sin(p.swayPhase + k * p.swayCycles * TAU) * p.swayAmp;
            p.dx = p.x0 + p.driftX * eased + sway;
            p.dy = p.y0 - p.rise * eased;
            p.a = fade(k) * p.maxAlpha;
            active[write++] = p;
        }
        for (int i = write; i < activeCount; i++) {
            active[i] = null;
        }
        activeCount = write;

        invalidate();

        if (activeCount > 0 || streaming) {
            ensureFrame();
        } else {
            lastFrameNs = 0L;   // 动画彻底停下，下次从干净状态开始
        }
    }

    // ------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int n = activeCount;
        if (n == 0) return;

        for (int i = 0; i < n; i++) {
            Particle p = active[i];
            float a = p.a;
            if (a <= 0.004f) continue;

            float x = p.dx;
            float y = p.dy;
            float r = p.size;
            int rgb = p.color;
            int alpha = (int) (a * 255f);
            if (alpha > 255) alpha = 255;
            if (alpha <= 0) continue;

            // 主体：半透明圆
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((alpha << 24) | rgb);
            c.drawCircle(x, y, r, paint);

            // 描边：更淡的白，让泡泡边缘有玻璃感
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth);
            int rim = (int) (alpha * 0.55f);
            if (rim > 0) {
                paint.setColor((rim << 24) | 0x00FFFFFF);
                c.drawCircle(x, y, Math.max(0.5f, r - strokeWidth * 0.5f), paint);
            }

            // 左上角高光
            float hr = r * 0.28f;
            if (hr < 0.6f) hr = 0.6f;
            int hl = (int) (alpha * 0.62f);
            if (hl > 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor((hl << 24) | 0x00FFFFFF);
                c.drawCircle(x - r * 0.32f, y - r * 0.34f, hr, paint);
            }
        }
    }

    /** 淡入 10%，中段保持，最后 35% 淡出 */
    private static float fade(float k) {
        if (k <= 0f) return 0f;
        if (k < 0.10f) return k / 0.10f;
        if (k < 0.65f) return 1f;
        float t = (k - 0.65f) / 0.35f;
        return t >= 1f ? 0f : 1f - t;
    }

    // ------------------------------------------------------------ 池 & 工具

    private Particle obtain() {
        if (poolCount > 0) {
            Particle p = pool[--poolCount];
            pool[poolCount] = null;
            return p;
        }
        return new Particle();
    }

    private void release(Particle p) {
        if (p == null) return;
        if (poolCount < MAX_PARTICLES) {
            pool[poolCount++] = p;
        }
    }

    private static int mix(int base, int other, float t) {
        int a = (int) (android.graphics.Color.alpha(base)
                + (android.graphics.Color.alpha(other) - android.graphics.Color.alpha(base)) * t);
        int r = (int) (android.graphics.Color.red(base)
                + (android.graphics.Color.red(other) - android.graphics.Color.red(base)) * t);
        int g = (int) (android.graphics.Color.green(base)
                + (android.graphics.Color.green(other) - android.graphics.Color.green(base)) * t);
        int b = (int) (android.graphics.Color.blue(base)
                + (android.graphics.Color.blue(other) - android.graphics.Color.blue(base)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // 不吃点击事件
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        // 防止 Choreographer 持着 view 一直回调造成泄漏
        streaming = false;
        streamAcc = 0f;
        deferredSpawn = false;
        frameScheduled = false;
        lastFrameNs = 0L;
        Choreographer.getInstance().removeFrameCallback(this);
        clearParticles();
        super.onDetachedFromWindow();
    }
}
