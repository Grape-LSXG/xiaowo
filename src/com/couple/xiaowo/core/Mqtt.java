package com.couple.xiaowo.core;

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

    private static byte[] connectPacket(String clientId) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAll(body, utf8("MQTT"));
        body.write(4);        // 协议级别 4 = 3.1.1
        body.write(0x02);     // clean session
        body.write(0);        // keepalive 高位
        body.write(60);       // keepalive 60s
        writeAll(body, utf8(clientId));
        return frame(0x10, body.toByteArray());
    }

    private static byte[] subscribePacket(int pid, String topic) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((pid >> 8) & 0xFF);
        body.write(pid & 0xFF);
        writeAll(body, utf8(topic));
        body.write(0);        // QoS 0
        return frame(0x82, body.toByteArray());
    }

    private static byte[] publishPacket(String topic, String payload) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeAll(body, utf8(topic));
        byte[] p = payload.getBytes(StandardCharsets.UTF_8);
        writeAll(body, p);
        return frame(0x30, body.toByteArray());   // QoS0
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
            int backoff = 2;
            while (!stop) {
                Socket s = null;
                try {
                    String host = brokerFor(room);
                    s = new Socket();
                    s.connect(new InetSocketAddress(host, PORT), 8000);
                    s.setSoTimeout(90000);
                    socket = s;
                    OutputStream out = s.getOutputStream();
                    InputStream in = s.getInputStream();

                    String cid = "xiaowo-" + Math.abs(rnd.nextLong() % 1000000000L);
                    out.write(connectPacket(cid));
                    out.flush();

                    int[] hdr = new int[1];
                    byte[] ack = readPacket(in, hdr);
                    if (hdr[0] != 0x20 || ack == null || ack.length < 2 || ack[1] != 0) {
                        throw new Exception("CONNACK 失败");
                    }
                    out.write(subscribePacket(1, topic(room)));
                    out.flush();
                    /*
                     * 必须等 SUBACK 才算真的订阅上了。
                     * 之前是写完 SUBSCRIBE 就立刻置 online=true，
                     * 调用方可能在这个瞬间发消息 —— broker 还没完成注册，
                     * 消息会被直接丢掉（实测踩到过）。
                     */
                    int[] sh = new int[1];
                    byte[] suback = readPacket(in, sh);
                    if (sh[0] != 0x90) {
                        throw new Exception("SUBACK 失败");
                    }
                    setOnline(true, host);
                    backoff = 2;

                    // 读循环：PUBLISH 交出去，PINGRESP 忽略
                    while (!stop) {
                        int[] h = new int[1];
                        byte[] body = readPacket(in, h);
                        if (body == null) {
                            break;      // 连接断了
                        }
                        if (h[0] == 0x30 || h[0] == 0x3A || h[0] == 0x32) {
                            String payload = parsePublish(body);
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
                backoff = Math.min(backoff * 2, 30);
            }
        }
    }

    /** 从 PUBLISH 的 body 里取出 payload（跳过 topic） */
    private static String parsePublish(byte[] body) {
        try {
            if (body == null || body.length < 2) {
                return null;
            }
            int tl = ((body[0] & 0xFF) << 8) | (body[1] & 0xFF);
            int off = 2 + tl;
            if (off > body.length) {
                return null;
            }
            return new String(body, off, body.length - off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
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
     */
    public static boolean publishOnce(String room, String payload, int timeoutMs) {
        Socket s = null;
        try {
            Random rnd = new Random();
            String host = brokerFor(room);
            s = new Socket();
            s.connect(new InetSocketAddress(host, PORT), Math.min(timeoutMs, 8000));
            s.setSoTimeout(timeoutMs);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            out.write(connectPacket("xiaowo-p-" + Math.abs(rnd.nextLong() % 1000000000L)));
            out.flush();
            int[] h = new int[1];
            byte[] ack = readPacket(in, h);
            if (h[0] != 0x20 || ack == null || ack.length < 2 || ack[1] != 0) {
                return false;
            }
            out.write(publishPacket(topic(room), payload));
            out.flush();
            // QoS0 没有确认包，写完就算发出去了
            return true;
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
