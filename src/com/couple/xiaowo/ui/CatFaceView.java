package com.couple.xiaowo.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 用 Canvas 画出来的小猫头像（菜单缩略图 / 页面主视觉）。
 * 不用任何图片资源，所以 APK 很小、任何分辨率都清晰。
 */
public class CatFaceView extends View {

    private static final int[][] PALETTE = {
            {0xFFFFE3EC, 0xFFFFFFFF}, // 0 粉
            {0xFFEDE4FB, 0xFFFFFFFF}, // 1 薰衣草
            {0xFFE4F1FB, 0xFFFFFFFF}, // 2 天蓝
            {0xFFFBEDE0, 0xFFFFFFFF}, // 3 蜜桃
            {0xFFE7F5EA, 0xFFFFFFFF}, // 4 薄荷
            {0xFFF7E9DD, 0xFFFFFFFF}, // 5 米色
            {0xFFF0E6F8, 0xFFFFFFFF}, // 6 紫
            {0xFFFFECF3, 0xFFFFFFFF}  // 7 玫瑰
    };

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int style = 0;

    public CatFaceView(Context c) {
        super(c);
    }

    public CatFaceView(Context c, int style) {
        super(c);
        this.style = style;
    }

    public void setStyle(int s) {
        this.style = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        int[] pal = PALETTE[Math.abs(style) % PALETTE.length];
        int bg = pal[0];
        int head = pal[1];
        int ink = 0xFF3A3546;
        int exp = Math.abs(style) % 3;

        // 背景圆角方块
        p.setColor(bg);
        c.drawRoundRect(new RectF(w * 0.03f, h * 0.03f, w * 0.97f, h * 0.97f), w * 0.26f, w * 0.26f, p);

        float cx = w * 0.5f;
        float hy = h * 0.55f;
        float hw = w * 0.62f;
        float hh = h * 0.52f;

        // 耳朵（三角形 + 顶点小圆做圆角）
        p.setColor(head);
        path.reset();
        path.moveTo(cx - hw * 0.46f, hy - hh * 0.18f);
        path.lineTo(cx - hw * 0.40f, hy - hh * 0.86f);
        path.lineTo(cx - hw * 0.06f, hy - hh * 0.44f);
        path.close();
        c.drawPath(path, p);
        c.drawCircle(cx - hw * 0.40f, hy - hh * 0.84f, w * 0.035f, p);

        path.reset();
        path.moveTo(cx + hw * 0.46f, hy - hh * 0.18f);
        path.lineTo(cx + hw * 0.40f, hy - hh * 0.86f);
        path.lineTo(cx + hw * 0.06f, hy - hh * 0.44f);
        path.close();
        c.drawPath(path, p);
        c.drawCircle(cx + hw * 0.40f, hy - hh * 0.84f, w * 0.035f, p);

        // 耳朵内部粉色
        p.setColor(0x33FF8FAB);
        oval.set(cx - hw * 0.40f, hy - hh * 0.72f, cx - hw * 0.20f, hy - hh * 0.40f);
        c.drawOval(oval, p);
        oval.set(cx + hw * 0.20f, hy - hh * 0.72f, cx + hw * 0.40f, hy - hh * 0.40f);
        c.drawOval(oval, p);

        // 头
        p.setColor(head);
        oval.set(cx - hw * 0.5f, hy - hh * 0.5f, cx + hw * 0.5f, hy + hh * 0.5f);
        c.drawRoundRect(oval, w * 0.22f, w * 0.22f, p);

        // 眼睛
        float eyeDx = hw * 0.20f;
        float eyeCy = hy + hh * 0.02f;
        p.setStyle(Paint.Style.FILL);
        if (exp == 1) {
            // 开心 `^ ^`
            p.setColor(ink);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.024f);
            p.setStrokeCap(Paint.Cap.ROUND);
            oval.set(cx - eyeDx - w * 0.055f, eyeCy - w * 0.045f, cx - eyeDx + w * 0.055f, eyeCy + w * 0.035f);
            c.drawArc(oval, 200, 140, false, p);
            oval.set(cx + eyeDx - w * 0.055f, eyeCy - w * 0.045f, cx + eyeDx + w * 0.055f, eyeCy + w * 0.035f);
            c.drawArc(oval, 200, 140, false, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            float rx = hw * 0.115f;
            float ry = hh * 0.135f;
            p.setColor(ink);
            oval.set(cx - eyeDx - rx, eyeCy - ry, cx - eyeDx + rx, eyeCy + ry);
            c.drawOval(oval, p);
            oval.set(cx + eyeDx - rx, eyeCy - ry, cx + eyeDx + rx, eyeCy + ry);
            c.drawOval(oval, p);
            p.setColor(Color.WHITE);
            c.drawCircle(cx - eyeDx + rx * 0.30f, eyeCy - ry * 0.40f, rx * 0.34f, p);
            c.drawCircle(cx + eyeDx + rx * 0.30f, eyeCy - ry * 0.40f, rx * 0.34f, p);
        }

        // 腮红
        p.setColor(0x66FF9DB5);
        c.drawCircle(cx - hw * 0.40f, hy + hh * 0.20f, w * 0.052f, p);
        c.drawCircle(cx + hw * 0.40f, hy + hh * 0.20f, w * 0.052f, p);

        // 嘴巴 ω
        p.setColor(ink);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.020f);
        p.setStrokeCap(Paint.Cap.ROUND);
        float mY = hy + hh * 0.30f;
        oval.set(cx - w * 0.058f, mY - w * 0.020f, cx, mY + w * 0.048f);
        c.drawArc(oval, 0, 180, false, p);
        oval.set(cx, mY - w * 0.020f, cx + w * 0.058f, mY + w * 0.048f);
        c.drawArc(oval, 0, 180, false, p);
        p.setStyle(Paint.Style.FILL);
    }
}
