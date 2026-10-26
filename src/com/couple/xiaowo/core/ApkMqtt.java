package com.couple.xiaowo.core;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 经中转（MQTT）把接单端手里的点单端安装包拉到本地。
 *
 * 为什么要有这个类：接单端本来就内置了一份点单端 APK（见 ApkRelay），
 * 但以前只有"同一个 Wi-Fi 下 HTTP 直连"能传，异地就只能干看着一个版本号。
 * 用户遇到的正是这件事 —— 异地时提示"有新版本"，却永远下不下来。
 *
 * 做法是最笨也最稳的那种，一条消息一块：
 *
 *   点单端                            接单端
 *   ── apkReq ─────────────────────────▶
 *   ◀───────────────────────── apkInfo ──   版本/大小/块数
 *   ── apkGet {seq:0} ────────────────▶
 *   ◀────────────────── apkPart {0,数据} ──
 *   ── apkGet {seq:1} ────────────────▶
 *   ...
 *
 * 每一块都等到了才要下一块，所以丢一块只会重试那 54KB，
 * 不会整包重来；QoS1 下重投也只是重复写同一块，靠 seq 覆盖掉。
 */
public final class ApkMqtt {

    /**
     * 每块原始字节数；base64 之后约 32KB。
     *
     * 不敢再大：手机在弱网（电梯里、地铁里）时，一个 50KB 的包经常要重传好几次，
     * 而 32KB 的包基本一次就过。块小一点最多多几次往返，慢一点总比卡住强。
     */
    public static final int CHUNK = 24 * 1024;

    /** 等单块 / 等版本信息的超时 */
    private static final long WAIT_MS = 7000;

    /** 单块最多要几次（弱网下偶尔连丢两次是常事） */
    private static final int RETRY = 5;

    public interface Listener {
        /** percent 0~100，note 是给用户看的一句话 */
        void onProgress(int percent, String note);

        void onDone(File apk, int versionCode, String versionName);

        void onError(String msg);
    }

    private static volatile boolean running = false;
    private static volatile boolean infoSeen = false;
    private static volatile int remoteVc = 0;
    private static volatile String remoteName = "";
    private static volatile long remoteSize = 0;
    private static volatile int chunks = 0;
    private static volatile String failNote = null;

    private static final Map<Integer, String> PARTS = new HashMap<Integer, String>();

    private ApkMqtt() {
    }

    public static boolean busy() {
        return running;
    }

    /**
     * 开始拉包。失败/成功都会回调一次，不会重复。
     *
     * @return false 表示已经有一个在跑了（重复点按钮）
     */
    public static boolean start(final Context ctx, final Listener l) {
        if (running) {
            return false;
        }
        running = true;
        infoSeen = false;
        failNote = null;
        remoteVc = 0;
        remoteName = "";
        remoteSize = 0;
        chunks = 0;
        synchronized (PARTS) {
            PARTS.clear();
        }
        final Context app = ctx.getApplicationContext();
        final String myId = Client.id(app);

        boolean sent = Relay.publish(app, Protocol.ROLE_ORDER, Json.object(Json.fields(
                "type", Json.quote(Protocol.TYPE_APK_REQ),
                "from", Json.quote(Protocol.ROLE_ORDER),
                "fromId", Json.quote(myId),
                "to", Json.quote("recv"),
                "ts", String.valueOf(System.currentTimeMillis()))), 8000);
        if (!sent) {
            running = false;
            if (l != null) {
                l.onError("中转没连上，发不出更新请求");
            }
            return true;
        }

        new Thread(new Runnable() {
            public void run() {
                try {
                    collect(app, myId, l);
                } catch (Exception e) {
                    back(l, "更新中断：" + e.getMessage());
                }
            }
        }, "apk-mqtt").start();
        return true;
    }

    private static void collect(Context app, String myId, Listener l) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS + 3000;
        while (!infoSeen && System.currentTimeMillis() < deadline) {
            if (failNote != null) {
                back(l, failNote);
                return;
            }
            Thread.sleep(80);
        }
        if (!infoSeen) {
            back(l, "接单端没回版本信息");
            return;
        }
        if (remoteVc <= ApkRelay.currentVersionCode(app)) {
            back(l, "已经是最新版了（" + Ui_appVersion(app) + "）");
            return;
        }
        if (chunks <= 0 || remoteSize <= 0) {
            back(l, "接单端那边的安装包不完整");
            return;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(remoteSize, 1024));
        for (int seq = 0; seq < chunks; seq++) {
            String data = fetch(app, myId, seq);
            if (data == null) {
                back(l, "第 " + (seq + 1) + "/" + chunks + " 块没传过来，更新中断");
                return;
            }
            byte[] raw = Base64.decode(data, Base64.NO_WRAP);
            bos.write(raw, 0, raw.length);
            int pct = (int) ((seq + 1) * 100L / chunks);
            if (l != null) {
                l.onProgress(pct, "正在接收安装包 " + pct + "%");
            }
        }
        byte[] apk = bos.toByteArray();
        if (remoteSize > 0 && apk.length != remoteSize) {
            back(l, "安装包大小对不上（收到 " + apk.length + "，应为 " + remoteSize + "）");
            return;
        }
        File out = new File(app.getCacheDir(), "relay-update-" + remoteVc + ".apk");
        FileOutputStream fos = new FileOutputStream(out);
        try {
            fos.write(apk);
            fos.flush();
        } finally {
            fos.close();
        }
        running = false;
        if (l != null) {
            l.onDone(out, remoteVc, remoteName);
        }
    }

    /** 要一块，最多重试 RETRY 次 */
    private static String fetch(Context c, String myId, int seq) {
        for (int attempt = 0; attempt < RETRY; attempt++) {
            boolean sent = Relay.publish(c, Protocol.ROLE_ORDER, Json.object(Json.fields(
                    "type", Json.quote(Protocol.TYPE_APK_GET),
                    "from", Json.quote(Protocol.ROLE_ORDER),
                    "fromId", Json.quote(myId),
                    "to", Json.quote("recv"),
                    "seq", String.valueOf(seq),
                    "ts", String.valueOf(System.currentTimeMillis()))), 8000);
            if (!sent) {
                sleepQuiet(400);
                continue;
            }
            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                String got;
                synchronized (PARTS) {
                    got = PARTS.remove(Integer.valueOf(seq));
                }
                if (got != null) {
                    return got;
                }
                sleepQuiet(60);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 收消息

    /** 接单端回的包信息。由 OrderScreen.onRelayMessage 转进来 */
    public static void onInfo(String payload) {
        try {
            Map<String, Object> m = Json.asObject(Json.parse(payload));
            remoteVc = (int) Json.num(m, "versionCode", 0);
            remoteName = Json.str(m, "versionName", "");
            remoteSize = Json.num(m, "size", 0);
            chunks = (int) Json.num(m, "chunks", 0);
            infoSeen = true;
        } catch (Exception ignore) {
        }
    }

    /** 接单端回的一块数据 */
    public static void onPart(String payload) {
        try {
            Map<String, Object> m = Json.asObject(Json.parse(payload));
            int seq = (int) Json.num(m, "seq", -1);
            String data = Json.str(m, "data", "");
            if (seq < 0 || data.length() == 0) {
                return;
            }
            synchronized (PARTS) {
                PARTS.put(Integer.valueOf(seq), data);
            }
        } catch (Exception ignore) {
        }
    }

    /** 接单端说自己手里没有可用包 */
    public static void onInfoFail(String note) {
        failNote = note == null ? "接单端没有可用的安装包" : note;
    }

    // ------------------------------------------------------------------ 小工具

    private static void back(Listener l, String msg) {
        running = false;
        if (l != null) {
            Logs.e("update", msg);
            l.onError(msg);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignore) {
        }
    }

    private static String Ui_appVersion(Context c) {
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
