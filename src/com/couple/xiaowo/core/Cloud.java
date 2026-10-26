package com.couple.xiaowo.core;

import android.content.Context;

import java.io.InputStream;
import java.util.Properties;

/**
 * 云端 MQTT 预置配置（EMQX Cloud Serverless 之类）。
 *
 * 为什么需要它：两台手机装好就应该能用，不该让「点单端」自己再去填服务器地址。
 * 所以把 broker 的地址/账号/密码以及默认房间码打进 APK 的 assets/cloud.properties，
 * App 启动时读一次，用户自己没配过服务器就用这份预置。
 *
 * 安全说明：
 *   · assets/cloud.properties **不进 git**（.gitignore 已排除），仓库里只有 .example；
 *   · APK 里是明文（能反编译出来），这属于"只有我们俩会装的私人 App"的取舍，
 *     真要更严，就把密码换成签发型 token + 服务端 ACL（EMQX Serverless 支持按用户名 ACL）。
 *
 * 文件格式（properties）：
 *   host=xxxxxxx.emqxsl.com
 *   port=8883
 *   user=xiaowo
 *   pass=******
 *   tls=true
 *   room=XXXXXX        # 可选：预置房间码，两端都读它
 */
public final class Cloud {

    private static boolean loaded = false;
    private static boolean present = false;
    private static String host;
    private static int port = 8883;
    private static String user;
    private static String pass;
    private static boolean tls = true;
    private static String room;

    private Cloud() {
    }

    /** 预置文件是否存在于本 APK 里 */
    public static boolean has() {
        return present;
    }

    public static String host() {
        return host;
    }

    public static int port() {
        return port;
    }

    public static String user() {
        return user;
    }

    public static String pass() {
        return pass;
    }

    public static boolean tls() {
        return tls;
    }

    public static String room() {
        return room;
    }

    /** 读一次 assets/cloud.properties（失败就当没有，不影响公共 broker 的老行为） */
    public static void load(Context c) {
        if (loaded || c == null) {
            return;
        }
        loaded = true;
        InputStream in = null;
        try {
            in = c.getAssets().open("cloud.properties");
            Properties p = new Properties();
            p.load(in);
            String h = p.getProperty("host", "").trim();
            if (h.length() == 0) {
                return;
            }
            host = h;
            try {
                port = Integer.parseInt(p.getProperty("port", "8883").trim());
            } catch (Exception e) {
                port = 8883;
            }
            String u = p.getProperty("user", "").trim();
            user = u.length() == 0 ? null : u;
            String pw = p.getProperty("pass", "");
            pass = pw.length() == 0 ? null : pw;
            tls = !"false".equalsIgnoreCase(p.getProperty("tls", "true").trim());
            String r = p.getProperty("room", "").trim();
            room = r.length() == 0 ? null : r;
            present = true;
        } catch (Exception ignore) {
            present = false;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignore) {
            }
        }
    }
}
