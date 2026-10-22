package com.couple.xiaowo.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.couple.xiaowo.core.Lux;

/**
 * 「浅色 / 深色」设置卡。
 *
 * 三个选项，和 Theme 的三个模式一一对应：
 *   跟随环境光 —— 暗处自动变深色（推荐，默认）
 *   始终浅色   —— 强制浅色
 *   始终深色   —— 强制深色
 *
 * 切换后会 recreate() 整个 Activity：界面是纯代码搭的，
 * 逐控件改色既容易漏、也很难做过渡动画，重建最稳。
 */
public final class ThemeCard {

    public static View build(final MainActivity act) {
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, Ui.dp(act, Ui.R_CARD)));
        card.setPadding(Ui.dpi(act, 14), Ui.dpi(act, 14), Ui.dpi(act, 14), Ui.dpi(act, 12));
        card.setElevation(Ui.dp(act, 2f));

        LinearLayout head = Ui.horizontal(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(act, "🌗 外观", 14, Ui.TEXT, true));
        head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));

        int mode = Theme.mode(act);
        TextView state = Ui.tv(act, Theme.modeNames()[mode], 10.5f,
                mode == Theme.MODE_AUTO ? Ui.PRIMARY_DEEP : Ui.TEXT_MUTED, true);
        head.addView(state);
        card.addView(head);

        // 传感器可用性提示 —— 没有光线传感器时"跟随环境光"只能按时间走，
        // 这一点必须写清楚，否则用户会觉得自动切换不管用。
        String hint;
        Theme.Ambient probe = new Theme.Ambient(act, null);
        if (mode == Theme.MODE_AUTO) {
            hint = probe.available()
                    ? "暗处自动变深色，亮处变回浅色（看环境光）"
                    : "这台机器没有光线传感器，跟随模式会按时间判断（晚 7 点后变深色）";
        } else {
            hint = "固定配色，不再自动切换";
        }
        card.addView(Ui.tv(act, hint, 10.5f, Ui.TEXT_SUB, false), top(act, 6));

        final String[] names = Theme.modeNames();
        final String[] hints = new String[]{
                probe.available() ? "推荐：亮度低于 20 lux 就切深色" : "这台机器没有光线传感器，按时间判断",
                "一直用浅色粉紫，白天最舒服",
                "一直用深色，夜里看不刺眼"
        };
        for (int i = 0; i < names.length; i++) {
            card.addView(optionRow(act, act, i, names[i], hints[i], mode == i));
        }

        return card;
    }

    private static View optionRow(final MainActivity act, Context c, final int value,
                                  String title, String hint, boolean selected) {
        LinearLayout row = Ui.horizontal(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dpi(c, 10), Ui.dpi(c, 10), Ui.dpi(c, 10), Ui.dpi(c, 10));
        row.setBackground(Ui.round(selected ? Ui.PRIMARY_SOFT : 0x00000000, Ui.dp(c, Ui.R_TILE)));
        row.setClickable(true);
        Ui.pressable(row);

        TextView mark = Ui.tv(c, selected ? "●" : "○", 13,
                selected ? Ui.PRIMARY_DEEP : Ui.TEXT_MUTED, false);
        mark.setPadding(0, 0, Ui.dpi(c, 10), 0);
        row.addView(mark);

        LinearLayout col = Ui.vertical(c);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(Ui.tv(c, title, 12.5f, Ui.TEXT, selected));
        col.addView(Ui.tv(c, hint, 10, Ui.TEXT_MUTED, false), top(c, 2));
        row.addView(col);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Theme.mode(act) == value) {
                    return;   // 已经是这个模式，别白重建
                }
                act.onThemePicked(value);
            }
        });
        Ui.a11y(row, title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dpi(c, 4);
        row.setLayoutParams(lp);
        return row;
    }

    private static LinearLayout.LayoutParams top(Context c, float v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dpi(c, v);
        return lp;
    }
}
