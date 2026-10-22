package com.couple.xiaowo.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 从 GitHub Releases 检索并下载更新。
 *
 * 为什么走 GitHub 而不是 raw 直链：
 *   实测这台机器上 **raw.githubusercontent.com 不通（HTTP 000）**，
 *   而 api.github.com 和 github.com 都是通的。
 *   所以：
 *     · 查版本 → api.github.com/repos/<owner>/<repo>/releases/latest
 *     · 下 APK → 同一个 API 的 assets 子资源，带 Accept: application/octet-stream
 *                （不要用 browser_download_url 拼，那个域名会被墙挡住）
 *
 * 为什么这个方案对"点单端自动更新"更好：
 *   · 点单端不需要连上接单端就能更新 —— 只要联网访问 GitHub
 *   · 不受"对方在后台被冻结""异地中转传不动 6MB"这些限制
 *   · 有版本记录，出问题能回退
 *
 * 免 token：公开仓库的 releases 接口不需要认证，
 * 只有 60 次/小时的匿名配额。一天开几次 App 完全够。
 */
public final class GithubRelease {

    /**
     * 默认仓库。
     * 注意：两个 App 都从这里取，靠 tag 里的关键字区分是哪个包。
     */
    public static final String DEFAULT_REPO = "Grape-LSXG/xiaowo";   // 已实测可读可下

    public static final String KEY_REPO = "gh_repo";
    public static final String KEY_ON = "gh_update_on";

    private static final String UA = "XiaoWo-App-Updater";

    private GithubRelease() {
    }

    public static String repo(Context c) {
        return sp(c).getString(KEY_REPO, DEFAULT_REPO);
    }

    public static void setRepo(Context c, String repo) {
        sp(c).edit().putString(KEY_REPO, repo).apply();
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean(KEY_ON, true);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_ON, on).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
    }

    public static final class Release {
        public int versionCode;
        public String versionName = "";
        public String tag = "";
        public String notes = "";
        public String assetName = "";
        /** 真正的下载地址（API 的 asset url） */
        public String assetApiUrl = "";
        /** browser_download_url，作为 API 路径不通时的备用下载地址 */
        public String assetDownUrl = "";
        public long size;
    }

    public interface Callback {
        /** latest 为 null 表示没有可用更新；error 非空表示失败 */
        void done(Release latest, String error);
    }

    /**
     * 查最新 release。
     *
     * 版本号从哪里来：**用 tag 里的数字**。
     * 约定 tag 形如 `order-v20` / `recv-v20`（v 后面是 versionCode）。
     * 这样不用额外维护 version.json，发布时打对 tag 就行。
     *
     * @param wantRecv true=接单端，false=点单端
     */
    public static void check(final Context c, final boolean wantRecv, final Callback cb) {
        if (cb == null) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    String repo = repo(c);
                    String body = getJson("https://api.github.com/repos/" + repo
                            + "/releases?per_page=20");
                    Object arr = Json.parse(body);
                    if (!(arr instanceof java.util.List)) {
                        cb.done(null, "GitHub 返回格式不对");
                        return;
                    }
                    java.util.List<?> list = (java.util.List<?>) arr;
                    Release best = null;
                    String wantPrefix = wantRecv ? "recv" : "order";
                    for (Object o : list) {
                        Map<String, Object> rel = Json.asObject(o);
                        String tag = Json.str(rel, "tag_name", "");
                        if (!tag.startsWith(wantPrefix)) {
                            continue;
                        }
                        int vc = versionFromTag(tag);
                        if (vc <= 0) {
                            continue;
                        }
                        // 只认 .apk 资产
                        String assetName = null;
                        String assetUrl = null;
                        String assetDown = null;
                        long size = 0;
                        Object assetsObj = rel.get("assets");
                        if (assetsObj instanceof java.util.List) {
                            for (Object a : (java.util.List<?>) assetsObj) {
                                Map<String, Object> am = Json.asObject(a);
                                String name = Json.str(am, "name", "");
                                if (name.endsWith(".apk")) {
                                    assetName = name;
                                    assetUrl = Json.str(am, "url", "");
                                    assetDown = Json.str(am, "browser_download_url", "");
                                    size = Json.num(am, "size", 0);
                                    break;
                                }
                            }
                        }
                        if (assetUrl == null || assetUrl.length() == 0) {
                            continue;
                        }
                        if (best == null || vc > best.versionCode) {
                            best = new Release();
                            best.versionCode = vc;
                            best.versionName = tag.substring(wantPrefix.length() + 1)
                                    .replace("-", "");
                            best.tag = tag;
                            best.notes = shorten(Json.str(rel, "body", ""), 300);
                            best.assetName = assetName;
                            best.assetApiUrl = assetUrl;
                            best.assetDownUrl = assetDown;
                            best.size = size;
                        }
                    }
                    int mine = currentVersionCode(c);
                    if (best == null) {
                        cb.done(null, null);
                    } else if (best.versionCode > mine) {
                        cb.done(best, null);
                    } else {
                        cb.done(null, null);
                    }
                } catch (Exception e) {
                    cb.done(null, "连不上 GitHub：" + e.getMessage());
                }
            }
        }).start();
    }

    /** 从 tag 里取 versionCode：`order-v20` → 20 */
    public static int versionFromTag(String tag) {
        if (tag == null) {
            return 0;
        }
        int dash = tag.lastIndexOf("v");
        if (dash < 0) {
            return 0;
        }
        String num = tag.substring(dash + 1).replaceAll("[^0-9].*$", "");
        try {
            return Integer.parseInt(num);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.replace("\r", "");
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String getJson(String url) throws Exception {
        // 网络不稳时多试几次（超时就重试，最多 3 次）
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", UA);
                // GitHub 要求显式声明接受的 API 版本
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new Exception("HTTP " + code);
                }
                return Net.readAll(conn.getInputStream());
            } catch (Exception e) {
                last = e;
                // 短暂等待后重试，给网络一点恢复时间
                try {
                    Thread.sleep(800L * (i + 1));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        throw last != null ? last : new Exception("网络异常");
    }

    /**
     * 下载 release 里的 APK。
     *
     * 用 API 的 asset url + `Accept: application/octet-stream`，
     * 会 302 到一个临时签名地址。不要去拼 browser_download_url ——
     * 那个走 github.com，在部分网络下不稳定，而 API 这条路实测更可靠。
     */
    public static void download(final Context c, final Release rel, final File out,
                                final DownloadCallback cb) {
        if (rel == null || rel.assetApiUrl == null || out == null) {
            if (cb != null) {
                cb.done(null, 0, "没有可下载的包");
            }
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                // 下载地址按优先级试：API asset url → browser_download_url
                // 实测某些网络下 API 的 302 目标（release-assets）不稳，
                // 此时 browser_download_url 走 github.com 反而是通的。
                String[] urls = new String[]{rel.assetApiUrl, rel.assetDownUrl};
                String lastErr = "下载失败";
                for (String url : urls) {
                    if (url == null || url.length() == 0) {
                        continue;
                    }
                    int tryCount = 2;   // 每个地址试两次，应对偶发网络抖动
                    for (int i = 0; i < tryCount; i++) {
                        String err = downloadOnce(url, rel.size, out, cb);
                        if (err == null) {
                            if (cb != null) {
                                cb.done(out, out.length(), null);
                            }
                            return;
                        }
                        lastErr = err;
                        // 第一次失败短暂等一下再试，给网络恢复一点时间
                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
                if (cb != null) {
                    cb.done(null, 0, lastErr);
                }
            }
        }).start();
    }

    /** 单次下载；成功返回 null，失败返回错误描述 */
    private static String downloadOnce(String url, long expectedSize, File out,
                                       DownloadCallback cb) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/octet-stream");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                return "HTTP " + code;
            }
            long total = Math.max(conn.getContentLength(), expectedSize);
            in = conn.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[32 * 1024];
            long got = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                got += n;
                if (total > 0 && cb != null) {
                    int pct = (int) (got * 100 / total);
                    if (pct != lastPct) {
                        lastPct = pct;
                        cb.onProgress(pct);
                    }
                }
            }
            fos.flush();
            /*
             * 校验完整性。
             *
             * 这里原来直接 return null（成功）。但网络在 read 循环里返回 -1
             * 提前结束时不会抛异常，会留下一个被截断的 APK —— 之后拿去安装
             * 就是系统报「软件包无效」。
             *
             * expectedSize 来自 release asset 的 size 字段（GitHub 一定给），
             * 所以这里可以硬校验。
             */
            long actual = out.length();
            if (expectedSize > 0 && actual != expectedSize) {
                out.delete();
                return "安装包不完整（" + (actual / 1024) + "KB / 应为 "
                        + (expectedSize / 1024) + "KB）";
            }
            return null;
        } catch (Exception e) {
            // 异常路径也要删掉残包，避免下次被误认为已下载
            try {
                out.delete();
            } catch (Exception ignore) {
            }
            return "网络异常：" + e.getClass().getSimpleName();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignore) {
            }
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception ignore) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void done(File apk, long bytes, String error);
    }

    public static int currentVersionCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }
}
