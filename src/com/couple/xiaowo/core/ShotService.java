package com.couple.xiaowo.core;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

/**
 * 单端静默截屏 —— 用无障碍服务的 takeScreenshot()。
 *
 * ── 为什么不是 MediaProjection ──
 *
 * MediaProjection 每次都要用户点「开始录制」，而且录的是整个屏幕（包括别的 App），
 * 还要再搞一层前台服务常驻通知。对一个「让对方看一眼我这边的点单界面」的需求，
 * 代价太大。
 *
 * ── 为什么不是 view.draw(Canvas) ──
 *
 * 见 Shot.java。它只能截到自己的 View 树，看起来"能用"，但：
 *   · App 退到后台时 View 尺寸可能是 0 → 截出来是 null
 *   · 截不到状态栏、对话框、系统弹层，用户看到的是"半张图"
 *   · Activity 正在 recreate / 滚动中间态时画面会糊
 * 所以真正的屏幕截取交给系统。
 *
 * ── 为什么无障碍可以静默截屏 ──
 *
 * Android 11 (API 30) 给 AccessibilityService 加了 takeScreenshot()。
 * 只要服务在运行、配置里声明了 canTakeScreenshot="true"，
 * 调用就会**直接返回 Bitmap，没有任何确认弹窗**。
 * 授权只有一次：用户在系统设置里把这个服务打开，之后永久生效。
 *
 * ── 关键实现细节 ──
 *
 * takeScreenshot() 是**异步回调**（Executor + callback 两个版本，
 * 后者在 API 33 才有）。这里用 Executor 版保证 API 30 就能跑：
 *   takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
 * 回调在 executor 线程，必须自己 post 回调用方要的线程。
 *
 * 服务实例是单例（系统只绑一个）。Shot 通过静态字段拿到它。
 */
public class ShotService extends AccessibilityService {

    /** 当前已连接的服务实例；null 表示用户没开无障碍 */
    private static volatile ShotService instance;

    /** 正在等待结果的一次截屏请求 */
    private static volatile Shot.Result pending;

    public static boolean isReady() {
        return instance != null;
    }

    /** 服务是否在系统里被勾选启用（不要求此刻已连接） */
    public static boolean isEnabledInSettings(Context c) {
        if (Build.VERSION.SDK_INT < 30) {
            return false;
        }
        try {
            String flat = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(flat)) {
                return false;
            }
            ComponentName me = new ComponentName(c, ShotService.class);
            String meFlat = me.flattenToString();
            String meFlatShort = me.flattenToShortString();
            for (String s : flat.split(":")) {
                if (s.equalsIgnoreCase(meFlat) || s.equalsIgnoreCase(meFlatShort)) {
                    return true;
                }
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    /** 跳去无障碍设置页让用户打开本服务 */
    public static void requestEnable(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignore) {
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不监听任何事件：截屏不需要窗口内容，配置里也没开 canRetrieveWindowContent
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    /**
     * 交给 Shot 调用：发起一次截屏。
     *
     * 调用方传入一个 Result，回调里只负责塞图并放行 latch，
     * 线程切换交给 Shot 决定（它可能从 HTTP 工作线程来）。
     */
    static void capture(Shot.Result out) {
        final ShotService svc = instance;
        if (svc == null) {
            out.error = "无障碍截屏服务没连上";
            out.done.countDown();
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            out.error = "系统版本低于 Android 11，无障碍截不了屏";
            out.done.countDown();
            return;
        }
        pending = out;
        try {
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
                    svc.getMainExecutor(), new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            Shot.Result target = pending;
                            if (target == null) {
                                // 不是我们发的请求（例如系统别的调用），忽略
                                if (result != null && result.getHardwareBuffer() != null) {
                                    result.getHardwareBuffer().close();
                                }
                                return;
                            }
                            try {
                                /*
                                 * wrapHardwareBuffer 给的是 HardwareBitmap：
                                 * 它只能用 GPU 绘制，compress() 和 createScaledBitmap()
                                 * 在部分机型上直接抛 IllegalArgumentException。
                                 * 这里立刻转成软件位图（ARGB_8888），后面随便压随便缩。
                                 */
                                Bitmap hw = Bitmap.wrapHardwareBuffer(
                                        result.getHardwareBuffer(), result.getColorSpace());
                                if (hw != null) {
                                    target.bitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    hw.recycle();
                                }
                            } catch (Exception e) {
                                target.error = "截图解码失败：" + e;
                            } finally {
                                try {
                                    result.getHardwareBuffer().close();
                                } catch (Exception ignore) {
                                }
                            }
                            pending = null;
                            target.done.countDown();
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Shot.Result target = pending;
                            if (target != null) {
                                target.error = "系统拒绝截屏，code=" + errorCode
                                        + "（1 表示系统正在截屏，稍后再试）";
                                pending = null;
                                target.done.countDown();
                            }
                        }
                    });
        } catch (Throwable t) {
            out.error = "发起截屏失败：" + t;
            pending = null;
            out.done.countDown();
        }
    }
}
