package com.haisades;

import android.content.Context;
import android.content.SharedPreferences;
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
     *  发布资产统一推到公开仓库 XION-HN/haisa-des-repo 的 Releases。
     *  包级可见：BootstrapUpdater 拼 version.json URL 时复用。 */
    static final String RELEASE_REPO_OWNER = "XION-HN";
    static final String RELEASE_REPO_NAME  = "haisa-des-repo";

    /** GitHub 直连基准 URL（所有镜像都是对它的代理/前缀包装） */
    private static final String GITHUB_BASE =
        "https://github.com/" + RELEASE_REPO_OWNER + "/" + RELEASE_REPO_NAME;

    /** 预置镜像列表。
     *  每个镜像 = 标签 + URL 前缀。
     *  applyMirror() 把 https://github.com/.../releases/download/.../file
     *  改写为 <prefix>https://github.com/.../releases/download/.../file
     *  （前缀式代理，如 ghproxy.com / gh-proxy.com）。
     *  index=0 为默认（直连，prefix=""）。 */
    public static final String[][] MIRRORS = {
        // {label, prefix}
        {"默认（GitHub 直连）", ""},
        {"ghproxy.com",         "https://ghproxy.com/"},
        {"gh-proxy.com",        "https://gh-proxy.com/"},
    };
    private static final int DEFAULT_MIRROR_INDEX = 0;

    private static final String SP_NAME  = "pkg_prefs";
    private static final String SP_MIRROR = "mirror_index";

    /** 当前生效的镜像索引（SharedPreferences 持久化） */
    public static int getMirrorIndex(Context ctx) {
        return ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                 .getInt(SP_MIRROR, DEFAULT_MIRROR_INDEX);
    }

    public static void setMirrorIndex(Context ctx, int index) {
        if (index < 0 || index >= MIRRORS.length) index = DEFAULT_MIRROR_INDEX;
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
           .edit().putInt(SP_MIRROR, index).apply();
    }

    public static String getMirrorLabel(int index) {
        if (index < 0 || index >= MIRRORS.length) return MIRRORS[DEFAULT_MIRROR_INDEX][0];
        return MIRRORS[index][0];
    }

    /** 用指定镜像索引重写 URL（不读 SP，用于重试时环形切换镜像） */
    static String applyMirrorWithIndex(int idx, String url) {
        if (idx < 0 || idx >= MIRRORS.length) idx = DEFAULT_MIRROR_INDEX;
        String prefix = MIRRORS[idx][1];
        if (prefix == null || prefix.isEmpty()) return url;
        if (!url.startsWith("https://github.com/")) return url;
        return prefix + url;
    }

    // ------------------------------------------------------------------
    // 网络健壮性：指数退避 + 镜像环形切换
    //
    // 设计：单次请求失败不直接抛异常，先重试 2 次（间隔 1s/2s）；
    // 仍失败则切换到下一个镜像（环形遍历 MIRRORS），再重试 2 次；
    // 所有镜像都失败才真正抛异常。
    // 不写回 SP：保留用户首选镜像，下次会话仍从首选开始。
    // ------------------------------------------------------------------

    /** 单次 HTTP GET 取字符串。 */
    private static String httpGetString(String url, int timeoutMs) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "HaisaDes-PackageManager");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new RuntimeException("HTTP " + code + " : " + url);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** 索引专用：环形遍历镜像 + 指数退避重试，返回响应字符串。
     *  从用户首选镜像开始，每个镜像重试 MAX_RETRIES_PER_MIRROR 次，
     *  间隔 BACKOFF_BASE_MS * 2^attempt。所有镜像都失败才抛异常。 */
    static String httpGetJsonWithRetry(Context ctx, String originalUrl) throws Exception {
        final int userMirror = getMirrorIndex(ctx);
        final int n = MIRRORS.length;
        Exception lastErr = null;
        for (int offset = 0; offset < n; offset++) {
            int m = (userMirror + offset) % n;
            String url = applyMirrorWithIndex(m, originalUrl);
            for (int attempt = 0; attempt < MAX_RETRIES_PER_MIRROR; attempt++) {
                try {
                    return httpGetString(url, 30000);
                } catch (Exception e) {
                    lastErr = e;
                    Log.w(TAG, "GET " + url + " 失败(尝试 " + (attempt + 1)
                        + "/" + MAX_RETRIES_PER_MIRROR + ", 镜像=" + MIRRORS[m][0] + "): " + e.getMessage());
                    if (attempt < MAX_RETRIES_PER_MIRROR - 1) {
                        Thread.sleep(BACKOFF_BASE_MS * (1L << attempt)); // 1s, 2s
                    }
                }
            }
        }
        throw new RuntimeException("所有镜像均失败: " + lastErr.getMessage(), lastErr);
    }

    /** 包下载专用：环形遍历镜像 + 指数退避重试。
     *  内部调 downloadTo（含断点续传），失败切镜像重试。
     *  断点续传的 dest 已有部分内容，切镜像后新镜像若支持 Range 可续传，
     *  不支持则 downloadTo 内部回退整文件覆盖（仍正确，只是浪费流量）。 */
    static void downloadFileWithRetry(Context ctx, String originalUrl, File dest,
                                      DownloadProgress progress) throws Exception {
        final int userMirror = getMirrorIndex(ctx);
        final int n = MIRRORS.length;
        Exception lastErr = null;
        for (int offset = 0; offset < n; offset++) {
            int m = (userMirror + offset) % n;
            String url = applyMirrorWithIndex(m, originalUrl);
            for (int attempt = 0; attempt < MAX_RETRIES_PER_MIRROR; attempt++) {
                try {
                    downloadTo(url, dest, progress);
                    return;   // 成功
                } catch (Exception e) {
                    lastErr = e;
                    Log.w(TAG, "下载 " + url + " 失败(尝试 " + (attempt + 1)
                        + "/" + MAX_RETRIES_PER_MIRROR + ", 镜像=" + MIRRORS[m][0] + "): " + e.getMessage());
                    if (attempt < MAX_RETRIES_PER_MIRROR - 1) {
                        Thread.sleep(BACKOFF_BASE_MS * (1L << attempt));
                    }
                }
            }
        }
        throw new RuntimeException("所有镜像均失败: " + lastErr.getMessage(), lastErr);
    }

    private static final int MAX_RETRIES_PER_MIRROR = 2;
    private static final long BACKOFF_BASE_MS = 1000L;

    /** 已安装包记录目录：$PREFIX/var/installed/<name> 内容为版本号；
     *  <name>.hold 标记文件存在 = 该包被锁定（不参与 upgradeAll）；
     *  <name>.files 文件清单（卸载时按引用计数删除） */
    private static final String INSTALLED_DIR = App.PREFIX + "/var/installed";

    // ---- 版本锁定（hold）----
    // 语义：被 hold 的包不参与 upgradeAll（防意外升级破坏兼容性）。
    // 显式安装/升级某包时仍允许（用户意图优先），仅在列表中标 [HOLD] 提醒。
    public static boolean isHeld(String name) {
        return new File(INSTALLED_DIR, name + ".hold").exists();
    }

    public static void setHeld(String name, boolean held) {
        File f = new File(INSTALLED_DIR, name + ".hold");
        if (held) {
            try {
                new File(INSTALLED_DIR).mkdirs();
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write("1\n".getBytes());
                }
            } catch (Exception e) {
                Log.w(TAG, "setHeld true failed: " + name + " " + e);
            }
        } else {
            f.delete();
        }
    }

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
    public static void fetchIndex(Context ctx, Callback cb) {
        new Thread(() -> {
            try {
                List<PackageInfo> pkgs = fetchIndexSync(ctx);
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
     *  用 httpGetJsonWithRetry 做镜像环形切换 + 指数退避重试。
     * 传入的 URL 是 GitHub 直连基准（不带镜像前缀），由重试层按镜像重写。 */
    static List<PackageInfo> fetchIndexSync(Context ctx) throws Exception {
        String raw = GITHUB_BASE + "/releases/latest/download/packages.json";
        String body = httpGetJsonWithRetry(ctx, raw);
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.getJSONArray("packages");
        List<PackageInfo> pkgs = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            pkgs.add(new PackageInfo(arr.getJSONObject(i)));
        }
        return pkgs;
    }

    /** 异步安装包（含依赖）。callback 在主线程回调。下载进度通过 onProgress 透传（百分比文字）。 */
    public static void installPackage(Context ctx, PackageInfo pkg, Callback cb) {
        new Thread(() -> {
            try {
                List<PackageInfo> index = fetchIndexSync(ctx);
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
                    // installOneSync 内部 downloadFileWithRetry 会按当前镜像重写 + 重试 + 切换
                    String result = installOneSync(ctx, p, dp);
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

    /** 卸载包：调用 dpkg -r <name>。
     *
     *  改造说明（真 apt 集成）:
     *  旧实现自己维护 *.files 清单 + 引用计数删除，复杂且易误删共享库。
     *  现改为调用 dpkg -r <name>，由 dpkg 根据 /var/lib/dpkg/info/<pkg>.list
     *  删除文件（dpkg 自己维护引用关系，不会误删被其他包依赖的共享库）。
     *
     *  hold 标记仍由 App 端 PackageManager 维护（dpkg 的 hold 在
     *  /var/lib/dpkg/selections，但 App 端 UI 层的 hold 语义独立保留）。 */
    static String uninstallOneSync(String pkgName) throws Exception {
        ensureDpkgAvailable();
        String ver = getInstalledVersion(pkgName);
        if (ver == null) {
            return pkgName + " 未安装，无需卸载";
        }

        // 调用 dpkg -r 移除包（保留配置文件，对应 dpkg 默认行为）
        StringBuilder out = new StringBuilder();
        int code = runDpkg(out, 120, "-r", pkgName);
        if (code != 0) {
            throw new RuntimeException(pkgName + " dpkg -r 失败 (code=" + code + "):\n"
                + out.toString().trim());
        }

        // 清理 App 端 hold 标记（dpkg -r 不感知 App 端的 *.hold 文件）
        new File(INSTALLED_DIR, pkgName + ".hold").delete();

        return pkgName + "-" + ver + " 已卸载\n" + out.toString().trim();
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

    /** 安装单个包（可带下载进度回调）：下载 → sha256 校验 → dpkg -i 安装。
     *
     *  改造说明（真 apt 集成）:
     *  旧实现用 tar -xzf 解压 .deb 并自己管理文件清单/符号链接/引用计数，
     *  与 Debian 标准 .deb 格式不兼容（.deb 是 ar 归档，非 tar.gz）。
     *  现改为调用 dpkg -i <deb>，由 dpkg 接管：
     *    - 解压 .deb（ar → debian-binary / control.tar.gz / data.tar.gz）
     *    - 维护文件清单 /var/lib/dpkg/info/<pkg>.list
     *    - 维护包状态 /var/lib/dpkg/status（Version / Status / Depends）
     *    - 重建符号链接（data.tar.gz 内含 symlink）
     *    - 升级时自动清理旧版残留
     *
     *  下载 / sha256 / 镜像切换逻辑保留（dpkg 不负责下载，App 端从 Releases 拉）。
     *
     *  ctx != null 时下载走 downloadFileWithRetry（镜像环形切换 + 指数退避）；
     *  ctx == null 时回退到 downloadTo 单次直连（用于无 Context 的旧调用路径）。 */
    static String installOneSync(Context ctx, PackageInfo pkg, DownloadProgress progress) throws Exception {
        ensureDpkgAvailable();

        // 已安装相同版本则跳过（读 dpkg status）
        String installedVer = getInstalledVersion(pkg.name);
        if (pkg.version.equals(installedVer)) {
            return pkg.getDisplayName() + " 已是最新（" + pkg.version + "），跳过";
        }

        boolean isUpgrade = installedVer != null;

        File cacheDir = new File(CACHE_DIR);
        cacheDir.mkdirs();
        File debFile = new File(cacheDir, pkg.filename);

        // 下载（如缓存命中且 sha256 匹配则跳过下载）
        if (!debFile.exists() || !sha256Matches(debFile, pkg.sha256)) {
            if (ctx != null) {
                downloadFileWithRetry(ctx, pkg.downloadUrl, debFile, progress);
            } else {
                downloadTo(pkg.downloadUrl, debFile, progress);
            }
        }
        // 下载后再次校验
        if (!sha256Matches(debFile, pkg.sha256)) {
            throw new RuntimeException(pkg.filename + " sha256 校验失败（文件损坏或被篡改）");
        }

        // 调用 dpkg -i 安装 .deb
        // dpkg 接管：解压 data.tar.gz → 重建符号链接 → 维护文件清单 → 更新 status
        // 升级时自动清理旧版残留（dpkg -i 对已装包执行升级流程）
        StringBuilder out = new StringBuilder();
        int code = runDpkg(out, 300, "-i", debFile.getAbsolutePath());
        if (code != 0) {
            throw new RuntimeException(pkg.filename + " dpkg -i 失败 (code=" + code + "):\n"
                + out.toString().trim());
        }

        if (isUpgrade) {
            return pkg.getDisplayName() + " 升级完成 " + installedVer + " → " + pkg.version
                + "\n" + out.toString().trim();
        }
        return pkg.getDisplayName() + " 安装完成\n" + out.toString().trim();
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

    /** 读取已安装版本号，未安装返回 null。
     *
     *  改造说明（真 apt 集成）:
     *  旧实现读 $PREFIX/var/installed/<name>（App 端自维护），
     *  现改为调用 dpkg -s <name> 解析 Version 字段，从 dpkg 标准状态库
     *  /var/lib/dpkg/status 读取（dpkg -i / -r 后实时更新）。 */
    static String getInstalledVersion(String name) {
        try {
            StringBuilder out = new StringBuilder();
            int code = runDpkg(out, 10, "-s", name);
            if (code != 0) return null;
            // 解析 "Version: 1.2.3" 行
            for (String line : out.toString().split("\n")) {
                if (line.startsWith("Version:")) {
                    return line.substring("Version:".length()).trim();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getInstalledVersion dpkg -s 失败: " + name + " " + e);
        }
        return null;
    }

    /** 列出所有已安装包及其版本，返回 name → version 映射。
     *  从 dpkg status 解析（dpkg-query 避免手写 status 文件解析）。 */
    public static java.util.Map<String, String> listInstalledSync() {
        java.util.Map<String, String> out = new HashMap<>();
        try {
            StringBuilder sb = new StringBuilder();
            // ${Package}\t${Version}，每行一个已装包（状态 installed）
            int code = runCommand(java.util.Arrays.asList(
                App.PREFIX + "/bin/dpkg-query",
                "-W", "-f=${Package}\t${Version}\n"
            ), sb, 30);
            if (code != 0) return out;
            for (String line : sb.toString().split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t", 2);
                if (parts.length == 2) {
                    out.put(parts[0], parts[1]);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "listInstalledSync dpkg-query 失败: " + e);
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

    // ============================================================
    // 真 apt / dpkg 客户端集成
    //
    // bootstrap 自带 apt 2.8.1 + dpkg 二进制（由 haisa-des-bootstrap CI 交叉编译），
    // sources.list / trusted.gpg.d / apt.conf.d 在 apt 包 build.sh staging 阶段已配置好，
    // 指向 https://xion-hn.github.io/haisa-des-repo/apt-repo（gh-pages 托管）。
    //
    // App 端 PackageManager 不再自己解压 .deb / 管理文件清单 / 做引用计数，
    // 改为调用 dpkg -i / apt-get install / dpkg -r，由 dpkg 接管：
    //   - 解压 .deb（data.tar.gz）
    //   - 维护文件清单 /var/lib/dpkg/info/<pkg>.list
    //   - 维护包状态 /var/lib/dpkg/status
    //   - 依赖解析（apt-get install -y <name>）
    //   - 升级时清理旧版残留（dpkg -i 自动处理）
    //
    // 运行环境变量（与 MainActivity.buildEnv 一致，确保能找到 $PREFIX/bin/dpkg
    // 和加载 $PREFIX/lib 下的 .so）：
    //   PATH=$PREFIX/bin  LD_LIBRARY_PATH=$PREFIX/lib
    //   HOME=$HOME_PATH   TMPDIR=$PREFIX/tmp  PREFIX=$PREFIX
    // ============================================================

    /** 执行命令并捕获输出，返回退出码。输出追加到 out。
     *  设置 $PREFIX 相关环境变量，确保 dpkg/apt 能找到自身和依赖库。 */
    private static int runCommand(List<String> cmd, StringBuilder out, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // 环境变量与 MainActivity.buildEnv 一致
        pb.environment().put("PATH", App.PREFIX + "/bin");
        pb.environment().put("LD_LIBRARY_PATH", App.PREFIX + "/lib");
        pb.environment().put("HOME", App.HOME_PATH);
        pb.environment().put("TMPDIR", App.PREFIX + "/tmp");
        pb.environment().put("PREFIX", App.PREFIX);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroy();
            throw new RuntimeException("命令超时: " + String.join(" ", cmd));
        }
        return p.exitValue();
    }

    /** 执行 dpkg 命令，返回退出码，输出追加到 out */
    private static int runDpkg(StringBuilder out, int timeoutSec, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(App.PREFIX + "/bin/dpkg");
        Collections.addAll(cmd, args);
        return runCommand(cmd, out, timeoutSec);
    }

    /** 执行 apt-get 命令，返回退出码，输出追加到 out。
     *  apt-get 需先 apt-get update 拉取索引。 */
    private static int runAptGet(StringBuilder out, int timeoutSec, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(App.PREFIX + "/bin/apt-get");
        Collections.addAll(cmd, args);
        return runCommand(cmd, out, timeoutSec);
    }

    /** 确认 dpkg/apt 二进制存在且可执行。
     *  首次从 bootstrap 解压后应已存在；若缺失说明 bootstrap 未正确安装。
     *  @throws RuntimeException 如果 dpkg/apt 缺失 */
    private static void ensureDpkgAvailable() {
        File dpkg = new File(App.PREFIX + "/bin/dpkg");
        if (!dpkg.isFile()) {
            throw new RuntimeException("dpkg 未安装: " + dpkg.getAbsolutePath()
                + "（请先通过 BootstrapInstaller 安装 bootstrap，或 apt update）");
        }
    }

    /** apt-get update：拉取 gh-pages 上的 Packages 索引到 $PREFIX/var/lib/apt/lists/。
     *  在首次安装 / 手动刷新索引时调用。
     *  @return apt-get 的输出 */
    static String aptUpdateSync() throws Exception {
        ensureDpkgAvailable();
        StringBuilder out = new StringBuilder();
        int code = runAptGet(out, 120, "update");
        if (code != 0) {
            throw new RuntimeException("apt-get update 失败 (code=" + code + "):\n" + out.toString().trim());
        }
        return out.toString();
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

    // ===========================================================
    // M3.1 B3: 预编译 wheel 安装路径
    //
    // wheels-index.json schema（由 build-system/make-wheels-index.sh 生成）:
    //   {
    //     "repo_version": 1, "abi": "arm64-v8a",
    //     "wheels": [{
    //       "name","version","py_tag","abi_tag","platform_tag","build_tag",
    //       "size","sha256","filename","download_url","desc"
    //     }]
    //   }
    //
    // installPythonPackage 统一入口：
    //   - 先查 wheels-index.json 命中 → installWheelSync（下载 wheel + pip install <local.whl> --no-index）
    //   - 未命中 → pipInstallSync 降级到 PyPI 直拉（设备端 pip 走自己的 manywheel 选型）
    // ===========================================================

    /** 预编译 wheel 元数据 */
    public static class WheelInfo {
        public final String name;
        public final String version;
        public final String pyTag;
        public final String abiTag;
        public final String platformTag;
        public final String buildTag;
        public final long size;
        public final String sha256;
        public final String filename;
        public final String downloadUrl;
        public final String desc;

        WheelInfo(JSONObject o) throws Exception {
            name = o.getString("name");
            version = o.getString("version");
            pyTag = o.optString("py_tag", "");
            abiTag = o.optString("abi_tag", "");
            platformTag = o.optString("platform_tag", "");
            buildTag = o.optString("build_tag", "");
            size = o.optLong("size", 0);
            sha256 = o.getString("sha256");
            filename = o.getString("filename");
            downloadUrl = o.getString("download_url");
            desc = o.optString("desc", "");
        }

        public String getDisplayName() { return name + "-" + version; }
    }

    /** 异步拉取 wheel 索引。callback 在主线程回调。 */
    public static void fetchWheelsIndex(Context ctx, Callback cb) {
        new Thread(() -> {
            try {
                List<WheelInfo> wheels = fetchWheelsIndexSync(ctx);
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onSuccess(formatWheelsSummary(wheels)));
            } catch (Exception e) {
                Log.e(TAG, "fetchWheelsIndex failed", e);
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onError(e.getMessage()));
            }
        }, "wheels-fetch").start();
    }

    static List<WheelInfo> fetchWheelsIndexSync(Context ctx) throws Exception {
        String url = GITHUB_BASE + "/releases/latest/download/wheels-index.json";
        String body = httpGetJsonWithRetry(ctx, url);
        JSONObject root = new JSONObject(body);
        JSONArray arr = root.getJSONArray("wheels");
        List<WheelInfo> wheels = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            wheels.add(new WheelInfo(arr.getJSONObject(i)));
        }
        return wheels;
    }

    /** 统一入口：安装 Python 包。
     *  - 优先从 wheels-index 命中预编译 wheel（避免设备端 gcc 编译）
     *  - 未命中则降级到 pip install（PyPI 直拉，依赖 pip 自身的 manywheel 选型） */
    public static void installPythonPackage(Context ctx, String pkgName, Callback cb) {
        new Thread(() -> {
            try {
                List<WheelInfo> wheels = fetchWheelsIndexSync(ctx);
                WheelInfo match = null;
                for (WheelInfo w : wheels) {
                    if (w.name.equalsIgnoreCase(pkgName)) { match = w; break; }
                }
                Handler h = new Handler(Looper.getMainLooper());
                final WheelInfo matched = match;
                String result;
                if (matched != null) {
                    h.post(() -> cb.onProgress("命中预编译 wheel: " + matched.filename));
                    result = installWheelSync(ctx, matched, cb);
                } else {
                    h.post(() -> cb.onProgress("仓库无预编译 wheel，降级 pip install（PyPI）"));
                    result = pipInstallSync(ctx, pkgName, cb);
                }
                final String r = result;
                h.post(() -> cb.onSuccess(r));
            } catch (Exception e) {
                Log.e(TAG, "installPythonPackage failed", e);
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> cb.onError(e.getMessage()));
            }
        }, "pip-install").start();
    }

    /** 安装预编译 wheel：下载到 cache → sha256 校验 → pip install <local.whl> --no-index --no-deps */
    static String installWheelSync(Context ctx, WheelInfo w, Callback cb) throws Exception {
        File cacheDir = new File(ctx.getFilesDir(), "wheel-cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File wheelFile = new File(cacheDir, w.filename);

        // 已存在且 sha256 匹配则跳过下载
        if (!wheelFile.exists() || !sha256Matches(wheelFile, w.sha256)) {
            DownloadProgress dp = (downloaded, total) -> {
                String msg;
                if (total > 0) {
                    int pct = (int) (downloaded * 100 / total);
                    msg = "下载 wheel " + w.name + " " + pct + "% ("
                        + formatBytes(downloaded) + "/" + formatBytes(total) + ")";
                } else {
                    msg = "下载 wheel " + w.name + " " + formatBytes(downloaded);
                }
                new Handler(Looper.getMainLooper()).post(() -> cb.onProgress(msg));
            };
            downloadFileWithRetry(ctx, w.downloadUrl, wheelFile, dp);
        } else {
            new Handler(Looper.getMainLooper()).post(() -> cb.onProgress("wheel 已缓存，跳过下载"));
        }

        if (!sha256Matches(wheelFile, w.sha256)) {
            throw new RuntimeException("wheel sha256 校验失败: " + w.filename);
        }

        // 调用设备端 pip 安装本地 wheel
        // --no-index 避免误从 PyPI 拉其他版本；--no-deps 避免依赖解析（依赖需用户单独装或本已在 bootstrap）
        String pipBin = App.PREFIX + "/bin/pip";
        String[] envp = buildPipEnvp();
        String out = runShell(envp, pipBin, "install", "--no-index", "--no-deps",
                              wheelFile.getAbsolutePath());
        return "已安装 wheel: " + w.filename + "\n" + out;
    }

    /** 降级路径：直接 pip install <pkgName>（设备端 pip 走 PyPI） */
    static String pipInstallSync(Context ctx, String pkgName, Callback cb) throws Exception {
        String pipBin = App.PREFIX + "/bin/pip";
        String[] envp = buildPipEnvp();
        String out = runShell(envp, pipBin, "install", pkgName);
        return "pip install " + pkgName + ":\n" + out;
    }

    /** pip 子进程环境：与终端会话对齐，确保 PATH/LD_LIBRARY_PATH/TMPDIR 等可用 */
    private static String[] buildPipEnvp() {
        return new String[]{
            "PATH=" + App.PREFIX + "/bin",
            "LD_LIBRARY_PATH=" + App.PREFIX + "/lib",
            "PYTHONPATH=",
            "HOME=" + App.HOME_PATH,
            "TMPDIR=" + App.PREFIX + "/tmp",
            "PREFIX=" + App.PREFIX,
            "PYTHON_BASIC_REPL=1",
        };
    }

    /** 运行命令并捕获 stdout+stderr（设备端 aarch64 ELF 由 App 私有目录 exec）。
     *  cwd=null 沿用 Diagnostics 风格（已 targetSdk=28 允许 W^X exec） */
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
            int code = p.waitFor();
            if (code != 0) {
                out.append("[exit=").append(code).append("]\n");
            }
        } catch (Exception e) {
            out.append("[runShell error] ").append(e.getMessage()).append('\n');
        }
        return out.toString();
    }

    /** 格式化 wheel 索引摘要 */
    static String formatWheelsSummary(List<WheelInfo> wheels) {
        StringBuilder sb = new StringBuilder();
        sb.append("预编译 wheel 共 ").append(wheels.size()).append(" 个:\n\n");
        for (WheelInfo w : wheels) {
            String sizeStr = formatSize(w.size);
            sb.append(String.format("%-14s %-10s %-8s %-12s %s",
                    w.name, w.version, w.pyTag, sizeStr, w.platformTag));
            if (!w.desc.isEmpty()) sb.append("  // ").append(w.desc);
            sb.append('\n');
        }
        return sb.toString().trim();
    }
}
