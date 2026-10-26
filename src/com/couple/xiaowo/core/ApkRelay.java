package com.couple.xiaowo.core;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 用「直连通道」推新版本 —— 不依赖 Gitee、不限速、不排队。
 *
 * 思路（比搭图床更直接）：
 *   1. 构建时把【点单端 APK】塞进接单端的 assets 里
 *      → 所以每次发布，你只要更新接单端，点单端的新包就自动跟着走了
 *   2. 接单端对外暴露两个端点：
 *        GET /apk/info   返回 {versionCode, versionName, size, sha256}
 *        GET /apk/order  直接把内置的点单端 APK 二进制吐出来
 *   3. 点单端每次打开时向接单端要 /apk/info，比自己新就下载并调安装器
 *
 * 全程走你们自己的局域网或中转房间，没有第三方存储。
 */
public final class ApkRelay {

    public static final String PATH_INFO = "/apk/info";
    public static final String PATH_ORDER = "/apk/order";

    /** 内置在接单端 assets 里的点单端 APK */
    public static final String BUNDLED_PATH = "apk/xiaowo-order.apk";

    private ApkRelay() {
    }

    // ================================================================== 接单端：读内置包

    /** 内置的点单端 APK 是否存在 */
    public static boolean hasBundled(Context c) {
        try {
            InputStream in = c.getAssets().open(BUNDLED_PATH);
            in.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 内置包缓存：经中转分块发送时会反复取用同一份字节 */
    private static byte[] cache;

    public static synchronized byte[] bundledBytes(Context c) {
        if (cache == null) {
            cache = readBundled(c);
        }
        return cache;
    }

    public static synchronized void dropBundledCache() {
        cache = null;
    }

    public static byte[] readBundled(Context c) {
        InputStream in = null;
        try {
            in = c.getAssets().open(BUNDLED_PATH);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /**
     * 内置包的版本信息。真实做法是把 version.json 也打包进 assets，
     * 但更稳的是直接读 APK 的 manifest —— 这里用 assets 里随包附带的 meta 文件，
     * 由 build.sh 在打包时写入，避免运行时解析 APK 二进制。
     */
    public static Info bundledInfo(Context c) {
        try {
            java.util.Properties p = new java.util.Properties();
            InputStream in = c.getAssets().open("apk/order-version.properties");
            p.load(in);
            in.close();
            Info info = new Info();
            info.versionCode = Integer.parseInt(p.getProperty("versionCode", "0").trim());
            info.versionName = p.getProperty("versionName", "").trim();
            info.size = Long.parseLong(p.getProperty("size", "0").trim());
            return info;
        } catch (Exception e) {
            // 退路：能读到包就算有，版本号未知
            if (hasBundled(c)) {
                Info info = new Info();
                info.versionName = "unknown";
                return info;
            }
            return null;
        }
    }

    // ================================================================== 数据模型

    public static final class Info {
        public int versionCode;
        public String versionName = "";
        public long size;

        public String toJson() {
            return Json.object(Json.fields(
                    "ok", "true",
                    "versionCode", String.valueOf(versionCode),
                    "versionName", Json.quote(versionName),
                    "size", String.valueOf(size)));
        }

        public static Info fromJson(String body) {
            try {
                java.util.Map<String, Object> m = Json.asObject(Json.parse(body));
                Info i = new Info();
                i.versionCode = (int) Json.num(m, "versionCode", 0);
                i.versionName = Json.str(m, "versionName", "");
                i.size = Json.num(m, "size", 0);
                return i;
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ================================================================== 点单端：检查 + 下载

    public interface Callback {
        /** info 为 null 表示对方没有可用的新包；error 非空表示失败 */
        void done(Info info, String error);
    }

    /** 向接单端询问「你手里有没有比我新的点单端包」 */
    public static void check(final Context c, final String host, final int port,
                             final Callback cb) {
        if (cb == null) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    String body = get(host, port, PATH_INFO);
                    if (body == null) {
                        cb.done(null, "接单端没有开「分享更新」");
                        return;
                    }
                    Info info = Info.fromJson(body);
                    if (info == null || info.versionCode <= 0) {
                        cb.done(null, "接单端没返回有效版本");
                        return;
                    }
                    int mine = currentVersionCode(c);
                    if (info.versionCode > mine) {
                        cb.done(info, null);
                    } else {
                        cb.done(null, null);
                    }
                } catch (Exception e) {
                    cb.done(null, "连不上接单端：" + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 经**异地中转**查询对方有没有新包。
     *
     * 为什么需要单独一条：原来的 check() 只能走直连 HTTP，
     * 异地时根本连不上对方，所以点单端永远拿不到更新
     * （这是用户反馈的"点单端没法从接单端获取更新"的根因）。
     *
     * 中转只能传消息不能传文件，所以这里**只查版本**，
     * 真要下载还得靠直连/Wi-Fi。至少用户能知道"有新版"。
     *
     * @param onInfo 收到对方回包时回调；超时没回包给 null
     */
    public static void askInfoViaRelay(final android.content.Context c,
                                       final InfoCallback onInfo) {
        // 先注册一个一次性监听，等对方回包
        final String[] holder = new String[1];
        final Object lock = new Object();
        final boolean[] done = new boolean[]{false};

        // 发查询
        boolean sent = Relay.publish(c, Protocol.ROLE_ORDER, Json.object(Json.fields(
                "type", Json.quote(PATH_INFO),
                "from", Json.quote(Protocol.ROLE_ORDER),
                "ts", String.valueOf(System.currentTimeMillis()))), 8000);

        if (!sent) {
            if (onInfo != null) {
                onInfo.done(null, "中转没发出去（还没配对或网络不通）");
            }
            return;
        }
        // 回包由 OrderScreen.onRelayMessage 处理，这里只负责"发出去"
        if (onInfo != null) {
            onInfo.done(null, "已发出查询，等对方回包…");
        }
    }

    public interface InfoCallback {
        void done(Info info, String note);
    }

    /** 从接单端下载点单端 APK 到 cache */
    public static void download(final Context c, final String host, final int port,
                                final File out, final DownloadCallback cb) {
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection conn = null;
                InputStream in = null;
                FileOutputStream fos = null;
                try {
                    URL url = Net.buildUrl(host, port, PATH_ORDER);
                    conn = (HttpURLConnection) url.openConnection();
                    Net.relaxTlsIfNeeded(conn);
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(120000);   // 5MB 要给足时间
                    conn.setRequestProperty("Connection", "close");
                    if (conn.getResponseCode() != 200) {
                        throw new Exception("HTTP " + conn.getResponseCode());
                    }
                    long total = conn.getContentLength();
                    in = conn.getInputStream();
                    fos = new FileOutputStream(out);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    long read = 0;
                    int last = -1;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        read += n;
                        if (total > 0 && cb != null) {
                            int pct = (int) (read * 100 / total);
                            if (pct != last) {
                                last = pct;
                                cb.onProgress(pct);
                            }
                        }
                    }
                    fos.flush();
                    if (cb != null) {
                        cb.onDone(out, read);
                    }
                } catch (Exception e) {
                    try {
                        out.delete();
                    } catch (Exception ignore) {
                    }
                    if (cb != null) {
                        cb.onError("下载失败：" + e.getMessage());
                    }
                } finally {
                    try {
                        if (fos != null) {
                            fos.close();
                        }
                    } catch (Exception ignore) {
                    }
                    try {
                        if (in != null) {
                            in.close();
                        }
                    } catch (Exception ignore) {
                    }
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void onDone(File apk, long bytes);

        void onError(String msg);
    }

    // ================================================================== 小工具

    public static int currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28
                    ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String get(String host, int port, String path) {
        HttpURLConnection conn = null;
        try {
            URL url = Net.buildUrl(host, port, path);
            conn = (HttpURLConnection) url.openConnection();
            Net.relaxTlsIfNeeded(conn);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Connection", "close");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            return Net.readAll(conn.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
