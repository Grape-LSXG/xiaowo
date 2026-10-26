package com.couple.xiaowo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 公告 和 聊天消息。
 *
 * 都走同一套 HTTP + JSON，只是路径不同：
 *   /notice  —— 接单端发布公告，点单端拉到后显示在厨房页顶部
 *   /chat    —— 双向聊天，两边都能发
 *
 * 设计上刻意保持"离线也好看"：
 *   · 公告是"最新一条生效"，不需要历史
 *   · 聊天只在本机存最近 200 条
 */
public final class Social {

    // ------------------------------------------------------------------ 公告

    public static final class Notice {
        public String from = "张老师";
        public String text = "";
        public long ts = 0L;

        public String toJson() {
            // 同上：中转通道依靠 type 分发，缺了就会被丢
            return Json.object(Json.fields(
                    "type", Json.quote(Protocol.TYPE_NOTICE),
                    "from", Json.quote(from),
                    "text", Json.quote(text),
                    "ts", String.valueOf(ts)));
        }

        public static Notice fromJson(String body) {
            Map<String, Object> m = Json.asObject(Json.parse(body));
            Notice n = new Notice();
            n.from = Json.str(m, "from", "张老师");
            n.text = Json.str(m, "text", "");
            n.ts = Json.num(m, "ts", System.currentTimeMillis());
            return n;
        }
    }

    // ------------------------------------------------------------------ 聊天

    public static final class Msg {
        /** "cat" = 猫猫（点单方），"teacher" = 张老师（接单方） */
        public String who = "cat";
        public String text = "";
        public long ts = 0L;
        public boolean mine = false;
        /**
         * 发信那一台的识别码（多台点单端时用来看清"这句是谁说的"）。
         * 老版本没有这个字段，读出来是空串，界面就退回原样。
         */
        public String from = "";

        public String toJson() {
            /*
             * 必须带 type。
             *
             * 走 HTTP 时接收方靠路径 /chat 就能认出是聊天；
             * 但走 MQTT 中转时，接收方是用 Protocol.typeOf(payload) 判断类型的 ——
             * 没有 type 字段就得到空字符串，消息会被直接丢掉。
             * 这正是"消息发不出去"的根因。
             */
            return Json.object(Json.fields(
                    "type", Json.quote(Protocol.TYPE_CHAT),
                    "who", Json.quote(who),
                    "from", Json.quote(from),
                    "text", Json.quote(text),
                    "mine", mine ? "true" : "false",
                    "ts", String.valueOf(ts)));
        }

        public static Msg fromJson(String body) {
            Map<String, Object> m = Json.asObject(Json.parse(body));
            Msg g = new Msg();
            g.who = Json.str(m, "who", "cat");
            g.from = Json.str(m, "from", "");
            g.text = Json.str(m, "text", "");
            g.ts = Json.num(m, "ts", System.currentTimeMillis());
            return g;
        }
    }

    private Social() {
    }

    // ------------------------------------------------------------------ 序列化

    public static String msgsToJson(List<Msg> list) {
        List<String> parts = new ArrayList<String>();
        for (Msg m : list) {
            parts.add(m.toJson());
        }
        return Json.array(parts);
    }

    /**
     * 解析聊天记录。
     *
     * @param catIsMe 本机是不是「猫猫」那一端（点单端 true / 接单端 false）
     *
     * ── 这里为什么必须按 who 推导 mine ──
     *
     * 以前这个参数收下了却**没用**，mine 一律取默认 false。结果：
     * 自己发的消息被存进 SharedPreferences 后，重开 App 全部变回"对方发的"，
     * 气泡左右位置也一起错乱 —— 这就是"自己发的消息刷新后变成对方的"根因。
     *
     * 现在统一按 who 推导，不信任序列化里的 mine：
     *   who == "cat" 且本机是点单端  → 是我
     *   who == "teacher" 且本机是接单端 → 是我
     * 这样跨设备同步过来的记录也能算对（对方存的 mine 对我们没意义）。
     */
    public static List<Msg> parseMsgs(String json, boolean catIsMe) {
        List<Msg> out = new ArrayList<Msg>();
        if (json == null || json.length() == 0) {
            return out;
        }
        try {
            Object o = Json.parse(json);
            if (o instanceof List) {
                out = parseMsgsList((List<?>) o, catIsMe);
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /** 把已经解析成 List 的消息对象转成 Msg（导入备份时用） */
    public static List<Msg> parseMsgsList(List<?> items, boolean catIsMe) {
        List<Msg> out = new ArrayList<Msg>();
        if (items == null) {
            return out;
        }
        for (Object item : items) {
            Map<String, Object> m = Json.asObject(item);
            Msg g = new Msg();
            g.who = Json.str(m, "who", "cat");
            g.text = Json.str(m, "text", "");
            g.ts = Json.num(m, "ts", 0);
            g.mine = mineForWho(g.who, catIsMe);
            out.add(g);
        }
        return out;
    }

    /**
     * 按「发言者」和「本机角色」判断这条消息是不是我发的。
     *
     * who: "cat" = 点单端（猫猫） / "teacher" = 接单端（张老师）
     */
    public static boolean mineForWho(String who, boolean catIsMe) {
        boolean isCat = "cat".equals(who);
        return isCat == catIsMe;
    }

    /** 公告存成单条 JSON */
    public static String noticeToJson(Notice n) {
        return n == null ? "" : n.toJson();
    }

    public static Notice parseNotice(String json) {
        if (json == null || json.length() == 0) {
            return null;
        }
        try {
            Map<String, Object> m = Json.asObject(Json.parse(json));
            Notice n = new Notice();
            n.from = Json.str(m, "from", "张老师");
            n.text = Json.str(m, "text", "");
            n.ts = Json.num(m, "ts", 0);
            return n.text.length() == 0 ? null : n;
        } catch (Exception e) {
            return null;
        }
    }
}
