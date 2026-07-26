package com.haisades;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView mDeviceInfo;
    private TextView mExecOutput;
    private TextView mPkgOutput;
    private final StringBuilder mDiagLog = new StringBuilder();   // 累积各次自检结果，供一键复制

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mDeviceInfo = findViewById(R.id.device_info);
        mExecOutput = findViewById(R.id.exec_test_output);
        TextView adbCmd = findViewById(R.id.adb_command);
        adbCmd.setText(Diagnostics.ADB_DISABLE_PHANTOM);

        mDeviceInfo.setText(Diagnostics.collectBasic(this));

        Button btnRunAll = findViewById(R.id.btn_run_all);
        btnRunAll.setOnClickListener(v -> runTest("全量自检", () -> Diagnostics.runAllSelfTests(this)));

        Button btnExec = findViewById(R.id.btn_exec_test);
        btnExec.setOnClickListener(v -> runTest("exec 自检", Diagnostics::execSelfTest));

        Button btnPython = findViewById(R.id.btn_python_test);
        btnPython.setOnClickListener(v -> runTest("Python 自检", Diagnostics::pythonSelfTest));

        Button btnPip = findViewById(R.id.btn_pip_test);
        btnPip.setOnClickListener(v -> runTest("pip 自检", Diagnostics::pipSelfTest));

        Button btnPhantom = findViewById(R.id.btn_phantom_test);
        btnPhantom.setOnClickListener(v -> runInBackground(
            Diagnostics::startPhantomStress,
            r -> Toast.makeText(this,
                "STRESS_STARTED".equals(r) ? "压测已启动，5 分钟后到终端执行 ps -A | grep -c sleep"
                                           : "压测异常: " + r,
                Toast.LENGTH_LONG).show()));

        Button btnCopyAdb = findViewById(R.id.btn_copy_adb);
        btnCopyAdb.setOnClickListener(v -> copyToClipboard("adb", Diagnostics.ADB_DISABLE_PHANTOM));

        Button btnExportLogs = findViewById(R.id.btn_export_logs);
        btnExportLogs.setOnClickListener(v -> {
            mExecOutput.setVisibility(View.VISIBLE);
            mExecOutput.setText("导出中…");
            new Thread(() -> {
                final java.io.File f = CrashHandler.exportDiagnostics(this);
                mHandler.post(() -> {
                    if (f != null) {
                        String p = f.getAbsolutePath();
                        Toast.makeText(this, "已导出:\n" + p, Toast.LENGTH_LONG).show();
                        mExecOutput.setText("已导出诊断日志:\n" + p
                            + "\n\n用文件管理器进 Android/data/com.haisades/files/logs/ 取回。");
                        mDiagLog.append("--- 导出诊断日志 ---\n").append(p).append("\n\n");
                    } else {
                        Toast.makeText(this, "导出失败", Toast.LENGTH_LONG).show();
                        mExecOutput.setText("导出失败");
                    }
                });
            }, "export-logs").start();
        });

        Button btnListLogs = findViewById(R.id.btn_list_logs);
        btnListLogs.setOnClickListener(v -> {
            String list = CrashHandler.listLogFiles(this);
            mExecOutput.setVisibility(View.VISIBLE);
            mExecOutput.setText(list);
        });

        Button btnCopyDiag = findViewById(R.id.btn_copy_diag);
        btnCopyDiag.setOnClickListener(v -> {
            String all = Diagnostics.collectBasic(this) + "\n" + mDiagLog.toString();
            copyToClipboard("diagnostics", all);
        });

        // ---- 包管理器 ----
        mPkgOutput = findViewById(R.id.pkg_index_output);
        // 缓存最近一次拉取的索引，供安装时按名查找
        final java.util.List<PackageManager.PackageInfo>[] indexHolder = new java.util.List[]{null};

        Button btnRefreshIndex = findViewById(R.id.btn_refresh_index);
        btnRefreshIndex.setOnClickListener(v -> {
            mPkgOutput.setVisibility(View.VISIBLE);
            mPkgOutput.setText("拉取索引中…（镜像: " + PackageManager.getMirrorLabel(PackageManager.getMirrorIndex(this)) + "）");
            PackageManager.fetchIndex(this, new PackageManager.Callback() {
                @Override public void onProgress(String msg) { }
                @Override public void onSuccess(String summary) {
                    mPkgOutput.setText(summary);
                    try {
                        indexHolder[0] = PackageManager.fetchIndexSync(SettingsActivity.this);
                    } catch (Exception e) {
                        indexHolder[0] = null;
                    }
                }
                @Override public void onError(String message) {
                    mPkgOutput.setText("拉取失败: " + message);
                }
            });
        });

        Button btnSelectMirror = findViewById(R.id.btn_select_mirror);
        btnSelectMirror.setOnClickListener(v -> showMirrorDialog());

        Button btnOpenPkgList = findViewById(R.id.btn_open_pkg_list);
        btnOpenPkgList.setOnClickListener(v -> startActivity(new Intent(this, PackageListActivity.class)));

        Button btnInstallPkg = findViewById(R.id.btn_install_pkg);
        btnInstallPkg.setOnClickListener(v -> showInstallDialog(indexHolder));

        Button btnUninstallPkg = findViewById(R.id.btn_uninstall_pkg);
        btnUninstallPkg.setOnClickListener(v -> showUninstallDialog());

        Button btnCheckBootstrap = findViewById(R.id.btn_check_bootstrap_update);
        btnCheckBootstrap.setOnClickListener(v -> checkBootstrapUpdate());
    }

    /** 弹出输入框让用户输入包名，调用卸载（不需要索引，直接按已装记录卸载） */
    private void showUninstallDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("包名，如 python");
        new AlertDialog.Builder(this)
            .setTitle("卸载包")
            .setMessage("卸载将删除该包独有的文件，被其他包共享的库会保留。\n注意：不会自动卸载依赖。")
            .setView(input)
            .setPositiveButton("卸载", (DialogInterface d, int w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                uninstallByName(name);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void uninstallByName(String name) {
        mPkgOutput.setVisibility(View.VISIBLE);
        mPkgOutput.setText("卸载 " + name + " …");
        PackageManager.uninstallPackage(name, new PackageManager.Callback() {
            @Override public void onProgress(String msg) {
                mPkgOutput.setText(msg);
            }
            @Override public void onSuccess(String summary) {
                mPkgOutput.setText(summary);
                Toast.makeText(SettingsActivity.this, "卸载完成", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                mPkgOutput.setText("卸载失败: " + message);
                Toast.makeText(SettingsActivity.this, "卸载失败", Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 弹出输入框让用户输入包名，从缓存的索引中查找后调用安装 */
    private void showInstallDialog(final java.util.List<PackageManager.PackageInfo>[] indexHolder) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("包名，如 python");
        new AlertDialog.Builder(this)
            .setTitle("安装包")
            .setView(input)
            .setPositiveButton("安装", (DialogInterface d, int w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                installByName(name, indexHolder[0]);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void installByName(String name, java.util.List<PackageManager.PackageInfo> index) {
        if (index == null) {
            Toast.makeText(this, "请先刷新索引", Toast.LENGTH_SHORT).show();
            return;
        }
        PackageManager.PackageInfo target = null;
        for (PackageManager.PackageInfo p : index) {
            if (p.name.equals(name)) { target = p; break; }
        }
        if (target == null) {
            Toast.makeText(this, "索引中找不到包: " + name, Toast.LENGTH_LONG).show();
            return;
        }
        mPkgOutput.setVisibility(View.VISIBLE);
        mPkgOutput.setText("准备安装 " + target.getDisplayName() + " …");
        PackageManager.installPackage(this, target, new PackageManager.Callback() {
            @Override public void onProgress(String msg) {
                mPkgOutput.setText(msg);
            }
            @Override public void onSuccess(String summary) {
                mPkgOutput.setText(summary);
                Toast.makeText(SettingsActivity.this, "安装完成", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                mPkgOutput.setText("安装失败: " + message);
                Toast.makeText(SettingsActivity.this, "安装失败", Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 通用自检运行器：后台执行，结果累积进 mDiagLog（供复制），最新结果显示在输出区。 */
    private void runTest(String title, Task task) {
        mExecOutput.setVisibility(View.VISIBLE);
        mExecOutput.setText(title + " 运行中…");
        runInBackground(task, result -> {
            String entry = "--- " + title + " ---\n" + (result.isEmpty() ? "(无输出)" : result);
            mDiagLog.append(entry).append("\n\n");
            mExecOutput.setText(result.isEmpty() ? "(无输出)" : result);
        });
    }

    private interface Task { String run(); }
    private interface Done { void onDone(String result); }

    private void runInBackground(Task task, Done done) {
        new Thread(() -> {
            String r = task.run();
            mHandler.post(() -> done.onDone(r));
        }, "diag-task").start();
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
    }

    /** 镜像源选择对话框：单选，选中后立即持久化。
     *  下次拉索引/下载包即生效（无需重启 Activity）。 */
    private void showMirrorDialog() {
        final int current = PackageManager.getMirrorIndex(this);
        String[] labels = new String[PackageManager.MIRRORS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = PackageManager.MIRRORS[i][0];
        new AlertDialog.Builder(this)
            .setTitle("包镜像源")
            .setSingleChoiceItems(labels, current, (DialogInterface d, int which) -> {
                PackageManager.setMirrorIndex(this, which);
                Toast.makeText(this, "已切换: " + PackageManager.getMirrorLabel(which),
                    Toast.LENGTH_SHORT).show();
                d.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /** 检查 bootstrap 更新：拉 version.json → 对比当前版本 → 有新版弹确认对话框 */
    private void checkBootstrapUpdate() {
        mPkgOutput.setVisibility(View.VISIBLE);
        final String currentVer = BootstrapInstaller.getCurrentVersion(this);
        mPkgOutput.setText("检查更新中…（当前: " + (currentVer.isEmpty() ? "未知" : currentVer) + "）");
        BootstrapUpdater.checkUpdate(this, new BootstrapUpdater.CheckCallback() {
            @Override
            public void onResult(BootstrapUpdater.VersionInfo info, String currentVersion) {
                if (info == null) {
                    mPkgOutput.setText("已是最新版本（当前: "
                        + (currentVersion.isEmpty() ? "未知" : currentVersion) + "）");
                    Toast.makeText(SettingsActivity.this, "已是最新", Toast.LENGTH_SHORT).show();
                    return;
                }
                mPkgOutput.setText("发现新版 bootstrap:\n  当前: "
                    + (currentVersion.isEmpty() ? "未知" : currentVersion)
                    + "\n  最新: " + info.version
                    + "\n  大小: " + PackageManager.formatBytes(info.size)
                    + "\n  build: " + info.buildId);
                confirmBootstrapUpgrade(info);
            }
            @Override
            public void onError(String message) {
                mPkgOutput.setText("检查失败: " + message);
                Toast.makeText(SettingsActivity.this, "检查失败: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 弹确认对话框，用户确认后执行升级 */
    private void confirmBootstrapUpgrade(final BootstrapUpdater.VersionInfo info) {
        new AlertDialog.Builder(this)
            .setTitle("升级 bootstrap")
            .setMessage("将升级 bootstrap 到 " + info.version
                + "\n\n升级过程中:\n"
                + "  - 保留 var/installed/ 等已装包记录\n"
                + "  - 保留 hold 锁定标记\n"
                + "  - 替换 bin/ lib/ etc/ share/\n"
                + "  - 终端会话需重启才能用新版")
            .setPositiveButton("升级", (DialogInterface d, int w) -> performBootstrapUpgrade(info))
            .setNegativeButton("取消", null)
            .show();
    }

    /** 执行升级：progress 显示在 mPkgOutput */
    private void performBootstrapUpgrade(final BootstrapUpdater.VersionInfo info) {
        BootstrapUpdater.performUpgrade(this, info, new BootstrapUpdater.UpgradeCallback() {
            @Override
            public void onProgress(String msg) {
                mPkgOutput.setText("升级中: " + msg);
            }
            @Override
            public void onSuccess(String summary) {
                mPkgOutput.setText(summary + "\n\n请重启 App 让新版 bootstrap 生效。");
                Toast.makeText(SettingsActivity.this, summary, Toast.LENGTH_LONG).show();
            }
            @Override
            public void onError(String message) {
                mPkgOutput.setText("升级失败: " + message);
                Toast.makeText(SettingsActivity.this, "升级失败: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }
}
