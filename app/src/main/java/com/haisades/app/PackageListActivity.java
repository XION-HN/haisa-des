package com.haisades;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 包列表页：展示索引中的所有包，按状态分类（已装/可升级/未安装），
 * 支持搜索过滤、点项弹详情、一键升级全部可升级包。
 *
 * 数据来源：先拉索引（fetchIndexSync）+ 读已装版本（listInstalledSync）。
 * 不在主线程做网络/文件 IO，启动时后台加载，加载完刷新 ListView。
 */
public class PackageListActivity extends Activity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private List<PackageManager.PackageInfo> mAllPackages = new ArrayList<>();
    private Map<String, String> mInstalled = new HashMap<>();
    private final List<PackageManager.PackageInfo> mFiltered = new ArrayList<>();

    private PkgAdapter mAdapter;
    private TextView mStatusBar;
    private TextView mEmptyView;
    private EditText mSearchInput;
    private Button mUpgradeAllBtn;

    /** 状态枚举（用于排序和展示） */
    private static final int STATE_UPGRADABLE = 0;  // 已装但版本低于索引版本
    private static final int STATE_INSTALLED = 1;   // 已装且最新
    private static final int STATE_NOT_INSTALLED = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_package_list);

        mStatusBar = findViewById(R.id.status_bar);
        mEmptyView = findViewById(R.id.empty_view);
        mSearchInput = findViewById(R.id.search_input);
        mUpgradeAllBtn = findViewById(R.id.btn_upgrade_all);
        ListView list = findViewById(R.id.pkg_list);

        mAdapter = new PkgAdapter();
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            PackageManager.PackageInfo pkg = mFiltered.get(position);
            showDetailDialog(pkg);
        });

        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(s.toString().trim()); }
        });

        mUpgradeAllBtn.setOnClickListener(v -> upgradeAll());

        loadData();
    }

    private void loadData() {
        mStatusBar.setText("拉取索引中…");
        mUpgradeAllBtn.setEnabled(false);
        new Thread(() -> {
            try {
                mAllPackages = PackageManager.fetchIndexSync(this);
                mInstalled = PackageManager.listInstalledSync();
                mHandler.post(() -> {
                    applyFilter("");
                    updateStatusBar();
                    mUpgradeAllBtn.setEnabled(true);
                });
            } catch (Exception e) {
                mHandler.post(() -> {
                    mStatusBar.setText("加载失败: " + e.getMessage());
                    mEmptyView.setVisibility(View.VISIBLE);
                    mEmptyView.setText("加载失败");
                });
            }
        }, "pkg-list-load").start();
    }

    private void applyFilter(String keyword) {
        mFiltered.clear();
        for (PackageManager.PackageInfo p : mAllPackages) {
            if (keyword.isEmpty() || p.name.toLowerCase().contains(keyword.toLowerCase())) {
                mFiltered.add(p);
            }
        }
        // 排序：可升级 → 已装 → 未安装，同状态按包名
        Collections.sort(mFiltered, new Comparator<PackageManager.PackageInfo>() {
            @Override public int compare(PackageManager.PackageInfo a, PackageManager.PackageInfo b) {
                int sa = getState(a), sb = getState(b);
                if (sa != sb) return Integer.compare(sa, sb);
                return a.name.compareTo(b.name);
            }
        });
        mAdapter.notifyDataSetChanged();
        mEmptyView.setVisibility(mFiltered.isEmpty() ? View.VISIBLE : View.GONE);
        if (mFiltered.isEmpty()) {
            mEmptyView.setText(keyword.isEmpty() ? "索引为空" : "无匹配包");
        }
    }

    private int getState(PackageManager.PackageInfo p) {
        String installed = mInstalled.get(p.name);
        if (installed == null) return STATE_NOT_INSTALLED;
        if (!installed.equals(p.version)) return STATE_UPGRADABLE;
        return STATE_INSTALLED;
    }

    private void updateStatusBar() {
        int upgradable = 0, installed = 0, notInstalled = 0;
        for (PackageManager.PackageInfo p : mAllPackages) {
            int s = getState(p);
            if (s == STATE_UPGRADABLE) upgradable++;
            else if (s == STATE_INSTALLED) installed++;
            else notInstalled++;
        }
        mStatusBar.setText(String.format(
            "共 %d 个包 | 已装 %d | 可升级 %d | 未安装 %d",
            mAllPackages.size(), installed, upgradable, notInstalled));
    }

    private void showDetailDialog(PackageManager.PackageInfo pkg) {
        int state = getState(pkg);
        boolean held = PackageManager.isHeld(pkg.name);
        String statusText;
        String actionText;
        switch (state) {
            case STATE_UPGRADABLE:
                statusText = "可升级：已装 " + mInstalled.get(pkg.name) + " → 索引 " + pkg.version;
                if (held) statusText += "  [已锁定]";
                actionText = "升级";
                break;
            case STATE_INSTALLED:
                statusText = "已安装（最新）：" + pkg.version;
                if (held) statusText += "  [已锁定]";
                actionText = "卸载";
                break;
            default:
                statusText = "未安装";
                actionText = "安装";
                break;
        }
        StringBuilder deps = new StringBuilder();
        if (pkg.depends.isEmpty()) deps.append("（无）");
        else for (String d : pkg.depends) deps.append(d).append(' ');
        String sizeStr = formatSize(pkg.size);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle(pkg.name + "-" + pkg.version)
            .setMessage("状态: " + statusText
                + "\n大小: " + sizeStr
                + "\n依赖: " + deps.toString().trim()
                + "\nsha256: " + pkg.sha256.substring(0, 12) + "…")
            .setPositiveButton(actionText, (DialogInterface d, int w) -> {
                if (state == STATE_INSTALLED) {
                    doUninstall(pkg);
                } else {
                    doInstall(pkg);
                }
            })
            .setNegativeButton("关闭", null);

        // 已装包（含可升级）才显示锁定/解锁按钮
        if (state == STATE_INSTALLED || state == STATE_UPGRADABLE) {
            final String holdBtnText = held ? "取消锁定" : "锁定版本";
            b.setNeutralButton(holdBtnText, (DialogInterface d, int w) -> {
                PackageManager.setHeld(pkg.name, !held);
                applyFilter(mSearchInput.getText().toString().trim());
                updateStatusBar();
                Toast.makeText(this,
                    !held ? "已锁定 " + pkg.name + "（upgradeAll 将跳过）"
                          : "已解锁 " + pkg.name,
                    Toast.LENGTH_SHORT).show();
            });
        }
        b.show();
    }

    private void doInstall(PackageManager.PackageInfo pkg) {
        mStatusBar.setText("正在安装 " + pkg.getDisplayName() + " …");
        mUpgradeAllBtn.setEnabled(false);
        PackageManager.installPackage(this, pkg, new PackageManager.Callback() {
            @Override public void onProgress(String msg) { mStatusBar.setText(msg); }
            @Override public void onSuccess(String summary) {
                mStatusBar.setText(summary);
                // 刷新已装状态
                mInstalled = PackageManager.listInstalledSync();
                applyFilter(mSearchInput.getText().toString().trim());
                updateStatusBar();
                mUpgradeAllBtn.setEnabled(true);
                Toast.makeText(PackageListActivity.this, "操作完成", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                mStatusBar.setText("失败: " + message);
                mUpgradeAllBtn.setEnabled(true);
                Toast.makeText(PackageListActivity.this, "失败: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void doUninstall(PackageManager.PackageInfo pkg) {
        mStatusBar.setText("正在卸载 " + pkg.name + " …");
        mUpgradeAllBtn.setEnabled(false);
        PackageManager.uninstallPackage(pkg.name, new PackageManager.Callback() {
            @Override public void onProgress(String msg) { mStatusBar.setText(msg); }
            @Override public void onSuccess(String summary) {
                mStatusBar.setText(summary);
                mInstalled = PackageManager.listInstalledSync();
                applyFilter(mSearchInput.getText().toString().trim());
                updateStatusBar();
                mUpgradeAllBtn.setEnabled(true);
                Toast.makeText(PackageListActivity.this, "卸载完成", Toast.LENGTH_SHORT).show();
            }
            @Override public void onError(String message) {
                mStatusBar.setText("卸载失败: " + message);
                mUpgradeAllBtn.setEnabled(true);
            }
        });
    }

    /** 一键升级所有可升级包：串行安装，依赖拓扑由 installPackage 内部 resolveDeps 处理。
     *  被 hold 的包跳过（防意外升级破坏兼容性）。 */
    private void upgradeAll() {
        final List<PackageManager.PackageInfo> targets = new ArrayList<>();
        final List<String> heldSkipped = new ArrayList<>();
        for (PackageManager.PackageInfo p : mAllPackages) {
            if (getState(p) == STATE_UPGRADABLE) {
                if (PackageManager.isHeld(p.name)) {
                    heldSkipped.add(p.name);
                } else {
                    targets.add(p);
                }
            }
        }
        if (targets.isEmpty()) {
            String msg = "没有可升级的包";
            if (!heldSkipped.isEmpty()) {
                msg += "（" + heldSkipped.size() + " 个被锁定跳过: " + String.join(", ", heldSkipped) + "）";
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }
        String msg = "将升级 " + targets.size() + " 个包：\n" + joinNames(targets);
        if (!heldSkipped.isEmpty()) {
            msg += "\n\n被锁定跳过 " + heldSkipped.size() + " 个: " + String.join(", ", heldSkipped)
                 + "\n（如需升级，先在详情里取消锁定）";
        }
        msg += "\n\n将串行下载安装，请耐心等待。";
        new AlertDialog.Builder(this)
            .setTitle("一键升级")
            .setMessage(msg)
            .setPositiveButton("升级", (DialogInterface d, int w) -> runUpgradeAll(targets))
            .setNegativeButton("取消", null)
            .show();
    }

    private void runUpgradeAll(final List<PackageManager.PackageInfo> targets) {
        mUpgradeAllBtn.setEnabled(false);
        new Thread(() -> {
            final StringBuilder log = new StringBuilder();
            int ok = 0, fail = 0;
            for (PackageManager.PackageInfo p : targets) {
                final String name = p.name;
                mHandler.post(() -> mStatusBar.setText("升级 " + name + " …"));
                try {
                    // installOneSync 内部 downloadFileWithRetry 会按当前镜像重写 + 重试 + 切换
                    String result = PackageManager.installOneSync(PackageListActivity.this, p, null);
                    log.append(result).append('\n');
                    ok++;
                } catch (Exception e) {
                    log.append(name).append(" 失败: ").append(e.getMessage()).append('\n');
                    fail++;
                }
            }
            final int okF = ok, failF = fail;
            mHandler.post(() -> {
                mInstalled = PackageManager.listInstalledSync();
                applyFilter(mSearchInput.getText().toString().trim());
                updateStatusBar();
                mUpgradeAllBtn.setEnabled(true);
                mStatusBar.setText("升级完成: 成功 " + okF + " 失败 " + failF);
                new AlertDialog.Builder(PackageListActivity.this)
                    .setTitle("升级结果")
                    .setMessage(log.toString().trim())
                    .setPositiveButton("确定", null)
                    .show();
            });
        }, "pkg-upgrade-all").start();
    }

    private String joinNames(List<PackageManager.PackageInfo> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkgs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(pkgs.get(i).name);
        }
        return sb.toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    private class PkgAdapter extends BaseAdapter {
        @Override public int getCount() { return mFiltered.size(); }
        @Override public Object getItem(int position) { return mFiltered.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(PackageListActivity.this)
                    .inflate(R.layout.pkg_list_item, parent, false);
            }
            PackageManager.PackageInfo pkg = mFiltered.get(position);
            int state = getState(pkg);

            ((TextView) convertView.findViewById(R.id.pkg_name)).setText(pkg.name);

            TextView tag = convertView.findViewById(R.id.pkg_status_tag);
            TextView versionInfo = convertView.findViewById(R.id.pkg_version_info);
            TextView sizeInfo = convertView.findViewById(R.id.pkg_size_info);
            TextView actionBtn = convertView.findViewById(R.id.pkg_action_btn);

            sizeInfo.setText(formatSize(pkg.size));

            // 锁定标记后缀（仅已装包显示）
            String heldSuffix = PackageManager.isHeld(pkg.name) ? "  [锁]" : "";

            switch (state) {
                case STATE_UPGRADABLE:
                    tag.setText("可升级" + heldSuffix);
                    tag.setTextColor(0xFFFFCC80);
                    versionInfo.setText(mInstalled.get(pkg.name) + " → " + pkg.version);
                    actionBtn.setText("升级");
                    actionBtn.setTextColor(0xFFFFCC80);
                    break;
                case STATE_INSTALLED:
                    tag.setText("已装" + heldSuffix);
                    tag.setTextColor(0xFFA5D6A7);
                    versionInfo.setText(pkg.version);
                    actionBtn.setText("卸载");
                    actionBtn.setTextColor(0xFFEF9A9A);
                    break;
                default:
                    tag.setText("未装");
                    tag.setTextColor(0xFF888888);
                    versionInfo.setText(pkg.version);
                    actionBtn.setText("安装");
                    actionBtn.setTextColor(0xFFA5D6A7);
                    break;
            }
            return convertView;
        }
    }
}
