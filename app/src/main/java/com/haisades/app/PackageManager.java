package com.haisades;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 包管理器：从 GitHub Releases 拉取 packages.json 索引 → 下载 tar.gz → sha256 校验
 * → 解压到 $PREFIX → 重建符号链接 → 记录文件清单。
 *
 * 索引格式见 build-system/make-packages-index.sh，关键字段:
 *   packages[].name / version / depends / sha256 / download_url / symlinks[]
 *
 * 安装流程（installPackage）：
 *   1. 拓扑展开依赖（递归），保证被依赖的包先装
 *   2. 对每个包：下载 → sha256 校验 → 解压到 $PREFIX → 重建符号链接
 *   3. 已安装版本相同则跳过（通过 $PREFIX/var/installed/<name> 记录版本号）
 *   4. 解压前后扫描 $PREFIX diff，新增文件记录到 <name>.files 清单
 *
 * 卸载（uninstallPackage）：读 <name>.files 清单，按引用计数删除文件。
 *   共享库可能被多个包引用（如 libz 被 openssl/curl/python 同时依赖），
 *   仅当引用计数=1（只被当前包引用）时才删除，避免误删导致其他包崩溃。
 */
public final class PackageManager {

    private static final String TAG = "PackageManager";

    /** 包发布仓库（必须 public，App 端无 token 访问）。
     *  开发仓库 XION-HN/haisa-des 是 private，不能直接给 App 用；
     *  发布资产统一推到公开仓库 XION-HN/haisa-des-repo 的 Releases。 */
    private static final String RELEASE_REPO_OWNER = "XION-HN";
    private static final String RELEASE_REPO_NAME  = "haisa-des-repo";

    /** packages.json 的下载 URL。
     *  用 GitHub Releases 的 latest/download/ 固定路径，自动 302 重定向到最新 release
     *  的 packages.json。相比调 GitHub API 查 tag 再拼 URL 的方案，此路径：
     *    - 无需 token（公开仓库资产下载通道）
     *    - 无 API 速率限制（未认证 API 仅 60 次/小时，多设备共享 IP 易耗尽）
     *    - 自动跟随最新 release，发新版无需改代码
     *  每个包的 tar.gz 下载 URL 由 packages.json 内的 download_url 字段提供，
     *  其中已含真实 tag 名，保证 sha256 校验与索引一致。 */
    private static final String INDEX_URL =
        "https://github.com/" + RELEASE_REPO_OWNER + "/" + RELEASE_REPO_NAME + "/releases/latest/download/packages.json";

    /** 已安装包记录目录：$PREFIX/var/installed/<name> 内容为版本号 */
    private static final String INSTALLED_DIR = App.PREFIX + "/var/installed";

    /** 下载缓存目录 */
    private static final String CACHE_DIR = App.PREFIX + "/var/cache/pkg";

    public interface Callback {
        void onProgress(String msg);
        void onSuccess(String summary);
        void onError(String message);
    }

    /** 下载进度回调（在调用线程触发，非主线程）。
     *  downloaded/total 单位字节；total < 0 表示服务器未返回 Content-Length。 */
    public interface DownloadProgress {
        void onProgress(long downloaded, long total);
    }

    /** 包元数据 */
    public static class PackageInfo {
        public final String name;
        public final String version;
        public final List<String> depends;
        public final long size;
        public final String sha256;
        public final String filename;
        public final String downloadUrl;
        public final List<String[]> symlinks;   // [link, target]
        // 版本约束预留：当前索引 depends 只是包名列表（无版本要求），
        // 若后续索引扩展为 "name>=minVersion" 格式，在此字段解析并交由 resolveDeps 校验。

        PackageInfo(JSONObject o) throws Exception {
            name = o.getString("name");
            version = o.getString("version");
            depends = new ArrayList<>();
            JSONArray arr = o.optJSONArray("depends");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) depends.add(arr.getString(i));
            }
            size = o.getLong("size");
            sha256 = o.getString("sha256");
            filename = o.getString("filename");
            downloadUrl = o.getString("download_url");
            symlinks = new ArrayList<>();
            JSONArray sl = o.optJSONArray("symlinks");
            if (sl != null) {
                for (int i = 0; i < sl.length(); i++) {
                    JSONObject s = sl.getJSONObject(i);
                    symlinks.add(new String[]{s.getString("link"), s.getString("target")});
                }
            }
        }

        public String getDisplayName() { return name + "-" + version; }
    }

    /** 异步拉取索引。callback 在主线程回调。 */
    public static void fetchIndex(Callback cb) {
        new Thread(() -> {
            try {
                List<PackageInfo> pkgs = fetchIndexSync();
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onSuccess(formatIndexSummary(pkgs)));
            } catch (Exception e) {
                Log.e(TAG, "fetchIndex failed", e);
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onError(e.getMessage()));
            }
        }, "pkg-fetch").start();
    }

    /** 同步拉取并解析索引。
     *  直接用 INDEX_URL（latest/download/ 固定路径），HttpURLConnection 默认
     *  followRedirects=true，会自动跟随 GitHub 的 302 到真实资产 URL。 */
    static List<PackageInfo> fetchIndexSync() throws Exception {
        URL url = new URL(INDEX_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "HaisaDes-PackageManager");
        try {
            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("HTTP " + conn.getResponseCode() + " 取索引失败: " + INDEX_URL);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.getJSONArray("packages");
            List<PackageInfo> pkgs = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                pkgs.add(new PackageInfo(arr.getJSONObject(i)));
            }
            return pkgs;
        } finally {
            conn.disconnect();
        }
    }

    /** 异步安装包（含依赖）。callback 在主线程回调。下载进度通过 onProgress 透传（百分比文字）。 */
    public static void installPackage(Context ctx, PackageInfo pkg, Callback cb) {
        new Thread(() -> {
            try {
                List<PackageInfo> index = fetchIndexSync();
                // 拓扑展开依赖
                List<PackageInfo> order = resolveDeps(pkg.name, index);
                StringBuilder log = new StringBuilder();
                for (PackageInfo p : order) {
                    final Handler h = new Handler(Looper.getMainLooper());
                    h.post(() -> cb.onProgress("安装 " + p.getDisplayName() + " ..."));
                    // 下载进度适配器：把字节进度转成百分比文字，post 到主线程
                    DownloadProgress dp = (downloaded, total) -> {
                        String msg;
                        if (total > 0) {
                            int pct = (int) (downloaded * 100 / total);
                            msg = "下载 " + p.name + " " + pct + "% ("
                                + formatBytes(downloaded) + "/" + formatBytes(total) + ")";
                        } else {
                            msg = "下载 " + p.name + " " + formatBytes(downloaded);
                        }
                        h.post(() -> cb.onProgress(msg));
                    };
                    String result = installOneSync(p, dp);
                    log.append(result).append('\n');
                }
                String summary = log.toString().trim();
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onSuccess(summary));
            } catch (Exception e) {
                Log.e(TAG, "install failed", e);
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onError(e.getMessage()));
            }
        }, "pkg-install").start();
    }

    /** 异步卸载包。callback 在主线程回调。
     *  注意：不递归卸载依赖（依赖可能被其他包使用），仅卸载指定包本身。
     *  调用方应先确认无其他已装包依赖此包，或允许孤儿依赖存在。 */
    public static void uninstallPackage(String pkgName, Callback cb) {
        new Thread(() -> {
            try {
                String result = uninstallOneSync(pkgName);
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onSuccess(result));
            } catch (Exception e) {
                Log.e(TAG, "uninstall failed", e);
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onError(e.getMessage()));
            }
        }, "pkg-uninstall").start();
    }

    /** 卸载单个包：读文件清单 → 按引用计数删除文件 → 清除清单和版本标记。
     *  引用计数：遍历所有 *.files 清单，统计每个路径被多少个包引用。
     *  仅当引用计数=1（只被当前包引用）时才删文件，避免误删共享库。 */
    static String uninstallOneSync(String pkgName) throws Exception {
        String ver = getInstalledVersion(pkgName);
        if (ver == null) {
            return pkgName + " 未安装，无需卸载";
        }
        List<String> files = readFileList(pkgName);

        // 构建全局引用计数表：path → 引用它的包数量
        Map<String, Integer> refCount = buildFileReferenceCount();

        int deleted = 0, skipped = 0;
        // 逆序删除：先删文件，再删可能变空的目录
        Collections.sort(files, Collections.reverseOrder());
        for (String rel : files) {
            int count = refCount.getOrDefault(rel, 0);
            if (count > 1) {
                // 被其他包引用，跳过
                skipped++;
                continue;
            }
            File f = new File(App.PREFIX, rel);
            if (f.exists()) {
                // 符号链接和普通文件都走 delete()（符号链接删链接本身不删目标）
                if (f.delete()) {
                    deleted++;
                }
            }
        }
        // 清理可能变空的父目录（不递归删非空目录）
        cleanupEmptyDirs(files);

        // 删除文件清单和版本标记
        new File(INSTALLED_DIR, pkgName + ".files").delete();
        new File(INSTALLED_DIR, pkgName).delete();

        return pkgName + "-" + ver + " 已卸载（删除 " + deleted + " 个文件，跳过 " + skipped + " 个共享文件）";
    }

    /** 扫描所有已装包的 *.files 清单，构建 path → 引用计数 表 */
    private static Map<String, Integer> buildFileReferenceCount() {
        Map<String, Integer> count = new HashMap<>();
        File dir = new File(INSTALLED_DIR);
        File[] lists = dir.listFiles((FilenameFilter) (d, name) -> name.endsWith(".files"));
        if (lists == null) return count;
        for (File lf : lists) {
            for (String rel : readFileList(stripSuffix(lf.getName(), ".files"))) {
                count.merge(rel, 1, Integer::sum);
            }
        }
        return count;
    }

    /** 删除文件清单中标记的空目录（仅删确实为空的） */
    private static void cleanupEmptyDirs(List<String> files) {
        // 收集所有目录路径（清单里目录和文件的父目录）
        Set<String> dirs = new HashSet<>();
        for (String rel : files) {
            File f = new File(rel);
            File parent = f.getParentFile();
            while (parent != null && !parent.getPath().equals(".")) {
                dirs.add(parent.getPath());
                parent = parent.getParentFile();
            }
        }
        // 从深到浅尝试删空目录
        List<String> sortedDirs = new ArrayList<>(dirs);
        Collections.sort(sortedDirs, Collections.reverseOrder());
        for (String rel : sortedDirs) {
            File d = new File(App.PREFIX, rel);
            if (d.isDirectory()) {
                String[] children = d.list();
                if (children == null || children.length == 0) {
                    d.delete();
                }
            }
        }
    }

    private static String stripSuffix(String s, String suffix) {
        return s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s;
    }

    /** 递归展开依赖（被依赖的包先），返回安装顺序列表 */
    static List<PackageInfo> resolveDeps(String pkgName, List<PackageInfo> index) throws Exception {
        // 建 name → PackageInfo 映射
        java.util.Map<String, PackageInfo> map = new java.util.HashMap<>();
        for (PackageInfo p : index) map.put(p.name, p);

        List<PackageInfo> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();   // 检测循环依赖

        visit(pkgName, map, order, visited, visiting);
        // 反转：被依赖的在前
        Collections.reverse(order);
        return order;
    }

    private static void visit(String name, java.util.Map<String, PackageInfo> map,
                              List<PackageInfo> order, Set<String> visited, Set<String> visiting)
            throws Exception {
        if (visited.contains(name)) return;
        if (visiting.contains(name)) {
            throw new RuntimeException("检测到循环依赖: " + name);
        }
        visiting.add(name);
        PackageInfo p = map.get(name);
        if (p == null) {
            throw new RuntimeException("索引中找不到包: " + name);
        }
        for (String dep : p.depends) {
            visit(dep, map, order, visited, visiting);
        }
        visiting.remove(name);
        visited.add(name);
        order.add(p);
    }

    /** 安装单个包：下载 → 校验 → （升级时清理旧版残留）→ 解压 → 重建符号链接 → 记录文件清单 → 记录已安装 */
    static String installOneSync(PackageInfo pkg) throws Exception {
        return installOneSync(pkg, null);
    }

    /** 安装单个包（可带下载进度回调）：下载 → 校验 → （升级时清理旧版残留）→ 解压 → 重建符号链接 → 记录文件清单 → 记录已安装 */
    static String installOneSync(PackageInfo pkg, DownloadProgress progress) throws Exception {
        // 已安装相同版本则跳过
        String installedVer = getInstalledVersion(pkg.name);
        if (pkg.version.equals(installedVer)) {
            return pkg.getDisplayName() + " 已是最新（" + pkg.version + "），跳过";
        }

        boolean isUpgrade = installedVer != null;

        File cacheDir = new File(CACHE_DIR);
        cacheDir.mkdirs();
        File tarball = new File(cacheDir, pkg.filename);

        // 下载（如缓存命中且 sha256 匹配则跳过下载）
        if (!tarball.exists() || !sha256Matches(tarball, pkg.sha256)) {
            downloadTo(pkg.downloadUrl, tarball, progress);
        }
        // 下载后再次校验
        if (!sha256Matches(tarball, pkg.sha256)) {
            throw new RuntimeException(pkg.filename + " sha256 校验失败（文件损坏或被篡改）");
        }

        // 升级场景：先列出新版将包含的文件集（tar 内文件 + 符号链接），
        // 删除旧版清单中"新版不再包含"的文件，避免旧版残留堆积。
        // 共享库仍按引用计数保护（被其他包引用则跳过）。
        int upgradedRemoved = 0;
        if (isUpgrade) {
            Set<String> newFileSet = listTarballFiles(tarball, pkg.symlinks);
            upgradedRemoved = cleanupStaleFiles(pkg.name, newFileSet);
        }

        // 解压前扫描 $PREFIX 现有文件集合（用于后续 diff 出新增文件）
        Set<String> before = scanPrefixFiles();

        // 解压到 $PREFIX
        extractTarGz(tarball, new File(App.PREFIX));

        // 重建符号链接（tar.gz 不含 symlink）
        for (String[] sl : pkg.symlinks) {
            File link = new File(App.PREFIX, sl[0]);
            File parent = link.getParentFile();
            if (parent != null) parent.mkdirs();
            link.delete();
            Os.symlink(sl[1], link.getAbsolutePath());
        }

        // chmod bin/ 下新解压的可执行文件
        chmodBin(new File(App.PREFIX, "bin"));

        // 解压后再扫描，diff 出本包新增的文件/符号链接，记录到清单
        Set<String> after = scanPrefixFiles();
        List<String> newFiles = new ArrayList<>();
        for (String p : after) {
            if (!before.contains(p)) newFiles.add(p);
        }
        // 符号链接清单里的 link 路径也要并入（scanPrefixFiles 已含符号链接，但保险起见补一次）
        for (String[] sl : pkg.symlinks) {
            if (!newFiles.contains(sl[0])) newFiles.add(sl[0]);
        }
        recordFileList(pkg.name, newFiles);

        // 记录已安装版本
        recordInstalled(pkg.name, pkg.version);

        if (isUpgrade) {
            return pkg.getDisplayName() + " 升级完成 " + installedVer + " → " + pkg.version
                + "（新增 " + newFiles.size() + " 个文件，清理 " + upgradedRemoved + " 个旧版残留）";
        }
        return pkg.getDisplayName() + " 安装完成（" + newFiles.size() + " 个文件）";
    }

    /** 扫描 $PREFIX 下所有常规文件和符号链接，返回相对 prefix 的路径集合。
     *  用于安装前后 diff 出新增文件，记录到文件清单供卸载时使用。 */
    private static Set<String> scanPrefixFiles() {
        Set<String> out = new HashSet<>();
        File root = new File(App.PREFIX);
        scanPrefixFiles(root, root, out);
        return out;
    }

    private static void scanPrefixFiles(File root, File dir, Set<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            String rel = root.toPath().relativize(f.toPath()).toString();
            // var/installed/ 是包管理器自己的状态目录，不计入清单
            if (rel.startsWith("var/installed/")) continue;
            if (f.isDirectory()) {
                out.add(rel);
                scanPrefixFiles(root, f, out);
            } else {
                out.add(rel);
            }
        }
    }

    /** 记录包的文件清单到 $PREFIX/var/installed/<name>.files，每行一个相对路径 */
    private static void recordFileList(String name, List<String> files) throws Exception {
        File dir = new File(INSTALLED_DIR);
        dir.mkdirs();
        File f = new File(dir, name + ".files");
        StringBuilder sb = new StringBuilder();
        for (String p : files) sb.append(p).append('\n');
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(sb.toString().getBytes());
        }
    }

    /** 读取包的文件清单，返回相对路径列表；无清单返回空列表 */
    private static List<String> readFileList(String name) {
        File f = new File(INSTALLED_DIR, name + ".files");
        if (!f.isFile()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f)))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) out.add(line);
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
        return out;
    }

    /** 读取已安装版本号，未安装返回 null */
    static String getInstalledVersion(String name) {
        File f = new File(INSTALLED_DIR, name);
        if (!f.isFile()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f)))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    /** 列出所有已安装包及其版本，返回 name → version 映射 */
    public static java.util.Map<String, String> listInstalledSync() {
        java.util.Map<String, String> out = new HashMap<>();
        File dir = new File(INSTALLED_DIR);
        File[] files = dir.listFiles((FilenameFilter) (d, n) ->
            !n.endsWith(".files") && !n.startsWith("."));
        if (files == null) return out;
        for (File f : files) {
            String ver = getInstalledVersion(f.getName());
            if (ver != null) out.put(f.getName(), ver);
        }
        return out;
    }

    /** 记录已安装版本 */
    static void recordInstalled(String name, String version) throws Exception {
        File dir = new File(INSTALLED_DIR);
        dir.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
            fos.write(version.getBytes());
        }
    }

    /** 下载到文件（无进度回调） */
    static void downloadTo(String urlStr, File dest) throws Exception {
        downloadTo(urlStr, dest, null);
    }

    /** 下载到文件，支持断点续传和进度回调。
     *  断点续传：若本地已有部分文件（且大小 < 远程总大小），发 Range 请求续传剩余部分，
     *  追加写入本地文件。GitHub Releases 的 CDN 支持 Range，省流量且大包中断后可恢复。
     *  resume 失败（如服务器不支持 Range 或返回 200）时回退为整文件覆盖下载。
     *  进度回调每下载 64KB 触发一次，total<0 表示未知总大小。 */
    static void downloadTo(String urlStr, File dest, DownloadProgress progress) throws Exception {
        // 先探一次总大小（HEAD 请求），并判断是否可续传
        long totalSize = -1;
        boolean canResume = false;
        long existingLen = dest.exists() ? dest.length() : 0;

        if (existingLen > 0) {
            // 探测服务器是否支持 Range + 取总大小
            URL headUrl = new URL(urlStr);
            HttpURLConnection headConn = (HttpURLConnection) headUrl.openConnection();
            headConn.setConnectTimeout(15000);
            headConn.setReadTimeout(15000);
            headConn.setRequestMethod("HEAD");
            try {
                if (headConn.getResponseCode() == 200) {
                    totalSize = headConn.getContentLengthLong();
                    String acceptRanges = headConn.getHeaderField("Accept-Ranges");
                    canResume = "bytes".equalsIgnoreCase(acceptRanges) && totalSize > 0;
                }
            } finally {
                headConn.disconnect();
            }
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        boolean appendMode = false;
        long startByte = 0;

        if (canResume && existingLen < totalSize) {
            // 续传：从已有字节数开始
            conn.setRequestProperty("Range", "bytes=" + existingLen + "-");
            appendMode = true;
            startByte = existingLen;
        }

        try {
            int code = conn.getResponseCode();
            // 206 = Partial Content（续传成功），200 = 服务器忽略 Range 返回全量
            if (code != 200 && code != 206) {
                throw new RuntimeException("HTTP " + code + " 下载 " + urlStr);
            }
            if (code == 200) {
                // 服务器没走 Range，整文件覆盖
                appendMode = false;
                startByte = 0;
                totalSize = conn.getContentLengthLong();
            } else if (code == 206) {
                // 续传时取 Content-Range 里的总大小（格式 bytes start-end/total）
                String contentRange = conn.getHeaderField("Content-Range");
                if (contentRange != null && contentRange.contains("/")) {
                    try {
                        totalSize = Long.parseLong(contentRange.substring(contentRange.indexOf('/') + 1));
                    } catch (NumberFormatException ignore) {}
                }
            }

            long downloaded = startByte;
            long lastReported = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(dest, appendMode)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (progress != null && downloaded - lastReported >= 64 * 1024) {
                        progress.onProgress(downloaded, totalSize);
                        lastReported = downloaded;
                    }
                }
                // 下载完成报一次最终进度
                if (progress != null) {
                    progress.onProgress(downloaded, totalSize > 0 ? totalSize : downloaded);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    /** 字节数格式化为人类可读（如 1.2 MB），用于下载进度显示 */
    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /** sha256 校验 */
    static boolean sha256Matches(File f, String expected) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString().equals(expected);
        } catch (Exception e) {
            return false;
        }
    }

    /** 解压 tar.gz 到 destDir。用设备上的 tar 命令（toybox 提供），避免引入第三方库。
     *  tar 路径已经写死为 $PREFIX/bin/tar，BootstrapInstaller 保证存在。 */
    static void extractTarGz(File tarball, File destDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            App.PREFIX + "/bin/tar", "-xzf", tarball.getAbsolutePath(),
            "-C", destDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // 读取输出防止 pipe 阻塞
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (!p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroy();
            throw new RuntimeException("tar 解压超时");
        }
        int code = p.exitValue();
        if (code != 0) {
            throw new RuntimeException("tar 退出码 " + code + ": " + out.toString().trim());
        }
    }

    /** chmod bin/ 目录下所有文件为 0755 */
    static void chmodBin(File binDir) {
        File[] files = binDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile()) {
                try {
                    Os.chmod(f.getAbsolutePath(), 0755);
                } catch (Exception e) {
                    Log.w(TAG, "chmod failed: " + f + " " + e);
                }
            }
        }
    }

    /** 列出 tarball 内文件清单 + 符号链接，返回相对 prefix 的路径集合。
     *  用于升级前对比旧版清单，找出"新版不再包含"的文件。
     *  tar -tzf 输出形如 ./bin/foo 或 bin/foo，统一去掉前导 ./ */
    private static Set<String> listTarballFiles(File tarball, List<String[]> symlinks) throws Exception {
        Set<String> out = new HashSet<>();
        ProcessBuilder pb = new ProcessBuilder(
            App.PREFIX + "/bin/tar", "-tzf", tarball.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // 去掉 tar 路径前缀 ./
                if (line.startsWith("./")) line = line.substring(2);
                if (!line.isEmpty()) out.add(line);
            }
        }
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroy();
            throw new RuntimeException("tar 列文件超时");
        }
        // 符号链接 link 路径也要并入
        for (String[] sl : symlinks) out.add(sl[0]);
        return out;
    }

    /** 升级时清理旧版残留：删除旧版文件清单中"新版不再包含"的文件。
     *  共享库按引用计数保护（被其他包引用则跳过）。
     *  返回实际删除的文件数。 */
    private static int cleanupStaleFiles(String pkgName, Set<String> newFileSet) {
        List<String> oldFiles = readFileList(pkgName);
        if (oldFiles.isEmpty()) return 0;

        Map<String, Integer> refCount = buildFileReferenceCount();
        int removed = 0;
        // 逆序删，便于后续清理空目录
        List<String> sorted = new ArrayList<>(oldFiles);
        Collections.sort(sorted, Collections.reverseOrder());
        for (String rel : sorted) {
            // 新版仍含此文件，保留（解压时会覆盖）
            if (newFileSet.contains(rel)) continue;
            // 被其他包引用，保留
            int count = refCount.getOrDefault(rel, 0);
            if (count > 1) continue;
            File f = new File(App.PREFIX, rel);
            if (f.exists() && f.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            cleanupEmptyDirs(oldFiles);
        }
        return removed;
    }

    /** 格式化索引摘要供 UI 显示 */
    static String formatIndexSummary(List<PackageInfo> pkgs) {
        StringBuilder sb = new StringBuilder();
        sb.append("仓库共 ").append(pkgs.size()).append(" 个包:\n\n");
        for (PackageInfo p : pkgs) {
            String installed = getInstalledVersion(p.name);
            String status = (installed != null) ? ("[已装 " + installed + "]") : "[未安装]";
            String sizeStr = formatSize(p.size);
            sb.append(String.format("%-20s %-12s %-10s %s",
                    p.name, p.version, sizeStr, status));
            if (!p.depends.isEmpty()) {
                sb.append("  deps: ").append(String.join(",", p.depends));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1048576) return String.format("%.1fMB", bytes / 1048576.0);
        if (bytes >= 1024) return String.format("%.1fKB", bytes / 1024.0);
        return bytes + "B";
    }
}
