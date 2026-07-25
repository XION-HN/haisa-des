package com.haisades;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 设备矩阵验证的数据收集工具：信息聚合 / exec 自检 / 幻影压测 */
public final class Diagnostics {

    public static final String ADB_DISABLE_PHANTOM =
        "adb shell \"settings put global settings_enable_monitor_phantom_procs false\"";

    /** 基础设备信息（不含 exec 自检结果，自检耗时单独调用） */
    public static String collectBasic(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android: ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("fingerprint: ").append(Build.FINGERPRINT).append('\n');
        sb.append("SELinux: ").append(selinuxStatus()).append('\n');
        sb.append("app uid: ").append(android.os.Process.myUid()).append('\n');
        sb.append("bootstrap: ").append(BootstrapInstaller.isInstalled(ctx) ? "已安装" : "未安装").append('\n');
        sb.append("prefix: ").append(App.PREFIX).append('\n');
        return sb.toString();
    }

    /** exec 自检 = 设备矩阵场景 1+2 的半自动化 */
    public static String execSelfTest() {
        // 命令刻意不依赖 wc/head/grep 等 toybox 命令——自检目的是验证 shell 可执行
        // + 动态链接链 + HTTPS 通路，不该被 toybox 装不全拖垮。
        // curl -w 直接输出 http_code 到 stdout，无需 head 截取；-o /dev/null 丢弃 body。
        return runShell(bootstrapEnv(), App.PREFIX + "/bin/bash", "-c",
            "echo EXEC_OK; uname -m; ls $PREFIX/bin; "
          + "curl -sI -w 'HTTP:%{http_code}\\n' -o /dev/null https://www.baidu.com");
    }

    /** Python 自检：验证解释器能启动并加载核心 C 扩展（覆盖多层 .so 依赖链） */
    public static String pythonSelfTest() {
        // import 列表刻意覆盖 _ssl/_hashlib/_ctypes/_sqlite3/_bz2/_lzma/pyexpat/readline
        // —— 任一缺失说明对应依赖 .so 在 lib/ 找不到或 RUNPATH 解析失败
        return runShell(bootstrapEnv(), App.PREFIX + "/bin/python3.13", "-c",
            "import sys,ssl,ctypes,sqlite3,bz2,lzma,hashlib,"
          + "xml.etree.ElementTree,readline;"
          + "print('PY_OK', sys.version.split()[0])");
    }

    /** pip 自检：验证 pip 入口脚本与 shebang、site-packages 安装完整 */
    public static String pipSelfTest() {
        return runShell(bootstrapEnv(), App.PREFIX + "/bin/pip3.13", "--version");
    }

    /**
     * 一键全量自检：按 spec 场景 1+2 顺序跑 exec → Python → pip，输出结构化报告。
     * 幻影压测是异步长任务（fork 40×sleep 300），不纳入本同步序列，单独按钮触发。
     *
     * 报告含每项原始输出 + PASS/FAIL 判定 + 末尾汇总，便于直接粘贴进
     * docs/device-test-checklist.md 的验证报告表格。
     */
    public static String runAllSelfTests(Context ctx) {
        StringBuilder sb = new StringBuilder();
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        sb.append("=== HaisaDes 全量自检报告 ===\n");
        sb.append("生成时间: ").append(ts).append("\n\n");
        sb.append(collectBasic(ctx)).append('\n');

        int passed = 0;
        int total = 3;

        // --- 1/3 exec 自检（场景 1+2：shell 可执行 + 三层 .so 链 + HTTPS）---
        String execOut = execSelfTest();
        // execSelfTest 现在用 curl -w 输出 "HTTP:200"，不依赖 head。
        boolean execPass = execOut.contains("EXEC_OK") && execOut.contains("HTTP:200");
        if (execPass) passed++;
        sb.append("--- 1/").append(total).append(" exec 自检（场景 1+2）---\n");
        sb.append("输出:\n").append(indent(execOut)).append('\n');
        sb.append("结论: ").append(execPass ? "PASS" : "FAIL").append("\n\n");

        // --- 2/3 Python 自检（核心 C 扩展：_ssl/_ctypes/_sqlite3/_bz2/_lzma/...）---
        String pyOut = pythonSelfTest();
        // 不能用 contains("PY_OK")：import 失败时 Traceback 会回显源码行
        // "print('PY_OK', ...)"，含 PY_OK 字符串，导致误判 PASS。
        // 必须只看 stdout（非 [stderr] 行）是否含 PY_OK。
        boolean pyPass = containsStdoutMarker(pyOut, "PY_OK");
        if (pyPass) passed++;
        sb.append("--- 2/").append(total).append(" Python 自检（核心 C 扩展）---\n");
        sb.append("输出:\n").append(indent(pyOut)).append('\n');
        sb.append("结论: ").append(pyPass ? "PASS" : "FAIL").append("\n\n");

        // --- 3/3 pip 自检（入口脚本 + shebang + site-packages）---
        String pipOut = pipSelfTest();
        // pip --version 成功输出形如 "pip 24.2 from .../pip (python 3.13)"
        // 失败标记：[stderr] / [exec失败] / [timeout]
        boolean pipPass = pipOut.contains("pip")
            && pipOut.contains("python")
            && !pipOut.contains("[exec失败]")
            && !pipOut.contains("[timeout]");
        if (pipPass) passed++;
        sb.append("--- 3/").append(total).append(" pip 自检 ---\n");
        sb.append("输出:\n").append(indent(pipOut)).append('\n');
        sb.append("结论: ").append(pipPass ? "PASS" : "FAIL").append("\n\n");

        sb.append("=== 汇总: ").append(passed).append('/').append(total).append(" 通过 ===\n");
        if (passed < total) {
            sb.append("提示: 失败项请进入「诊断」页单独复跑，或用「导出诊断日志」收 logcat。\n");
        }
        return sb.toString();
    }

    /**
     * 检查输出中是否存在非 stderr 行含指定标记。
     * runShell 把 stderr 行加了 "[stderr] " 前缀，stdout 行无前缀。
     * 用于规避 Traceback 回显源码导致的误判（如源码含 'PY_OK' 字符串）。
     */
    private static boolean containsStdoutMarker(String output, String marker) {
        if (output == null || output.isEmpty()) return false;
        for (String line : output.split("\n", -1)) {
            if (line.startsWith("[stderr]")) continue;
            if (line.contains(marker)) return true;
        }
        return false;
    }

    /** 每行前加 2 空格缩进，便于在报告里区分原始输出与判定文字。 */
    private static String indent(String s) {
        if (s == null || s.isEmpty()) return "  (无输出)";
        StringBuilder r = new StringBuilder(s.length() + 16);
        for (String line : s.split("\n", -1)) {
            r.append("  ").append(line).append('\n');
        }
        // 去掉末尾多余换行
        if (r.length() > 0 && r.charAt(r.length() - 1) == '\n') r.setLength(r.length() - 1);
        return r.toString();
    }

    /** 幻影进程压测：fork 40 个 sleep 300（进程脱离父进程后由 init 收养，正是幻影杀手的目标） */
    public static String startPhantomStress() {
        return runShell(bootstrapEnv(), App.PREFIX + "/bin/bash", "-c",
            "for i in $(seq 1 40); do sleep 300 & done; echo STRESS_STARTED");
    }

    /** bootstrap 前缀环境变量（与 MainActivity.buildEnv 对齐，供 runShell 注入）。
     *  Runtime.exec 不继承终端会话 env，必须显式传入，否则子进程里 $PREFIX/$PATH 为空。 */
    private static String[] bootstrapEnv() {
        return new String[]{
            "TERM=xterm-256color",
            "HOME=" + App.HOME_PATH,
            "PREFIX=" + App.PREFIX,
            "PATH=" + App.PREFIX + "/bin",
            "TMPDIR=" + App.PREFIX + "/tmp",
            "LANG=C.UTF-8",
            "LD_LIBRARY_PATH=" + App.PREFIX + "/lib",
            "PYTHON_BASIC_REPL=1",
        };
    }

    private static String runShell(String... cmd) {
        return runShell(null, cmd);
    }

    private static String runShell(String[] envp, String... cmd) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(cmd, envp, null);
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) out.append(line).append('\n');
                } catch (Exception ignored) { }
            });
            t.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append("[stderr] ").append(line).append('\n');
            }
            t.join();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroy();
                out.append("[timeout]\n");
            }
        } catch (Exception e) {
            out.append("[exec失败] ").append(e.getMessage()).append('\n');
        }
        return out.toString().trim();
    }

    private static String selinuxStatus() {
        File f = new File("/sys/fs/selinux/enforce");
        if (!f.isFile()) return "unknown(no selinuxfs)";
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s = r.readLine();
            return "1".equals(s) ? "enforcing" : "permissive";
        } catch (Exception e) {
            return "unknown(" + e.getMessage() + ")";
        }
    }
}
