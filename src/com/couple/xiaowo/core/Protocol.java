package com.couple.xiaowo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 两端 App 之间唯一的"语言"。
 *
 * 所有消息都是 UTF-8 的 JSON，通过局域网 HTTP（TCP 8765）传输，
 * 发现对端用 UDP 广播（8766）。协议刻意保持极简，方便你把来
 * 换成公网中继/WebSocket 也不改业务代码。
 *
 * 消息一览：
 *   order  点单端 → 接单端   一次下单（菜品、数量、备注、回信地址）
 *   ack    接单端 → 点单端   确认收到
 *   heart  接单端 → 点单端   "回一个爱心"
 *   here   接单端 → 点单端   UDP 发现应答（我在、我的端口是多少）
 */
public final class Protocol {

    public static final String ROLE_ORDER = "order";   // 点单端（猫猫）
    public static final String ROLE_RECV = "recv";     // 接单端（张老师）

    /** 双方显示名 */
    public static final String NAME_ORDER = "猫猫";
    public static final String NAME_RECV = "张老师";

    public static final String TYPE_ORDER = "order";
    public static final String TYPE_ACK = "ack";
    public static final String TYPE_HEART = "heart";
    public static final String TYPE_DISCOVER = "discover";
    public static final String TYPE_NOTICE = "notice";
    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_MENU = "menu";
    public static final String TYPE_UPDATE_ACK = "updateAck";
    public static final String TYPE_BUZZ = "buzz";
    public static final String TYPE_HERE = "here";
    /** 点单端 → 接单端：我还在线（多台点单端时用来数人数） */
    public static final String TYPE_PRESENCE = "presence";
    public static final String TYPE_SYNC = "sync";
    public static final String TYPE_SHOT = "shot";
    public static final String TYPE_SHOT_IMG = "shotImg";

    public static final String PATH_ORDER = "/order";
    public static final String PATH_HEART = "/heart";
    public static final String PATH_PING = "/ping";
    /** 接单端 → 点单端：公告 */
    public static final String PATH_NOTICE = "/notice";
    /** 双向：聊天 */
    public static final String PATH_CHAT = "/chat";
    /** 点单端 → 接单端：菜谱同步 */
    public static final String PATH_MENU = "/menu";
    /** 点单端 → 接单端：在线心跳（带设备编号和回信地址） */
    public static final String PATH_PRESENCE = "/presence";
    /** 点单端 → 接单端：安装回执（确认点单端真的更新成功了） */
    public static final String PATH_ACK_UPDATE = "/update/ack";
    /** 接单端 → 点单端：远程震动指令 */
    public static final String PATH_BUZZ = "/buzz";
    /** 双向：订单记录 + 聊天记录同步 */
    public static final String PATH_SYNC = "/sync";
    /** 接单端 → 点单端：请求截图 */
    public static final String PATH_SHOT = "/shot";

    private Protocol() {
    }

    // ------------------------------------------------------------------ 模型

    /** 点单里的一个菜品条目。 */
    public static final class Line {
        public String name = "";
        public int qty = 1;
        public int price = 0;
        public int cat = 0;          // 猫猫头像的配色/表情编号
        public String emoji = "";
        /**
         * 规格（可选）。空串表示这道菜没选规格。
         *
         * 为什么不做成数组：一单里同一种菜可以点两个不同规格
         * （"要亲亲·脸颊"+"要亲亲·嘴巴"），做成"菜+规格"组合的一行更直观，
         * 接单端按行显示也才看得懂到底要的是哪个。
         */
        public String spec = "";

        public Line() {
        }

        public Line(String name, int qty, int price, int cat, String emoji) {
            this(name, qty, price, cat, emoji, "");
        }

        public Line(String name, int qty, int price, int cat, String emoji, String spec) {
            this.name = name;
            this.qty = qty;
            this.price = price;
            this.cat = cat;
            this.emoji = emoji;
            this.spec = spec == null ? "" : spec;
        }

        /** 「菜名·规格」——界面上统一用这个显示 */
        public String displayName() {
            return spec.length() == 0 ? name : name + "·" + spec;
        }

        public int subtotal() {
            return qty * price;
        }
    }

    /** 一张订单。 */
    public static final class Order {
        public String id = "";
        public String note = "";
        public long ts = 0L;
        public int total = 0;
        public String fromName = "小窝·点单";
        /**
         * 下单的是哪台点单端（见 Client）。
         * 老版本没有这个字段，所以读的时候允许为空 —— 空就只当"点单端"。
         */
        public String fromId = "";
        public String replyHost = "";
        public int replyPort = 0;
        public final List<Line> lines = new ArrayList<Line>();

        public String toJson() {
            List<String> items = new ArrayList<String>();
            for (Line l : lines) {
                items.add(Json.object(Json.fields(
                        "name", Json.quote(l.name),
                        "qty", String.valueOf(l.qty),
                        "price", String.valueOf(l.price),
                        "cat", String.valueOf(l.cat),
                        "emoji", Json.quote(l.emoji),
                        "spec", Json.quote(l.spec))));
            }
            return Json.object(Json.fields(
                    "type", Json.quote(TYPE_ORDER),
                    "id", Json.quote(id),
                    "from", Json.quote(ROLE_ORDER),
                    "fromName", Json.quote(fromName),
                    "fromId", Json.quote(fromId),
                    "ts", String.valueOf(ts),
                    "note", Json.quote(note),
                    "total", String.valueOf(total),
                    "replyHost", Json.quote(replyHost),
                    "replyPort", String.valueOf(replyPort),
                    "items", Json.array(items)));
        }

        /** 通知里用的一句话摘要 */
        public String summaryForNotify() {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(l.displayName());
                if (l.qty > 1) {
                    sb.append(" ×").append(l.qty);
                }
            }
            if (note != null && note.length() > 0) {
                sb.append("　「").append(note).append("」");
            }
            sb.append("　共 ❤").append(total);
            return sb.toString();
        }

        public static Order fromJson(String text) {
            Map<String, Object> m = Json.asObject(Json.parse(text));
            Order o = new Order();
            o.id = Json.str(m, "id", "");
            o.note = Json.str(m, "note", "");
            o.ts = Json.num(m, "ts", System.currentTimeMillis());
            o.total = (int) Json.num(m, "total", 0);
            o.fromName = Json.str(m, "fromName", "小窝·点单");
            o.fromId = Json.str(m, "fromId", "");
            o.replyHost = Json.str(m, "replyHost", "");
            o.replyPort = (int) Json.num(m, "replyPort", 0);
            for (Object it : Json.list(m, "items")) {
                Map<String, Object> im = Json.asObject(it);
                Line l = new Line();
                l.name = Json.str(im, "name", "");
                l.spec = Json.str(im, "spec", "");
                l.qty = (int) Json.num(im, "qty", 1);
                l.price = (int) Json.num(im, "price", 0);
                l.cat = (int) Json.num(im, "cat", 0);
                l.emoji = Json.str(im, "emoji", "");
                o.lines.add(l);
            }
            if (o.total <= 0) {
                int t = 0;
                for (Line l : o.lines) {
                    t += l.subtotal();
                }
                o.total = t;
            }
            return o;
        }
    }

    // ------------------------------------------------------------------ 编码辅助

    public static String ack(boolean ok, String role, String msg) {
        return Json.object(Json.fields(
                "ok", String.valueOf(ok),
                "type", Json.quote(TYPE_ACK),
                "role", Json.quote(role),
                "msg", Json.quote(msg),
                "ts", String.valueOf(System.currentTimeMillis())));
    }

    /** 点单端安装完成后的回执 */
    public static String updateAck(int versionCode, String versionName) {
        return Json.object(Json.fields(
                "type", Json.quote(TYPE_UPDATE_ACK),
                "role", Json.quote(ROLE_ORDER),
                "versionCode", String.valueOf(versionCode),
                "versionName", Json.quote(versionName),
                "ts", String.valueOf(System.currentTimeMillis())));
    }

    /** 接单端让点单端震动。pattern 参考 Android Vibrator 的 long[] 语义 */
    public static String buzz(String reason, int ms, String from) {
        return Json.object(Json.fields(
                "type", Json.quote(TYPE_BUZZ),
                "role", Json.quote(ROLE_RECV),
                "reason", Json.quote(reason),
                "ms", String.valueOf(ms),
                "fromName", Json.quote(from),
                "ts", String.valueOf(System.currentTimeMillis())));
    }

    /** 接单端发起：请求点单端截屏 */
    public static String shot(String from) {
        return Json.object(Json.fields(
                "type", Json.quote(TYPE_SHOT),
                "role", Json.quote(ROLE_RECV),
                "fromName", Json.quote(from),
                "ts", String.valueOf(System.currentTimeMillis())));
    }

    /** 点单端回传：截图（base64） */
    public static String shotImg(String base64) {
        return Json.object(Json.fields(
                "type", Json.quote(TYPE_SHOT_IMG),
                "role", Json.quote(ROLE_ORDER),
                "img", Json.quote(base64),
                "ts", String.valueOf(System.currentTimeMillis())));
    }

    public static boolean isOk(String response) {
        try {
            return Boolean.parseBoolean(Json.str(Json.asObject(Json.parse(response)), "ok", "false"));
        } catch (Exception e) {
            return false;
        }
    }

    public static String heart(String fromName, String text) {
        return Json.object(Json.fields(
                "ok", "true",
                "type", Json.quote(TYPE_HEART),
                "fromName", Json.quote(fromName),
                "text", Json.quote(text),
                "ts", String.valueOf(System.currentTimeMillis())));
    }

    public static String discover() {
        return Json.object(Json.fields(
                "type", Json.quote(TYPE_DISCOVER),
                "role", Json.quote(ROLE_ORDER),
                "name", Json.quote("小窝·点单")));
    }

    /**
     * 点单端的在线心跳。
     *
     * 带上自己的回信地址（host/port）是有意的：接单端要按"在线的那几台"
     * 逐个发消息，只靠"最近一单的 replyHost"在多台点单端时会漏人。
     */
    public static String presence(String id, String name, String host, int port) {
        return Json.object(Json.fields(
                "type", Json.quote(TYPE_PRESENCE),
                "role", Json.quote(ROLE_ORDER),
                "id", Json.quote(id),
                "name", Json.quote(name),
                "host", Json.quote(host),
                "port", String.valueOf(port),
                "ts", String.valueOf(System.currentTimeMillis())));
    }

    public static String here(String name, int port) {
        return Json.object(Json.fields(
                "type", Json.quote(TYPE_HERE),
                "role", Json.quote(ROLE_RECV),
                "name", Json.quote(name),
                "port", String.valueOf(port),
                "ts", String.valueOf(System.currentTimeMillis())));
    }

    public static String typeOf(String json) {
        try {
            return Json.str(Json.asObject(Json.parse(json)), "type", "");
        } catch (Exception e) {
            return "";
        }
    }
}
