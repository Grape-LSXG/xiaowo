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

        /*
         * 眼睛：「斜眼」表情包。
         *
         * 画法（按 48px 也能认出为准）：
         *   1. 白色眼白椭圆
         *   2. 瞳孔整体往同一侧偏移 —— 这是「斜眼」的关键，两只眼看同一个方向
         *   3. 上眼皮用一条倾斜的深色粗线压下来，做出半眯的效果
         */
        g.setColor(new Color(0x3A3546));
        float eyeDx = headW * 0.20f;
        float eyeCy = hy + headH * 0.02f;
        float rx = headW * 0.130f;
        float ry = headH * 0.150f;

        // 眼白：白脸用纯白会看不见边界，但也别加灰 —— 加了会像戴眼镜。
        // 靠瞳孔和上眼皮来表达「斜眼」，眼白只当作一层底色。
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Float(cx - eyeDx - rx, eyeCy - ry, rx * 2, ry * 2));
        g.fill(new Ellipse2D.Float(cx + eyeDx - rx, eyeCy - ry, rx * 2, ry * 2));

        // 瞳孔：两只一起往右偏 → 斜眼
        g.setColor(new Color(0x3A3546));
        float pupR = rx * 0.60f;
        float pupOffX = rx * 0.40f;
        float pupOffY = ry * 0.46f;
        g.fill(new Ellipse2D.Float(cx - eyeDx + pupOffX - pupR, eyeCy + pupOffY - pupR, pupR * 2, pupR * 2));
        g.fill(new Ellipse2D.Float(cx + eyeDx + pupOffX - pupR, eyeCy + pupOffY - pupR, pupR * 2, pupR * 2));

        // 上眼皮：倾斜粗线压住眼睛上半部 = 半眯斜眼
        g.setColor(new Color(0x3A3546));
        g.setStroke(new BasicStroke(s * 0.026f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float lidY1 = eyeCy - ry * 0.92f;
        float lidY2 = eyeCy - ry * 0.50f;
        Path2D lidL = new Path2D.Float();
        lidL.moveTo(cx - eyeDx - rx * 0.95f, lidY2);
        lidL.lineTo(cx - eyeDx + rx * 0.95f, lidY1);
        g.draw(lidL);
        Path2D lidR = new Path2D.Float();
        lidR.moveTo(cx + eyeDx - rx * 0.95f, lidY1);
        lidR.lineTo(cx + eyeDx + rx * 0.95f, lidY2);
        g.draw(lidR);

        // 腮红：保留一点点，让它还是可爱的（不是嘲讽脸）
        g.setColor(new Color(255, 157, 181, 110));
        g.fill(new Ellipse2D.Float(cx - headW * 0.40f - s * 0.04f, hy + headH * 0.15f, s * 0.09f, s * 0.06f));
        g.fill(new Ellipse2D.Float(cx + headW * 0.40f - s * 0.04f, hy + headH * 0.15f, s * 0.09f, s * 0.06f));

        // 嘴巴：一边翘的小坏笑（配合斜眼）
        g.setColor(new Color(0x3A3546));
        g.setStroke(new BasicStroke(s * 0.020f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float mY = hy + headH * 0.26f;
        Path2D mouth = new Path2D.Float();
        mouth.moveTo(cx - s * 0.045f, mY);
        mouth.curveTo(cx - s * 0.015f, mY + s * 0.045f,
                cx + s * 0.035f, mY + s * 0.040f,
                cx + s * 0.055f, mY - s * 0.010f);
        g.draw(mouth);

        // 右上角小爱心
        g.setColor(new Color(0xFF4D6D));
        Path2D heart = new Path2D.Float();
        float hx = s * 0.80f, hsz = s * 0.10f;
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
