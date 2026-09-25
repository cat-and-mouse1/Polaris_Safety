package com.polaris.app.util;

import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

/**
 * Root 检测与 su 命令执行。
 *
 * 检测策略（分层，非单点判断）：
 * 1) su 二进制常见路径探测
 * 2) `which su` 命令
 * 3) 执行 `su -c id` 验证是否真的拿到 uid=0
 */
public final class RootChecker {

    private static final String TAG = "PolarisRoot";

    private static final List<String> SU_PATHS = Arrays.asList(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/bin/.ext/.su", "/system/usr/we-need-root/su-backup",
            "/data/local/bin/su", "/data/local/xbin/su", "/data/local/su",
            "/system/sd/xbin/su", "/vendor/bin/su", "/cache/su");

    private static final List<String> ROOT_APPS = Arrays.asList(
            "com.topjohnwu.magisk", "eu.chainfire.supersu", "com.koushikdutta.superuser",
            "com.thirdparty.superuser", "com.noshufou.android.su");

    private RootChecker() {}

    /** 设备是否具备可用的 Root 能力。 */
    public static boolean isRootAvailable() {
        return suBinaryExists() || whichSu() || canRunSuId();
    }

    /** 以 root 身份执行一条命令（无输出需求，如 am force-stop / pm disable-user）。 */
    public static boolean runAsRoot(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int exit = p.waitFor();
            Log.d(TAG, "su -c " + command + " -> exit " + exit);
            return exit == 0;
        } catch (Exception e) {
            Log.w(TAG, "runAsRoot failed: " + e.getMessage());
            return false;
        }
    }

    /** 以 root 身份执行命令并读取首行输出（如 dumpsys 前台窗口）。 */
    public static String runAsRootOutput(String command) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "runAsRootOutput failed: " + e.getMessage());
            return "";
        }
    }

    private static boolean suBinaryExists() {
        for (String path : SU_PATHS) {
            File f = new File(path);
            if (f.exists() && f.canExecute()) return true;
        }
        return false;
    }

    private static boolean whichSu() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            return line != null && !line.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 真正执行 su 并验证输出是否含 uid=0。 */
    private static boolean canRunSuId() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
            String output = reader.readLine();
            return output != null && output.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    /** 仅检查是否装有知名 root 管理应用（Magisk / SuperSU 等）。 */
    public static boolean hasRootManagerApp(android.content.Context context) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        for (String pkg : ROOT_APPS) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    /** 设备是否带 test-keys 编译标记（常见于第三方 ROM）。 */
    public static boolean hasTestKeys() {
        return Build.TAGS != null && Build.TAGS.contains("test-keys");
    }
}
