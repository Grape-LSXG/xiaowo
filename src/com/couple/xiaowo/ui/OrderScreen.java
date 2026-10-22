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

    private static final int TAB_KITCHEN = 0;
    private static final int TAB_ORDERS = 1;
    private static final int TAB_FRIDGE = 2;
    private static final int TAB_MANAGE = 3;
    private static final int TAB_ME = 4;

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
    private Net.Server server;

    private List<History.Entry> history = new ArrayList<History.Entry>();
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
        scheduleAutoPeerUpdate();
        scheduleSyncPush();
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
        if (host == null || port <= 0) {
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                Net.post(host, port, Protocol.PATH_ACK_UPDATE,
                        Protocol.updateAck(info.versionCode, info.versionName), 3000);
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
        o.fromName = Protocol.NAME_ORDER;
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

                if (peerHost != null && peerPort > 0) {
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
                 * 这时才走公共 MQTT 把订单送出去。
                 * QoS0 没有回执，所以只要 publish 成功就认为已送达。
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
                        if (fResp != null) {
                            peerHost = fUsed;
                            peerPort = fPort;
                            sp.edit().putString("peer_host", fUsed).putInt("peer_port", fPort).apply();
                            if (peerView != null) {
                                peerView.setText(peerText());
                            }
                            History.Entry e = new History.Entry();
                            e.ts = order.ts;
                            e.total = order.total;
                            e.note = order.note;
                            e.delivered = true;
                            e.lines.addAll(order.lines);
                            history.add(e);
                            saveHistory();
                            cart.clear();
                            for (String k : new ArrayList<String>(actionBoxes.keySet())) {
                                refreshActions(k);
                            }
                            updateCart();
                            host.burst(18);
                            toastLove("已经送到张老师那儿啦 ❤");
                        } else {
                            updateCart();
                            toastWarn("没连上张老师，去「我 → 连接设置」填一下地址");
                        }
                    }
                });
            }
        }).start();
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
        box.addView(Ui.tv(act, "接单端地址（IP:端口，可留空自动发现）", 11, Ui.PRIMARY_DEEP, true), top(12));

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
                room == null ? "🌐 异地配对（填一次房间码，永久有效）" : ("🌐 已配对：" + room),
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
        box.addView(Ui.tv(act, "接单端「异地配对」里复制的房间码，粘在下面：",
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
            if (Protocol.TYPE_CHAT.equals(type)) {
                onChatIncoming(Social.Msg.fromJson(payload));
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
             * 中转能拿到"有新版"，但传不了 6MB 的包。
             * 所以这里明确告诉用户下一步怎么做，而不是假装能下。
             */
            DialogKit.confirm(act, "有新版本",
                    "张老师那边是 v" + info.versionName + "，你是 " + Ui.appVersion(act) + "。\n\n"
                            + "安装包有 6MB，中转通道传不动。\n"
                            + "请在同一个 Wi-Fi 下打开这个页面点「检查并更新」，\n"
                            + "或者让张老师直接把 APK 发给你。",
                    "知道了", null);
        } else {
            toast("已经是最新版了（" + Ui.appVersion(act) + "）");
        }
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

        TextView rand = Ui.pill(act, "随机选菜", 0xFFFFFFFF, Ui.TEXT, 11, 11, 8, false);
        rand.setBackground(Ui.round(0xFFFFFFFF, dp(999), Math.max(1, dp(0.8f)), 0xFFE7E1F2));
        rand.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                randomPick();
            }
        });
        bar.addView(rand);

        orderBtn = Ui.pill(act, "下单", 0xFFD9D4E6, 0xFFFFFFFF, 13, 20, 9, true);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        oLp.leftMargin = dp(8);
        bar.addView(orderBtn, oLp);
        orderBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                placeOrder(NOTES[random.nextInt(NOTES.length)]);
            }
        });
        orderBtn.setOnLongClickListener(new OnLongClickListener() {
            public boolean onLongClick(View v) {
                showNoteDialog();
                return true;
            }
        });
        return bar;
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
                showFridgePicker();
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
        TextView addDish = Ui.pill(act, "＋ 添加菜谱", 0xFFFFFFFF, Ui.TEXT, 11, 12, 7, false);
        addDish.setBackground(Ui.round(0xFFFFFFFF, dp(999), Math.max(1, dp(0.8f)), 0xFFE7E1F2));
        addDish.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                toast("示例版：菜谱写在 core/Menu.java 里");
            }
        });
        btns.addView(addDish);

        TextView search = Ui.pill(act, "🔍 搜索", 0xFFFFFFFF, Ui.TEXT, 11, 12, 7, false);
        search.setBackground(Ui.round(0xFFFFFFFF, dp(999), Math.max(1, dp(0.8f)), 0xFFE7E1F2));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.leftMargin = dp(8);
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
        content.addView(Ui.tv(act, "想改菜名/价格：编辑 core/Menu.java 后重新编译", 10.5f, Ui.SUB, false), top(4));
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
        input.setPadding(dp(14), dp(9), dp(14), dp(9));
        input.setSingleLine(true);
        sendRow.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
        nav.setPadding(0, dp(7), 0, dp(7));
        for (int i = 0; i < 5; i++) {
            final int index = i;
            LinearLayout tabView = Ui.vertical(act);
            tabView.setGravity(Gravity.CENTER);
            TextView icon = Ui.tv(act, TAB_ICON[i], 17, 0xFF9B96A8, false);
            icon.setGravity(Gravity.CENTER);
            tabView.addView(icon);
            TextView label = Ui.tv(act, TAB_NAME[i], 9.5f, 0xFF9B96A8, false);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lLp.topMargin = dp(2);
            tabView.addView(label, lLp);
            navIcons[i] = icon;
            navLabels[i] = label;
            tabView.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    switchTab(index);
                }
            });
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
            chatLog.addAll(Social.parseMsgs(sp.getString("chat", "[]"), false));
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
        TextView t = Ui.tv(act, text, text.length() > 1 ? 11.5f : 16f, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(bg, dp(10)));
        t.setMinWidth(widthPx);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30));
        t.setLayoutParams(lp);
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
            TextView row = Ui.pill(act, label, 0xFFF7F5FA, Ui.TEXT, 12.5f, 14, 10, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    addToCartSpec(it.name, sp, -1);
                    Dialog d = dlgRef[0];
                    if (d != null) {
                        d.dismiss();
                    }
                }
            });
            box.addView(row, top(6));
        }
        dlgRef[0] = DialogKit.custom(act, "减掉一份", box, "取消", null);
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
            TextView row = Ui.pill(act, sp, 0xFFF7F5FA, Ui.TEXT, 12.5f, 14, 10, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rLp = top(6);
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
            box.addView(row, rLp);
        }
        dlgRef[0] = DialogKit.custom(act, "选规格", box, "取消", null);
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
                    0xFFF7F5FA, Ui.TEXT, 12.5f, 14, 10, false);
            row.setGravity(Gravity.CENTER_VERTICAL);
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
            box.addView(row, top(6));
        }
        dlgRef[0] = DialogKit.custom(act, "存进冰箱", box, "取消", null);
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
    /**
     * 「再囤几道」—— 列菜让用户勾选囤进冰箱。
     *
     * ── 之前为什么是空列表 ──
     *
     * 上一版这里只列 `!it.hasSpecs()` 的菜，理由是"有规格的菜要先选规格，
     * 多选列表表达不了"。但后来给 22 道预制菜**全部配上了规格**，
     * 于是筛选结果恒为空 —— 用户点开只看到一片空白。
     *
     * ── 现在怎么做 ──
     *
     * 不再筛掉带规格的菜，而是让每一项显示成「菜名」或「菜名 · 规格」，
     * 规格展开成多行。这样列表里永远有东西，且勾选的含义明确。
     * 默认勾选冰箱里已有的组合，取消勾选即从冰箱移除。
     */
    private void showFridgePicker() {
        // 1) 展开成「可囤的组合」：没规格的只有一行，有规格的每个规格一行
        final List<String> keys = new ArrayList<String>();
        final List<String> labels = new ArrayList<String>();
        for (Menu.Category c : Menu.CATEGORIES) {
            for (Menu.Item it : c.items) {
                if (!it.hasSpecs()) {
                    keys.add(it.name);
                    labels.add(it.name);
                } else {
                    for (String sp : it.specs) {
                        keys.add(com.couple.xiaowo.core.CartKey.of(it.name, sp));
                        labels.add(it.name + " · " + sp);
                    }
                }
            }
        }
        final String[] arr = labels.toArray(new String[0]);
        final boolean[] checked = new boolean[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            checked[i] = fridge.contains(keys.get(i));
        }

        new AlertDialog.Builder(act)
                .setTitle("选几道菜囤进冰箱")
                .setMultiChoiceItems(arr, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface dlg, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dlg, int which) {
                        for (int i = 0; i < keys.size(); i++) {
                            String key = keys.get(i);
                            boolean in = fridge.contains(key);
                            if (checked[i] && !in) {
                                fridge.add(key);
                            } else if (!checked[i] && in) {
                                fridge.remove(key);
                            }
                        }
                        saveFridge();
                        switchTab(TAB_FRIDGE);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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
            orderBtn.setBackground(Ui.round(can ? Ui.PURPLE : 0xFFD9D4E6, dp(999)));
            orderBtn.setTextColor(can ? 0xFFFFFFFF : 0xFF9A95A8);
            orderBtn.setText(sending ? "投递中…" : "下单");
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
                TextView dl = Ui.tv(act, day, 9.5f, Ui.TEXT_MUTED, false);
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
        m.text = text;
        m.ts = System.currentTimeMillis();
        m.mine = true;
        chatLog.add(m);
        saveChat();
        rebuildChat();
        new Thread(new Runnable() {
            public void run() {
                tryPost(Protocol.PATH_CHAT, m.toJson());
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
        box.addView(Ui.tv(act, "多个规格用顿号、逗号或空格分开", 9.5f, Ui.TEXT_MUTED, false), top(3));

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
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            Menu.applyJson(body, true);
                            saveMenuToDisk();
                            byName.clear();
                            for (Menu.Category c : Menu.CATEGORIES) {
                                for (Menu.Item it : c.items) {
                                    byName.put(it.name, it);
                                }
                            }
                            pages[TAB_KITCHEN] = null;
                            if (tab == TAB_KITCHEN) {
                                switchTab(TAB_KITCHEN);
                                buildSidebar();
                                rebuildList();
                            }
                            Toast2.ok(act, "菜谱已从张老师那边更新");
                        }
                    });
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
        GitHubCard.stopPeriodic();
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
