package com.couple.xiaowo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 菜单数据。完全照着你截图里"小窝"点单器的思路做：
 * 左侧分类、右侧菜品、每道菜标价（心动值）、评分、月销。
 * 这些数据在真机上就是恋人之间可以点的"服务"。
 */
public final class Menu {

    public static final class Item {
        public String name;
        public int price;            // 心动值
        public int cat;              // 猫猫头像样式 0..7
        public String emoji;         // 角标 emoji
        public String desc;          // 一句话说明
        public float rating;
        public int monthly;          // 月销
        /** 用户自己加的菜（可以删） */
        public boolean custom;

        public Item(String name, int price, int cat, String emoji, String desc,
                    float rating, int monthly) {
            this(name, price, cat, emoji, desc, rating, monthly, false);
        }

        public Item(String name, int price, int cat, String emoji, String desc,
                    float rating, int monthly, boolean custom) {
            this.name = name;
            this.price = price;
            this.cat = cat;
            this.emoji = emoji;
            this.desc = desc;
            this.rating = rating;
            this.monthly = monthly;
            this.custom = custom;
        }

        public String toJson() {
            return Json.object(Json.fields(
                    "name", Json.quote(name),
                    "price", String.valueOf(price),
                    "cat", String.valueOf(cat),
                    "emoji", Json.quote(emoji),
                    "desc", Json.quote(desc),
                    "from", Json.quote("猫猫")));
        }

        public static Item fromJson(java.util.Map<String, Object> m) {
            return new Item(
                    Json.str(m, "name", ""),
                    (int) Json.num(m, "price", 0),
                    (int) Json.num(m, "cat", 0),
                    Json.str(m, "emoji", "🐾"),
                    Json.str(m, "desc", ""),
                    5.0f, 0, false);
        }
    }

    public static final class Category {
        public String name;
        public String icon;
        public final List<Item> items = new ArrayList<Item>();

        Category(String name, String icon) {
            this.name = name;
            this.icon = icon;
        }

        public String toJson() {
            List<String> parts = new ArrayList<String>();
            for (Item it : items) {
                parts.add(it.toJson());
            }
            return Json.object(Json.fields(
                    "cat", Json.quote(name),
                    "icon", Json.quote(icon),
                    "items", Json.array(parts)));
        }
    }

    public static final List<Category> CATEGORIES = new ArrayList<Category>();

    private static void add(Category c, String name, int price, int cat, String emoji, String desc) {
        c.items.add(new Item(name, price, cat, emoji, desc, 5.0f, 0));
    }

    static {
        Category miss = new Category("想你了", "💗");
        add(miss, "要亲亲", 5, 0, "💋", "现在、立刻、马上");
        add(miss, "要抱抱", 5, 1, "🫂", "抱到不想松手那种");
        add(miss, "要夸夸", 5, 2, "✨", "今天也要被夸好看");
        add(miss, "陪陪我", 8, 3, "🐾", "什么都不做也行");
        add(miss, "摸摸头", 3, 4, "🖐️", "顺毛，很轻的");
        add(miss, "说晚安", 2, 5, "🌙", "睡前必须要的");
        add(miss, "讲个笑话", 4, 6, "😹", "不好笑也要笑");
        CATEGORIES.add(miss);

        Category takeout = new Category("点外卖", "🧋");
        add(takeout, "一杯奶茶", 12, 6, "🧋", "三分糖，去冰");
        add(takeout, "炸鸡桶", 18, 7, "🍗", "罪恶但快乐");
        add(takeout, "小蛋糕", 15, 2, "🍰", "就一小块");
        add(takeout, "冰淇淋", 9, 0, "🍦", "天冷也要吃");
        CATEGORIES.add(takeout);

        Category doWhat = new Category("做什么", "🎬");
        add(doWhat, "看电影", 20, 3, "🎬", "你选片我买票");
        add(doWhat, "去散步", 5, 4, "🌿", "绕小区两圈");
        add(doWhat, "一起打游戏", 10, 5, "🎮", "输了不许生气");
        add(doWhat, "拍张合照", 6, 1, "📷", "今天也很好看");
        CATEGORIES.add(doWhat);

        Category mood = new Category("有情绪", "🥺");
        add(mood, "哄哄我", 6, 7, "🥺", "我不说但你要懂");
        add(mood, "撒个娇", 3, 1, "🌷", "就一下下");
        add(mood, "求安慰", 4, 5, "🫧", "今天有点累");
        add(mood, "委屈巴巴", 5, 4, "💧", "需要抱抱续命");
        CATEGORIES.add(mood);

        Category money = new Category("不够花", "💰");
        add(money, "零花钱", 30, 2, "💰", "自愿上交");
        add(money, "想要个小礼物", 25, 6, "🎁", "不用很贵");
        add(money, "清空购物车", 50, 0, "🛒", "这个有点过分");
        CATEGORIES.add(money);
    }

    // ================================================================== 菜谱同步

    /**
     * 把当前菜单导出成 JSON —— 点单端改完菜谱后发给接单端。
     * 只发"自定义/改动过"的内容不现实（Android 端难追踪），所以整表同步，
     * 数据量很小（几百字节）。
     */
    public static String toJson() {
        List<String> parts = new ArrayList<String>();
        for (Category c : CATEGORIES) {
            parts.add(c.toJson());
        }
        return Json.object(Json.fields(
                "type", Json.quote(Protocol.TYPE_MENU),
                "from", Json.quote(Protocol.NAME_ORDER),
                "ts", String.valueOf(System.currentTimeMillis()),
                "categories", Json.array(parts)));
    }

    /** 接单端收到后原地替换（保留自定义菜） */
    public static synchronized void applyJson(String body, boolean replaceAll) {
        Map<String, Object> root = Json.asObject(Json.parse(body));
        List<Object> cats = Json.list(root, "categories");
        if (cats.isEmpty()) {
            return;
        }
        List<Category> fresh = new ArrayList<Category>();
        for (Object co : cats) {
            Map<String, Object> cm = Json.asObject(co);
            Category c = new Category(Json.str(cm, "cat", "未命名"),
                    Json.str(cm, "icon", "🐾"));
            for (Object io : Json.list(cm, "items")) {
                c.items.add(Item.fromJson(Json.asObject(io)));
            }
            if (!c.items.isEmpty()) {
                fresh.add(c);
            }
        }
        if (replaceAll) {
            List<Category> keep = new ArrayList<Category>();
            for (Category c : CATEGORIES) {
                boolean hasCustom = false;
                for (Item it : c.items) {
                    if (it.custom) {
                        hasCustom = true;
                    }
                }
                if (hasCustom) {
                    keep.add(c);
                }
            }
            CATEGORIES.clear();
            CATEGORIES.addAll(fresh);
            for (Category k : keep) {
                boolean merged = false;
                for (Category c : CATEGORIES) {
                    if (c.name.equals(k.name)) {
                        for (Item it : k.items) {
                            if (it.custom) {
                                c.items.add(it);
                            }
                        }
                        merged = true;
                    }
                }
                if (!merged) {
                    CATEGORIES.add(k);
                }
            }
        }
    }

    /** 对照用：菜单指纹，变了才需要同步 */
    public static String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (Category c : CATEGORIES) {
            sb.append(c.name).append('|');
            for (Item it : c.items) {
                sb.append(it.name).append(',').append(it.price).append(';');
            }
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    public static int totalCount() {
        int n = 0;
        for (Category c : CATEGORIES) {
            n += c.items.size();
        }
        return n;
    }

    private Menu() {
    }
}
