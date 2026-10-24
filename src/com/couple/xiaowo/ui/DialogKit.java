package com.couple.xiaowo.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 统一风格的对话框 —— 取代系统那个白底黑字的 AlertDialog。
 *
 * 设计依据（颜色/圆角/组件全部取自 {@link Ui} 里已有的令牌，不新造 hex）：
 *   · Soft UI Evolution：白色卡片 + 20dp 圆角 + 柔和阴影，
 *     标题条用 Ui.PRIMARY → Ui.PRIMARY_DEEP 的渐变（比小圆点更压得住标题，也顺手当关闭区的视觉锚点）
 *   · UX 规范 Dialog：主次按钮分色、正文对比度够、点遮罩可关闭、进场 240ms 缓动
 *   · 触控可达性：所有按钮 / 列表项高度 ≥ 48dp，点按有 0.97 缩放反馈
 *   · 布局：卡片宽 = 屏宽 88%，最宽不超过 560dp（平板/折叠屏不会横贯全屏）
 *
 * 用法（Lead 接线）：
 * <pre>
 *   DialogKit.alert(act, "张老师的公告", text);
 *   DialogKit.confirm(act, "删掉这道菜？", "删了就没了", "删掉", () -&gt; doDelete());
 *   DialogKit.input(act, "改名", "菜名", old, text -&gt; rename(text));
 *   DialogKit.choose(act, "选一份菜单", names, current, i -&gt; pick(i));
 *   DialogKit.custom(act, "新增一道菜", formView, "加到「想你了」", () -&gt; save());
 * </pre>
 *
 * 全部方法返回 {@link Dialog}，调用方可以随时 dismiss；想走退场动画就调
 * {@link #dismiss(Dialog)}（等价于 {@link Dialog#dismiss()}，只是多一段淡出）。
 * 回调语义：alert 没有回调；confirm/input/choose/custom 只在用户「确认 / 选中」时回调，
 * 取消、点遮罩、返回键都只关闭不回调。
 */
public final class DialogKit {

    // ---------------------------------------------------------------- 回调

    /** 单行输入框的回调：用户点了「确定」（或按键盘完成键）才回调，取消不回调 */
    public interface InputCallback {
        void onInput(String text);
    }

    /** 单选列表的回调：点哪一项就回调它的下标，取消不回调 */
    public interface ChooseCallback {
        void onChoose(int index);
    }

    // ---------------------------------------------------------------- 尺寸 / 动效

    /** 卡片宽 = 屏宽 × 该比例 */
    private static final float SCREEN_RATIO = 0.88f;
    /** 再宽也不超过这个 dp */
    private static final float MAX_WIDTH_DP = 560f;
    private static final float CARD_RADIUS_DP = 20f;
    /** 触摸目标下限 */
    private static final float MIN_TOUCH_DP = 48f;
    /** 进场：缩放 0.94 → 1.0 + 淡入 */
    private static final int DUR_IN = 240;
    /** 退场：略微缩回 + 淡出 */
    private static final int DUR_OUT = 150;
    /** 底部面板进场：上滑 + 遮罩淡入 */
    private static final int SHEET_IN = 260;
    /** 面板内容最多占屏高（剩下的留给"我看得见背景里的列表"） */
    private static final float SHEET_MAX_RATIO = 0.68f;
    /** 下滑超过这个距离就关（不到则弹回去） */
    private static final float SHEET_DRAG_CLOSE_DP = 80f;

    /** Dialog → 卡片，退场动画的目标；弱引用，不把窗口 / Activity 持有住 */
    private static final WeakHashMap<Dialog, View> CARDS = new WeakHashMap<Dialog, View>();
    /** 正在退场的 Dialog，防连点导致回调跑两遍 */
    private static final Set<Dialog> CLOSING =
            Collections.newSetFromMap(new WeakHashMap<Dialog, Boolean>());

    /** 底部面板：Dialog → {卡片, 遮罩}，退场要用它做上滑而不是缩小 */
    private static final WeakHashMap<Dialog, View[]> SHEETS = new WeakHashMap<Dialog, View[]>();

    private DialogKit() {
    }

    // ================================================================ 公开 API

    /** 只有「知道了」的提示框 */
    public static Dialog alert(Activity act, String title, String message) {
        if (act == null) {
            return null;
        }
        Frame f = create(act, title);
        if (!isEmpty(message)) {
            f.body.addView(messageView(act, message), fullWidth());
        }
        f.body.addView(actionRow(act, null,
                primaryAction(act, "知道了", f.dialog, null)), topMargin(act, 18));
        return present(act, f, true);
    }

    /** 「取消 / 确定」确认框；先关框再回调，回调里可以放心再弹别的框 */
    public static Dialog confirm(Activity act, String title, String message,
                                 String okText, final Runnable onOk) {
        if (act == null) {
            return null;
        }
        Frame f = create(act, title);
        if (!isEmpty(message)) {
            f.body.addView(messageView(act, message), fullWidth());
        }
        TextView cancel = secondaryAction(act, "取消", f.dialog);
        TextView ok = primaryAction(act, isEmpty(okText) ? "确定" : okText, f.dialog, onOk);
        f.body.addView(actionRow(act, cancel, ok), topMargin(act, 18));
        return present(act, f, true);
    }

    /** 单行输入框。回调给的是原始文本（不 trim，空串也照常回调，要不要拦由调用方定） */
    public static Dialog input(Activity act, String title, String hint,
                               String prefill, final InputCallback cb) {
        if (act == null) {
            return null;
        }
        final Frame f = create(act, title);
        final Activity a = act;

        final EditText et = new EditText(act);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTextColor(Theme.adapt(Ui.TEXT));
        et.setHintTextColor(Ui.TEXT_MUTED);
        et.setHint(isEmpty(hint) ? "" : hint);
        if (!isEmpty(prefill)) {
            et.setText(prefill);
            et.setSelection(et.getText().length());
        }
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setPadding(Ui.dpi(act, 14), Ui.dpi(act, 12), Ui.dpi(act, 14), Ui.dpi(act, 12));
        et.setMinHeight(Ui.dpi(act, MIN_TOUCH_DP));
        et.setBackground(Ui.round(Ui.BG, Ui.dp(act, 12f),
                Math.max(1, Ui.dpi(act, 0.8f)), Ui.LINE));
        f.body.addView(et, fullWidth());

        // 真正干活的部分；关闭由按钮 / 键盘回调各自负责
        final Runnable submit = new Runnable() {
            public void run() {
                String text = et.getText() == null ? "" : et.getText().toString();
                if (cb != null) {
                    cb.onInput(text);
                }
            }
        };

        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean done = actionId == EditorInfo.IME_ACTION_DONE
                        || actionId == EditorInfo.IME_ACTION_NEXT
                        || actionId == EditorInfo.IME_ACTION_GO
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.hasNoModifiers());
                if (!done) {
                    return false;
                }
                if (close(f.dialog)) {
                    submit.run();
                }
                return true;
            }
        });

        TextView cancel = secondaryAction(act, "取消", f.dialog);
        TextView ok = primaryAction(act, "确定", f.dialog, submit);
        f.body.addView(actionRow(act, cancel, ok), topMargin(act, 18));

        Dialog dlg = present(act, f, false);
        // 弹框后直接聚焦输入框并拉起键盘，少一次点击
        et.requestFocus();
        final EditText target = et;
        Window w = dlg.getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        et.post(new Runnable() {
            public void run() {
                try {
                    InputMethodManager imm = (InputMethodManager)
                            a.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Exception ignore) {
                }
            }
        });
        return dlg;
    }

    /** 单选列表；currentIndex 那项高亮打勾，点哪项立刻关闭并回调 */
    public static Dialog choose(Activity act, String title, String[] options,
                                int currentIndex, final ChooseCallback cb) {
        if (act == null) {
            return null;
        }
        if (options == null || options.length == 0) {
            return alert(act, title, "暂时没有可选项");
        }
        final Frame f = create(act, title);
        final ChooseCallback callback = cb;

        LinearLayout list = Ui.vertical(act);
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            boolean selected = i == currentIndex;

            LinearLayout row = Ui.horizontal(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dpi(act, 14), Ui.dpi(act, 12), Ui.dpi(act, 12), Ui.dpi(act, 12));
            row.setMinimumHeight(Ui.dpi(act, MIN_TOUCH_DP));
            row.setBackground(selected
                    ? Ui.round(Ui.PRIMARY_SOFT, Ui.dp(act, 12f),
                            Math.max(1, Ui.dpi(act, 1f)), withAlpha(Ui.PRIMARY, 0.55f))
                    : Ui.round(Ui.BG, Ui.dp(act, 12f)));

            TextView label = Ui.tv(act, options[i] == null ? "" : options[i], 14.5f,
                    selected ? Ui.PRIMARY_DEEP : Ui.TEXT, selected);
            label.setMaxLines(3);
            label.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (selected) {
                TextView check = Ui.tv(act, "✓", 12, Ui.CARD, true);
                check.setGravity(Gravity.CENTER);
                check.setBackground(Ui.round(Ui.PRIMARY, Ui.dp(act, 999f)));
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                        Ui.dpi(act, 22), Ui.dpi(act, 22));
                cLp.leftMargin = Ui.dpi(act, 10);
                row.addView(check, cLp);
            }

            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (close(f.dialog) && callback != null) {
                        callback.onChoose(index);
                    }
                }
            });
            Ui.pressable(row);

            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                rLp.topMargin = Ui.dpi(act, 8);
            }
            list.addView(row, rLp);
        }

        // 选项多的时候限高滚动，别把框顶到屏幕外面去
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        int wanted = Ui.dpi(act, 56) * options.length;
        int maxH = Math.max(Ui.dpi(act, 110), Math.min(wanted, (int) (screenH * 0.46f)));
        MaxHeightScroll scroll = new MaxHeightScroll(act, maxH);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        f.body.addView(scroll, fullWidth());

        TextView cancel = secondaryAction(act, "取消", f.dialog);
        f.body.addView(actionRow(act, null, cancel), topMargin(act, 16));
        return present(act, f, true);
    }

    /**
     * 任意自定义内容（表单等）+ 主按钮。
     * okText 为空时只显示一个「关闭」；内容超高会自动变成框内滚动。
     */
    public static Dialog custom(Activity act, String title, View content,
                                String okText, final Runnable onOk) {
        if (act == null) {
            return null;
        }
        Frame f = create(act, title);

        if (content != null) {
            int screenH = act.getResources().getDisplayMetrics().heightPixels;
            MaxHeightScroll scroll = new MaxHeightScroll(act, (int) (screenH * 0.5f));
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            f.body.addView(scroll, fullWidth());
        }

        boolean hasOk = !isEmpty(okText);
        TextView cancel = secondaryAction(act, hasOk ? "取消" : "关闭", f.dialog);
        TextView ok = hasOk ? primaryAction(act, okText, f.dialog, onOk) : null;
        f.body.addView(actionRow(act, hasOk ? cancel : null, hasOk ? ok : cancel),
                topMargin(act, 18));
        return present(act, f, false);
    }

    /**
     * 自下而上面板（bottom sheet）—— 选规格、切规格、囤冰箱这类"顺着当前这一行往下钻一层"的选择用它。
     *
     * 为什么不是中间弹框：中间框会把注意力从列表上抽走，还正好盖住"我刚点的是哪道菜"；
     * 从屏幕下缘推上来的面板里，手指本来就在下半屏，动线更顺，也留出上半屏给用户确认上下文
     * （Material / iOS 的通行做法）。
     *
     * 视觉：22dp 顶部圆角、卡片同色、顶部一根把手；标题左、✕ 右；内容超过屏高 68% 时自己滚。
     * 动效：260ms Decelerate 上滑，遮罩同步淡入；退场反向滑出。
     * 手势：点遮罩关、按住把手（含标题行）下滑超过 80dp 关、点 ✕ 关、返回键关。
     * 颜色全部走 Ui 令牌，所以深浅色切换后自动跟主题。
     *
     * @param content 面板主体（自己用 Ui.vertical + Ui.pill 拼），已自带内边距的话请传 0 内边距容器
     */
    public static Dialog sheet(Activity act, String title, View content) {
        if (act == null) {
            return null;
        }

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final FrameLayout root = new FrameLayout(act);

        // 遮罩自己画：窗口 dim 是一步到位的，跟不上面板滑动，观感很硬
        final View scrim = new View(act);
        scrim.setBackgroundColor(0x77000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                close(dlg);
            }
        });
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout card = Ui.vertical(act);
        card.setOrientation(LinearLayout.VERTICAL);
        // 只有上面两角是圆的：底边贴着屏幕底，不需要圆角
        card.setBackground(Ui.roundTop(Ui.CARD, Ui.dp(act, 22f)));
        card.setElevation(Ui.dp(act, 16f));
        card.setClipChildren(false);
        card.setClipToPadding(false);

        // 把手：告诉用户"这块可以往下拽"
        View grip = new View(act);
        grip.setBackground(Ui.round(0xFFD8D2E4, Ui.dp(act, 2f)));
        LinearLayout.LayoutParams gripLp = new LinearLayout.LayoutParams(
                Ui.dpi(act, 38), Ui.dpi(act, 4));
        gripLp.gravity = Gravity.CENTER_HORIZONTAL;
        gripLp.topMargin = Ui.dpi(act, 9);
        card.addView(grip, gripLp);

        // 标题行：左标题 + 右 ✕（触控区 40dp，不靠字号撑）
        LinearLayout head = Ui.horizontal(act);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dpi(act, 20), Ui.dpi(act, 6), Ui.dpi(act, 12), 0);
        TextView titleView = Ui.tv(act, isEmpty(title) ? "小窝" : title, 16f, Ui.TEXT, true);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(titleView, titleLp);
        TextView x = Ui.tv(act, "✕", 15f, Ui.TEXT_MUTED, false);
        x.setGravity(Gravity.CENTER);
        x.setContentDescription("关闭");
        x.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                close(dlg);
            }
        });
        head.addView(x, new LinearLayout.LayoutParams(Ui.dpi(act, 40), Ui.dpi(act, 40)));
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 内容区：超屏就滚，永远留出 32% 屏高让用户看见背景
        int screenH = act.getResources().getDisplayMetrics().heightPixels;
        MaxHeightScroll scroll = new MaxHeightScroll(act, Math.round(screenH * SHEET_MAX_RATIO));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(Ui.dpi(act, 20), Ui.dpi(act, 6), Ui.dpi(act, 20), 0);
        if (content != null) {
            scroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部留白：避开手势条 / 导航栏，否则最后一行会被系统条压住
        View space = new View(act);
        int spaceH = Ui.dpi(act, 14) + navBottom(act);
        card.addView(space, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, spaceH));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.BOTTOM;
        root.addView(card, cardLp);

        // —— 下滑关闭：把手和标题行当拖拽区（面板主体要能正常滚动，不接管那里的触摸）——
        sheetDrag(dlg, card, head);
        sheetDrag(dlg, card, grip);

        dlg.setContentView(root);
        dlg.setCanceledOnTouchOutside(false); // 遮罩点击由 scrim 自己处理，窗口这一层别再插一脚
        dlg.setCancelable(true);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            // 不加 FLAG_DIM_BEHIND：遮罩要跟面板同步淡入，不能一步到位
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }

        SHEETS.put(dlg, new View[]{card, scrim});

        if (act.isFinishing()) {
            return dlg;
        }
        // 先放到屏幕外，show 之后按真实高度滑上来，避免"闪一下再弹"
        card.setTranslationY(Ui.dp(act, 900f));
        dlg.show();
        card.post(new Runnable() {
            public void run() {
                float h = card.getHeight() > 0 ? card.getHeight() : Ui.dp(act, 460f);
                card.setTranslationY(h);
                card.animate().translationY(0f)
                        .setDuration(SHEET_IN)
                        .setInterpolator(new DecelerateInterpolator(1.5f))
                        .start();
                scrim.animate().alpha(1f).setDuration(SHEET_IN - 40).start();
            }
        });
        return dlg;
    }

    /** 带退场动画关闭（等价于 Dialog#dismiss，只是多一段淡出）；重复调用安全 */
    public static void dismiss(Dialog dlg) {
        close(dlg);
    }

    /**
     * 不可取消的确认框：返回键 / 点外部都不能关闭，且**只有一个主按钮，没有取消键**。
     * 用于启动时的强制权限引导 —— 权限没配好就不让进主界面。
     */
    public static Dialog customBlocking(Activity act, String title, String bodyText,
                                        String okText, final Runnable onOk) {
        // 用 custom 但把"取消"按钮去掉：直接走只有主按钮的构造
        Dialog dlg = customSingle(act, title, okOnlyView(act, bodyText), okText,
                new Runnable() {
                    @Override
                    public void run() {
                        if (onOk != null) {
                            onOk.run();
                        }
                    }
                });
        if (dlg != null) {
            dlg.setCancelable(false);
            dlg.setCanceledOnTouchOutside(false);
            // 锁死返回键：权限没配好不能跳过
            dlg.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(android.content.DialogInterface d, int keyCode,
                                     android.view.KeyEvent event) {
                    return keyCode == android.view.KeyEvent.KEYCODE_BACK;
                }
            });
        }
        return dlg;
    }

    /** 只有单个主按钮的对话框（没有取消/关闭按钮），供阻断式场景用 */
    public static Dialog customSingle(Activity act, String title, View content,
                                      String okText, final Runnable onOk) {
        if (act == null) {
            return null;
        }
        Frame f = create(act, title);
        if (content != null) {
            int screenH = act.getResources().getDisplayMetrics().heightPixels;
            MaxHeightScroll scroll = new MaxHeightScroll(act, (int) (screenH * 0.5f));
            scroll.setVerticalScrollBarEnabled(false);
            scroll.addView(content, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            f.body.addView(scroll, fullWidth());
        }
        String ok = isEmpty(okText) ? "知道了" : okText;
        TextView primary = primaryAction(act, ok, f.dialog, onOk);
        f.body.addView(primary, topMargin(act, 18));
        return present(act, f, false);
    }

    private static View okOnlyView(Activity act, String bodyText) {
        LinearLayout box = Ui.vertical(act);
        box.setPadding(Ui.dpi(act, 8), Ui.dpi(act, 4), Ui.dpi(act, 8), Ui.dpi(act, 4));
        /*
         * 传空就真的什么都不显示 —— 不要再塞一句默认文案。
         * 有些权限（比如静默截屏）不需要解释，只需要一个「去开启」，
         * 多一行说明反而让人犹豫。
         */
        if (bodyText != null && bodyText.length() > 0) {
            box.addView(Ui.tv(act, bodyText, 12, Ui.TEXT_SUB, false));
        }
        return box;
    }

    // ================================================================ 骨架

    /** 一整套对话框的骨架：窗口 + 白色卡片 + 渐变标题条 + 正文容器 */
    private static final class Frame {
        Dialog dialog;
        FrameLayout wrap;
        LinearLayout card;
        LinearLayout body;
    }

    private static Frame create(Activity act, String title) {
        Frame f = new Frame();

        Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        f.dialog = dlg;

        FrameLayout wrap = new FrameLayout(act);
        wrap.setClipChildren(false);
        // 上下留白给柔和阴影落点，也保证卡片不贴屏幕边
        int padH = Ui.dpi(act, 16);
        wrap.setPadding(padH, Ui.dpi(act, 26), padH, Ui.dpi(act, 26));
        f.wrap = wrap;

        LinearLayout card = Ui.vertical(act);
        card.setClipChildren(false);
        card.setBackground(Ui.round(Ui.CARD, Ui.dp(act, CARD_RADIUS_DP),
                Math.max(1, Ui.dpi(act, 0.6f)), Ui.LINE));
        card.setElevation(Ui.dp(act, 14f));
        f.card = card;

        // 标题条：PRIMARY → PRIMARY_DEEP 渐变，只有上面两角跟着卡片圆
        FrameLayout band = new FrameLayout(act);
        GradientDrawable bandBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{Ui.PRIMARY, Ui.PRIMARY_DEEP});
        bandBg.setShape(GradientDrawable.RECTANGLE);
        float r = Ui.dp(act, CARD_RADIUS_DP);
        bandBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        band.setBackground(bandBg);
        band.setClipChildren(false);

        TextView titleView = Ui.tv(act, isEmpty(title) ? "小窝" : title, 16.5f, Ui.CARD, true);
        titleView.setPadding(Ui.dpi(act, 20), Ui.dpi(act, 16), Ui.dpi(act, 20), Ui.dpi(act, 16));
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        band.addView(titleView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(band, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = Ui.vertical(act);
        body.setPadding(Ui.dpi(act, 20), Ui.dpi(act, 18), Ui.dpi(act, 20), Ui.dpi(act, 20));
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        f.body = body;

        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        CARDS.put(dlg, card);
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                if (d instanceof Dialog) {
                    CARDS.remove(d);
                }
            }
        });
        return f;
    }

    /** 配置窗口、挂上内容、播放进场动画；Activity 已经在关就只构造不显示 */
    private static Dialog present(Activity act, Frame f, boolean outsideCancel) {
        Dialog dlg = f.dialog;
        dlg.setContentView(f.wrap);
        dlg.setCanceledOnTouchOutside(outsideCancel);

        Window w = dlg.getWindow();
        if (w != null) {
            // 窗口本体全透明，圆角与阴影全靠卡片自己画
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.42f);
            w.setGravity(Gravity.CENTER);
            int screenW = act.getResources().getDisplayMetrics().widthPixels;
            int cardW = Math.min(Math.round(screenW * SCREEN_RATIO), Ui.dpi(act, MAX_WIDTH_DP));
            int padH = Ui.dpi(act, 16);
            // 窗口比卡片多出两侧阴影留白，卡片本身正好是 88% / 560dp 上限
            w.setLayout(cardW + padH * 2, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        if (act.isFinishing()) {
            return dlg;
        }
        dlg.show();
        animateIn(f.card);
        return dlg;
    }

    /** 进场：0.94 → 1.0 + 淡入，240ms，Decelerate */
    private static void animateIn(View card) {
        card.setAlpha(0f);
        card.setScaleX(0.94f);
        card.setScaleY(0.94f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(DUR_IN)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();
    }

    /**
     * 关掉一个 Dialog，返回 true 表示这一次是「真正开始关闭」。
     * 正在退场时再次调用返回 false —— 连点两次不会把回调跑两遍。
     */
    private static boolean close(final Dialog dlg) {
        if (dlg == null) {
            return false;
        }
        if (!CLOSING.add(dlg)) {
            return false;
        }
        final View[] sheet = SHEETS.remove(dlg);
        if (sheet != null) {
            final View scard = sheet[0];
            final View sscrim = sheet[1];
            float h = scard.getHeight() > 0 ? scard.getHeight()
                    : Ui.dp(scard.getContext(), 460f);
            scard.animate().translationY(h)
                    .setDuration(DUR_OUT + 70)
                    .setInterpolator(new AccelerateInterpolator(1.2f))
                    .start();
            sscrim.animate().alpha(0f)
                    .setDuration(DUR_OUT + 70)
                    .withEndAction(new Runnable() {
                        public void run() {
                            try {
                                dlg.dismiss();
                            } catch (Exception ignore) {
                            }
                        }
                    }).start();
            return true;
        }

        final View card = CARDS.remove(dlg);
        if (card == null || !dlg.isShowing()) {
            try {
                dlg.dismiss();
            } catch (Exception ignore) {
            }
            return true;
        }
        card.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f)
                .setDuration(DUR_OUT)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    public void run() {
                        try {
                            dlg.dismiss();
                        } catch (Exception ignore) {
                        }
                    }
                }).start();
        return true;
    }

    // ================================================================ 零件

    private static TextView messageView(Activity act, String message) {
        TextView t = Ui.tv(act, message, 15, Ui.TEXT_SUB, false);
        t.setLineSpacing(Ui.dp(act, 3f), 1.2f);
        return t;
    }

    /**
     * 主按钮：Ui.primaryButton 的渐变紫胶囊，抬到 ≥48dp 触摸高度。
     * 点击先关框（带退场动画），关成功再执行 extra。
     */
    private static TextView primaryAction(Activity act, String text, final Dialog dlg,
                                          final Runnable extra) {
        TextView t = Ui.primaryButton(act, text);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dpi(act, MIN_TOUCH_DP));
        t.setPadding(Ui.dpi(act, 16), Ui.dpi(act, 12), Ui.dpi(act, 16), Ui.dpi(act, 12));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (close(dlg) && extra != null) {
                    extra.run();
                }
            }
        });
        Ui.pressable(t);
        return t;
    }

    /** 次要按钮：Ui.secondaryButton 的浅紫胶囊，同样 ≥48dp 触摸高度，点了只会关框 */
    private static TextView secondaryAction(Activity act, String text, final Dialog dlg) {
        TextView t = Ui.secondaryButton(act, text);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Ui.dpi(act, MIN_TOUCH_DP));
        t.setPadding(Ui.dpi(act, 16), Ui.dpi(act, 12), Ui.dpi(act, 16), Ui.dpi(act, 12));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                close(dlg);
            }
        });
        Ui.pressable(t);
        return t;
    }

    /** 一行按钮：两个就各占一半，一个就占满 */
    private static LinearLayout actionRow(Activity act, View secondary, View primary) {
        LinearLayout row = Ui.horizontal(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (secondary != null) {
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (primary != null) {
                sLp.rightMargin = Ui.dpi(act, 10);
            }
            row.addView(secondary, sLp);
        }
        if (primary != null) {
            row.addView(primary, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams topMargin(Activity act, float dpValue) {
        LinearLayout.LayoutParams p = fullWidth();
        p.topMargin = Ui.dpi(act, dpValue);
        return p;
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.length() == 0;
    }

    /** 给面板的一个区域装"向下拖就关"的手势；上滑/回弹都不拦截点击 */
    private static void sheetDrag(final Dialog dlg, final View card, View handle) {
        if (handle == null) {
            return;
        }
        final Context c = handle.getContext();
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downY;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        card.setTranslationY(Math.max(0f, e.getRawY() - downY));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dy = card.getTranslationY();
                        if (dy > Ui.dp(c, SHEET_DRAG_CLOSE_DP)) {
                            close(dlg);
                        } else {
                            card.animate().translationY(0f).setDuration(150).start();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    /** 手势条 / 三键导航的高度，面板底部要给它们留出空档 */
    private static int navBottom(Activity act) {
        try {
            View dv = act.getWindow().getDecorView();
            WindowInsets wi = dv.getRootWindowInsets();
            if (wi == null) {
                return 0;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                return wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                return wi.getSystemWindowInsetBottom();
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    /** 内容高到一定程度才滚，矮的时候自然收起，不会留大片空白 */
    private static final class MaxHeightScroll extends ScrollView {
        private final int maxHeight;

        MaxHeightScroll(Context c, int maxHeight) {
            super(c);
            this.maxHeight = Math.max(1, maxHeight);
            setFillViewport(false);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec,
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }
}
