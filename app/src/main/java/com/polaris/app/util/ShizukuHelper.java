package com.polaris.app.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Shizuku 能力封装：可用性检测、权限申请、以 ADB/root 身份执行 shell 命令。
 *
 * 检测链路：应用是否安装 -> 服务是否存活(pingBinder) -> 权限是否授予。
 * 任一环节失败即视为"未查找到可使用的权限"。
 */
public final class ShizukuHelper {

    private static final String TAG = "PolarisShizuku";
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    public static final int REQUEST_CODE_PERMISSION = 10086;

    /** Shizuku 应用是否已安装。 */
    public static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Shizuku 服务是否存活并可连接。 */
    public static boolean isShizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 权限是否已授予。 */
    public static boolean hasShizukuPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 请求 Shizuku 授权（结果通过 Shizuku.addRequestPermissionResultListener 回调）。 */
    public static void requestPermission() {
        try {
            if (Shizuku.isPreV11()) return;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE_PERMISSION);
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed: " + t.getMessage());
        }
    }

    /** 当前 Shizuku 后端身份：true=root(uid 0)，false=ADB(shell uid 2000)。 */
    public static boolean isRootPrivilege() {
        try {
            return Shizuku.getUid() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 通过 Shizuku 以系统 shell 身份执行命令，返回输出（无输出时返回 null 表示失败）。 */
    public static String runShell(String... cmd) {
        if (!hasShizukuPermission()) return null;
        try {
            ShizukuRemoteProcess p = Shizuku.newProcess(cmd, null, null);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            int exit = p.waitFor();
            Log.d(TAG, "shizuku exec exit=" + exit + " cmd=" + String.join(" ", cmd));
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "runShell failed: " + e.getMessage());
            return null;
        }
    }

    /** 完整可用性检测结果。 */
    public static class CheckResult {
        public boolean appInstalled;
        public boolean running;
        public boolean permissionGranted;

        public boolean allReady() {
            return appInstalled && running && permissionGranted;
        }
    }

    public static CheckResult checkAll(Context context) {
        CheckResult r = new CheckResult();
        r.appInstalled = isShizukuInstalled(context);
        r.running = isShizukuRunning();
        r.permissionGranted = hasShizukuPermission();
        return r;
    }
}
