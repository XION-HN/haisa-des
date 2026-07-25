package com.haisades;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.StatFs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：未捕获异常发生时，把完整堆栈 + 设备信息 + bootstrap 状态 + logcat
 * 写到 Android/data/com.haisades/files/crash/（应用私有外部目录，免存储权限，
 * 用户可用文件管理器直接进 Android/data/com.haisades/ 取回）。
 *
 * 同时把 logcat 单独存一份到 logs/，并在 SettingsActivity 提供“导出诊断日志”按钮
 * 供非崩溃场景主动导出。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context ctx;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashHandler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            writeCrashLog(t, e);
        } catch (Throwable ignore) { /* 崩溃处理器自身绝不能再抛 */ }
        // 交回默认 handler，让进程按系统原行为退出（弹“应用已停止”对话框）
        if (defaultHandler != null) defaultHandler.uncaughtException(t, e);
    }

    private void writeCrashLog(Thread t, Throwable e) {
        File crashDir = new File(ctx.getExternalFilesDir(null), "crash");
        crashDir.mkdirs();
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File f = new File(crashDir, "crash_" + ts + ".log");

        try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
            pw.println("===== HaisaDes 崩溃报告 =====");
            pw.println("时间: " + new Date());
            pw.println();

            pw.println("--- 进程/线程 ---");
            pw.println("PID: " + android.os.Process.myPid());
            pw.println("UID: " + android.os.Process.myUid());
            pw.println("TID: " + t.getId());
            pw.println("Thread: " + t.getName());
            pw.println("Thread state: " + t.getState());
            pw.println();

            pw.println("--- App ---");
            try {
                PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                pw.println("package: " + pi.packageName);
                pw.println("versionName: " + pi.versionName);
                pw.println("versionCode: " + pi.getLongVersionCode());
            } catch (PackageManager.NameNotFoundException ex) {
                pw.println("version: <unknown>");
            }
            pw.println();

            pw.println("--- 设备 ---");
            pw.println("Manufacturer: " + Build.MANUFACTURER);
            pw.println("Model: " + Build.MODEL);
            pw.println("Device: " + Build.DEVICE);
            pw.println("Product: " + Build.PRODUCT);
            pw.println("Brand: " + Build.BRAND);
            pw.println("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            pw.println("fingerprint: " + Build.FINGERPRINT);
            pw.println("SUPPORTED_ABIS: " + joinAbis());
            pw.println();

            pw.println("--- 堆栈 ---");
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            pw.print(sw.toString());
            pw.println();

            dumpBootstrapState(pw);

            pw.println("--- 存储 ---");
            File dataDir = ctx.getFilesDir();
            pw.println("filesDir: " + dataDir + " (free=" + usableMb(dataDir) + "MB)");
            File extDir = ctx.getExternalFilesDir(null);
            pw.println("externalFilesDir: " + extDir + " (free=" + usableMb(extDir) + "MB)");
            pw.println();

            pw.println("--- logcat (最近 800 行) ---");
            pw.print(dumpLogcat(800));
            pw.println();
        } catch (IOException ignored) {
            // FileWriter 失败（外部存储未挂载/权限问题）则跳过主报告，
            // 下方 logcat 文件仍尝试写一份。
        }

        // logcat 单独存全量一份，便于排查非崩溃线程的输出
        File logDir = new File(ctx.getExternalFilesDir(null), "logs");
        logDir.mkdirs();
        File logFile = new File(logDir, "logcat_" + ts + ".log");
        try (PrintWriter lpw = new PrintWriter(new FileWriter(logFile))) {
            lpw.print(dumpLogcat(3000));
        } catch (IOException ignored) { }
    }

    /** 主动导出诊断日志（非崩溃场景），返回写入的文件；失败返回 null。 */
    public static File exportDiagnostics(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "logs");
        dir.mkdirs();
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File f = new File(dir, "diag_" + ts + ".log");
        try (PrintWriter pw = new PrintWriter(new FileWriter(f))) {
            pw.println("===== HaisaDes 诊断日志 =====");
            pw.println("时间: " + new Date());
            pw.println();
            pw.println(Diagnostics.collectBasic(ctx));
            pw.println("--- exec 自检 ---");
            pw.println(Diagnostics.execSelfTest());
            pw.println();
            pw.println("--- logcat (最近 2000 行) ---");
            pw.print(dumpLogcat(2000));
            pw.println();
            dumpBootstrapState(pw);
        } catch (IOException e) {
            return null;
        }
        return f;
    }

    /** 列出已写出的崩溃日志与诊断日志文件路径（供 UI 展示，便于用户定位取回）。 */
    public static String listLogFiles(Context ctx) {
        StringBuilder sb = new StringBuilder();
        File ext = ctx.getExternalFilesDir(null);
        if (ext == null) return "外部目录不可用";
        File crashDir = new File(ext, "crash");
        File logDir = new File(ext, "logs");
        sb.append("日志根目录:\n  ").append(ext.getAbsolutePath()).append("\n\n");
        sb.append("崩溃日志 (crash/):\n");
        File[] cs = crashDir.listFiles();
        if (cs == null || cs.length == 0) sb.append("  (无)\n");
        else for (File c : cs) sb.append("  ").append(c.getName())
                .append(" (").append(c.length() / 1024).append(" KB)\n");
        sb.append("\n诊断/日志 (logs/):\n");
        File[] ls = logDir.listFiles();
        if (ls == null || ls.length == 0) sb.append("  (无)\n");
        else for (File l : ls) sb.append("  ").append(l.getName())
                .append(" (").append(l.length() / 1024).append(" KB)\n");
        return sb.toString();
    }

    private static void dumpBootstrapState(PrintWriter pw) {
        pw.println("--- bootstrap 状态 ---");
        File prefix = new File(App.PREFIX);
        pw.println("prefix: " + App.PREFIX);
        pw.println("prefix exists: " + prefix.exists());
        pw.println(".bootstrap-ok: " + new File(prefix, ".bootstrap-ok").exists());
        File bin = new File(prefix, "bin");
        File lib = new File(prefix, "lib");
        if (bin.isDirectory()) {
            String[] names = bin.list();
            pw.println("bin/ 文件数: " + (names == null ? 0 : names.length));
            String[] keys = {"bash", "sh", "ls", "python3", "python3.13",
                             "pip3", "pip3.13", "pip"};
            for (String k : keys) {
                File kf = new File(bin, k);
                String st = !kf.exists() ? "MISSING"
                    : (kf.canExecute() ? "exec" : "noexec");
                pw.println("  bin/" + k + ": " + st);
            }
        } else {
            pw.println("bin/: MISSING");
        }
        if (lib.isDirectory()) {
            String[] libs = {"libpython3.13.so", "libpython3.13.so.1",
                             "libssl.so", "libcrypto.so", "libffi.so",
                             "libsqlite3.so", "libbz2.so", "liblzma.so",
                             "libexpat.so", "libreadline.so", "libncursesw.so",
                             "libncurses.so", "libz.so"};
            for (String l : libs) {
                pw.println("  lib/" + l + ": " + (new File(lib, l).exists() ? "ok" : "MISSING"));
            }
        } else {
            pw.println("lib/: MISSING");
        }
        pw.println();
    }

    static String dumpLogcat(int maxLines) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-t", String.valueOf(maxLines))
                    .redirectErrorStream(true).start();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) sb.append(new String(buf, 0, n));
            }
            p.waitFor();
        } catch (Throwable e) {
            sb.append("[logcat 读取失败: ").append(e.getMessage()).append("]\n");
        } finally {
            if (p != null) p.destroy();
        }
        return sb.toString();
    }

    private static long usableMb(File f) {
        if (f == null) return -1;
        try {
            StatFs s = new StatFs(f.getAbsolutePath());
            return s.getAvailableBytes() / (1024 * 1024);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String joinAbis() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis == null) return "<none>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < abis.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(abis[i]);
        }
        return sb.toString();
    }
}
