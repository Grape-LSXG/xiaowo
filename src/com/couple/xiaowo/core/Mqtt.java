package com.couple.xiaowo.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * 极简 MQTT 3.1.1 客户端 —— 只为「异地也能收单」这一件事存在。
 *
 * 为什么是 MQTT：
 *   局域网里我们用 UDP 广播互相发现，很干净；但两个人在不同城市时
 *   UDP 广播出不了路由器，也拿不到对方的公网 IP。
 *   这时最省事的办法是让**第三方帮我们把消息传过去**，而且这个第三方
 *   必须是"谁都不用注册、谁都不用付钱、谁都不用自己搭服务器"的。
 *   公共 MQTT broker 正好满足：全球有很多免费开放的公共 broker，
 *   协议简单到可以用几百行手写实现，不需要任何第三方库。
 *
 * 协议范围（刻意只做需要的那部分）：
 *   CONNECT / CONNACK / SUBSCRIBE / SUBACK / PUBLISH(QoS0) / PINGREQ
 *   QoS0 意味着"最多一次"，不保证送达 —— 对"点个单"够用，
 *   因为我们还有局域网直连和重试兜底。
 *
 * 一个关键设计：**房间码（room）**。
 *   两个人约定一个短码（比如 6 位），topic 就是 xiaowo/<room>/msg。
 *   房间码同时用于：谁都能给谁发消息、两台手机都能收到。
 *   它既是「配对」也是「身份」，所以**只需配一次**，之后永久记住。
 */
public final class Mqtt {

    /** 几个公共 broker，按顺序尝试，一个不通就换下一个 */
    public static final String[] BROKERS = {
            "broker.hivemq.com",
            "test.mosquitto.org",
            "broker.emqx.io",
    };
    public static final int PORT = 1883;

    // ------------------------------------------------------------ 自建服务器
    /*
     * v4.7 起可以不用公共 broker，改连自己搭的 mosquitto。
     *
     * 为什么：公共 broker 在国内经常连不上/被限速，而且 topic 是全公开的 ——
     * 只要猜到房间码，任何人 `mosquitto_sub -t 'xiaowo/#'` 就能看到两个人的聊天。
     * 自己一台服务器还能开账号密码、按房间做 ACL、并且只走一条稳定的连接。
     *
     * 配置存在 SharedPreferences("xiaowo") 里，由「连接设置 → 自建服务器」写入。
     */
    public static final String SP_HOST = "broker_host";
    public static final String SP_PORT = "broker_port";
    public static final String SP_USER = "broker_user";
    public static final String SP_PASS = "broker_pass";
    public static final String SP_TLS = "broker_tls";
    /**
     * 本机安装编号：订阅用固定 clientId 时必须稳定，否则每次重连都是新会话，
     * broker 攒的离线消息就投不出来（会话保持的前提就是 clientId 不变）。
     */
    public static final String SP_UID = "client_uid";

    private static String cfgHost;
    private static int cfgPort = PORT;
    private static String cfgUser;
    private static String cfgPass;
    private static boolean cfgTls;
    private static String cfgUid;
    /** 当前是否在用打包进来的云端预置（用户没手动配过服务器时） */
    private static boolean cfgPreset;

    /**
     * 从 SP 读一次配置（静态字段）。
     *
     * 每次建连接前调一遍：用户改了地址不用重启 App，重连时就生效。
     */
    public static void configure(Context c) {
        if (c == null) {
            return;
        }
        try {
            Cloud.load(c);
            SharedPreferences sp = c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
            // 先按"用户手填过的服务器"读一遍，Relay.mode 会用到 hasOwnBroker()
            String h = sp.getString(SP_HOST, "");
            cfgHost = (h == null || h.trim().length() == 0) ? null : h.trim();
            cfgPort = sp.getInt(SP_PORT, PORT);
            cfgUser = null;
            cfgPass = null;
            cfgTls = false;

            String mode = Relay.mode(c);
            if (Relay.MODE_CLOUD.equals(mode) && Cloud.has()) {
                cfgHost = Cloud.host();
                cfgPort = Cloud.port();
                cfgUser = Cloud.user();
                cfgPass = Cloud.pass();
                cfgTls = Cloud.tls();
                cfgPreset = true;
            } else if (Relay.MODE_SELF.equals(mode)) {
                String u = sp.getString(SP_USER, "");
                cfgUser = (u == null || u.trim().length() == 0) ? null : u.trim();
                String pw = sp.getString(SP_PASS, "");
                cfgPass = (pw == null || pw.length() == 0) ? null : pw;
                cfgTls = sp.getBoolean(SP_TLS, false);
                cfgPreset = false;
            } else {
                // 公共 broker：不填地址就按 room 哈希轮换，明文、免账号
                cfgHost = null;
                cfgPort = PORT;
                cfgTls = false;
                cfgPreset = false;
            }
            if (cfgPort <= 0 || cfgPort > 65535) {
                cfgPort = PORT;
            }
            String uid = sp.getString(SP_UID, "");
            if (uid == null || uid.length() == 0) {
                uid = Long.toHexString(new Random().nextLong() & 0x7FFFFFFFFFFFFFFFL);
                if (uid.length() > 10) {
                    uid = uid.substring(0, 10);
                }
                sp.edit().putString(SP_UID, uid).apply();
            }
            cfgUid = uid;
        } catch (Exception ignore) {
        }
    }

    /** 本机安装编号（订阅会话用，保证重连还是同一个会话） */
    public static String clientUid() {
        return cfgUid == null ? "local" : cfgUid;
    }

    /** 保存自建服务器配置（空字符串表示清掉、回到公共 broker） */
    public static void saveBroker(Context c, String host, int port,
                                  String user, String pass, boolean tls) {
        SharedPreferences sp = c.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
        sp.edit()
                .putString(SP_HOST, host == null ? "" : host.trim())
                .putInt(SP_PORT, port)
                .putString(SP_USER, user == null ? "" : user.trim())
                .putString(SP_PASS, pass == null ? "" : pass)
                .putBoolean(SP_TLS, tls)
                .apply();
        // 存了自建服务器 = 明确表示"我要用自己这台"
        Relay.setMode(c, Relay.MODE_SELF);
        configure(c);
    }

    public static String cfgHostText() {
        return cfgHost == null ? "" : cfgHost;
    }

    public static int cfgPortValue() {
        return cfgPort;
    }

    public static String cfgUserText() {
        return cfgUser == null ? "" : cfgUser;
    }

    public static String cfgPassText() {
        return cfgPass == null ? "" : cfgPass;
    }

    public static boolean cfgTlsValue() {
        return cfgTls;
    }

    /** 是否配置了自建服务器（配了就不再碰公共 broker） */
    public static boolean hasOwnBroker() {
        return cfgHost != null && cfgHost.length() > 0;
    }

    /** 给 UI 显示用的一句话，比如 "47.98.1.2:1883" */
    public static String endpointText() {
        if (!hasOwnBroker()) {
            return "公共 broker";
        }
        String prefix = cfgPreset ? "云端 " : "";
        return prefix + cfgHost + ":" + cfgPort + (cfgTls ? " (TLS)" : "");
    }

    /** 是否在用打包进来的云端服务器（UI 上用来显示"已预置、不用配"） */
    public static boolean usingPreset() {
        return cfgPreset;
    }

    private static String hostFor(String room) {
        return hasOwnBroker() ? cfgHost : brokerFor(room);
    }

    private static int portFor() {
        return hasOwnBroker() ? cfgPort : PORT;
    }

    /**
     * 建一条 TCP（或 TLS）连接。
     *
     * TLS 走系统信任库：服务器用 Let's Encrypt 证书就直接能用；
     * 自签证书则必须在手机上装一次该证书，否则会被拒绝 ——
     * 这是有意的，不然"跳过校验"等于没有加密。
     */
    private static Socket open(String host, int port, int timeoutMs) throws Exception {
        if (!cfgTls) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return s;
        }
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeoutMs);
        javax.net.ssl.SSLSocketFactory f =
                (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        javax.net.ssl.SSLSocket ssl =
                (javax.net.ssl.SSLSocket) f.createSocket(plain, host, port, true);
        ssl.setUseClientMode(true);
        javax.net.ssl.SSLParameters prm = ssl.getSSLParameters();
        prm.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(prm);
        ssl.startHandshake();
        return ssl;
    }

    private static final String TOPIC_PREFIX = "xiaowo/";

    /** 单个 MQTT 包上限（公共 broker 上任何人可发，必须防 OOM） */
    private static final int MAX_PACKET = 1024 * 1024;

    private Mqtt() {
    }

    public static String topic(String room) {
        return TOPIC_PREFIX + room + "/msg";
    }

    // ---------------------------------------------------------------- 编解码

    /** MQTT 剩余长度字段：变长整数 */
    private static byte[] encLen(int n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do {
            int d = n % 128;
            n /= 128;
            if (n > 0) {
                d |= 0x80;
            }
            out.write(d);
        } while (n > 0);
        return out.toByteArray();
    }

    /** 把字符串编码成 MQTT 的 UTF-8 字符串（2 字节长度 + 内容） */
    private static byte[] utf8(String s) {
        byte[] b = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + b.length];
        out[0] = (byte) ((b.length >> 8) & 0xFF);
        out[1] = (byte) (b.length & 0xFF);
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    private static byte[] frame(int header, byte[] body) {
        byte[] len = encLen(body.length);
        byte[] out = new byte[1 + len.length + body.length];
        out[0] = (byte) header;
        System.arraycopy(len, 0, out, 1, len.length);
        System.arraycopy(body, 0, out, 1 + len.length, body.length);
        return out;
    }

    private static byte[] connectPacket(String clientId, boolean clean) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAll(body, utf8("MQTT"));
        body.write(4);        // 协议级别 4 = 3.1.1

        /*
         * connect flags：
         *   bit1 clean session —— 订阅连接置 0（会话保持）：断线期间 broker 会把
         *     QoS1 消息替我们攒着，重连后补发，这是"离线段也要能收到"的关键。
         *     一次性发布连接仍然置 1（发完就走，不需要会话）。
         *   bit7 username、bit6 password —— 自建服务器要鉴权时置位
         */
        int flags = clean ? 0x02 : 0x00;
        if (cfgUser != null) {
            flags |= 0x80;
        }
        if (cfgPass != null) {
            flags |= 0x40;
        }
        body.write(flags);
        body.write(0);        // keepalive 高位
        body.write(60);       // keepalive 60s
        writeAll(body, utf8(clientId));
        if (cfgUser != null) {
            writeAll(body, utf8(cfgUser));
        }
        if (cfgPass != null) {
            writeAll(body, utf8(cfgPass));
        }
        return frame(0x10, body.toByteArray());
    }

    private static byte[] subscribePacket(int pid, String topic, int qos) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((pid >> 8) & 0xFF);
        body.write(pid & 0xFF);
        writeAll(body, utf8(topic));
        body.write(qos);
        return frame(0x82, body.toByteArray());
    }

    private static byte[] publishPacket(int pid, String topic, String payload, int qos) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAll(body, utf8(topic));
        if (qos > 0) {
            body.write((pid >> 8) & 0xFF);
            body.write(pid & 0xFF);
        }
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        writeAll(body, p);
        return frame(qos > 0 ? 0x32 : 0x30, body.toByteArray());
    }

    /** PUBACK：告诉 broker "这条 QoS1 我收到了"（不发的话 broker 会一直重投） */
    private static byte[] pubackPacket(int pid) {
        return new byte[]{ 0x40, 2, (byte) ((pid >> 8) & 0xFF), (byte) (pid & 0xFF) };
    }

    private static byte[] pingPacket() {
        return new byte[]{ (byte) 0xC0, 0 };
    }

    private static void writeAll(ByteArrayOutputStream o, byte[] b) {
        o.write(b, 0, b.length);
    }

    // ---------------------------------------------------------------- 客户端

    /**
     * 一条长连的订阅连接。
     *
     * 用法：后台起一个线程，调 run()；收到消息会回调 listener。
     * 断线后自己重连（间隔递增，最多 30 秒），直到 close() 被调用。
     */
    public static final class Sub {
        public interface Listener {
            void onMessage(String payload);

            /** 连接状态变化，用于 UI 上的小绿点 */
            void onState(boolean online, String note);
        }

        private final String room;
        private final Listener listener;
        private final Random rnd = new Random();
        private volatile boolean stop = false;
        private Socket socket;
        private Thread thread;
        private volatile boolean online = false;

        public Sub(String room, Listener l) {
            this.room = room;
            this.listener = l;
        }

        public boolean isOnline() {
            return online;
        }

        public void start() {
            if (thread != null) {
                return;
            }
            stop = false;
            thread = new Thread(new Runnable() {
                public void run() {
                    loop();
                }
            }, "mqtt-sub");
            thread.setDaemon(true);
            thread.start();
        }

        public void close() {
            stop = true;
            closeQuietly();
            Thread t = thread;
            thread = null;
            if (t != null) {
                t.interrupt();
            }
            setOnline(false, "已断开");
        }

        private void closeQuietly() {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception ignore) {
            }
            socket = null;
        }

        private void setOnline(boolean on, String note) {
            online = on;
            if (listener != null) {
                listener.onState(on, note);
            }
        }

        private void loop() {
            int backoff = 1;
            while (!stop) {
                Socket s = null;
                try {
                    String host = hostFor(room);
                    int port = portFor();
                    s = open(host, port, 8000);
                    /*
                     * 35 秒：keepalive 声明的是 60 秒，留一半余量。
                     * 超时不是"断线"，而是"该 ping 了"——见下面的读循环。
                     */
                    s.setSoTimeout(35000);
                    socket = s;
                    OutputStream out = s.getOutputStream();
                    InputStream in = s.getInputStream();

                    String cid = "xiaowo-s-" + clientUid();
                    out.write(connectPacket(cid, false));
                    out.flush();

                    int[] hdr = new int[1];
                    byte[] ack = readPacket(in, hdr);
                    if (hdr[0] != 0x20 || ack == null || ack.length < 2 || ack[1] != 0) {
                        throw new Exception("CONNACK 失败");
                    }
                    out.write(subscribePacket(1, topic(room), 1));
                    out.flush();
                    /*
                     * 必须等 SUBACK 才算真的订阅上了。
                     * 之前是写完 SUBSCRIBE 就立刻置 online=true，
                     * 调用方可能在这个瞬间发消息 —— broker 还没完成注册，
                     * 消息会被直接丢掉（实测踩到过）。
                     *
                     * 会话保持模式下订阅本身是"恢复已有会话"，
                     * 但为了把订阅的 QoS 提到 1，重连时照发一次 SUBSCRIBE；
                     * 这期间 broker 可能已经开始投递离线消息，所以这段循环
                     * 顺手把 PUBLISH 也处理掉，不能死等都 0x90。
                     */
                    boolean subscribed = false;
                    long subDeadline = System.currentTimeMillis() + 8000;
                    while (!subscribed && !stop) {
                        int[] sh = new int[1];
                        byte[] pkt;
                        try {
                            pkt = readPacket(in, sh);
                        } catch (java.net.SocketTimeoutException te) {
                            if (System.currentTimeMillis() > subDeadline) {
                                throw new Exception("SUBACK 失败");
                            }
                            continue;
                        }
                        if (pkt == null) {
                            throw new Exception("SUBACK 失败");
                        }
                        if (sh[0] == 0x90) {
                            subscribed = true;
                        } else if ((sh[0] & 0xF0) == 0x30) {
                            int qos = (sh[0] >> 1) & 0x03;
                            String payload = parsePublish(pkt, qos);
                            if (qos > 0) {
                                out.write(pubackPacket(pidOf(pkt)));
                                out.flush();
                            }
                            if (payload != null && listener != null) {
                                listener.onMessage(payload);
                            }
                        }
                    }
                    setOnline(true, host);
                    backoff = 1;

                    /*
                     * 读循环：PUBLISH 交出去，PINGRESP 只用来清心跳计数。
                     *
                     * v4.7 修：原来这里**从不发 PINGREQ**（pingPacket() 写了却没人调）。
                     * 结果 broker 按 keepalive=60 在 90 秒内收不到客户端的包就断掉，
                     * 而我们这边要等 soTimeout 到期才发现 —— 每条"长连接"其实只能活
                     * 60~90 秒，然后退避 2 秒重连。那 2 秒的空窗里发来的消息（QoS0、
                     * clean session）直接消失，表现就是"偶尔丢单""明明在线却说不在线"。
                     *
                     * 现在：空闲 35 秒就主动发 PINGREQ（keepalive 60 的一半多一点），
                     * 收到 PINGRESP 计数清零；连续两次窗口都没回应就判定断线重连。
                     * 这样连接才能真正长期挂着。
                     */
                    int idleTimeouts = 0;
                    while (!stop) {
                        int[] h = new int[1];
                        byte[] body;
                        try {
                            body = readPacket(in, h);
                        } catch (java.net.SocketTimeoutException te) {
                            if (idleTimeouts >= 2) {
                                throw new Exception("心跳无响应，判定断线");
                            }
                            out.write(pingPacket());
                            out.flush();
                            idleTimeouts++;
                            continue;
                        }
                        if (body == null) {
                            break;      // 连接断了
                        }
                        idleTimeouts = 0;
                        if ((h[0] & 0xF0) == 0x30) {
                            /*
                             * PUBLISH：QoS = (首字节 >> 1) & 3。
                             * QoS1 的 body 在 topic 后面还跟着 2 字节包号，
                             * 必须回 PUBACK，否则 broker 会一直重投同一条；
                             * 取 payload 时也要跳过这两个字节。
                             */
                            int qos = (h[0] >> 1) & 0x03;
                            String payload = parsePublish(body, qos);
                            if (qos > 0) {
                                out.write(pubackPacket(pidOf(body)));
                                out.flush();
                            }
                            if (payload != null && listener != null) {
                                listener.onMessage(payload);
                            }
                        } else if (h[0] == 0xD0) {
                            // PINGRESP，正常
                        }
                    }
                } catch (Exception e) {
                    // 断线：退避后重连
                } finally {
                    closeQuietly();
                    setOnline(false, "重连中…");
                }
                if (stop) {
                    break;
                }
                try {
                    Thread.sleep(backoff * 1000L);
                } catch (InterruptedException e) {
                    break;
                }
                // 上限 8 秒：原来 30 秒意味着一次网络抖动可能丢十几条消息，
                // 而我们这套协议没有离线补发，只能靠"尽快连回来"。
                backoff = Math.min(backoff * 2, 8);
            }
        }
    }

    /** 从 PUBLISH 的 body 里取出 payload（跳过 topic，QoS>0 时再跳过 2 字节包号） */
    private static String parsePublish(byte[] body, int qos) {
        try {
            if (body == null || body.length < 2) {
                return null;
            }
            int tl = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
            int off = 2 + tl + (qos > 0 ? 2 : 0);
            if (off > body.length) {
                return null;
            }
            return new String(body, off, body.length - off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 PUBLISH 的 body 里取出包号（QoS>0 才有） */
    private static int pidOf(byte[] body) {
        try {
            if (body == null || body.length < 4) {
                return 0;
            }
            int tl = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
            int off = 2 + tl;
            if (off + 1 >= body.length) {
                return 0;
            }
            return ((body[off] & 0xFF) << 8) | (body[off + 1] & 0xFF);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 读一个 MQTT 包，返回 body；hdr[0] 存首字节 */
    private static byte[] readPacket(InputStream in, int[] hdr) throws Exception {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        hdr[0] = b0;
        int mult = 1;
        int len = 0;
        for (int i = 0; i < 4; i++) {
            int c = in.read();
            if (c < 0) {
                return null;
            }
            len += (c & 0x7F) * mult;
            mult *= 128;
            if ((c & 0x80) == 0) {
                break;
            }
        }
        if (len == 0) {
            return new byte[0];
        }
        /*
         * 上限保护。
         *
         * MQTT 的剩余长度是 4 字节变长整数，理论上能表示到 268MB。
         * 我们订阅的是公共 broker —— 任何人都能往同一个 topic 发东西，
         * 也能发一个声称很大的包。不设限的话 new byte[len] 就能
         * 直接把 App 撑爆（OutOfMemoryError）。
         *
         * 协议里我们发的消息都是几 KB 级（订单/聊天/同步），
         * 1MB 已经远超实际需要。
         */
        if (len > MAX_PACKET) {
            throw new Exception("MQTT 包过大：" + len);
        }
        byte[] data = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(data, read, len - read);
            if (n <= 0) {
                return null;
            }
            read += n;
        }
        return data;
    }

    /**
     * 发一条消息（一次性短连接）。
     *
     * 为什么不用长连接发：发完就走，最省电、最不容易被系统掐。
     * 收消息才需要长连接（Sub）。
     *
     * QoS1：写完 PUBLISH 之后必须等到 broker 的 PUBACK 才算成功。
     * 以前是"写进 socket 就 return true"，网络半断（TCP 缓冲还吃得下）时
     * 订单其实没到 broker，界面上却显示已经发出去了 —— 这是"明明在线却说不在线"
     * 的另一半原因。
     */
    public static boolean publishOnce(String room, String payload, int timeoutMs) {
        Socket s = null;
        try {
            Random rnd = new Random();
            String host = hostFor(room);
            int port = portFor();
            s = open(host, port, Math.min(timeoutMs, 8000));
            s.setSoTimeout(timeoutMs);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write(connectPacket("xiaowo-p-" + Math.abs(rnd.nextLong() % 1000000000L), true));
            out.flush();
            int[] h = new int[1];
            byte[] ack = readPacket(in, h);
            if (h[0] != 0x20 || ack == null || ack.length < 2 || ack[1] != 0) {
                return false;
            }
            int pid = 1 + rnd.nextInt(60000);
            out.write(publishPacket(pid, topic(room), payload, 1));
            out.flush();

            long deadline = System.currentTimeMillis() + Math.max(1200, timeoutMs);
            while (System.currentTimeMillis() < deadline) {
                int[] ph = new int[1];
                byte[] b;
                try {
                    b = readPacket(in, ph);
                } catch (java.net.SocketTimeoutException te) {
                    return false;
                }
                if (b == null) {
                    return false;
                }
                if (ph[0] == 0x40 && b.length >= 2) {
                    int got = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                    if (got == pid) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (Exception ignore) {
            }
        }
    }

    /**
     * 给一个房间挑 broker。
     *
     * 这里必须**确定**，不能随机：发布端和订阅端如果连到不同的公共 broker，
     * 消息根本不会互通（broker 之间不共享消息，实测踩到过）。
     * 用房间码做哈希，保证同房间永远落在同一台 broker 上。
     */
    private static String brokerFor(String room) {
        int h = 0;
        if (room != null) {
            for (int i = 0; i < room.length(); i++) {
                h = h * 31 + room.charAt(i);
            }
        }
        return BROKERS[Math.abs(h) % BROKERS.length];
    }

    /** 房间码：6 位数字字母，避开容易看错的 0/O、1/I */
    public static String newRoomCode() {
        String abc = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(abc.charAt(r.nextInt(abc.length())));
        }
        return sb.toString();
    }
}
