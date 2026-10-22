package com.couple.xiaowo.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SakuraFrp 开放 API v4 的最小客户端。
 *
 * 官方文档：https://api.natfrp.com/docs/
 * 认证方式：HTTP Header `Authorization: Bearer <访问密钥>`
 *   —— 注意：v4 API 不接受「账号密码」，只认访问密钥（用户面板里点「查看访问密钥」）。
 *      所以 App 里那个"登录"输入框填的是访问密钥，不是登录密码。
 *
 * 只会用三个只读接口：
 *   GET /v4/user/info  用户信息
 *   GET /v4/tunnels    隧道列表
 *   GET /v4/nodes      节点列表（拿到节点域名，拼出公网访问地址）
 */
public final class Natfrp {

    public static final String BASE = "https://api.natfrp.com/v4";

    private Natfrp() {
    }

    public static final class ApiException extends Exception {
        public final int code;

        ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    // ------------------------------------------------------------------ 模型

    public static final class User {
        public int id;
        public String name = "";
        public String groupName = "";
        public String speed = "";
        public int tunnelLimit;
    }

    public static final class Node {
        public int id;
        public String name = "";
        public String host = "";
        public int flag;
    }

    public static final class Tunnel {
        public int id;
        public String name = "";
        public String type = "tcp";
        public int node;
        public boolean online;
        public int status;
        public String note = "";
        public String localIp = "127.0.0.1";
        public int localPort;
        public String remote = "";

        /** 拼出给对方填的地址：tcp/udp = 节点域名:远程端口；http/https = 绑定域名 */
        public String address(Map<Integer, Node> nodes) {
            if (remote == null || remote.length() == 0) {
                return "";
            }
            if ("http".equalsIgnoreCase(type) || "https".equalsIgnoreCase(type) || "wol".equalsIgnoreCase(type)) {
                return remote;
            }
            if (remote.indexOf(':') > 0) {
                return remote;
            }
            Node n = nodes == null ? null : nodes.get(node);
            String host = n == null ? "" : n.host;
            if (host.length() == 0) {
                return remote;
            }
            return host + ":" + remote;
        }

        public boolean matchesPort(int port) {
            return localPort == port;
        }
    }

    // ------------------------------------------------------------------ 接口

    public static User userInfo(String token) throws ApiException, IOException {
        Object o = call(token, "/user/info");
        Map<String, Object> m = Json.asObject(o);
        User u = new User();
        u.id = (int) Json.num(m, "id", 0);
        u.name = Json.str(m, "name", "SakuraFrp 用户");
        u.speed = Json.str(m, "speed", "");
        u.tunnelLimit = (int) Json.num(m, "tunnels", 0);
        Map<String, Object> g = Json.asObject(m.get("group"));
        u.groupName = Json.str(g, "name", "");
        return u;
    }

    public static List<Tunnel> tunnels(String token) throws ApiException, IOException {
        Object o = call(token, "/tunnels");
        List<Tunnel> out = new ArrayList<Tunnel>();
        if (o instanceof List) {
            for (Object item : (List<?>) o) {
                Map<String, Object> m = Json.asObject(item);
                Tunnel t = new Tunnel();
                t.id = (int) Json.num(m, "id", 0);
                t.name = Json.str(m, "name", "隧道 " + t.id);
                t.type = Json.str(m, "type", "tcp");
                t.node = (int) Json.num(m, "node", 0);
                t.online = Boolean.parseBoolean(Json.str(m, "online", "false"));
                t.status = (int) Json.num(m, "status", 0);
                t.note = Json.str(m, "note", "");
                t.localIp = Json.str(m, "local_ip", "127.0.0.1");
                t.localPort = (int) Json.num(m, "local_port", 0);
                Object remote = m.get("remote");
                t.remote = remote == null ? "" : String.valueOf(remote);
                out.add(t);
            }
        }
        return out;
    }

    public static Map<Integer, Node> nodes(String token) throws ApiException, IOException {
        Object o = call(token, "/nodes");
        Map<Integer, Node> out = new LinkedHashMap<Integer, Node>();
        Map<String, Object> m = Json.asObject(o);
        for (Map.Entry<String, Object> e : m.entrySet()) {
            int id;
            try {
                id = Integer.parseInt(e.getKey());
            } catch (Exception ignore) {
                continue;
            }
            Map<String, Object> nm = Json.asObject(e.getValue());
            Node n = new Node();
            n.id = id;
            n.name = Json.str(nm, "name", "");
            n.host = Json.str(nm, "host", "");
            n.flag = (int) Json.num(nm, "flag", 0);
            out.put(id, n);
        }
        return out;
    }

    // ------------------------------------------------------------------ 日志解析

    /** frpc 日志里的公网地址，例如 cn-bgp-1.natfrp.cloud:35821 */
    private static final Pattern ADDR = Pattern.compile(
            "([a-zA-Z0-9][a-zA-Z0-9.\\-]*\\.(?:natfrp\\.(?:com|cloud|io|net)|[a-z0-9\\-]+\\.frp[a-z0-9.\\-]*))(?::(\\d{1,5}))?");

    public static String parseAddress(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = ADDR.matcher(line);
        while (m.find()) {
            String port = m.group(2);
            if (port != null) {
                return m.group(1) + ":" + port;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ HTTP

    private static Object call(String token, String path) throws ApiException, IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "XiaoWo/1.0 (Android)");
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(is);
            if (body == null || body.trim().length() == 0) {
                throw new ApiException(code, "服务器没有返回内容 (HTTP " + code + ")");
            }
            Object parsed;
            try {
                parsed = Json.parse(body.trim());
            } catch (Exception e) {
                throw new ApiException(code, "返回内容无法解析");
            }
            Map<String, Object> asMap = parsed instanceof Map ? Json.asObject(parsed) : null;
            if (asMap != null && asMap.containsKey("code")) {
                int bizCode = (int) Json.num(asMap, "code", 0);
                if (bizCode != 0) {
                    throw new ApiException(bizCode, Json.str(asMap, "msg", "接口返回错误 " + bizCode));
                }
            }
            return parsed;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
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
        return new String(bos.toByteArray(), "UTF-8");
    }
}
