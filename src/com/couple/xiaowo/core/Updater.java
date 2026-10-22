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
            "https://gitee.com/Quriosity233/love/raw/master/version.json";

    /** 一次检查的结果 */
    public static final class Info {
        public int versionCode;
        public String versionName = "";
        public String url = "";
        public String notes = "";
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
                try {
                    String body = fetch(manifestUrl);
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
                    if (i.versionCode > currentVersionCode(c) && i.url.length() > 0) {
                        info = i;
                    }
                } catch (Exception e) {
                    error = "检查失败：" + e.getMessage();
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
                    if (out.exists() && out.length() > 1024 * 512) {
                        if (cb != null) {
                            cb.onProgress(100);
                            cb.onDone(out);
                        }
                        return;
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

    // ------------------------------------------------------------------ 安装

    /**
     * 调起系统安装器。
     * Android 8+ 需要 REQUEST_INSTALL_PACKAGES 权限；没授权的话会跳去授权页。
     */
    public static String install(Context c, File apk) {
        if (apk == null || !apk.exists()) {
            return "安装包不存在";
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
