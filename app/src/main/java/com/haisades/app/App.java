package com.haisades;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public class App extends Application {

    public static final String CHANNEL_SESSION = "session";

    /** Linux 环境前缀（编译期写死于所有二进制中，必须与 build-system/config.sh 一致） */
    public static final String PREFIX = "/data/data/com.haisades/files/usr";
    public static final String HOME_PATH = "/data/data/com.haisades/files/home";
    public static final String BOOTSTRAP_ASSET = "bootstrap/bootstrap-arm64-v8a.zip";

    /** 当前 APK 内置 bootstrap 的版本号与构建标识。
     *  写入 .bootstrap-ok 供 BootstrapUpdater 对比版本，决定是否提示升级。
     *  发版时与 CI 的 RELEASE_TAG 保持一致（如 "0.2.0"）。
     *  build_id 用 git short sha，便于追溯具体构建（仅用于日志，不参与版本对比）。 */
    public static final String BOOTSTRAP_VERSION = "0.1.0";
    public static final String BOOTSTRAP_BUILD_ID = "initial";

    @Override
    public void onCreate() {
        super.onCreate();
        // 全局崩溃捕获：必须在所有业务初始化前安装，覆盖任意线程的未捕获异常
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_SESSION, "终端会话", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
