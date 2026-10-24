package com.couple.xiaowo.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.couple.xiaowo.core.Menu;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜谱编辑器（两端共用）。
 *
 * ── 为什么需要它 ──
 *
 * 原来只有点单端能改菜谱，接单端只能「刷新」。用户要求两端都自己编辑，
 * 且互相同步。这个类把"改菜单"的 UI 抽出来，两边谁调用都行：
 *
 *   MenuEditor.open(act, callback)
 *
 * 改完只负责把变化告诉调用方（存盘 + 推送），不自己决定同步策略 ——
 * 点单端和接单端的对端地址、推送通路不一样，交给各自处理。
 */
public final class MenuEditor {

    /** 改动回调：调用方负责存盘 + 推送给对方 + 刷新界面 */
    public interface OnChanged {
        void onChanged();
    }

    private MenuEditor() {
    }

    /** 打开菜谱编辑面板 */
    public static void open(final Activity act, final OnChanged cb) {
        if (act == null) {
            return;
        }
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(act, 8), dp(act, 4), dp(act, 8), dp(act, 4));

        ScrollView sv = new ScrollView(act);
        LinearLayout list = Ui.vertical(act);
        sv.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 逐分类列出，每道菜右边两个小按钮 ----
        for (final Menu.Category c : Menu.CATEGORIES) {
            if (c.items.isEmpty()) {
                continue;
            }
            TextView head = Ui.tv(act, c.icon + " " + c.name + "（" + c.items.size() + "）",
                    12, Ui.PRIMARY_DEEP, true);
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hLp.topMargin = dp(act, 10);
            hLp.bottomMargin = dp(act, 4);
            list.addView(head, hLp);

            for (final Menu.Item it : c.items) {
                list.addView(buildRow(act, it, cb), rowParams(act));
            }
        }

        box.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 360)));

        TextView add = Ui.pill(act, "＋ 新增一道菜", Ui.PRIMARY, 0xFFFFFFFF, 12.5f, 18, 10, true);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAdd(act, cb);
            }
        });
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(act, 8);
        box.addView(add, aLp);

        DialogKit.custom(act, "编辑菜谱", box, "完成", null);
    }

    private static View buildRow(final Activity act, final Menu.Item it, final OnChanged cb) {
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(Ui.CARD, dp(act, 12)));
        row.setPadding(dp(act, 10), dp(act, 8), dp(act, 8), dp(act, 8));

        // 封面小图
        ImageView iv = new ImageView(act);
        android.graphics.Bitmap bm = CatImage.bitmap(act, it.cat);
        if (bm != null) {
            iv.setImageBitmap(bm);
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        row.addView(iv, new LinearLayout.LayoutParams(dp(act, 34), dp(act, 34)));

        LinearLayout texts = Ui.vertical(act);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.weight = 1f;
        tLp.leftMargin = dp(act, 8);
        texts.setLayoutParams(tLp);
        texts.addView(Ui.tv(act, it.emoji + " " + it.name, 13, Ui.TEXT, true));
        String sub = "❤" + it.price;
        if (it.hasSpecs()) {
            sub += " · " + it.specs.size() + " 个规格";
        } else {
            sub += " · 不用选规格";
        }
        texts.addView(Ui.tv(act, sub, 10, Ui.TEXT_MUTED, false));
        row.addView(texts);

        TextView edit = Ui.pill(act, "改", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 10.5f, 10, 4, true);
        edit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showEdit(act, it, cb);
            }
        });
        row.addView(edit);

        TextView del = Ui.pill(act, "删", 0xFFFDECEC, 0xFFD2453F, 10.5f, 10, 4, true);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dLp.leftMargin = dp(act, 6);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmDelete(act, it, cb);
            }
        });
        row.addView(del, dLp);
        return row;
    }

    /** 新增一道菜 */
    public static void showAdd(final Activity act, final OnChanged cb) {
        showAdd(act, cb, "", "", "🐾", "", "");
    }

    /**
     * 新增一道菜（带初值）。
     *
     * 为什么要这个重载：DialogKit 的主按钮是「先关框再执行回调」，
     * 所以校验失败时框已经关了 —— 只能带着刚才填的内容重新开一次，
     * 不然用户填了一半天凭空消失。
     */
    public static void showAdd(final Activity act, final OnChanged cb,
                               String initName, String initPrice,
                               String initEmoji, String initDesc, String initSpecs) {
        LinearLayout box = formBox(act);
        final EditText name = field(act, box, "菜名，例如「要摸摸头」", initName);
        final EditText price = field(act, box, "心动值，例如 5", initPrice);
        price.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText emoji = field(act, box, "封面表情（一个 emoji）", initEmoji);
        final EditText desc = field(act, box, "一句话说明", initDesc);
        final EditText specs = field(act, box, "规格，用顿号/逗号分开（可留空）", initSpecs);
        box.addView(Ui.tv(act, "例：微辣、中辣、特辣 —— 填了就变成要点时选规格的菜",
                10, Ui.TEXT_MUTED, false), top(act, 3));

        final int[] cat = {0};
        box.addView(Ui.tv(act, "放进哪个分类：", 11, Ui.PRIMARY_DEEP, true), top(act, 10));
        LinearLayout cats = Ui.horizontal(act);
        final List<TextView> chips = new ArrayList<TextView>();
        for (int i = 0; i < Menu.CATEGORIES.size(); i++) {
            final int idx = i;
            final Menu.Category c = Menu.CATEGORIES.get(i);
            final TextView chip = Ui.pill(act, c.name,
                    i == 0 ? Ui.PRIMARY : 0xFFF2EEFB,
                    i == 0 ? 0xFFFFFFFF : Ui.PRIMARY_DEEP, 10.5f, 10, 6, true);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cat[0] = idx;
                    for (int k = 0; k < chips.size(); k++) {
                        boolean on = k == idx;
                        chips.get(k).setBackground(Ui.round(
                                on ? Ui.PRIMARY : 0xFFF2EEFB, dp(act, 999)));
                        chips.get(k).setTextColor(on ? 0xFFFFFFFF : Ui.PRIMARY_DEEP);
                    }
                }
            });
            chips.add(chip);
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.rightMargin = dp(act, 6);
            cLp.bottomMargin = dp(act, 6);
            cats.addView(chip, cLp);
        }
        ScrollView cs = new ScrollView(act);
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(cats);
        box.addView(cs, top(act, 4));

        DialogKit.custom(act, "新增一道菜", box, "添加", new Runnable() {
            public void run() {
                String nm = name.getText().toString().trim();
                if (nm.length() == 0) {
                    Toast2.show(act, Toast2.WARN, "菜名不能为空，帮你把填好的内容留着");
                    // 框已经被关掉了，带值重开，避免白填
                    showAdd(act, cb,
                            name.getText().toString(), price.getText().toString(),
                            emoji.getText().toString(), desc.getText().toString(),
                            specs.getText().toString());
                    return;
                }
                int p;
                try {
                    p = Integer.parseInt(price.getText().toString().trim());
                } catch (Exception e) {
                    p = 5;
                }
                Menu.Item it = new Menu.Item(nm, p, cat[0],
                        emoji.getText().toString().trim(),
                        desc.getText().toString().trim(), 5.0f, 0, true);
                applySpecs(it, specs.getText().toString());
                Menu.CATEGORIES.get(cat[0]).items.add(it);
                Toast2.show(act, Toast2.SUCCESS, "已添加「" + nm + "」");
                if (cb != null) {
                    cb.onChanged();
                }
            }
        });
    }

    /** 编辑已有菜 */
    public static void showEdit(final Activity act, final Menu.Item it, final OnChanged cb) {
        LinearLayout box = formBox(act);
        final EditText name = field(act, box, "菜名", it.name);
        final EditText price = field(act, box, "心动值", String.valueOf(it.price));
        price.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText desc = field(act, box, "一句话说明", it.desc);
        final EditText specs = field(act, box, "规格，用顿号/逗号分开（留空=不用选规格）",
                joinSpecs(it));
        box.addView(Ui.tv(act, "留空后这道菜就变成直接点「＋」，不再弹规格",
                10, Ui.TEXT_MUTED, false), top(act, 3));

        DialogKit.custom(act, "编辑「" + it.name + "」", box, "保存", new Runnable() {
            public void run() {
                String nm = name.getText().toString().trim();
                if (nm.length() == 0) {
                    Toast2.show(act, Toast2.WARN, "菜名不能为空，帮你把填好的内容留着");
                    showEdit(act, it, cb);
                    return;
                }
                it.name = nm;
                try {
                    it.price = Integer.parseInt(price.getText().toString().trim());
                } catch (Exception ignore) {
                }
                it.desc = desc.getText().toString().trim();
                it.specs.clear();
                applySpecs(it, specs.getText().toString());
                Toast2.show(act, Toast2.SUCCESS, "已保存「" + nm + "」");
                if (cb != null) {
                    cb.onChanged();
                }
            }
        });
    }

    /** 删除确认 */
    public static void confirmDelete(final Activity act, final Menu.Item it, final OnChanged cb) {
        DialogKit.confirm(act, "删除「" + it.name + "」？",
                "删掉后对方那边同步到也会消失。", "删除", new Runnable() {
                    public void run() {
                        for (Menu.Category c : Menu.CATEGORIES) {
                            if (c.items.remove(it)) {
                                break;
                            }
                        }
                        Toast2.show(act, Toast2.SUCCESS, "已删除");
                        if (cb != null) {
                            cb.onChanged();
                        }
                    }
                });
    }

    // ------------------------------------------------------------------ 小工具

    private static LinearLayout formBox(Activity act) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(act, 16), dp(act, 6), dp(act, 16), dp(act, 6));
        return box;
    }

    private static EditText field(Activity act, LinearLayout box, String hint, String value) {
        EditText e = new EditText(act);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(e, top(act, 8));
        return e;
    }

    private static void applySpecs(Menu.Item it, String raw) {
        if (raw == null) {
            return;
        }
        // 顿号、逗号、空格、换行都当分隔符
        String[] parts = raw.split("[、,，;；\\s]+");
        for (String sp : parts) {
            String s = sp.trim();
            if (s.length() > 0 && s.length() <= 12 && !it.specs.contains(s)) {
                it.specs.add(s);
            }
        }
    }

    private static String joinSpecs(Menu.Item it) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < it.specs.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(it.specs.get(i));
        }
        return sb.toString();
    }

    private static LinearLayout.LayoutParams rowParams(Activity act) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(act, 6);
        return p;
    }

    private static LinearLayout.LayoutParams top(Activity act, int dpVal) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(act, dpVal);
        return p;
    }

    private static int dp(Activity act, float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
