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
                    "text", Json.quote(text),
                    "ts", String.valueOf(ts)));
        }

        public static Msg fromJson(String body) {
            Map<String, Object> m = Json.asObject(Json.parse(body));
            Msg g = new Msg();
            g.who = Json.str(m, "who", "cat");
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

    public static List<Msg> parseMsgs(String json, boolean mineWhenCat) {
        List<Msg> out = new ArrayList<Msg>();
        if (json == null || json.length() == 0) {
            return out;
        }
        try {
            Object o = Json.parse(json);
            if (o instanceof List) {
                for (Object item : (List<?>) o) {
                    Map<String, Object> m = Json.asObject(item);
                    Msg g = new Msg();
                    g.who = Json.str(m, "who", "cat");
                    g.text = Json.str(m, "text", "");
                    g.ts = Json.num(m, "ts", 0);
                    out.add(g);
                }
            }
        } catch (Exception ignore) {
        }
        return out;
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
