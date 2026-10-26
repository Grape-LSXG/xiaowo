package com.couple.xiaowo.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.ImageView;
import android.widget.ScrollView;
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
    /** 当前挂在 root 里的那一屏（切主题时要整屏换掉） */
    private View screenView;
    private FrameLayout root;
    /** 防止一次切换里被传感器的第二节回调重入 */
    private boolean themeSwapping = false;

    /**
     * 网络变化监听。
     *
     * 手机从 Wi-Fi 切到流量（或反过来）时，原来那条 MQTT 长连接其实已经死了，
     * 但我们只能靠读超时才发现 —— 最多 35 秒的空窗，中间订单一律丢。
     * 系统换了默认网络就主动重连，空窗缩到一两秒。
     */
    private android.net.ConnectivityManager.NetworkCallback netCb;
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
    /**
     * 所有显示中转状态的 TextView（弱引用 + 前缀）。
     *
     * 为什么用 WeakHashMap 而不是 ArrayList：
     * 这些 View 属于各自页面，页面重建（切 Tab、主题刷新）后旧 View 就被丢了。
     * 用强引用的 List 会一直攒着已经不在界面上的 TextView；
     * 用弱引用表，谁被回收谁自己消失，任何页面重建后重新 bind 一次即可。
     */
    private final java.util.Map<TextView, String> relayStatusViews =
            new java.util.WeakHashMap<TextView, String>();
    private String relayLastNote = "";

    /** 让某个 TextView 跟随中转状态刷新（默认前缀「🌐 异地中转：」） */
    public void bindRelayStatus(TextView tv) {
        bindRelayStatus(tv, "🌐 异地中转：");
    }

    /** 同上，前缀由调用方决定（如「中转状态：」） */
    public void bindRelayStatus(TextView tv, String prefix) {
        if (tv == null) {
            return;
        }
        relayStatusViews.put(tv, prefix == null ? "" : prefix);
        boolean online = relayOnline();
        tv.setText((prefix == null ? "" : prefix) + relayStatusText());
        tv.setTextColor(Theme.adapt(online ? 0xFF16A34A : 0xFF958DA3));
    }

    /** 中转是否真的连上了 broker —— 各页面显示「在线/重连中」都读这里 */
    public boolean relayOnline() {
        return relaySub != null && relaySub.isOnline();
    }

    /**
     * 不带前缀的状态短语。
     *
     * 这里**只看真实的订阅状态**，不看"存没存过房间码"：
     * 原来首页卡片是建页面时写死的一句「未配对」，之后再没人改它，
     * 所以配对了也永远显示未配对（用户报的就是这个）。
     */
    private String relayStatusText() {
        String room = Relay.room(this);
        if (room == null || !Relay.enabled(this)) {
            return "未配对";
        }
        if (relaySub == null) {
            return "准备连接…（房间 " + room + "）";
        }
        if (relaySub.isOnline()) {
            return "在线 · 房间 " + room;
        }
        return (relayLastNote.length() > 0 ? relayLastNote : "重连中…") + "　房间 " + room;
    }

    private void notifyRelayViews() {
        final String text = relayStatusText();
        final boolean online = relayOnline();
        runOnUiThread(new Runnable() {
            public void run() {
                for (java.util.Map.Entry<TextView, String> e : relayStatusViews.entrySet()) {
                    TextView tv = e.getKey();
                    if (tv == null) {
                        continue;
                    }
                    String prefix = e.getValue();
                    tv.setText((prefix == null ? "" : prefix) + text);
                    tv.setTextColor(Theme.adapt(online ? 0xFF16A34A : 0xFF958DA3));
                }
                // 除了写死的状态行，页面自己算的文案（点单端顶部的「⇄ 直连」）也要跟着变
                if (orderScreen != null) {
                    orderScreen.onRelayState();
                }
                if (recvScreen != null) {
                    recvScreen.onRelayState();
                }
            }
        });
    }

    /**
     * 重建中转订阅（配对/解绑后调用）。
     *
     * 注意**不能**再清空 relayStatusViews：清掉之后这些 View 就再也不会
     * 被刷新了 —— 那正是「首页一直显示未配对」的第二个成因。
     */
    public void restartRelay() {
        if (relaySub != null) {
            relaySub.close();
            relaySub = null;
        }
        relayLastNote = "连接中…";
        startRelay();
        watchNetworkChange();
        notifyRelayViews();
    }

    /**
     * 启动中转订阅。
     * 只要配了房间码就常驻 —— 点单端和接单端都需要收（聊天/公告/爱心是双向的）。
     */
    private void startRelay() {
        // 每次建连前刷一次自建服务器配置：用户改了地址不用重启 App
        Mqtt.configure(this);
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

    /**
     * 监听默认网络切换：一换网就把中转订阅重连一次。
     *
     * 只在"当前没连上"时才重启，避免每次网络波动都白掐一条好连接。
     */
    private void watchNetworkChange() {
        if (netCb != null) {
            return;
        }
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            netCb = new android.net.ConnectivityManager.NetworkCallback() {
                public void onAvailable(android.net.Network network) {
                    if (relaySub != null && !relaySub.isOnline()) {
                        restartRelay();
                    }
                }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Throwable ignore) {
            // 老 ROM 上可能没有这个 API（http://developer.android.com 说 24 起才有），忽略即可
            netCb = null;
        }
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

        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.adapt(Ui.BG));
        buildScreens();
        View screen = screenView;

        burstView = new HeartBurstView(this);
        root.addView(burstView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 泡泡层在爱心之上，也吃不到点击
        bubbleView = new BubbleBurstView(this);
        root.addView(bubbleView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyInsets(root);
        /*
         * 保活总闸：前台服务 + 一像素窗一起拉起来。
         * 这一步不再有"用户开没开"的判断 —— 保活是不可关闭的，
         * KeepAliveCard.sync 内部会把所有手段补齐（缺权限时返回 true）。
         */
        KeepAliveCard.sync(this, !BuildInfo.IS_ORDER, null);
        // 定时自检：每 5 分钟确认服务/一像素窗还在，掉了自己拉回来
        com.couple.xiaowo.core.Keep.startWatchdog(this);
        maybeAskLocalNetwork();
        AlertCenter.SystemNotify.request(this);
        com.couple.xiaowo.core.Updater.ensureDefault(this);
        // 清掉老版本留下的可能不完整的 APK 缓存（一次性）
        com.couple.xiaowo.core.Updater.purgeStaleDownloads(this);
        setupAmbient();
        /*
         * 冷启动时按当前光线校正一次主题。
         * 只在这里做（不在 onResume）—— 每次回前台都读光会导致不必要的 recreate/闪烁，
         * 而 Ambient 传感器在 resume 后本就会按滞回规则自己跟上。
         */
        Theme.syncAutoFromSensor(this, new Runnable() {
            public void run() {
                applyThemeInPlace();
            }
        });
        startRelay();

        // 启动时权限门：缺权限就弹引导。
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
            public void onAmbientChanged(final boolean nowDark) {
                if (!Theme.applyAuto(MainActivity.this, nowDark)) {
                    return;
                }
                /*
                 * 不再直接 recreate()。
                 *
                 * 直接重建会在屏幕上"啪"地换色，光线一抖就闪；
                 * 现在先盖一层 300ms 的日/月遮罩，把重建藏在遮罩后面。
                 */
                ThemeSwitchOverlay.play(MainActivity.this, nowDark, new Runnable() {
                    public void run() {
                        applyThemeInPlace();
                    }
                });
            }
        });
        ambient.start();
    }

    /** 主题切换入口：由「我」页的主题卡片调用。 */
    public void onThemePicked(final int mode) {
        Theme.setMode(this, mode);
        if (mode != Theme.MODE_AUTO) {
            applyThemeInPlace();
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
                        applyThemeInPlace();
                    }
                });
            }
        }).start();
    }

    /** 按角色建当前这一屏（冷启动、切主题时都走这里） */
    private void buildScreens() {
        if (BuildInfo.IS_ORDER) {
            orderScreen = new OrderScreen(this, this);
            screenView = orderScreen;
        } else {
            recvScreen = new RecvScreen(this, this);
            screenView = recvScreen;
        }
        root.addView(screenView, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /**
     * 原地换主题 —— **不再 recreate()**。
     *
     * 为什么不能只改根布局背景：整屏的每个背景/字色都是建视图那一刻
     * 由 Ui 工厂算好、直接写进 View 里的（204 处字面量），
     * 运行时没有"主题变量"可读，所以只能重建视图。
     *
     * 但重建视图 ≠ 必须重建 Activity：
     *   · recreate() 会把窗口、Activity、服务全重启一次，屏幕会闪一下、
     *     Tab 之外的状态全丢，用户看到的就是"刷新了"；
     *   · 这里只在同一个窗口里把内容那一屏换掉：先把旧画面截图盖在最上层，
     *     换完再淡出 —— 颜色是"渐变过去"的，滚动位置也按原位恢复。
     */
    public void applyThemeInPlace() {
        if (themeSwapping || root == null) {
            recreate();
            return;
        }
        themeSwapping = true;
        try {
            Ui.applyTheme();
            setupWindow();
            root.setBackgroundColor(Theme.adapt(Ui.BG));

            // 1) 记下当前滚动位置（按 View 树顺序，恢复时一一对应）
            final java.util.List<Integer> scrolls = new java.util.ArrayList<Integer>();
            collectScroll(root, scrolls);
            // 2) 旧画面截图：切换过程中盖在上面淡出，避免任何"白闪"
            Bitmap veil = snapshot(root);

            // 3) 拆旧建新（服务端口短暂释放，随即重新绑定）
            if (orderScreen != null) {
                orderScreen.shutdown();
                orderScreen = null;
            }
            if (recvScreen != null) {
                recvScreen.shutdown();
                recvScreen = null;
            }
            if (screenView != null) {
                root.removeView(screenView);
                screenView = null;
            }
            buildScreens();
            restoreScroll(root, scrolls);

            // 4) 淡出旧画面
            if (veil != null) {
                final ImageView layer = new ImageView(this);
                layer.setImageBitmap(veil);
                layer.setScaleType(ImageView.ScaleType.FIT_XY);
                root.addView(layer, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                layer.animate().alpha(0f).setDuration(240).withEndAction(new Runnable() {
                    public void run() {
                        root.removeView(layer);
                        veil.recycle();
                    }
                });
            }
        } finally {
            themeSwapping = false;
        }
    }

    /** 把树里所有 ScrollView 的滚动位置按顺序记下来 */
    private void collectScroll(View v, java.util.List<Integer> out) {
        if (v == null) {
            return;
        }
        if (v instanceof ScrollView) {
            out.add(Integer.valueOf(((ScrollView) v).getScrollY()));
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectScroll(g.getChildAt(i), out);
            }
        }
    }

    /** 按同样顺序把滚动位置还回去（数量对不上就按少的来） */
    private void restoreScroll(View v, java.util.List<Integer> ys) {
        if (v == null || ys.isEmpty()) {
            return;
        }
        final java.util.List<ScrollView> found = new java.util.ArrayList<ScrollView>();
        gatherScrolls(v, found);
        for (int i = 0; i < found.size() && i < ys.size(); i++) {
            found.get(i).scrollTo(0, ys.get(i).intValue());
        }
    }

    private void gatherScrolls(View v, java.util.List<ScrollView> out) {
        if (v == null) {
            return;
        }
        if (v instanceof ScrollView) {
            out.add((ScrollView) v);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                gatherScrolls(g.getChildAt(i), out);
            }
        }
    }

    /** 把当前界面画到一张位图上（拿不到尺寸就返回 null，宁可不淡出也不崩） */
    private Bitmap snapshot(View v) {
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0) {
                return null;
            }
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            v.draw(new Canvas(bmp));
            return bmp;
        } catch (Throwable t) {
            return null;
        }
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
         * 每次回到前台都检查权限（按需求：切回软件就检查一次）。
         *
         * ensure() 内部是幂等的：权限齐全时什么都不做；
         * 有缺失才弹引导框，且已有框在显示时不会叠加。
         */
        PermissionGate.ensure(this);

        /*
         * 每次回到前台也检查一次更新。
         * 静默进行（不打断界面），发现新版才提示/下载。
         * 用 updateCheckOnResume() 而不是 run()，避免和手动检查互相干扰。
         */
        GitHubCard.checkOnResume(this, !BuildInfo.IS_ORDER);

        /*
         * 授权悬浮窗回来后，把一像素保活窗拉起来。
         * 这是"默认开启所有保活措施"的一部分 —— 用户一授权，保活就生效。
         */
        /*
         * 每次回到前台都重新同步一次保活 —— 用户可能在系统设置里撤销了权限、
         * 或者 ROM 把服务清了。这里补回来，做到「和权限一样经常检查」。
         */
        KeepAliveCard.sync(this, !BuildInfo.IS_ORDER, null);
        /*
         * 不再每次回前台都读光校色。
         *
         * 原来这里会导致：只要光线判断结果和执行时不一致就 recreate()，
         * 表现就是"每次从后台切回来屏幕闪一下"。
         * 现在交给 Ambient 传感器在 resume 后自行按滞回规则判断（阈值放宽 + 8 秒节流），
         * 只在真的跨过阈值时才切换一次。
         */
        // 回到前台：把常驻通知恢复成正常状态
        KeepAlive.update(this, BuildInfo.IS_ORDER ? Protocol.ROLE_ORDER : Protocol.ROLE_RECV,
                BuildInfo.IS_ORDER ? "猫猫在线，正在等你接单" : "张老师在接单，随时可以点单");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ambient != null) {
            ambient.stop();
        }
        // 退到后台：把常驻通知缩成"后台运行中"（后台隐藏固定开启）
        KeepAlive.update(this, BuildInfo.IS_ORDER ? Protocol.ROLE_ORDER : Protocol.ROLE_RECV,
                "在后台运行中 · 点我回到小窝");
    }

    /**
     * 系统文件选择器返回（聊天记录导入用）。
     *
     * 聊天记录导入要先选文件，而选文件必须走 startActivityForResult，
     * 所以把结果转发给 ChatIO 处理，避免在这里掺业务逻辑。
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (com.couple.xiaowo.core.ChatIO.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
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
        /*
         * 必须关掉中转订阅。
         *
         * 原来漏了这一步：Activity 重建（主题切换、授权返回）时旧的 Sub 线程
         * 还在跑，还持有旧的 Listener —— 消息会投递到已经销毁的界面上，
         * 而且每重建一次就多一个 MQTT 长连接（公共 broker 那边也会攒连接）。
         */
        if (relaySub != null) {
            try {
                relaySub.close();
            } catch (Exception ignore) {
            }
            relaySub = null;
        }
        if (netCb != null) {
            try {
                android.net.ConnectivityManager cm =
                        (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(netCb);
                }
            } catch (Throwable ignore) {
            }
            netCb = null;
        }
        relayStatusViews.clear();
        if (orderScreen != null) {
            orderScreen.shutdown();
        }
        if (recvScreen != null) {
            recvScreen.shutdown();
        }
        super.onDestroy();
    }
}
