package com.couple.xiaowo.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全部网络能力都集中在这里，只依赖 java.net：
 *
 *  1) Server         —— 一个极简 HTTP 服务（监听 TCP，收 /order、/heart、/ping）
 *  2) post()         —— 点单端把订单 HTTP POST 给接单端
 *  3) discover()     —— UDP 广播，自动在局域网里找到对方（不需要手填 IP）
 *  4) localIpv4()    —— 拿到本机在 Wi-Fi 下的 IP，显示给用户
 *
 * 两个 App 装在同一台手机上时，127.0.0.1 也走同一条代码路径，
 * 所以"同机联调"和"两台手机联调"是完全一样的逻辑。
 */
public final class Net {

    public static final int DEFAULT_PORT = 8765;
    public static final int DISCOVERY_PORT = 8766;

    private Net() {
    }

    // ================================================================== 发现

    public static final class Peer {
        public final String host;
        public final int port;
        public final String name;

        Peer(String host, int port, String name) {
            this.host = host;
            this.port = port;
            this.name = name;
        }

        @Override
        public String toString() {
            return name + " @ " + host + ":" + port;
        }
    }

    /** 本机所有网卡的 IPv4 地址（用于显示 / 兜底回信地址）。 */
    public static List<String> localIps() {
        Set<String> out = new LinkedHashSet<String>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return new ArrayList<String>(out);
    }

    public static String localIpv4() {
        // Tailscale 地址优先：它是唯一「换到任何网络都还在」的地址，
        // 异地恋真正能用的就是这一个。
        String ts = tailscaleIpv4();
        if (ts != null) {
            return ts;
        }
        List<String> ips = localIps();
        for (String ip : ips) {
            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                return ip;
            }
        }
        return ips.isEmpty() ? "127.0.0.1" : ips.get(0);
    }

    /** 本机 Tailscale 地址；没装或者没连上就返回 null。 */
    public static String tailscaleIpv4() {
        for (String ip : localIps()) {
            if (isTailscaleIp(ip)) {
                return ip;
            }
        }
        return null;
    }

    /**
     * Tailscale 用 100.64.0.0/10 这段 CGNAT 地址（100.64.x.x ~ 100.127.x.x），
     * 判断它同时也是「异地该填哪个地址」的判据。
     */
    public static boolean isTailscaleIp(String ip) {
        if (ip == null || !ip.startsWith("100.")) {
            return false;
        }
        String[] p = ip.split("\\.");
        if (p.length != 4) {
            return false;
        }
        try {
            int b = Integer.parseInt(p[1]);
            return b >= 64 && b <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static List<InetAddress> broadcastTargets() {
        List<InetAddress> t = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && !t.contains(b)) {
                        t.add(b);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        try {
            t.add(InetAddress.getByName("255.255.255.255"));
            t.add(InetAddress.getByName("127.0.0.1")); // 同机联调：自己找自己
        } catch (Exception ignore) {
        }
        return t;
    }

    /**
     * 在局域网里喊一声"我是点单端，谁接单？"，收集应答。
     * 收不到也不要紧 —— 用户可以手填 IP，或者走公网中继。
     */
    public static List<Peer> discover(int waitMs) {
        List<Peer> peers = new ArrayList<Peer>();
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            ds.setBroadcast(true);
            byte[] payload = Protocol.discover().getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : broadcastTargets()) {
                try {
                    ds.send(new DatagramPacket(payload, payload.length, target, DISCOVERY_PORT));
                } catch (Exception ignore) {
                }
            }
            ds.setSoTimeout(300);
            long deadline = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    Map<String, Object> m = Json.asObject(Json.parse(text));
                    if (!Protocol.TYPE_HERE.equals(Json.str(m, "type", ""))) {
                        continue;
                    }
                    String host = p.getAddress().getHostAddress();
                    int port = (int) Json.num(m, "port", DEFAULT_PORT);
                    String name = Json.str(m, "name", "小窝·接单");
                    boolean dup = false;
                    for (Peer old : peers) {
                        if (old.host.equals(host) && old.port == port) {
                            dup = true;
                        }
                    }
                    if (!dup) {
                        peers.add(new Peer(host, port, name));
                    }
                    if (peers.size() > 0 && System.currentTimeMillis() + 400 > deadline) {
                        break;
                    }
                } catch (SocketTimeoutException ignore) {
                    // 继续等到 deadline
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (ds != null) {
                ds.close();
            }
        }
        return peers;
    }

    /** 接单端：监听 UDP 广播并应答自己的 TCP 端口。 */
    public static final class DiscoveryResponder {
        private DatagramSocket ds;
        private Thread thread;
        private volatile boolean running;
        private final PortProvider portProvider;

        public interface PortProvider {
            int tcpPort();
        }

        public DiscoveryResponder(PortProvider p) {
            this.portProvider = p;
        }

        public void start() {
            if (running) {
                return;
            }
            try {
                ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(DISCOVERY_PORT));
                ds.setBroadcast(true);
                ds.setSoTimeout(500);
            } catch (Exception e) {
                ds = null;
                return; // 广播端口被占用：不影响手动填 IP 的连接
            }
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[2048];
                    while (running) {
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            ds.receive(p);
                            String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                            if (!Protocol.TYPE_DISCOVER.equals(Protocol.typeOf(text))) {
                                continue;
                            }
                            String reply = Protocol.here("小窝·接单", portProvider.tcpPort());
                            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                            ds.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                        } catch (SocketTimeoutException ignore) {
                        } catch (Exception e) {
                            if (!running) {
                                break;
                            }
                        }
                    }
                }
            }, "discovery-responder");
            thread.setDaemon(true);
            thread.start();
        }

        public void stop() {
            running = false;
            if (ds != null) {
                try {
                    ds.close();
                } catch (Exception ignore) {
                }
                ds = null;
            }
            if (thread != null) {
                thread.interrupt();
                thread = null;
            }
        }
    }

    // ================================================================== 协议

    /**
     * 把 host 解析成「协议 + 纯主机名」。
     *
     * 为什么需要它：某些穿透服务因合规要求**拦截明文 HTTP**，
     * 公网访问必须走 HTTPS。所以 host 字段允许携带协议前缀：
     *   "frp-cat.com"            → http + frp-cat.com
     *   "https://frp-cat.com"    → https + frp-cat.com
     *   "https://abc.example.com" → https + abc.example.com
     *
     * 这样局域网老配置完全不受影响，公网填带 https:// 的地址即可。
     */
    public static final class Target {
        public final String scheme;
        public final String host;
        public final boolean secure;

        Target(String scheme, String host) {
            this.scheme = scheme;
            this.host = host;
            this.secure = "https".equals(scheme);
        }
    }

    public static Target parseHost(String raw) {
        String s = raw == null ? "" : raw.trim();
        String scheme = "http";
        if (s.regionMatches(true, 0, "https://", 0, 8)) {
            scheme = "https";
            s = s.substring(8);
        } else if (s.regionMatches(true, 0, "http://", 0, 7)) {
            scheme = "http";
            s = s.substring(7);
        }
        // 去掉可能残留的端口/斜杠
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        return new Target(scheme, s);
    }

    /** 拼出完整 URL。host 带协议前缀时按 HTTPS 走。 */
    public static URL buildUrl(String host, int port, String path) throws Exception {
        Target t = parseHost(host);
        boolean defaultPort = t.secure ? (port == 443) : (port == 80);
        String portPart = (port <= 0 || defaultPort) ? "" : (":" + port);
        return new URL(t.scheme + "://" + t.host + portPart + path);
    }

    /**
     * HTTPS 宽容模式。
     *
     * 为什么需要：部分穿透服务默认用自签/平台证书，
     * 而 App 默认的信任库不认识，会抛 SSLHandshakeException。
     * 这里对该域名放宽校验——注意：**只放宽证书校验，不改协议、不降级明文**，
     * 而且仅对用户显式填写 https:// 的目标生效。
     */
    private static boolean relax = false;

    public static void setRelaxTls(boolean on) {
        relax = on;
    }

    /** 给别的类用的入口 */
    public static void relaxTlsIfNeeded(HttpURLConnection conn) {
        prepareTls(conn);
    }

    private static void prepareTls(HttpURLConnection conn) {
        if (!(conn instanceof javax.net.ssl.HttpsURLConnection) || !relax) {
            return;
        }
        final javax.net.ssl.HttpsURLConnection https =
                (javax.net.ssl.HttpsURLConnection) conn;
        try {
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                        }

                        public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                        }

                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            }, new java.security.SecureRandom());
            https.setSSLSocketFactory(ctx.getSocketFactory());
            https.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                public boolean verify(String h, javax.net.ssl.SSLSession s) {
                    return true;
                }
            });
        } catch (Exception ignore) {
        }
    }

    // ================================================================== 客户端

    /** 发一个 HTTP POST；失败返回 null（不抛异常，UI 好处理）。 */
    public static String post(String host, int port, String path, String body, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = buildUrl(host, port, path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            prepareTls(conn);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Connection", "close");
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            OutputStream os = conn.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is);
            if (code < 200 || code >= 300) {
                return null;
            }
            return text;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static String get(String host, int port, String path, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = buildUrl(host, port, path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            prepareTls(conn);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }
            return readAll(conn.getInputStream());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 在区间 [preferred, preferred+15] 里找一个能绑的端口，找不到就让系统分配。 */
    public static ServerSocket bindFree(int preferred, int tries) throws IOException {
        IOException last = null;
        for (int i = 0; i < tries; i++) {
            ServerSocket ss = new ServerSocket();
            try {
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(preferred + i));
                return ss;
            } catch (IOException e) {
                last = e;
                try {
                    ss.close();
                } catch (Exception ignore) {
                }
            }
        }
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(0));
        if (last != null) {
            // 系统分配成功就算成功
        }
        return ss;
    }

    // ================================================================== 服务端

    public interface Handler {
        /** 返回要写回的 body（JSON 字符串）。 */
        String handle(String method, String path, String body);
    }

    /**
     * 二进制响应。用于把 APK 这类文件直接通过直连通道吐给对方，
     * 不经过任何第三方存储 —— 这就是「局域网直连推新版本」的地基。
     */
    public static final class Blob {
        public final byte[] data;
        public final String contentType;

        public Blob(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }

        public static Blob apk(byte[] data) {
            return new Blob(data, "application/vnd.android.package-archive");
        }
    }

    /** 支持返回 Blob 的 Handler（返回 Blob 就走二进制，返回 String 走 JSON） */
    public interface BlobHandler {
        Object handle(String method, String path, String body);
    }

    /**
     * 极简 HTTP/1.1 服务器：一个 accept 线程 + 每连接一个短线程。
     * 只处理本协议需要的 GET/POST，够用且没有第三方依赖。
     */
    public static final class Server {

        /**
         * 单次请求体上限。
         *
         * Content-Length 是**客户端说了算**的。如果直接按它 new byte[]，
         * 任何能连上端口的人发一个 "Content-Length: 2000000000"
         * 就能让我们申请 2GB，OutOfMemoryError 把整个进程干掉。
         * 这个端口在局域网里谁都能连，所以必须卡上限。
         *
         * 合法 body 最大是订单/聊天/菜谱同步，都是几 KB 量级；
         * 8MB 留足余量（APK 传输走的是 Blob 响应，不经这里）。
         */
        private static final int MAX_REQUEST_BODY = 8 * 1024 * 1024;

        private ServerSocket ss;
        private Thread acceptThread;
        private volatile boolean running;
        private final Handler handler;
        private final BlobHandler blobHandler;
        private int port;

        public Server(Handler handler) {
            this.handler = handler;
            this.blobHandler = null;
        }

        /** 支持返回二进制（传 APK）的构造器 */
        public Server(BlobHandler blobHandler) {
            this.handler = null;
            this.blobHandler = blobHandler;
        }

        public synchronized int start(int preferredPort) throws IOException {
            if (running) {
                return port;
            }
            ss = bindFree(preferredPort, 16);
            port = ss.getLocalPort();
            running = true;
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (running) {
                        try {
                            final Socket s = ss.accept();
                            Thread t = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    serve(s);
                                }
                            }, "http-conn");
                            t.setDaemon(true);
                            t.start();
                        } catch (Exception e) {
                            if (!running) {
                                break;
                            }
                        }
                    }
                }
            }, "http-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            return port;
        }

        public int port() {
            return port;
        }

        public boolean isRunning() {
            return running;
        }

        public synchronized void stop() {
            running = false;
            if (ss != null) {
                try {
                    ss.close();
                } catch (Exception ignore) {
                }
                ss = null;
            }
            if (acceptThread != null) {
                acceptThread.interrupt();
                acceptThread = null;
            }
        }

        private void serve(Socket socket) {
            InputStream in = null;
            OutputStream out = null;
            try {
                socket.setSoTimeout(30000);   // 传 5MB APK 需要更长时间
                in = new BufferedInputStream(socket.getInputStream());
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.length() == 0) {
                    return;
                }
                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                String path = parts.length > 1 ? parts[1] : "/";
                int q = path.indexOf('?');
                if (q >= 0) {
                    path = path.substring(0, q);
                }
                int contentLength = 0;
                String line;
                while ((line = readLine(in)) != null && line.length() > 0) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String k = line.substring(0, colon).trim().toLowerCase();
                        String v = line.substring(colon + 1).trim();
                        if ("content-length".equals(k)) {
                            try {
                                contentLength = Integer.parseInt(v);
                            } catch (Exception ignore) {
                            }
                        }
                    }
                }
                String body = "";
                if (contentLength > 0) {
                    if (contentLength > MAX_REQUEST_BODY) {
                        // 超限直接不处理（不读、不分配），回一个空响应让对端超时
                        return;
                    }
                    byte[] buf = new byte[contentLength];
                    int read = 0;
                    while (read < contentLength) {
                        int n = in.read(buf, read, contentLength - read);
                        if (n <= 0) {
                            break;
                        }
                        read += n;
                    }
                    body = new String(buf, 0, read, StandardCharsets.UTF_8);
                }
                Object respObj;
                try {
                    respObj = (blobHandler != null)
                            ? blobHandler.handle(method, path, body)
                            : handler.handle(method, path, body);
                } catch (Exception e) {
                    respObj = Protocol.ack(false, "server", "服务端异常: " + e.getMessage());
                }
                if (respObj == null) {
                    respObj = Protocol.ack(false, "server", "unsupported");
                }

                byte[] outBytes;
                String contentType;
                if (respObj instanceof Blob) {
                    Blob b = (Blob) respObj;
                    outBytes = b.data;
                    contentType = b.contentType;
                } else {
                    outBytes = String.valueOf(respObj).getBytes(StandardCharsets.UTF_8);
                    contentType = "application/json; charset=utf-8";
                }

                out = socket.getOutputStream();
                String header = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: " + contentType + "\r\n"
                        + "Content-Length: " + outBytes.length + "\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                // 分块写，5MB 的 APK 也不会一次性顶满缓冲区
                int off = 0;
                while (off < outBytes.length) {
                    int n = Math.min(64 * 1024, outBytes.length - off);
                    out.write(outBytes, off, n);
                    off += n;
                }
                out.flush();
            } catch (Exception ignore) {
            } finally {
                try {
                    if (out != null) {
                        out.close();
                    }
                } catch (Exception ignore) {
                }
                try {
                    socket.close();
                } catch (Exception ignore) {
                }
            }
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    int len = sb.length();
                    if (len > 0 && sb.charAt(len - 1) == '\r') {
                        sb.setLength(len - 1);
                    }
                    return sb.toString();
                }
                sb.append((char) c);
                if (sb.length() > 16384) {
                    break;
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
    }

    /** 端口占用情况小工具，便于调试显示。 */
    public static boolean portInUse(int port) {
        ServerSocket s = null;
        try {
            s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            return false;
        } catch (IOException e) {
            return true;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    static List<String> emptyIfNull(List<String> l) {
        return l == null ? Collections.<String>emptyList() : l;
    }
}
