package com.couple.xiaowo.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.couple.xiaowo.BuildInfo;
import com.couple.xiaowo.core.Channel;
import com.couple.xiaowo.core.Mqtt;
import com.couple.xiaowo.core.Net;
import com.couple.xiaowo.core.Protocol;
import com.couple.xiaowo.core.Relay;

/**
 * 「连接方式」整页 —— 把所有通道收在一处。
 *
 * 设计上要解决三件事：
 *   1. 三种方式各自的用法必须写清楚（用户不该去猜）
 *   2. 状态必须是**真探测**出来的，不能像以前那样存了地址就显示"已连接"
 *   3. 同一套页面两个 App 都能用，避免维护两份
 *
 * 页面结构：
 *   顶部：当前首选通道 + 一句话总结
 *   列表：三张卡，每张 = 名称 + 用法 + 状态 + 操作按钮
 *   底部：优先级说明
 */
public final class ConnectPage {

    private ConnectPage() {
    }

    /**
     * 打开整页。
     *
     * @param isRecv 接单端=true。有些文案/操作会反过来（比如谁生成房间码）
     */
    public static Dialog show(final Activity act, final boolean isRecv,
                              final Runnable onChanged) {
        final SharedPreferences sp = act.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);

        LinearLayout root = Ui.vertical(act);
        root.setPadding(Ui.dpi(act, 4), 0, Ui.dpi(act, 4), Ui.dpi(act, 8));

        // ---------- 顶部：当前状态 ----------
        root.addView(buildHeader(act, isRecv), top(act, 0));

        // ---------- 三条通道 ----------
        final LinearLayout list = Ui.vertical(act);
        root.addView(list, top(act, 10));

        // 中转的房间码变化后要重建列表
        final Runnable rebuild = new Runnable() {
            public void run() {
                list.removeAllViews();
                String room = Relay.room(act);
                String peerHost = sp.getString("peer_host", null);
                int peerPort = sp.getInt("peer_port", 0);

                // 1) 同 Wi-Fi（总是存在，无需配置）
                list.addView(lanCard(act, onChanged), top(act, 0));

                // 2) Tailscale / 手填
                list.addView(directCard(act, peerHost, peerPort, isRecv, onChanged),
                        top(act, 10));

                // 3) 异地中转
                list.addView(relayCard(act, room, isRecv, this, onChanged), top(act, 10));

                // 底部说明
                list.addView(buildFooter(act), top(act, 14));
            }
        };
        rebuild.run();

        /*
         * 不要再套 ScrollView：DialogKit.custom 内部已经有一个
         * MaxHeightScroll（高度取屏幕 50%）。嵌套滚动会让内层高度算成负数，
         * 内容直接被裁掉 —— 实测 dump 出的区域是 "191,2628,1068,2106"，
         * bottom < top，等于不可见。
         *
         * 直接把线性布局交给 DialogKit，由它负责滚动。
         */
        return DialogKit.custom(act, "连接方式", root, "关闭", null);
    }

    // ---------------------------------------------------------------- 顶部

    private static View buildHeader(Activity act, boolean isRecv) {
        LinearLayout box = Ui.vertical(act);
        box.setBackground(Ui.gradient(0xFFFFF0F7, 0xFFF5F0FE, Ui.dp(act, 16)));
        box.setPadding(Ui.dpi(act, 14), Ui.dpi(act, 12), Ui.dpi(act, 14), Ui.dpi(act, 12));

        box.addView(Ui.tv(act, isRecv ? "让猫猫找到你" : "找到张老师", 15, Ui.TEXT, true));
        box.addView(Ui.tv(act, Ui.pick(act,
                        "三种方式选一种就好，通了就能用，不用都配。",
                        "下面三种方式，任意一种通了就能用。\n"
                                + "系统会自动按「同 Wi-Fi → Tailscale → 异地连接」的顺序尝试。"),
                10.5f, Ui.TEXT_SUB, false), top(act, 5));
        return box;
    }

    private static View buildFooter(Activity act) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(Ui.dpi(act, 12), Ui.dpi(act, 10), Ui.dpi(act, 12), Ui.dpi(act, 10));
        box.setBackground(Ui.round(Ui.PRIMARY_SOFT, Ui.dp(act, Ui.R_TILE)));
        box.addView(Ui.tv(act, Ui.pick(act, "小提示", "怎么选？"), 11.5f, Ui.PRIMARY_DEEP, true));
        box.addView(Ui.tv(act, Ui.pick(act,
                        "· 在一起：什么都不用配\n"
                                + "· 不在一起：用「异地连接」，选一次就行",
                        "· 在家里同一个 Wi-Fi —— 什么都不用配，自动就能找到\n"
                                + "· 一个人在家、一个人在宿舍 —— 用「异地连接」，配对一次就行\n"
                                + "· 两台手机都装了 Tailscale —— 填 100.x 地址，比中转快"),
                10, Ui.TEXT_SUB, false), top(act, 4));
        return box;
    }

    // ---------------------------------------------------------------- 卡片 1：同 Wi-Fi

    private static View lanCard(final Activity act, final Runnable onChanged) {
        LinearLayout card = cardShell(act);
        card.addView(cardHead(act, "🏠", "同一个 Wi-Fi", "最快，完全自动", 0xFF16A34A));

        card.addView(Ui.tv(act,
                "两个人在同一个路由器下时用这个。\n"
                        + "不用填任何东西 —— 点下面的按钮，App 会在局域网里广播找对方。",
                10.5f, Ui.TEXT_SUB, false), top(act, 6));

        final TextView status = Ui.tv(act, "点下面按钮开始查找", 10.5f, Ui.TEXT_MUTED, false);
        card.addView(status, top(act, 8));

        final TextView btn = Ui.pill(act, "🔍 在局域网里找对方", Ui.PRIMARY, 0xFFFFFFFF,
                11.5f, 14, 8, true);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                btn.setEnabled(false);
                btn.setText("查找中…");
                status.setText("正在广播…");
                status.setTextColor(Theme.adaptText(Ui.TEXT_MUTED));
                new Thread(new Runnable() {
                    public void run() {
                        final java.util.List<Net.Peer> peers = Net.discover(2200);
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                btn.setEnabled(true);
                                btn.setText("🔍 在局域网里找对方");
                                if (peers.isEmpty()) {
                                    status.setText("没找到。确认两台手机连的是同一个 Wi-Fi，且对方开着 App。");
                                    status.setTextColor(0xFFDC2626);
                                } else {
                                    Net.Peer p = peers.get(0);
                                    SharedPreferences sp = act.getSharedPreferences(
                                            "xiaowo", Context.MODE_PRIVATE);
                                    sp.edit().putString("peer_host", p.host)
                                            .putInt("peer_port", p.port).apply();
                                    status.setText("✓ 找到了：" + p.host + ":" + p.port + "，已自动保存");
                                    status.setTextColor(0xFF16A34A);
                                    if (onChanged != null) {
                                        onChanged.run();
                                    }
                                }
                            }
                        });
                    }
                }).start();
            }
        });
        card.addView(btn, top(act, 10));
        return card;
    }

    // ---------------------------------------------------------------- 卡片 2：Tailscale / 手填

    private static View directCard(final Activity act, String peerHost, int peerPort,
                                   final boolean isRecv, final Runnable onChanged) {
        LinearLayout card = cardShell(act);
        final boolean has = peerHost != null && peerPort > 0;
        final boolean isTs = has && Net.isTailscaleIp(peerHost);
        card.addView(cardHead(act, isTs ? "🛰" : "🔗",
                isTs ? "Tailscale 虚拟内网" : "手动填地址",
                isTs ? "像在同一个局域网里" : "知道对方 IP 时用", 0xFF7C3AED));

        card.addView(Ui.tv(act,
                "· Tailscale：两台手机都装 Tailscale、登同一个账号，\n"
                        + "  对方状态卡上的 100.x 地址填进来即可\n"
                        + "· 手动：直接填 192.168.x.x:8765 这种",
                10.5f, Ui.TEXT_SUB, false), top(act, 6));

        final TextView status = Ui.tv(act,
                has ? ("当前：" + peerHost + ":" + peerPort) : "还没填地址",
                10.5f, has ? Ui.PRIMARY_DEEP : Ui.TEXT_MUTED, false);
        card.addView(status, top(act, 8));

        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView edit = Ui.pill(act, has ? "修改地址" : "填地址", Ui.PRIMARY_SOFT,
                Ui.PRIMARY_DEEP, 11, 12, 8, true);
        edit.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                promptAddress(act, onChanged);
            }
        });
        row.addView(edit);

        if (has) {
            final TextView probe = Ui.pill(act, "测试连通", 0xFFF2EEFB, Ui.PRIMARY_DEEP,
                    11, 12, 8, true);
            probe.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    probe.setEnabled(false);
                    probe.setText("测试中…");
                    status.setText("正在连接对方…");
                    status.setTextColor(Theme.adaptText(Ui.TEXT_MUTED));
                    SharedPreferences sp = act.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
                    String h = sp.getString("peer_host", null);
                    int p = sp.getInt("peer_port", 0);
                    Channel ch = Channel.direct(h, p);
                    ch.probe(isRecv ? Protocol.ROLE_RECV : Protocol.ROLE_ORDER,
                            new Channel.Result() {
                                public void done(final Channel c) {
                                    act.runOnUiThread(new Runnable() {
                                        public void run() {
                                            probe.setEnabled(true);
                                            probe.setText("测试连通");
                                            status.setText((c.state == Channel.STATE_OK ? "✓ " : "✗ ")
                                                    + c.detail);
                                            status.setTextColor(c.stateColor());
                                        }
                                    });
                                }
                            });
                }
            });
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pLp.leftMargin = Ui.dpi(act, 8);
            row.addView(probe, pLp);
        }
        card.addView(row, top(act, 10));
        return card;
    }

    private static void promptAddress(final Activity act, final Runnable onChanged) {
        final SharedPreferences sp = act.getSharedPreferences("xiaowo", Context.MODE_PRIVATE);
        LinearLayout box = Ui.vertical(act);
        box.setPadding(Ui.dpi(act, 20), Ui.dpi(act, 8), Ui.dpi(act, 20), Ui.dpi(act, 8));

        box.addView(Ui.tv(act, Ui.pick(act, "对方的地址", "对方的地址（IP:端口）"),
                11, Ui.PRIMARY_DEEP, true));
        final EditText et = new EditText(act);
        et.setHint("192.168.1.8:8765　或　100.94.232.43:8765");
        String cur = sp.getString("peer_host", null);
        int p = sp.getInt("peer_port", 0);
        if (cur != null && p > 0) {
            et.setText(cur + ":" + p);
        }
        box.addView(et);
        box.addView(Ui.tv(act,
                "· 同一 Wi-Fi 时不用填，上一张卡自动发现就够\n"
                        + "· 填了就在 App 里记住，以后不用再填",
                10, Ui.TEXT_SUB, false), top(act, 8));

        DialogKit.custom(act, "手动填地址", box, "保存", new Runnable() {
            public void run() {
                String s = et.getText().toString().trim();
                if (s.length() == 0) {
                    sp.edit().remove("peer_host").remove("peer_port").apply();
                } else {
                    String raw = s;
                    if (raw.regionMatches(true, 0, "https://", 0, 8)) {
                        raw = raw.substring(8);
                    } else if (raw.regionMatches(true, 0, "http://", 0, 7)) {
                        raw = raw.substring(7);
                    }
                    String host = raw;
                    int port = Net.DEFAULT_PORT;
                    int colon = raw.lastIndexOf(':');
                    if (colon > 0) {
                        host = raw.substring(0, colon);
                        try {
                            port = Integer.parseInt(raw.substring(colon + 1).trim());
                        } catch (Exception ignore) {
                            port = Net.DEFAULT_PORT;
                        }
                    }
                    sp.edit().putString("peer_host", host).putInt("peer_port", port).apply();
                }
                if (onChanged != null) {
                    onChanged.run();
                }
            }
        });
    }

    // ---------------------------------------------------------------- 卡片 3：异地连接

    /**
     * 异地连接只有三种，且**必须**分清楚：
     *
     *   ☁️ 小窝云    —— APK 里预置的服务器 + 预置房间，装上就通，用户什么都不用填
     *   📻 房间码    —— 公共 broker + 自己编的 6 位码（以前的玩法）
     *   🖥 自建服务器 —— 自己搭的 mosquitto/EMQX + 房间码
     *
     * 以前这里只写"房间码"，实际连的却是预置服务器，界面和代码对不上，
     * 用户自然会困惑。现在选哪种就只显示哪种，状态行读的也是真实值。
     */
    private static View relayCard(final Activity act, final String room, final boolean isRecv,
                                  final Runnable rebuild, final Runnable onChanged) {
        LinearLayout card = cardShell(act);
        card.addView(cardHead(act, "🌐", "异地连接",
                "不在一起时走这条 · 三种方式选一种", 0xFFEC4899));

        final String mode = Relay.mode(act);
        card.addView(Ui.tv(act, isRecv
                ? "选好之后把「房间码」告诉猫猫一次，以后都不用再弄。"
                : "三种方式张老师选哪种，你就选哪种；房间码问他要一个填进来。",
                10.5f, Ui.TEXT_SUB, false), top(act, 6));

        // ---------- 三选一 ----------
        LinearLayout modes = Ui.horizontal(act);
        modes.setGravity(Gravity.CENTER_VERTICAL);
        String[][] defs = new String[][]{
                {Relay.MODE_CLOUD, "☁️ 小窝云"},
                {Relay.MODE_PUBLIC, "📻 房间码"},
                {Relay.MODE_SELF, "🖥 自建"},
        };
        for (int i = 0; i < defs.length; i++) {
            final String m = defs[i][0];
            boolean sel = m.equals(mode);
            TextView pill = Ui.pill(act, defs[i][1],
                    sel ? Ui.PRIMARY : 0xFFF1EEF7,
                    sel ? 0xFFFFFFFF : Ui.TEXT_SUB, 11.5f, 13, 8, true);
            pill.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Relay.setMode(act, m);
                    if (act instanceof MainActivity) {
                        ((MainActivity) act).restartRelay();
                    }
                    rebuild.run();
                    if (onChanged != null) {
                        onChanged.run();
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.leftMargin = Ui.dpi(act, 8);
            }
            modes.addView(pill, lp);
        }
        card.addView(modes, top(act, 10));

        // ---------- 当前用的是哪一套 ----------
        TextView cur;
        if (Relay.MODE_CLOUD.equals(mode)) {
            cur = Ui.tv(act, "当前：小窝云，房间 " + (room == null ? "—" : room) + "（不用你配置）",
                    10.5f, Ui.SUCCESS, false);
        } else if (Relay.MODE_SELF.equals(mode)) {
            cur = Ui.tv(act, "当前：自建服务器 " + Mqtt.cfgHostText() + ":" + Mqtt.cfgPortValue()
                    + (room == null ? "" : " · 房间 " + room), 10.5f, Ui.SUCCESS, false);
        } else {
            cur = Ui.tv(act, room == null
                            ? "当前：公共房间，还没编房间码" : "当前：公共房间 " + room,
                    10.5f, room == null ? Ui.TEXT_MUTED : Ui.SUCCESS, false);
        }
        card.addView(cur, top(act, 8));

        // ---------- 这一套的操作按钮 ----------
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int added = 0;

        if (Relay.MODE_CLOUD.equals(mode)) {
            TextView toPublic = Ui.pill(act, "改用房间码", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                    11, 12, 8, true);
            toPublic.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Relay.setMode(act, Relay.MODE_PUBLIC);
                    promptRoom(act, rebuild, onChanged);
                }
            });
            row.addView(toPublic);
            added++;
            if (!Mqtt.hasOwnBroker()) {
                // 云模式下 hasOwnBroker 恒真（走的是预置），这里只提供出口
                TextView toSelf = Ui.pill(act, "自建服务器", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                        11, 12, 8, true);
                toSelf.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        promptBroker(act, rebuild, onChanged, cur);
                    }
                });
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sp.leftMargin = Ui.dpi(act, 8);
                row.addView(toSelf, sp);
                added++;
            }
        } else {
            if (isRecv) {
                TextView gen = Ui.pill(act, room == null ? "生成房间码" : "换一个房间码",
                        Ui.PRIMARY, 0xFFFFFFFF, 11.5f, 14, 8, true);
                gen.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        String code = Relay.newPairing(act);
                        copyToClipboard(act, code);
                        rebuild.run();
                        if (onChanged != null) {
                            onChanged.run();
                        }
                        Toast2.ok(act, "房间码 " + code + " 已复制，发给猫猫");
                    }
                });
                row.addView(gen);
                added++;
            } else {
                TextView join = Ui.pill(act, room == null ? "填房间码加入" : "换一个房间码",
                        Ui.PRIMARY, 0xFFFFFFFF, 11.5f, 14, 8, true);
                join.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        promptRoom(act, rebuild, onChanged);
                    }
                });
                row.addView(join);
                added++;
            }
            if (room != null) {
                TextView copy = Ui.pill(act, "复制", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                        11, 12, 8, true);
                copy.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        copyToClipboard(act, room);
                        Toast2.ok(act, "已复制 " + room);
                    }
                });
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cLp.leftMargin = Ui.dpi(act, 8);
                row.addView(copy, cLp);
                added++;
            }
            if (Relay.MODE_SELF.equals(mode)) {
                TextView srvBtn = Ui.pill(act, "改服务器", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                        11, 12, 8, true);
                srvBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        promptBroker(act, rebuild, onChanged, cur);
                    }
                });
                LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sLp.leftMargin = Ui.dpi(act, 8);
                row.addView(srvBtn, sLp);
                added++;
            } else {
                TextView srvBtn = Ui.pill(act, "自建服务器", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP,
                        11, 12, 8, true);
                srvBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        promptBroker(act, rebuild, onChanged, cur);
                    }
                });
                LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sLp.leftMargin = Ui.dpi(act, 8);
                row.addView(srvBtn, sLp);
                added++;
            }
        }
        if (added > 0) {
            card.addView(row, top(act, 10));
        }

        // ---------- 真实连接状态 ----------
        TextView live = Ui.tv(act, "连接状态：正在检查…", 10, Ui.TEXT_MUTED, false);
        card.addView(live, top(act, 6));
        if (act instanceof MainActivity) {
            ((MainActivity) act).bindRelayStatus(live);
        }
        return card;
    }

    private static void clearRoom(Activity act, Runnable rebuild, Runnable onChanged) {
        Relay.clear(act);
        rebuild.run();
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /**
     * 「自建服务器」设置。
     *
     * 四个字段 + 一个 TLS 开关。留空地址 = 回到公共 broker。
     * 保存后立刻 restartRelay()，不然旧连接还挂在老地址上。
     */
    private static void promptBroker(final Activity act, final Runnable rebuild,
                                     final Runnable onChanged, final TextView label) {
        Mqtt.configure(act);
        LinearLayout box = Ui.vertical(act);
        box.setPadding(Ui.dpi(act, 4), Ui.dpi(act, 2), Ui.dpi(act, 4), Ui.dpi(act, 2));

        box.addView(Ui.tv(act, Ui.pick(act,
                        "只有你自己搭了服务器才需要填这里。没搭就关掉这个窗口。",
                        "自己搭的 mosquitto（阿里云/腾讯云都行）。留空表示继续用公共 broker。"),
                10.5f, Ui.TEXT_SUB, false));

        final EditText host = field(act, "地址，如 47.98.1.2", Mqtt.cfgHostText());
        box.addView(labelOf(act, "服务器地址"), top(act, 10));
        box.addView(host, top(act, 4));

        final EditText port = field(act, "端口，默认 1883",
                String.valueOf(Mqtt.cfgPortValue()));
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(labelOf(act, "端口"), top(act, 10));
        box.addView(port, top(act, 4));

        final EditText user = field(act, "用户名（没开鉴权就留空）", Mqtt.cfgUserText());
        box.addView(labelOf(act, "用户名"), top(act, 10));
        box.addView(user, top(act, 4));

        final EditText pass = field(act, "密码", Mqtt.cfgPassText());
        box.addView(labelOf(act, "密码"), top(act, 10));
        box.addView(pass, top(act, 4));

        final boolean[] tls = new boolean[]{Mqtt.cfgTlsValue()};
        final TextView tlsBtn = Ui.pill(act,
                tls[0] ? "TLS：开（8883）" : "TLS：关", Ui.PRIMARY_SOFT,
                Ui.PRIMARY_DEEP, 11.5f, 14, 8, true);
        tlsBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tls[0] = !tls[0];
                tlsBtn.setText(tls[0] ? "TLS：开（8883）" : "TLS：关");
            }
        });
        box.addView(tlsBtn, top(act, 12));
        box.addView(Ui.tv(act, tls[0]
                        ? "证书必须被系统信任（Let's Encrypt 即可；自签要先在手机里装一次）。"
                        : "不开 TLS 时账号密码是明文传的，只建议在家里/自己网络里用。",
                9.5f, Ui.TEXT_MUTED, false), top(act, 6));

        DialogKit.custom(act, "自建服务器", box, "保存", new Runnable() {
            public void run() {
                String h = host.getText() == null ? "" : host.getText().toString().trim();
                int p;
                try {
                    p = Integer.parseInt(port.getText() == null ? ""
                            : port.getText().toString().trim());
                } catch (Exception e) {
                    p = 1883;
                }
                if (p <= 0 || p > 65535) {
                    p = 1883;
                }
                String u = user.getText() == null ? "" : user.getText().toString().trim();
                String pw = pass.getText() == null ? "" : pass.getText().toString();
                Mqtt.saveBroker(act, h, p, u, pw, tls[0]);
                if (label != null) {
                    label.setText("服务器：" + Mqtt.endpointText()
                            + (Mqtt.usingPreset() ? "（已预置，不用配）"
                               : Mqtt.hasOwnBroker() ? "" : "（公共，建议自建）"));
                    label.setTextColor(Theme.adapt(
                            (Mqtt.hasOwnBroker() || Mqtt.usingPreset()) ? Ui.SUCCESS : Ui.TEXT_MUTED));
                }
                if (act instanceof MainActivity) {
                    ((MainActivity) act).restartRelay();
                }
                if (rebuild != null) {
                    rebuild.run();
                }
                if (onChanged != null) {
                    onChanged.run();
                }
                Toast2.ok(act, h.length() == 0
                        ? Ui.pick(act, "已改回公用通道", "已切回公共 broker")
                        : Ui.pick(act, "保存好啦，正在连…", "已保存，正在连 " + h));
            }
        });
    }

    private static TextView labelOf(Activity act, String text) {
        return Ui.tv(act, text, 10.5f, Ui.TEXT_MUTED, false);
    }

    private static EditText field(Activity act, String hint, String prefill) {
        EditText et = new EditText(act);
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        et.setTextColor(Theme.adapt(Ui.TEXT));
        et.setHintTextColor(Ui.TEXT_MUTED);
        et.setHint(hint);
        if (prefill != null && prefill.length() > 0) {
            et.setText(prefill);
            et.setSelection(et.getText().length());
        }
        et.setSingleLine(true);
        et.setPadding(Ui.dpi(act, 14), Ui.dpi(act, 11), Ui.dpi(act, 14), Ui.dpi(act, 11));
        et.setBackground(Ui.round(Ui.BG, Ui.dp(act, 12f),
                Math.max(1, Ui.dpi(act, 0.8f)), Ui.LINE));
        return et;
    }

    private static void promptRoom(final Activity act, final Runnable rebuild,
                                   final Runnable onChanged) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(Ui.dpi(act, 20), Ui.dpi(act, 8), Ui.dpi(act, 20), Ui.dpi(act, 8));
        box.addView(Ui.tv(act, "张老师给你的 6 位房间码", 11, Ui.PRIMARY_DEEP, true));
        final EditText et = new EditText(act);
        et.setHint("例如 SL5LWV");
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        String cur = Relay.room(act);
        if (cur != null) {
            et.setText(cur);
        }
        box.addView(et);
        DialogKit.custom(act, "加入房间", box, "确定", new Runnable() {
            public void run() {
                String code = et.getText().toString().trim().toUpperCase();
                if (code.length() < 4) {
                    Toast2.warn(act, "房间码太短了，再检查一下");
                    return;
                }
                Relay.setRoom(act, code);
                Relay.setEnabled(act, true);
                if (act instanceof MainActivity) {
                    ((MainActivity) act).restartRelay();
                }
                rebuild.run();
                if (onChanged != null) {
                    onChanged.run();
                }
                Toast2.ok(act, "已加入房间 " + code);
            }
        });
    }

    // ---------------------------------------------------------------- 小工具

    private static LinearLayout cardShell(Activity act) {
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(Ui.CARD, Ui.dp(act, Ui.R_CARD),
                Math.max(1, Ui.dpi(act, 0.8f)), 0xFFEDE9F4));
        card.setPadding(Ui.dpi(act, 14), Ui.dpi(act, 12), Ui.dpi(act, 14), Ui.dpi(act, 12));
        card.setElevation(Ui.dp(act, 2f));
        return card;
    }

    private static View cardHead(Activity act, String icon, String title, String sub, int color) {
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.tv(act, icon, 17, Ui.TEXT, false));
        LinearLayout col = Ui.vertical(act);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.weight = 1f;
        cLp.leftMargin = Ui.dpi(act, 9);
        col.setLayoutParams(cLp);
        col.addView(Ui.tv(act, title, 13, Ui.TEXT, true));
        col.addView(Ui.tv(act, sub, 10, Ui.TEXT_MUTED, false), top(act, 2));
        row.addView(col);
        return row;
    }

    private static void copyToClipboard(Context c, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("小窝", text));
        } catch (Exception ignore) {
        }
    }

    private static LinearLayout.LayoutParams top(Context c, float dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dpi(c, dp);
        return lp;
    }
}
