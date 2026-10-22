package com.couple.xiaowo.core;

import java.util.Map;

/**
 * 购物车键：「菜名 + 规格」。
 *
 * ── 为什么需要这层封装 ──
 *
 * 同一道菜可以点两个不同规格：
 *   要亲亲·脸颊  ×1
 *   要亲亲·嘴巴  ×1
 * 这两行在购物车里必须是**两条独立记录**，计数也要各算各的。
 * 如果只用菜名当 key，第二个规格会把第一个覆盖掉。
 *
 * 所以 key 用「菜名 + 分隔符 + 规格」拼出来。
 *
 * ── 为什么用 \u0001 当分隔符 ──
 *
 * 菜名和规格都是用户能输入的。用 '|'、'-'、':' 这类常见字符，
 * 菜名里一旦出现就会歧义（"奶茶|大杯" 到底是一道叫"奶茶|大杯"的菜，
 * 还是"奶茶"的"大杯"？）。\u0001 是不可打印的控制字符，
 * 输入法打不出来，几乎不可能出现在菜名里。
 */
public final class CartKey {

    private static final char SEP = '\u0001';

    private CartKey() {
    }

    /** 拼购物车键；spec 为空时直接用菜名 */
    public static String of(String name, String spec) {
        String n = name == null ? "" : name;
        String s = spec == null ? "" : spec;
        return s.length() == 0 ? n : n + SEP + s;
    }

    /** 从键里取菜名（不含规格） */
    public static String nameOf(String key) {
        if (key == null) {
            return "";
        }
        int i = key.indexOf(SEP);
        return i < 0 ? key : key.substring(0, i);
    }

    /** 从键里取规格（可能为空串） */
    public static String specOf(String key) {
        if (key == null) {
            return "";
        }
        int i = key.indexOf(SEP);
        return i < 0 ? "" : key.substring(i + 1);
    }

    /**
     * 按菜名汇总某个菜的总份数 —— 列表右侧的 +/− 要用它。
     *
     * 因为同一种菜可能散在多个规格行里，点 + 时应该选规格，
     * 但显示数量时要把所有规格加起来，不然用户会以为"点了没反应"。
     */
    public static int totalQty(Map<String, Integer> cart, String name) {
        int n = 0;
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            if (nameOf(e.getKey()).equals(name)) {
                n += e.getValue();
            }
        }
        return n;
    }

    /** 清掉某道菜的所有规格行 */
    public static void removeAll(Map<String, Integer> cart, String name) {
        java.util.List<String> drop = new java.util.ArrayList<String>();
        for (String k : cart.keySet()) {
            if (nameOf(k).equals(name)) {
                drop.add(k);
            }
        }
        for (String k : drop) {
            cart.remove(k);
        }
    }
}
