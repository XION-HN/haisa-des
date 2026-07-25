package com.haisades;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
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

    static void install(Context ctx) throws Exception {
        File filesDir = ctx.getFilesDir();
        File usrTmp = new File(filesDir, "usr.tmp");
        File usr = new File(filesDir, "usr");

        deleteRecursively(usrTmp);
        if (!usrTmp.mkdirs()) throw new IllegalStateException("无法创建 " + usrTmp);

        try {
            // 1) 解压 zip
            try (InputStream in = ctx.getAssets().open(App.BOOTSTRAP_ASSET);
                 ZipInputStream zis = new ZipInputStream(in)) {
                byte[] buf = new byte[64 * 1024];
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

            // 2) 按 SYMLINKS.txt 重建符号链接（zip 无法存储 symlink）
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

            // 3) chmod：bin/libexec/sbin 0755，tmp 0700
            chmodDir(new File(usrTmp, "bin"), 0755);
            chmodDir(new File(usrTmp, "libexec"), 0755);
            chmodDir(new File(usrTmp, "sbin"), 0755);
            File tmp = new File(usrTmp, "tmp");
            tmp.mkdirs();
            Os.chmod(tmp.getAbsolutePath(), 0700);

            // 4) HOME 与默认 .bashrc
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

            // 5) 标记 + 原子 rename
            try (FileOutputStream fos = new FileOutputStream(new File(usrTmp, MARKER))) {
                fos.write(("prefix=" + App.PREFIX + "\n").getBytes());
            }
            deleteRecursively(usr);
            if (!usrTmp.renameTo(usr)) throw new IllegalStateException("usr.tmp → usr rename 失败");
            Log.i(TAG, "bootstrap installed at " + usr);
        } catch (Exception e) {
            deleteRecursively(usrTmp);   // 不留残局
            throw e;
        }
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

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}
