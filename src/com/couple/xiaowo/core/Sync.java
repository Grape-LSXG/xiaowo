package com.couple.xiaowo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 订单 + 聊天记录同步的载荷。
 *
 * 需求：两端每次启动时都自动同步订单记录和聊天记录。
 *
 * 做法：启动后（延迟几秒，等界面稳定）向对方 POST 一份自己的
 * order_history + chat，对方收到后按 ts 合并去重。
 *
 * 为什么用"全量合并"而不是"增量"：
 *   记录量很小（订单几十单、聊天最多 200 条），全量最简单也最不容易丢。
 *   去重靠 ts + 内容指纹，幂等，重复同步不会产生重复记录。
 */
public final class Sync {

    // 各自的字段名
    private static final String K_HISTORY = "history";
    private static final String K_CHAT = "chat";
    private static final String K_TS = "syncTs";

    private Sync() {
    }

    /** 构造一份同步载荷 */
    public static String build(List<History.Entry> history, List<Social.Msg> chat) {
        return Json.object(Json.fields(
                "type", Json.quote(Protocol.TYPE_SYNC),
                K_TS, String.valueOf(System.currentTimeMillis()),
                K_HISTORY, Json.array(HistoryJson.historyToParts(history)),
                K_CHAT, Json.array(SocialJson.chatToParts(chat))));
    }

    /** 解析同步载荷；不是 sync 类型返回 null */
    public static Parsed parse(String body) {
        try {
            Map<String, Object> m = Json.asObject(Json.parse(body));
            if (!Protocol.TYPE_SYNC.equals(Json.str(m, "type", ""))) {
                return null;
            }
            Parsed p = new Parsed();
            for (Object it : Json.list(m, K_HISTORY)) {
                p.history.add(History.Entry.fromJson(it));
            }
            for (Object it : Json.list(m, K_CHAT)) {
                Map<String, Object> im = Json.asObject(it);
                Social.Msg g = new Social.Msg();
                g.who = Json.str(im, "who", "cat");
                g.text = Json.str(im, "text", "");
                g.ts = Json.num(im, "ts", 0);
                p.chat.add(g);
            }
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Parsed {
        public final List<History.Entry> history = new ArrayList<History.Entry>();
        public final List<Social.Msg> chat = new ArrayList<Social.Msg>();
    }

    // 内嵌小工具，避免在 History/Social 里加太多公开 API
    static final class HistoryJson {
        static List<String> historyToParts(List<History.Entry> list) {
            List<String> out = new ArrayList<String>();
            for (History.Entry e : list) {
                out.add(e.toJson());
            }
            return out;
        }
    }

    static final class SocialJson {
        static List<String> chatToParts(List<Social.Msg> list) {
            List<String> out = new ArrayList<String>();
            for (Social.Msg m : list) {
                out.add(m.toJson());
            }
            return out;
        }
    }
}