package com.couple.xiaowo.core;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 运行日志（专业模式里那个「日志」页用）。
 *
 * 为什么自己存一份：
 *   · 用户是普通手机，拿不到 logcat —— 出了问题只能我猜；
 *   · 连接这件事牵扯太多环节（模式选择、broker、心跳、发布回执、分块传输），
 *     出问题时需要"刚才到底发生了什么"的时间线。
 *
 * 做法尽量省事：
 *   · 内存里环形缓冲（最近 {@link #CAPACITY} 条），页面打开就能看；
 *   · 同时追加到 cacheDir/xiaowo.log，App 被杀以后也还在（超过 {@link #MAX_BYTES} 自动截断）；
 *   · 全程 try/catch 吞掉异常 —— 记日志本身绝不能把业务搞挂。
 */
public final class Logs {

    /** 内存里保留多少条 */
    private static final int CAPACITY = 600;

    /** 文件超过多大就砍掉一半，防止无限长 */
    private static final long MAX_BYTES = 256 * 1024L;

    private static final LinkedList<Line> MEM = new LinkedList<Line>();

    private static final SimpleDateFormat HMS =
            new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final SimpleDateFormat FULL =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private static File file;
    private static boolean writing = false;

    private Logs() {
    }

    /** 一条日志 */
    public static final class Line {
        public final long ts;
        public final String tag;
        public final String text;

        Line(long ts, String tag, String text) {
            this.ts = ts;
            this.tag = tag;
            this.text = text;
        }

        public String stamp() {
            return HMS.format(new Date(ts));
        }

        public String full() {
            return FULL.format(new Date(ts)) + "  [" + tag + "]  " + text;
        }
    }

    /** 绑定文件位置（Application 启动时调一次就够，重复调无副作用） */
    public static void init(Context c) {
        if (file != null || c == null) {
            return;
        }
        try {
            File dir = c.getCacheDir();
            File f = new File(dir, "xiaowo.log");
            if (f.exists() && f.length() > MAX_BYTES) {
                File old = new File(dir, "xiaowo.old.log");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
            file = f;
        } catch (Throwable ignore) {
        }
    }

    public static void i(String tag, String text) {
        add(tag, text);
    }

    public static void e(String tag, String text) {
        add(tag, "⚠ " + text);
    }

    public static void add(String tag, String text) {
        if (text == null) {
            text = "";
        }
        Line line = new Line(System.currentTimeMillis(), tag == null ? "app" : tag, text);
        synchronized (MEM) {
            MEM.addLast(line);
            while (MEM.size() > CAPACITY) {
                MEM.removeFirst();
            }
        }
        append(line);
    }

    private static void append(Line line) {
        if (file == null || writing) {
            return;
        }
        writing = true;
        try {
            FileWriter w = new FileWriter(file, true);
            try {
                w.write(line.full());
                w.write('\n');
            } finally {
                w.close();
            }
        } catch (Throwable ignore) {
        } finally {
            writing = false;
        }
    }

    /** 最新的在前（页面按时间倒序显示，最关心的在最上面） */
    public static List<Line> recent(int max) {
        List<Line> out = new ArrayList<Line>();
        synchronized (MEM) {
            int n = MEM.size();
            for (int i = n - 1; i >= 0 && out.size() < max; i--) {
                out.add(MEM.get(i));
            }
        }
        return out;
    }

    public static List<Line> all() {
        return recent(CAPACITY);
    }

    public static void clear() {
        synchronized (MEM) {
            MEM.clear();
        }
        try {
            if (file != null && file.exists()) {
                file.delete();
            }
        } catch (Throwable ignore) {
        }
    }

    /** 导出成一段纯文本（复制到剪贴板 / 分享给开发者） */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("小窝日志 ").append(FULL.format(new Date())).append('\n');
        for (Line l : all()) {
            sb.append(l.full()).append('\n');
        }
        return sb.toString();
    }
}
