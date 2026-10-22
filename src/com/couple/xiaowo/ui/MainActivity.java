package com.couple.xiaowo.ui;

import android.widget.TextView;
import com.couple.xiaowo.core.Relay;
import com.couple.xiaowo.core.Mqtt;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.couple.xiaowo.BuildInfo;
import com.couple.xiaowo.core.KeepAlive;
import com.couple.xiaowo.core.Protocol;

/**
 * 两个 App 共用同一个 Activity 类，靠编译期常量 BuildInfo.IS_ORDER 决定角色：
 *  - 小窝·点单 (com.couple.xiaowo.order)  → OrderScreen
 *  - 小窝·接单 (com.couple.xiaowo.recv)   → RecvScreen
 * 这样"两份源码"永远只有一份，维护成本最低。
 */
public class MainActivity extends Activity implements HeartHost {

    private HeartBurstView burstView;
    private BubbleBurstView bubbleView;
    private OrderScreen orderScreen;
    private RecvScreen recvScreen;
    private Theme.Ambient ambient;

    /**
     * 全局唯一的中转订阅。
     *
     * 为什么收归到 Activity：之前在 RecvScreen / OrderScreen 里各自维护，
     * 换页面、切 Tab 就可能被停掉或者状态不同步 ——
     * 用户看到"加入房间了但显示未连接"就是这么来的。
     * 现在只有一份，任何页面要显示状态都从这里读。
     */
    private Mqtt.Sub relaySub;
    private final java.util.List<TextView> relayStatusViews = new java.util.ArrayList<TextView>();
    private String relayLastNote = "";

    /** 让某个 TextView 跟随中转状态刷新 */
    public void bindRelayStatus(TextView tv) {
        if (tv == null) {
            return;
        }
        relayStatusViews.add(tv);
        tv.setText(relayStatusText());
        tv.setTextColor(relaySub != null && relaySub.isOnline()
                ? Theme.adapt(0xFF16A34A) : Theme.adapt(0xFF958DA3));
    }

    private String relayStatusText() {
        String room = Relay.room(this);
        if (room == null || !Relay.enabled(this)) {
            return "中转状态：未配对";
        }
        if (relaySub == null) {
            return "中转状态：准备连接…（房间 " + room + "）";
        }
        if (relaySub.isOnline()) {
            return "中转状态：在线"
                    + (relayLastNote.length() > 0 ? "（" + relayLastNote + "）" : "")
                    + "　房间 " + room;
        }
        return "中转状态：" + (relayLastNote.length() > 0 ? relayLastNote : "重连中…")
                + "　房间 " + room;
    }

    private void notifyRelayViews() {
        final String text = relayStatusText();
        final boolean online = relaySub != null && relaySub.isOnline();
        runOnUiThread(new Runnable() {
            public void run() {
                for (TextView tv : relayStatusViews) {
                    if (tv == null) {
                        continue;
                    }
                    tv.setText(text);
                    tv.setTextColor(Theme.adapt(online ? 0xFF16A34A : 0xFF958DA3));
                }
            }
        });
    }

    /** 重建中转订阅（配对/解绑后调用） */
    public void restartRelay() {
        if (relaySub != null) {
            relaySub.close();
            relaySub = null;
        }
        relayStatusViews.clear();
        startRelay();
    }

    /**
     * 启动中转订阅。
     * 只要配了房间码就常驻 —— 点单端和接单端都需要收（聊天/公告/爱心是双向的）。
     */
    private void startRelay() {
        final String room = Relay.room(this);
        if (room == null || !Relay.enabled(this)) {
            notifyRelayViews();
            return;
        }
        if (relaySub != null && relaySub.isOnline()) {
            return;
        }
        final boolean isRecv = !BuildInfo.IS_ORDER;
        final String myRole = isRecv ? Protocol.ROLE_RECV : Protocol.ROLE_ORDER;
        relayLastNote = "连接中…";
        relaySub = new Mqtt.Sub(room, new Mqtt.Sub.Listener() {
            public void onMessage(String payload) {
                deliverRelay(payload, myRole);
            }

            public void onState(boolean online, String note) {
                relayLastNote = online ? note : note;
                notifyRelayViews();
            }
        });
        relaySub.start();
        notifyRelayViews();
    }

    /** 把中转收到的消息交给对应的界面处理 */
    private void deliverRelay(String raw, final String myRole) {
        final Relay.Wrapped w = Relay.unwrap(raw);
        if (w == null) {
            return;
        }
        /*
         * 必须过滤掉自己发的消息：MQTT 的订阅会把自己 PUBLISH 的消息
         * 也投递回来（同一 clientId 下是如此）。
         * 不过滤的话，点单端会收到自己发出的版本查询，把它当成"对方回包"，
         * 解析出 versionCode=0 —— 实测就是这么翻车的。
         */
        if (w.from.equals(myRole)) {
            return;
        }
        runOnUiThread(new Runnable() {
            public void run() {
                String type = Protocol.typeOf(w.payload);
                if (BuildInfo.IS_ORDER) {
                    if (orderScreen != null) {
                        orderScreen.onRelayMessage(type, w.payload);
                    }
                } else {
                    if (recvScreen != null) {
                        recvScreen.onRelayMessage(type, w.payload);
                    }
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 先把主题定下来，再建任何视图 —— 否则第一帧会是浅色再闪成深色
        Theme.init(this);
        Ui.applyTheme();
        setupWindow();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.adapt(Ui.BG));

        View screen;
        if (BuildInfo.IS_ORDER) {
            orderScreen = new OrderScreen(this, this);
            screen = orderScreen;
        } else {
            recvScreen = new RecvScreen(this, this);
            screen = recvScreen;
        }
        root.addView(screen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        burstView = new HeartBurstView(this);
        root.addView(burstView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 泡泡层在爱心之上，也吃不到点击
        bubbleView = new BubbleBurstView(this);
        root.addView(bubbleView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyInsets(root);
        KeepAliveCard.sync(this, !BuildInfo.IS_ORDER, null);
        maybeAskLocalNetwork();
        AlertCenter.SystemNotify.request(this);
        com.couple.xiaowo.core.Updater.ensureDefault(this);
        setupAmbient();
        startRelay();
        /*
         * 保活开着的话，把一像素窗口也恢复起来。
         * 这个窗口是进程级的（不是 Activity 级的），但 App 被彻底杀掉重进时
         * 需要重新立起来。已经有权限、已经在跑时 start() 是幂等的。
         */
        if (KeepAliveCard.aliveEnabled(this) && com.couple.xiaowo.core.PixelKeeper.canOverlay(this)) {
            com.couple.xiaowo.core.PixelKeeper.start(this);
        }

        // 启动时权限门：缺权限就不让用主界面。
        // 放在 UI 建好之后调用，这样授权回来 recreate 时不会闪。
        PermissionGate.ensure(this);
    }

    /**
     * 环境光自动切换。
     *
     * 只有在「跟随环境光」模式下才注册传感器；切到固定浅/深色时主动注销，
     * 免得白耗电。变化发生时重建 Activity —— 界面是纯代码搭的，
     * 重建比逐控件改色可靠得多，而且这一下本来就是用户能感知的明暗切换。
     */
    private void setupAmbient() {
        if (Theme.mode(this) != Theme.MODE_AUTO) {
            return;
        }
        ambient = new Theme.Ambient(this, new Theme.Ambient.Listener() {
            @Override
            public void onAmbientChanged(boolean nowDark) {
                if (!Theme.applyAuto(MainActivity.this, nowDark)) {
                    return;
                }
                recreate();
            }
        });
        ambient.start();
    }

    /** 主题切换入口：由「我」页的主题卡片调用。 */
    public void onThemePicked(final int mode) {
        Theme.setMode(this, mode);
        if (mode != Theme.MODE_AUTO) {
            recreate();
            return;
        }
        /*
         * 切到「跟随环境光」时，立刻按当前光线收敛一次，
         * 别干等传感器回调（那可能要几秒，用户会以为点了没反应）。
         *
         * 读光必须放到后台线程：Lux.isBright 最多阻塞 900ms，
         * 放在主线程会卡住 UI —— 这一条是查代码时发现的。
         */
        new Thread(new Runnable() {
            public void run() {
                final boolean bright = com.couple.xiaowo.core.Lux.isBright(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        Theme.applyAuto(MainActivity.this, !bright);
                        recreate();
                    }
                });
            }
        }).start();
    }

    private void setupWindow() {
        Window w = getWindow();
        boolean dark = Theme.isDark();
        w.setStatusBarColor(dark ? 0xFF14121B : 0xFFF1EBFF);
        w.setNavigationBarColor(dark ? 0xFF14121B : 0xFFF6F4F9);
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        View decor = w.getDecorView();
        // 深色底要白图标，浅色底要黑图标 —— 这里的 LIGHT_* 标志是"深色文字"的意思，
        // 名字和直觉相反，别搞反了。
        int flags = decor.getSystemUiVisibility();
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    /** Android 15+ 默认强制沉浸式，这里统一手动吃系统栏内边距，保证不被状态栏压住。 */
    private void applyInsets(final View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets ins) {
                    android.graphics.Insets bars = ins.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    v.setPadding(0, bars.top, 0, bars.bottom);
                    return ins;
                }
            });
            root.requestApplyInsets();
        }
    }

    /**
     * Android 16/17 起有了「本地网络」权限：App 想访问同一 Wi-Fi 下的其它设备要用户授权。
     * 老系统上这个权限不存在，requestPermissions 会立刻返回拒绝，不会有任何弹窗。
     */
    private void maybeAskLocalNetwork() {
        if (Build.VERSION.SDK_INT < 36) {
            return;
        }
        try {
            String perm = "android.permission.ACCESS_LOCAL_NETWORK";
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{perm}, 41);
            }
        } catch (Throwable ignore) {
            // 系统还没有这个权限时忽略
        }
    }

    /** 给 OrderScreen 拿泡泡层用 */
    public BubbleBurstView bubbleLayer() {
        return bubbleView;
    }

    @Override
    public void burst(int count) {
        if (burstView != null) {
            burstView.burst(count);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ambient != null) {
            ambient.start();
        }
        /*
         * 用户从授权页回来时再次检查权限。
         * 全部就绪才放行；否则继续弹不可取消的引导框。
         */
        PermissionGate.ensure(this);

        /*
         * 授权悬浮窗回来后，把一像素保活窗拉起来。
         * 这是"默认开启所有保活措施"的一部分 —— 用户一授权，保活就生效。
         */
        if (KeepAliveCard.aliveEnabled(this) && com.couple.xiaowo.core.PixelKeeper.canOverlay(this)) {
            com.couple.xiaowo.core.PixelKeeper.start(this);
        }
        /*
         * AUTO 模式下再异步读一次光校正。
         * 冷启动时 init() 用的是"上次记住的结论"，如果用户换了环境
         * （比如从被窝走到窗边），这里能立刻纠正过来，不用等传感器
         * 的 3 秒限流窗口。
         */
        Theme.syncAutoFromSensor(this, new Runnable() {
            public void run() {
                recreate();
            }
        });
        // 回到前台：把常驻通知恢复成正常状态
        if (KeepAliveCard.aliveEnabled(this)) {
            KeepAlive.update(this, BuildInfo.IS_ORDER ? Protocol.ROLE_ORDER : Protocol.ROLE_RECV,
                    BuildInfo.IS_ORDER ? "猫猫在线，正在等你接单" : "张老师在接单，随时可以点单");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ambient != null) {
            ambient.stop();
        }
        // 退到后台：把常驻通知缩成"后台运行中"，这就是「后台隐藏」
        if (KeepAliveCard.aliveEnabled(this) && KeepAliveCard.hideEnabled(this)) {
            KeepAlive.update(this, BuildInfo.IS_ORDER ? Protocol.ROLE_ORDER : Protocol.ROLE_RECV,
                    "在后台运行中 · 点我回到小窝");
        }
    }

    @Override
    public void onBackPressed() {
        // 规范：返回行为要可预期。在非「厨房」页时，先回到厨房，而不是直接退出。
        if (BuildInfo.IS_ORDER && orderScreen != null && orderScreen.goHomeTab()) {
            return;
        }
        if (!BuildInfo.IS_ORDER && recvScreen != null && recvScreen.goHomeTab()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        AlertCenter.dismissOverlay();
        if (orderScreen != null) {
            orderScreen.shutdown();
        }
        if (recvScreen != null) {
            recvScreen.shutdown();
        }
        super.onDestroy();
    }
}
