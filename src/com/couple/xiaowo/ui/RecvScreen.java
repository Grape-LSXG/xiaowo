package com.couple.xiaowo.ui;

import android.app.Activity;
import android.content.ClipData;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.couple.xiaowo.core.ApkRelay;
import com.couple.xiaowo.core.History;
import com.couple.xiaowo.core.Json;
import com.couple.xiaowo.core.Menu;
import com.couple.xiaowo.core.Mqtt;
import com.couple.xiaowo.core.Relay;
import com.couple.xiaowo.core.Social;
import com.couple.xiaowo.core.Net;
import com.couple.xiaowo.core.Protocol;
import com.couple.xiaowo.core.Remind;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 【接单端】五个 Tab：
 *   厨房 接单主页（状态 + 最近一单）
 *   订单 收到的全部订单
 *   冰箱 TA 常点的菜
 *   管理 端口 / 异地通道 / 服务开关
 *   我   关于
 */
public class RecvScreen extends LinearLayout {

    /** 周期性任务统一 Handler；shutdown() 里一次性掐掉（原地换主题会重建屏幕） */
    private final android.os.Handler sched =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean dead = false;

    /** 定时拉菜谱的周期（2 分钟） */
    private static final long AUTO_REFRESH_MENU_MS = 2 * 60 * 1000L;

    private static final int TAB_KITCHEN = 0;
    private static final int TAB_ORDERS = 1;
    private static final int TAB_FRIDGE = 2;
    private static final int TAB_MANAGE = 3;
    private static final int TAB_ME = 4;

    /** 在线点单端的心跳有效期（心跳 25 秒一次，留足三个周期的余量） */
    private static final long CLIENT_TTL_MS = 90 * 1000L;
    /** 扫一遍在线点单端的周期 */
    private static final long CLIENT_TICK_MS = 15 * 1000L;

    /** 一台在线点单端 */
    private static final class PeerClient {
        String id = "";
        String name = "";
        String host = "";
        int port = 0;
        long seen = 0L;
    }

    private static final String[] TAB_ICON = {"🏠", "🧾", "🧊", "📖", "😊"};
    private static final String[] TAB_NAME = {"厨房", "订单", "冰箱", "管理", "我"};

    private final Activity act;
    private final HeartHost host;
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);

    private Net.Server server;
    private Net.DiscoveryResponder responder;
    private volatile int listenPort;
    private int wantedPort = Net.DEFAULT_PORT;

    private TextView statusDot;
    private TextView statusText;
    private TextView addrText;
    private TextView toggleBtn;
    private TextView connectHint;
    private TextView tsText;
    private TextView relayText;
    /** 首页卡片里「现在几个点单端在线」那一行 */
    private TextView clientsText;
    /**
     * 在线点单端：设备编号 → 记录。
     *
     * 以前接单端只有"最近一单"的概念，同时登两台点单端时
     * 完全看不出谁在线、几个在线（用户就提了这个）。
     */
    private final java.util.LinkedHashMap<String, PeerClient> orderClients =
            new java.util.LinkedHashMap<String, PeerClient>();
    /** 配对状态变了要让状态卡重建一次 */
    private Runnable statusCardDirty;

    private FrameLayout pageHost;
    private final TextView[] navIcons = new TextView[5];
    private final TextView[] navLabels = new TextView[5];
    private final View[] pages = new View[5];
    private int tab = TAB_KITCHEN;

    private final List<Protocol.Order> orders = new ArrayList<Protocol.Order>();
    private final SharedPreferences sp;
    private final List<Social.Msg> chatLog = new ArrayList<Social.Msg>();
    private Social.Notice notice;
    private LinearLayout chatBox;
    private TextView noticePreview;
    private TextView menuStamp;
    private TextView ackView;
    private String lastAckText = "";
    private long lastAckTime = 0L;

    public RecvScreen(Activity act, HeartHost host) {
        super(act);
        this.act = act;
        this.host = host;
        this.sp = act.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
        String savedPeer = sp.getString("peer_host", null);
        com.couple.xiaowo.core.Net.setRelaxTls(
                savedPeer != null && savedPeer.toLowerCase().startsWith("https://"));
        this.notice = Social.parseNotice(sp.getString("notice", ""));
        // 接单端是「张老师」那一端：who=="cat" 是对方发的
        chatLog.addAll(Social.parseMsgs(sp.getString("chat", "[]"), false));
        orders.addAll(loadOrders());
        // 恢复本地保存的菜谱（否则每次启动都退回内置默认菜单）
        restoreMenu();
        setOrientation(VERTICAL);
        setBackgroundColor(Theme.adapt(Ui.BG));

        pageHost = new FrameLayout(act);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        hostLp.weight = 1f;
        addView(pageHost, hostLp);
        addView(buildNav(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        switchTab(sp.getInt("cur_tab", TAB_KITCHEN));
        startListening();
        scheduleClientTick();
        // 定时自动拉最新菜谱（同 Wi-Fi 直连拉；异地靠点单端广播，见 onRelayMessage）
        scheduleMenuAutoRefresh();

        // 启动时不再自动检查更新（改为手动，见「我」页的「检查更新」）

        // 启动后同步订单/聊天记录给对方（延迟，等双方服务起来）
        scheduleSyncPush();

        // 使用中周期性检查 GitHub 更新（静默，偶尔试一次）
        // 周期自动检查已关闭：更新只在用户点「检查更新」时进行
    }

    /**
     * 把当前菜谱写回本地。
     *
     * 接单端以前**完全不存菜谱** —— 猫猫推过来的菜谱只在内存里，
     * App 一重启就退回内置默认菜单，用户会以为"菜谱没同步成功"。
     * 现在和点单端用同一套键（menu / menu_fp），两边各自持有一份。
     */
    private void saveMenu() {
        try {
            sp.edit().putString("menu", Menu.toJson())
                    .putString("menu_fp", Menu.fingerprint()).apply();
        } catch (Exception ignore) {
        }
    }

    /**
     * 启动时恢复本地菜谱。
     *
     * 判定方式和点单端一致：内置菜单指纹变了（发新版）才重新合并。
     */
    private void restoreMenu() {
        try {
            String saved = sp.getString("menu", "");
            if (saved == null || saved.length() == 0) {
                return;
            }
            if (!Menu.fingerprint().equals(sp.getString("menu_fp", ""))) {
                Menu.applyJson(saved, true);
            }
        } catch (Exception ignore) {
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }

    // ================================================================ Tab

    private void switchTab(int t) {
        tab = t;
        sp.edit().putInt("cur_tab", t).apply();
        pageHost.removeAllViews();
        View page = pages[t];
        if (page == null || t != TAB_KITCHEN) {
            page = createPage(t);
            pages[t] = page;
        }
        pageHost.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ui.fadeUp(page);
        updateNav();
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

    private void updateNav() {
        for (int i = 0; i < 5; i++) {
            boolean on = i == tab;
            if (navIcons[i] != null) {
                navIcons[i].setTextColor(on ? Ui.PRIMARY : 0xFF9B96A8);
                navIcons[i].setBackground(on
                        ? Ui.round(Ui.PRIMARY_SOFT, dp(999))
                        : Ui.round(0x00000000, dp(999)));
                navIcons[i].setPadding(dp(10), dp(1), dp(10), dp(1));
                navLabels[i].setTextColor(on ? Ui.PRIMARY_DEEP : 0xFF9B96A8);
                navLabels[i].setTypeface(Typeface.create(Typeface.SANS_SERIF,
                        on ? Typeface.BOLD : Typeface.NORMAL));
            }
        }
    }

    private View buildNav() {
        LinearLayout nav = Ui.horizontal(act);
        nav.setBackgroundColor(Theme.adapt(0xFFFFFFFF));
        nav.setPadding(0, dp(6), 0, dp(6));
        for (int i = 0; i < 5; i++) {
            final int index = i;
            LinearLayout tabView = Ui.vertical(act);
            tabView.setGravity(Gravity.CENTER);
            tabView.setPadding(dp(4), dp(6), dp(4), dp(6));

            TextView icon = Ui.tv(act, TAB_ICON[i], 18, 0xFF9B96A8, false);
            icon.setGravity(Gravity.CENTER);
            tabView.addView(icon);
            TextView label = Ui.tv(act, TAB_NAME[i], 10, 0xFF9B96A8, false);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lLp.topMargin = dp(3);
            tabView.addView(label, lLp);

            navIcons[i] = icon;
            navLabels[i] = label;
            Ui.a11y(tabView, TAB_NAME[i] + "页面");
            Ui.pressable(tabView);
            tabView.setMinimumHeight(dp(48));
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

    // ================================================================ 厨房（接单主页）

    private View buildKitchenPage() {
        LinearLayout page = Ui.vertical(act);
        page.setBackgroundColor(Theme.adapt(Ui.BG));
        page.addView(buildHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(buildStatusCard());

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.vertical(act);
        content.setPadding(dp(14), dp(4), dp(14), dp(16));

        if (orders.isEmpty()) {
            content.addView(emptyCat(5, "还没有人向你点单", "把「点单端」装到 TA 手机上，\n在同一个 Wi-Fi 下点一单试试"));
        } else {
            Protocol.Order latest = orders.get(0);
            content.addView(Ui.tv(act, "最近一单", 11, Ui.SUB, true), top(6));
            content.addView(buildOrderCard(latest), top(8));
            if (orders.size() > 1) {
                TextView more = Ui.pill(act, "查看全部 " + orders.size() + " 单", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 11, 14, 7, false);
                more.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        switchTab(TAB_ORDERS);
                    }
                });
                LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mLp.topMargin = dp(10);
                content.addView(more, mLp);
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

    private View buildHeader() {
        FrameLayout header = new FrameLayout(act);
        header.setBackground(Ui.gradient(0xFFFFF0F7, 0xFFF5F0FE, dp(26)));
        header.setPadding(dp(18), dp(16), dp(14), dp(18));

        LinearLayout col = Ui.vertical(act);
        col.setPadding(0, 0, dp(86), 0);
        col.addView(Ui.tv(act, "张老师 · 接单", 24, Ui.TEXT, true));
        TextView sub = Ui.tv(act, "猫猫点的每一份心意，都会准时送到这里", 11.5f, Ui.SUB, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(5);
        col.addView(sub, subLp);
        header.addView(col);

        ImageView art = new ImageView(act);
        Bitmap bm = CatImage.bitmap(act, 1);
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

    private View buildStatusCard() {
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(Ui.R_CARD)));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setElevation(dp(3));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.setMargins(dp(14), dp(12), dp(14), dp(6));
        card.setLayoutParams(cLp);

        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = Ui.tv(act, "●", 12, 0xFF3ECF8E, false);
        row.addView(statusDot);
        statusText = Ui.tv(act, " 接单中", 13, Ui.TEXT, true);
        row.addView(statusText);
        row.addView(Ui.hspacer(act, 8));

        addrText = Ui.tv(act, "0.0.0.0:8765", 11, Ui.SUB, false);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.weight = 1f;
        row.addView(addrText, aLp);

        toggleBtn = Ui.pill(act, "停止", 0xFFF2EEFB, Ui.PRIMARY_DEEP, 11, 12, 6, true);
        toggleBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                if (server != null && server.isRunning()) {
                    stopListening();
                } else {
                    startListening();
                }
            }
        });
        row.addView(toggleBtn);
        card.addView(row);

        // ---- Tailscale 状态：异地恋真正靠得住的通路 ----
        tsText = Ui.tv(act, "🛰 Tailscale：检测中…", 10.5f, Ui.SUB, false);
        tsText.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                String ip = Net.tailscaleIpv4();
                if (ip == null) {
                    toast("Tailscale 没连上，去 Tailscale App 里点一下开关");
                    return;
                }
                copyText(ip + ":" + listenPort);
                toast("已复制 " + ip + ":" + listenPort + "，发给猫猫就好");
            }
        });
        card.addView(tsText, top(8));

        // ---- 异地中转（MQTT）状态 ----
        relayText = Ui.tv(act, "🌐 异地中转：未配对", 10.5f, Ui.TEXT_MUTED, false);
        relayText.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                ConnectPage.show(act, true, new Runnable() {
                    public void run() {
                        switchTab(tab);
                    }
                });
            }
        });
        card.addView(relayText, top(6));
        // 交给 MainActivity 跟着真实订阅状态刷新（原来这里建完就再也没人改它，
        // 所以配对了也永远显示「未配对」——用户报的就是这个）
        if (act instanceof MainActivity) {
            ((MainActivity) act).bindRelayStatus(relayText, "🌐 异地中转：");
        }

        // ---- 现在有几个点单端连着（多端点单/自己调试时看这里）----
        clientsText = Ui.tv(act, "📱 点单端：0 个在线", 10.5f, Ui.TEXT_MUTED, false);
        card.addView(clientsText, top(4));

        TextView hint = Ui.tv(act, "同一个 Wi-Fi：猫猫留空就能自动找到你\n"
                        + "异地：让猫猫连同一个 Tailscale 账号，填上面的地址",
                10.5f, Ui.SUB, false);
        card.addView(hint, top(6));

        // ---- 连接方式：三种通道统一入口 ----
        TextView connectBtn = Ui.pill(act, "🔗 连接方式", Ui.PRIMARY, 0xFFFFFFFF,
                12, 18, 9, true);
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = dp(10);
        connectBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                ConnectPage.show(act, true, new Runnable() {
                    public void run() {
                        switchTab(tab);
                    }
                });
            }
        });
        card.addView(connectBtn, cbLp);

        connectHint = Ui.tv(act, "同 Wi-Fi 自动、异地用中转 —— 都在这里配", 10,
                Ui.TEXT_MUTED, false);
        card.addView(connectHint, top(6));

        // ---- 公告 ----
        card.addView(Ui.dividerTop(act, 12));

        LinearLayout noticeRow = Ui.horizontal(act);
        noticeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams nrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nrLp.topMargin = dp(10);
        noticeRow.setLayoutParams(nrLp);
        noticeRow.addView(Ui.tv(act, "📢 公告", 12.5f, Ui.TEXT, true));

        noticePreview = Ui.tv(act, notice == null ? "还没有公告" : notice.text,
                10.5f, Ui.TEXT_MUTED, false);
        noticePreview.setMaxLines(1);
        LinearLayout.LayoutParams npLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        npLp.weight = 1f;
        npLp.leftMargin = dp(8);
        noticeRow.addView(noticePreview, npLp);

        TextView noticeBtn = Ui.pill(act, "发布", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 11, 12, 6, true);
        noticeBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                showNoticeDialog();
            }
        });
        noticeRow.addView(noticeBtn);
        card.addView(noticeRow);

        addrText.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                copyAddr();
            }
        });

        updateStatus(server != null && server.isRunning(), listenPort);
        return card;
    }

    private void copyAddr() {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("小窝地址", addrText.getText().toString()));
            toast("地址已复制，发给 TA 就好");
        } catch (Exception ignore) {
        }
    }

    // ================================================================ 订单页

    private View buildOrdersPage() {
        LinearLayout page = Ui.vertical(act);
        page.setBackgroundColor(Theme.adapt(Ui.BG));
        page.addView(buildSubHeader("订单", orders.isEmpty() ? "还没有收到订单" : ("一共收到 " + orders.size() + " 单")), wrap());

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.vertical(act);
        content.setPadding(dp(14), dp(8), dp(14), dp(16));
        if (orders.isEmpty()) {
            content.addView(emptyCat(5, "还没有人向你点单", "把「点单端」装到 TA 手机上试试"));
        } else {
            for (Protocol.Order o : orders) {
                content.addView(buildOrderCard(o), rowLp());
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

    private View buildOrderCard(final Protocol.Order o) {
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(18), Math.max(1, dp(0.8f)), 0xFFEDE9F4));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setElevation(dp(1.5f));

        LinearLayout top = Ui.horizontal(act);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.tv(act, "💗 来自 " + o.fromName, 12, Ui.PRIMARY_DEEP, true));
        TextView time = Ui.tv(act, fmt.format(new Date(o.ts)), 10, Ui.SUB, false);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.weight = 1f;
        tLp.leftMargin = dp(8);
        time.setGravity(Gravity.END);
        top.addView(time, tLp);
        card.addView(top);

        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        lLp.topMargin = dp(9);
        lLp.bottomMargin = dp(9);
        card.addView(Ui.divider(act), lLp);

        for (Protocol.Line l : o.lines) {
            LinearLayout row = Ui.horizontal(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            // 用 displayName()：带规格的菜显示成「要亲亲·脸颊」，
            // 不然接单端只看到"要亲亲"根本不知道对方要的是哪个
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
        bottom.addView(Ui.tv(act, "合计 ❤" + o.total, 12, Ui.TEXT, true));
        if (o.note != null && o.note.length() > 0) {
            TextView note = Ui.tv(act, "  ·  " + o.note, 11, Ui.PRIMARY, false);
            note.setMaxLines(1);
            bottom.addView(note, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else {
            bottom.addView(new View(act), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        TextView reply = Ui.pill(act, "回个爱心", 0xFFF2EEFB, Ui.PRIMARY_DEEP, 11, 12, 6, true);
        reply.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                replyHeart(o);
            }
        });
        bottom.addView(reply);
        card.addView(bottom, bLp);
        return card;
    }

    // ================================================================ 冰箱（TA 常点）

    private View buildFridgePage() {
        LinearLayout page = Ui.vertical(act);
        page.setBackgroundColor(Theme.adapt(Ui.BG));
        page.addView(buildSubHeader("冰箱", "TA 最爱点什么，这里一目了然"), wrap());

        final Map<String, Integer> counts = new java.util.LinkedHashMap<String, Integer>();
        final Map<String, Integer> prices = new java.util.LinkedHashMap<String, Integer>();
        final Map<String, String> emojis = new java.util.LinkedHashMap<String, String>();
        for (Protocol.Order o : orders) {
            for (Protocol.Line l : o.lines) {
                String disp = l.displayName();
                Integer c = counts.get(disp);
                counts.put(disp, c == null ? l.qty : c + l.qty);
                prices.put(disp, l.price);
                if (l.emoji != null && l.emoji.length() > 0) {
                    emojis.put(disp, l.emoji);
                }
            }
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<Map.Entry<String, Integer>>(counts.entrySet());
        java.util.Collections.sort(list, new java.util.Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.vertical(act);
        content.setPadding(dp(14), dp(8), dp(14), dp(16));

        if (list.isEmpty()) {
            content.addView(emptyCat(4, "冰箱还是空的", "TA 下过的单会自动在这里统计"));
        } else {
            content.addView(Ui.tv(act, "累计被点了 " + totalItems() + " 份", 11, Ui.SUB, true), top(2));
            int rank = 1;
            for (Map.Entry<String, Integer> e : list) {
                content.addView(buildRankRow(rank++, e.getKey(), e.getValue(),
                        prices.get(e.getKey()) == null ? 0 : prices.get(e.getKey()),
                        emojis.get(e.getKey())), rowLp());
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

    private int totalItems() {
        int n = 0;
        for (Protocol.Order o : orders) {
            for (Protocol.Line l : o.lines) {
                n += l.qty;
            }
        }
        return n;
    }

    private View buildRankRow(int rank, String name, int qty, int price, String emoji) {
        LinearLayout card = Ui.horizontal(act);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.round(Ui.CARD, dp(16), Math.max(1, dp(0.8f)), 0xFFEDE9F4));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView r = Ui.tv(act, rank <= 3 ? "🏅" : String.valueOf(rank), rank <= 3 ? 16 : 12,
                rank <= 3 ? Ui.TEXT : Ui.SUB, true);
        r.setGravity(Gravity.CENTER);
        card.addView(r, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout texts = Ui.vertical(act);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.weight = 1f;
        tLp.leftMargin = dp(8);
        texts.setLayoutParams(tLp);
        texts.addView(Ui.tv(act, (emoji == null ? "🐾" : emoji) + " " + name, 14, Ui.TEXT, true));
        texts.addView(Ui.tv(act, "单价 ❤" + price, 10.5f, Ui.SUB, false));
        card.addView(texts);

        card.addView(Ui.chip(act, "被点 " + qty + " 次", 0xFFFFF0F4, 0xFFD2457A, 10));
        return card;
    }

    // ================================================================ 管理 / 我

    private View buildManagePage() {
        LinearLayout page = Ui.vertical(act);
        page.setBackgroundColor(Theme.adapt(Ui.BG));
        page.addView(buildSubHeader("管理", "服务状态与端口"), wrap());

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = Ui.vertical(act);
        content.setPadding(dp(14), dp(8), dp(14), dp(16));

        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, dp(18), Math.max(1, dp(0.8f)), 0xFFEDE9F4));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(row("接单端口", (listenPort == 0 ? "未启动" : String.valueOf(listenPort))));
        card.addView(row("本机地址", Net.localIpv4()));
        card.addView(row("局域网发现", listenPort == 0 ? "未启动" : "UDP 8766 监听中"));
        // 原来这里是「穿透进程」（frpc）。frpc 已整体移除，
        // 换成对用户真正有意义的「交换状态」：中转有没有在线。
        String relayRoom = com.couple.xiaowo.core.Relay.room(act);
        String relayText;
        if (relayRoom == null) {
            relayText = "未配对";
        } else if (com.couple.xiaowo.core.Relay.enabled(act)) {
            relayText = "已配对 · 房间 " + relayRoom;
        } else {
            relayText = "已关闭";
        }
        card.addView(row("异地通道", relayText));
        card.addView(row("已收订单", orders.size() + " 单"));
        content.addView(card);

        // ---- 菜谱同步 ----
        LinearLayout menuCard = Ui.vertical(act);
        menuCard.setBackground(Ui.round(Ui.CARD, dp(Ui.R_CARD)));
        menuCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        menuCard.setElevation(dp(2));
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mcLp.topMargin = dp(12);
        menuCard.setLayoutParams(mcLp);

        LinearLayout mh = Ui.horizontal(act);
        mh.setGravity(Gravity.CENTER_VERTICAL);
        mh.addView(Ui.tv(act, "🍽 猫猫的菜谱", 14, Ui.TEXT, true));
        mh.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        menuStamp = Ui.tv(act, Menu.totalCount() + " 道", 10.5f, Ui.TEXT_MUTED, false);
        mh.addView(menuStamp);
        menuCard.addView(mh);
        menuCard.addView(Ui.tv(act, "猫猫改了菜谱之后，这边点「刷新」就能看到最新的。",
                10.5f, Ui.TEXT_SUB, false), top(8));

        TextView refreshMenu = Ui.pill(act, "🔄 刷新菜谱", Ui.PRIMARY, 0xFFFFFFFF, 12.5f, 20, 10, true);
        refreshMenu.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                pullMenu();
            }
        });
        LinearLayout.LayoutParams rmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rmLp.topMargin = dp(12);
        menuCard.addView(refreshMenu, rmLp);

        // 两端都能改菜谱：接单端也能改名/改价/加规格/删除
        TextView editMenu = Ui.pill(act, "✏️ 编辑菜谱", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                12.5f, 20, 10, true);
        editMenu.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                MenuEditor.open(act, new MenuEditor.OnChanged() {
                    public void onChanged() {
                        saveMenu();
                        if (menuStamp != null) {
                            menuStamp.setText(Menu.totalCount() + " 道");
                        }
                        pages[TAB_KITCHEN] = null;
                        pushMenuToCat();
                    }
                });
            }
        });
        LinearLayout.LayoutParams emLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emLp.topMargin = dp(8);
        menuCard.addView(editMenu, emLp);

        TextView show = Ui.pill(act, "查看完整菜单", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 12, 18, 9, true);
        show.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                showMenuDialog();
            }
        });
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shLp.topMargin = dp(8);
        menuCard.addView(show, shLp);

        content.addView(menuCard);

        TextView stop = Ui.pill(act, "停止接单", 0xFFFDECEC, 0xFFD2453F, 12, 20, 9, true);
        stop.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                stopListening();
                switchTab(TAB_MANAGE);
                toast("已停止接单");
            }
        });
        content.addView(stop, top(12));

        TextView start = Ui.pill(act, "开始接单", Ui.PRIMARY, 0xFFFFFFFF, 12, 20, 9, true);
        start.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startListening();
                switchTab(TAB_MANAGE);
                toast("已开始接单");
            }
        });
        content.addView(start, top(8));

        content.addView(Ui.tv(act, "提示：换端口后，正在使用这个端口的所有连接都要重新配一次", 10, Ui.SUB, false), top(14));

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
        card.setElevation(dp(2));

        LinearLayout who = Ui.horizontal(act);
        who.setGravity(Gravity.CENTER_VERTICAL);
        ImageView av = image(dp(58), 1, 18);
        who.addView(av, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout names = Ui.vertical(act);
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nLp.weight = 1f;
        nLp.leftMargin = dp(12);
        names.setLayoutParams(nLp);
        names.addView(Ui.tv(act, "张老师", 16, Ui.TEXT, true));
        names.addView(Ui.tv(act, "接单的这一半 · 收下 " + orders.size() + " 份心意", 11, Ui.TEXT_SUB, false));
        who.addView(names);
        card.addView(who);

        card.addView(Ui.dividerTop(act, 14));
        card.addView(rowAccent("对方", "猫猫"));
        card.addView(row("累计份数", totalItems() + " 份"));
        card.addView(row("常点的菜", topDish()));
        content.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 远程叫猫猫 ----------
        LinearLayout buzzCard = Ui.vertical(act);
        buzzCard.setBackground(Ui.round(Ui.CARD, dp(Ui.R_CARD)));
        buzzCard.setPadding(dp(14), dp(14), dp(14), dp(12));
        buzzCard.setElevation(dp(2f));
        LinearLayout.LayoutParams bzLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bzLp.topMargin = dp(12);
        buzzCard.setLayoutParams(bzLp);

        buzzCard.addView(Ui.tv(act, "📳 叫一下猫猫", 14, Ui.TEXT, true));
        buzzCard.addView(Ui.tv(act, "让她的手机立刻震起来，比发消息更直接。",
                10.5f, Ui.TEXT_SUB, false), top(6));

        LinearLayout buzzRow = Ui.horizontal(act);
        buzzRow.setGravity(Gravity.CENTER_VERTICAL);
        buzzRow.setPadding(0, dp(10), 0, 0);
        for (int i = 0; i < 3; i++) {
            final int level = i;
            String[] labels = {"轻震", "中震", "狠狠震"};
            int[] bg = {Ui.PRIMARY_SOFT, Ui.PRIMARY_SOFT, 0xFFFDECEC};
            int[] fg = {Ui.PRIMARY_DEEP, Ui.PRIMARY_DEEP, Ui.DANGER};
            TextView b = Ui.pill(act, labels[i], bg[i], fg[i], 12, 14, 8, true);
            b.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    buzzCat(level);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.weight = 1f;
            if (i > 0) {
                lp.leftMargin = dp(8);
            }
            buzzRow.addView(b, lp);
        }
        buzzCard.addView(buzzRow);

        // ---- 看猫猫屏幕（截图）----
        LinearLayout shotRow = Ui.horizontal(act);
        shotRow.setGravity(Gravity.CENTER_VERTICAL);
        shotRow.setPadding(0, dp(8), 0, 0);
        TextView shotBtn = Ui.pill(act, "📸 看猫猫的屏幕", Ui.PRIMARY_SOFT,
                Ui.PRIMARY_DEEP, 12, 14, 8, true);
        shotBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                requestShot();
            }
        });
        shotRow.addView(shotBtn);
        TextView shotHint = Ui.tv(act, "她那边不需要任何操作", 10, Ui.TEXT_MUTED, false);
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shLp.leftMargin = dp(10);
        shotRow.addView(shotHint, shLp);
        buzzCard.addView(shotRow);

        ackView = Ui.tv(act, lastAckText.length() == 0 ? "还没收到猫猫的更新回执" : ("✅ " + lastAckText),
                10, lastAckText.length() == 0 ? Ui.TEXT_MUTED : Ui.SUCCESS, false);
        LinearLayout.LayoutParams ackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ackLp.topMargin = dp(10);
        buzzCard.addView(ackView, ackLp);

        content.addView(buzzCard);

        // ---------- 聊天 ----------
        LinearLayout chatCard = Ui.vertical(act);
        chatCard.setBackground(Ui.round(Ui.CARD, dp(Ui.R_CARD)));
        chatCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        chatCard.setElevation(dp(2));
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp.topMargin = dp(12);
        chatCard.setLayoutParams(ccLp);

        LinearLayout chatHead = Ui.horizontal(act);
        chatHead.setGravity(Gravity.CENTER_VERTICAL);
        chatHead.addView(Ui.tv(act, "💬 和猫猫说说", 14, Ui.TEXT, true));
        chatHead.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));
        chatHead.addView(Ui.chip(act, chatLog.size() + " 条", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 10));
        chatCard.addView(chatHead);
        chatCard.addView(Ui.dividerTop(act, 10));

        ScrollView chatScroll = new ScrollView(act);
        chatScroll.setVerticalScrollBarEnabled(false);
        /*
         * 修「聊天框划不动」：聊天区外面还套着一层 ScrollView（「我」页），
         * 外层会把垂直手势吃掉。手指落在聊天区时通知父容器别拦截。
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
                return false;
            }
        });
        chatBox = Ui.vertical(act);
        chatBox.setPadding(0, dp(10), 0, dp(6));
        chatScroll.addView(chatBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230));
        csLp.topMargin = dp(2);
        chatCard.addView(chatScroll, csLp);
        final ScrollView fChatScroll = chatScroll;

        LinearLayout sendRow = Ui.horizontal(act);
        sendRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = dp(8);
        sendRow.setLayoutParams(srLp);

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
        chatCard.addView(sendRow);

        // ---------- 聊天记录备份 ----------
        LinearLayout backupRow = Ui.horizontal(act);
        backupRow.setGravity(Gravity.CENTER_VERTICAL);
        backupRow.addView(Ui.tv(act, "聊天记录", 10.5f, Ui.TEXT_MUTED, false));
        backupRow.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView exportBtn = Ui.pill(act, "⬇ 导出备份", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                11, 12, 6, true);
        exportBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                com.couple.xiaowo.core.ChatIO.export(act, chatLog, true,
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
                com.couple.xiaowo.core.ChatIO.pickImport(act, false,
                        new com.couple.xiaowo.core.ChatIO.OnImported() {
                            public void onImported(java.util.List<com.couple.xiaowo.core.Social.Msg> msgs,
                                                   String fileName) {
                                int added = com.couple.xiaowo.core.ChatIO.mergeInto(chatLog, msgs);
                                saveChat();
                                rebuildChat(fChatScroll);
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
        LinearLayout.LayoutParams bkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bkLp.topMargin = dp(8);
        chatCard.addView(backupRow, bkLp);

        content.addView(chatCard);
        rebuildChat(fChatScroll);

        View keepAlive = KeepAliveCard.build(act, true);
        LinearLayout.LayoutParams kaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kaLp.topMargin = dp(12);
        content.addView(keepAlive, kaLp);

        // ---------- 外观（浅色 / 深色） ----------
        if (act instanceof MainActivity) {
            LinearLayout.LayoutParams thLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            thLp.topMargin = dp(12);
            content.addView(ThemeCard.build((MainActivity) act), thLp);
        }

        // ---------- 从 GitHub 更新 ----------
        View github = GitHubCard.build(act, true);
        LinearLayout.LayoutParams ghLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ghLp.topMargin = dp(12);
        content.addView(github, ghLp);

        View remind = RemindSettings.build(act, null);
        LinearLayout.LayoutParams rmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rmLp.topMargin = dp(12);
        content.addView(remind, rmLp);

        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        upLp.topMargin = dp(12);
        content.addView(UpdateCard.build(act), upLp);

        content.addView(Ui.tv(act, "小窝 · 张老师的接单端 " + Ui.appVersion(act) + "\n支持同 Wi-Fi 自动发现、Tailscale 与异地中转",
                10, Ui.TEXT_MUTED, false), top(16));

        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        sLp2.weight = 1f;
        page.addView(scroll, sLp2);
        return page;
    }

    // ---------------------------------------------------------------- 聊天

    private void rebuildChat(final ScrollView container) {
        if (chatBox == null) {
            return;
        }
        chatBox.removeAllViews();
        if (chatLog.isEmpty()) {
            TextView empty = Ui.tv(act, "还没有聊天记录，\n跟猫猫说句晚安？", 11.5f, Ui.TEXT_MUTED, false);
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
        if (container != null) {
            container.post(new Runnable() {
                public void run() {
                    container.fullScroll(View.FOCUS_DOWN);
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
        if (mine) {
            bubble.setBackground(Ui.gradient(Ui.PRIMARY, 0xFF9333EA, dp(16)));
        } else {
            bubble.setBackground(Ui.round(0xFFF4F1FA, dp(16)));
        }
        column.addView(bubble);

        TextView stamp = Ui.tv(act, (mine ? "我 · " : "猫猫 · ") + time, 9, Ui.TEXT_MUTED, false);
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
        m.who = "teacher";
        m.text = text;
        m.ts = System.currentTimeMillis();
        m.mine = true;
        chatLog.add(m);
        saveChat();
        if (tab == TAB_ME) {
            switchTab(TAB_ME);
        }

        new Thread(new Runnable() {
            public void run() {
                final boolean ok = sendChatToCat(m);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (!ok) {
                            toastWarn("消息没发出去，猫猫可能不在线");
                        }
                    }
                });
            }
        }).start();
    }

    /** 接单端发消息给点单端：订单里带过 replyHost，但聊天是随时发的，所以用发现兜底 */
    private boolean sendChatToCat(Social.Msg m) {
        String body = m.toJson();
        // 多台点单端在线时，先给"心跳里报过地址"的每一台都送一份，
        // 否则只有最近下单的那台能收到消息，另一台像被晾着
        //
        // 这里跑在网络线程，而在线表是主线程在增删的 —— 先拷一份再遍历，
        // 否则正好撞上心跳写入就会 ConcurrentModificationException。
        java.util.List<PeerClient> targets;
        synchronized (orderClients) {
            targets = new java.util.ArrayList<PeerClient>(orderClients.values());
        }
        int delivered = 0;
        for (PeerClient c : targets) {
            if (c.host == null || c.host.length() == 0 || c.port <= 0) {
                continue;
            }
            if (Protocol.isOk(Net.post(c.host, c.port, Protocol.PATH_CHAT, body, 2500))
                    || Protocol.isOk(Net.post("127.0.0.1", c.port,
                    Protocol.PATH_CHAT, body, 1200))) {
                delivered++;
            }
        }
        if (delivered > 0) {
            return true;
        }
        String savedHost = sp.getString("peer_host", null);
        int savedPort = sp.getInt("peer_port", 0);
        if (savedHost != null && savedPort > 0) {
            if (Protocol.isOk(Net.post(savedHost, savedPort, Protocol.PATH_CHAT, body, 2500))) {
                return true;
            }
        }
        // 最近一单的回信地址
        for (Protocol.Order o : orders) {
            if (o.replyHost != null && o.replyHost.length() > 0 && o.replyPort > 0) {
                if (Protocol.isOk(Net.post(o.replyHost, o.replyPort, Protocol.PATH_CHAT, body, 2500))) {
                    return true;
                }
                if (Protocol.isOk(Net.post("127.0.0.1", o.replyPort, Protocol.PATH_CHAT, body, 1500))) {
                    return true;
                }
                break;
            }
        }
        // 直连全失败 → 走中转。异地时这是唯一能把消息送到对方的通路。
        if (com.couple.xiaowo.core.Relay.enabled(act)) {
            return com.couple.xiaowo.core.Relay.publish(act,
                    Protocol.ROLE_RECV, body, 8000);
        }
        return false;
    }

    private void onChatIncoming(final Social.Msg m) {
        m.mine = false;
        chatLog.add(m);
        while (chatLog.size() > 200) {
            chatLog.remove(0);
        }
        saveChat();
        if (tab == TAB_ME) {
            switchTab(TAB_ME);
        }
        host.burst(10);
        AlertCenter.push(act, RemindSettings.level(act), "猫猫说", m.text, 8);
    }

    /** 让点单端震动。level: 0 轻 / 1 中 / 2 重 */
    /** 「叫一下猫猫」—— 让点单端震动。 */
    /**
     * 请求点单端截一张屏幕发过来。
     *
     * 三条路依次试，和「震一下」「公告」保持同一套优先级：
     *   1) 保存的 peer 地址（同 Wi-Fi 时最准）
     *   2) **最近一单的 replyHost/replyPort** —— 这一条以前漏了，
     *      结果是：接单端如果没跑过「拉菜谱」，peer_host 就是空的，
     *      点「看猫猫的屏幕」直接什么都不发，用户只看到"没反应"。
     *      而订单里天然带着点单端的回信地址，异地也指向中转地址。
     *   3) 中转（点单端收到 TYPE_SHOT 后自己截、自己回传）
     */
    private void requestShot() {
        toast("让猫猫截屏中…");
        new Thread(new Runnable() {
            public void run() {
                String img = null;

                // 1) 保存的 peer 地址
                String host = sp.getString("peer_host", null);
                int port = sp.getInt("peer_port", 0);
                if (host != null && port > 0) {
                    img = extractShotImg(Net.post(host, port, Protocol.PATH_SHOT,
                            Protocol.shot(Protocol.NAME_RECV), 8000));
                }

                // 2) 最近一单带的回信地址（这是接单端最可靠的来源）
                if (img == null) {
                    for (Protocol.Order o : orders) {
                        if (o.replyHost == null || o.replyHost.length() == 0
                                || o.replyPort <= 0) {
                            continue;
                        }
                        img = extractShotImg(Net.post(o.replyHost, o.replyPort,
                                Protocol.PATH_SHOT,
                                Protocol.shot(Protocol.NAME_RECV), 8000));
                        if (img == null) {
                            img = extractShotImg(Net.post("127.0.0.1", o.replyPort,
                                    Protocol.PATH_SHOT,
                                    Protocol.shot(Protocol.NAME_RECV), 3000));
                        }
                        // 只试最近一单：更早的地址大概率已经失效
                        break;
                    }
                }

                if (img != null) {
                    final String fImg = img;
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            showShot(fImg);
                        }
                    });
                    return;
                }
                // 3) 直连都不通，走中转
                if (Relay.enabled(act)) {
                    if (Relay.publish(act, Protocol.ROLE_RECV,
                            Protocol.shot(Protocol.NAME_RECV), 8000)) {
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                toast("已向猫猫请求截图，等她截好传过来");
                            }
                        });
                    } else {
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                toastWarn("截图请求没发出去");
                            }
                        });
                    }
                } else {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            toastWarn("还没连上猫猫，也没配对中转");
                        }
                    });
                }
            }
        }).start();
    }

    /** 从 shotImg JSON 里抽出 base64 */
    private String extractShotImg(String resp) {
        if (resp == null) {
            return null;
        }
        try {
            Map<String, Object> m = Json.asObject(Json.parse(resp));
            if (Protocol.TYPE_SHOT_IMG.equals(Json.str(m, "type", ""))) {
                return Json.str(m, "img", "");
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * 显示点单端传来的截图，并自动存进相册。
     *
     * 自动保存是默认行为：截图这件事本身就是「我想留下来看看」，
     * 每次都要用户多点一次「保存」没有意义。
     * 对话框里另外给了手动「保存到相册」，重复保存会生成新文件（同图不同名），
     * 这不是 bug —— 用户主动点的就该存下来。
     */
    private void showShot(final String base64) {
        if (base64 == null || base64.length() == 0) {
            toastWarn("收到的截图是空的");
            return;
        }
        try {
            final byte[] data = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
            final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bmp == null) {
                toastWarn("截图解析失败");
                return;
            }
            LinearLayout box = Ui.vertical(act);
            box.setPadding(dp(8), dp(0), dp(8), dp(8));
            ImageView iv = new ImageView(act);
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setAdjustViewBounds(true);
            // 限制展示尺寸，避免大图撑爆对话框
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(380));
            box.addView(iv, ivLp);

            // ---- 自动保存（后台线程，别卡 UI）----
            final TextView savedTip = Ui.tv(act, "正在存入相册…", 10, Ui.TEXT_MUTED, false);
            savedTip.setGravity(Gravity.CENTER);
            box.addView(savedTip, top(8));
            new Thread(new Runnable() {
                public void run() {
                    final String msg = com.couple.xiaowo.core.ShotSaver.save(
                            act, data, Protocol.NAME_ORDER);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            if (msg != null) {
                                savedTip.setText("✅ " + msg);
                                savedTip.setTextColor(Theme.adaptText(Ui.SUCCESS));
                            } else {
                                savedTip.setText("没能自动存入相册，可用下面的按钮重试");
                                savedTip.setTextColor(Theme.adaptText(Ui.WARN));
                            }
                        }
                    });
                }
            }).start();

            // ---- 手动保存 ----
            TextView saveBtn = Ui.secondaryButton(act, "保存到相册");
            saveBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    new Thread(new Runnable() {
                        public void run() {
                            final String msg = com.couple.xiaowo.core.ShotSaver.save(
                                    act, data, Protocol.NAME_ORDER);
                            act.runOnUiThread(new Runnable() {
                                public void run() {
                                    if (msg != null) {
                                        toastOk("已保存 · 相册「小窝」里能看到");
                                        savedTip.setText("✅ " + msg);
                                        savedTip.setTextColor(Theme.adaptText(Ui.SUCCESS));
                                    } else {
                                        toastWarn("保存失败，检查一下存储空间");
                                    }
                                }
                            });
                        }
                    }).start();
                }
            });
            box.addView(saveBtn, top(8));

            DialogKit.custom(act, "猫猫的屏幕", box, "关闭", null);
        } catch (Exception e) {
            toastWarn("截图解析出错");
        }
    }

    /**
     * 「叫一下猫猫」—— 让点单端震动。
     *
     * 原来只走直连（Net.post 到 peer_host），异地时必然失败，
     * 表现就是"震动功能不可用"。现在和订单一样按优先级试：
     *   直连 → 中转
     */
    private void buzzCat(int level) {
        final int ms = level == 0 ? 200 : (level == 1 ? 600 : 1500);
        final String[] reasons = {
                "轻轻碰了你一下", "张老师叫你", "张老师急着找你！！"
        };
        final String buzzBody = Protocol.buzz(reasons[level], ms, Protocol.NAME_RECV);
        final String catHost = sp.getString("peer_host", null);
        final int port = sp.getInt("peer_port", 0);

        boolean canDirect = catHost != null && port > 0;
        boolean canRelay = com.couple.xiaowo.core.Relay.enabled(act);
        if (!canDirect && !canRelay) {
            toastWarn("还没配对 —— 去「连接方式」里配一次，以后就能震她了");
            return;
        }

        toast("正在震猫猫…");
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                if (catHost != null && port > 0) {
                    ok = Protocol.isOk(Net.post(catHost, port, Protocol.PATH_BUZZ,
                            buzzBody, 3000));
                }
                // 直连不通就试中转（异地的主要通路）
                if (!ok && com.couple.xiaowo.core.Relay.enabled(act)) {
                    ok = com.couple.xiaowo.core.Relay.publish(act,
                            Protocol.ROLE_RECV, buzzBody, 8000);
                }
                final boolean fOk = ok;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (fOk) {
                            toastOk("震到了 💗");
                            host.burst(12);
                        } else {
                            toastWarn("没送到 —— 猫猫可能没联网，或她把中转关了");
                        }
                    }
                });
            }
        }).start();
    }

    private void showNoticeDialog() {
        final EditText et = new EditText(act);
        et.setHint("例如：今天想喝奶茶，接单截止 22:00");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        et.setText(notice == null ? "" : notice.text);
        et.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(2), dp(4), dp(2), dp(2));
        box.addView(Ui.tv(act, "公告会推送到猫猫的点单端顶部", 11, Ui.TEXT_SUB, false));
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp.topMargin = dp(8);
        box.addView(et, eLp);

        DialogKit.custom(act, "📢 发布公告", box, "发布", new Runnable() {
                    public void run() {
                        String text = et.getText().toString().trim();
                        if (text.length() == 0) {
                            toastWarn("公告不能为空");
                            return;
                        }
                        publishNotice(text);
                    }
        });
    }

    private void publishNotice(final String text) {
        Social.Notice n = new Social.Notice();
        n.from = Protocol.NAME_RECV;
        n.text = text;
        n.ts = System.currentTimeMillis();
        notice = n;
        sp.edit().putString("notice", Social.noticeToJson(n)).apply();
        if (noticePreview != null) {
            noticePreview.setText(text);
            noticePreview.setTextColor(Theme.adapt(Ui.TEXT_SUB));
        }

        new Thread(new Runnable() {
            public void run() {
                final boolean ok = pushNotice(n);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (ok) {
                            toastOk("公告已推送给猫猫");
                            host.burst(16);
                        } else {
                            toastWarn("公告发出去了，但猫猫好像不在线（本机已保存）");
                        }
                    }
                });
            }
        }).start();
    }

    private boolean pushNotice(Social.Notice n) {
        String body = n.toJson();
        String savedHost = sp.getString("peer_host", null);
        int savedPort = sp.getInt("peer_port", 0);
        if (savedHost != null && savedPort > 0) {
            if (Protocol.isOk(Net.post(savedHost, savedPort, Protocol.PATH_NOTICE, body, 2500))) {
                return true;
            }
        }
        for (Protocol.Order o : orders) {
            if (o.replyHost != null && o.replyHost.length() > 0 && o.replyPort > 0) {
                if (Protocol.isOk(Net.post(o.replyHost, o.replyPort, Protocol.PATH_NOTICE, body, 2500))) {
                    return true;
                }
                if (Protocol.isOk(Net.post("127.0.0.1", o.replyPort, Protocol.PATH_NOTICE, body, 1500))) {
                    return true;
                }
                break;
            }
        }
        return false;
    }

    private void pullMenu() {
        Toast2.show(act, Toast2.LOADING, "正在向猫猫要最新的菜谱…");
        new Thread(new Runnable() {
            public void run() {
                final String body = tryPullMenu();
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (body == null) {
                            toastWarn("没连上猫猫，稍后再试（本地菜谱不受影响）");
                            return;
                        }
                        String fpBefore = Menu.fingerprint();
                        Menu.applyJson(body, true);
                        boolean changed = !Menu.fingerprint().equals(fpBefore);
                        saveMenu();
                        if (menuStamp != null) {
                            menuStamp.setText(Menu.totalCount() + " 道 · " + nowHm());
                        }
                        pages[TAB_KITCHEN] = null;
                        switchTab(TAB_MANAGE);
                        if (changed) {
                            toastOk("菜谱已更新：" + Menu.totalCount() + " 道菜");
                            host.burst(12);
                        } else {
                            toast("菜谱是最新的，没有变化");
                        }
                    }
                });
            }
        }).start();
    }

    /** 从点单端 GET /menu?pull=1 取一份菜谱 */
    private String tryPullMenu() {
        String savedHost = sp.getString("peer_host", null);
        int savedPort = sp.getInt("peer_port", 0);
        if (savedHost != null && savedPort > 0) {
            String r = Net.get(savedHost, savedPort, Protocol.PATH_MENU + "?pull=1", 2500);
            if (r != null && r.indexOf("categories") > 0) {
                return r;
            }
        }
        for (Net.Peer p : Net.discover(1600)) {
            String r = Net.get(p.host, p.port, Protocol.PATH_MENU + "?pull=1", 2500);
            if (r != null && r.indexOf("categories") > 0) {
                sp.edit().putString("peer_host", p.host).putInt("peer_port", p.port).apply();
                return r;
            }
        }
        return null;
    }

    /**
     * 接单端改了菜谱 → 推给点单端。
     *
     * 通路和发消息一样：先试保存过的对端地址，再试发现，最后走中转。
     * 异地时只有中转能把菜谱送到，所以中继这一步不能省。
     */
    private void pushMenuToCat() {
        final String body = Menu.toJson();
        Toast2.show(act, Toast2.LOADING, "正在把菜谱同步给猫猫…");
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String savedHost = sp.getString("peer_host", null);
                int savedPort = sp.getInt("peer_port", 0);
                if (savedHost != null && savedPort > 0) {
                    ok = Protocol.isOk(Net.post(savedHost, savedPort,
                            Protocol.PATH_MENU, body, 2500));
                }
                if (!ok) {
                    for (Net.Peer p : Net.discover(1600)) {
                        if (Protocol.isOk(Net.post(p.host, p.port,
                                Protocol.PATH_MENU, body, 2500))) {
                            sp.edit().putString("peer_host", p.host)
                                    .putInt("peer_port", p.port).apply();
                            ok = true;
                            break;
                        }
                    }
                }
                if (!ok && com.couple.xiaowo.core.Relay.enabled(act)) {
                    // 第二个参数是「发送方角色」，不是消息类型 —— 传错会让接收端
                    // 判断不出是谁发的，所以这里必须是 ROLE_RECV。
                    ok = com.couple.xiaowo.core.Relay.publish(act,
                            Protocol.ROLE_RECV, body, 8000);
                }
                final boolean done = ok;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (done) {
                            toastOk("菜谱已同步给猫猫（" + Menu.totalCount() + " 道）");
                            host.burst(12);
                        } else {
                            toastWarn("没连上猫猫，菜谱先存在本机了");
                        }
                    }
                });
            }
        }).start();
    }

    private void showMenuDialog() {
        StringBuilder sb = new StringBuilder();
        for (Menu.Category c : Menu.CATEGORIES) {
            sb.append(c.icon).append(' ').append(c.name).append('\n');
            for (Menu.Item it : c.items) {
                sb.append("   ").append(it.emoji).append(' ').append(it.name)
                        .append("  ❤").append(it.price).append('\n');
            }
            sb.append('\n');
        }
        sb.append("共 ").append(Menu.totalCount()).append(" 道菜");
        TextView body = Ui.tv(act, sb.toString(), 12, Ui.TEXT, false);
        body.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(act);
        sv.addView(body);
        DialogKit.custom(act, "猫猫的菜单", sv, "关闭", null);
    }

    private String nowHm() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }

    private void saveChat() {
        sp.edit().putString("chat", Social.msgsToJson(chatLog)).apply();
    }

    /** 接单端订单本地持久化（同步功能的前提，之前是纯内存态、重启丢） */
    private java.util.List<Protocol.Order> loadOrders() {
        java.util.List<Protocol.Order> out = new java.util.ArrayList<Protocol.Order>();
        String json = sp.getString("recv_orders", "[]");
        Object o = Json.parse(json);
        if (o instanceof java.util.List) {
            for (Object it : (java.util.List<?>) o) {
                Protocol.Order ord = Protocol.Order.fromJson(String.valueOf(it));
                if (ord != null && ord.id != null && ord.id.length() > 0) {
                    out.add(ord);
                }
            }
        }
        return out;
    }

    private void saveOrders() {
        java.util.List<String> parts = new java.util.ArrayList<String>();
        for (Protocol.Order o : orders) {
            parts.add(o.toJson());
        }
        sp.edit().putString("recv_orders", Json.array(parts)).apply();
    }

    /** 把对方推来的订单+聊天合并进来（按 id/键 去重，幂等） */
    private void mergeSync(com.couple.xiaowo.core.Sync.Parsed p) {
        boolean changed = false;
        // 合并订单：这里的"订单"是点单端视角的 History.Entry，
        // 转成 Protocol.Order 存进 orders（展示层共用）
        java.util.Set<Long> haveTs = new java.util.HashSet<Long>();
        for (Protocol.Order o : orders) {
            haveTs.add(o.ts);
        }
        for (History.Entry e : p.history) {
            if (e.ts > 0 && !haveTs.contains(e.ts)) {
                Protocol.Order ord = new Protocol.Order();
                ord.id = "h" + e.ts;
                ord.ts = e.ts;
                ord.total = e.total;
                ord.note = e.note;
                ord.fromName = Protocol.NAME_ORDER;
                for (Protocol.Line l : e.lines) {
                    ord.lines.add(l);
                }
                orders.add(ord);
                haveTs.add(e.ts);
                changed = true;
            }
        }
        // 合并聊天
        java.util.Set<String> chatKeys = new java.util.HashSet<String>();
        for (Social.Msg m : chatLog) {
            chatKeys.add(m.who + "|" + m.ts + "|" + m.text);
        }
        for (Social.Msg m : p.chat) {
            String key = m.who + "|" + m.ts + "|" + m.text;
            if (!chatKeys.contains(key)) {
                // 同步过来的消息：mine 按本机角色（接单端=张老师）重算
                m.mine = Social.mineForWho(m.who, false);
                chatLog.add(m);
                chatKeys.add(key);
                changed = true;
            }
        }
        if (changed) {
            java.util.Collections.sort(orders, new java.util.Comparator<Protocol.Order>() {
                public int compare(Protocol.Order a, Protocol.Order b) {
                    return Long.compare(b.ts, a.ts);
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
            saveOrders();
            saveChat();
            pages[TAB_KITCHEN] = null;
            pages[TAB_ORDERS] = null;
            pages[TAB_ME] = null;
            if (tab == TAB_KITCHEN || tab == TAB_ORDERS || tab == TAB_ME) {
                switchTab(tab);
            }
        }
    }

    /** 启动后把本地订单+聊天推给对方 */
    /**
     * 定时自动刷新菜谱。
     *
     * 点单端随时可能改菜谱，接单端不能只靠「收到推送」这一条路 ——
     * 推送可能因为点单端离线、没点同步、网络抖动漏掉。
     * 所以每 2 分钟主动 GET /menu?pull=1 问一次（同 Wi-Fi 直连）；
     * 异地直连不通时，由点单端每 3 分钟广播一次菜谱（见 OrderScreen）。
     *
     * 首轮延迟 8 秒（等界面和监听服务稳定），之后周期执行。
     * 静默进行，只在真有变化时提示。
     */
    private void scheduleMenuAutoRefresh() {
        final android.os.Handler h = sched;
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            public void run() {
                if (dead || act == null || act.isFinishing()
                        || (android.os.Build.VERSION.SDK_INT >= 17 && act.isDestroyed())) {
                    return;
                }
                new Thread(new Runnable() {
                    public void run() {
                        final String body = tryPullMenu();
                        if (body == null) {
                            return;   // 没连上，下一轮再试
                        }
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                String fpBefore = Menu.fingerprint();
                                Menu.applyJson(body, true);
                                saveMenu();
                                boolean changed = !Menu.fingerprint().equals(fpBefore);
                                if (menuStamp != null) {
                                    menuStamp.setText(Menu.totalCount() + " 道 · " + nowHm());
                                }
                                pages[TAB_KITCHEN] = null;
                                if (changed) {
                                    Toast2.ok(act, "菜谱已自动更新：" + Menu.totalCount() + " 道");
                                }
                            }
                        });
                    }
                }).start();
                h.postDelayed(self[0], AUTO_REFRESH_MENU_MS);
            }
        };
        h.postDelayed(self[0], 8000L);
    }

    private void scheduleSyncPush() {
        postDelayed(new Runnable() {
            @Override
            public void run() {
                pushSync();
            }
        }, 2500);
    }

    private void pushSync() {
        // 把自己的数据编码：订单转 History.Entry 形态 + 聊天
        java.util.List<History.Entry> hist = new java.util.ArrayList<History.Entry>();
        for (Protocol.Order o : orders) {
            History.Entry e = new History.Entry();
            e.ts = o.ts;
            e.total = o.total;
            e.note = o.note;
            e.delivered = true;
            e.lines.addAll(o.lines);
            hist.add(e);
        }
        final String body = com.couple.xiaowo.core.Sync.build(hist, chatLog);
        new Thread(new Runnable() {
            public void run() {
                boolean ok = false;
                String host = sp.getString("peer_host", null);
                int port = sp.getInt("peer_port", 0);
                if (host != null && port > 0) {
                    ok = Protocol.isOk(Net.post(host, port, Protocol.PATH_SYNC, body, 3000));
                }
                if (!ok && Relay.enabled(act)) {
                    ok = Relay.publish(act, Protocol.ROLE_RECV, body, 8000);
                }
            }
        }).start();
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

    private String topDish() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (Protocol.Order o : orders) {
            for (Protocol.Line l : o.lines) {
                String disp = l.displayName();
                Integer c = counts.get(disp);
                counts.put(disp, c == null ? l.qty : c + l.qty);
            }
        }
        String best = null;
        int max = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                best = e.getKey();
            }
        }
        return best == null ? "还没有数据" : (best + " ×" + max);
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

    private View buildSubHeader(String title, String sub) {
        LinearLayout h = Ui.vertical(act);
        h.setBackground(Ui.gradient(0xFFFFF0F7, 0xFFF5F0FE, dp(24)));
        h.setPadding(dp(18), dp(18), dp(18), dp(16));
        h.addView(Ui.tv(act, title, 22, Ui.TEXT, true));
        TextView s = Ui.tv(act, sub, 11.5f, Ui.SUB, false);
        h.addView(s, top(4));
        return h;
    }

    private View emptyCat(int catIndex, String title, String sub) {
        LinearLayout box = Ui.vertical(act);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, dp(36), 0, dp(20));
        ImageView iv = new ImageView(act);
        Bitmap bm = CatImage.bitmap(act, catIndex);
        if (bm != null) {
            iv.setImageBitmap(bm);
        }
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        roundClip(iv, 28);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(dp(120), dp(120));
        iLp.gravity = Gravity.CENTER;
        box.addView(iv, iLp);
        TextView t = Ui.tv(act, title, 14, Ui.TEXT, true);
        t.setGravity(Gravity.CENTER);
        box.addView(t, top(12));
        TextView s = Ui.tv(act, sub, 11, Ui.SUB, false);
        s.setGravity(Gravity.CENTER);
        box.addView(s, top(6));
        return box;
    }

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

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(10);
        return p;
    }

    private void roundClip(ImageView iv, final int radiusDp) {
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(radiusDp));
            }
        });
    }

    // ================================================================ 网络

    private void startListening() {
        startListening(wantedPort);
    }

    private void startListening(int wanted) {
        if (server != null && server.isRunning()) {
            return;
        }
        final int port;
        Net.Server s = new Net.Server(new Net.BlobHandler() {
            public Object handle(String method, String path, String body) {
                /*
                 * 界面已经销毁就别再处理了。
                 *
                 * Server 是后台线程，长连接可能比 Activity 活得久一点
                 * （shutdown 里会 stop，但已 accept 的连接会走完当前请求）。
                 * 这时再 runOnUiThread 去动已经 destroy 的界面没有意义，
                 * 也容易踩到空 View。
                 */
                if (act.isFinishing() || (Build.VERSION.SDK_INT >= 17 && act.isDestroyed())) {
                    return Protocol.ack(false, Protocol.ROLE_RECV, "bye");
                }
                // ---- 直连推更新：把内置的点单端 APK 给对方 ----
                if (ApkRelay.PATH_INFO.equals(path)) {
                    ApkRelay.Info info = ApkRelay.bundledInfo(act);
                    if (info == null) {
                        return Protocol.ack(false, Protocol.ROLE_RECV, "没有内置更新包");
                    }
                    return info.toJson();
                }
                if (ApkRelay.PATH_ORDER.equals(path)) {
                    byte[] apk = ApkRelay.readBundled(act);
                    if (apk == null) {
                        return Protocol.ack(false, Protocol.ROLE_RECV, "没有内置更新包");
                    }
                    return Net.Blob.apk(apk);
                }

                if (Protocol.PATH_PRESENCE.equals(path)) {
                    // 点单端的心跳：记下这台设备 + 它的回信地址
                    Map<String, Object> m = Json.asObject(Json.parse(body));
                    touchClient(Json.str(m, "id", ""), Json.str(m, "name", ""),
                            Json.str(m, "host", ""), (int) Json.num(m, "port", 0));
                    return Protocol.ack(true, Protocol.ROLE_RECV, "知道你在");
                }
                if (Protocol.PATH_ORDER.equals(path)) {
                    final Protocol.Order o = Protocol.Order.fromJson(body);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            onOrderArrived(o);
                        }
                    });
                    return Protocol.ack(true, Protocol.ROLE_RECV, "收到啦，马上配送");
                }
                if (Protocol.PATH_PING.equals(path)) {
                    return Json.object(Json.fields(
                            "ok", "true",
                            "role", Json.quote(Protocol.ROLE_RECV),
                            "port", String.valueOf(server == null ? 0 : server.port())));
                }
                if (Protocol.PATH_HEART.equals(path)) {
                    return Protocol.ack(true, Protocol.ROLE_RECV, "谢谢你的爱心");
                }
                if (Protocol.PATH_CHAT.equals(path)) {
                    final Social.Msg m = Social.Msg.fromJson(body);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            onChatIncoming(m);
                        }
                    });
                    return Protocol.ack(true, Protocol.ROLE_RECV, "收到");
                }
                if (Protocol.PATH_ACK_UPDATE.equals(path)) {
                    // 点单端说它装上新版了
                    final java.util.Map<String, Object> m = Json.asObject(Json.parse(body));
                    final String vn = Json.str(m, "versionName", "?");
                    final int vc = (int) Json.num(m, "versionCode", 0);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            lastAckText = "猫猫已更新到 " + vn + "（vc" + vc + "）";
                            lastAckTime = System.currentTimeMillis();
                            if (ackView != null) {
                                ackView.setTextColor(Theme.adapt(Ui.SUCCESS));
                                ackView.setText("✅ " + lastAckText + " · 刚刚");
                            }
                            Toast2.ok(act, "猫猫那边装好了 v" + vn);
                            host.burst(14);
                        }
                    });
                    return Protocol.ack(true, Protocol.ROLE_RECV, "回执已记录");
                }
                if (Protocol.PATH_MENU.equals(path)) {
                    // 猫猫更新了菜谱，推给接单端
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            int before = Menu.totalCount();
                            String fpBefore = Menu.fingerprint();
                            Menu.applyJson(body, true);
                            saveMenu();
                            if (menuStamp != null) {
                                menuStamp.setText("菜谱：" + Menu.totalCount() + " 道 · " + nowHm());
                            }
                            pages[TAB_KITCHEN] = null;
                            if (tab == TAB_KITCHEN || tab == TAB_ORDERS || tab == TAB_FRIDGE) {
                                switchTab(tab);
                            }
                            if (!Menu.fingerprint().equals(fpBefore)) {
                                Toast2.ok(act, "菜谱已更新：" + Menu.totalCount() + " 道菜");
                                host.burst(12);
                            } else {
                                Toast2.info(act, "菜谱没有变化（" + Menu.totalCount() + " 道）");
                            }
                        }
                    });
                    return Protocol.ack(true, Protocol.ROLE_RECV, "菜谱已同步");
                }
                if (Protocol.PATH_NOTICE.equals(path)) {
                    // 接单端自己也可能收到公告（比如对方回敬一条）
                    final Social.Notice n = Social.Notice.fromJson(body);
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            notice = n;
                            sp.edit().putString("notice", Social.noticeToJson(n)).apply();
                            if (noticePreview != null) {
                                noticePreview.setText(n.text);
                                noticePreview.setTextColor(Theme.adapt(Ui.TEXT_SUB));
                            }
                            toastLove("猫猫发了公告：" + n.text);
                        }
                    });
                    return Protocol.ack(true, Protocol.ROLE_RECV, "公告已收到");
                }
                if (Protocol.PATH_PING.equals(path)) {
                    return Json.object(Json.fields(
                            "ok", "true",
                            "role", Json.quote(Protocol.ROLE_RECV),
                            "port", String.valueOf(server == null ? 0 : server.port())));
                }
                if (Protocol.PATH_SYNC.equals(path)) {
                    final com.couple.xiaowo.core.Sync.Parsed p =
                            com.couple.xiaowo.core.Sync.parse(body);
                    if (p != null) {
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                mergeSync(p);
                            }
                        });
                        return Protocol.ack(true, Protocol.ROLE_RECV, "记录已同步");
                    }
                    return Protocol.ack(false, Protocol.ROLE_RECV, "同步数据格式不对");
                }
                return Protocol.ack(false, Protocol.ROLE_RECV, "unknown path");
            }
        });
        try {
            port = s.start(wanted);
        } catch (Exception e) {
            listenPort = 0;
            updateStatus(false, 0);
            return;
        }
        server = s;
        listenPort = port;
        responder = new Net.DiscoveryResponder(new Net.DiscoveryResponder.PortProvider() {
            public int tcpPort() {
                return listenPort;
            }
        });
        responder.start();
        updateStatus(true, port);
    }
    /**
     * 处理中转消息 —— 由 MainActivity 统一派发进来。
     *
     * 订阅本身已经收归 Activity 了（只有一份），这里只负责"收到之后怎么处理"。
     * 和 HTTP 那套走同一批处理函数，逻辑天然复用。
     */
    public void onRelayMessage(String type, String payload) {
        try {
            if (Protocol.TYPE_PRESENCE.equals(type)) {
                Map<String, Object> m = Json.asObject(Json.parse(payload));
                touchClient(Json.str(m, "id", ""), Json.str(m, "name", ""),
                        Json.str(m, "host", ""), (int) Json.num(m, "port", 0));
            } else if (Protocol.TYPE_ORDER.equals(type)) {
                onOrderArrived(Protocol.Order.fromJson(payload));
            } else if (Protocol.TYPE_CHAT.equals(type)) {
                onChatIncoming(Social.Msg.fromJson(payload));
            } else if (Protocol.TYPE_MENU.equals(type)) {
                /*
                 * 菜谱从中转推过来（异地时直连拉不到，只能靠这条）。
                 * 和 HTTP 那条走同一套 applyJson + 落盘逻辑。
                 */
                String fpBefore = Menu.fingerprint();
                Menu.applyJson(payload, true);
                saveMenu();
                boolean changed = !Menu.fingerprint().equals(fpBefore);
                if (menuStamp != null) {
                    menuStamp.setText(Menu.totalCount() + " 道 · " + nowHm());
                }
                pages[TAB_KITCHEN] = null;
                if (changed) {
                    Toast2.ok(act, "菜谱已更新：" + Menu.totalCount() + " 道");
                }
            } else if (Protocol.TYPE_NOTICE.equals(type)) {
                Social.Notice n = Social.Notice.fromJson(payload);
                notice = n;
                sp.edit().putString("notice", Social.noticeToJson(n)).apply();
                if (noticePreview != null) {
                    noticePreview.setText(n.text);
                    noticePreview.setTextColor(Theme.adapt(Ui.TEXT_SUB));
                }
                toastLove("猫猫发了公告：" + n.text);
            } else if (Protocol.TYPE_HEART.equals(type)) {
                host.burst(24);
                toastLove("收到一个爱心 ❤");
            } else if (ApkRelay.PATH_INFO.equals(type)) {
                // 点单端来问版本：直接把版本信息回去
                replyApkInfo(payload);
            } else if (ApkRelay.PATH_ORDER.equals(type)) {
                // 点单端要更新包：中转传不了 6MB，告诉它改用直连
                replyApkDenied(payload);
            } else if (Protocol.TYPE_SYNC.equals(type)) {
                final com.couple.xiaowo.core.Sync.Parsed p =
                        com.couple.xiaowo.core.Sync.parse(payload);
                if (p != null) {
                    mergeSync(p);
                }
            } else if (Protocol.TYPE_SHOT_IMG.equals(type)) {
                // 点单端从中转回传的截图
                final String base64 = Json.str(Json.asObject(Json.parse(payload)), "img", "");
                showShot(base64);
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 回答"我这有什么版本的点单端包"。
     *
     * 这条必须走中转 —— 否则点单端在异地时永远问不到版本，
     * 也就永远拿不到更新（这是用户报的第二个问题的根因）。
     */
    private void replyApkInfo(String payload) {
        ApkRelay.Info info = ApkRelay.bundledInfo(act);
        if (info == null || act == null) {
            return;
        }
        java.util.Map<String, Object> m = Json.asObject(Json.parse(payload));
        String asker = Json.str(m, "from", "");
        Relay.publish(act, Protocol.ROLE_RECV, info.toJson(), 6000);
    }

    /** 中转传不动 APK，明确拒绝并告知原因，避免对方一直等 */
    private void replyApkDenied(String payload) {
        Relay.publish(act, Protocol.ROLE_RECV, Protocol.ack(false, Protocol.ROLE_RECV,
                "中转通道不能传安装包，请在同一个 Wi-Fi 下更新"), 6000);
    }

    private void stopListening() {
        if (responder != null) {
            responder.stop();
            responder = null;
        }
        if (server != null) {
            server.stop();
        }
        listenPort = 0;
        updateStatus(false, 0);
    }

    // ============================================================ 在线点单端

    /**
     * 记一笔"这台点单端刚说话"。
     *
     * 心跳、订单、中转里的 presence 都会走到这里 —— 只要它还活着，
     * 90 秒内就一直算在线。以后要区分"哪台点了什么"也全靠这个编号。
     */
    private void touchClient(final String id, final String name,
                             final String host, final int port) {
        if (id == null || id.length() == 0) {
            return;
        }
        act.runOnUiThread(new Runnable() {
            public void run() {
                PeerClient c;
                boolean isNew;
                synchronized (orderClients) {
                    c = orderClients.get(id);
                    isNew = c == null;
                    if (isNew) {
                        c = new PeerClient();
                        c.id = id;
                        orderClients.put(id, c);
                    }
                    c.name = (name == null || name.length() == 0) ? Protocol.NAME_ORDER : name;
                    if (host != null && host.length() > 0) {
                        c.host = host;
                    }
                    if (port > 0) {
                        c.port = port;
                    }
                    c.seen = System.currentTimeMillis();
                    pruneClients();
                }
                refreshClients();
                if (isNew && tab == TAB_KITCHEN) {
                    // 多了一台点单端，给个轻提示，方便调试时确认
                    Toast2.info(act, c.name + " 连上了");
                }
            }
        });
    }

    /** 把超过 90 秒没心跳的从在线表里去掉 */
    private void pruneClients() {
        long now = System.currentTimeMillis();
        synchronized (orderClients) {
            java.util.Iterator<Map.Entry<String, PeerClient>> it =
                    orderClients.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue().seen > CLIENT_TTL_MS) {
                    it.remove();
                }
            }
        }
    }

    /** 刷新首页卡片上「几个点单端在线」那一行 */
    private void refreshClients() {
        if (clientsText == null) {
            return;
        }
        pruneClients();
        StringBuilder sb = new StringBuilder();
        synchronized (orderClients) {
            if (orderClients.isEmpty()) {
                clientsText.setText("📱 点单端：还没有谁在线");
                clientsText.setTextColor(Theme.adapt(Ui.TEXT_MUTED));
                return;
            }
            sb.append("📱 点单端：").append(orderClients.size()).append(" 个在线");
            for (PeerClient c : orderClients.values()) {
                sb.append("　·　").append(c.name);
            }
        }
        clientsText.setText(sb.toString());
        clientsText.setTextColor(Theme.adapt(0xFF16A34A));
    }

    /** 每 15 秒扫一遍：掉线的剔掉、显示刷一下（没有心跳就永远"在线"是假的） */
    private void scheduleClientTick() {
        final android.os.Handler h = sched;
        final Runnable[] self = new Runnable[1];
        self[0] = new Runnable() {
            public void run() {
                if (dead || act == null || act.isFinishing()) {
                    return;
                }
                refreshClients();
                h.postDelayed(self[0], CLIENT_TICK_MS);
            }
        };
        h.postDelayed(self[0], CLIENT_TICK_MS);
    }

    /** 中转状态变了 —— 首页的状态行由 MainActivity 直接绑着刷，这里只更新人数行 */
    public void onRelayState() {
        refreshClients();
    }

    private void updateStatus(boolean running, int port) {
        if (statusText == null) {
            return;
        }
        if (running) {
            statusDot.setTextColor(Theme.adapt(0xFF3ECF8E));
            statusText.setText(" 接单中");
            addrText.setText(Net.localIpv4() + ":" + port);
            toggleBtn.setText("停止");
            toggleBtn.setTextColor(Theme.adapt(Ui.PRIMARY_DEEP));
            toggleBtn.setBackground(Ui.round(0xFFF2EEFB, dp(999)));
        } else {
            statusDot.setTextColor(Theme.adapt(0xFFC9C4D4));
            statusText.setText(" 已停止");
            addrText.setText("点「开始」继续接单");
            toggleBtn.setText("开始");
            toggleBtn.setTextColor(Theme.adapt(0xFFFFFFFF));
            toggleBtn.setBackground(Ui.round(Ui.PRIMARY, dp(999)));
        }
        updateTailscaleLine(port);
    }

    /** Tailscale 那行：有地址就显示可复制的 100.x:端口，没有就提示去哪开。 */
    private void updateTailscaleLine(int port) {
        if (tsText == null) {
            return;
        }
        String ip = Net.tailscaleIpv4();
        if (ip == null) {
            tsText.setText("🛰 Tailscale：未连接（异地联机请在 Tailscale App 里开启）");
            tsText.setTextColor(Theme.adapt(Ui.TEXT_MUTED));
        } else {
            tsText.setText("🛰 Tailscale：" + ip + ":" + (port > 0 ? port : Net.DEFAULT_PORT)
                    + "　点一下复制");
            tsText.setTextColor(Theme.adapt(Ui.PRIMARY_DEEP));
        }
    }

    private void copyText(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("小窝地址", text));
        } catch (Exception ignore) {
        }
    }

    /**
     * 「异地配对」对话框。
     *
     * 只有一个动作：拿到一个房间码，发给对方一次。
     * 之后两个人永远不用再配 —— 房间码就存在本地。
     */
    private void showPairingDialog() {
        final Dialog[] holder = new Dialog[1];
        LinearLayout box = Ui.vertical(act);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        final String cur = Relay.room(act);
        box.addView(Ui.tv(act, cur == null ? "还没有配对" : ("当前房间：" + cur),
                12, cur == null ? Ui.TEXT_MUTED : Ui.PRIMARY_DEEP, true));
        box.addView(Ui.tv(act,
                "异地（不在同一个 Wi-Fi）时，靠这条中转通路收发。\n"
                        + "生成一个房间码，发给猫猫填一次就行 —— 之后永久有效，\n"
                        + "换 Wi-Fi、重启、重装都不用再配。",
                10.5f, Ui.TEXT_SUB, false), top(10));

        TextView gen = Ui.pill(act, cur == null ? "生成房间码" : "换一个新的房间码",
                Ui.PRIMARY, 0xFFFFFFFF, 12, 16, 9, true);
        gen.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                String code = Relay.newPairing(act);
                copyText(code);
                if (holder[0] != null) {
                    DialogKit.dismiss(holder[0]);
                }
                        if (statusCardDirty != null) {
                    statusCardDirty.run();
                }
                toastOk("房间码 " + code + " 已复制，发给猫猫就好");
            }
        });
        box.addView(gen, top(12));

        if (cur != null) {
            TextView copy = Ui.pill(act, "复制当前房间码", Ui.PRIMARY_SOFT,
                    Ui.PRIMARY_DEEP, 11, 14, 8, true);
            copy.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    copyText(cur);
                    if (holder[0] != null) {
                        DialogKit.dismiss(holder[0]);
                    }
                    toast("已复制 " + cur);
                }
            });
            box.addView(copy, top(8));

            TextView clear = Ui.pill(act, "解除配对", 0xFFFDECEC, 0xFFD2453F, 11, 14, 8, true);
            clear.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    DialogKit.confirm(act, "解除配对？",
                            "解除后就只能靠同一个 Wi-Fi 找对方了，异地会收不到。",
                            "解除", new Runnable() {
                                public void run() {
                                    Relay.clear(act);
                                    if (act instanceof MainActivity) {
                                        ((MainActivity) act).restartRelay();
                                    }
                                    if (statusCardDirty != null) {
                                        statusCardDirty.run();
                                    }
                                    toastWarn("已解除配对");
                                }
                            });
                }
            });
            box.addView(clear, top(8));
        }

        holder[0] = DialogKit.custom(act, "异地配对", box, "关闭", null);
    }

    private void onOrderArrived(final Protocol.Order o) {
        // 下单本身也算"这台点单端在线"（老版本没有 fromId，就记不下编号，忽略即可）
        touchClient(o.fromId, o.fromName, o.replyHost, o.replyPort);
        // 去重：客户端读响应超时后会带同一个 id 重发，
        // 这时单其实已经收下了，不能记两遍。
        if (o.id != null && o.id.length() > 0) {
            for (Protocol.Order old : orders) {
                if (o.id.equals(old.id)) {
                    return;
                }
            }
        }
        orders.add(0, o);
        saveOrders();
        // 厨房页是缓存复用的，新订单进来要让它重建，"最近一单"才会更新
        pages[TAB_KITCHEN] = null;
        switchTab(tab);
        host.burst(40);
        vibrate();
        AlertCenter.push(act, RemindSettings.level(act),
                "💗 " + o.fromName + " 点了单",
                o.summaryForNotify(), 24);
    }

    private void replyHeart(final Protocol.Order o) {
        if (o.replyHost == null || o.replyHost.length() == 0 || o.replyPort <= 0) {
            toast("不知道对方的回信地址，这一单发不出爱心");
            return;
        }
        host.burst(22);
        new Thread(new Runnable() {
            public void run() {
                String r = Net.post(o.replyHost, o.replyPort, Protocol.PATH_HEART,
                        Protocol.heart("小窝·接单", "抱抱你，已接单！"), 2500);
                if (!Protocol.isOk(r)) {
                    r = Net.post("127.0.0.1", o.replyPort, Protocol.PATH_HEART,
                            Protocol.heart("小窝·接单", "抱抱你，已接单！"), 1500);
                }
                final boolean ok = Protocol.isOk(r);
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        toast(ok ? "爱心已经飞过去啦 💗" : "对方好像不在线…");
                    }
                });
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
                v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(60);
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
        stopListening();
    }
}
