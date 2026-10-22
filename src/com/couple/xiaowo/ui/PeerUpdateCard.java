package com.couple.xiaowo.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.core.ApkRelay;
import com.couple.xiaowo.core.Relay;
import com.couple.xiaowo.core.Updater;

import java.io.File;

/**
 * 「从接单端拿新版」—— 点单端专用的更新卡。
 *
 * 和 Gitee 方案的区别：
 *   · 不走第三方服务，直接用你们自己的局域网通道
 *   · 不限速、没缓存延迟，5MB 几秒钟就传完
 *   · 接单端更新 = 点单端也跟着有新版（因为包内置在接单端里）
 */
public final class PeerUpdateCard {

    public static final String KEY_AUTO = "peer_auto_update";

    /** 是否允许「打开 App 时自动检查」 */
    public static boolean autoEnabled(Context c) {
        return c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO, true);
    }

    public static void setAuto(Context c, boolean on) {
        c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO, on).apply();
    }

    public static View build(final Activity act, final String peerHost, final int peerPort) {
        final SharedPreferences sp = act.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);

        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(act, Ui.R_CARD)));
        card.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 12));
        card.setElevation(dp(act, 2f));

        LinearLayout head = Ui.horizontal(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(act, "📡 从张老师那儿拿新版", 14, Ui.TEXT, true));
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(Ui.chip(act, Ui.appVersion(act), Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 10));
        card.addView(head);

        final TextView status = Ui.tv(act, "点「检查」看看张老师那边有没有新包。", 10.5f, Ui.TEXT_SUB, false);
        status.setMaxLines(3);
        card.addView(status, top(act, 6));

        card.addView(Ui.tv(act,
                "张老师更新了接单端，新版本就跟着到了他手机里；\n"
                        + "你这边打开 App 时会自动问一句，有新版就装。",
                10, Ui.TEXT_MUTED, false), top(act, 8));

        final TextView checkBtn = Ui.pill(act, "🔄 检查并更新", Ui.PRIMARY, 0xFFFFFFFF, 12.5f, 20, 10, true);
        checkBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (peerHost == null || peerPort <= 0) {
                    status.setTextColor(Theme.adapt(Ui.WARN));
                    status.setText("还没连上张老师。先在「😊 我 → 连接设置」里填地址，或用自动发现。");
                    Toast2.warn(act, "先连上接单端");
                    return;
                }
                status.setTextColor(Theme.adapt(Ui.TEXT_SUB));
                status.setText("正在问张老师…");
                runCheck(act, peerHost, peerPort, status, checkBtn, true);
            }
        });
        card.addView(checkBtn, top(act, 10));

        // 自动检查开关
        final boolean[] on = {autoEnabled(act)};
        final LinearLayout toggle = Ui.horizontal(act);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(dp(act, 10), dp(act, 9), dp(act, 10), dp(act, 9));
        final TextView box = Ui.tv(act, on[0] ? "☑" : "☐", 15,
                on[0] ? Ui.PRIMARY : Ui.TEXT_MUTED, false);
        toggle.addView(box);
        TextView label = Ui.tv(act, "打开 App 时自动检查更新", 12, Ui.TEXT, false);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.weight = 1f;
        lLp.leftMargin = dp(act, 8);
        toggle.addView(label, lLp);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                on[0] = !on[0];
                setAuto(act, on[0]);
                box.setText(on[0] ? "☑" : "☐");
                box.setTextColor(on[0] ? Ui.PRIMARY : Ui.TEXT_MUTED);
                Toast2.ok(act, on[0] ? "已开启自动检查" : "已关闭自动检查");
            }
        });
        card.addView(toggle, top(act, 8));

        return card;
    }

    /** 检查 → 下载 → 安装，一整条流程 */
    public static void runCheck(final Activity act, String host, int port,
                                final TextView status, final TextView btn,
                                final boolean interactive) {
        if (btn != null) {
            btn.setEnabled(false);
            btn.setText("检查中…");
        }
        ApkRelay.check(act, host, port, new ApkRelay.Callback() {
            public void done(final ApkRelay.Info info, final String error) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (btn != null) {
                            btn.setEnabled(true);
                            btn.setText("🔄 检查并更新");
                        }
                        if (error != null) {
                            /*
                             * 直连失败 → 试试异地中转。
                             *
                             * 这是用户报的"点单端拿不到更新"的关键修复：
                             * 原来这里直连失败就直接报错结束，异地时永远走不到后面。
                             * 中转虽然传不了 6MB 的包，但能问到"有没有新版"。
                             */
                            if (Relay.enabled(act)) {
                                if (status != null) {
                                    status.setTextColor(Theme.adapt(Ui.WARN));
                                    status.setText("直连不通，改从异地中转查版本…");
                                }
                                askViaRelay(act, status, btn, interactive);
                                return;
                            }
                            if (status != null) {
                                status.setTextColor(Theme.adapt(Ui.WARN));
                                status.setText(error);
                            }
                            if (interactive) {
                                Toast2.warn(act, error);
                            }
                            return;
                        }
                        if (info == null) {
                            if (status != null) {
                                status.setTextColor(Theme.adapt(Ui.SUCCESS));
                                status.setText("已经是最新版啦（" + Ui.appVersion(act) + "）");
                            }
                            if (interactive) {
                                Toast2.ok(act, "已是最新版");
                            }
                            return;
                        }
                        if (status != null) {
                            status.setTextColor(Theme.adapt(Ui.PRIMARY_DEEP));
                            status.setText("发现新版 " + info.versionName + "，正在下载…");
                        }
                        startDownload(act, host, port, info, status);
                    }
                });
            }
        });
    }

    /**
     * 经中转查版本。
     *
     * 发出查询后，回包会由 OrderScreen.onRelayMessage 收到并展示。
     * 这里只负责把"正在等"的状态告诉用户，避免看起来像卡住。
     */
    private static void askViaRelay(final Activity act, final TextView status,
                                    final TextView btn, final boolean interactive) {
        ApkRelay.askInfoViaRelay(act, new ApkRelay.InfoCallback() {
            public void done(ApkRelay.Info info, final String note) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (btn != null) {
                            btn.setEnabled(true);
                            btn.setText("🔄 检查并更新");
                        }
                        if (status != null) {
                            status.setTextColor(Theme.adapt(Ui.PRIMARY_DEEP));
                            status.setText(note == null ? "等待对方回包…" : note);
                        }
                    }
                });
            }
        });
    }

    private static void startDownload(final Activity act, String host, int port,
                                      final ApkRelay.Info info, final TextView status) {
        final File out = new File(act.getCacheDir(), "peer-update-" + info.versionCode + ".apk");
        ApkRelay.download(act, host, port, out, new ApkRelay.DownloadCallback() {
            public void onProgress(final int percent) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (status != null) {
                            status.setText("正在下载 " + percent + "%…");
                        }
                    }
                });
            }

            public void onDone(final File apk, final long bytes) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (status != null) {
                            status.setTextColor(Theme.adapt(Ui.SUCCESS));
                            status.setText("下载完成（" + (bytes / 1024) + " KB），准备安装 " + info.versionName);
                        }
                        if (!Updater.canInstall(act)) {
                            DialogKit.confirm(act, "需要「安装未知应用」权限",
                                    "系统要求先允许小窝安装应用，点确定去开启，然后回来再点一次检查。",
                                    "去开启", new Runnable() {
                                        public void run() {
                                            Updater.requestInstallPermission(act);
                                        }
                                    });
                            return;
                        }
                        String err = Updater.install(act, apk);
                        if (err != null) {
                            Toast2.warn(act, err);
                        } else {
                            Toast2.info(act, "在弹出的安装界面点「安装」即可");
                        }
                    }
                });
            }

            public void onError(final String msg) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (status != null) {
                            status.setTextColor(Theme.adapt(Ui.DANGER));
                            status.setText(msg);
                        }
                        Toast2.warn(act, msg);
                    }
                });
            }
        });
    }

    private static int dp(Context c, float v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    private static LinearLayout.LayoutParams top(Context c, int v) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, v);
        return p;
    }

    private PeerUpdateCard() {
    }
}
