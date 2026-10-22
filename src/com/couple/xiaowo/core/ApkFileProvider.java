package com.couple.xiaowo.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 最小可用的 FileProvider —— 本项目零第三方依赖，不能用 AndroidX 的那个。
 *
 * 只做一件事：把 cacheDir 里的文件暴露成 content:// URI，
 * 让系统安装器能读到我们下载的 APK（Android 7+ 禁止直接用 file:// 分享）。
 *
 * 安全上只允许 cacheDir 下的文件，且 URI 路径里不允许出现 ".."。
 */
public final class ApkFileProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".updates";

    public static Uri uriFor(android.content.Context c, File file) {
        return Uri.parse("content://" + c.getPackageName() + AUTHORITY_SUFFIX
                + "/" + Uri.encode(file.getName()));
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null) {
            throw new FileNotFoundException("空路径");
        }
        name = Uri.decode(name);
        if (name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("非法路径");
        }
        File dir = getContext().getCacheDir();
        File f = new File(dir, name);
        // 再校验一次真实路径仍在 cacheDir 内
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath())) {
                throw new FileNotFoundException("越界访问");
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("路径解析失败");
        }
        if (!f.exists()) {
            throw new FileNotFoundException(name);
        }
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            String[] cols = projection != null ? projection
                    : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor c = new MatrixCursor(cols, 1);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                    row[i] = f.getName();
                } else if (OpenableColumns.SIZE.equals(cols[i])) {
                    row[i] = f.length();
                } else {
                    row[i] = null;
                }
            }
            c.addRow(row);
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
