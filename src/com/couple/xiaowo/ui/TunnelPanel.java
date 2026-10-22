package com.couple.xiaowo.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.couple.xiaowo.core.FrpcRunner;
import com.couple.xiaowo.core.Natfrp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「SakuraFrp 一键联机」面板。
 *
 * 它做三件事：
 *   1. 用访问密钥登录官方 API v4，拉取账号下的隧道列表
 *   2. 选一条隧道，启动 APK 内置的官方 frpc（-f 密钥:隧道ID）
 *   3. 从 frpc 日志里抓到公网地址，一键复制给对方填
 *
 * 全程只读取官方接口，不会替用户改隧道、改配置。
 */
public class TunnelPanel extends LinearLayout {

    public interface Host {
        /** 接单端当前监听的本地端口（隧道要指向它） */
        int localPort();

        /** 把接单端的端口改成指定值；成功返回 true */
        boolean rebindTo(int port);
    }

    private static final String PREF = "xiaowo";
    private static final String KEY_TOKEN = "natfrp_token";
    private static final String KEY_TUNNEL = "natfrp_tunnel";

    private final Activity act;
    private final Host host;
    private final FrpcRunner runner;
    private final SharedPreferences sp;
    private final LinearLayout container;
    private final TextView logView;
    private final List<String> logs = new ArrayList<String>();

    private String token;
    private Natfrp.User user;
    private List<Natfrp.Tunnel> tunnels = new ArrayList<Natfrp.Tunnel>();
    private Map<Integer, Natfrp.Node> nodes = new LinkedHashMap<Integer, Natfrp.Node>();
    private Natfrp.Tunnel selected;
    private String address = "";
    private boolean busy = false;
    private String error = "";

    public TunnelPanel(Activity act, Host host, FrpcRunner runner) {
        super(act);
        this.act = act;
        this.host = host;
        this.runner = runner;
        this.sp = act.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.token = sp.getString(KEY_TOKEN, null);

        setOrientation(VERTICAL);
        setPadding(dp(18), dp(16), dp(18), dp(10));
        setBackground(Ui.round(0xFFFFFFFF, dp(20)));

        TextView title = Ui.tv(act, "🚀 SakuraFrp 一键联机", 16, Ui.TEXT, true);
        addView(title);
        TextView sub = Ui.tv(act, "把接单端穿透到公网，对方填个地址就能点单",
                11, Ui.SUB, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        addView(sub, subLp);

        ScrollView scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = dp(10);
        container = Ui.vertical(act);
        scroll.addView(container, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, sLp);

        logView = Ui.tv(act, "", 9, 0xFF6B6678, false);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setBackground(Ui.round(0xFFF7F5FA, dp(10)));
        logView.setPadding(dp(8), dp(6), dp(8), dp(6));
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.topMargin = dp(8);
        logView.setMaxLines(10);
        logView.setVisibility(GONE);
        addView(logView, lLp);

        TextView close = Ui.pill(act, "关闭", 0xFFF2EEFB, Ui.PRIMARY_DEEP, 13, 20, 9, true);
        close.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                dismiss();
            }
        });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(10);
        addView(close, cLp);

        if (token != null && token.length() > 0) {
            login();
        } else {
            refresh();
        }
    }

    private Runnable dismisser;

    public void setDismisser(Runnable r) {
        this.dismisser = r;
    }

    private void dismiss() {
        if (dismisser != null) {
            dismisser.run();
        }
    }

    public static Dialog show(Activity act, Host host, FrpcRunner runner) {
        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final TunnelPanel panel = new TunnelPanel(act, host, runner);
        panel.setDismisser(new Runnable() {
            public void run() {
                dlg.dismiss();
            }
        });
        dlg.setContentView(panel);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            int width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.92f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
        panel.refresh();
        return dlg;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }

    // ================================================================ 渲染

    private void refresh() {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        if (token == null || token.length() == 0) {
            buildLoginForm();
            return;
        }
        buildAccountBar();
        if (error.length() > 0) {
            TextView e = Ui.tv(act, "⚠ " + error, 11, 0xFFD2453F, false);
            LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eLp.topMargin = dp(8);
            container.addView(e, eLp);
        }
        if (busy) {
            container.addView(label("正在和 SakuraFrp 服务器通信…", Ui.SUB, 11), top(8));
            return;
        }
        if (runner.isRunning()) {
            buildRunning();
            return;
        }
        buildTunnelList();

        // 上次穿透成功过的地址：直接摆出来，不用等它重新刷日志
        final String saved = lastSavedAddress();
        if (saved.length() > 0) {
            LinearLayout last = Ui.vertical(act);
            last.setBackground(Ui.round(0xFFF2FBF6, dp(12), Math.max(1, dp(0.8f)), 0xFFCDEBDB));
            last.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams llLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llLp.topMargin = dp(10);
            last.setLayoutParams(llLp);

            last.addView(Ui.tv(act, "📌 上次的公网地址", 11.5f, Ui.TEXT, true));
            TextView addr = Ui.tv(act, saved, 14, Ui.SUCCESS, true);
            addr.setTextIsSelectable(true);
            last.addView(addr, top(6));

            long ago = lastSavedTime();
            if (ago > 0) {
                long mins = Math.max(0, (System.currentTimeMillis() - ago) / 60000L);
                last.addView(Ui.tv(act, mins < 1 ? "刚刚还在用" : (mins + " 分钟前用过，可能已经失效"),
                        9.5f, Ui.TEXT_MUTED, false), top(4));
            }

            TextView copy = Ui.pill(act, "复制这个地址", Ui.PRIMARY, 0xFFFFFFFF, 11.5f, 14, 7, true);
            copy.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    copyToClipboard(saved);
                    toast("已复制：" + saved);
                }
            });
            LinearLayout.LayoutParams cpLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cpLp.topMargin = dp(8);
            last.addView(copy, cpLp);

            container.addView(last);
        }
    }

    private TextView label(String text, int color, float sp) {
        return Ui.tv(act, text, sp, color, false);
    }

    private LinearLayout.LayoutParams top(int dpValue) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(dpValue);
        return p;
    }

    private void buildLoginForm() {
        if (error.length() > 0) {
            TextView e = Ui.tv(act, "⚠ " + error, 11, 0xFFD2453F, false);
            e.setBackground(Ui.round(0xFFFDECEC, dp(10)));
            e.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams eLp0 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            eLp0.bottomMargin = dp(10);
            container.addView(e, eLp0);
        }
        container.addView(label("1. 打开 natfrp.com → 用户信息 → 点「查看访问密钥」", Ui.TEXT, 11.5f));
        container.addView(label("2. 复制那串密钥填在这里（它不是登录密码）", Ui.TEXT, 11.5f), top(2));
        container.addView(label("3. 登录后勾选一条隧道，点「开始穿透」", Ui.TEXT, 11.5f), top(2));

        final EditText et = new EditText(act);
        et.setHint("粘贴访问密钥，例如 wdnmdtoken666666");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setTypeface(android.graphics.Typeface.MONOSPACE);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        et.setBackground(Ui.round(0xFFF7F5FA, dp(10)));
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp.topMargin = dp(10);
        container.addView(et, eLp);

        TextView go = Ui.pill(act, "登录", Ui.PRIMARY, 0xFFFFFFFF, 13, 24, 9, true);
        go.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                String t = et.getText().toString().trim();
                if (t.length() < 8) {
                    toast("密钥看起来不太对，再检查一下");
                    return;
                }
                token = t;
                login();
            }
        });
        container.addView(go, top(10));

        TextView self = Ui.tv(act, "ⓘ 自检：内置 frpc 能不能跑", 10.5f, Ui.PRIMARY, false);
        self.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                appendLog("正在自检…");
                new Thread(new Runnable() {
                    public void run() {
                        final String v = FrpcRunner.runVersion(act);
                        act.runOnUiThread(new Runnable() {
                            public void run() {
                                appendLog("内置 frpc: " + v.replace("\n", " | "));
                                toast("自检结果见下方日志");
                            }
                        });
                    }
                }).start();
            }
        });
        container.addView(self, top(12));
    }

    private void buildAccountBar() {
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String who = user == null ? "已保存访问密钥" : ("已登录 · " + user.name);
        TextView name = Ui.tv(act, "👤 " + who, 12, Ui.TEXT, true);
        row.addView(name);
        TextView out = Ui.pill(act, "退出", 0xFFF2EEFB, Ui.PRIMARY_DEEP, 10, 10, 5, false);
        LinearLayout.LayoutParams oLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        oLp.leftMargin = dp(8);
        out.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                runner.stop();
                token = null;
                user = null;
                tunnels = new ArrayList<Natfrp.Tunnel>();
                selected = null;
                sp.edit().remove(KEY_TOKEN).remove(KEY_TUNNEL).apply();
                logs.clear();
                updateLog();
                refresh();
            }
        });
        row.addView(out, oLp);
        addFull(row);
    }

    private void addFull(View v) {
        container.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void buildTunnelList() {
        if (tunnels.isEmpty()) {
            container.addView(label("账号下还没有隧道。先去 natfrp.com 建一条 TCP 隧道，本地地址填 "
                    + "127.0.0.1:" + host.localPort(), Ui.SUB, 11.5f), top(10));
            TextView refresh = Ui.pill(act, "重新获取隧道", Ui.PRIMARY_SOFT, Ui.PRIMARY_DEEP, 11, 14, 7, false);
            refresh.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    loadTunnels();
                }
            });
            container.addView(refresh, top(10));
            return;
        }

        container.addView(label("选择要穿透的隧道：", Ui.SUB, 11), top(10));
        for (final Natfrp.Tunnel t : tunnels) {
            LinearLayout card = Ui.vertical(act);
            boolean on = selected != null && selected.id == t.id;
            card.setBackground(on
                    ? Ui.round(0xFFF0ECFB, dp(12), Math.max(1, dp(1.2f)), Ui.PRIMARY)
                    : Ui.round(0xFFF9F8FC, dp(12), Math.max(1, dp(0.8f)), 0xFFEDE9F4));
            card.setPadding(dp(12), dp(10), dp(12), dp(10));

            LinearLayout head = Ui.horizontal(act);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(Ui.tv(act, t.name, 12.5f, Ui.TEXT, true));
            head.addView(Ui.tv(act, "  #" + t.id, 10, Ui.SUB, false));
            View spacer = new View(act);
            head.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
            head.addView(Ui.tv(act, t.type.toUpperCase(), 9.5f, Ui.PRIMARY_DEEP, true));
            card.addView(head);

            String portLine = "本地 " + t.localIp + ":" + t.localPort;
            boolean match = t.matchesPort(host.localPort());
            TextView detail = Ui.tv(act, portLine + (match ? "  ✓ 端口对得上" : "  ⚠ 和接单端口 " + host.localPort() + " 不一致"),
                    10.5f, match ? 0xFF3EA97B : 0xFFE0823D, false);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = dp(3);
            card.addView(detail, dLp);

            card.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    selected = t;
                    sp.edit().putInt(KEY_TUNNEL, t.id).apply();
                    refresh();
                }
            });
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.topMargin = dp(8);
            container.addView(card, cLp);
        }

        if (selected == null && tunnels.size() > 0) {
            selected = tunnels.get(0);
            sp.edit().putInt(KEY_TUNNEL, selected.id).apply();
        }
        if (selected == null) {
            return;
        }

        if (!selected.matchesPort(host.localPort())) {
            TextView fix = Ui.pill(act, "把接单端口改成 " + selected.localPort,
                    0xFFFFF3E6, 0xFFC86A1E, 11, 14, 7, false);
            fix.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    boolean ok = host.rebindTo(selected.localPort);
                    toast(ok ? "接单端口已改成 " + selected.localPort
                            : "端口被占用，换一条隧道或改面板配置");
                    refresh();
                }
            });
            container.addView(fix, top(10));
        }

        TextView start = Ui.pill(act, "开始穿透", Ui.PRIMARY, 0xFFFFFFFF, 14, 40, 11, true);
        start.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startTunnel();
            }
        });
        container.addView(start, top(12));
        container.addView(label("将运行官方 frpc 0.51.0-sakura-14（已内置，无需另装）", Ui.SUB, 9.5f), top(6));
    }

    private void buildRunning() {
        LinearLayout card = Ui.vertical(act);
        card.setBackground(Ui.round(0xFFF2FBF6, dp(12), Math.max(1, dp(0.8f)), 0xFFCDEBDB));
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(Ui.tv(act, "● 穿透已启动", 12, 0xFF2E9E6B, true));

        TextView addr = Ui.tv(act, address.length() > 0 ? address : "等待 frpc 返回公网地址…",
                address.length() > 0 ? 15 : 11.5f, Ui.TEXT, address.length() > 0);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = dp(6);
        card.addView(addr, aLp);

        if (address.length() > 0) {
            TextView copy = Ui.pill(act, "复制地址发给 TA", Ui.PRIMARY, 0xFFFFFFFF, 12, 16, 8, true);
            copy.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    copyToClipboard(address);
                    toast("已复制：" + address);
                }
            });
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.topMargin = dp(10);
            card.addView(copy, cLp);
            card.addView(label("让对方在点单端点「⇄ 连接」，把这段粘进去就行", Ui.SUB, 10), top(8));
        } else {
            card.addView(label("隧道连上后这里会显示公网地址", Ui.SUB, 10), top(6));
        }
        addFull(card);

        TextView stop = Ui.pill(act, "停止穿透", 0xFFFDECEC, 0xFFD2453F, 13, 30, 9, true);
        stop.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                runner.stop();
                address = "";
                toast("已停止穿透");
                refresh();
            }
        });
        container.addView(stop, top(12));
    }

    // ================================================================ 动作

    private void login() {
        busy = true;
        error = "";
        refresh();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                Natfrp.User u = null;
                try {
                    u = Natfrp.userInfo(token);
                    sp.edit().putString(KEY_TOKEN, token).apply();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final Natfrp.User fu = u;
                final String ferr = err;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        if (fu == null) {
                            token = null;
                            error = "登录失败：" + ferr;
                            refresh();
                            return;
                        }
                        user = fu;
                        error = "";
                        refresh();
                        loadTunnels();
                    }
                });
            }
        }).start();
    }

    private void loadTunnels() {
        if (token == null) {
            return;
        }
        busy = true;
        error = "";
        refresh();
        new Thread(new Runnable() {
            public void run() {
                List<Natfrp.Tunnel> ts = null;
                Map<Integer, Natfrp.Node> ns = new LinkedHashMap<Integer, Natfrp.Node>();
                String err = null;
                try {
                    ts = Natfrp.tunnels(token);
                    try {
                        ns = Natfrp.nodes(token);
                    } catch (Exception ignore) {
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<Natfrp.Tunnel> fts = ts;
                final Map<Integer, Natfrp.Node> fns = ns;
                final String ferr = err;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        busy = false;
                        if (fts == null) {
                            error = "获取隧道失败：" + ferr;
                            refresh();
                            return;
                        }
                        tunnels = fts;
                        nodes = fns;
                        int keep = sp.getInt(KEY_TUNNEL, 0);
                        selected = null;
                        for (Natfrp.Tunnel t : tunnels) {
                            if (t.id == keep) {
                                selected = t;
                            }
                        }
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void startTunnel() {
        if (selected == null) {
            toast("先选一条隧道");
            return;
        }
        if (!FrpcRunner.available(act)) {
            toast("这个 APK 里没有内置 frpc，只有「接单端」才有");
            return;
        }
        logs.clear();
        updateLog();
        address = "";
        runner.start(act, token, selected.id, new FrpcRunner.Listener() {
            public void onLine(final String line) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        appendLog(line);
                        String found = parseAddress(line);
                        if (found != null && address.length() == 0) {
                            address = found;
                            rememberAddress(found);
                            refresh();
                        }
                    }
                });
            }

            public void onExit(final int code) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        appendLog("[frpc 已退出，退出码 " + code + "]");
                        if (code != 0) {
                            error = "frpc 退出了（" + code + "），检查隧道是否已在别处运行";
                        }
                        address = "";
                        refresh();
                    }
                });
            }

            public void onStartFailed(final String reason) {
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        error = reason;
                        refresh();
                    }
                });
            }
        });
        toast("正在启动 frpc…");
        refresh();
        // 有些隧道不会在日志里给完整地址，用面板配置兜底
        new android.os.Handler(act.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                if (runner.isRunning() && address.length() == 0 && selected != null) {
                    String guess = selected.address(nodes);
                    if (guess.length() > 0) {
                        address = guess;
                        rememberAddress(guess);
                        refresh();
                    }
                }
            }
        }, 6000);
    }

    /** 把成功过的地址存下来，下次打开直接显示，不用等日志 */
    private void rememberAddress(String addr) {
        if (addr != null && addr.length() > 0) {
            sp.edit().putString("frp_last_address", addr)
                    .putLong("frp_last_time", System.currentTimeMillis()).apply();
        }
    }

    private String lastSavedAddress() {
        return sp.getString("frp_last_address", "");
    }

    private long lastSavedTime() {
        return sp.getLong("frp_last_time", 0L);
    }

    private void appendLog(String line) {
        logs.add(line);
        while (logs.size() > 9) {
            logs.remove(0);
        }
        updateLog();
    }

    private void updateLog() {
        if (logView == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String l : logs) {
            sb.append(l).append('\n');
        }
        logView.setText(sb.toString());
        logView.setVisibility(sb.length() == 0 ? GONE : VISIBLE);
    }

    static String parseAddress(String line) {
        return Natfrp.parseAddress(line);
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("小窝地址", text));
        } catch (Exception ignore) {
        }
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
