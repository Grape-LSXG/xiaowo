package com.couple.xiaowo.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.core.Updater;

import java.io.File;

/**
 * 「检查更新」卡片 —— 放进两个 App 的「我」页面。
 *
 * 你只要把两个 APK 和 version.json 传到任意直链地址，然后在这里填 version.json 的 URL，
 * 之后每次点「检查更新」它就会比对版本、下载、调起安装器。
 */
public final class UpdateCard {

    /** 现在版本 + 更新入口 */
    public static View build(final Activity act) {
        final SharedPreferences sp = act.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);

        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(act, Ui.R_CARD)));
        card.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 12));
        card.setElevation(dp(act, 2f));

        LinearLayout head = Ui.horizontal(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(act, "⬆ 自动更新", 14, Ui.TEXT, true));
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(Ui.chip(act, Ui.appVersion(act),
                Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 10));
        card.addView(head);

        final TextView status = Ui.tv(act, "还没检查过", 10.5f, Ui.TEXT_SUB, false);
        status.setMaxLines(3);
        card.addView(status, top(act, 6));

        // ---- 检查按钮 ----
        TextView checkBtn = Ui.pill(act, "检查更新", Ui.PRIMARY, 0xFFFFFFFF, 12.5f, 20, 10, true);
        checkBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                status.setTextColor(Theme.adapt(Ui.TEXT_SUB));
                status.setText("正在检查…");
                Updater.check(act, new Updater.Callback() {
                    public void done(final Updater.Info info, final String error) {
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                if (error != null) {
                                    status.setTextColor(Theme.adapt(Ui.WARN));
                                    status.setText(error);
                                    return;
                                }
                                if (info == null) {
                                    status.setTextColor(Theme.adapt(Ui.SUCCESS));
                                    status.setText("已经是最新版啦（" + Ui.appVersion(act) + "）");
                                    Toast2.ok(act, "已是最新版");
                                    return;
                                }
                                status.setTextColor(Theme.adapt(Ui.PRIMARY_DEEP));
                                status.setText("发现新版本 " + info.versionName
                                        + (info.notes.length() > 0 ? "：" + info.notes : "") + "，正在下载…");
                                Downloading.show(act, info, status);
                            }
                        });
                    }
                });
            }
        });
        card.addView(checkBtn, top(act, 10));

        // ---- 地址设置 ----
        TextView setUrl = Ui.pill(act, "⚙ 设置更新地址", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 11.5f, 18, 8, true);
        setUrl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                DialogKit.input(act, "更新清单地址",
                        "https://.../version.json", sp.getString(Updater.KEY_URL, ""),
                        new DialogKit.InputCallback() {
                            public void onInput(String text) {
                                sp.edit().putString(Updater.KEY_URL,
                                        text == null ? "" : text.trim()).apply();
                                Toast2.ok(act, "已保存更新地址");
                                status.setTextColor(Theme.adapt(Ui.TEXT_SUB));
                                status.setText("地址已保存，点「检查更新」试试");
                            }
                        });
            }
        });
        card.addView(setUrl, top(act, 8));

        card.addView(Ui.tv(act,
                "把新 APK 和 version.json 传到任意直链（GitHub Release / 对象存储都行），\n"
                        + "这里填 version.json 的地址，之后每次点检查就能更新。",
                10, Ui.TEXT_MUTED, false), top(act, 10));

        return card;
    }

    /** 下载 + 安装的那一段，单独抽出来免得匿名类套太深 */
    static final class Downloading {
        static void show(final Activity act, final Updater.Info info, final TextView status) {
            Updater.download(act, info, new Updater.Progress() {
                public void onProgress(final int percent) {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            status.setText("正在下载 " + percent + "%…");
                        }
                    });
                }

                public void onDone(final File apk) {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            status.setTextColor(Theme.adapt(Ui.SUCCESS));
                            status.setText("下载完成，准备安装 " + info.versionName);
                            if (!Updater.canInstall(act)) {
                                DialogKit.confirm(act, "需要「安装未知应用」权限",
                                        "系统要求先允许小窝安装应用，点确定去开启，然后回来再点一次检查更新。",
                                        "去开启", new Runnable() {
                                            public void run() {
                                                Updater.requestInstallPermission(act);
                                            }
                                        });
                                return;
                            }
                            final String err = Updater.install(act, apk);
                            if (err != null) {
                                status.setTextColor(Theme.adapt(Ui.DANGER));
                                status.setText(err);
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
                            status.setTextColor(Theme.adapt(Ui.DANGER));
                            status.setText(msg);
                            Toast2.warn(act, msg);
                        }
                    });
                }
            });
        }
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

    private UpdateCard() {
    }
}
