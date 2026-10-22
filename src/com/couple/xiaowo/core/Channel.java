package com.couple.xiaowo.core;

/**
 * 一条可用通道的描述 + 真实探测。
 *
 * 为什么需要它：之前的"已连接"是**假的** —— 只要 SharedPreferences 里
 * 存了地址就显示"已连接"，哪怕对方根本没开机、地址早就变了。
 * 用户加入房间后仍然看到"未连接"，就是被这种假状态误导过。
 *
 * 这里把三条通道统一抽象出来，每种都给定一个**可验证的健康状态**：
 *   1. 同 Wi-Fi 直连   —— 能不能 TCP 连上对方的 8765
 *   2. Tailscale/手填  —— 同上，但地址是 100.x
 *   3. 异地中转        —— MQTT 订阅是否在线 + 能不能收到对方心跳
 *
 * 注意三者的"探测"方式不一样，不能一刀切。
 */
public final class Channel {

    public static final int KIND_LAN = 0;
    public static final int KIND_DIRECT = 1;
    public static final int KIND_RELAY = 2;

    public static final int STATE_UNKNOWN = 0;   // 还没探测
    public static final int STATE_OK = 1;        // 探测通过
    public static final int STATE_FAIL = 2;      // 探测失败
    public static final int STATE_OFF = 3;       // 未配置 / 未启用

    public final int kind;
    public final String title;
    public final String subtitle;

    /** 直连类通道用这两个 */
    public final String host;
    public final int port;
    /** 中转通道用这个 */
    public final String room;

    public int state = STATE_UNKNOWN;
    public String detail = "";
    /** 最近一次探测耗时，用于显示延迟 */
    public long lastMs = 0L;

    private Channel(int kind, String title, String subtitle, String host, int port, String room) {
        this.kind = kind;
        this.title = title;
        this.subtitle = subtitle;
        this.host = host;
        this.port = port;
        this.room = room;
    }

    public static Channel lan(String host, int port) {
        return new Channel(KIND_LAN, "同一个 Wi-Fi",
                "两个人在同一个路由器下，自动发现，速度最快", host, port, null);
    }

    public static Channel direct(String host, int port) {
        boolean ts = Net.isTailscaleIp(host);
        return new Channel(KIND_DIRECT,
                ts ? "Tailscale 虚拟内网" : "手动填地址",
                ts ? "两台手机登同一个 Tailscale 账号，像在同一个局域网里"
                        : "知道对方的 IP 就直接填", host, port, null);
    }

    public static Channel relay(String room) {
        return new Channel(KIND_RELAY, "异地中转",
                "不在同一个网络时用。配对一次，以后永久有效", null, 0, room);
    }

    public boolean isRelay() {
        return kind == KIND_RELAY;
    }

    public boolean configured() {
        if (isRelay()) {
            return room != null && room.length() > 0;
        }
        return host != null && host.length() > 0 && port > 0;
    }

    /**
     * 异步探测这条通道通不通。
     *
     * 直连类：打一次 /ping，看返回里的 role 对不对。
     * 中转类：不能只信 MQTT 的"在线"，那只说明我们连上了 broker，
     *         **不代表对方也在线**。所以额外要求"最近收到过对方的心跳"。
     */
    public void probe(final String myRole, final Result cb) {
        if (!configured()) {
            state = STATE_OFF;
            detail = isRelay() ? "还没配对" : "还没填地址";
            if (cb != null) {
                cb.done(this);
            }
            return;
        }
        if (isRelay()) {
            // 中转的健康度由订阅层实时维护，这里不做网络探测
            if (cb != null) {
                cb.done(this);
            }
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                long t0 = System.currentTimeMillis();
                String body = null;
                try {
                    body = Net.get(host, port, Protocol.PATH_PING, 2500);
                } catch (Exception ignore) {
                }
                lastMs = System.currentTimeMillis() - t0;
                if (body != null) {
                    String role = Json.str(Json.asObject(Json.parse(body)), "role", "");
                    // 必须确认对面是"对方"，不能是自己（同机调试时容易搞混）
                    if (role.length() > 0 && !role.equals(myRole)) {
                        state = STATE_OK;
                        detail = "已连通 · " + lastMs + "ms";
                    } else {
                        state = STATE_FAIL;
                        detail = "这个地址是" + (role.equals(Protocol.ROLE_ORDER) ? "点单端" : "接单端")
                                + "自己，不是对方";
                    }
                } else {
                    state = STATE_FAIL;
                    detail = "连不上（对方没开机 / 不在同一网络）";
                }
                if (cb != null) {
                    cb.done(Channel.this);
                }
            }
        }).start();
    }

    public interface Result {
        void done(Channel ch);
    }

    /** 给 UI 用的状态文案 */
    public String stateText() {
        switch (state) {
            case STATE_OK:
                return "可用";
            case STATE_FAIL:
                return "不可用";
            case STATE_OFF:
                return "未配置";
            default:
                return "待检测";
        }
    }

    /** 状态色（浅色模式下的值，交给 Ui 工厂做深色映射） */
    public int stateColor() {
        switch (state) {
            case STATE_OK:
                return 0xFF16A34A;
            case STATE_FAIL:
                return 0xFFDC2626;
            case STATE_OFF:
                return 0xFF958DA3;
            default:
                return 0xFFB45309;
        }
    }
}
