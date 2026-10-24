package com.couple.xiaowo.core;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 聊天记录导出 / 导入。
 *
 * ── 为什么要有它 ──
 *
 * 聊天记录只存在 SharedPreferences 里，换机、重装、清数据就全没了。
 * 用户要求两端都能把记录导出成文件备份，也能从文件导回来。
 *
 * ── 导出 ──
 *
 * 写到系统「下载」目录（Android 10+ 走 MediaStore，零权限；更低版本走
 * 公共下载目录）。文件名带时间和角色，避免两次导出互相覆盖。
 *
 * 文件内容：
 *   { "app":"xiaowo", "kind":"chat", "version":1,
 *     "role":"order", "savedAt":1700000000000, "count":42,
 *     "msgs":[ {"who":"cat","text":"...","ts":...}, ... ] }
 *
 * ── 导入 ──
 *
 * 用 ACTION_OPEN_DOCUMENT 让系统文件选择器挑一个 json。
 * 因为选文件要 startActivityForResult，而 MainActivity 里原本没有这个回调，
 * 所以这里用一个静态挂起回调，由 MainActivity.onActivityResult 转发进来。
 *
 * 兼容两种格式：上面带 msgs 字段的，以及早期直接导出的纯数组。
 */
public final class ChatIO {

    /** 导入完成回调 */
    public interface OnImported {
        void onImported(List<Social.Msg> msgs, String fileName);
    }

    /** 导出成功回调（文件名） */
    public interface OnExported {
        void onExported(String fileName, int count);
    }

    /** 失败回调 */
    public interface OnError {
        void onError(String msg);
    }

    private static final int REQ_IMPORT = 0x5C01;

    /** 挂起的导入回调 —— 文件选择器返回后由 MainActivity 转发 */
    private static OnImported pendingImport;
    private static OnError pendingError;
    /** 本机是不是猫猫端（决定导入的记录里哪边是"我"） */
    private static boolean pendingCatIsMe = true;

    private ChatIO() {
    }

    // ================================================================ 导出

    /**
     * 把聊天记录导出到「下载/小窝」。
     *
     * 全程在后台线程做，回调回主线程。
     */
    public static void export(final Activity act, final List<Social.Msg> msgs,
                              final boolean isRecv, final OnExported ok, final OnError err) {
        if (act == null) {
            return;
        }
        final List<Social.Msg> copy = new ArrayList<Social.Msg>(msgs);
        new Thread(new Runnable() {
            public void run() {
                String name = "xiaowo-chat-"
                        + (isRecv ? "recv" : "order") + "-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                        .format(new Date()) + ".json";
                try {
                    String json = buildExportJson(copy, isRecv);
                    writeToDownloads(act, name, json);
                    final int n = copy.size();
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            if (ok != null) {
                                ok.onExported(name, n);
                            }
                        }
                    });
                } catch (final Exception e) {
                    act.runOnUiThread(new Runnable() {
                        public void run() {
                            if (err != null) {
                                err.onError("导出失败：" + e.getClass().getSimpleName());
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private static String buildExportJson(List<Social.Msg> msgs, boolean isRecv) {
        return Json.object(Json.fields(
                "app", Json.quote("xiaowo"),
                "kind", Json.quote("chat"),
                "version", "1",
                "role", Json.quote(isRecv ? Protocol.ROLE_RECV : Protocol.ROLE_ORDER),
                "savedAt", String.valueOf(System.currentTimeMillis()),
                "count", String.valueOf(msgs.size()),
                "msgs", Social.msgsToJson(msgs)));
    }

    /** 写进系统下载目录 */
    private static void writeToDownloads(Context ctx, String name, String json)
            throws Exception {
        byte[] data = json.getBytes("UTF-8");
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            cv.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/小窝");
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = ctx.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                throw new Exception("no uri");
            }
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) {
                throw new Exception("no stream");
            }
            try {
                os.write(data);
                os.flush();
            } finally {
                os.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "小窝");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new Exception("no dir");
            }
            FileOutputStream fos = new FileOutputStream(new File(dir, name));
            try {
                fos.write(data);
                fos.flush();
            } finally {
                fos.close();
            }
        }
    }

    // ================================================================ 导入

    /**
     * 打开系统文件选择器挑备份文件。
     *
     * @param catIsMe 本机是不是点单端（决定导入记录里谁是"我"）
     */
    public static void pickImport(Activity act, boolean catIsMe,
                                  OnImported ok, OnError err) {
        if (act == null) {
            return;
        }
        currentAct = act;
        pendingImport = ok;
        pendingError = err;
        pendingCatIsMe = catIsMe;
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            act.startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            clearPending();
            if (err != null) {
                err.onError("打不开文件选择器");
            }
        }
    }

    /** MainActivity.onActivityResult 转发到这里 */
    public static boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT) {
            return false;
        }
        final OnImported ok = pendingImport;
        final OnError err = pendingError;
        final boolean catIsMe = pendingCatIsMe;
        clearPending();

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            // 用户取消了，不算错误，静默返回
            return true;
        }
        final Uri uri = data.getData();
        final String fileName = lastPathSegment(uri);
        new Thread(new Runnable() {
            public void run() {
                try {
                    // 读文件
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    InputStream in = currentAct.getContentResolver().openInputStream(uri);
                    if (in == null) {
                        throw new Exception("no stream");
                    }
                    try {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                        }
                    } finally {
                        in.close();
                    }
                    String text = new String(bos.toByteArray(), "UTF-8");
                    final List<Social.Msg> msgs = parseImport(text, catIsMe);
                    currentAct.runOnUiThread(new Runnable() {
                        public void run() {
                            if (ok != null) {
                                ok.onImported(msgs, fileName);
                            }
                        }
                    });
                } catch (final Exception e) {
                    currentAct.runOnUiThread(new Runnable() {
                        public void run() {
                            if (err != null) {
                                err.onError("导入失败：" + e.getClass().getSimpleName());
                            }
                        }
                    });
                }
            }
        }).start();
        return true;
    }

    /** 需要一个 Context 读流；由 pickImport 时记下 */
    private static Activity currentAct = null;

    private static void clearPending() {
        pendingImport = null;
        pendingError = null;
    }

    /** 解析备份文件：支持 {msgs:[...]} 和纯数组两种 */
    public static List<Social.Msg> parseImport(String text, boolean catIsMe) {
        List<Social.Msg> out = new ArrayList<Social.Msg>();
        if (text == null || text.length() == 0) {
            return out;
        }
        try {
            Object o = Json.parse(text.trim());
            if (o instanceof List) {
                return Social.parseMsgsList((List<?>) o, catIsMe);
            }
            if (o instanceof Map) {
                Map<String, Object> m = Json.asObject(o);
                List<Object> items = Json.list(m, "msgs");
                return Social.parseMsgsList(items, catIsMe);
            }
        } catch (Exception ignore) {
        }
        return out;
    }

    /** 合并导入的记录：按 (who+ts+text) 去重，按时间排序 */
    public static int mergeInto(List<Social.Msg> target, List<Social.Msg> incoming) {
        if (target == null || incoming == null) {
            return 0;
        }
        Map<String, Social.Msg> seen = new LinkedHashMap<String, Social.Msg>();
        for (Social.Msg m : target) {
            seen.put(keyOf(m), m);
        }
        int added = 0;
        for (Social.Msg m : incoming) {
            String k = keyOf(m);
            if (!seen.containsKey(k)) {
                seen.put(k, m);
                added++;
            }
        }
        target.clear();
        target.addAll(seen.values());
        sortByTs(target);
        return added;
    }

    private static String keyOf(Social.Msg m) {
        return m.who + "|" + m.ts + "|" + (m.text == null ? "" : m.text);
    }

    private static void sortByTs(List<Social.Msg> list) {
        java.util.Collections.sort(list, new java.util.Comparator<Social.Msg>() {
            public int compare(Social.Msg a, Social.Msg b) {
                if (a.ts == b.ts) {
                    return 0;
                }
                return a.ts < b.ts ? -1 : 1;
            }
        });
    }

    private static String lastPathSegment(Uri uri) {
        try {
            String p = uri.getLastPathSegment();
            if (p == null) {
                return "备份文件";
            }
            int slash = p.lastIndexOf('/');
            return slash >= 0 ? p.substring(slash + 1) : p;
        } catch (Exception e) {
            return "备份文件";
        }
    }

    /** 由 pickImport 记录 Context，供 onActivityResult 里读流 */
    public static void bind(Activity act) {
        currentAct = act;
    }
}
