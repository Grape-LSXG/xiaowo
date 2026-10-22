package com.couple.xiaowo.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.BuildInfo;
import com.couple.xiaowo.core.Keep;
import com.couple.xiaowo.core.PixelKeeper;
import com.couple.xiaowo.core.Remind;
import com.couple.xiaowo.core.ShotService;

/**
 * 「后台保活 & 必要权限」设置卡 —— **只读状态 + 补权限入口，没有开关**。
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
        boolean missing = Keep.sync(act, isRecv, hint);
        /*
         * 补一个"事后刷新"：卡片是在构造函数里建的，读的是服务起来之前的状态；
         * 现在同步完了，把已建好的卡片刷新成真实值。
         * 用 post 延迟一帧，让 startForegroundService 有时间真正跑起来。
         */
        act.runOnUiThread(new Runnable() {
            public void run() {
                act.findViewById(android.R.id.content).post(new Runnable() {
                    public void run() {
                        refreshAll();
                    }
                });
            }
        });
        return missing;
    }

    // ------------------------------------------------------------------ UI

    public static View build(final Activity act, final boolean isRecv) {
        ItemHolder holder = new ItemHolder();

        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(act, Ui.R_CARD)));
        card.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 12));
        card.setElevation(dp(act, 2f));
        holder.card = card;

        // ---- 标题 ----
        LinearLayout head = Ui.horizontal(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(act, "🛡 运行权限", 14, Ui.TEXT, true));
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        holder.badge = Ui.pill(act, "检查中", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 10f, 10, 3, true);
        head.addView(holder.badge);
        card.addView(head);

        holder.subtitle = Ui.tv(act, "正在检查各项权限…", 10.5f, Ui.TEXT_SUB, false);
        card.addView(holder.subtitle, top(act, 6));

        // ---- 权限清单（每行一个缺口，全部就绪时整块隐藏）----
        holder.rowsBox = Ui.vertical(act);
        card.addView(holder.rowsBox, top(act, 10));

        holder.fixAll = Ui.primaryButton(act, "去开启");
        holder.fixAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        goNextMissing(act);
                    }
                });
            }
        });
        card.addView(holder.fixAll, top(act, 12));

        // ---- 系统设置入口：只在有缺口时才出现 ----
        holder.moreRow = Ui.pill(act, "还有系统级设置需要确认 →", Ui.TEXT_MUTED,
                Ui.TEXT_SUB, 10.5f, 12, 6, false);
        holder.moreRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Keep.goAppDetail(act);
            }
        });
        card.addView(holder.moreRow, top(act, 6));

        // ---- 全部就绪时显示的紧凑一行 ----
        holder.okRow = Ui.pill(act, "✅ 运行权限已就绪", Ui.SUCCESS_SOFT,
                Ui.SUCCESS, 11.5f, 12, 8, true);
        card.addView(holder.okRow);

        // ---- 首次引导：提示改成允许后台高耗电（每台设备只提一次）----
        holder.hintBox = Ui.vertical(act);
        card.addView(holder.hintBox);

        Runnable refresh = new Runnable() {
            public void run() {
                refreshCard(act, holder);
            }
        };
        registerRefresh(refresh);
        holder.selfRefresh = refresh;
        refresh.run();

        return card;
    }

    /** build() 里用到的可变引用集合，避免 lambda 捕获 final 限制 */
    private static final class ItemHolder {
        LinearLayout card;
        TextView badge;
        TextView subtitle;
        LinearLayout rowsBox;
        TextView fixAll;
        TextView moreRow;
        TextView okRow;
        LinearLayout hintBox;
        Runnable selfRefresh;
    }

    /**
     * 把卡片刷成"当前真实状态"。
     *
     * 设计原则（按需求）：
     *   · 不描述任何具体手段 —— 不提常驻通知、窗口、服务这些词
     *   · 只讲"缺什么权限、开了有什么好处"
     *   · 全部就绪时，清单和按钮整体收起，只留一行"已就绪"
     */
    private static void refreshCard(final Activity act, final ItemHolder h) {
        if (act == null || act.isFinishing()) {
            return;
        }
        boolean overlayOk = PixelKeeper.canOverlay(act);
        boolean notifOk = AlertCenter.SystemNotify.hasPermission(act);
        boolean batteryOk = Keep.isIgnoringBattery(act);
        boolean shotOk = !BuildInfo.IS_ORDER
                || Build.VERSION.SDK_INT < 30
                || ShotService.isReady()
                || ShotService.isEnabledInSettings(act);
        boolean oemOk = Keep.oemGuideConfirmed(act);

        h.rowsBox.removeAllViews();

        int missing = 0;
        if (!overlayOk) {
            missing++;
            h.rowsBox.addView(missingRow(act, "显示在其他应用上层",
                    "用于在你看不到的时候也能收到提醒"), top(act, 6));
            h.rowsBox.getChildAt(h.rowsBox.getChildCount() - 1)
                    .setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            Keep.goOverlay(act);
                        }
                    });
        }
        if (!notifOk) {
            missing++;
            h.rowsBox.addView(missingRow(act, "通知",
                    "对方发来的消息和提醒才能显示出来"), top(act, 6));
            h.rowsBox.getChildAt(h.rowsBox.getChildCount() - 1)
                    .setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            AlertCenter.SystemNotify.request(act);
                        }
                    });
        }
        if (!batteryOk) {
            missing++;
            h.rowsBox.addView(missingRow(act, "电池优化",
                    "允许以后，长时间放着也不会掉线"), top(act, 6));
            h.rowsBox.getChildAt(h.rowsBox.getChildCount() - 1)
                    .setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            Keep.requestIgnoreBattery(act);
                        }
                    });
        }
        if (BuildInfo.IS_ORDER && Build.VERSION.SDK_INT >= 30 && !shotOk) {
            missing++;
            h.rowsBox.addView(missingRow(act, "无障碍",
                    "对方想看你这边时可以自动完成"), top(act, 6));
            h.rowsBox.getChildAt(h.rowsBox.getChildCount() - 1)
                    .setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            Keep.goShot(act);
                        }
                    });
        }
        if (!oemOk) {
            missing++;
            h.rowsBox.addView(missingRow(act, "后台运行",
                    "系统里允许后台高耗电后更稳定"), top(act, 6));
            h.rowsBox.getChildAt(h.rowsBox.getChildCount() - 1)
                    .setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            Keep.goAppDetail(act);
                        }
                    });
        }

        if (missing == 0) {
            // 全部就绪：清单/按钮/说明全部收起，只留一行结论
            h.rowsBox.setVisibility(View.GONE);
            h.fixAll.setVisibility(View.GONE);
            h.moreRow.setVisibility(View.GONE);
            h.hintBox.setVisibility(View.GONE);
            h.okRow.setVisibility(View.VISIBLE);
            h.badge.setText("已完成");
            h.badge.setBackground(Ui.round(Ui.SUCCESS_SOFT, dp(act, 999)));
            h.badge.setTextColor(Ui.SUCCESS);
            h.subtitle.setVisibility(View.GONE);
            return;
        }

        h.rowsBox.setVisibility(View.VISIBLE);
        h.okRow.setVisibility(View.GONE);
        h.badge.setText(missing + " 项待确认");
        h.badge.setBackground(Ui.round(Ui.WARN_SOFT, dp(act, 999)));
        h.badge.setTextColor(Ui.WARN);
        h.subtitle.setVisibility(View.VISIBLE);
        h.subtitle.setText("开启下面这些之后，即使不打开 App 也能正常收到消息。");

        h.fixAll.setVisibility(View.VISIBLE);
        h.fixAll.setText("去开启");

        h.moreRow.setVisibility(View.VISIBLE);

        // 首次进入且电池优化没开：多给一句明确的厂商提示
        h.hintBox.removeAllViews();
        if (!batteryOk && !Keep.batteryHintShown(act)) {
            /*
             * 这里**不**立即置位。
             *
             * 原来一显示提示就把标记写死了 —— 用户如果只是看了一眼、
             * 没有点「去开启」，之后永远不会再被提醒，
             * 而"第一次要提示用户改为允许后台高耗电"这件事就没做到。
             *
             * 改成：只有真的点了（跳去系统设置时）才标记。
             */
            TextView hint = Ui.pill(act,
                    "在系统设置里把本应用改为「允许后台高耗电」，会更稳定",
                    Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 10.5f, 12, 6, false);
            // 点击落在 pill 上（容器本身不一定可点），并且真要跳才标记已提示
            hint.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Keep.markBatteryHintShown(act);
                    Keep.goAppDetail(act);
                }
            });
            h.hintBox.addView(hint, top(act, 8));
            h.hintBox.setVisibility(View.VISIBLE);
        } else {
            h.hintBox.setVisibility(View.GONE);
        }
    }

    /** 缺权限的一行：标题 + 一句"开了有什么用" */
    private static LinearLayout missingRow(Activity act, String title, String hint) {
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 11), dp(act, 10), dp(act, 11), dp(act, 10));
        row.setBackground(Ui.round(0xFFF7F5FA, dp(act, 13)));

        row.addView(Ui.tv(act, "⚠️", 12.5f, Ui.WARN, false));

        LinearLayout col = Ui.vertical(act);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.weight = 1f;
        cLp.leftMargin = dp(act, 9);
        col.setLayoutParams(cLp);
        col.addView(Ui.tv(act, title, 12.5f, Ui.TEXT, true));
        col.addView(Ui.tv(act, hint, 9.5f, Ui.TEXT_SUB, false), top(act, 2));
        row.addView(col);

        row.addView(Ui.tv(act, "›", 15, Ui.TEXT_MUTED, false));
        return row;
    }

    /** 依次去补下一个缺口 */
    private static void goNextMissing(Activity act) {
        if (!PixelKeeper.canOverlay(act)) {
            Keep.goOverlay(act);
            return;
        }
        if (!AlertCenter.SystemNotify.hasPermission(act)) {
            AlertCenter.SystemNotify.request(act);
            return;
        }
        if (!Keep.isIgnoringBattery(act)) {
            Keep.requestIgnoreBattery(act);
            return;
        }
        if (BuildInfo.IS_ORDER && Build.VERSION.SDK_INT >= 30 && !ShotService.isReady()) {
            Keep.goShot(act);
            return;
        }
        Keep.goAppDetail(act);
    }

    // ------------------------------------------------------------------ 小构件

    private static TextView sectionTitle(Activity act, String text) {
        return Ui.tv(act, text, 10.5f, Ui.TEXT_MUTED, true);
    }

    /**
     * 本进程里所有已建好的卡片刷新器。
     *
     * 存的是「弱引用」而不是 Runnable 本身：刷新器是匿名类，
     * 内部捕获了 Activity 和卡片里的 View。如果这里是强引用，
     * 每次 Activity 重建（主题切换、授权返回、旋转）都会留下一个
     * 指向已销毁界面的刷新器 —— 静态集合永不释放，就是典型泄漏。
     *
     * 用 WeakReference 包一层后，Activity 被回收时刷新器自然失效，
     * refreshAll 里 get() 拿不到就跳过。
     */
    private static final java.util.List<java.lang.ref.WeakReference<Runnable>> REFRESHERS =
            new java.util.ArrayList<java.lang.ref.WeakReference<Runnable>>();

    /**
     * 把已建好的卡片状态刷成当前真实值。
     *
     * 为什么需要：卡片是在 OrderScreen/RecvScreen 的构造函数里建的，
     * 而 KeepAliveCard.sync() 要等 setContentView 之后才调用 ——
     * 卡片读状态时服务还没起来，就会一直显示「没在跑」，
     * 同时下面又写着「所有保活手段都就绪」，自相矛盾。
     *
     * 这里在 sync 之后回调一次，让界面收敛到真实状态。
     */
    public static void refreshAll() {
        java.util.List<java.lang.ref.WeakReference<Runnable>> copy;
        synchronized (REFRESHERS) {
            copy = new java.util.ArrayList<java.lang.ref.WeakReference<Runnable>>(REFRESHERS);
            // 顺手清掉已经失效的，别让列表只增不减
            java.util.Iterator<java.lang.ref.WeakReference<Runnable>> it = REFRESHERS.iterator();
            while (it.hasNext()) {
                if (it.next().get() == null) {
                    it.remove();
                }
            }
        }
        for (java.lang.ref.WeakReference<Runnable> ref : copy) {
            Runnable r = ref.get();
            if (r == null) {
                continue;
            }
            try {
                r.run();
            } catch (Exception ignore) {
            }
        }
    }

    private static void registerRefresh(Runnable r) {
        if (r == null) {
            return;
        }
        synchronized (REFRESHERS) {
            // 除了清掉失效项，也给一个上限兜底（重建很频繁时）
            java.util.Iterator<java.lang.ref.WeakReference<Runnable>> it = REFRESHERS.iterator();
            while (it.hasNext()) {
                if (it.next().get() == null) {
                    it.remove();
                }
            }
            if (REFRESHERS.size() > 12) {
                REFRESHERS.clear();
            }
            REFRESHERS.add(new java.lang.ref.WeakReference<Runnable>(r));
        }
    }

    /** 就地更新一行状态（不重建 View，避免闪烁） */
    private static void stateRowUpdate(Activity act, LinearLayout row, String icon,
                                       String title, String hint, int titleColor) {
        try {
            TextView ic = (TextView) row.getChildAt(0);
            ic.setText(icon);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            TextView t = (TextView) col.getChildAt(0);
            t.setText(title);
            t.setTextColor(titleColor);
            TextView h = (TextView) col.getChildAt(1);
            h.setText(hint);
        } catch (Exception ignore) {
        }
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
