import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 生成 App 图标（各密度 mipmap PNG）。
 * 用 JDK 自带的 Java2D 画，不依赖任何图形库。
 *   运行： java MakeIcon.java <res 目录>
 */
public class MakeIcon {

    public static void main(String[] args) throws Exception {
        String resDir = args.length > 0 ? args[0] : "res";
        int[][] specs = {{48, 1}, {72, 1}, {96, 1}, {144, 1}, {192, 1}};
        String[] dirs = {"mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"};
        for (int i = 0; i < specs.length; i++) {
            File d = new File(resDir, dirs[i]);
            d.mkdirs();
            BufferedImage img = draw(specs[i][0]);
            ImageIO.write(img, "png", new File(d, "ic_launcher.png"));
            System.out.println("icon -> " + new File(d, "ic_launcher.png"));
        }
    }

    static BufferedImage draw(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        float s = size;

        // 背景：圆角方块 + 渐变
        RoundRectangle2D bg = new RoundRectangle2D.Float(s * 0.02f, s * 0.02f, s * 0.96f, s * 0.96f, s * 0.42f, s * 0.42f);
        g.setPaint(new GradientPaint(0, 0, new Color(0xFFB8D4), s, s, new Color(0xFFE8C4)));
        g.fill(bg);

        // 底部柔和光晕
        g.setPaint(new GradientPaint(0, s * 0.5f, new Color(255, 255, 255, 0), 0, s, new Color(255, 255, 255, 60)));
        g.fill(bg);

        float cx = s * 0.5f;
        float headW = s * 0.58f;
        float headH = s * 0.50f;
        float hy = s * 0.54f;

        // 耳朵：粗描边 + 填充 = 自动圆角
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(s * 0.045f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D earL = new Path2D.Float();
        earL.moveTo(cx - headW * 0.42, hy - headH * 0.20);
        earL.lineTo(cx - headW * 0.34, hy - headH * 0.86);
        earL.lineTo(cx - headW * 0.02, hy - headH * 0.40);
        earL.closePath();
        g.fill(earL);
        g.draw(earL);

        Path2D earR = new Path2D.Float();
        earR.moveTo(cx + headW * 0.42, hy - headH * 0.20);
        earR.lineTo(cx + headW * 0.34, hy - headH * 0.86);
        earR.lineTo(cx + headW * 0.02, hy - headH * 0.40);
        earR.closePath();
        g.fill(earR);
        g.draw(earR);
        g.setStroke(new BasicStroke(1f));

        // 头
        g.setColor(Color.WHITE);
        RoundRectangle2D head = new RoundRectangle2D.Float(
                cx - headW / 2, hy - headH / 2, headW, headH, s * 0.22f, s * 0.22f);
        g.fill(head);

        // 眼睛
        g.setColor(new Color(0x3A3546));
        float eyeDx = headW * 0.20f;
        float eyeCy = hy + headH * 0.02f;
        float rx = headW * 0.115f;
        float ry = headH * 0.135f;
        g.fill(new Ellipse2D.Float(cx - eyeDx - rx, eyeCy - ry, rx * 2, ry * 2));
        g.fill(new Ellipse2D.Float(cx + eyeDx - rx, eyeCy - ry, rx * 2, ry * 2));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(cx - eyeDx + rx * 0.30f - rx * 0.34f, eyeCy - ry * 0.40f - rx * 0.34f, rx * 0.68f, rx * 0.68f));
        g.fill(new Ellipse2D.Float(cx + eyeDx + rx * 0.30f - rx * 0.34f, eyeCy - ry * 0.40f - rx * 0.34f, rx * 0.68f, rx * 0.68f));

        // 腮红
        g.setColor(new Color(255, 157, 181, 150));
        g.fill(new Ellipse2D.Float(cx - headW * 0.40f - s * 0.05f, hy + headH * 0.16f, s * 0.10f, s * 0.07f));
        g.fill(new Ellipse2D.Float(cx + headW * 0.40f - s * 0.05f, hy + headH * 0.16f, s * 0.10f, s * 0.07f));

        // 嘴巴 ω
        g.setColor(new Color(0x3A3546));
        g.setStroke(new BasicStroke(s * 0.020f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float mY = hy + headH * 0.24f;
        g.draw(new Arc2D.Float(cx - s * 0.050f, mY - s * 0.012f, s * 0.050f, s * 0.058f, 0, 180, Arc2D.OPEN));
        g.draw(new Arc2D.Float(cx, mY - s * 0.012f, s * 0.050f, s * 0.058f, 0, 180, Arc2D.OPEN));

        // 右上角小爱心
        g.setColor(new Color(0xFF4D6D));
        Path2D heart = new Path2D.Float();
        float hx = s * 0.80f, hsz = s * 0.11f;
        heart.moveTo(hx, s * 0.155f + hsz * 0.78f);
        heart.curveTo(-0.58f * hsz + hx, 0.44f * hsz + s * 0.155f, -0.96f * hsz + hx, 0.06f * hsz + s * 0.155f, -0.60f * hsz + hx, -0.26f * hsz);
        heart.curveTo(-0.36f * hsz + hx, -0.50f * hsz, -0.06f * hsz + hx, -0.40f * hsz, hx, -0.14f * hsz);
        heart.curveTo(0.06f * hsz + hx, -0.40f * hsz, 0.36f * hsz + hx, -0.50f * hsz, 0.60f * hsz + hx, -0.26f * hsz);
        heart.curveTo(0.96f * hsz + hx, 0.06f * hsz, 0.58f * hsz + hx, 0.44f * hsz, hx, 0.78f * hsz);
        heart.closePath();
        g.fill(heart);

        g.dispose();
        return img;
    }
}
