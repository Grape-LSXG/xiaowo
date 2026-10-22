package com.couple.xiaowo.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 提醒程度设置。
 *
 * 三种级别，两边都能各自选：
 *   OFF      完全不提醒（对方发的东西只在 App 里能看到）
 *   NOTIFY   只发系统通知（不打扰，下拉栏能看到）
 *   OVERLAY  悬浮窗气泡直接蹦到屏幕上（最显眼，需要用户授权）
 */
public final class Remind {

    public static final int OFF = 0;
    public static final int NOTIFY = 1;
    public static final int OVERLAY = 2;

    public static final String[] LABELS = {"完全安静", "只发通知", "悬浮窗蹦出来"};
    public static final String[] HINTS = {
            "只在这两个 App 里能看到，不打扰手机",
            "会发一条系统通知，下拉通知栏才看得到",
            "爱心和消息会直接飘到屏幕上，最明显"
    };

    public static String label(int level) {
        if (level < 0 || level >= LABELS.length) {
            return LABELS[NOTIFY];
        }
        return LABELS[level];
    }

    public static String hint(int level) {
        if (level < 0 || level >= HINTS.length) {
            return HINTS[NOTIFY];
        }
        return HINTS[level];
    }

    private Remind() {
    }

    // ------------------------------------------------------------------ 公告/聊天的已读状态

    /** 简单的"未读数"记录，用于我页面上的小红点 */
    public static final class Unread {
        public int chat;
        public int notice;
        public int orders;

        public String toJson() {
            return Json.object(Json.fields(
                    "chat", String.valueOf(chat),
                    "notice", String.valueOf(notice),
                    "orders", String.valueOf(orders)));
        }

        public static Unread fromJson(String s) {
            Unread u = new Unread();
            if (s == null || s.length() == 0) {
                return u;
            }
            try {
                Map<String, Object> m = Json.asObject(Json.parse(s));
                u.chat = (int) Json.num(m, "chat", 0);
                u.notice = (int) Json.num(m, "notice", 0);
                u.orders = (int) Json.num(m, "orders", 0);
            } catch (Exception ignore) {
            }
            return u;
        }

        public int total() {
            return chat + notice + orders;
        }
    }
}
