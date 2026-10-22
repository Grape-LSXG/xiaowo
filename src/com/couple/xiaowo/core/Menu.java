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
        /**
         * 可选规格。空表示这道菜没有规格可选（点的时候直接加进购物车）。
         *
         * 为什么放在 Item 上而不是单独建表：
         * 规格是"这道菜怎么点"的一部分，跟着菜走最自然 ——
         * 菜谱同步、自定义菜、持久化都只需要多带一个数组。
         */
        public final java.util.List<String> specs = new java.util.ArrayList<String>();

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
            java.util.List<String> specParts = new java.util.ArrayList<String>();
            for (String sp : specs) {
                specParts.add(Json.quote(sp));
            }
            return Json.object(Json.fields(
                    "name", Json.quote(name),
                    "price", String.valueOf(price),
                    "cat", String.valueOf(cat),
                    "emoji", Json.quote(emoji),
                    "desc", Json.quote(desc),
                    "specs", Json.array(specParts),
                    "from", Json.quote("猫猫")));
        }

        public static Item fromJson(java.util.Map<String, Object> m) {
            Item it = new Item(
                    Json.str(m, "name", ""),
                    (int) Json.num(m, "price", 0),
                    (int) Json.num(m, "cat", 0),
                    Json.str(m, "emoji", "🐾"),
                    Json.str(m, "desc", ""),
                    5.0f, 0, false);
            for (Object o : Json.list(m, "specs")) {
                String sp = String.valueOf(o);
                if (sp != null && sp.length() > 0) {
                    it.specs.add(sp);
                }
            }
            return it;
        }

        /** 有没有可选规格 */
        public boolean hasSpecs() {
            return !specs.isEmpty();
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

    /**
     * 内置默认菜单的规格快照（static 块里填充，之后只读）。
     *
     * 存在的理由：升级后用旧版本保存的菜单替换时，要能查回"这道菜原本有哪些规格"。
     * 因为替换会把 CATEGORIES 变成旧数据，那时就读不到内置规格了。
     */
    private static final List<Category> BUILTIN = new ArrayList<Category>();

    private static void add(Category c, String name, int price, int cat, String emoji, String desc) {
        c.items.add(new Item(name, price, cat, emoji, desc, 5.0f, 0));
    }

    /** 带规格的菜：点的时候要先选一个规格 */
    private static void add(Category c, String name, int price, int cat, String emoji,
                            String desc, String... specs) {
        Item it = new Item(name, price, cat, emoji, desc, 5.0f, 0);
        for (String sp : specs) {
            it.specs.add(sp);
        }
        c.items.add(it);
    }

    static {
        Category miss = new Category("想你了", "💗");
        add(miss, "要亲亲", 5, 0, "💋", "现在、立刻、马上",
                "额头", "脸颊", "嘴巴", "抱起来亲");
        add(miss, "要抱抱", 5, 1, "🫂", "抱到不想松手那种",
                "轻轻抱", "紧紧抱", "从背后抱", "抱起来转圈");
        add(miss, "要夸夸", 5, 2, "✨", "今天也要被夸好看",
                "夸好看", "夸可爱", "夸厉害", "夸我全对");
        add(miss, "陪陪我", 8, 3, "🐾", "什么都不做也行",
                "聊会儿天", "安静待着", "陪我看剧", "陪我发呆");
        add(miss, "摸摸头", 3, 4, "🖐️", "顺毛，很轻的",
                "顺着摸", "轻轻拍", "揉一揉", "摸到睡着");
        // 规格太牵强，改回无规格（直接 +）
        add(miss, "说晚安", 2, 5, "🌙", "睡前必须要的");
        add(miss, "讲个笑话", 4, 6, "😹", "不好笑也要笑");
        CATEGORIES.add(miss);

        Category takeout = new Category("点外卖", "🧋");
        add(takeout, "一杯奶茶", 12, 6, "🧋", "三分糖，去冰",
                "全糖", "七分糖", "半糖", "三分糖", "无糖", "常温", "少冰", "去冰");
        add(takeout, "炸鸡桶", 18, 7, "🍗", "罪恶但快乐",
                "原味", "香辣", "蜂蜜芥末", "一半一半");
        add(takeout, "小蛋糕", 15, 2, "🍰", "就一小块",
                "草莓", "巧克力", "抹茶", "芝士");
        add(takeout, "冰淇淋", 9, 0, "🍦", "天冷也要吃",
                "香草", "巧克力", "芒果", "双拼");
        CATEGORIES.add(takeout);

        Category doWhat = new Category("做什么", "🎬");
        add(doWhat, "看电影", 20, 3, "🎬", "你选片我买票",
                "电影院", "在家看", "恐怖片", "爱情片", "动画片");
        add(doWhat, "去散步", 5, 4, "🌿", "绕小区两圈");
        add(doWhat, "一起打游戏", 10, 5, "🎮", "输了不许生气",
                "合作过关", "互相打", "你带我", "随便玩");
        add(doWhat, "拍张合照", 6, 1, "📷", "今天也很好看",
                "正经的", "搞怪的", "十指相扣", "自拍一张");
        CATEGORIES.add(doWhat);

        Category mood = new Category("有情绪", "🥺");
        add(mood, "哄哄我", 6, 7, "🥺", "我不说但你要懂",
                "要哄哄", "听抱怨", "你认错", "我认错", "想骂人");
        add(mood, "撒个娇", 3, 1, "🌷", "就一下下");
        add(mood, "求安慰", 4, 5, "🫧", "今天有点累",
                "工作累", "心情差", "身体不舒服", "就想被照顾");
        add(mood, "委屈巴巴", 5, 4, "💧", "需要抱抱续命",
                "你凶我了", "忽略我了", "没回消息", "就是委屈");
        CATEGORIES.add(mood);

        Category money = new Category("不够花", "💰");
        add(money, "零花钱", 30, 2, "💰", "自愿上交",
                "这周的", "这月的", "小额救急", "大额存着");
        add(money, "想要个小礼物", 25, 6, "🎁", "不用很贵",
                "吃的", "用的", "戴的", "你挑");
        add(money, "清空购物车", 50, 0, "🛒", "这个有点过分");
        CATEGORIES.add(money);

        // 记录内置菜单的规格快照：升级后用旧数据替换时，靠它把规格补回来
        for (Category c : CATEGORIES) {
            Category copy = new Category(c.name, c.icon);
            for (Item it : c.items) {
                Item ci = new Item(it.name, it.price, it.cat, it.emoji, it.desc,
                        it.rating, it.monthly, false);
                ci.specs.addAll(it.specs);
                copy.items.add(ci);
            }
            BUILTIN.add(copy);
        }
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
        /*
         * 补齐规格（升级兼容）。
         *
         * 老版本存下来的菜单 JSON 里没有 specs 字段。如果直接用它替换，
         * 发新版内置的规格会全部消失 —— 用户升级后看到的就是
         * "怎么还是没有选规格的按钮"（不是功能没做，是被旧数据盖掉了）。
         *
         * 做法：拿内置默认菜单当"规格字典"，同名菜把规格补回去。
         * 用户自己改过的规格优先保留（非空就不动）。
         */
        for (Category c : fresh) {
            for (Item it : c.items) {
                if (it.specs.size() > 0) {
                    continue;
                }
                List<String> builtin = builtinSpecsOf(it.name);
                if (builtin != null) {
                    it.specs.addAll(builtin);
                }
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
                            if (!it.custom) {
                                continue;
                            }
                            /*
                             * 去重：同名自定义菜若已经存在，就不再追加。
                             *
                             * 原来这里是无条件 c.items.add(it) —— 而 keep 来自
                             * 上一轮的 CATEGORIES，它本身可能已经含有一份同名
                             * 自定义菜。多同步几次就会堆出好几份重复项，
                             * 菜单里出现"要摸摸头"×3 这种。
                             *
                             * 补一条：如果新菜单里也有同名（用户可能改名后又撞名），
                             * 就用新传过来的那份为准，不保留旧的。
                             */
                            boolean exists = false;
                            for (Item old : c.items) {
                                if (old.name.equals(it.name)) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
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

    /**
     * 内置默认菜单里同名菜的规格（升级兼容用）。
     *
     * 注意：必须读**静态快照**而不是 CATEGORIES —— applyJson 里
     * CATEGORIES 可能已经被 fresh 替换掉了，那时再读就没有内置数据了。
     */
    public static List<String> builtinSpecsOf(String name) {
        if (name == null) {
            return null;
        }
        for (Category c : BUILTIN) {
            for (Item it : c.items) {
                if (it.name.equals(name) && it.specs.size() > 0) {
                    return new ArrayList<String>(it.specs);
                }
            }
        }
        return null;
    }

    /** 对照用：菜单指纹，变了才需要同步 */
    public static String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (Category c : CATEGORIES) {
            sb.append(c.name).append('|');
            for (Item it : c.items) {
                /*
                 * 必须把规格算进指纹。
                 * 漏了它的话，用户只改规格（菜名价格都不变）时指纹不变，
                 * 菜谱同步就不会触发 —— 对方永远收不到新加的规格。
                 */
                sb.append(it.name).append(',').append(it.price);
                for (String sp : it.specs) {
                    sb.append('/').append(sp);
                }
                sb.append(';');
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
