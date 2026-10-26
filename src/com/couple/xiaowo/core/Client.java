package com.couple.xiaowo.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

/**
 * 点单端的「这台设备是谁」。
 *
 * 为什么要这个：
 * 一个人手里可能有两台手机都装着点单端（或者像我们这样，
 * 同一台手机同时开点单端和接单端来调试）。以前接单端只有
 * 一个「最近一单」的概念，谁在线、几个在线，完全看不出来。
 *
 * 做法很土但够用：第一次运行时随机生成 4 位编号存进 SP，
 * 之后一直用它。既不用申请任何权限，也不上传任何设备信息。
 */
public final class Client {

    private static final String SP = "xiaowo";
    private static final String KEY_ID = "order_client_id";
    /** 去掉了容易看混的 0/O/1/I，读出来念给对方也不会错 */
    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    private Client() {
    }

    /** 点单端的设备编号（如 "A3F2"），首次调用时生成 */
    public static String id(Context c) {
        SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        String id = sp.getString(KEY_ID, null);
        if (id != null && id.length() > 0) {
            return id;
        }
        Random r = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        id = sb.toString();
        sp.edit().putString(KEY_ID, id).apply();
        return id;
    }

    /** 显示名：「猫猫(A3F2)」—— 多台点单端时一眼能分清是哪一台 */
    public static String label(Context c) {
        return Protocol.NAME_ORDER + "(" + id(c) + ")";
    }

    /**
     * 这台手机叫什么（如 "V2502A"）。
     *
     * 编号 A3F2 只能区分"哪一台"，看不出是"谁的哪台"，
     * 所以心跳里再带一个机型名，接单端的设备列表就一眼能读懂。
     * 只用 Build.MODEL，不申请任何权限、不上传可识别信息。
     */
    public static String device() {
        try {
            String m = android.os.Build.MODEL;
            if (m == null) {
                return "手机";
            }
            m = m.trim();
            return m.length() == 0 ? "手机" : m;
        } catch (Throwable t) {
            return "手机";
        }
    }
}
