package com.couple.xiaowo.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 把打包进 APK 里的官方 frpc 跑起来。
 *
 * 为什么要放进 lib/arm64-v8a/libfrpc.so？
 *   Android 10 起禁止 App 执行自己数据目录里的文件（W^X），
 *   但 `nativeLibraryDir` 里的文件是可以执行的。所以把 arm64 的 frpc
 *   命名成 libfrpc.so 放进 jniLibs，装完系统会把它解压到 nativeLibraryDir，
 *   路径就变成 /data/app/.../lib/arm64-v8a/libfrpc.so，可以直接 exec。
 *
 * 版本：0.51.0-sakura-14（取自官方 Android 启动器，保证能在 Android 上跑）
 */
public final class FrpcRunner {

    public interface Listener {
        void onLine(String line);

        void onExit(int code);

        void onStartFailed(String reason);
    }

    private Process process;
    private Thread reader;
    private Listener listener;
    private volatile boolean stopping;

    public static File binary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libfrpc.so");
    }

    public static boolean available(Context c) {
        File f = binary(c);
        return f.exists() && f.canExecute();
    }

    /** 自检：跑一下内置 frpc 的 -v，用来确认「能不能执行」这条链路是通的 */
    public static String runVersion(Context c) {
        File bin = binary(c);
        if (!bin.exists()) {
            return "找不到 " + bin.getAbsolutePath();
        }
        if (!bin.canExecute()) {
            return "无法执行（没有执行权限）: " + bin.getAbsolutePath();
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "-V");
            pb.redirectErrorStream(true);
            pb.directory(c.getCacheDir());
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "执行失败：" + e;
        }
    }

    public boolean isRunning() {
        if (process == null) {
            return false;
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    /**
     * @param token    访问密钥
     * @param tunnelId 隧道 ID
     */
    public synchronized void start(Context c, String token, int tunnelId, Listener l) {
        if (isRunning()) {
            return;
        }
        this.listener = l;
        this.stopping = false;
        File bin = binary(c);
        if (!bin.exists()) {
            l.onStartFailed("APK 里没有找到 frpc（" + bin.getAbsolutePath() + "）");
            return;
        }
        if (!bin.canExecute()) {
            l.onStartFailed("frpc 没有执行权限，可能被系统限制");
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    bin.getAbsolutePath(),
                    "--disable_log_color",
                    "--no_check_update",
                    "-f", token + ":" + tunnelId);
            pb.redirectErrorStream(true);
            pb.directory(c.getCacheDir());
            process = pb.start();
        } catch (Exception e) {
            l.onStartFailed("启动 frpc 失败：" + e.getMessage());
            return;
        }
        final Process p = process;
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (listener != null) {
                            listener.onLine(line);
                        }
                    }
                } catch (Exception ignore) {
                }
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (Exception ignore) {
                }
                if (!stopping && listener != null) {
                    listener.onExit(code);
                }
            }
        }, "frpc-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized void stop() {
        stopping = true;
        if (process != null) {
            try {
                process.destroy();
            } catch (Exception ignore) {
            }
            final Process p = process;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!p.waitFor(2500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            try {
                                p.destroyForcibly();
                            } catch (Exception ignore) {
                            }
                        }
                    } catch (Exception ignore) {
                    }
                }
            }).start();
        }
        process = null;
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
    }
}
