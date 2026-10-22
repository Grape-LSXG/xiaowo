package com.couple.xiaowo.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.core.Keep;
import com.couple.xiaowo.core.PixelKeeper;
import com.couple.xiaowo.core.Remind;
import com.couple.xiaowo.core.ShotService;

/**
 * 「后台保活 & 静默截屏」设置卡 —— **只读状态 + 补权限入口，没有开关**。
 *
 * ── 为什么把开关全删了 ──
 *
 * v3.6 及以前这里有三行可点的开关（保活 / 后台隐藏 / 一像素）。
 * 问题在于：**关掉其中任何一个，App 就变成"收不到消息"的状态**，
 * 而用户并不知道自己刚刚把功能弄坏了 —— 他只是想"清静点"。
 *
 * 现在的规则简单粗暴：
 *   · 保活默认开、只能由代码维持，用户无法关闭
 *   · 后台隐藏固定开（它只影响通知文案，不影响接收，没有关的必要）
 *   · 一像素窗有权限就立着，没权限才显示「去授权」
 *
 * 每张卡片都显示**实时状态**（不是存下来的偏好），
 * 这样用户能一眼看出"现在到底有没有在保活"。
 */
public final class KeepAliveCard {

    /** 旧版本留下的键名，只为兼容读取；新版本不再写 false。 */
    public static final String KEY_ALIVE = "keep_alive_on";
    public static final String KEY_HIDE = "bg_hide_on";

    /**
     * 保活是否启用。
     *
     * **永远返回 true** —— 这是刻意的，不是漏改。
     * 旧安装里可能存着 false，这里直接忽略，把用户拉回正确状态。
     */
    public static boolean aliveEnabled(Context c) {
        return true;
    }

    /** 兼容旧调用方；现在没有任何地方能让它变 false */
    @Deprecated
    public static void setAlive(Context c, boolean on) {
        // 空实现：保活不再可关闭
    }

    /** 后台隐藏固定开启：它只换通知文案，不影响接收 */
    public static boolean hideEnabled(Context c) {
        return true;
    }

    @Deprecated
    public static void setHide(Context c, boolean on) {
        // 空实现
    }

    /**
     * 同步保活状态。所有启动/回前台路径都走它。
     *
     * @return true 表示还有权限缺口需要用户处理（悬浮窗 / 无障碍 / 通知）
     */
    public static boolean sync(Activity act, boolean isRecv, String hint) {
        if (act == null) {
            return false;
        }
        // 顺手把旧版存的 false 清掉，避免以后有人读 KEY_ALIVE 时被误导
        act.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .edit().remove(KEY_ALIVE).remove(KEY_HIDE).apply();
        return Keep.sync(act, isRecv, hint);
    }

    // ------------------------------------------------------------------ UI

    public static View build(final Activity act, final boolean isRecv) {
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(act, Ui.R_CARD)));
        card.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 12));
        card.setElevation(dp(act, 2f));

        // ---- 标题 + 不关闭的说明 ----
        LinearLayout head = Ui.horizontal(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(act, "🔋 后台保活", 14, Ui.TEXT, true));
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView always = Ui.pill(act, "始终开启", Ui.SUCCESS_SOFT, Ui.SUCCESS,
                10f, 10, 3, true);
        head.addView(always);
        card.addView(head);

        card.addView(Ui.tv(act,
                "这一项没有开关 —— 因为关掉它不是「省点资源」，而是直接让 App 收不到。\n"
                        + "实测：保活一停，接单端退到后台几秒，监听端口就不再应答，"
                        + "对方只会一直超时报「没连上」。所以它默认开、自动维持、不能关。",
                10.5f, Ui.TEXT_SUB, false), top(act, 6));

        // ---- 状态清单 ----
        card.addView(sectionTitle(act, "现在的状态"), top(act, 12));

        // 1) 前台服务
        boolean svc = Keep.serviceRunning(act);
        card.addView(stateRow(act, svc ? "✅" : "⚠️",
                "保活前台服务",
                svc ? "正在运行，常驻通知已挂上"
                        : "没在跑 —— 已尝试重新拉起，若一直这样请检查通知权限",
                svc ? Ui.SUCCESS : Ui.WARN), top(act, 6));

        // 2) 一像素窗
        boolean overlay = PixelKeeper.canOverlay(act);
        boolean pixelRunning = PixelKeeper.isRunning();
        LinearLayout row2 = stateRow(act, pixelRunning ? "✅" : (overlay ? "⏳" : "⚠️"),
                "屏幕边缘常驻（1 像素）",
                pixelRunning ? "已立起来，进程不会被当后台冻结"
                        : (overlay ? "权限有了，正在拉起"
                        : "缺「显示在其他应用上层」权限，点这里去开"),
                pixelRunning ? Ui.SUCCESS : (overlay ? Ui.TEXT_MUTED : Ui.WARN));
        if (!pixelRunning && !overlay) {
            row2.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Keep.goOverlay(act);
                    Toast2.warn(act, "先允许「显示在其他应用上层」，回来就自动生效");
                }
            });
        }
        card.addView(row2, top(act, 6));

        // 3) 静默截屏（无障碍）
        boolean shotOk = ShotService.isReady();
        boolean shotEnabled = ShotService.isEnabledInSettings(act);
        boolean lowApi = Build.VERSION.SDK_INT < 30;
        LinearLayout row3 = stateRow(act, shotOk ? "✅" : (shotEnabled ? "⏳" : "⚠️"),
                "静默截屏（无障碍）",
                lowApi ? "系统低于 Android 11，只能用 App 内截图"
                        : (shotOk ? "已就绪 —— 张老师点「看屏幕」时不用你任何操作"
                        : (shotEnabled ? "已授权，正在连接…"
                        : "没开。开了以后对方请求截图时你完全不用动手，点这里去开")),
                shotOk ? Ui.SUCCESS : (lowApi ? Ui.TEXT_MUTED : Ui.WARN));
        if (!shotOk && !lowApi) {
            row3.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Keep.goShot(act);
                    Toast2.warn(act, "在列表里找到「" + Ui.appName(act) + " · 静默截屏」并打开");
                }
            });
        }
        card.addView(row3, top(act, 6));

        // 4) 通知权限
        boolean notif = AlertCenter.SystemNotify.hasPermission(act);
        card.addView(stateRow(act, notif ? "✅" : "⚠️",
                "通知权限",
                notif ? "已允许，通知能正常弹出"
                        : "没开 —— 保活通知也发不出来，保活会打折扣",
                notif ? Ui.SUCCESS : Ui.WARN), top(act, 6));

        // ---- 一键补权限 ----
        if (!overlay || (!shotOk && !lowApi) || !notif) {
            TextView fixAll = Ui.primaryButton(act, "一键补齐权限");
            fixAll.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!PixelKeeper.canOverlay(act)) {
                        Keep.goOverlay(act);
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 30 && !ShotService.isReady()) {
                        Keep.goShot(act);
                        return;
                    }
                    if (!AlertCenter.SystemNotify.hasPermission(act)) {
                        AlertCenter.SystemNotify.request(act);
                        return;
                    }
                    Toast2.ok(act, "都齐了");
                }
            });
            card.addView(fixAll, top(act, 12));
        } else {
            card.addView(Ui.pill(act, "✅ 所有保活手段都就绪", Ui.SUCCESS_SOFT,
                    Ui.SUCCESS, 11.5f, 12, 8, true), top(act, 12));
        }

        // ---- 提醒程度联动 ----
        if (RemindSettings.level(act) >= Remind.OVERLAY && !AlertCenter.canOverlay(act)) {
            TextView fix = Ui.pill(act, "⚠ 提醒选了「悬浮窗」，但还没授权 → 去开启",
                    Ui.WARN_SOFT, Ui.WARN, 11.5f, 14, 8, true);
            fix.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    AlertCenter.requestOverlay(act);
                }
            });
            card.addView(fix, top(act, 10));
        }

        // ---- vivo/小米 专属 ----
        card.addView(sectionTitle(act, "厂商后台限制（只能手动开）"), top(act, 12));
        card.addView(Ui.tv(act,
                "vivo / 小米 / OPPO 在系统层还有几个白名单开关，任何 App 都没法静默打开：\n"
                        + "· 自启动（开机/被清后能自己起来）\n"
                        + "· 允许后台高耗电（后台持续联网）\n"
                        + "· 锁屏显示 / 后台弹出界面（锁屏也能提醒）",
                9.5f, Ui.TEXT_MUTED, false), top(act, 4));
        TextView goSys = Ui.secondaryButton(act, "去系统设置开这些");
        goSys.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Keep.goAppDetail(act);
            }
        });
        card.addView(goSys, top(act, 8));

        return card;
    }

    // ------------------------------------------------------------------ 小构件

    private static TextView sectionTitle(Activity act, String text) {
        return Ui.tv(act, text, 10.5f, Ui.TEXT_MUTED, true);
    }

    private static LinearLayout stateRow(Activity act, String icon, String title,
                                         String hint, int titleColor) {
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(act, 11), dp(act, 10), dp(act, 11), dp(act, 10));
        row.setBackground(Ui.round(0xFFF7F5FA, dp(act, 13)));

        TextView ic = Ui.tv(act, icon, 12.5f, Ui.TEXT, false);
        row.addView(ic);

        LinearLayout col = Ui.vertical(act);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.weight = 1f;
        cLp.leftMargin = dp(act, 9);
        col.setLayoutParams(cLp);
        col.addView(Ui.tv(act, title, 12f, titleColor, true));
        TextView h = Ui.tv(act, hint, 9.5f, Ui.TEXT_SUB, false);
        h.setLineSpacing(dp(act, 1.5f), 1f);
        col.addView(h, top(act, 2));
        row.addView(col);

        return row;
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

    private KeepAliveCard() {
    }
}
