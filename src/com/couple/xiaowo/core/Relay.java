package com.couple.xiaowo.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「异地中转」通道。
 *
 * 解决的问题：两个人在**不同城市**时，局域网 UDP 广播出不了路由器，
 * 也拿不到对方的公网 IP，直连必然失败。
 *
 * 方案：用一个**共享房间码**，双方都订阅同一个公共 MQTT topic。
 *   · 点单端把订单发到 topic
 *   · 接单端订阅 topic，收到就当成正常订单处理
 *   · 反向（聊天、公告、震一下）也走同一个 topic
 *
 * 为什么选它：
 *   · 零账号 —— 公共 broker 不需要注册
 *   · 零服务器 —— 不用自己搭
 *   · 零依赖 —— MQTT 3.1.1 简单到能手写（见 Mqtt.java）
 *   · **只需配一次** —— 房间码存本地，之后永不再问
 *
 * 三种通路按优先级：
 *   1) 局域网直连（最快，同一个 Wi-Fi 时）
 *   2) Tailscale（有虚拟内网时，也是直连）
 *   3) MQTT 中转（异地兜底，最慢但最通用）
 *
 * 这里只负责 3)。上层调用 relay(...)，成功就说明送达了。
 */
public final class Relay {

    public static final String KEY_ROOM = "relay_room";
    public static final String KEY_ON = "relay_on";

    private Relay() {
    }

    public static String room(Context c) {
        String r = sp(c).getString(KEY_ROOM, null);
        if (r != null && r.length() > 0) {
            return r;
        }
        // 没配过房间码、但 APK 里预置了云端：
        // 两端都用预置房间码，装好即通，点单端不用再扫码/填码。
        Cloud.load(c);
        if (Cloud.has() && Cloud.room() != null) {
            return Cloud.room();
        }
        return null;
    }

    public static void setRoom(Context c, String room) {
        sp(c).edit().putString(KEY_ROOM, room).apply();
    }

    public static boolean enabled(Context c) {
        String r = room(c);
        if (r == null) {
            return false;
        }
        // 用预置房间码时一律算开启：用户没手动配过、也没手动关过，
        // 就不该被以前遗留的 KEY_ON=false 挡住（否则装好还是连不上）。
        boolean local = sp(c).getString(KEY_ROOM, null) != null;
        if (!local) {
            return true;
        }
        return sp(c).getBoolean(KEY_ON, true);
    }

    public static void setEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_ON, on).apply();
    }

    /** 生成并保存一个新房间码 */
    public static String newPairing(Context c) {
        String code = Mqtt.newRoomCode();
        setRoom(c, code);
        setEnabled(c, true);
        return code;
    }

    /**
     * 清掉配对信息。
     * 注意：这个操作会让两台手机**彻底断联**（除非同一 Wi-Fi），
     * 所以 UI 上要二次确认。
     */
    public static void clear(Context c) {
        sp(c).edit().remove(KEY_ROOM).remove(KEY_ON).apply();
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
    }

    /**
     * 发一个协议消息到中转通道。
     *
     * payload 直接就是 Protocol 那套 JSON，外面再包一层"sender"，
     * 让接收端能区分是哪一端发的（避免自己收到的消息又被自己处理）。
     *
     * @param sender Protocol.ROLE_ORDER / ROLE_RECV
     */
    public static boolean publish(Context c, String sender, String payload, int timeoutMs) {
        String room = room(c);
        if (room == null) {
            return false;
        }
        // 注意 Json.fields 的 key 不要加引号，它内部会 quote(key)
        String wrapped = Json.object(Json.fields(
                "relayFrom", Json.quote(sender),
                "relayTs", String.valueOf(System.currentTimeMillis()),
                "relayPayload", Json.quote(payload)));
        // 发布也要用同一套服务器配置（自建时公共 broker 就完全不碰了）
        Mqtt.configure(c);
        return Mqtt.publishOnce(room, wrapped, timeoutMs);
    }

    /** 解包一条中转消息；不是我们格式的返回 null */
    public static Wrapped unwrap(String raw) {
        try {
            java.util.Map<String, Object> m = Json.asObject(Json.parse(raw));
            String from = Json.str(m, "relayFrom", "");
            String payload = Json.str(m, "relayPayload", "");
            if (from.length() == 0 || payload.length() == 0) {
                return null;
            }
            return new Wrapped(from, payload);
        } catch (Exception e) {
            return null;
        }
    }

    public static final class Wrapped {
        public final String from;
        public final String payload;

        Wrapped(String f, String p) {
            this.from = f;
            this.payload = p;
        }
    }

    /** 消息类型：从 payload 里读 type 字段 */
    public static String typeOf(String payload) {
        return Protocol.typeOf(payload);
    }
}
