package com.couple.xiaowo.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 全屏爱心粒子特效：下单成功 / 收到订单时炸一朵爱心。
 *
 * 完全由 Canvas 逐帧绘制，没有任何图片和第三方动画库。
 * 它盖在最上层但 clickable=false，所以不会挡住下面按钮的点击。
 */
public class HeartBurstView extends View implements Choreographer.FrameCallback {

    private static final int[] COLORS = {
            0xFFFF4D6D, 0xFFFF7897, 0xFFFF9DB5, 0xFFE86A9A,
            0xFF9B7BE8, 0xFFFF5FA2, 0xFFFFB3C7
    };

    private final List<Particle> particles = new ArrayList<Particle>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path heart = new Path();
    private final Random random = new Random();
    private final float density;

    private boolean animating;
    private long lastMs;

    private static final class Particle {
        float x, y, vx, vy, size, rot, rotSpeed, alpha;
        int color;
        long life, ttl;
    }

    public HeartBurstView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        setClickable(false);
        setFocusable(false);
        paint.setStyle(Paint.Style.FILL);
        buildHeartPath();
    }

    /** 单位空间的心形路径（大约是 -0.5..0.5），draw 时再缩放。 */
    private void buildHeartPath() {
        heart.reset();
        heart.moveTo(0f, 0.78f);
        heart.cubicTo(-0.58f, 0.44f, -0.96f, 0.06f, -0.60f, -0.26f);
        heart.cubicTo(-0.36f, -0.50f, -0.06f, -0.40f, 0f, -0.14f);
        heart.cubicTo(0.06f, -0.40f, 0.36f, -0.50f, 0.60f, -0.26f);
        heart.cubicTo(0.96f, 0.06f, 0.58f, 0.44f, 0f, 0.78f);
        heart.close();
    }

    public void burst() {
        burst(26);
    }

    public void burst(int count) {
        if (getWidth() == 0) {
            // 还没布局完，等一帧再来
            post(new Runnable() {
                @Override
                public void run() {
                    burst(count);
                }
            });
            return;
        }
        // 强制带上透明度动画的层级，避免低端机卡顿
        setLayerType(LAYER_TYPE_HARDWARE, null);
        float w = getWidth();
        float h = getHeight();
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            p.x = w * (0.5f + (random.nextFloat() - 0.5f) * 0.26f);
            p.y = h * (0.66f + (random.nextFloat() - 0.5f) * 0.10f);
            double ang = Math.toRadians(-90 + (random.nextDouble() - 0.5) * 150);
            float speed = density * (0.10f + random.nextFloat() * 0.26f);
            p.vx = (float) Math.cos(ang) * speed;
            p.vy = (float) Math.sin(ang) * speed;
            p.size = density * (7 + random.nextFloat() * 13);
            p.rot = random.nextFloat() * 360f;
            p.rotSpeed = (random.nextFloat() - 0.5f) * 0.22f;
            p.color = COLORS[random.nextInt(COLORS.length)];
            p.alpha = 0.95f;
            p.ttl = 900 + random.nextInt(1100);
            p.life = 0;
            particles.add(p);
        }
        if (!animating) {
            animating = true;
            lastMs = now;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        long now = System.currentTimeMillis();
        float dt = Math.min(48f, Math.max(0f, now - lastMs));
        lastMs = now;

        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life += (long) dt;
            if (p.life >= p.ttl) {
                particles.remove(i);
                continue;
            }
            p.vy += density * 0.00042f * dt;   // 重力
            p.vx *= 0.995f;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.rot += p.rotSpeed * dt;
            float k = (float) p.life / p.ttl;
            p.alpha = k < 0.15f ? k / 0.15f : (k > 0.65f ? Math.max(0f, 1f - (k - 0.65f) / 0.35f) : 1f);
        }
        invalidate();
        if (!particles.isEmpty()) {
            Choreographer.getInstance().postFrameCallback(this);
        } else {
            animating = false;
            setLayerType(LAYER_TYPE_NONE, null);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        for (Particle p : particles) {
            int a = (int) (Math.max(0f, Math.min(1f, p.alpha)) * 255);
            paint.setColor(p.color);
            paint.setAlpha(a);
            c.save();
            c.translate(p.x, p.y);
            c.rotate(p.rot);
            c.scale(p.size, p.size);
            c.drawPath(heart, paint);
            c.restore();
        }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        return false;
    }
}
