package com.couple.xiaowo.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.core.KeepAlive;
import com.couple.xiaowo.core.PixelKeeper;
import com.couple.xiaowo.core.Protocol;
import com.couple.xiaowo.core.Remind;

/**
 * 「后台保活」设置卡。
 *
 * 解决的是「通知有时候不灵敏」这类问题的**根因**：
 * App 被系统回收后进程就没了，什么通知都弹不出来。
 *
 * 三个开关：
 *   1. 保活前台服务 —— 挂常驻通知，系统当"正在运行"，不会被随手杀
 *   2. 后台隐藏     —— App 退到后台时自动缩成一条常驻通知，不占屏幕
 *   3. 提醒程度联动 —— 选「悬浮窗」时如果权限没给，这里可以直接跳去授权
 */
public final class KeepAliveCard {

    public static final String KEY_ALIVE = "keep_alive_on";
    public static final String KEY_HIDE = "bg_hide_on";

    public static boolean aliveEnabled(Context c) {
        return c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .getBoolean(KEY_ALIVE, false);
    }

    public static void setAlive(Context c, boolean on) {
        c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ALIVE, on).apply();
    }

    public static boolean hideEnabled(Context c) {
        return c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .getBoolean(KEY_HIDE, true);
    }

    public static void setHide(Context c, boolean on) {
        c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HIDE, on).apply();
    }

    /** 按当前开关启动/停止保活服务 */
    public static void sync(Activity act, boolean isRecv, String hint) {
        if (aliveEnabled(act)) {
            KeepAlive.start(act, isRecv ? Protocol.ROLE_RECV : Protocol.ROLE_ORDER,
                    hint == null || hint.length() == 0 ? "正在等你" : hint);
        } else {
            KeepAlive.stop(act);
        }
    }

    public static View build(final Activity act, final boolean isRecv) {
        final SharedPreferences sp = act.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);

        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(act, Ui.R_CARD)));
        card.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 12));
        card.setElevation(dp(act, 2f));

        LinearLayout head = Ui.horizontal(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(act, "🔋 后台保活", 14, Ui.TEXT, true));
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        final TextView state = Ui.tv(act, aliveEnabled(act) ? "已开启" : "未开启",
                10.5f, aliveEnabled(act) ? Ui.SUCCESS : Ui.TEXT_MUTED, true);
        head.addView(state);
        card.addView(head);

        card.addView(Ui.tv(act,
                "实测结论：不开保活时，接单端一退到后台，监听端口就不再应答——\n"
                        + "对方会一直卡到超时、报「没连上」，聊天和公告也发不出去。\n"
                        + "所以异地联机这一项必须开。代价只是通知栏多一条常驻通知。",
                10.5f, Ui.TEXT_SUB, false), top(act, 6));

        // ---- 开关 1：保活 ----
        final boolean[] alive = {aliveEnabled(act)};
        final TextView[] aliveBox = new TextView[1];
        LinearLayout row1 = toggleRow(act, "保持后台运行（推荐）",
                "挂一条常驻通知，系统就不会随手杀掉小窝",
                alive[0], new Runnable() {
                    public void run() {
                        alive[0] = !alive[0];
                        setAlive(act, alive[0]);
                        aliveBox[0].setText(alive[0] ? "☑" : "☐");
                        aliveBox[0].setTextColor(alive[0] ? Ui.PRIMARY : Ui.TEXT_MUTED);
                        state.setText(alive[0] ? "已开启" : "未开启");
                        state.setTextColor(alive[0] ? Ui.SUCCESS : Ui.TEXT_MUTED);
                        sync(act, isRecv, null);
                        Toast2.ok(act, alive[0] ? "已开启保活" : "已关闭保活");
                    }
                }, aliveBox);
        card.addView(row1, top(act, 10));

        // ---- 开关 2：后台隐藏 ----
        final boolean[] hide = {hideEnabled(act)};
        final TextView[] hideBox = new TextView[1];
        LinearLayout row2 = toggleRow(act, "退到后台时自动隐藏",
                "只留常驻通知，不占屏幕（不影响接收）",
                hide[0], new Runnable() {
                    public void run() {
                        hide[0] = !hide[0];
                        setHide(act, hide[0]);
                        hideBox[0].setText(hide[0] ? "☑" : "☐");
                        hideBox[0].setTextColor(hide[0] ? Ui.PRIMARY : Ui.TEXT_MUTED);
                        Toast2.ok(act, hide[0] ? "已开启后台隐藏" : "已关闭后台隐藏");
                    }
                }, hideBox);
        card.addView(row2, top(act, 8));

        // ---- 开关 3：一像素保活（真正解决"要打开 App 才收到"） ----
        final boolean[] pixelOn = {PixelKeeper.isRunning()};
        final TextView[] pixelBox = new TextView[1];
        LinearLayout row3 = toggleRow(act, "屏幕边缘常驻（更可靠）",
                "挂一个看不见的 1 像素窗口，系统就不会把你当后台冻结 ——\n"
                        + "这样不打开 App 也能及时收到通知。需要「显示在其他应用上层」权限",
                pixelOn[0], new Runnable() {
                    public void run() {
                        if (pixelOn[0]) {
                            PixelKeeper.stop();
                            pixelOn[0] = false;
                            pixelBox[0].setText("☐");
                            pixelBox[0].setTextColor(Ui.TEXT_MUTED);
                            Toast2.warn(act, "已关闭一像素保活");
                            return;
                        }
                        if (!PixelKeeper.canOverlay(act)) {
                            PixelKeeper.requestOverlay(act);
                            Toast2.warn(act, "请允许「显示在其他应用上层」，回来再点一次");
                            return;
                        }
                        boolean ok = PixelKeeper.start(act);
                        pixelOn[0] = ok;
                        pixelBox[0].setText(ok ? "☑" : "☐");
                        pixelBox[0].setTextColor(ok ? Ui.PRIMARY : Ui.TEXT_MUTED);
                        Toast2.ok(act, ok ? "已开启常驻（看不见的）" : "开启失败，检查悬浮窗权限");
                    }
                }, pixelBox);
        card.addView(row3, top(act, 8));

        // 权限提示
        if (!PixelKeeper.canOverlay(act)) {
            TextView fix2 = Ui.pill(act, "⚠ 开启上面这项需要先授权「显示在其他应用上层」",
                    Ui.WARN_SOFT, Ui.WARN, 11.5f, 14, 8, true);
            fix2.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    PixelKeeper.requestOverlay(act);
                }
            });
            card.addView(fix2, top(act, 8));
        }

        // ---- 提醒程度联动：没给悬浮窗权限时给个入口 ----
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

        card.addView(Ui.tv(act,
                "提示：部分手机厂商（vivo/小米）有「自启动」「后台高耗电」限制，\n"
                        + "如果还是收不到，去系统设置 → 电池 → 允许后台活动。",
                9.5f, Ui.TEXT_MUTED, false), top(act, 10));

        return card;
    }

    private static LinearLayout toggleRow(Activity act, String title, String hint,
                                          boolean on, final Runnable onClick,
                                          TextView[] outBox) {
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 12), dp(act, 11), dp(act, 12), dp(act, 11));
        row.setBackground(Ui.round(on ? Ui.PRIMARY_SOFT : 0xFFF7F5FA, dp(act, 14),
                Math.max(1, dp(act, on ? 1.4f : 0f)), on ? Ui.PRIMARY : 0x00000000));

        TextView box = Ui.tv(act, on ? "☑" : "☐", 15,
                on ? Ui.PRIMARY : Ui.TEXT_MUTED, false);
        row.addView(box);
        outBox[0] = box;

        LinearLayout col = Ui.vertical(act);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.weight = 1f;
        cLp.leftMargin = dp(act, 10);
        col.setLayoutParams(cLp);
        col.addView(Ui.tv(act, title, 12.5f, Ui.TEXT, false));
        TextView h = Ui.tv(act, hint, 9.5f, Ui.TEXT_SUB, false);
        h.setMaxLines(2);
        col.addView(h, top(act, 2));
        row.addView(col);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onClick.run();
                // 重画选中态
                boolean nowOn = "☑".equals(((TextView) ((LinearLayout) v).getChildAt(0)).getText());
                v.setBackground(Ui.round(nowOn ? Ui.PRIMARY_SOFT : 0xFFF7F5FA, dp(act, 14),
                        Math.max(1, dp(act, nowOn ? 1.4f : 0f)),
                        nowOn ? Ui.PRIMARY : 0x00000000));
            }
        });
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
