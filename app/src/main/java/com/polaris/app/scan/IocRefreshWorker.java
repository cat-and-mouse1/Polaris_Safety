package com.polaris.app.scan;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.polaris.app.util.Prefs;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * 病毒库 (Polar Region IOC) 定时刷新 Worker。
 * <p>
 * 仅刷新 Polar Region 全量库；Polar Point 为内置种子（随 APK 打包、离线可用），不在此处自动更新。
 * - 每日执行一次（需网络、非低电量）
 * - 尊重用户设置 {@link Prefs#getVirusAutoUpdate()}
 * - 失败按指数退避重试
 */
public class IocRefreshWorker extends Worker {

    private static final String TAG = "IocRefreshWorker";
    public static final String WORK_NAME = "polaris_ioc_daily_refresh";

    private static final String MODEL_VERSION_URL =
            "https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/app/src/main/assets/ml_model_metadata.json";
    private static final String MODEL_FILE_URL =
            "https://raw.githubusercontent.com/cat-and-mouse1/Polaris_Safety/main/app/src/main/assets/malware_detector.tflite";

    public IocRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Prefs prefs = new Prefs(getApplicationContext());
        if (!prefs.getVirusAutoUpdate()) {
            Log.i(TAG, "Auto-update disabled by user");
            return Result.success();
        }

        Log.i(TAG, "Starting IOC database refresh...");

        try {
            // 同步等待异步刷新完成（最长 60 秒）
            final Object lock = new Object();
            final Result[] resultHolder = {Result.retry()};

            IocDatabase.getInstance(getApplicationContext(), "region").refresh(new IocDatabase.RefreshCallback() {
                @Override
                public void onUpdated(int newVersion) {
                    Log.i(TAG, "IOC database updated to version " + newVersion);
                    synchronized (lock) {
                        resultHolder[0] = Result.success();
                        lock.notifyAll();
                    }
                }

                @Override
                public void onError(String message) {
                    Log.w(TAG, "IOC refresh failed: " + message);
                    synchronized (lock) {
                        resultHolder[0] = Result.retry();
                        lock.notifyAll();
                    }
                }
            });

            synchronized (lock) {
                lock.wait(60_000); // 等待回调，最多 60 秒
            }

            // Check for ML model updates
            checkModelUpdate(getApplicationContext());

            return resultHolder[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during IOC refresh", e);
            return Result.retry();
        }
    }

    private void checkModelUpdate(Context context) {
        try {
            String metadata = downloadUrl(MODEL_VERSION_URL);
            JSONObject remoteMeta = new JSONObject(metadata);

            String localMeta = loadLocalMetadata(context);
            JSONObject localMetaObj = new JSONObject(localMeta);

            String remoteVersion = remoteMeta.getString("version");
            String localVersion = localMetaObj.getString("version");

            if (!remoteVersion.equals(localVersion)) {
                Log.i(TAG, "ML model update available: " + remoteVersion);

                byte[] modelData = downloadBytes(MODEL_FILE_URL);
                saveToFile(context, "malware_detector.tflite", modelData);
                saveToFile(context, "ml_model_metadata.json", metadata.getBytes());

                Log.i(TAG, "ML model updated to " + remoteVersion);
            } else {
                Log.i(TAG, "ML model is up to date: " + localVersion);
            }
        } catch (Exception e) {
            Log.e(TAG, "Model update check failed", e);
        }
    }

    private String loadLocalMetadata(Context context) throws IOException {
        File internalFile = new File(context.getFilesDir(), "ml_model_metadata.json");
        if (internalFile.exists()) {
            return readFileToString(internalFile);
        }
        try (InputStream is = context.getAssets().open("ml_model_metadata.json")) {
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            return new String(buffer);
        }
    }

    private String downloadUrl(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private byte[] downloadBytes(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            is.close();
            return bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private void saveToFile(Context context, String filename, byte[] data) throws IOException {
        FileOutputStream fos = new FileOutputStream(new File(context.getFilesDir(), filename));
        fos.write(data);
        fos.close();
    }

    private String readFileToString(File file) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        byte[] buffer = new byte[(int) file.length()];
        fis.read(buffer);
        fis.close();
        return new String(buffer);
    }

    /** 在 Application 启动时调用，注册每日周期性任务。 */
    public static void schedule(Context context) {
        Prefs prefs = new Prefs(context);
        if (!prefs.getVirusAutoUpdate()) {
            Log.i(TAG, "Auto-update disabled, skipping schedule");
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(IocRefreshWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
        );
        Log.i(TAG, "IOC daily refresh scheduled");
    }

    /** 取消周期性任务（用户关闭自动更新时调用）。 */
    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        Log.i(TAG, "IOC daily refresh cancelled");
    }

    /** 立即触发一次性刷新（用户点击「立即更新」时调用）。 */
    public static void triggerNow(Context context) {
        androidx.work.OneTimeWorkRequest request = new androidx.work.OneTimeWorkRequest.Builder(IocRefreshWorker.class)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        WorkManager.getInstance(context).enqueue(request);
        Log.i(TAG, "IOC immediate refresh triggered");
    }
}