package com.haisades;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

/**
 * bootstrap OTA 升级器：从 Releases 拉 bootstrap-version.json → 对比当前版本
 * → 下载新 zip → sha256 校验 → 解压到 usr.tmp（保留 var/installed 等用户状态）
 * → 原子 rename 替换 usr。
 *
 * 与 BootstrapInstaller 的关系：
 *   - BootstrapInstaller = 首次安装（从 APK assets 解压，无旧 var/ 可保留）
 *   - BootstrapUpdater   = 升级（从 Releases 下载 zip，必须保留 var/）
 *
 * version.json 由 build-system/make-bootstrap-version.sh 在 CI 端生成，
 * 通过 Releases latest/download/ 固定路径下发（与 packages.json 同源）。
 * App 端按当前镜像重写 URL，复用 PackageManager 的网络重试 + 镜像切换能力。
 */
public final class BootstrapUpdater {

    private static final String TAG = "BootstrapUpdater";

    /** bootstrap-version.json 的 GitHub 直连基准 URL（由重试层按镜像重写） */
    private static final String VERSION_JSON_URL =
        "https://github.com/" + PackageManager.RELEASE_REPO_OWNER + "/"
        + PackageManager.RELEASE_REPO_NAME + "/releases/latest/download/bootstrap-version.json";

    public interface CheckCallback {
        /** info=null 表示已是最新或拉到版本与当前相同；
         *  info!=null 表示有新版可升级。currentVersion 为当前安装的版本（可能为空）。 */
        void onResult(VersionInfo info, String currentVersion);
        void onError(String message);
    }

    public interface UpgradeCallback {
        void onProgress(String msg);
        void onSuccess(String summary);
        void onError(String message);
    }

    public static class VersionInfo {
        public final String version;
        public final String buildId;
        public final String filename;
        public final String sha256;
        public final long size;
        public final String downloadUrl;

        VersionInfo(JSONObject o) throws Exception {
            version = o.getString("version");
            buildId = o.optString("build_id", "");
            filename = o.getString("filename");
            sha256 = o.getString("sha256");
            size = o.getLong("size");
            downloadUrl = o.getString("download_url");
        }

        public String getDisplayName() { return version + " (" + buildId + ")"; }
    }

    /** 异步检查更新。callback 在主线程回调。
     *  判断逻辑：server version != current version 即视为有更新。
     *  current 为空（旧版 .bootstrap-ok 无 version 字段）也触发升级，
     *  让用户从"未知版本"升级到已知版本。 */
    public static void checkUpdate(Context ctx, CheckCallback cb) {
        new Thread(() -> {
            try {
                String body = PackageManager.httpGetJsonWithRetry(ctx, VERSION_JSON_URL);
                VersionInfo info = new VersionInfo(new JSONObject(body));
                String current = BootstrapInstaller.getCurrentVersion(ctx);
                boolean isNewer = !info.version.equals(current);
                Handler h = new Handler(Looper.getMainLooper());
                if (isNewer) {
                    h.post(() -> cb.onResult(info, current));
                } else {
                    h.post(() -> cb.onResult(null, current));
                }
            } catch (Exception e) {
                Log.e(TAG, "checkUpdate failed", e);
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onError(e.getMessage()));
            }
        }, "bootstrap-check").start();
    }

    /** 异步执行升级。callback 在主线程回调。
     *  流程：下载 zip → sha256 校验 → 调 BootstrapInstaller.installFromZip(keepVar=true) */
    public static void performUpgrade(Context ctx, VersionInfo info, UpgradeCallback cb) {
        new Thread(() -> {
            try {
                Handler h = new Handler(Looper.getMainLooper());

                // 1. 下载 zip 到 cache
                h.post(() -> cb.onProgress("下载 " + info.filename + " ..."));
                File cache = new File(App.PREFIX + "/var/cache/bootstrap");
                cache.mkdirs();
                File zip = new File(cache, info.filename);

                PackageManager.DownloadProgress dp = (downloaded, total) -> {
                    if (total > 0) {
                        int pct = (int) (downloaded * 100 / total);
                        h.post(() -> cb.onProgress("下载 " + pct + "% ("
                            + PackageManager.formatBytes(downloaded) + "/"
                            + PackageManager.formatBytes(total) + ")"));
                    } else {
                        h.post(() -> cb.onProgress("下载 " + PackageManager.formatBytes(downloaded)));
                    }
                };
                PackageManager.downloadFileWithRetry(ctx, info.downloadUrl, zip, dp);

                // 2. sha256 校验
                h.post(() -> cb.onProgress("校验 sha256 ..."));
                if (!PackageManager.sha256Matches(zip, info.sha256)) {
                    throw new RuntimeException("sha256 校验失败（文件损坏或被篡改）");
                }

                // 3. 调 BootstrapInstaller.installFromZip(keepVar=true) 保留 var/
                h.post(() -> cb.onProgress("解压并保留 var/ ..."));
                BootstrapInstaller.installFromZip(ctx, zip, true, info.version, info.buildId);

                String summary = "bootstrap 已升级到 " + info.version
                    + "（" + PackageManager.formatBytes(info.size) + "）";
                h.post(() -> cb.onSuccess(summary));
            } catch (Exception e) {
                Log.e(TAG, "performUpgrade failed", e);
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onError(e.getMessage()));
            }
        }, "bootstrap-upgrade").start();
    }
}
