package com.couple.xiaowo.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.core.Remind;

import java.util.ArrayList;
import java.util.List;

/**
 * 提醒程度设置卡 —— 两边 App 的「我」页面里都能改，各存各的。
 *
 * 三个选项做成三个可点的卡片，而不是下拉框，因为只有三个、
 * 而且每个都有解释文案，卡片比弹窗更好理解（也少一次点击）。
 */
public final class RemindSettings {

    public static final String KEY = "remind_level";

    public static int level(Context c) {
        SharedPreferences sp = c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
        return sp.getInt(KEY, Remind.OVERLAY);
    }

    public static void setLevel(Context c, int v) {
        c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE)
                .edit().putInt(KEY, v).apply();
    }

    /** 生成整块设置卡，调用方直接 addView 即可 */
    public static View build(final Activity act, final Runnable onChanged) {
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(act, Ui.R_CARD)));
        card.setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 10));
        card.setElevation(dp(act, 2f));

        LinearLayout head = Ui.horizontal(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(act, "🔔 提醒程度", 14, Ui.TEXT, true));
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        final TextView state = Ui.tv(act, Remind.label(level(act)), 10.5f, Ui.PRIMARY_DEEP, true);
        head.addView(state);
        card.addView(head);

        card.addView(Ui.tv(act, "对方发来点单、公告、消息时，怎么提醒你。",
                10.5f, Ui.TEXT_SUB, false), top(act, 6));

        final List<LinearLayout> rows = new ArrayList<LinearLayout>();
        final List<TextView[]> texts = new ArrayList<TextView[]>();
        final int[] current = {level(act)};

        for (int i = 0; i < Remind.LABELS.length; i++) {
            final int idx = i;
            LinearLayout row = Ui.horizontal(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(act, 12), dp(act, 11), dp(act, 12), dp(act, 11));

            TextView radio = Ui.tv(act, "○", 15, Ui.TEXT_MUTED, false);
            row.addView(radio);

            LinearLayout col = Ui.vertical(act);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.weight = 1f;
            cLp.leftMargin = dp(act, 10);
            col.setLayoutParams(cLp);
            TextView title = Ui.tv(act, Remind.LABELS[i], 13, Ui.TEXT, false);
            col.addView(title);
            TextView hint = Ui.tv(act, Remind.HINTS[i], 10, Ui.TEXT_SUB, false);
            hint.setMaxLines(2);
            col.addView(hint, top(act, 2));
            row.addView(col);

            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    current[0] = idx;
                    setLevel(act, idx);
                    state.setText(Remind.label(idx));
                    paint(rows, texts, idx);
                    Ui.pressable(row);
                    if (idx == Remind.OVERLAY && !AlertCenter.canOverlay(act)) {
                        AlertCenter.requestOverlay(act);
                    }
                    if (onChanged != null) {
                        onChanged.run();
                    }
                }
            });
            rows.add(row);
            texts.add(new TextView[]{radio, title, hint});
            card.addView(row, top(act, 8));
        }

        paint(rows, texts, current[0]);
        return card;
    }

    private static void paint(List<LinearLayout> rows, List<TextView[]> texts, int on) {
        for (int i = 0; i < rows.size(); i++) {
            boolean active = i == on;
            LinearLayout row = rows.get(i);
            row.setBackground(Ui.round(active ? Ui.PRIMARY_SOFT : 0xFFF7F5FA,
                    dp(row.getContext(), 14),
                    Math.max(1, dp(row.getContext(), active ? 1.4f : 0f)),
                    active ? Ui.PRIMARY : 0x00000000));
            TextView[] t = texts.get(i);
            t[0].setText(active ? "●" : "○");
            t[0].setTextColor(Theme.adaptText(active ? Ui.PRIMARY : Ui.TEXT_MUTED));
            t[1].setTextColor(Theme.adaptText(active ? Ui.PRIMARY_DEEP : Ui.TEXT));
            t[1].setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,
                    active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
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

    private RemindSettings() {
    }
}
