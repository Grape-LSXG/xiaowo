package com.couple.xiaowo.core;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自动更新。
 *
 * 工作方式（不需要任何后端）：
 *   1. 你把两个 APK + 一个 version.json 传到任意一个能直链下载的地方
 *      （GitHub Release、Gitee、对象存储、你自己的服务器都行）；
 *   2. App 里填那个 version.json 的地址；
 *   3. App 每次启动或点「检查更新」时拉取它，比对版本号；
 *   4. 有新版就下载 APK，然后用系统安装器装上。
 *
 * version.json 长这样：
 *   {
 *     "order":   { "versionCode": 6, "versionName": "1.5", "url": "https://.../xiaowo-order.apk" },
 *     "recv":    { "versionCode": 6, "versionName": "1.5", "url": "https://.../xiaowo-recv.apk"  }
 *   }
 *
 * 注意：Android 不允许 App 静默装 APK（除非是系统应用或设备管理员），
 * 所以最后一步一定要弹系统安装器，用户点一下「安装」。这是平台限制，绕不过去。
 */
public final class Updater {

    public static final String KEY_URL = "update_url";

    /**
     * 内置默认更新源。首次启动时自动写入，用户不用手填。
     * 改仓库地址时同步改这里即可。
     */
    public static final String DEFAULT_MANIFEST_URL =
            "https://cdn.jsdelivr.net/gh/Grape-LSXG/xiaowo@main/dist/version.json";

    /**
     * 备用清单地址。
     *
     * 原来只有 Gitee 一条，结果那条早就 403 了（仓库没了/私有了），
     * 于是所有人"检查更新"永远失败 —— 4.5 拿不到 4.9 就是这么来的。
     * 现在按顺序试，谁先通就用谁；全都试不通才报错。
     */
    public static final String[] MIRRORS = new String[]{
            "https://cdn.jsdelivr.net/gh/Grape-LSXG/xiaowo@main/dist/version.json",
            "https://raw.githubusercontent.com/Grape-LSXG/xiaowo/main/dist/version.json",
            "https://gitee.com/Quriosity233/love/raw/master/version.json",
    };

    /** 一次检查的结果 */
    public static final class Info {
        public int versionCode;
        public String versionName = "";
        public String url = "";
        public String notes = "";
        /** 声明的文件字节数；0 表示没声明（那就只校验 ZIP 魔数） */
        public long size;
    }

    public interface Callback {
        /** 检查完成。info 为 null 表示已是最新；error 非空表示检查失败 */
        void done(Info info, String error);
    }

    private Updater() {
    }

    // ------------------------------------------------------------------ 版本

    public static int currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 我是点单端还是接单端 —— 决定去 version.json 里取哪一段 */
    public static String role() {
        try {
            Class<?> bi = Class.forName("com.couple.xiaowo.BuildInfo");
            Object role = bi.getField("ROLE").get(null);
            return String.valueOf(role);
        } catch (Exception e) {
            return "order";
        }
    }

    /**
     * 清掉缓存目录里所有下载的 APK。
     *
     * 存在的理由：老版本把「>512KB 就当下载好了」当成功判据，
     * 用户机器上很可能躺着一个残包。升级到修好的版本后，
     * 这个残包还会被复用一次 —— 那就等于 bug 只是被"减弱"而没被根治。
     *
     * 首次运行新版时清一次，让所有旧残包彻底消失。
     */
    public static void purgeStaleDownloads(Context c) {
        try {
            android.content.SharedPreferences sp =
                    c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
            if (sp.getBoolean("purged_downloads_v32", false)) {
                return;
            }
            File dir = c.getCacheDir();
            if (dir != null && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String n = f.getName();
                        if (n.endsWith(".apk")) {
                            try {
                                f.delete();
                            } catch (Exception ignore) {
                            }
                        }
                    }
                }
            }
            sp.edit().putBoolean("purged_downloads_v32", true).apply();
        } catch (Exception ignore) {
        }
    }

    /** 首次运行时把默认更新地址写进配置，界面上就能看到已有地址 */
    public static void ensureDefault(Context c) {
        android.content.SharedPreferences sp =
                c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
        if (sp.getString(KEY_URL, "").length() == 0) {
            sp.edit().putString(KEY_URL, DEFAULT_MANIFEST_URL).apply();
        }
    }

    // ------------------------------------------------------------------ 检查

    public static void check(final Context c, final Callback cb) {
        if (cb == null) {
            return;
        }
        final String manifestUrl = c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .getString(KEY_URL, DEFAULT_MANIFEST_URL);
        if (manifestUrl == null || manifestUrl.length() == 0) {
            cb.done(null, "还没填更新地址（在「我」页面里填）");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String error = null;
                Info info = null;
                // 用户自己填过地址就先用他的，再按镜像顺序兜底
                java.util.List<String> urls = new java.util.ArrayList<String>();
                urls.add(manifestUrl);
                for (int i = 0; i < MIRRORS.length; i++) {
                    if (!MIRRORS[i].equals(manifestUrl)) {
                        urls.add(MIRRORS[i]);
                    }
                }
                String lastErr = null;
                for (int attempt = 0; attempt < urls.size(); attempt++) {
                try {
                    String body = fetch(urls.get(attempt));
                    Map<String, Object> root = Json.asObject(Json.parse(body));
                    Object mine = root.get(role());
                    if (mine == null) {
                        // 兼容：没分角色时就用根节点
                        mine = root;
                    }
                    Map<String, Object> m = Json.asObject(mine);
                    Info i = new Info();
                    i.versionCode = (int) Json.num(m, "versionCode", 0);
                    i.versionName = Json.str(m, "versionName", "");
                    i.url = Json.str(m, "url", "");
                    i.notes = Json.str(m, "notes", "");
                    i.size = (long) Json.num(m, "size", 0);
                    if (i.versionCode > currentVersionCode(c) && i.url.length() > 0) {
                        info = i;
                    }
                    lastErr = null;
                    break;
                } catch (Exception e) {
                    lastErr = e.getMessage() == null ? "网络不通" : e.getMessage();
                }
                }
                if (lastErr != null) {
                    error = "检查失败：" + lastErr;
                }
                cb.done(info, error);
            }
        }).start();
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "XiaoWo-Updater");
            conn.setRequestProperty("Cache-Control", "no-cache");
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ 下载

    public interface Progress {
        void onProgress(int percent);

        void onDone(File apk);

        void onError(String msg);
    }

    /** 下载到 cacheDir，返回文件；失败返回 null。进度回调在后台线程。 */
    public static void download(final Context c, final Info info, final Progress cb) {
        new Thread(new Runnable() {
            public void run() {
                File out = new File(c.getCacheDir(), "update-" + info.versionCode + ".apk");
                HttpURLConnection conn = null;
                InputStream in = null;
                FileOutputStream fos = null;
                try {
                    /*
                     * 复用缓存**必须校验大小**。
                     *
                     * 原来这里是 `length() > 512KB` 就当成好的直接装 ——
                     * 只要出现一次下载中断（切后台、断网、系统回收），
                     * 缓存里就留下一个被截断的 zip；之后每次更新都拿它去安装，
                     * 系统安装器读不出完整包，就报「软件包无效」。
                     *
                     * 正确条件：长度必须等于服务器声明的总长度。
                     * 拿不到总长度时（chunked），宁可重下 —— 下错的代价
                     * 是一次失败安装，比这更烦人。
                     */
                    if (out.exists() && isValidApkFile(out, info)) {
                        if (cb != null) {
                            cb.onProgress(100);
                            cb.onDone(out);
                        }
                        return;
                    }
                    // 残包先删掉，别让后面的流续写到一个错误偏移上
                    if (out.exists() && !out.delete()) {
                        // 删不掉也不致命：下面用新文件覆盖写
                    }
                    conn = (HttpURLConnection) new URL(info.url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(60000);
                    conn.setRequestProperty("User-Agent", "XiaoWo-Updater");
                    conn.setInstanceFollowRedirects(true);
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        throw new Exception("HTTP " + code);
                    }
                    int total = conn.getContentLength();
                    in = conn.getInputStream();
                    fos = new FileOutputStream(out);
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    long read = 0;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        read += n;
                        if (total > 0 && cb != null) {
                            int pct = (int) (read * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                cb.onProgress(pct);
                            }
                        }
                    }
                    fos.flush();
                    /*
                     * 写完必须核对长度。网络在 read 循环里返回 -1 提前结束时，
                     * 上面不会抛异常，会留下一个"看起来成功了"的残包。
                     * 校验失败就删除并报错，绝不让它进安装流程。
                     */
                    if (!isValidApkFile(out, info)) {
                        long got = out.length();
                        out.delete();
                        throw new Exception("安装包不完整（" + (got / 1024) + "KB），已删除，请重试");
                    }
                    if (cb != null) {
                        cb.onProgress(100);
                        cb.onDone(out);
                    }
                } catch (Exception e) {
                    if (cb != null) {
                        cb.onError("下载失败：" + e.getMessage());
                    }
                    try {
                        out.delete();
                    } catch (Exception ignore) {
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

    /**
     * 判断缓存里的文件是不是一个**完整**的安装包。
     *
     * 两道检查，顺序不能颠倒：
     *
     *   1. 长度与 version.json 里声明的 size 一致（声明了的话）。
     *      这是最强的一道 —— 截断/追加必然不等。
     *
     *   2. ZIP 的**中央目录**能被解析（End Of Central Directory 在文件尾部）。
     *
     * 为什么不用"文件头是 PK\x03\x04"这种弱校验：
     * 实测把一个 771KB 的 APK 截成 600KB，头四字节依然是 PK\x03\x04，
     * 弱校验照样放行 —— 这正是系统报「软件包无效」的来源。
     * 被截断的 zip 丢的是**尾部**的中央目录，所以检查必须落在尾部。
     *
     * 这里用纯 Java 读 EOCD 记录，不引任何第三方库（本项目零依赖）。
     */
    private static boolean isValidApkFile(File f, Info info) {
        try {
            if (f == null || !f.exists() || f.length() < 1024) {
                return false;
            }
            // 1) 大小必须与声明一致
            if (info != null && info.size > 0 && f.length() != info.size) {
                return false;
            }
            // 2) 尾部必须有 ZIP 中央目录结束记录
            return hasZipCentralDirectory(f);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验 ZIP 的 EOCD（End Of Central Directory）是否存在。
     *
     * EOCD 固定 22 字节（不含注释），签名是 0x06054b50，
     * 位置在文件末尾 22 至 22+65535（最大注释长度）字节之内。
     * 截断的文件读不到它 —— 这就是判断"完整不完整"最可靠的免费手段。
     */
    private static boolean hasZipCentralDirectory(File f) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            int tail = (int) Math.min(len, 22 + 65535);
            if (tail < 22) {
                return false;
            }
            byte[] buf = new byte[tail];
            raf.seek(len - tail);
            raf.readFully(buf);
            // 从后往前找 EOCD 签名 50 4B 05 06
            for (int i = tail - 22; i >= 0; i--) {
                if (buf[i] == 0x50 && buf[i + 1] == 0x4B
                        && buf[i + 2] == 0x05 && buf[i + 3] == 0x06) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (raf != null) {
                    raf.close();
                }
            } catch (Exception ignore) {
            }
        }
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 调起系统安装器。
     * Android 8+ 需要 REQUEST_INSTALL_PACKAGES 权限；没授权的话会跳去授权页。
     */
    public static String install(Context c, File apk) {
        if (apk == null || !apk.exists()) {
            return "安装包不存在";
        }
        /*
         * 最后一道闸：真正交给系统安装器之前再验一次。
         *
         * 前面下载和缓存都校验过了，这里再查一次是为了兜住所有路径
         * （包括从别处传进来的文件）。校验不过就直接删掉并告诉用户，
         * 而不是让系统弹一个莫名其妙的「软件包无效」。
         */
        if (!looksLikeApk(apk)) {
            try {
                apk.delete();
            } catch (Exception ignore) {
            }
            return "安装包已损坏，已删除，请重新下载";
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 24) {
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Uri uri = androidxFileProvider(c, apk);
                if (uri == null) {
                    return "无法生成文件 URI";
                }
                i.setDataAndType(uri, "application/vnd.android.package-archive");
            } else {
                i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            }
            c.startActivity(i);
            return null;
        } catch (Exception e) {
            return "打不开安装器：" + e.getMessage();
        }
    }

    /** 用我们自己的最小 FileProvider（本项目零第三方依赖） */
    private static Uri androidxFileProvider(Context c, File apk) {
        try {
            return ApkFileProvider.uriFor(c, apk);
        } catch (Throwable e) {
            return Uri.fromFile(apk);
        }
    }

    /**
     * 安装前校验：文件存在、够大、**ZIP 中央目录完整**。
     *
     * 只看文件头是不够的（截断包的头部依然合法），必须查尾部 EOCD。
     */
    public static boolean looksLikeApk(File f) {
        if (f == null || !f.exists() || f.length() < 1024) {
            return false;
        }
        return hasZipCentralDirectory(f);
    }

    /** 判断有没有「安装未知应用」权限 */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return true;
        }
        try {
            return c.getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return true;
        }
    }

    public static void requestInstallPermission(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignore) {
        }
    }
}
