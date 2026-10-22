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
        box.addView(Ui.tv(act,
                "下面三种方式，任意一种通了就能用。\n"
                        + "系统会自动按「Wi-Fi → Tailscale → 中转」的顺序尝试。",
                10.5f, Ui.TEXT_SUB, false), top(act, 5));
        return box;
    }

    private static View buildFooter(Activity act) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(Ui.dpi(act, 12), Ui.dpi(act, 10), Ui.dpi(act, 12), Ui.dpi(act, 10));
        box.setBackground(Ui.round(Ui.PRIMARY_SOFT, Ui.dp(act, Ui.R_TILE)));
        box.addView(Ui.tv(act, "怎么选？", 11.5f, Ui.PRIMARY_DEEP, true));
        box.addView(Ui.tv(act,
                "· 在家里同一个 Wi-Fi —— 什么都不用配，自动就能找到\n"
                        + "· 一个人在家、一个人在宿舍 —— 用「异地中转」，配对一次就行\n"
                        + "· 两台手机都装了 Tailscale —— 填 100.x 地址，比中转快",
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
                status.setTextColor(Ui.TEXT_MUTED);
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
                    status.setTextColor(Ui.TEXT_MUTED);
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

        box.addView(Ui.tv(act, "对方的地址（IP:端口）", 11, Ui.PRIMARY_DEEP, true));
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

    // ---------------------------------------------------------------- 卡片 3：异地中转

    private static View relayCard(final Activity act, final String room, final boolean isRecv,
                                  final Runnable rebuild, final Runnable onChanged) {
        LinearLayout card = cardShell(act);
        final boolean has = room != null && room.length() > 0;
        card.addView(cardHead(act, "🌐", "异地中转",
                "不在同一个网络时用 · 配对一次永久有效", 0xFFEC4899));

        card.addView(Ui.tv(act,
                isRecv
                        ? "1. 点下面的按钮生成一个房间码\n"
                        + "2. 把房间码发给猫猫（微信、短信都行）\n"
                        + "3. 猫猫在「连接方式 → 异地中转」里填一次，以后永不再配"
                        : "1. 问张老师要一个房间码（6 位）\n"
                        + "2. 点下面的按钮填进去\n"
                        + "3. 填一次就行，换 Wi-Fi、重启都不用再填",
                10.5f, Ui.TEXT_SUB, false), top(act, 6));

        final TextView status = Ui.tv(act,
                has ? ("当前房间：" + room + "（已配对）") : "还没配对",
                10.5f, has ? Ui.PRIMARY_DEEP : Ui.TEXT_MUTED, true);
        card.addView(status, top(act, 8));

        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);

        if (isRecv) {
            TextView gen = Ui.pill(act, has ? "换一个房间码" : "生成房间码", Ui.PRIMARY,
                    0xFFFFFFFF, 11.5f, 14, 8, true);
            gen.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String code = Relay.newPairing(act);
                    copyToClipboard(act, code);
                    status.setText("当前房间：" + code + "（已配对，已复制）");
                    status.setTextColor(Ui.PRIMARY_DEEP);
                    rebuild.run();
                    if (onChanged != null) {
                        onChanged.run();
                    }
                    Toast2.ok(act, "房间码 " + code + " 已复制，发给猫猫");
                }
            });
            row.addView(gen);
        } else {
            TextView join = Ui.pill(act, has ? "换一个房间码" : "填房间码加入",
                    Ui.PRIMARY, 0xFFFFFFFF, 11.5f, 14, 8, true);
            join.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    promptRoom(act, rebuild, onChanged);
                }
            });
            row.addView(join);
        }

        if (has) {
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

            TextView cut = Ui.pill(act, "解除", 0xFFFDECEC, 0xFFD2453F, 11, 12, 8, true);
            cut.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    DialogKit.confirm(act, "解除配对？",
                            "解除后异地就收不到了，只能靠同一个 Wi-Fi。",
                            "解除", new Runnable() {
                                public void run() {
                                    Relay.clear(act);
                                    rebuild.run();
                                    if (onChanged != null) {
                                        onChanged.run();
                                    }
                                }
                            });
                }
            });
            LinearLayout.LayoutParams xLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            xLp.leftMargin = Ui.dpi(act, 8);
            row.addView(cut, xLp);
        }
        card.addView(row, top(act, 10));

        // 中转的"在线"是真实维护的，直接读
        if (has) {
            TextView live = Ui.tv(act, "中转状态：正在连接…", 10, Ui.TEXT_MUTED, false);
            card.addView(live, top(act, 6));
            if (act instanceof MainActivity) {
                ((MainActivity) act).bindRelayStatus(live);
            }
        }
        return card;
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
