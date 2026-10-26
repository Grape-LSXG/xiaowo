package com.couple.xiaowo.ui;

import android.app.Dialog;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.couple.xiaowo.core.ApkRelay;
import com.couple.xiaowo.core.History;
import com.couple.xiaowo.core.Social;
import com.couple.xiaowo.core.Json;
import com.couple.xiaowo.core.Menu;
import com.couple.xiaowo.core.Mqtt;
import com.couple.xiaowo.core.Relay;
import com.couple.xiaowo.core.Net;
import com.couple.xiaowo.core.Protocol;
import com.couple.xiaowo.core.Updater;
import com.couple.xiaowo.core.Remind;

import java.text.SimpleDateFormat;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * 【点单端】五个 Tab：
 *   厨房 点菜 / 订单 下过的单 / 冰箱 囤起来的菜 / 管理 菜单一览 / 我 连接与记录
 */
public class OrderScreen extends LinearLayout {

    /**
     * 周期性任务的统一 Handler。
     *
     * 为什么要收成一个字段：深色模式切换改成"同窗口原地重建"之后，旧屏会被丢进垃圾堆，
     * 但它内部那些 postDelayed 自循环还在跑 —— 每切一次主题就多一条永不停的循环，
     * 心跳、菜谱广播都会翻倍。shutdown() 里 removeCallbacksAndMessages(null) 一次性掐掉。
     */
    private final android.os.Handler sched =
            new android.os.Handler(android.os.Looper.getMainLooper());
    /** shutdown 之后置 true，兜住"已经在执行的那一帧回调" */
    private boolean dead = false;

    private static final int TAB_KITCHEN = 0;
    private static final int TAB_ORDERS = 1;
    private static final int TAB_FRIDGE = 2;
    private static final int TAB_MANAGE = 3;
    private static final int TAB_ME = 4;

    /** 菜谱广播周期（异地同步靠它） */
    private static final long MENU_BROADCAST_MS = 3 * 60 * 1000L;

    private static final String[] TAB_ICON = {"🏠", "🧾", "🧊", "📖", "😊"};
    private static final String[] TAB_NAME = {"厨房", "订单", "冰箱", "管理", "我"};

    private final Activity act;
    private final HeartHost host;
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);

    private final Map<String, Menu.Item> byName = new HashMap<String, Menu.Item>();
    private final Map<String, Integer> cart = new LinkedHashMap<String, Integer>();
    private final Map<String, LinearLayout> actionBoxes = new HashMap<String, LinearLayout>();
    private final SharedPreferences sp;
    private final Random random = new Random();

    private LinearLayout listBox;
    private LinearLayout sidebarBox;
    private TextView totalView;
    private TextView subTotalView;
    private TextView orderBtn;
    private TextView peerView;

    private FrameLayout pageHost;
    private final TextView[] navIcons = new TextView[5];
    private final TextView[] navLabels = new TextView[5];
    private final View[] pages = new View[5];
    private int tab = TAB_KITCHEN;

    private int currentCat = 0;
    private String filter = null;
    private String peerHost = null;
    private int peerPort = 0;
    private boolean sending = false;
    /** 「选菜进冰箱」模式：true 时下单键变成「加入冰箱」 */
    private boolean pickingForFridge = false;
    /** 随机选菜按钮的引用（进冰箱模式时要藏起来） */
    private TextView randBtn;
    /** 取消按钮（选菜进冰箱模式下才显示） */
    private TextView cancelPickBtn;
    private Net.Server server;

    private List<History.Entry> history = new ArrayList<History.Entry>();

    /**
     * 经中转发出去、还在等接单端回执的订单：订单 id → 历史条目。
     *
     * MQTT 是 QoS0，publish 成功只说明 broker 收下了。以前直接在界面说
     * "已经送到了"，对方其实可能在重连的空窗里 —— 用户看到的就是
     * "我明明在线，对方却说没收到"。现在改成"等回执"，
     * 等不到就老实说可能没送到。
     */
    private final java.util.Map<String, History.Entry> pendingAcks =
            new java.util.LinkedHashMap<String, History.Entry>();
    private List<String> fridge = new ArrayList<String>();
    private Social.Notice notice;
    private final List<Social.Msg> chatLog = new ArrayList<Social.Msg>();
    private LinearLayout chatBox;
    private ScrollView chatScroll;
    private TextView noticeView;
    private TextView chatBadge;
    private BubbleBurstView bubbleLayer;

    private static final String[] NOTES = {
            "今天也要开心呀", "想你了，快接单", "不许拒绝哦", "就一下下",
            "接单的人最好看", "已下单，请尽快配送", "这是今天份的撒娇"
    };

    public OrderScreen(Activity act, HeartHost host) {
        super(act);
        this.act = act;
        this.host = host;
        this.sp = act.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
        this.peerHost = sp.getString("peer_host", null);
        this.peerPort = sp.getInt("peer_port", 0);
        // 恢复 TLS 设置：地址带 https:// 就按 HTTPS + 宽容证书走
        Net.setRelaxTls(peerHost != null && peerHost.toLowerCase().startsWith("https://"));
        for (Menu.Category c : Menu.CATEGORIES) {
            for (Menu.Item it : c.items) {
                byName.put(it.name, it);
            }
        }
        loadStore();
        restoreMenu();
        byName.clear();
        for (Menu.Category c : Menu.CATEGORIES) {
            for (Menu.Item it : c.items) {
                byName.put(it.name, it);
            }
        }

        setOrientation(VERTICAL);
        setBackgroundColor(Theme.adapt(Ui.BG));

        pageHost = new FrameLayout(act);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        hostLp.weight = 1f;
        addView(pageHost, hostLp);
        addView(buildNav(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (act instanceof MainActivity) {
            bubbleLayer = ((MainActivity) act).bubbleLayer();
        }

        // 恢复上次的 tab（主题切换 recreate 时不切回主界面）
        tab = sp.getInt("cur_tab", TAB_KITCHEN);
        switchTab(tab);
        startServer();
        schedulePresence();
        scheduleAutoPeerUpdate();
        scheduleSyncPush();
        scheduleMenuBroadcast();
        // 使用中周期性检查 GitHub 更新（静默，偶尔试一次）
        // 周期自动检查已关闭：更新只在用户点「检查更新」时进行
    }

    /**
     * 启动时的自动检查已经**整段去掉**（按需求改为纯手动更新）。
     *
     * 保留这个方法名的地方全部改成空实现，避免遗漏调用点；
     * 真正的检查入口只有一个：用户在「我」页点「检查更新」。
     *
     * 为什么不再自动查：
     *   自动下载 + 系统安装器弹出是"打断式"的，用户正在点单时突然
     *   跳安装界面体验很差；而且失败时用户完全无感，会以为功能坏了。
     *   改手动之后，行为完全可预期。
     */
    /**
     * 定时把当前菜谱广播到中转房间。
     *
     * 为什么需要：接单端「自动刷新菜谱」靠直连 GET /menu?pull=1，
     * 但异地时那条直连根本不通 —— 菜单就永远同步不过去。
     * 这里由点单端主动广播，接单端订阅到就更新，异地也能同步。
     *
     * 3 分钟一次、只有配置了中转才发，避免无谓流量。
     */
    private void scheduleMenuBroadcast() {
        final android.os.Handler h = sched;
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            public void run() {
                if (dead || act == null || act.isFinishing()) {
                    return;
                }
                if (com.couple.xiaowo.core.Relay.enabled(act)) {
                    new Thread(new Runnable() {
                        public void run() {
                            try {
                                com.couple.xiaowo.core.Relay.publish(act,
                                        Protocol.ROLE_ORDER, Menu.toJson(), 8000);
                            } catch (Exception ignore) {
                            }
                        }
                    }).start();
                }
                h.postDelayed(self[0], MENU_BROADCAST_MS);
            }
        };
        // 启动后先等 20 秒（让首屏和服务先稳定），之后周期性广播
        h.postDelayed(self[0], 20000L);
    }

    // ---------------------------------------------------------------- 在线心跳

    /** 心跳周期：25 秒。接单端 90 秒没收到就当它离线了 */
    private static final long PRESENCE_MS = 25 * 1000L;

    /**
     * 周期性地告诉接单端「我这台点单端还在」。
     *
     * 为什么要有：接单端首页要显示「现在有几个点单端在线」。
     * 光靠订单推不出来（不下单的时候难道就"没连接"？），
     * 所以加一条独立的心跳，带设备编号 + 回信地址。
     *
     * 三条路都发（能通的先通）：直连 → 局域网发现 → 异地中转。
     */
    private void schedulePresence() {
        final android.os.Handler h = sched;
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            public void run() {
                if (dead || act == null || act.isFinishing()) {
                    return;
                }
                sendPresenceOnce();
                h.postDelayed(self[0], PRESENCE_MS);
            }
        };
        // 等 6 秒，让两个服务都起来、也避开首屏动画
        h.postDelayed(self[0], 6000L);
    }

    private void sendPresenceOnce() {
        final String id = com.couple.xiaowo.core.Client.id(act);
        final String name = com.couple.xiaowo.core.Client.label(act);
        final String host = Net.localIpv4();
        final int port = server != null ? server.port() : 0;
        final String body = Protocol.presence(id, name, host, port,
                com.couple.xiaowo.core.Client.device());
        final String knownHost = peerHost;
        final int knownPort = peerPort;
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (knownHost != null && knownPort > 0) {
                        if (Protocol.isOk(Net.post(knownHost, knownPort,
                                Protocol.PATH_PRESENCE, body, 2000))) {
                            return;
                        }
                    }
                    // 同 Wi-Fi 但不记得地址：广播找一下
                    for (Net.Peer p : Net.discover(1200)) {
                        if (Protocol.isOk(Net.post(p.host, p.port,
                                Protocol.PATH_PRESENCE, body, 1500))) {
                            return;
                        }
                    }
                } catch (Exception ignore) {
                    // 心跳失败不打扰用户，下一轮再说
                }
                // 直连都不通 → 异地中转（接单端订阅着同一个房间）
                if (Relay.enabled(act)) {
                    try {
                        Relay.publish(act, Protocol.ROLE_ORDER, body, 6000);
                    } catch (Exception ignore) {
                    }
                }
            }
        }).start();
    }

    /** 中转连接状态变了 —— 顶部那行「⇄ 直连 / 🌐 中转」要重新说一遍 */
    public void onRelayState() {
        if (peerView != null) {
            peerView.setText(peerText());
        }
    }

    private void scheduleAutoPeerUpdate() {
        // 不自动检查。更新一律由用户在「我」页手动触发。
    }

    /**
     * 经中转问一次版本 —— 「自动更新」在异地时的通路。
     *
     * @param silent true 表示是启动时自动做的，没新版时不要弹任何东西
     */
    private void askPeerVersionViaRelay(final boolean silent) {
        if (!com.couple.xiaowo.core.Relay.enabled(act)) {
            return;
        }
        // 回包由 onRelayMessage -> isApkInfoReply 处理，那边会提示有没有新版
        ApkRelay.askInfoViaRelay(act, new ApkRelay.InfoCallback() {
            public void done(ApkRelay.Info info, String note) {
                if (!silent && note != null) {
                    toast(note);
                }
            }
        });
    }

    /** 安装器调起后通知接单端：「我装上了」 */
    private void sendUpdateAck(final ApkRelay.Info info) {
        final String host = peerHost;
        final int port = peerPort;
        final String body = Protocol.updateAck(info.versionCode, info.versionName);
        // 地址没有也得发：中转这条路一直都在，接单端要靠它记得"这台已经更新了"
        if ((host == null || port <= 0) && !Relay.enabled(act)) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                if (host != null && port > 0) {
                    ok = Protocol.isOk(Net.post(host, port, Protocol.PATH_ACK_UPDATE, body, 3000));
                }
                if (!ok && Relay.enabled(act)) {
                    Relay.publish(act, Protocol.ROLE_ORDER, body, 6000);
                }
            }
        }).start();
    }

    /**
     * 发现新版：直接开始下载并拉起安装器，不先弹确认框。
     * 用户点的"打开 App"本身就是要用的意思，多一步确认只会让人错过更新；
     * 仍然弹一条提示条告诉他发生了什么。
     */
    private void promptPeerUpdate(final String host, final int port, final ApkRelay.Info info) {
        Toast2.show(act, Toast2.LOADING,
                "张老师更新了，正在自动下载 v" + info.versionName + "…");
        autoInstall(host, port, info);
    }

    /** 自动下载 + 调安装器 */
    private void autoInstall(final String host, final int port, final ApkRelay.Info info) {
        final java.io.File out =
                new java.io.File(act.getCacheDir(), "peer-update-" + info.versionCode + ".apk");
        ApkRelay.download(act, host, port, out, new ApkRelay.DownloadCallback() {
            public void onProgress(int percent) {
            }

            public void onDone(final java.io.File apk, final long bytes) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (!Updater.canInstall(act)) {
                            // 没授权：引导一次，取消就静默留在 cache 里，下次再说
                            Toast2.warn(act, "需要允许「安装未知应用」才能自动更新");
                            Updater.requestInstallPermission(act);
                            return;
                        }
                        String err = Updater.install(act, apk);
                        if (err != null) {
                            Toast2.warn(act, "自动更新失败：" + err);
                        } else {
                            Toast2.ok(act, "正在安装 v" + info.versionName);
                            sendUpdateAck(info);
                        }
                    }
                });
            }

            public void onError(final String msg) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        // 自动更新失败不打扰，只在「我」页面的卡片里能看到
                        Toast2.warn(act, "自动更新没成功：" + msg);
                    }
                });
            }
        });
    }

    private void onRemoteBuzz(String body) {
        int ms = 800;
        String reason = "张老师让你看一下手机";
        String from = "张老师";
        try {
            Map<String, Object> m = Json.asObject(Json.parse(body));
            ms = (int) Json.num(m, "ms", 800);
            reason = Json.str(m, "reason", reason);
            from = Json.str(m, "fromName", from);
        } catch (Exception ignore) {
        }
        ms = Math.max(50, Math.min(3000, ms));
        try {
            Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                // 用 pattern 让它震起来更像"叫人"：两短一长
                long[] pattern = {0, ms / 3, ms / 6, ms / 3, ms / 6, ms / 3};
                if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            }
        } catch (Exception ignore) {
        }
        host.burst(18);
        Toast2.love(act, "💗 " + from + "：" + reason);
    }

    private void onChatIncoming(final Social.Msg m) {
        // QoS1 是"至少一次"，broker 没收到 PUBACK 会重投：同 ts + 同文本已在列表里就跳过
        for (int i = chatLog.size() - 1; i >= 0; i--) {
            Social.Msg old = chatLog.get(i);
            if (old.ts == m.ts && old.text != null && old.text.equals(m.text)) {
                return;
            }
        }
        m.mine = false;
        chatLog.add(m);
        while (chatLog.size() > 200) {
            chatLog.remove(0);
        }
        saveChat();
        if (tab == TAB_ME) {
            rebuildChat();
        }
        host.burst(10);
        AlertCenter.push(act, RemindSettings.level(act), "张老师说", m.text, 8);
    }

    private View rowAccent(String k, String v) {
        LinearLayout r = Ui.horizontal(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        r.addView(Ui.tv(act, k, 12, Ui.TEXT_SUB, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(Ui.tv(act, v, 12, Ui.ACCENT_DEEP, true));
        return r;
    }

    private ImageView image(float size, int catIndex, int radius) {
        ImageView iv = new ImageView(act);
        Bitmap bm = CatImage.bitmap(act, catIndex);
        if (bm != null) {
            iv.setImageBitmap(bm);
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(iv, radius);
        return iv;
    }

    private View row(String k, String v) {
        LinearLayout r = Ui.horizontal(act);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        r.addView(Ui.tv(act, k, 12, Ui.SUB, false), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(Ui.tv(act, v, 12, Ui.TEXT, false));
        return r;
    }

    // ================================================================ 小工具

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams top(int dpValue) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(dpValue);
        return p;
    }

    /**
     * 底部面板里的一行选项：整行横贯、至少 48dp 高。
     *
     * 为什么不用 top()：top() 是 WRAP_CONTENT 宽（给卡片里的小 pill 用的），
     * 放进底部面板就变成一小截，手指得瞄着字点；整行可点才符合触控规范。
     */
    private LinearLayout.LayoutParams sheetRow(int dpTop) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(dpTop);
        return p;
    }

    private View buildSubHeader(String title, String sub) {
        LinearLayout h = Ui.vertical(act);
        h.setBackground(Ui.gradient(0xFFFFF0F7, 0xFFF5F0FE, dp(24)));
        h.setPadding(dp(18), dp(18), dp(18), dp(16));
        h.addView(Ui.tv(act, title, 22, Ui.TEXT, true));
        TextView s = Ui.tv(act, sub, 11.5f, Ui.SUB, false);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(4);
        h.addView(s, sLp);
        return h;
    }

    private View emptyState(String emoji, String title, String sub) {
        LinearLayout box = Ui.vertical(act);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(50), 0, dp(20));
        TextView e = Ui.tv(act, emoji, 44, Ui.SUB, false);
        e.setGravity(Gravity.CENTER);
        box.addView(e);
        TextView t = Ui.tv(act, title, 14, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t, top(10));
        TextView s = Ui.tv(act, sub, 11, Ui.SUB, false);
        s.setGravity(Gravity.CENTER);
        box.addView(s, top(6));
        return box;
    }

    private void roundClip(ImageView iv, final int radiusDp) {
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(radiusDp));
            }
        });
    }

    // ================================================================ 下单

    private Protocol.Order buildOrder(String note) {
        Protocol.Order o = new Protocol.Order();
        o.id = "o" + System.currentTimeMillis();
        o.ts = System.currentTimeMillis();
        o.note = note;
        // 带上"是哪台点单端"：多台同时在线时接单端要靠它区分
        o.fromId = com.couple.xiaowo.core.Client.id(act);
        o.fromName = com.couple.xiaowo.core.Client.label(act);
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            String dishName = com.couple.xiaowo.core.CartKey.nameOf(e.getKey());
            String spec = com.couple.xiaowo.core.CartKey.specOf(e.getKey());
            Menu.Item it = byName.get(dishName);
            if (it == null) {
                continue;
            }
            o.lines.add(new Protocol.Line(it.name, e.getValue(), it.price, it.cat,
                    it.emoji, spec));
            o.total += it.price * e.getValue();
        }
        o.replyHost = Net.localIpv4();
        o.replyPort = server != null ? server.port() : 0;
        return o;
    }

    private void placeOrder(final String note) {
        if (sending) {
            return;
        }
        if (cart.isEmpty()) {
            toast("先去点两道菜吧～");
            return;
        }
        final Protocol.Order order = buildOrder(note);
        if (order.lines.isEmpty()) {
            toast("先去点两道菜吧～");
            return;
        }
        host.burst(34);
        vibrate();
        sending = true;
        updateCart();

        new Thread(new Runnable() {
            public void run() {
                String resp = null;
                String used = null;
                int usedPort = 0;

                /*
                 * 如果配了云端中转（EMQX Serverless 之类）而对方直连地址还是空的，
                 * 说明大概率不在同一个网络：先走 MQTT，别让用户白等 5 秒直连超时。
                 * 订单带同一个 id，接单端会去重，多试一条通路不会记两单。
                 */
                boolean relayFirst = Relay.enabled(act)
                        && (peerHost == null || peerHost.length() == 0);
                if (relayFirst) {
                    if (Relay.publish(act, Protocol.ROLE_ORDER, order.toJson(), 8000)) {
                        resp = "{\"ok\":true,\"via\":\"relay\"}";
                        used = "relay";
                        usedPort = 0;
                    }
                }

                if (resp == null && peerHost != null && peerPort > 0) {
                    // 超时不一定等于没送到：很可能是对方已经处理完、
                    // 只是响应没读到（后台进程被冻结就是这个表现）。
                    // 所以重试一次 —— 订单带同一个 id，接单端会去重，不会记两遍。
                    for (int attempt = 0; attempt < 2 && resp == null; attempt++) {
                        String r = Net.post(peerHost, peerPort, Protocol.PATH_ORDER,
                                order.toJson(), attempt == 0 ? 3500 : 6000);
                        if (Protocol.isOk(r)) {
                            resp = r;
                            used = peerHost;
                            usedPort = peerPort;
                        }
                    }
                }
                if (resp == null) {
                    List<Net.Peer> peers = Net.discover(1800);
                    for (Net.Peer p : peers) {
                        String r = Net.post(p.host, p.port, Protocol.PATH_ORDER, order.toJson(), 2500);
                        if (Protocol.isOk(r)) {
                            resp = r;
                            used = p.host;
                            usedPort = p.port;
                            break;
                        }
                    }
                }
                if (resp == null) {
                    String r = Net.post("127.0.0.1", Net.DEFAULT_PORT, Protocol.PATH_ORDER, order.toJson(), 1500);
                    if (Protocol.isOk(r)) {
                        resp = r;
                        used = "127.0.0.1";
                        usedPort = Net.DEFAULT_PORT;
                    }
                }
                /*
                 * 最后一道兜底：异地中转。
                 * 前面几条都是"直连"，两个人不在同一网络时必然全失败，
                 * 这时才走 MQTT 把订单送出去。
                 *
                 * 注意 QoS0 只代表"broker 收下了"，不代表"张老师收到了"，
                 * 所以这里不能直接说"已送达"——要等接单端回 orderAck。
                 */
                if (resp == null && Relay.enabled(OrderScreen.this.act)) {
                    if (Relay.publish(act, Protocol.ROLE_ORDER, order.toJson(), 8000)) {
                        resp = "{\"ok\":true,\"via\":\"relay\"}";
                        used = "relay";
                        usedPort = 0;
                    }
                }
                final String fResp = resp;
                final String fUsed = used;
                final int fPort = usedPort;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        sending = false;
                        com.couple.xiaowo.core.Logs.i("order", fResp != null
                                ? ("下单成功 via=" + fUsed + " 共 " + order.lines.size() + " 项")
                                : "下单失败：所有通路都不通");
                        if (fResp != null) {
                            // 走中转时不记地址：fUsed 是 "relay"，写进 peer_host
                            // 会让下次启动拿它当 IP 去连（连不上还耽误时间）
                            if (!"relay".equals(fUsed)) {
                                peerHost = fUsed;
                                peerPort = fPort;
                                sp.edit().putString("peer_host", fUsed)
                                        .putInt("peer_port", fPort).apply();
                            }
                            if (peerView != null) {
                                peerView.setText(peerText());
                            }
                            boolean viaRelay = "relay".equals(fUsed);
                            History.Entry e = new History.Entry();
                            e.ts = order.ts;
                            e.total = order.total;
                            e.note = order.note;
                            // 走中转时还不知道对方收没收到，先记"未确认"，
                            // 等 orderAck 回来再改成已送达（见 handleOrderAck）
                            e.delivered = !viaRelay;
                            e.lines.addAll(order.lines);
                            history.add(e);
                            saveHistory();
                            cart.clear();
                            for (String k : new ArrayList<String>(actionBoxes.keySet())) {
                                refreshActions(k);
                            }
                            updateCart();
                            host.burst(18);
                            if (viaRelay) {
                                pendingAcks.put(order.id, e);
                                toastLove("已经发给张老师了，等回执…");
                                scheduleAckWatch(order.id);
                            } else {
                                toastLove("已经送到张老师那儿啦 ❤");
                            }
                        } else {
                            updateCart();
                            toastWarn("没连上张老师，去「我 → 连接设置」填一下地址");
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 接单端回执到了：把这单改成"已送达"，并撤掉超时提醒。
     *
     * 这条是 v4.7 加的。之前异地中转下单是"发出即成功"，
     * 对方在重连窗口里就丢了，用户只会看到"我明明在线"的对骂。
     */
    private void handleOrderAck(String orderId) {
        if (orderId == null || orderId.length() == 0) {
            return;
        }
        History.Entry e = pendingAcks.remove(orderId);
        if (e == null) {
            return;   // 已经确认过，或者不是我等的那一单
        }
        e.delivered = true;
        saveHistory();
        toastLove("张老师收到啦 ❤");
    }

    /**
     * 发出去之后开始等回执。
     *
     * 15 秒还没等到就说实话，别让用户以为送到了。
     * 不做自动重发：同一单重发两次容易被当成两单，让用户自己再点一次更可控。
     */
    private void scheduleAckWatch(final String orderId) {
        sched.postDelayed(new Runnable() {
            public void run() {
                History.Entry e = pendingAcks.remove(orderId);
                if (e == null) {
                    return;
                }
                toastWarn("没等到张老师的回执，可能刚好没连上；再点一次下单试试");
            }
        }, 15000L);
    }

    private void showNoteDialog() {
        if (cart.isEmpty()) {
            toast("先去点两道菜吧～");
            return;
        }
        DialogKit.input(act, "附一句话再下单", "想对 TA 说点什么…", NOTES[0],
                new DialogKit.InputCallback() {
                    public void onInput(String text) {
                        String note = text == null ? "" : text.trim();
                        placeOrder(note.length() == 0 ? "想你了" : note);
                    }
                });
    }

    // ================================================================ 连接

    /** 打开「连接方式」整页（三种通道都在里面） */
    private void openConnectPage() {
        ConnectPage.show(act, false, new Runnable() {
            public void run() {
                peerHost = sp.getString("peer_host", null);
                peerPort = sp.getInt("peer_port", 0);
                if (peerView != null) {
                    peerView.setText(peerText());
                }
            }
        });
    }

    private void showConnectDialog() {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        box.addView(Ui.tv(act, "本机地址：" + Net.localIpv4(), 12, Ui.SUB, false));
        box.addView(Ui.tv(act, Ui.pick(act, "张老师的地址（不知道就留空）", "接单端地址（IP:端口，可留空自动发现）"),
                11, Ui.PRIMARY_DEEP, true), top(12));

        final EditText et = new EditText(act);
        et.setHint("100.94.232.43:8765　或　192.168.1.8:8765");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        if (peerHost != null && peerPort > 0) {
            et.setText(peerHost + ":" + peerPort);
        }
        box.addView(et);

        TextView autoBtn = Ui.pill(act, "🔍 自动发现在同一 Wi-Fi 的接单端",
                Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 12, 16, 9, true);
        autoBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                autoDiscover(true);
            }
        });
        box.addView(autoBtn, top(10));

        // ---- 异地配对（只需一次）----
        final String room = Relay.room(act);
        TextView pairBtn = Ui.pill(act,
                room == null ? "🌐 异地连接（点这里配一次）"
                        : (Relay.MODE_CLOUD.equals(Relay.mode(act))
                        ? "🌐 异地连接：小窝云已启用"
                        : ("🌐 已配对：" + room)),
                room == null ? 0xFFF2EEFB : Ui.PRIMARY_SOFT,
                Ui.PRIMARY_DEEP, 11.5f, 14, 8, true);
        pairBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                showPairDialog();
            }
        });
        box.addView(pairBtn, top(10));

        box.addView(Ui.tv(act,
                "· 异地（推荐）：两台手机都装 Tailscale、登同一个账号，\n"
                        + "　把张老师 App 上「🛰 Tailscale」那行的地址填进来，\n"
                        + "　形如 100.94.232.43:8765 —— 走的是虚拟内网，不用 https\n"
                        + "· 同一个 Wi-Fi：留空即可自动发现\n"
                        + "· 同机调试：127.0.0.1:8765\n"
                        + "· 一般不用填 https://，只有走自建隧道时才需要",
                10.5f, Ui.SUB, false), top(10));

        DialogKit.custom(act, "连接到接单端", box, "保存", new Runnable() {
                    public void run() {
                        String s = et.getText().toString().trim();
                        if (s.length() == 0) {
                            peerHost = null;
                            peerPort = 0;
                            sp.edit().remove("peer_host").remove("peer_port").apply();
                        } else {
                            // 支持 "https://host:port" / "host:port" / "host"
                            String raw = s;
                            boolean secure = raw.regionMatches(true, 0, "https://", 0, 8);
                            if (raw.regionMatches(true, 0, "https://", 0, 8)) {
                                raw = raw.substring(8);
                            } else if (raw.regionMatches(true, 0, "http://", 0, 7)) {
                                raw = raw.substring(7);
                            }
                            String host = raw;
                            int port = secure ? 443 : Net.DEFAULT_PORT;
                            int colon = raw.lastIndexOf(':');
                            if (colon > 0) {
                                host = raw.substring(0, colon);
                                try {
                                    port = Integer.parseInt(raw.substring(colon + 1).trim());
                                } catch (Exception ignore) {
                                    port = secure ? 443 : Net.DEFAULT_PORT;
                                }
                            }
                            // 带 https 时把协议前缀存回去，后续所有请求自动走 HTTPS
                            if (secure) {
                                host = "https://" + host;
                            }
                            peerHost = host;
                            peerPort = port;
                            sp.edit().putString("peer_host", host).putInt("peer_port", port).apply();
                            Net.setRelaxTls(secure);
                            if (secure) {
                                toast("已启用加密连接");
                            }
                        }
                        if (peerView != null) {
                            peerView.setText(peerText());
                        }
                    }
        });

    }

    /**
     * 点单端的异地配对。
     *
     * 点单端在这条链路上其实是**双向**的：
     *   发：订单、消息、爱心
     *   收：接单端的聊天、公告、催单
     * 所以配对后这里也要开一个订阅。
     */
    private void showPairDialog() {
        final Dialog[] holder = new Dialog[1];
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        box.addView(Ui.tv(act, "异地时靠这条通路收发，填一次就永久有效。",
                10.5f, Ui.TEXT_SUB, false));
        box.addView(Ui.tv(act, "接单端「异地连接」里复制的房间码，粘在下面：",
                11, Ui.PRIMARY_DEEP, true), top(10));

        final EditText et = new EditText(act);
        et.setHint("例如 4WNF9Q");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        String cur = Relay.room(act);
        if (cur != null) {
            et.setText(cur);
        }
        box.addView(et, top(8));

        holder[0] = DialogKit.custom(act, "异地配对", box, "保存", new Runnable() {
            public void run() {
                String code = et.getText().toString().trim().toUpperCase();
                if (code.length() == 0) {
                    Relay.clear(act);
                    toastWarn("已清除配对");
                    return;
                }
                Relay.setRoom(act, code);
                Relay.setEnabled(act, true);
                if (act instanceof MainActivity) {
                    ((MainActivity) act).restartRelay();
                }
                toastOk("配对成功：" + code + "　以后不用再填了");
            }
        });
    }

    /**
     * 处理中转消息 —— 由 MainActivity 统一派发。
     *
     * 除了聊天/公告/爱心，这里还要处理**版本查询和更新下载**，
     * 因为异地时点单端没有直连通道，只能靠中转拿到版本信息。
     */
    public void onRelayMessage(String type, String payload) {
        try {
            /*
             * 多台点单端时，一条消息不应该被所有点单端都当成自己的。
             * 带 to 的（指定设备/指定角色）先过滤一遍：
             *   "recv"/"order" = 给那一端的（谁收到都是给自己这边用）
             *   <设备编号>      = 只给那一台
             */
            String to = "";
            try {
                to = Json.str(Json.asObject(Json.parse(payload)), "to", "");
            } catch (Exception ignore) {
            }
            if (to.length() > 0 && !Protocol.ROLE_RECV.equals(to) && !Protocol.ROLE_ORDER.equals(to)
                    && !to.equals(com.couple.xiaowo.core.Client.id(act))) {
                return;
            }
            if (Protocol.TYPE_APK_INFO.equals(type)) {
                com.couple.xiaowo.core.ApkMqtt.onInfo(payload);
                return;
            }
            if (Protocol.TYPE_APK_PART.equals(type)) {
                com.couple.xiaowo.core.ApkMqtt.onPart(payload);
                return;
            }
            if (Protocol.TYPE_ORDER_ACK.equals(type)) {
                java.util.Map<String, Object> m = Json.asObject(Json.parse(payload));
                handleOrderAck(Json.str(m, "id", ""));
            } else if (Protocol.TYPE_CHAT.equals(type)) {
                onChatIncoming(Social.Msg.fromJson(payload));
            } else if (Protocol.TYPE_MENU.equals(type)) {
                // 接单端改了菜谱，经中转推过来（异地时的通路）
                applyMenuFromPeer(payload, "菜谱已从张老师那边更新");
            } else if (Protocol.TYPE_NOTICE.equals(type)) {
                Social.Notice n = Social.Notice.fromJson(payload);
                if (noticeView != null) {
                    noticeView.setText(n.text.length() == 0 ? "公告：暂无" : "公告：" + n.text);
                }
                toastLove("张老师发了公告：" + n.text);
            } else if (Protocol.TYPE_BUZZ.equals(type)) {
                onRemoteBuzz(payload);
            } else if (Protocol.TYPE_HEART.equals(type)) {
                host.burst(24);
                toastLove("张老师回了个爱心 ❤");
            } else if (ApkRelay.PATH_INFO.equals(type) || isApkInfoReply(payload)) {
                // 接单端回了版本信息：显示出来，并引导去更新
                handleApkInfoReply(payload);
            } else if (Protocol.TYPE_SYNC.equals(type)) {
                // 中转推来的同步记录
                final com.couple.xiaowo.core.Sync.Parsed p =
                        com.couple.xiaowo.core.Sync.parse(payload);
                if (p != null) {
                    mergeSync(p);
                }
            } else if (Protocol.TYPE_SHOT.equals(type)) {
                /*
                 * 张老师从中转请求截图。
                 *
                 * 这里本来在主线程上直接截，但无障碍截屏要等系统回调（几十到几百毫秒），
                 * 卡在 UI 线程会让界面停顿。所以丢到后台线程，截好再回传。
                 */
                new Thread(new Runnable() {
                    public void run() {
                        /*
                         * 走中转的截图用低清版：公共 MQTT broker 对大包不友好，
                         * QoS0 下大消息会被丢弃，回传就失败了。
                         */
                        String b64 = com.couple.xiaowo.core.Shot.captureBase64ForRelay(act,
                                OrderScreen.this);
                        if (b64 != null) {
                            com.couple.xiaowo.core.Relay.publish(act, Protocol.ROLE_ORDER,
                                    Protocol.shotImg(b64), 8000);
                        }
                    }
                }).start();
            }
        } catch (Exception ignore) {
        }
    }

    /** 中转回来的版本信息是一个普通 Info JSON，没有 type 字段，靠字段判断 */
    private boolean isApkInfoReply(String payload) {
        try {
            java.util.Map<String, Object> m = Json.asObject(Json.parse(payload));
            return m.containsKey("versionCode") && m.containsKey("size");
        } catch (Exception e) {
            return false;
        }
    }

    private void handleApkInfoReply(String payload) {
        ApkRelay.Info info = ApkRelay.Info.fromJson(payload);
        if (info == null) {
            return;
        }
        int mine = ApkRelay.currentVersionCode(act);
        if (info.versionCode > mine) {
            toastOk("张老师那边有新版 v" + info.versionName + "（你这版是 "
                    + Ui.appVersion(act) + "）");
            /*
             * 以前这里只能提示"传不动"，现在走 MQTT 分块，异地也能更新。
             */
            startMqttUpdate(info);
        } else {
            toast("已经是最新版了（" + Ui.appVersion(act) + "）");
        }
    }

    /**
     * 经中转把更新包拉下来（分块，见 core/ApkMqtt）。
     *
     * 这条和"同一个 Wi-Fi 直连下载"是并列的：直连快，中转能跨网络。
     */
    private void startMqttUpdate(final ApkRelay.Info info) {
        if (com.couple.xiaowo.core.ApkMqtt.busy()) {
            toast("正在接收安装包了，别急…");
            return;
        }
        Toast2.show(act, Toast2.LOADING, "正在接收 v" + info.versionName + "…（走中转，慢一点）");
        com.couple.xiaowo.core.ApkMqtt.start(act, new com.couple.xiaowo.core.ApkMqtt.Listener() {
            public void onProgress(int percent, String note) {
                if (percent % 20 == 0) {
                    Toast2.show(act, Toast2.LOADING, note);
                }
            }

            public void onDone(final java.io.File apk, final int vc, final String vn) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (!Updater.canInstall(act)) {
                            Toast2.warn(act, "需要允许「安装未知应用」才能自动更新");
                            Updater.requestInstallPermission(act);
                            return;
                        }
                        String err = Updater.install(act, apk);
                        if (err != null) {
                            Toast2.warn(act, "安装包收好了，但调不起安装器：" + err);
                        } else {
                            Toast2.ok(act, "正在安装 v" + vn);
                        }
                    }
                });
            }

            public void onError(final String msg) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        Toast2.warn(act, "更新没成功：" + msg);
                    }
                });
            }
        });
    }

    private void autoDiscover(final boolean verbose) {
        toast("正在局域网里找张老师…");
        new Thread(new Runnable() {
            public void run() {
                final List<Net.Peer> peers = Net.discover(2200);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (peers.isEmpty()) {
                            if (verbose) {
                                toastWarn("没找到，试试手动填 IP 或 127.0.0.1:8765");
                            }
                        } else {
                            peerHost = peers.get(0).host;
                            peerPort = peers.get(0).port;
                            sp.edit().putString("peer_host", peerHost).putInt("peer_port", peerPort).apply();
                            if (peerView != null) {
                                peerView.setText(peerText());
                            }
                            toastOk("找到啦：" + peers.get(0).toString());
                        }
                    }
                });
            }
        }).start();
    }

    private void showSearchDialog() {
        DialogKit.input(act, "搜索菜谱", "想吃什么？比如 抱抱", "", new DialogKit.InputCallback() {
            public void onInput(String text) {
                String q = text == null ? "" : text.trim();
                filter = q.length() == 0 ? null : q;
                switchTab(TAB_KITCHEN);
                rebuildSidebarState();
                rebuildList();
            }
        });
    }

    /** 加一份（不带规格的菜）。带规格的走 addToCartSpec */
    private void addToCart(String name, int delta) {
        addToCartSpec(name, "", delta);
    }

    /**
     * 加减一份。key 由「菜名 + 规格」组成，所以同一道菜的不同规格各算一行。
     *
     * 减到 0 时删掉**这一行**，不动同名的其他规格 —— 这是关键：
     * 用户点「要亲亲·脸颊 −」不应该顺手把「要亲亲·嘴巴」也减掉。
     */
    private void addToCartSpec(String name, String spec, int delta) {
        String key = com.couple.xiaowo.core.CartKey.of(name, spec);
        int q = cart.containsKey(key) ? cart.get(key) : 0;
        q += delta;
        if (q <= 0) {
            cart.remove(key);
        } else {
            cart.put(key, q);
        }
        refreshActions(name);
        updateCart();
    }
    private View buildBody() {
        LinearLayout body = Ui.horizontal(act);

        ScrollView sideScroll = new ScrollView(act);
        sideScroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams sideLp = new LinearLayout.LayoutParams(dp(86),
                ViewGroup.LayoutParams.MATCH_PARENT);
        sideScroll.setBackgroundColor(Theme.adapt(0xFFFFFFFF));
        sidebarBox = Ui.vertical(act);
        sidebarBox.setPadding(0, dp(8), 0, dp(8));
        sideScroll.addView(sidebarBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(sideScroll, sideLp);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        listBox = Ui.vertical(act);
        listBox.setPadding(dp(12), dp(12), dp(12), dp(16));
        scroll.addView(listBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT);
        scrollLp.weight = 1f;
        body.addView(scroll, scrollLp);

        buildSidebar();
        rebuildList();
        return body;
    }
    private View buildCartBar() {
        LinearLayout bar = Ui.horizontal(act);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(0xFFFFFFFF, dp(999), Math.max(1, dp(0.8f)), 0xFFEDE9F4));
        bar.setPadding(dp(16), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.setMargins(dp(12), dp(6), dp(12), dp(8));
        bar.setLayoutParams(barLp);

        TextView icon = Ui.tv(act, "🛒", 20, Ui.TEXT, false);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        iLp.leftMargin = dp(2);
        iLp.rightMargin = dp(6);
        bar.addView(icon, iLp);

        LinearLayout money = Ui.vertical(act);
        totalView = Ui.tv(act, "合计 ❤0", 15, Ui.TEXT, true);
        money.addView(totalView);
        subTotalView = Ui.tv(act, "还没点菜哦", 10, Ui.SUB, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        money.addView(subTotalView, subLp);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.weight = 1f;
        bar.addView(money, mLp);

        randBtn = Ui.pill(act, "随机选菜", 0xFFFFFFFF, Ui.TEXT, 11, 11, 8, false);
        randBtn.setBackground(Ui.round(0xFFFFFFFF, dp(999), Math.max(1, dp(0.8f)), 0xFFE7E1F2));
        randBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                randomPick();
            }
        });
        bar.addView(randBtn);

        /*
         * 「选菜进冰箱」模式下多一个「取消」按钮。
         * 平时它隐藏；进入模式时和下单键一起切换形态。
         */
        cancelPickBtn = Ui.pill(act, "取消", 0xFFFFFFFF, Ui.TEXT, 12, 14, 8, false);
        cancelPickBtn.setBackground(Ui.round(0xFFFDECEC, dp(999),
                Math.max(1, dp(0.8f)), 0xFFE7C5C5));
        cancelPickBtn.setVisibility(View.GONE);
        cancelPickBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                exitFridgePickMode();
            }
        });
        bar.addView(cancelPickBtn);

        orderBtn = Ui.pill(act, "下单", 0xFFD9D4E6, 0xFFFFFFFF, 13, 20, 9, true);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        oLp.leftMargin = dp(8);
        bar.addView(orderBtn, oLp);
        orderBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (pickingForFridge) {
                    addCartToFridge();
                } else {
                    placeOrder(NOTES[random.nextInt(NOTES.length)]);
                }
            }
        });
        orderBtn.setOnLongClickListener(new OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (!pickingForFridge) {
                    showNoteDialog();
                }
                return true;
            }
        });
        return bar;
    }

    /** 进入「选菜进冰箱」模式：跳厨房，下单键变「加入冰箱」，显示取消 */
    private void enterFridgePickMode() {
        pickingForFridge = true;
        refreshCartBar();
        // 跳回厨房让用户挑菜
        switchTab(TAB_KITCHEN);
        toast("挑几道菜，点「加入冰箱」就囤进去啦");
    }

    /** 退出选菜模式，恢复普通下单形态，并清掉购物车里选的菜 */
    private void exitFridgePickMode() {
        if (!pickingForFridge) {
            return;
        }
        pickingForFridge = false;
        cart.clear();
        for (String k : new ArrayList<String>(actionBoxes.keySet())) {
            refreshActions(k);
        }
        refreshCartBar();
        updateCart();
        switchTab(TAB_FRIDGE);
    }

    /** 把购物车里的菜全部囤进冰箱（选菜模式下的「加入冰箱」） */
    private void addCartToFridge() {
        if (cart.isEmpty()) {
            toast("先去点两道菜吧～");
            return;
        }
        int added = 0;
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            String key = e.getKey();
            if (!fridge.contains(key)) {
                fridge.add(key);
                added++;
            }
        }
        saveFridge();
        // 退出模式（会清购物车并回冰箱页）
        pickingForFridge = false;
        cart.clear();
        for (String k : new ArrayList<String>(actionBoxes.keySet())) {
            refreshActions(k);
        }
        refreshCartBar();
        updateCart();
        switchTab(TAB_FRIDGE);
        toast(added > 0 ? "已囤进 " + added + " 道菜 🧊" : "这些菜都已经在冰箱里了");
    }

    /**
     * 按当前模式刷新底栏：下单按钮文字、随机/取消按钮可见性。
     */
    private void refreshCartBar() {
        if (orderBtn == null) {
            return;
        }
        if (pickingForFridge) {
            orderBtn.setText("加入冰箱");
            orderBtn.setBackground(Ui.round(Ui.ACCENT, dp(999)));
            orderBtn.setTextColor(0xFFFFFFFF);
            if (randBtn != null) {
                randBtn.setVisibility(View.GONE);
            }
            if (cancelPickBtn != null) {
                cancelPickBtn.setVisibility(View.VISIBLE);
            }
        } else {
            orderBtn.setText("下单");
            orderBtn.setBackground(Ui.round(0xFFD9D4E6, dp(999)));
            orderBtn.setTextColor(0xFFFFFFFF);
            if (randBtn != null) {
                randBtn.setVisibility(View.VISIBLE);
            }
            if (cancelPickBtn != null) {
                cancelPickBtn.setVisibility(View.GONE);
            }
        }
    }
    private View buildFridgePage() {
        LinearLayout page = Ui.vertical(act);
        page.setBackgroundColor(Theme.adapt(Ui.BG));
        page.addView(buildSubHeader("冰箱", "把喜欢的菜囤起来，饿的时候直接下单"), wrap());

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.vertical(act);
        content.setPadding(dp(14), dp(8), dp(14), dp(16));

        if (fridge.isEmpty()) {
            content.addView(emptyState("🧊", "冰箱是空的", "在厨房里长按一道菜，就能囤进冰箱"));
        } else {
            for (final String key : new ArrayList<String>(fridge)) {
                content.addView(buildFridgeRow(key), rowLp());
            }
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            aLp.topMargin = dp(6);
            TextView all = Ui.pill(act, "把冰箱里的都点一份", Ui.PURPLE, 0xFFFFFFFF, 13, 30, 10, true);
            all.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    cart.clear();
                    for (String key : fridge) {
                        /*
                         * 冰箱里存的就是完整的 CartKey（菜名+规格），
                         * 直接用，不再猜规格 —— 囤的是什么就点什么。
                         */
                        String n = com.couple.xiaowo.core.CartKey.nameOf(key);
                        if (byName.get(n) == null) {
                            continue;
                        }
                        Integer prev = cart.get(key);
                        cart.put(key, prev == null ? 1 : prev + 1);
                    }
                    for (String k : new ArrayList<String>(actionBoxes.keySet())) {
                        refreshActions(k);
                    }
                    updateCart();
                    switchTab(TAB_KITCHEN);
                    toast("冰箱里的都放进购物车了");
                }
            });
            content.addView(all, aLp);
        }
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        sLp.weight = 1f;
        page.addView(scroll, sLp);

        TextView add = Ui.pill(act, "＋ 再囤几道", Ui.PURPLE_SOFT, Ui.PURPLE_DEEP, 12, 20, 8, true);
        add.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                /*
                 * 不再弹多选列表，而是进入「选菜模式」：
                 * 跳回厨房，下单键变「加入冰箱」，多一个「取消」。
                 */
                enterFridgePickMode();
            }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMargins(dp(14), dp(4), dp(14), dp(12));
        page.addView(add, addLp);
        return page;
    }
    private View buildFridgeRow(final String key) {
        final String name = com.couple.xiaowo.core.CartKey.nameOf(key);
        final String spec = com.couple.xiaowo.core.CartKey.specOf(key);
        Menu.Item it = byName.get(name);
        if (it == null) {
            return new View(act);
        }
        LinearLayout card = Ui.horizontal(act);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.round(Ui.CARD, dp(16), Math.max(1, dp(0.8f)), 0xFFEDE9F4));
        card.setPadding(dp(10), dp(8), dp(10), dp(8));

        ImageView iv = new ImageView(act);
        Bitmap bm = CatImage.bitmap(act, it.cat);
        if (bm != null) {
            iv.setImageBitmap(bm);
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(iv, 12);
        card.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout texts = Ui.vertical(act);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.weight = 1f;
        tLp.leftMargin = dp(10);
        texts.setLayoutParams(tLp);
        // 有规格的显示成「要亲亲 · 脸颊」，冰箱里两种规格是两条独立记录
        texts.addView(Ui.tv(act, spec.length() == 0 ? it.name : it.name + " · " + spec,
                14, Ui.TEXT, true));
        texts.addView(Ui.tv(act, it.desc + " · ❤" + it.price, 10.5f, Ui.SUB, false));
        card.addView(texts);

        TextView del = Ui.tv(act, "取出", 10.5f, 0xFFD2453F, true);
        del.setPadding(dp(10), dp(6), dp(10), dp(6));
        del.setBackground(Ui.round(0xFFFDECEC, dp(999)));
        del.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                fridge.remove(key);
                saveFridge();
                switchTab(TAB_FRIDGE);
                toast("已从冰箱取出");
            }
        });
        card.addView(del);
        return card;
    }
    private View buildHeader() {
        FrameLayout header = new FrameLayout(act);
        header.setBackground(Ui.roundBottom(0xFFF1EBFF, dp(26)));
        header.setPadding(dp(18), dp(16), dp(14), dp(16));

        LinearLayout col = Ui.vertical(act);
        col.setPadding(0, 0, dp(86), 0);

        LinearLayout titleRow = Ui.horizontal(act);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(Ui.tv(act, "小窝", 24, Ui.TEXT, true));
        TextView lv = Ui.chip(act, "Lv.0", 0xFFE3DAFB, Ui.PURPLE_DEEP, 10);
        LinearLayout.LayoutParams lvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lvLp.leftMargin = dp(8);
        titleRow.addView(lv, lvLp);
        col.addView(titleRow);

        TextView sub = Ui.tv(act, "共 1 人 · 今天也要甜甜的", 11.5f, Ui.SUB, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(5);
        col.addView(sub, subLp);

        TextView notice = Ui.tv(act, "公告：暂无", 11, Ui.SUB, false);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.topMargin = dp(4);
        col.addView(notice, nLp);

        peerView = Ui.tv(act, peerText(), 11, Ui.PURPLE_DEEP, true);
        // 顶部状态本身可点：最自然的入口
        peerView.setClickable(true);
        peerView.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                openConnectPage();
            }
        });
        Ui.pressable(peerView);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = dp(6);
        col.addView(peerView, pLp);

        LinearLayout btns = Ui.horizontal(act);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(12);
        /*
         * 「＋ 添加菜谱」已删除。
         *
         * 它原来只弹一句"菜谱写在 core/Menu.java 里"，点了没有任何实际作用；
         * 真正能加菜谱的入口是「管理 → ✏️ 编辑菜谱」（MenuEditor），
         * 留着一个假按钮只会让人以为坏了。
         */
        TextView search = Ui.pill(act, "🔍 搜索", 0xFFFFFFFF, Ui.TEXT, 11, 12, 7, false);
        search.setBackground(Ui.round(0xFFFFFFFF, dp(999), Math.max(1, dp(0.8f)), 0xFFE7E1F2));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 删掉「添加菜谱」后，搜索变成第一个按钮，不再需要左边距
        sLp.leftMargin = 0;
        btns.addView(search, sLp);
        search.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                showSearchDialog();
            }
        });

        TextView link = Ui.pill(act, "⇄ 连接", 0xFF7B6CD9, 0xFFFFFFFF, 11, 14, 7, true);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.leftMargin = dp(8);
        btns.addView(link, lLp);
        link.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                openConnectPage();
            }
        });
        col.addView(btns, bLp);

        header.addView(col);

        ImageView art = new ImageView(act);
        Bitmap bm = CatImage.bitmap(act, 7);
        if (bm != null) {
            art.setImageBitmap(bm);
        }
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(art, 22);
        FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(dp(86), dp(86));
        artLp.gravity = Gravity.RIGHT | Gravity.TOP;
        header.addView(art, artLp);
        return header;
    }
    private View buildItemRow(final Menu.Item it) {
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(Ui.CARD, dp(16)));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setElevation(dp(1.2f));

        FrameLayout thumb = new FrameLayout(act);
        ImageView iv = new ImageView(act);
        Bitmap bm = CatImage.bitmap(act, it.cat);
        if (bm != null) {
            iv.setImageBitmap(bm);
        } else {
            thumb.setBackground(Ui.round(0xFFF0ECFB, dp(14)));
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(iv, 14);
        thumb.addView(iv, new FrameLayout.LayoutParams(dp(60), dp(60)));
        TextView badge = Ui.tv(act, it.emoji, 11, Ui.TEXT, false);
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(dp(18), dp(18));
        bLp.gravity = Gravity.BOTTOM | Gravity.END;
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(0xCCFFFFFF, dp(9)));
        thumb.addView(badge, bLp);
        row.addView(thumb, new LinearLayout.LayoutParams(dp(60), dp(60)));

        LinearLayout texts = Ui.vertical(act);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.weight = 1f;
        tLp.leftMargin = dp(10);
        tLp.rightMargin = dp(6);
        texts.setLayoutParams(tLp);

        texts.addView(Ui.tv(act, it.name, 15, Ui.TEXT, true));

        LinearLayout stars = Ui.horizontal(act);
        stars.setGravity(Gravity.CENTER_VERTICAL);
        stars.addView(Ui.tv(act, "★★★★★", 9, Ui.STAR, false));
        stars.addView(Ui.tv(act, " " + String.format(Locale.US, "%.1f", it.rating), 10, Ui.STAR, false));
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(3);
        texts.addView(stars, stLp);

        TextView desc = Ui.tv(act, it.desc, 10.5f, Ui.SUB, false);
        desc.setMaxLines(1);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = dp(3);
        texts.addView(desc, dLp);

        /*
         * 「选规格」那个小标签已经去掉。
         *
         * 原因：它和右侧的「+」做的是同一件事（都是点了去选规格），
         * 而且它本身不可点击 —— 用户点了没反应，属于"看着像按钮但不是"。
         * 现在统一由右侧那个圆角矩形按钮承担：有规格时它直接写「选规格」。
         */
        TextView sales = Ui.tv(act, "月销 " + it.monthly + " · ❤" + it.price,
                10, Ui.SUB, false);
        LinearLayout.LayoutParams saLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saLp.topMargin = dp(3);
        texts.addView(sales, saLp);

        row.addView(texts);

        LinearLayout actions = Ui.horizontal(act);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(actions);
        actionBoxes.put(it.name, actions);
        refreshActions(it.name);

        row.setOnLongClickListener(new OnLongClickListener() {
            public boolean onLongClick(View v) {
                /*
                 * 冰箱键也用「菜名 + 规格」。
                 *
                 * 原来只存菜名，带规格的菜进冰箱后规格就丢了，
                 * 之后「全部点一份」只能瞎猜一个（原来猜的是第一个），
                 * 用户囤的是"奶茶·三分糖"，下单却变成"奶茶·全糖"。
                 */
                if (!it.hasSpecs()) {
                    if (fridge.contains(it.name)) {
                        toast("这道菜已经在冰箱里了");
                    } else {
                        fridge.add(it.name);
                        saveFridge();
                        toast("已把「" + it.name + "」存进冰箱 🧊");
                    }
                    return true;
                }
                showFridgeSpecPicker(it);
                return true;
            }
        });
        return row;
    }
    private View buildKitchenPage() {
        LinearLayout page = Ui.vertical(act);
        page.setBackgroundColor(Theme.adapt(Ui.BG));
        page.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        bodyLp.weight = 1f;
        page.addView(buildBody(), bodyLp);
        page.addView(buildCartBar());
        return page;
    }
    private View buildManagePage() {
        LinearLayout page = Ui.vertical(act);
        page.setBackgroundColor(Theme.adapt(Ui.BG));
        page.addView(buildSubHeader("管理", "你的菜单长什么样"), wrap());

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.vertical(act);
        content.setPadding(dp(14), dp(8), dp(14), dp(16));

        for (Menu.Category c : Menu.CATEGORIES) {
            LinearLayout card = Ui.vertical(act);
            card.setBackground(Ui.round(Ui.CARD, dp(16), Math.max(1, dp(0.8f)), 0xFFEDE9F4));
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout head = Ui.horizontal(act);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.tv(act, c.icon + "  " + c.name, 14, Ui.TEXT, true));
            head.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
            head.addView(Ui.chip(act, c.items.size() + " 道", Ui.PURPLE_SOFT, Ui.PURPLE_DEEP, 10));
            card.addView(head);

            StringBuilder sb = new StringBuilder();
            int sum = 0;
            for (Menu.Item it : c.items) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(it.name);
                sum += it.price;
            }
            TextView list = Ui.tv(act, sb.toString(), 11, Ui.SUB, false);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lLp.topMargin = dp(6);
            card.addView(list, lLp);
            card.addView(Ui.tv(act, "整类点完约 ❤" + sum, 10, Ui.PURPLE, false), top(4));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.bottomMargin = dp(10);
            content.addView(card, cLp);
        }
        // 两端都能改菜谱：这里开共用的 MenuEditor，改完存盘并推给对方
        TextView editMenu = Ui.pill(act, "✏️ 编辑菜谱（改名/改价/加规格/删除）",
                Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 12, 18, 9, true);
        editMenu.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                MenuEditor.open(act, new MenuEditor.OnChanged() {
                    public void onChanged() {
                        saveMenuToDisk();
                        byName.clear();
                        for (Menu.Category c : Menu.CATEGORIES) {
                            for (Menu.Item it : c.items) {
                                byName.put(it.name, it);
                            }
                        }
                        pages[TAB_KITCHEN] = null;
                        pages[TAB_MANAGE] = null;
                        switchTab(TAB_MANAGE);
                        buildSidebar();
                        rebuildList();
                        pushMenuToPeer(null);
                    }
                });
            }
        });
        content.addView(editMenu, top(6));
        content.addView(Ui.tv(act, "改完会自动同步给张老师；张老师那边改了也会同步回来",
                10.5f, Ui.SUB, false), top(4));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        sLp.weight = 1f;
        page.addView(scroll, sLp);
        return page;
    }
    private View buildMinePage() {
        LinearLayout page = Ui.vertical(act);
        page.setBackgroundColor(Theme.adapt(Ui.BG));
        page.addView(buildSubHeader("我", "这一半的小窝"), wrap());

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.vertical(act);
        content.setPadding(dp(14), dp(8), dp(14), dp(16));

        // ---------- 身份卡 ----------
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(Ui.R_CARD)));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setElevation(dp(2f));
        LinearLayout who = Ui.horizontal(act);
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.addView(image(dp(58), 4, 18), new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout names = Ui.vertical(act);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.weight = 1f;
        nLp.leftMargin = dp(12);
        names.setLayoutParams(nLp);
        names.addView(Ui.tv(act, "猫猫", 16, Ui.TEXT, true));
        names.addView(Ui.tv(act, "点单的这一半 · 一共点过 " + history.size() + " 单",
                11, Ui.TEXT_SUB, false));
        who.addView(names);
        card.addView(who);
        card.addView(Ui.dividerTop(act, 14));
        card.addView(rowAccent("对方", "张老师"));
        card.addView(row("接单端", peerHost == null ? "未连接" : (peerHost + ":" + peerPort)));
        card.addView(row("本机地址", Net.localIpv4()));
        content.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 聊天 ----------
        LinearLayout chatCard = Ui.vertical(act);
        chatCard.setBackground(Ui.round(Ui.CARD, dp(Ui.R_CARD)));
        chatCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        chatCard.setElevation(dp(2f));
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp.topMargin = dp(12);
        chatCard.setLayoutParams(ccLp);
        LinearLayout chatHead = Ui.horizontal(act);
        chatHead.setGravity(Gravity.CENTER_VERTICAL);
        chatHead.addView(Ui.tv(act, "💬 和张老师说说", 14, Ui.TEXT, true));
        chatHead.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        chatHead.addView(Ui.chip(act, chatLog.size() + " 条", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 10));
        chatCard.addView(chatHead);
        chatCard.addView(Ui.dividerTop(act, 10));
        chatScroll = new ScrollView(act);
        chatScroll.setVerticalScrollBarEnabled(false);
        /*
         * 修复「聊天框划不动」。
         *
         * 聊天区在「我」页里，而「我」页外面本身就是一个 ScrollView ——
         * ScrollView 套 ScrollView 时，外层会把垂直手势全部吃掉，
         * 内层永远收不到滑动，看起来就是"聊天记录翻不了页"。
         *
         * 做法：手指落在聊天区上时，通知所有父容器不要拦截触摸事件，
         * 抬手/取消时再恢复，让外层继续正常工作。
         */
        chatScroll.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                int action = e.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN
                        || action == android.view.MotionEvent.ACTION_MOVE) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                } else if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;   // 不消费事件，交给 ScrollView 自己处理
            }
        });
        chatBox = Ui.vertical(act);
        chatBox.setPadding(0, dp(10), 0, dp(6));
        chatScroll.addView(chatBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chatCard.addView(chatScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
        LinearLayout sendRow = Ui.horizontal(act);
        sendRow.setGravity(Gravity.CENTER_VERTICAL);
        final EditText input = new EditText(act);
        input.setHint("说点什么…");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        input.setBackground(Ui.round(0xFFF7F4FB, dp(999)));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL);
        // 多行时不横向滚动，否则长句只在一条线里挤着走，看着像"溢出"
        input.setHorizontallyScrolling(false);
        input.setVerticalScrollBarEnabled(false);
        /*
         * 输入框高度：收起 40dp、点开 132dp。
         *
         * 原来固定单行 ~36dp，一行长句只能看到末尾几个字，
         * 想回头改前半句就得删了重打。现在点一下（获得焦点）就展开成
         * 多行输入框，失焦再收回去 —— 聊天卡片本身不变高，只借上面的空间。
         */
        final int inShort = dp(40);
        final int inLong = dp(132);
        sendRow.addView(input, new LinearLayout.LayoutParams(0, inShort, 1f));
        input.setOnFocusChangeListener(new OnFocusChangeListener() {
            public void onFocusChange(View v, final boolean hasFocus) {
                // 展开时允许多行换行，收起时回到单行（回车即发送的直觉）
                if (hasFocus) {
                    input.setSingleLine(false);
                    input.setMaxLines(5);
                    input.setGravity(Gravity.TOP | Gravity.START);
                    input.setPadding(dp(14), dp(10), dp(14), dp(10));
                    /*
                     * 展开时长条用 14dp 圆角 —— 半径 999 在 132dp 高的框上
                     * 会变成一个巨大的胶囊（看着像"圆角矩形"，文字还被挤在中间），
                     * 这里显式换成长条形状。
                     */
                    input.setBackground(Ui.round(0xFFF7F4FB, dp(14)));
                    input.setVerticalScrollBarEnabled(true);
                } else {
                    input.setSingleLine(true);
                    input.setGravity(Gravity.CENTER_VERTICAL);
                    input.setBackground(Ui.round(0xFFF7F4FB, dp(999)));
                    input.setVerticalScrollBarEnabled(false);
                }
                final ViewGroup.LayoutParams lp = input.getLayoutParams();
                android.animation.ValueAnimator va = android.animation.ValueAnimator
                        .ofInt(lp.height < 0 ? inShort : lp.height, hasFocus ? inLong : inShort);
                va.setDuration(180L);
                va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        ViewGroup.LayoutParams p = input.getLayoutParams();
                        p.height = (Integer) a.getAnimatedValue();
                        input.setLayoutParams(p);
                        // 展开时把这一行顶到可见区域，别被键盘挡住
                        if (hasFocus && chatScroll != null) {
                            chatScroll.smoothScrollBy(0, dp(40));
                        }
                    }
                });
                va.start();
            }
        });
        TextView send = Ui.pill(act, "发送", Ui.PRIMARY, 0xFFFFFFFF, 13, 16, 8, true);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.leftMargin = dp(8);
        send.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                String text = input.getText().toString().trim();
                if (text.length() == 0) {
                    return;
                }
                input.setText("");
                sendChat(text);
            }
        });
        sendRow.addView(send, sLp);
        chatCard.addView(sendRow, top(8));

        // ---------- 聊天记录备份 ----------
        LinearLayout backupRow = Ui.horizontal(act);
        backupRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView backupHint = Ui.tv(act, "聊天记录", 10.5f, Ui.TEXT_MUTED, false);
        backupRow.addView(backupHint);
        backupRow.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView exportBtn = Ui.pill(act, "⬇ 导出备份", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                11, 12, 6, true);
        exportBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                com.couple.xiaowo.core.ChatIO.export(act, chatLog, false,
                        new com.couple.xiaowo.core.ChatIO.OnExported() {
                            public void onExported(String fileName, int count) {
                                toastOk("已导出 " + count + " 条到「下载/小窝/" + fileName + "」");
                            }
                        },
                        new com.couple.xiaowo.core.ChatIO.OnError() {
                            public void onError(String msg) {
                                toastWarn(msg);
                            }
                        });
            }
        });
        backupRow.addView(exportBtn);

        TextView importBtn = Ui.pill(act, "⬆ 导入", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                11, 12, 6, true);
        LinearLayout.LayoutParams ibLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ibLp.leftMargin = dp(6);
        importBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                com.couple.xiaowo.core.ChatIO.pickImport(act, true,
                        new com.couple.xiaowo.core.ChatIO.OnImported() {
                            public void onImported(java.util.List<com.couple.xiaowo.core.Social.Msg> msgs,
                                                   String fileName) {
                                int added = com.couple.xiaowo.core.ChatIO.mergeInto(chatLog, msgs);
                                saveChat();
                                rebuildChat();
                                if (added > 0) {
                                    toastOk("导入了 " + added + " 条聊天记录");
                                } else {
                                    toast("这个备份里的记录都已经有了");
                                }
                            }
                        },
                        new com.couple.xiaowo.core.ChatIO.OnError() {
                            public void onError(String msg) {
                                toastWarn(msg);
                            }
                        });
            }
        });
        backupRow.addView(importBtn, ibLp);
        chatCard.addView(backupRow, top(8));

        content.addView(chatCard);
        rebuildChat();

        // ---------- 从张老师那儿拿新版 ----------
        LinearLayout.LayoutParams peerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        peerLp.topMargin = dp(12);
        content.addView(PeerUpdateCard.build(act, peerHost, peerPort), peerLp);

        // ---------- 从 GitHub 更新（不需要连上对方）----------
        LinearLayout.LayoutParams ghLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ghLp.topMargin = dp(12);
        content.addView(GitHubCard.build(act, false), ghLp);

        // ---------- 后台保活 ----------
        LinearLayout.LayoutParams kaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kaLp.topMargin = dp(12);
        content.addView(KeepAliveCard.build(act, false), kaLp);

        // ---------- 外观（浅色 / 深色） ----------
        if (act instanceof MainActivity) {
            LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            thLp.topMargin = dp(12);
            content.addView(ThemeCard.build((MainActivity) act), thLp);
        }

        // ---------- 提醒程度 ----------
        LinearLayout.LayoutParams rmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rmLp.topMargin = dp(12);
        content.addView(RemindSettings.build(act, null), rmLp);

        // ---------- Gitee 备用更新 ----------
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        upLp.topMargin = dp(12);
        content.addView(UpdateCard.build(act), upLp);

        // ---------- 专业模式 ----------
        /*
         * 默认是"给普通人看的版本"：不出现端口、QoS、broker 这些词。
         * 打开之后才把技术细节放出来，方便自己调试。
         */
        final TextView proBtn = Ui.pill(act,
                Ui.pro(act) ? "🧑‍🔧 专业模式：开" : "🧑‍🔧 专业模式：关",
                Ui.pro(act) ? Ui.PRIMARY : Ui.PRIMARY_SOFT,
                Ui.pro(act) ? 0xFFFFFFFF : Ui.PRIMARY_DEEP, 12, 22, 9, true);
        proBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                Ui.setPro(act, !Ui.pro(act));
                Toast2.ok(act, Ui.pro(act) ? "专业模式已打开" : "已切换到简洁模式");
                switchTab(TAB_ME);
            }
        });
        LinearLayout.LayoutParams proLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        proLp.topMargin = dp(12);
        content.addView(proBtn, proLp);

        // ---------- 日志（只有专业模式才出现） ----------
        if (Ui.pro(act)) {
            TextView logBtn = Ui.pill(act, "🧾 运行日志", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                    12, 22, 9, true);
            logBtn.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    LogPage.show(act);
                }
            });
            LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            logLp.topMargin = dp(8);
            content.addView(logBtn, logLp);
        }

        // ---------- 连接 / 清空 ----------
        TextView conn = Ui.pill(act, "⇄ 连接设置", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 12, 22, 9, true);
        conn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                openConnectPage();
            }
        });
        LinearLayout.LayoutParams cLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp2.topMargin = dp(12);
        content.addView(conn, cLp2);

        TextView clear = Ui.pill(act, "清空订单记录", Ui.DANGER_SOFT, Ui.DANGER, 12, 22, 9, true);
        clear.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                DialogKit.confirm(act, "清空订单记录？",
                        "只清空这台手机上的记录，不影响张老师那边。", "清空", new Runnable() {
                            public void run() {
                                history.clear();
                                saveHistory();
                                switchTab(TAB_ME);
                                toastOk("已清空");
                            }
                        });
            }
        });
        LinearLayout.LayoutParams cLp3 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp3.topMargin = dp(8);
        content.addView(clear, cLp3);

        /*
         * 「我」页最下面的爱心按钮。
         *
         * 点一下爆一小簇；**按住不放就一直蹦、一直震**（松手才停）——
         * 和长按菜品喷泡泡同一套节奏（见 attachLongPressBubble），
         * 手指不松开，爱心就一簇接一簇地往外冒。
         */
        TextView love = Ui.pill(act, "❤️", Ui.ACCENT_SOFT, Ui.ACCENT, 22, 24, 12, true);
        love.setGravity(Gravity.CENTER);
        love.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                host.burst(18);
                vibrate();
            }
        });
        attachLongPressHeart(love);
        love.setContentDescription("撒一把爱心，长按会一直撒");
        LinearLayout.LayoutParams loveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loveLp.topMargin = dp(16);
        content.addView(love, loveLp);

        content.addView(Ui.tv(act, "小窝 · 猫猫的点单端 " + Ui.appVersion(act),
                10, Ui.TEXT_MUTED, false), top(16));

        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        sLp2.weight = 1f;
        page.addView(scroll, sLp2);
        return page;
    }
    private View buildNav() {
        LinearLayout nav = Ui.horizontal(act);
        nav.setBackgroundColor(Theme.adapt(0xFFFFFFFF));
        // 分隔线：深色主题下纯白底和页面背景会糊在一起，加一条极淡的顶边
        nav.setPadding(0, dp(6), 0, dp(6));
        for (int i = 0; i < 5; i++) {
            final int index = i;
            LinearLayout tabView = Ui.vertical(act);
            tabView.setGravity(Gravity.CENTER);
            // 触摸目标：整块至少 52dp 高（规范 ≥48dp），图标也一并放大
            tabView.setMinimumHeight(dp(52));
            tabView.setPadding(0, dp(4), 0, dp(4));

            TextView icon = Ui.tv(act, TAB_ICON[i], 19, 0xFF9B96A8, false);
            icon.setGravity(Gravity.CENTER);
            // emoji 是装饰性的，屏幕阅读器读"房子"这种没意义，交给父容器读页面名
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tabView.addView(icon);

            TextView label = Ui.tv(act, TAB_NAME[i], 10.5f, 0xFF9B96A8, false);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lLp.topMargin = dp(3);
            tabView.addView(label, lLp);
            navIcons[i] = icon;
            navLabels[i] = label;
            tabView.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    switchTab(index);
                }
            });
            // 无障碍：整块读出「厨房，标签页」，并标注当前选中
            tabView.setContentDescription(TAB_NAME[i] + "页面");
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.weight = 1f;
            nav.addView(tabView, tLp);
        }
        return nav;
    }
    private View buildOrderCard(final History.Entry e) {
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(18), Math.max(1, dp(0.8f)), 0xFFEDE9F4));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setElevation(dp(1.5f));

        LinearLayout top = Ui.horizontal(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.tv(act, "💗 已下单", 12, Ui.PURPLE_DEEP, true));
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        spLp.weight = 1f;
        TextView time = Ui.tv(act, dayFmt.format(new Date(e.ts)), 10, Ui.SUB, false);
        time.setGravity(Gravity.END);
        top.addView(time, spLp);
        card.addView(top);

        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        lineLp.topMargin = dp(8);
        lineLp.bottomMargin = dp(8);
        card.addView(Ui.divider(act), lineLp);

        for (Protocol.Line l : e.lines) {
            LinearLayout row = Ui.horizontal(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // displayName() 会把规格带上（要亲亲·脸颊），
            // 否则历史里两种规格看起来一模一样，分不清当初点的是哪个
            row.addView(Ui.tv(act, (l.emoji == null || l.emoji.length() == 0 ? "🐾" : l.emoji)
                            + " " + l.displayName(),
                    12.5f, Ui.TEXT, false), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(Ui.tv(act, "×" + l.qty + "  ❤" + (l.price * l.qty), 11.5f, Ui.SUB, false));
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(4);
            card.addView(row, rLp);
        }

        LinearLayout bottom = Ui.horizontal(act);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(10);
        bottom.addView(Ui.tv(act, "合计 ❤" + e.total, 12, Ui.TEXT, true));
        if (e.note != null && e.note.length() > 0) {
            TextView note = Ui.tv(act, "  ·  " + e.note, 11, Ui.PURPLE, false);
            note.setMaxLines(1);
            bottom.addView(note, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            bottom.addView(new View(act), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        TextView again = Ui.pill(act, "再来一单", 0xFFF2EEFB, Ui.PURPLE_DEEP, 11, 12, 6, true);
        again.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                reorder(e);
            }
        });
        bottom.addView(again);
        card.addView(bottom, bLp);
        return card;
    }
    private View buildOrdersPage() {
        LinearLayout page = Ui.vertical(act);
        page.setBackgroundColor(Theme.adapt(Ui.BG));
        page.addView(buildSubHeader("订单", "你点过的每一份心意，都在这儿"), wrap());

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.vertical(act);
        content.setPadding(dp(14), dp(8), dp(14), dp(16));

        if (history.isEmpty()) {
            content.addView(emptyState("🧾", "还没有下过单", "去「厨房」点两道菜试试？"));
        } else {
            for (int i = history.size() - 1; i >= 0; i--) {
                content.addView(buildOrderCard(history.get(i)), rowLp());
            }
        }
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        sLp.weight = 1f;
        page.addView(scroll, sLp);
        return page;
    }
    private void buildSidebar() {
        sidebarBox.removeAllViews();
        for (int i = 0; i < Menu.CATEGORIES.size(); i++) {
            final int index = i;
            Menu.Category c = Menu.CATEGORIES.get(i);
            LinearLayout row = Ui.horizontal(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(12), dp(6), dp(12));

            View bar = new View(act);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), dp(16));
            barLp.rightMargin = dp(7);
            bar.setBackground(Ui.round(i == currentCat ? Ui.PURPLE : 0x00000000, dp(2)));
            row.addView(bar, barLp);

            TextView t = Ui.tv(act, c.icon + " " + c.name, 12,
                    i == currentCat ? Ui.PURPLE_DEEP : 0xFF6E6A78, i == currentCat);
            t.setMaxLines(1);
            row.addView(t);

            row.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    currentCat = index;
                    filter = null;
                    rebuildSidebarState();
                    rebuildList();
                }
            });
            sidebarBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
    private View createPage(int t) {
        switch (t) {
            case TAB_ORDERS:
                return buildOrdersPage();
            case TAB_FRIDGE:
                return buildFridgePage();
            case TAB_MANAGE:
                return buildManagePage();
            case TAB_ME:
                return buildMinePage();
            default:
                return buildKitchenPage();
        }
    }
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }
    private LinearLayout.LayoutParams headLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(2);
        p.bottomMargin = dp(10);
        return p;
    }
    private void loadStore() {
        history = History.parse(sp.getString("order_history", "[]"));
        fridge = History.fridgeFrom(sp.getString("fridge", "[]"));
        /*
         * 聊天记录和公告也必须恢复。
         *
         * 原来这两项只在内存里：Activity 一旦重建（切深色、从授权页返回、
         * 系统因内存紧张回收）就全没了，而且下次 saveChat 会把空列表写回磁盘 ——
         * 等于**永久丢失**。这是用户会直接看到的问题。
         */
        try {
            chatLog.clear();
            // 点单端是「猫猫」那一端：who=="cat" 就是我发的
            chatLog.addAll(Social.parseMsgs(sp.getString("chat", "[]"), true));
        } catch (Exception ignore) {
        }
        try {
            notice = Social.parseNotice(sp.getString("notice", ""));
        } catch (Exception ignore) {
        }
    }
    /**
     * 顶部的连接状态文案。
     *
     * 之前这里是"存了地址就显示已连接"，属于假状态 ——
     * 对方没开机、IP 变了、走的是中转，全都显示"已连接"，
     * 这就是加入房间后仍显示未连接的误导来源。
     *
     * 现在按**实际可用的通道**来说话：
     *   有直连地址 → 说直连（但注明"未验证"，因为可以离线）
     *   只有房间码 → 说中转已配对
     *   都没有     → 未连接
     */
    private String peerText() {
        boolean hasDirect = peerHost != null && peerPort > 0;
        String room = com.couple.xiaowo.core.Relay.room(act);
        if (hasDirect) {
            return "⇄ 直连 " + peerHost + ":" + peerPort;
        }
        if (room != null) {
            if (Relay.MODE_CLOUD.equals(Relay.mode(act))) {
                return "🌐 异地连接：小窝云";
            }
            if (Relay.MODE_SELF.equals(Relay.mode(act))) {
                return "🌐 自建服务器 · 房间 " + room;
            }
            return "🌐 中转已配对 · 房间 " + room;
        }
        return "⇄ 未连接（点这里配一下）";
    }
    private void randomPick() {
        List<Menu.Item> all = new ArrayList<Menu.Item>();
        for (Menu.Category c : Menu.CATEGORIES) {
            all.addAll(c.items);
        }
        int n = 1 + random.nextInt(2);
        for (int i = 0; i < n; i++) {
            Menu.Item it = all.get(random.nextInt(all.size()));
            // 有规格的随机挑一个规格，别把"没选规格"的菜塞进购物车
            String spec = it.hasSpecs()
                    ? it.specs.get(random.nextInt(it.specs.size())) : "";
            addToCartSpec(it.name, spec, 1);
        }
        toast("帮你随便点了点～");
    }
    private void rebuildList() {
        if (listBox == null) {
            return;
        }
        listBox.removeAllViews();
        actionBoxes.clear();
        if (filter != null) {
            listBox.addView(Ui.tv(act, "搜索：" + filter, 13, Ui.SUB, true), headLp());
            for (Menu.Category c : Menu.CATEGORIES) {
                for (Menu.Item it : c.items) {
                    if (it.name.contains(filter) || it.desc.contains(filter)) {
                        listBox.addView(buildItemRow(it), rowLp());
                    }
                }
            }
            if (listBox.getChildCount() == 1) {
                TextView empty = Ui.tv(act, "没有找到这道菜～", 12, Ui.SUB, false);
                LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                eLp.topMargin = dp(30);
                empty.setGravity(Gravity.CENTER);
                listBox.addView(empty, eLp);
            }
            return;
        }
        Menu.Category c = Menu.CATEGORIES.get(currentCat);
        listBox.addView(Ui.tv(act, c.name + "（" + c.items.size() + "）", 13, Ui.SUB, true), headLp());
        for (Menu.Item it : c.items) {
            listBox.addView(buildItemRow(it), rowLp());
        }
    }
    private void rebuildSidebarState() {
        for (int i = 0; i < sidebarBox.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) sidebarBox.getChildAt(i);
            View bar = row.getChildAt(0);
            TextView t = (TextView) row.getChildAt(1);
            boolean on = (i == currentCat && filter == null);
            bar.setBackground(Ui.round(on ? Ui.PURPLE : 0x00000000, dp(2)));
            t.setTextColor(on ? Ui.PURPLE_DEEP : 0xFF6E6A78);
            t.setTypeface(Typeface.create(Typeface.SANS_SERIF, on ? Typeface.BOLD : Typeface.NORMAL));
        }
    }
    /**
     * 右侧操作区。
     *
     * 形态上统一用**圆角矩形**（原来是圆形），因为带规格的菜需要一个
     * 能放字的按钮：没规格显示「＋」，有规格显示「选规格」。
     * 圆形放不下三个字，硬塞会挤成很小。
     *
     * 加号/选规格合并成一个按钮后，就没有"两个入口做同一件事"的困惑了。
     */
    private void refreshActions(String name) {
        LinearLayout box = actionBoxes.get(name);
        if (box == null) {
            return;
        }
        box.removeAllViews();
        Menu.Item it = byName.get(name);
        boolean needSpec = it != null && it.hasSpecs();
        // 同一种菜可能散在多个规格行里，这里显示的是**总份数**
        int qty = com.couple.xiaowo.core.CartKey.totalQty(cart, name);

        if (qty > 0) {
            TextView minus = rectBtn("−", 0xFFF2EEFB, Ui.PURPLE_DEEP, dp(30));
            minus.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    minusFor(name);
                }
            });
            box.addView(minus);

            TextView count = Ui.tv(act, String.valueOf(qty), 13, Ui.PURPLE, true);
            count.setGravity(Gravity.CENTER);
            count.setContentDescription("已选 " + qty + " 份");
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.leftMargin = dp(7);
            cLp.rightMargin = dp(7);
            box.addView(count, cLp);

            TextView plus = rectBtn(needSpec ? "选规格" : "＋",
                    Ui.PURPLE, 0xFFFFFFFF, needSpec ? dp(64) : dp(30));
            plus.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    plusFor(name);
                }
            });
            box.addView(plus);
        } else {
            TextView plus = rectBtn(needSpec ? "选规格" : "＋",
                    0xFFEDE7FB, Ui.PURPLE_DEEP, needSpec ? dp(64) : dp(30));
            plus.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    plusFor(name);
                }
            });
            box.addView(plus);
        }
    }

    /**
     * 圆角矩形小按钮。
     *
     * @param widthPx 宽度；高度固定，宽度按文字量给（「＋」窄、「选规格」宽）
     */
    private TextView rectBtn(String text, int bg, int fg, int widthPx) {
        TextView t = Ui.tv(act, text, text.length() > 1 ? 12f : 16f, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(bg, dp(10)));
        t.setMinWidth(widthPx);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        t.setLayoutParams(lp);
        // 规范：图标/符号按钮必须有可读的无障碍名称
        t.setContentDescription("＋".equals(text) ? "增加一份"
                : "−".equals(text) ? "减少一份" : text);
        // 视觉高度 36dp，热区补到 44dp，手指更好点
        Ui.expandTouch(t, 44);
        return t;
    }

    /**
     * 点「+」：有规格的菜先弹选择，没规格的直接加一份。
     *
     * 为什么每次加都弹：规格之间可以同时点（"脸颊"和"嘴巴"都要），
     * 记住"上次选的"反而会让用户点错还没发觉。
     */
    private void plusFor(String name) {
        Menu.Item it = byName.get(name);
        if (it == null) {
            return;
        }
        if (!it.hasSpecs()) {
            addToCart(name, 1);
            return;
        }
        showSpecPicker(it, null);
    }

    /**
     * 点「−」：把这一份减掉。
     *
     * 关键：**必须按规格减**。
     *
     * 原来这里直接调 addToCart(name, -1)，而 addToCart 拼的是
     * "菜名 + 空规格" 的 key —— 带规格的菜在购物车里根本没有那个 key，
     * 所以 cart.get() 返回 null、q 变成 -1、然后 remove 一个不存在的键。
     * 结果就是：带规格的菜点了减号**完全没反应**，份数一点不变。
     *
     * 现在的规则：
     *   · 没规格的菜 → 直接减 1
     *   · 只选了 1 种规格 → 直接减那一种（不用问，没有歧义）
     *   · 选了 2 种以上规格 → 弹窗让用户选减哪一个
     */
    private void minusFor(String name) {
        Menu.Item it = byName.get(name);
        if (it == null) {
            return;
        }
        java.util.List<String> usedSpecs = new java.util.ArrayList<String>();
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            if (com.couple.xiaowo.core.CartKey.nameOf(e.getKey()).equals(name)) {
                usedSpecs.add(com.couple.xiaowo.core.CartKey.specOf(e.getKey()));
            }
        }
        if (usedSpecs.isEmpty()) {
            return;
        }
        if (usedSpecs.size() == 1) {
            // 只有一种，直接减，不用打扰用户
            addToCartSpec(name, usedSpecs.get(0), -1);
            return;
        }
        // 多种规格：让用户明确选减哪个
        showSpecPickerForRemove(it, usedSpecs);
    }

    /**
     * 减规格弹窗：列出**购物车里实际有的**规格及其份数。
     *
     * 和加规格的弹窗分开，因为这里要显示份数，且只列已在购物车里的规格
     * （点一个没买的规格去"减"是没有意义的）。
     */
    private void showSpecPickerForRemove(final Menu.Item it,
                                         java.util.List<String> usedSpecs) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(6), dp(4), dp(6), dp(4));
        box.addView(Ui.tv(act, "要减掉哪一个" + it.name + "？", 12.5f, Ui.TEXT, true));

        final Dialog[] dlgRef = new Dialog[1];
        for (final String sp : usedSpecs) {
            String key = com.couple.xiaowo.core.CartKey.of(it.name, sp);
            int q = cart.containsKey(key) ? cart.get(key) : 0;
            if (q <= 0) {
                continue;
            }
            String label = (sp.length() == 0 ? "不选规格" : sp) + "　×" + q;
            TextView row = Ui.pill(act, label, 0xFFF7F5FA, Ui.TEXT, 13f, 16, 12, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(46));
            row.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    addToCartSpec(it.name, sp, -1);
                    Dialog d = dlgRef[0];
                    if (d != null) {
                        d.dismiss();
                    }
                }
            });
            box.addView(row, sheetRow(8));
        }
        dlgRef[0] = DialogKit.sheet(act, "减掉一份", box);
    }

    /**
     * 规格选择弹窗。
     *
     * @param defaultSpec 预选中的规格（null 表示不预选）
     */
    private void showSpecPicker(final Menu.Item it, String defaultSpec) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(6), dp(4), dp(6), dp(4));

        box.addView(Ui.tv(act, "选一个" + it.name + "的规格", 12.5f, Ui.TEXT, true));
        box.addView(Ui.tv(act, it.desc, 10.5f, Ui.TEXT_MUTED, false), top(4));

        // 每个规格一行，点了直接加进购物车并关掉弹窗
        final Dialog[] dlgRef = new Dialog[1];
        for (final String sp : it.specs) {
            TextView row = Ui.pill(act, sp, 0xFFF7F5FA, Ui.TEXT, 13f, 16, 12, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(46));
            row.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    addToCartSpec(it.name, sp, 1);
                    Dialog d = dlgRef[0];
                    if (d != null) {
                        d.dismiss();
                    }
                    toastOk("已加「" + it.name + "·" + sp + "」");
                }
            });
            box.addView(row, sheetRow(8));
        }
        dlgRef[0] = DialogKit.sheet(act, "选规格", box);
    }
    /**
     * 长按入冰箱时，让用户选一个规格囤起来。
     *
     * 之所以要选：冰箱是"下次直接点"的快捷方式，
     * 囤一个模糊的菜名没有意义 —— 下次还是得重新选规格。
     */
    private void showFridgeSpecPicker(final Menu.Item it) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(6), dp(4), dp(6), dp(4));
        box.addView(Ui.tv(act, "囤哪个规格的" + it.name + "？", 12.5f, Ui.TEXT, true));

        final Dialog[] dlgRef = new Dialog[1];
        for (final String sp : it.specs) {
            final String key = com.couple.xiaowo.core.CartKey.of(it.name, sp);
            boolean already = fridge.contains(key);
            TextView row = Ui.pill(act, sp + (already ? "（已在冰箱）" : ""),
                    0xFFF7F5FA, Ui.TEXT, 13f, 16, 12, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(46));
            row.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (fridge.contains(key)) {
                        toast("这个规格已经在冰箱里了");
                    } else {
                        fridge.add(key);
                        saveFridge();
                        toastOk("已把「" + it.name + " · " + sp + "」存进冰箱 🧊");
                    }
                    Dialog d = dlgRef[0];
                    if (d != null) {
                        d.dismiss();
                    }
                }
            });
            box.addView(row, sheetRow(8));
        }
        dlgRef[0] = DialogKit.sheet(act, "存进冰箱「" + it.name + "」", box);
    }

    private void reorder(History.Entry e) {
        cart.clear();
        for (Protocol.Line l : e.lines) {
            if (byName.containsKey(l.name)) {
                String k = com.couple.xiaowo.core.CartKey.of(l.name, l.spec);
                int prev = cart.containsKey(k) ? cart.get(k) : 0;
                cart.put(k, prev + l.qty);
            }
        }
        for (String k : new ArrayList<String>(actionBoxes.keySet())) {
            refreshActions(k);
        }
        updateCart();
        switchTab(TAB_KITCHEN);
        toast("已经把这一单放回购物车");
    }
    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(10);
        return p;
    }
    private void saveFridge() {
        sp.edit().putString("fridge", History.fridgeToJson(fridge)).apply();
    }
    private void saveHistory() {
        sp.edit().putString("order_history", History.toJson(history)).apply();
    }

    /** 把对方推来的记录合并进来（按 ts 去重，幂等） */
    private void mergeSync(com.couple.xiaowo.core.Sync.Parsed p) {
        boolean changed = false;
        // 合并订单：同 ts 视为同一条，跳过
        java.util.Set<Long> haveTs = new java.util.HashSet<Long>();
        for (History.Entry e : history) {
            haveTs.add(e.ts);
        }
        for (History.Entry e : p.history) {
            if (e.ts > 0 && !haveTs.contains(e.ts)) {
                history.add(e);
                haveTs.add(e.ts);
                changed = true;
            }
        }
        // 合并聊天：同 ts + who + text 视为同一条
        java.util.Set<String> chatKeys = new java.util.HashSet<String>();
        for (Social.Msg m : chatLog) {
            chatKeys.add(m.who + "|" + m.ts + "|" + m.text);
        }
        for (Social.Msg m : p.chat) {
            String key = m.who + "|" + m.ts + "|" + m.text;
            if (!chatKeys.contains(key)) {
                // 同步过来的消息：mine 要按本机角色重算，不能沿用对方的值
                m.mine = Social.mineForWho(m.who, true);
                chatLog.add(m);
                chatKeys.add(key);
                changed = true;
            }
        }
        if (changed) {
            // 保持时间有序 + 上限
            java.util.Collections.sort(history, new java.util.Comparator<History.Entry>() {
                public int compare(History.Entry a, History.Entry b) {
                    return Long.compare(a.ts, b.ts);
                }
            });
            java.util.Collections.sort(chatLog, new java.util.Comparator<Social.Msg>() {
                public int compare(Social.Msg a, Social.Msg b) {
                    return Long.compare(a.ts, b.ts);
                }
            });
            while (chatLog.size() > 200) {
                chatLog.remove(0);
            }
            saveHistory();
            saveChat();
            // 刷新受影响页面
            pages[TAB_ORDERS] = null;
            pages[TAB_ME] = null;
            if (tab == TAB_ORDERS || tab == TAB_ME) {
                switchTab(tab);
            }
        }
    }

    /** 启动后把自己的记录推给对方（延迟执行，等对方服务起来） */
    private void scheduleSyncPush() {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                pushSync();
            }
        }, 2500);
    }

    /** 把自己的订单 + 聊天推给对方（直连 + 中转两级） */
    private void pushSync() {
        final String body = com.couple.xiaowo.core.Sync.build(history, chatLog);
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String host = peerHost;
                int port = peerPort;
                if (host != null && port > 0) {
                    ok = Protocol.isOk(Net.post(host, port, Protocol.PATH_SYNC, body, 3000));
                }
                // 直连失败再试中转（异地场景）
                if (!ok && com.couple.xiaowo.core.Relay.enabled(act)) {
                    ok = com.couple.xiaowo.core.Relay.publish(act, Protocol.ROLE_ORDER, body, 8000);
                }
            }
        }).start();
    }
    private void switchTab(int t) {
        tab = t;
        // 记住当前 tab，主题切换 recreate 后回到同一页
        sp.edit().putInt("cur_tab", t).apply();
        pageHost.removeAllViews();
        View page = pages[t];
        if (page == null || t != TAB_KITCHEN) {
            page = createPage(t);
            pages[t] = page;
        }
        pageHost.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateNav();
        if (t == TAB_KITCHEN) {
            updateCart();
        }
    }
    private TextView tinyCircle(String text, int bg, int fg, int sizeDp, float sp) {
        TextView t = Ui.tv(act, text, sp, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(bg, dp(999)));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return t;
    }
    private void updateCart() {
        int total = 0;
        int count = 0;
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            Menu.Item it = byName.get(com.couple.xiaowo.core.CartKey.nameOf(e.getKey()));
            if (it == null) {
                continue;
            }
            total += it.price * e.getValue();
            count += e.getValue();
        }
        if (totalView != null) {
            totalView.setText("合计 ❤" + total);
        }
        if (subTotalView != null) {
            subTotalView.setText(count == 0 ? "还没点菜哦" : ("共 " + count + " 份 · 等你下单"));
        }
        if (orderBtn != null) {
            boolean can = count > 0 && !sending;
            /*
             * 注意：按钮文字要区分模式。
             * 选菜进冰箱时应该是「加入冰箱」，不能被更新购物车时改回「下单」
             * （这是本轮改动里自己引入过的一个 bug，这里一并守住）。
             */
            String label;
            int normalBg;
            if (pickingForFridge) {
                label = "加入冰箱";
                normalBg = Ui.ACCENT;
            } else {
                label = sending ? "投递中…" : "下单";
                normalBg = Ui.PURPLE;
            }
            orderBtn.setBackground(Ui.round(can ? normalBg : 0xFFD9D4E6, dp(999)));
            orderBtn.setTextColor(can ? 0xFFFFFFFF : 0xFF9A95A8);
            orderBtn.setText(label);

            // 冰箱模式下副标题也换成对应说明
            if (subTotalView != null && pickingForFridge) {
                subTotalView.setText(count == 0 ? "挑一道菜囤起来" : ("已选 " + count + " 道 · 点右边加入冰箱"));
            }
        }
    }
    private void updateNav() {
        for (int i = 0; i < 5; i++) {
            boolean on = i == tab;
            if (navIcons[i] != null) {
                navIcons[i].setTextColor(on ? Ui.PURPLE : 0xFF9B96A8);
                navLabels[i].setTextColor(on ? Ui.PURPLE_DEEP : 0xFF9B96A8);
                navLabels[i].setTypeface(Typeface.create(Typeface.SANS_SERIF,
                        on ? Typeface.BOLD : Typeface.NORMAL));
            }
        }
    }


    // ================================================================ 持久化

    private void saveNotice() {
        sp.edit().putString("notice", Social.noticeToJson(notice)).apply();
    }

    private void saveChat() {
        sp.edit().putString("chat", Social.msgsToJson(chatLog)).apply();
    }

    private void saveMenuToDisk() {
        sp.edit().putString("menu", Menu.toJson())
                .putString("menu_fp", Menu.fingerprint()).apply();
    }

    private void restoreMenu() {
        String saved = sp.getString("menu", "");
        if (saved == null || saved.length() == 0) {
            return;
        }
        String fp = sp.getString("menu_fp", "");
        /*
         * 注意这里比较的方向：拿**当前（内置默认）菜单的指纹**去比**保存的指纹**。
         *
         * 内置菜谱变了（发新版加了菜/规格）时两者不等，就应用保存的菜单 + 合并。
         * 但应用之后**必须把新指纹写回**，否则下次启动仍然"不等"，
         * 于是又合并一次 —— 自定义菜会被重复叠加，越开越多。
         */
        if (!Menu.fingerprint().equals(fp)) {
            Menu.applyJson(saved, true);
            saveMenuToDisk();
        }
    }

    // ================================================================ 聊天

    private void rebuildChat() {
        if (chatBox == null) {
            return;
        }
        chatBox.removeAllViews();
        if (chatLog.isEmpty()) {
            TextView empty = Ui.tv(act, "还没有聊天记录，\n说句「想你了」试试？", 11.5f, Ui.TEXT_MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(60), 0, dp(60));
            chatBox.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        SimpleDateFormat d = new SimpleDateFormat("MM-dd", Locale.US);
        SimpleDateFormat t = new SimpleDateFormat("HH:mm", Locale.US);
        String lastDay = "";
        for (Social.Msg m : chatLog) {
            String day = d.format(new Date(m.ts));
            if (!day.equals(lastDay)) {
                lastDay = day;
                TextView dl = Ui.tv(act, day, 10.5f, Ui.TEXT_MUTED, false);
                dl.setGravity(Gravity.CENTER);
                dl.setPadding(0, dp(8), 0, dp(4));
                chatBox.addView(dl, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            chatBox.addView(buildBubble(m, t.format(new Date(m.ts))));
        }
        final ScrollView sv = chatScroll;
        if (sv != null) {
            sv.post(new Runnable() {
                public void run() {
                    sv.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private View buildBubble(Social.Msg m, String time) {
        boolean mine = m.mine;
        LinearLayout wrapRow = Ui.horizontal(act);
        wrapRow.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout column = Ui.vertical(act);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (mine) {
            colLp.gravity = Gravity.END;
        }
        TextView bubble = Ui.tv(act, m.text, 13, mine ? 0xFFFFFFFF : Ui.TEXT, false);
        bubble.setMaxWidth(dp(230));
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9));
        bubble.setBackground(mine
                ? Ui.gradient(Ui.PRIMARY, 0xFF9333EA, dp(16))
                : Ui.round(0xFFF4F1FA, dp(16)));
        column.addView(bubble);
        TextView stamp = Ui.tv(act, (mine ? "我 · " : "张老师 · ") + time, 9, Ui.TEXT_MUTED, false);
        stamp.setPadding(0, dp(3), dp(2), 0);
        if (mine) {
            stamp.setGravity(Gravity.END);
        }
        column.addView(stamp);
        wrapRow.addView(column, colLp);
        return wrapRow;
    }

    private void sendChat(final String text) {
        Social.Msg m = new Social.Msg();
        m.who = "cat";
        m.from = com.couple.xiaowo.core.Client.id(act);
        m.text = text;
        m.ts = System.currentTimeMillis();
        m.mine = true;
        chatLog.add(m);
        saveChat();
        rebuildChat();
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = tryPost(Protocol.PATH_CHAT, m.toJson());
                com.couple.xiaowo.core.Logs.i("chat", (ok ? "发出" : "发失败") + "：" + m.text);
                if (!ok) {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            toastWarn("消息没发出去：检查网络或「连接设置」里的配对");
                        }
                    });
                }
            }
        }).start();
    }

    /** 统一投递：先已保存地址，再自动发现，最后回环 */
    private boolean tryPost(String path, String body) {
        if (peerHost != null && peerPort > 0
                && Protocol.isOk(Net.post(peerHost, peerPort, path, body, 2500))) {
            return true;
        }
        for (Net.Peer p : Net.discover(1500)) {
            if (Protocol.isOk(Net.post(p.host, p.port, path, body, 2500))) {
                peerHost = p.host;
                peerPort = p.port;
                sp.edit().putString("peer_host", p.host).putInt("peer_port", p.port).apply();
                return true;
            }
        }
        // 最后一档：走中转（异地时这是唯一通路）
        if (com.couple.xiaowo.core.Relay.enabled(act)) {
            return com.couple.xiaowo.core.Relay.publish(act,
                    Protocol.ROLE_ORDER, body, 8000);
        }
        return Protocol.isOk(Net.post("127.0.0.1", Net.DEFAULT_PORT, path, body, 1200));
    }

    // ================================================================ 公告

    private String noticeLine() {
        if (notice == null || notice.text.length() == 0) {
            return "📢 公告：暂无";
        }
        return "📢 " + notice.text;
    }

    private void refreshNotice() {
        if (noticeView != null) {
            noticeView.setText(noticeLine());
            noticeView.setTextColor(notice == null ? Ui.SUB : Ui.ACCENT_DEEP);
        }
    }

    // ================================================================ 菜谱编辑

    private void showAddDishDialog(final TextView stamp) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final EditText name = new EditText(act);
        name.setHint("菜名，例如「要摸摸头」");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(name);

        final EditText price = new EditText(act);
        price.setHint("心动值，例如 5");
        price.setInputType(InputType.TYPE_CLASS_NUMBER);
        price.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(price, top(8));

        final EditText emoji = new EditText(act);
        emoji.setText("🐾");
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(emoji, top(8));

        final EditText desc = new EditText(act);
        desc.setHint("一句话说明");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(desc, top(8));

        final EditText specs = new EditText(act);
        specs.setHint("规格，用顿号或逗号分开（可留空）");
        specs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(specs, top(8));
        box.addView(Ui.tv(act,
                "例：微辣、中辣、特辣 — 点了这道菜时，对方会先选一个规格",
                9.5f, Ui.TEXT_MUTED, false), top(3));

        final int[] cat = {0};
        LinearLayout cats = Ui.horizontal(act);
        final List<ImageView> ivs = new ArrayList<ImageView>();
        for (int i = 0; i < 8; i++) {
            final int idx = i;
            ImageView iv = image(dp(40), i, 12);
            iv.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    cat[0] = idx;
                    for (int k = 0; k < ivs.size(); k++) {
                        ivs.get(k).setBackground(Ui.round(0x00000000, dp(12),
                                Math.max(1, dp(k == idx ? 2 : 0)), k == idx ? Ui.PRIMARY : 0x00000000));
                    }
                }
            });
            ivs.add(iv);
            cats.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        ScrollView cs = new ScrollView(act);
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(cats);
        box.addView(Ui.tv(act, "挑一只猫猫当封面：", 11, Ui.TEXT_SUB, false), top(12));
        box.addView(cs, top(6));

        DialogKit.custom(act, "新增一道菜", box, "加到「想你了」", new Runnable() {
            public void run() {
                String nm = name.getText().toString().trim();
                if (nm.length() == 0) {
                    toastWarn("菜名不能为空");
                    return;
                }
                int p;
                try {
                    p = Integer.parseInt(price.getText().toString().trim());
                } catch (Exception e) {
                    p = 5;
                }
                Menu.Item it = new Menu.Item(nm, p, cat[0],
                        emoji.getText().toString().trim(),
                        desc.getText().toString().trim(), 5.0f, 0, true);
                addSpecs(it, specs.getText().toString());
                Menu.CATEGORIES.get(0).items.add(it);
                byName.put(nm, it);
                saveMenuToDisk();
                if (stamp != null) {
                    stamp.setText("共 " + Menu.totalCount() + " 道");
                }
                pages[TAB_MANAGE] = null;
                switchTab(TAB_MANAGE);
                toastOk("已新增「" + nm + "」，记得推送给张老师");
            }
        });
    }

    private void showEditDishDialog(final Menu.Category cat, final Menu.Item it, final TextView stamp) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        final EditText name = new EditText(act);
        name.setText(it.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(name);
        final EditText price = new EditText(act);
        price.setText(String.valueOf(it.price));
        price.setInputType(InputType.TYPE_CLASS_NUMBER);
        price.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(price, top(8));
        final EditText desc = new EditText(act);
        desc.setText(it.desc);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(desc, top(8));

        final EditText specs = new EditText(act);
        specs.setText(joinSpecs(it));
        specs.setHint("规格，用顿号或逗号分开（可留空）");
        specs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(specs, top(8));
        box.addView(Ui.tv(act, "多个规格用顿号、逗号或空格分开", 10.5f, Ui.TEXT_MUTED, false), top(3));

        DialogKit.custom(act, "改「" + it.name + "」", box, "保存", new Runnable() {
            public void run() {
                String nm = name.getText().toString().trim();
                if (nm.length() == 0) {
                    toastWarn("菜名不能为空");
                    return;
                }
                try {
                    it.price = Integer.parseInt(price.getText().toString().trim());
                } catch (Exception ignore) {
                }
                byName.remove(it.name);
                it.name = nm;
                it.desc = desc.getText().toString().trim();
                it.specs.clear();
                addSpecs(it, specs.getText().toString());
                byName.put(nm, it);
                saveMenuToDisk();
                if (stamp != null) {
                    stamp.setText("共 " + Menu.totalCount() + " 道");
                }
                /*
                 * 厨房页也要失效重画 —— 规格改了之后，那道菜那行
                 * 的「选规格」标记要不要显示会变（从无规格变成有规格）。
                 * 只清 TAB_MANAGE 的话，厨房列表还是旧的。
                 */
                pages[TAB_KITCHEN] = null;
                pages[TAB_MANAGE] = null;
                rebuildList();
                switchTab(TAB_MANAGE);
                toastOk("已保存，记得推送给张老师");
            }
        });
    }

    /**
     * 解析用户填的规格串。
     *
     * 容忍多种分隔符（顿号/中英文逗号/空格/分号），
     * 因为没人会记得"这里要用什么符号"。
     */
    private void addSpecs(Menu.Item it, String raw) {
        if (raw == null) {
            return;
        }
        for (String part : raw.split("[\u3001,\uFF0C;\uFF1B/\\s]+")) {
            String sp = part.trim();
            if (sp.length() > 0 && sp.length() <= 12 && !it.specs.contains(sp)) {
                it.specs.add(sp);
            }
        }
    }

    /** 规格列表 → 编辑框里的文本 */
    private String joinSpecs(Menu.Item it) {
        StringBuilder sb = new StringBuilder();
        for (String sp : it.specs) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(sp);
        }
        return sb.toString();
    }

    /**
     * 应用对方推来的菜谱（直连 POST /menu 和中转 TYPE_MENU 共用）。
     *
     * 两条通路都会走到这里：同 Wi-Fi 时走 HTTP，异地时走中转。
     */
    private void applyMenuFromPeer(final String body, final String tip) {
        act.runOnUiThread(new Runnable() {
            public void run() {
                // 先记指纹：内容一样就别再提示"已更新"，同步是常驻行为
                String fpBefore = Menu.fingerprint();
                Menu.applyJson(body, true);
                saveMenuToDisk();
                byName.clear();
                for (Menu.Category c : Menu.CATEGORIES) {
                    for (Menu.Item it : c.items) {
                        byName.put(it.name, it);
                    }
                }
                pages[TAB_KITCHEN] = null;
                pages[TAB_MANAGE] = null;
                if (tab == TAB_KITCHEN) {
                    switchTab(TAB_KITCHEN);
                    buildSidebar();
                    rebuildList();
                } else if (tab == TAB_MANAGE) {
                    switchTab(TAB_MANAGE);
                }
                if (!Menu.fingerprint().equals(fpBefore)) {
                    Toast2.ok(act, tip);
                }
            }
        });
    }

    private void pushMenuToPeer(final TextView stamp) {
        final String body = Menu.toJson();
        Toast2.show(act, Toast2.LOADING, "正在把菜谱推给张老师…");
        new Thread(new Runnable() {
            public void run() {
                final boolean ok = tryPost(Protocol.PATH_MENU, body);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (ok) {
                            toastOk("菜谱已同步给张老师（" + Menu.totalCount() + " 道）");
                            host.burst(12);
                        } else {
                            toastWarn("没连上张老师，菜谱先存在本机了");
                        }
                    }
                });
            }
        }).start();
    }

    // ================================================================ 冰箱多选

    private LinearLayout buildFridgePicker(final String[] arr, final boolean[] checked) {
        LinearLayout list = Ui.vertical(act);
        final List<TextView> boxes = new ArrayList<TextView>();
        for (int i = 0; i < arr.length; i++) {
            final int idx = i;
            LinearLayout row = Ui.horizontal(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(9), dp(10), dp(9));
            TextView box = Ui.tv(act, checked[i] ? "☑" : "☐", 15,
                    checked[i] ? Ui.PRIMARY : Ui.TEXT_MUTED, false);
            row.addView(box);
            boxes.add(box);
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            nLp.weight = 1f;
            nLp.leftMargin = dp(8);
            row.addView(Ui.tv(act, arr[i], 13, Ui.TEXT, false), nLp);
            row.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    checked[idx] = !checked[idx];
                    boxes.get(idx).setText(checked[idx] ? "☑" : "☐");
                    boxes.get(idx).setTextColor(checked[idx] ? Ui.PRIMARY : Ui.TEXT_MUTED);
                }
            });
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(4);
            list.addView(row, rLp);
            list.addView(Ui.divider(act));
        }
        ScrollView sv = new ScrollView(act);
        sv.addView(list);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        LinearLayout wrapBox = Ui.vertical(act);
        wrapBox.addView(sv);
        return wrapBox;
    }

    // ================================================================ 长按喷泡泡

    private void attachLongPressBubble(final TextView btn, final String dishName) {
        btn.setOnLongClickListener(new OnLongClickListener() {
            public boolean onLongClick(View v) {
                if (bubbleLayer == null) {
                    return false;
                }
                bubbleLayer.setAnchorFraction(0.86f, 0.62f);
                bubbleLayer.startStreaming();
                Haptics.pop(act);
                addToCart(dishName, 1);
                final android.os.Handler h =
                        new android.os.Handler(android.os.Looper.getMainLooper());
                final Runnable[] task = new Runnable[1];
                task[0] = new Runnable() {
                    public void run() {
                        if (!v.isPressed() || !bubbleLayer.isStreaming()) {
                            return;
                        }
                        addToCart(dishName, 1);
                        Haptics.tick(act);
                        h.postDelayed(task[0], 160);
                    }
                };
                h.postDelayed(task[0], 160);
                v.setOnTouchListener(new View.OnTouchListener() {
                    public boolean onTouch(View view, android.view.MotionEvent e) {
                        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                                || e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
                            h.removeCallbacks(task[0]);
                            bubbleLayer.stopStreaming();
                            bubbleLayer.resetAnchor();
                            Haptics.pop(act);
                            view.setOnTouchListener(null);
                        }
                        return false;
                    }
                });
                return true;
            }
        });
    }

    // ================================================================ 长按撒爱心

    /**
     * 按住爱心：一直蹦、一直震，松手才停。
     *
     * 之前长按只是一次性 burst(80) + 一下震动，按住屏幕不会持续有反馈，
     * 手指停在上面像卡住了。现在沿用 attachLongPressBubble 的节奏：
     * 按下先给一簇大的，然后每 180ms 补一小簇 + 一次轻震，
     * ACTION_UP / ACTION_CANCEL 时把定时器摘掉。
     *
     * 用 {@code v.isPressed()} 兜底：即使没收到 UP（比如手指滑出控件），
     * 循环也会在下一次 tick 自然停下，不会漏震。
     */
    private void attachLongPressHeart(final TextView btn) {
        btn.setOnLongClickListener(new OnLongClickListener() {
            public boolean onLongClick(final View v) {
                final android.os.Handler h =
                        new android.os.Handler(android.os.Looper.getMainLooper());
                final Runnable[] task = new Runnable[1];
                // 第一下给足"按住了"的反馈
                host.burst(28);
                Haptics.pop(act);
                vibrate();
                task[0] = new Runnable() {
                    public void run() {
                        if (!v.isPressed()) {
                            return;
                        }
                        host.burst(7);
                        Haptics.tick(act);
                        h.postDelayed(task[0], 180);
                    }
                };
                h.postDelayed(task[0], 180);
                v.setOnTouchListener(new View.OnTouchListener() {
                    public boolean onTouch(View view, android.view.MotionEvent e) {
                        int a = e.getActionMasked();
                        if (a == android.view.MotionEvent.ACTION_UP
                                || a == android.view.MotionEvent.ACTION_CANCEL) {
                            h.removeCallbacks(task[0]);
                            Haptics.pop(act);
                            view.setOnTouchListener(null);
                        }
                        return false;
                    }
                });
                return true;
            }
        });
    }

    // ================================================================ 兼容旧方法名

    private void sendChatToPeer(Social.Msg m) {
        tryPost(Protocol.PATH_CHAT, m.toJson());
    }

    private String tryPullMenu() {
        return null;
    }

    // ================================================================ 截图

    /**
     * 给张老师截一张屏。返回 base64，失败返回 null。
     *
     * 走 Shot 的三级降级：无障碍静默截屏 → 自己 View 树 → 失败。
     *
     * 为什么不在主线程做：无障碍截屏要等系统回调（几十到几百毫秒），
     * 卡在 UI 线程上界面会一顿。这个方法从服务器工作线程调用正合适。
     */
    private String shotForPeer() {
        try {
            // 直连 HTTP 回传：用高清版（局域网带宽足够，打字都看得清）
            String b64 = com.couple.xiaowo.core.Shot.captureBase64(act, this);
            if (b64 != null) {
                return b64;
            }
            /*
             * 两条路都失败，通常意味着：无障碍没开，而且此刻 View 尺寸还是 0
             * （App 刚被杀后重建）。给用户一个明确的提示，别让人以为功能坏了。
             */
            act.runOnUiThread(new Runnable() {
                public void run() {
                    toastWarn("请启用必要权限以保证软件正常运行");
                }
            });
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================ 服务器

    private void startServer() {
        if (server != null) {
            return;
        }
        Net.Server s = new Net.Server(new Net.Handler() {
            public String handle(String method, String path, String body) {
                // 界面已销毁就不再处理（理由同 RecvScreen）
                if (act.isFinishing() || (Build.VERSION.SDK_INT >= 17 && act.isDestroyed())) {
                    return Protocol.ack(false, Protocol.ROLE_ORDER, "bye");
                }
                // 接单端来拉菜谱：GET /menu?pull=1
                if (Protocol.PATH_MENU.equals(path) && "GET".equalsIgnoreCase(method)) {
                    saveMenuToDisk();
                    return Menu.toJson();
                }
                if (Protocol.PATH_HEART.equals(path)) {
                    final Map<String, Object> m = Json.asObject(Json.parse(body));
                    final String text = Json.str(m, "text", "抱抱");
                    final String from = Json.str(m, "fromName", "小窝·接单");
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            host.burst(30);
                            vibrate();
                            toast(from + "：" + text + " 💗");
                        }
                    });
                    return Protocol.ack(true, Protocol.ROLE_ORDER, "收到你的爱心啦");
                }
                if (Protocol.PATH_NOTICE.equals(path)) {
                    final Social.Notice n = Social.Notice.fromJson(body);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            notice = n;
                            saveNotice();
                            refreshNotice();
                            host.burst(14);
                            AlertCenter.push(act, RemindSettings.level(act),
                                    "📢 张老师的新公告", n.text, 14);
                        }
                    });
                    return Protocol.ack(true, Protocol.ROLE_ORDER, "公告已收到");
                }
                if (Protocol.PATH_CHAT.equals(path)) {
                    final Social.Msg m = Social.Msg.fromJson(body);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            onChatIncoming(m);
                        }
                    });
                    return Protocol.ack(true, Protocol.ROLE_ORDER, "收到");
                }
                if (Protocol.PATH_BUZZ.equals(path)) {
                    // 张老师在远程按「震一下」
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            onRemoteBuzz(body);
                        }
                    });
                    return Protocol.ack(true, Protocol.ROLE_ORDER, "已震");
                }
                if (Protocol.PATH_SYNC.equals(path)) {
                    // 张老师推来的订单/聊天记录，合并去重
                    final com.couple.xiaowo.core.Sync.Parsed p =
                            com.couple.xiaowo.core.Sync.parse(body);
                    if (p != null) {
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                mergeSync(p);
                            }
                        });
                        return Protocol.ack(true, Protocol.ROLE_ORDER, "记录已同步");
                    }
                    return Protocol.ack(false, Protocol.ROLE_ORDER, "同步数据格式不对");
                }
                if (Protocol.PATH_MENU.equals(path)) {
                    // 接单端把（可能被它调整过的）菜谱推回来
                    applyMenuFromPeer(body, "菜谱已从张老师那边更新");
                    return Protocol.ack(true, Protocol.ROLE_ORDER, "菜单已更新");
                }
                if (Protocol.PATH_PING.equals(path)) {
                    return Json.object(Json.fields(
                            "ok", "true",
                            "role", Json.quote(Protocol.ROLE_ORDER),
                            "port", String.valueOf(server == null ? 0 : server.port())));
                }
                if (Protocol.PATH_SHOT.equals(path)) {
                    /*
                     * 张老师请求看点单端的屏幕。
                     *
                     * 直接用 shotNow()（无障碍静默截屏优先，没人打扰）；
                     * 拿不到时它内部已经降级到 View 树，再不行才承认失败。
                     *
                     * 注意：这个方法本身跑在服务器的**工作线程**上，
                     * 所以不需要再切主线程 —— 无障碍截屏用不到主线程，
                     * View 树兜底也只是读像素，不触碰布局。
                     */
                    String b64 = shotForPeer();
                    return b64 != null
                            ? Protocol.shotImg(b64)
                            : Protocol.ack(false, Protocol.ROLE_ORDER,
                            "截图没成功（点单端那边躲开了）");
                }
                return Protocol.ack(false, Protocol.ROLE_ORDER, "我是点单端");
            }
        });
        server = s;
        new Thread(new Runnable() {
            public void run() {
                try {
                    server.start(0);
                } catch (Exception e) {
                    server = null;
                }
            }
        }).start();
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(55, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(55);
            }
        } catch (Exception ignore) {
        }
    }

    private void toast(String s) {
        Toast2.info(act, s);
    }

    private void toastOk(String s) {
        Toast2.ok(act, s);
    }

    private void toastLove(String s) {
        Toast2.love(act, s);
    }

    private void toastWarn(String s) {
        Toast2.warn(act, s);
    }

    /** 返回键：不在厨房页就先回厨房。返回 true 表示已消费这次返回。 */
    public boolean goHomeTab() {
        if (tab != TAB_KITCHEN) {
            switchTab(TAB_KITCHEN);
            return true;
        }
        return false;
    }

    public void shutdown() {
        dead = true;
        sched.removeCallbacksAndMessages(null);
        GitHubCard.stopPeriodic();
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
