package com.couple.xiaowo.core;

import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把收到的截图存进相册。
 *
 * 分两条路，按系统版本选：
 *   · Android 10+ (API 29)：走 MediaStore，**不需要任何存储权限**
 *     插到 Pictures/小窝/ 下，系统相册能看到
 *   · 更早的系统：只能写外部存储，需要 WRITE_EXTERNAL_STORAGE
 *
 * 两种都失败时返回 null，由调用方提示（不要静默失败）。
 *
 * 文件名用「小窝-对方-时间.jpg」，方便在相册里一眼认出来，
 * 也天然按时间排序。
 */
public final class ShotSaver {

    /** 相册里的子目录名 */
    private static final String ALBUM = "小窝";
    private static final String MIME = "image/jpeg";

    private ShotSaver() {
    }

    /**
     * 保存一张 JPEG 字节数组到相册。
     *
     * @return 保存成功返回可读的描述（给用户看，如「已存入相册 · 小窝」）；
     * 失败返回 null
     */
    public static String save(Context c, byte[] jpg, String fromName) {
        if (c == null || jpg == null || jpg.length == 0) {
            return null;
        }
        String fileName = buildName(fromName);
        if (Build.VERSION.SDK_INT >= 29) {
            return saveViaMediaStore(c, jpg, fileName);
        }
        return saveViaFile(c, jpg, fileName);
    }

    private static String buildName(String fromName) {
        String who = (fromName == null || fromName.length() == 0) ? "小窝" : fromName;
        // 去掉文件名里不能出现的字符
        who = who.replaceAll("[\\\\/:*?\"<>|\\s]", "");
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date(System.currentTimeMillis()));
        return "小窝-" + who + "-" + ts + ".jpg";
    }

    /** Android 10+：MediaStore，零权限 */
    private static String saveViaMediaStore(Context c, byte[] jpg, String fileName) {
        OutputStream out = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            cv.put(MediaStore.Images.Media.MIME_TYPE, MIME);
            // 相对路径：相册里会显示成「小窝」这个相簿
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + ALBUM);
            // 明确标成「图片」，扫描器不会漏
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);

            android.net.Uri uri = c.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                return null;
            }
            out = c.getContentResolver().openOutputStream(uri);
            if (out == null) {
                c.getContentResolver().delete(uri, null, null);
                return null;
            }
            out.write(jpg);
            out.flush();
            out.close();
            out = null;

            // IS_PENDING 清零，别人才看得到
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            c.getContentResolver().update(uri, done, null, null);

            return "已存入相册 · " + ALBUM;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignore) {
            }
        }
    }

    /** Android 9 及以下：直接写文件（需要存储权限，没有就失败） */
    @SuppressWarnings("deprecation")
    private static String saveViaFile(Context c, byte[] jpg, String fileName) {
        FileOutputStream fos = null;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), ALBUM);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File out = new File(dir, fileName);
            fos = new FileOutputStream(out);
            fos.write(jpg);
            fos.flush();
            return "已存入相册 · " + ALBUM;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception ignore) {
            }
        }
    }
}
