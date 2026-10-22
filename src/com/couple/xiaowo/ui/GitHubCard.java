package com.couple.xiaowo.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.core.GithubRelease;
import com.couple.xiaowo.core.Updater;

import java.io.File;

/**
 * 「从 GitHub 拿新版」。
 *
 * 为什么加这条：从接单端拿更新（PeerUpdateCard）有个绕不过去的限制 ——
 * 点单端必须**直连**上接单端才拿得到 6MB 的包。异地时中转传不动，
 * 于是就永远更新不了。
 *
 * GitHub Release 把这件事彻底解决了：
 *   · 只要点单端能上网（不需要连上接单端）就能更新
 *   · 不受"对方在后台被冻结"影响
 *   · 有版本记录，出问题能回退
 *
 * 查版本走 api.github.com（实测可用），
 * 下载走同源的 asset url（会 302 到 release-assets，那条路也实测可用）。
 * raw.githubusercontent.com 在这台机器上是不通的，所以刻意没走它。
 */
public final class GitHubCard {

    private GitHubCard() {
    }

    // ================================================================ 周期检查

    private static final android.os.Handler PERIODIC = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private static Runnable periodicTask;
    private static final long PERIOD_MS = 5 * 60 * 1000L;   // 每 5 分钟试一次
    private static final long FIRST_MS = 60 * 1000L;         // 首次 1 分钟后

    /** 周期检查当前绑定的 Activity（弱引用，避免把已销毁的界面按住不放） */
    private static java.lang.ref.WeakReference<Activity> periodicAct;
    private static boolean periodicIsRecv;

    /**
     * 启动周期检查：使用中每隔几分钟悄悄试一次 GitHub。
     * 只在有新版时才打断用户（直接下载安装），没新版静默。
     *
     * 用弱引用而不是直接捕获 Activity：主题切换 / 授权返回都会 recreate，
     * 旧的 Activity 已经 destroy，但静态 Handler 还在跑。捕获强引用会：
     *   · 泄漏整个旧界面（含 View 树）
     *   · 下载完成时 act.runOnUiThread 往一个已销毁的 Activity 上回调
     *
     * 现在每一轮都重新取当前 Activity；取不到（进程里已经没有界面）就跳过这一轮。
     */
    public static void startPeriodic(final Activity act, final boolean isRecv) {
        stopPeriodic();
        periodicAct = new java.lang.ref.WeakReference<Activity>(act);
        periodicIsRecv = isRecv;
        periodicTask = new Runnable() {
            public void run() {
                Activity cur = periodicAct == null ? null : periodicAct.get();
                if (cur != null && !cur.isFinishing()
                        && (Build.VERSION.SDK_INT < 17 || !cur.isDestroyed())) {
                    GitHubCard.run(cur, periodicIsRecv, null, null, false);
                }
                // 无论这一轮有没有跑，都继续排下一轮
                if (periodicTask == this) {
                    PERIODIC.postDelayed(this, PERIOD_MS);
                }
            }
        };
        PERIODIC.postDelayed(periodicTask, FIRST_MS);
    }

    public static void stopPeriodic() {
        Runnable t = periodicTask;
        periodicTask = null;
        if (t != null) {
            PERIODIC.removeCallbacks(t);
        }
        periodicAct = null;
    }

    public static View build(final Activity act, final boolean isRecv) {
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, Ui.dp(act, Ui.R_CARD)));
        card.setPadding(Ui.dpi(act, 14), Ui.dpi(act, 14), Ui.dpi(act, 14), Ui.dpi(act, 12));
        card.setElevation(Ui.dp(act, 2f));

        LinearLayout head = Ui.horizontal(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(act, "🚀 从 GitHub 更新", 14, Ui.TEXT, true));
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(Ui.chip(act, Ui.appVersion(act), Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 10));
        card.addView(head);

        final TextView status = Ui.tv(act, "点「检查更新」看看有没有新版本。", 10.5f,
                Ui.TEXT_SUB, false);
        status.setMaxLines(4);
        card.addView(status, top(act, 6));

        card.addView(Ui.tv(act,
                "不需要连上对方 —— 只要这台手机能上网就能更新。\n"
                        + "（比起「从对方那儿拿」，这条路异地也能用）",
                10, Ui.TEXT_MUTED, false), top(act, 4));

        final TextView btn = Ui.pill(act, "检查更新", Ui.PRIMARY, 0xFFFFFFFF,
                12, 18, 9, true);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                run(act, isRecv, status, btn, true);
            }
        });
        card.addView(btn, top(act, 10));

        // 仓库设置
        TextView repoRow = Ui.tv(act, "仓库：" + GithubRelease.repo(act), 10,
                Ui.PRIMARY_DEEP, false);
        repoRow.setPadding(0, Ui.dpi(act, 8), 0, 0);
        repoRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                editRepo(act, status);
            }
        });
        card.addView(repoRow);

        // 说明：启动自动检查是默认行为，不再提供开关
        card.addView(Ui.tv(act,
                "发现新版本时会提示你，确认后才会下载安装。",
                10, Ui.TEXT_MUTED, false), top(act, 4));
        return card;
    }

    private static void editRepo(final Activity act, final TextView status) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(Ui.dpi(act, 20), Ui.dpi(act, 8), Ui.dpi(act, 20), Ui.dpi(act, 8));
        box.addView(Ui.tv(act, "GitHub 仓库（owner/repo）", 11, Ui.PRIMARY_DEEP, true));
        final EditText et = new EditText(act);
        et.setHint("Grape-LSXG/xiaowo");
        et.setText(GithubRelease.repo(act));
        box.addView(et);
        box.addView(Ui.tv(act,
                "release 的 tag 约定：\n"
                        + "· 点单端用 order-v<版本号>，例如 order-v20\n"
                        + "· 接单端用 recv-v<版本号>，例如 recv-v20\n"
                        + "版本号就是 versionCode，越大越新。",
                10, Ui.TEXT_SUB, false), top(act, 8));
        DialogKit.custom(act, "设置仓库", box, "保存", new Runnable() {
            public void run() {
                String s = et.getText().toString().trim();
                if (s.length() > 0) {
                    // 容忍粘贴完整 URL
                    s = s.replace("https://github.com/", "").replace("http://github.com/", "");
                    if (s.endsWith("/")) {
                        s = s.substring(0, s.length() - 1);
                    }
                    GithubRelease.setRepo(act, s);
                    status.setText("仓库已设为 " + s);
                }
            }
        });
    }

    /** 防止 onResume 连续触发时并发检查 */
    private static volatile boolean resumeChecking = false;

    /**
     * 回到前台时静默检查一次更新。
     *
     * 和 run() 的区别：
     *   · 不依赖界面上的 status/btn（onResume 时那两个引用可能是旧的）
     *   · 有并发保护，切来切去不会同时发多个请求
     *   · 发现新版才提示，没新版完全不打扰
     */
    public static void checkOnResume(final android.app.Activity act, final boolean isRecv) {
        if (act == null || act.isFinishing()) {
            return;
        }
        if (resumeChecking) {
            return;
        }
        resumeChecking = true;
        GithubRelease.check(act, isRecv, new GithubRelease.Callback() {
            public void done(final GithubRelease.Release rel, final String error) {
                resumeChecking = false;
                // 检查失败：静默忽略（网络抖动是常态，下一轮再试）
                if (error != null || rel == null) {
                    return;
                }
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (act.isFinishing()) {
                            return;
                        }
                        Toast2.info(act, "发现新版 " + rel.versionName + "，正在后台下载…");
                        downloadAndInstall(act, rel, null);
                    }
                });
            }
        });
    }

    /**
     * 检查并（可选）下载。
     *
     * 需求变更：发现新版本后**直接下载并安装**，不再弹确认框。
     * 所以 interactive 参数现在只影响"进度反馈显示在哪"，不再改变流程。
     */
    public static void run(final Activity act, final boolean isRecv,
                           final TextView status, final TextView btn,
                           final boolean interactive) {
        if (btn != null) {
            btn.setEnabled(false);
            btn.setText("检查中…");
        }
        if (status != null && interactive) {
            status.setTextColor(Theme.adapt(Ui.TEXT_SUB));
            status.setText("正在问 GitHub…");
        }
        GithubRelease.check(act, isRecv, new GithubRelease.Callback() {
            public void done(final GithubRelease.Release rel, final String error) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (btn != null) {
                            btn.setEnabled(true);
                            btn.setText("检查更新");
                        }
                        if (error != null) {
                            if (status != null && interactive) {
                                status.setTextColor(Theme.adapt(Ui.WARN));
                                status.setText(error);
                            } else if (status != null) {
                                // 静默模式但有状态栏可写：记一句，别让用户以为没检查
                                status.setTextColor(Theme.adapt(Ui.WARN));
                                status.setText(error);
                            }
                            /*
                             * 自动检查失败不弹 Toast —— 网络抖动是常态，
                             * 每次开 App 都弹一次会很吵。下一轮周期检查会再试。
                             */
                            return;
                        }
                        if (rel == null) {
                            if (status != null && interactive) {
                                status.setTextColor(Theme.adapt(Ui.SUCCESS));
                                status.setText("已经是最新版（" + Ui.appVersion(act) + "）");
                            }
                            if (interactive) {
                                Toast2.ok(act, "已是最新版");
                            }
                            return;
                        }
                        /*
                         * 发现新版：直接下载并安装。
                         *
                         * 静默模式（status == null，自动检查走这条）以前什么都不显示 ——
                         * 用户在下载完成、系统安装器弹出之前完全无感；
                         * 一旦下载失败，就成了"自动更新好像没工作"。
                         * 现在至少给一条 Toast，让整个过程可感知。
                         */
                        if (status != null) {
                            status.setTextColor(Theme.adapt(Ui.PRIMARY_DEEP));
                            status.setText("发现新版 " + rel.versionName
                                    + "（" + (rel.size / 1024) + " KB），开始下载…");
                        } else {
                            Toast2.info(act, "发现新版 " + rel.versionName + "，正在后台下载…");
                        }
                        downloadAndInstall(act, rel, status);
                    }
                });
            }
        });
    }

    private static void downloadAndInstall(final Activity act,
                                           final GithubRelease.Release rel,
                                           final TextView status) {
        final File out = new File(act.getCacheDir(), "gh-" + rel.versionCode + ".apk");
        /*
         * 同名残包先删掉。
         *
         * 文件名只含 versionCode（gh-34.apk），如果上一次下载到一半中断，
         * 这个文件会留在缓存里。GithubRelease.download 现在虽然会校验长度，
         * 但**先从干净状态开始**永远最省事 —— 也让重试行为可预期。
         */
        try {
            if (out.exists()) {
                out.delete();
            }
        } catch (Exception ignore) {
        }
        GithubRelease.download(act, rel, out, new GithubRelease.DownloadCallback() {
            public void onProgress(final int pct) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (status != null) {
                            status.setTextColor(Theme.adapt(Ui.PRIMARY_DEEP));
                            status.setText("正在下载 " + pct + "%…");
                        }
                    }
                });
            }

            public void done(final File apk, final long bytes, final String error) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (error != null || apk == null) {
                            if (status != null) {
                                status.setTextColor(Theme.adapt(Ui.DANGER));
                                status.setText(error == null ? "下载失败" : error);
                            }
                            Toast2.warn(act, error == null ? "下载失败" : error);
                            return;
                        }
                        if (status != null) {
                            status.setTextColor(Theme.adapt(Ui.SUCCESS));
                            status.setText("下载完成（" + (bytes / 1024) + " KB），准备安装");
                        }
                        if (!Updater.canInstall(act)) {
                            Toast2.warn(act, "请允许「安装未知应用」后再试");
                            Updater.requestInstallPermission(act);
                            return;
                        }
                        String err = Updater.install(act, apk);
                        if (err != null && err.length() > 0) {
                            Toast2.warn(act, err);
                            // 装不上就把包删掉，别让它下次被当成"已下载"
                            try {
                                apk.delete();
                            } catch (Exception ignore) {
                            }
                        } else {
                            /*
                             * 已经交给系统安装器了。这里**不能**立刻删 ——
                             * 安装器还要读这个文件。等一会儿再清，
                             * 而且只清旧的（安装完通常会重启进程，不重启时兜底）。
                             */
                            final File toClean = apk;
                            PERIODIC.postDelayed(new Runnable() {
                                public void run() {
                                    try {
                                        if (toClean.exists()) {
                                            toClean.delete();
                                        }
                                    } catch (Exception ignore) {
                                    }
                                }
                            }, 120000L);
                        }
                    }
                });
            }
        });
    }

    private static LinearLayout.LayoutParams top(Context c, float dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dpi(c, dp);
        return lp;
    }
}
