import com.couple.xiaowo.core.Json;
import com.couple.xiaowo.core.Net;
import com.couple.xiaowo.core.Protocol;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 纯 JVM 的端到端协议测试。
 * 直接跑 App 里那套 core 代码（不碰 Android API），验证：
 *   1. 接单端 HTTP 服务能收到订单并回 ack
 *   2. UDP 广播能被发现（自动找到对端）
 *   3. 点单端能收到"回爱心"
 *   4. JSON 编解码往返一致
 */
public class ProtocolTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        } else {
            fail++;
            System.out.println("  ✗ " + name + "  " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. JSON 往返 ===");
        Protocol.Order src = new Protocol.Order();
        src.id = "o123";
        src.note = "今天也要开心呀 \"引号\" \\反斜杠";
        src.total = 13;
        src.replyHost = "192.168.1.7";
        src.replyPort = 40001;
        src.lines.add(new Protocol.Line("要亲亲", 1, 5, 0, "💋"));
        src.lines.add(new Protocol.Line("要抱抱", 2, 4, 1, "🫂"));
        src.lines.add(new Protocol.Line("陪陪我", 1, 8, 3, "🐾"));

        String json = src.toJson();
        Protocol.Order dst = Protocol.Order.fromJson(json);
        check("订单条目数", dst.lines.size() == 3, "3 条");
        check("总价", dst.total == 13, "13");
        check("备注含引号与反斜杠", "今天也要开心呀 \"引号\" \\反斜杠".equals(dst.note), dst.note);
        check("emoji 未损坏", "💋".equals(dst.lines.get(0).emoji), dst.lines.get(0).emoji);
        check("回信地址", "192.168.1.7".equals(dst.replyHost) && dst.replyPort == 40001,
                dst.replyHost + ":" + dst.replyPort);

        System.out.println("=== 2. 接单端 HTTP 服务 ===");
        final AtomicReference<Protocol.Order> received = new AtomicReference<Protocol.Order>();
        final CountDownLatch orderLatch = new CountDownLatch(1);
        Net.Server recvServer = new Net.Server(new Net.Handler() {
            public String handle(String method, String path, String body) {
                if (Protocol.PATH_ORDER.equals(path)) {
                    received.set(Protocol.Order.fromJson(body));
                    orderLatch.countDown();
                    return Protocol.ack(true, Protocol.ROLE_RECV, "收到啦，马上配送");
                }
                if (Protocol.PATH_PING.equals(path)) {
                    return Json.object(Json.fields("ok", "true", "role", Json.quote(Protocol.ROLE_RECV)));
                }
                return Protocol.ack(false, Protocol.ROLE_RECV, "unknown");
            }
        });
        int recvPort = recvServer.start(18765);
        check("服务已启动", recvPort > 0, "port=" + recvPort);

        String resp = Net.post("127.0.0.1", recvPort, Protocol.PATH_ORDER, src.toJson(), 3000);
        check("POST /order 有响应", resp != null, resp == null ? "null" : "ok");
        check("ack.ok == true", Protocol.isOk(resp), String.valueOf(resp));
        check("服务端解析出订单", orderLatch.await(2, TimeUnit.SECONDS), "");
        Protocol.Order got = received.get();
        check("收到的订单内容一致", got != null && got.lines.size() == 3 && "要抱抱".equals(got.lines.get(1).name),
                got == null ? "null" : got.lines.get(1).name + " x" + got.lines.get(1).qty);

        System.out.println("=== 3. UDP 自动发现 ===");
        Net.DiscoveryResponder resp2 = new Net.DiscoveryResponder(new Net.DiscoveryResponder.PortProvider() {
            public int tcpPort() {
                return 18765;
            }
        });
        resp2.start();
        Thread.sleep(300);
        List<Net.Peer> peers = Net.discover(2000);
        boolean found = false;
        for (Net.Peer p : peers) {
            if (p.port == 18765) {
                found = true;
            }
        }
        check("广播发现了接单端", found, peers.isEmpty() ? "没收到应答" : peers.get(0).toString());
        resp2.stop();

        System.out.println("=== 4. 点单端接收「回爱心」 ===");
        final AtomicReference<String> heartText = new AtomicReference<String>("");
        final CountDownLatch heartLatch = new CountDownLatch(1);
        Net.Server orderServer = new Net.Server(new Net.Handler() {
            public String handle(String method, String path, String body) {
                if (Protocol.PATH_HEART.equals(path)) {
                    Map<String, Object> m = Json.asObject(Json.parse(body));
                    heartText.set(Json.str(m, "text", ""));
                    heartLatch.countDown();
                    return Protocol.ack(true, Protocol.ROLE_ORDER, "收到你的爱心啦");
                }
                return Protocol.ack(false, Protocol.ROLE_ORDER, "我是点单端");
            }
        });
        int orderPort = orderServer.start(0);
        check("点单端用随机端口", orderPort > 0, "port=" + orderPort);
        String hr = Net.post("127.0.0.1", orderPort, Protocol.PATH_HEART,
                Protocol.heart("小窝·接单", "抱抱你，已接单！"), 3000);
        check("回爱心成功", Protocol.isOk(hr), String.valueOf(hr));
        check("点单端收到文案", heartLatch.await(2, TimeUnit.SECONDS) && "抱抱你，已接单！".equals(heartText.get()),
                heartText.get());

        System.out.println("=== 5. 异常场景 ===");
        String bad = Net.post("127.0.0.1", 65530, Protocol.PATH_ORDER, src.toJson(), 800);
        check("连不上时返回 null 而不是崩溃", bad == null, "null");
        String wrongPath = Net.post("127.0.0.1", recvPort, "/nope", "{}", 2000);
        check("未知路径返回 ok=false", !Protocol.isOk(wrongPath), String.valueOf(wrongPath));

        System.out.println("=== 6. 端口占用回退 ===");
        Net.Server s2 = new Net.Server(new Net.Handler() {
            public String handle(String method, String path, String body) {
                return Protocol.ack(true, "x", "hi");
            }
        });
        int p2 = s2.start(18765); // 已被占用，应自动 +1
        check("端口冲突自动回退", p2 != 18765 && p2 > 0, "18765 → " + p2);

        s2.stop();
        recvServer.stop();
        orderServer.stop();

        System.out.println();
        System.out.println("结果: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }
}
