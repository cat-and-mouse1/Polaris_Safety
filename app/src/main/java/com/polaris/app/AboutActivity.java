package com.polaris.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.polaris.app.R;
import com.polaris.app.scan.IocDatabase;
import com.polaris.app.util.Prefs;
import com.polaris.app.util.UpdateManager;

import java.io.File;

/**
 * 应用信息页：中心应用图标 + 版本号 + 病毒库版本 + 更新日志 + 底部固定「检查更新」按钮。
 *
 * 「检查更新」检查 GitHub Releases（cat-and-mouse1/Polaris_Safety），发现新版本后
 * 由用户选择原 GitHub 或国内镜像下载 APK，并引导安装（含「安装未知应用」权限处理）。
 */
public class AboutActivity extends AppCompatActivity {

    private MaterialButton checkButton;
    private Prefs prefs;
    private String currentVersion = "?";
    private String pendingApkUrl;
    private String pendingSource;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_about);

        prefs = new Prefs(this);
        checkButton = findViewById(R.id.checkUpdateButton);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 版本号：从 PackageManager 读取
        int versionCode = 0;
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            currentVersion = info.versionName;
            versionCode = info.versionCode;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        ((TextView) findViewById(R.id.versionText))
                .setText(getString(R.string.about_version, currentVersion, versionCode));

        // 病毒库版本行
        IocDatabase db = IocDatabase.getInstance(this);
        ((TextView) findViewById(R.id.virusDbLine))
                .setText(getString(R.string.virusdb_name) + " v" + db.getDbVersion());

        checkButton.setOnClickListener(v -> checkUpdate());
    }

    // ---------- 检查更新 ----------

    private void checkUpdate() {
        setBusy(true, R.string.about_checking);
        UpdateManager.check(new UpdateManager.CheckCallback() {
            @Override
            public void onLatest(String tag, String apkUrl, String body) {
                runOnUiThread(() -> {
                    setBusy(false, R.string.about_check_update);
                    showUpdateDialog(tag, apkUrl, body);
                });
            }

            @Override
            public void onUpToDate() {
                runOnUiThread(() -> {
                    setBusy(false, R.string.about_check_update);
                    Toast.makeText(AboutActivity.this,
                            R.string.about_up_to_date, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setBusy(false, R.string.about_check_update);
                    if ("timeout".equals(message)) {
                        Toast.makeText(AboutActivity.this,
                                R.string.update_timeout, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(AboutActivity.this,
                                getString(R.string.update_check_failed, message),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showUpdateDialog(String tag, String apkUrl, String body) {
        String msg = getString(R.string.update_available_body, tag, currentVersion);
        if (body != null && !body.trim().isEmpty()) msg += "\n\n" + body.trim();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_available_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_download_github, (d, w) ->
                        startDownload(apkUrl, Prefs.UPDATE_SOURCE_GITHUB))
                .setNeutralButton(R.string.update_download_mirror, (d, w) ->
                        startDownload(apkUrl, Prefs.UPDATE_SOURCE_MIRROR))
                .setNegativeButton(R.string.update_cancel, null)
                .show();
    }

    // ---------- 下载 + 安装 ----------

    private void startDownload(String apkUrl, String source) {
        prefs.setUpdateSource(source);
        // Android 8+ 需「安装未知应用」权限；未授权则跳转设置，返回后继续下载
        if (Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingApkUrl = apkUrl;
            pendingSource = source;
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, R.string.update_need_install_perm,
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        doDownload(apkUrl, source);
    }

    private void doDownload(String apkUrl, String source) {
        setBusy(true, R.string.update_downloading_init);
        UpdateManager.download(this, apkUrl, source, new UpdateManager.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                runOnUiThread(() ->
                        checkButton.setText(getString(R.string.update_downloading, percent)));
            }

            @Override
            public void onDone(File apk) {
                runOnUiThread(() -> {
                    setBusy(false, R.string.about_check_update);
                    installApk(apk);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    setBusy(false, R.string.about_check_update);
                    if ("timeout".equals(message)) {
                        Toast.makeText(AboutActivity.this,
                                R.string.update_timeout, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(AboutActivity.this,
                                getString(R.string.update_download_failed, message),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void installApk(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            Toast.makeText(this, R.string.update_install_now, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.file_action_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void setBusy(boolean busy, int textRes) {
        checkButton.setEnabled(!busy);
        checkButton.setText(textRes);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「安装未知应用」设置页返回且已授权，继续下载
        if (pendingApkUrl != null) {
            if (Build.VERSION.SDK_INT < 26
                    || getPackageManager().canRequestPackageInstalls()) {
                String url = pendingApkUrl;
                String src = pendingSource;
                pendingApkUrl = null;
                pendingSource = null;
                doDownload(url, src);
            }
        }
    }
}
