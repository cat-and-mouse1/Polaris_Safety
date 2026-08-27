package com.polaris.app.util;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * GitHub Releases 检查与更新 APK 下载（零第三方依赖，原生 HttpURLConnection）。
 *
 * 仓库：cat-and-mouse1/Polaris_Safety
 * 下载源：原 GitHub / 国内镜像（ghproxy 前缀加速）。
 */
public final class UpdateManager {

    public static final String REPO = "cat-and-mouse1/Polaris_Safety";
    public static final String API_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** 国内镜像前缀：拼接在原始 URL 前做加速代理。 */
    public static final String MIRROR_PREFIX = "https://mirror.ghproxy.com/";

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    public interface CheckCallback {
        void onLatest(String tagName, String apkUrl, String changelog);
        void onUpToDate();
        /** message 为 "timeout" 时表示连接超时。 */
        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onDone(File apk);
        void onError(String message);
    }

    // ---------- 检查更新 ----------

    public static void check(final CheckCallback cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(READ_TIMEOUT);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "Polaris-Safety");
                int code = conn.getResponseCode();
                if (code == 404) { cb.onUpToDate(); return; }
                if (code != 200) { cb.onError("HTTP " + code); return; }

                String json = readAll(conn.getInputStream());
                JSONObject root = new JSONObject(json);
                String tag = root.optString("tag_name", "");
                String body = root.optString("body", "");
                String apkUrl = null;
                JSONArray assets = root.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        String name = a.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", "");
                            break;
                        }
                    }
                }
                cb.onLatest(tag, apkUrl, body);
            } catch (SocketTimeoutException e) {
                cb.onError("timeout");
            } catch (Exception e) {
                cb.onError(e.getMessage() == null ? "network" : e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ---------- 下载 ----------

    public static void download(final Context context, final String apkUrl,
                                final String source, final DownloadCallback cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                String target = Prefs.UPDATE_SOURCE_MIRROR.equals(source)
                        ? MIRROR_PREFIX + apkUrl : apkUrl;
                conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Polaris-Safety");
                int code = conn.getResponseCode();
                if (code != 200) { cb.onError("HTTP " + code); return; }

                long total = conn.getContentLengthLong();
                File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = context.getFilesDir();
                File apk = new File(dir, "PolarisSafety_update.apk");
                in = conn.getInputStream();
                out = new FileOutputStream(apk);
                byte[] buf = new byte[8192];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) cb.onProgress((int) (done * 100 / total));
                }
                out.flush();
                cb.onDone(apk);
            } catch (SocketTimeoutException e) {
                cb.onError("timeout");
            } catch (Exception e) {
                cb.onError(e.getMessage() == null ? "download" : e.getMessage());
            } finally {
                try { if (out != null) out.close(); } catch (IOException ignored) { }
                try { if (in != null) in.close(); } catch (IOException ignored) { }
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    // ---------- 版本比较 ----------

    /** 若 newVer 比 curVer 新返回 true（忽略大小写与 v 前缀）。 */
    public static boolean isNewer(String newVer, String curVer) {
        int[] a = parse(newVer);
        int[] b = parse(curVer);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] parse(String v) {
        int[] r = new int[3];
        if (v == null) return r;
        String s = v.trim().toLowerCase();
        if (s.startsWith("v")) s = s.substring(1);
        String[] parts = s.split("\\.");
        for (int i = 0; i < 3 && i < parts.length; i++) {
            String num = parts[i].replaceAll("[^0-9].*$", "");
            try {
                r[i] = num.isEmpty() ? 0 : Integer.parseInt(num);
            } catch (NumberFormatException e) {
                r[i] = 0;
            }
        }
        return r;
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
