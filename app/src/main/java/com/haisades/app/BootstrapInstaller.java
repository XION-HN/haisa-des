package com.haisades;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 首次启动安装 bootstrap：assets zip → files/usr.tmp → 重建符号链接 → chmod → 原子 rename。
 * 与 build-system/make-bootstrap.sh 的产物格式一一对应：
 *   - zip 根 = $PREFIX 内容（bin/ lib/ etc/ share/ tmp/）+ SYMLINKS.txt
 *   - SYMLINKS.txt 每行: link路径<TAB>目标（相对 prefix 根）
 *
 * 升级路径（installFromZip）：从 Releases 下载新 zip → 解压 → 保留 var/ → 原子 rename。
 * 由 BootstrapUpdater 调用，保留 var/installed/ 等用户状态（已装包记录、hold 标记等）。
 */
public final class BootstrapInstaller {

    public interface Callback {
        void onReady();
        void onError(String message);
    }

    private static final String TAG = "BootstrapInstaller";
    private static final String MARKER = ".bootstrap-ok";

    public static boolean isInstalled(Context ctx) {
        return new File(ctx.getFilesDir(), "usr/" + MARKER).isFile();
    }

    public static void installAsync(Context ctx, Callback cb) {
        Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                install(ctx.getApplicationContext());
                h.post(cb::onReady);
            } catch (Exception e) {
                Log.e(TAG, "install failed", e);
                h.post(() -> cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }, "bootstrap-install").start();
    }

    /** 首次安装：从 APK assets 解压。不保留 var/（首次无旧 var/）。
     *  version/buildId 写入 .bootstrap-ok 供 BootstrapUpdater 对比。 */
    static void install(Context ctx) throws Exception {
        File filesDir = ctx.getFilesDir();
        File usrTmp = new File(filesDir, "usr.tmp");
        File usr = new File(filesDir, "usr");

        deleteRecursively(usrTmp);
        if (!usrTmp.mkdirs()) throw new IllegalStateException("无法创建 " + usrTmp);

        try {
            try (InputStream in = ctx.getAssets().open(App.BOOTSTRAP_ASSET)) {
                extractAndPrepare(usrTmp, in);
            }
            writeMarker(usrTmp, App.BOOTSTRAP_VERSION, App.BOOTSTRAP_BUILD_ID);
            deleteRecursively(usr);
            if (!usrTmp.renameTo(usr)) throw new IllegalStateException("usr.tmp → usr rename 失败");
            Log.i(TAG, "bootstrap installed at " + usr);
        } catch (Exception e) {
            deleteRecursively(usrTmp);
            throw e;
        }
    }

    /** 升级路径：从下载好的 zip 文件安装。
     *  keepVar=true 时，rename 前把旧 usr/var 复制到新 usr.tmp/var，
     *  保留已装包记录、hold 标记等用户状态。
     *  version/buildId 写入 .bootstrap-ok 供下次升级对比。 */
    static void installFromZip(Context ctx, File zipFile, boolean keepVar,
                              String version, String buildId) throws Exception {
        File filesDir = ctx.getFilesDir();
        File usrTmp = new File(filesDir, "usr.tmp");
        File usr = new File(filesDir, "usr");

        deleteRecursively(usrTmp);
        if (!usrTmp.mkdirs()) throw new IllegalStateException("无法创建 " + usrTmp);

        try {
            try (InputStream in = new FileInputStream(zipFile)) {
                extractAndPrepare(usrTmp, in);
            }

            if (keepVar) {
                // 保留旧 usr/var：复制到新 usr.tmp/var（覆盖新 zip 里可能的空 var/）
                File oldVar = new File(usr, "var");
                if (oldVar.isDirectory()) {
                    File newVar = new File(usrTmp, "var");
                    deleteRecursively(newVar);
                    copyRecursively(oldVar, newVar);
                }
            }

            writeMarker(usrTmp, version, buildId);
            deleteRecursively(usr);
            if (!usrTmp.renameTo(usr)) throw new IllegalStateException("usr.tmp → usr rename 失败");
            Log.i(TAG, "bootstrap upgraded to " + version + " at " + usr);
        } catch (Exception e) {
            deleteRecursively(usrTmp);
            throw e;
        }
    }

    /** 解压 zip 到 usrTmp，重建符号链接，chmod，HOME/.bashrc。
     *  公共逻辑：install() 和 installFromZip() 都走这一段。 */
    private static void extractAndPrepare(File usrTmp, InputStream zipStream) throws Exception {
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File out = new File(usrTmp, e.getName());
                if (e.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }

        // 按 SYMLINKS.txt 重建符号链接（zip 无法存储 symlink）
        File symlinks = new File(usrTmp, "SYMLINKS.txt");
        if (symlinks.isFile()) {
            try (BufferedReader r = new BufferedReader(new FileReader(symlinks))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String linkPath = line.substring(0, tab);
                    String target = line.substring(tab + 1);
                    File link = new File(usrTmp, linkPath);
                    File parent = link.getParentFile();
                    if (parent != null) parent.mkdirs();
                    link.delete();
                    Os.symlink(target, link.getAbsolutePath());
                }
            }
        }

        // chmod：bin/libexec/sbin 0755，tmp 0700
        chmodDir(new File(usrTmp, "bin"), 0755);
        chmodDir(new File(usrTmp, "libexec"), 0755);
        chmodDir(new File(usrTmp, "sbin"), 0755);
        File tmp = new File(usrTmp, "tmp");
        tmp.mkdirs();
        Os.chmod(tmp.getAbsolutePath(), 0700);

        // HOME 与默认 .bashrc
        File home = new File(App.HOME_PATH);
        home.mkdirs();
        File bashrc = new File(home, ".bashrc");
        if (!bashrc.exists()) {
            try (FileOutputStream fos = new FileOutputStream(bashrc)) {
                fos.write(("export PS1='[\\u@haisa-des \\w]\\$ '\n"
                         + "alias ll='ls -l'\n"
                         + "export TERM=xterm-256color\n").getBytes());
            }
        }
    }

    /** 写 .bootstrap-ok 标记文件，含 prefix/version/buildId 三行。
     *  供 BootstrapUpdater.getCurrentVersion() 对比版本。 */
    private static void writeMarker(File usrTmp, String version, String buildId) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(new File(usrTmp, MARKER))) {
            fos.write(("prefix=" + App.PREFIX + "\n"
                     + "version=" + version + "\n"
                     + "build_id=" + buildId + "\n").getBytes());
        }
    }

    /** 读 .bootstrap-ok 的 version 字段，未安装或旧版无此字段返回空字符串 */
    static String getCurrentVersion(Context ctx) {
        File marker = new File(ctx.getFilesDir(), "usr/" + MARKER);
        if (!marker.isFile()) return "";
        try (BufferedReader r = new BufferedReader(new FileReader(marker))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("version=")) {
                    return line.substring("version=".length()).trim();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getCurrentVersion failed: " + e);
        }
        return "";
    }

    private static void chmodDir(File dir, int mode) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try {
                if (f.isFile()) Os.chmod(f.getAbsolutePath(), mode);
            } catch (Exception e) {
                Log.w(TAG, "chmod failed: " + f + " " + e);
            }
        }
    }

    /** 递归复制目录（保留符号链接，不跟随）。
     *  顺序：先判断符号链接（避免被 isDirectory/isFile 跟随误导），再判断目录/文件。 */
    private static void copyRecursively(File src, File dst) throws Exception {
        if (java.nio.file.Files.isSymbolicLink(src.toPath())) {
            // 符号链接：用 Os.symlink 重建，不复制目标
            String target = java.nio.file.Files.readSymbolicLink(src.toPath()).toString();
            Os.symlink(target, dst.getAbsolutePath());
        } else if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children == null) return;
            for (File c : children) {
                copyRecursively(c, new File(dst, c.getName()));
            }
        } else if (src.isFile()) {
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}

