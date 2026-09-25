package com.polaris.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.polaris.app.scan.IocDatabase;
import com.polaris.app.util.Prefs;

public class OnboardingActivity extends AppCompatActivity {

    private Prefs prefs;
    private MaterialButton btnEnter;
    private MaterialButton btnCancelDownload;
    private LinearProgressIndicator downloadProgress;
    private TextView downloadStatus;
    private LinearLayout bottomPanel;
    private MaterialSwitch mlEngineSwitch;
    private RadioGroup mlModelRadioGroup;
    private IocDatabase.DownloadToken downloadToken;
    private boolean leaving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        prefs = new Prefs(this);

        downloadProgress = findViewById(R.id.downloadProgress);
        downloadStatus = findViewById(R.id.downloadStatus);
        btnCancelDownload = findViewById(R.id.btnCancelDownload);
        bottomPanel = findViewById(R.id.bottomPanel);

        setupMlEngineCard();

        // 已完成欢迎页 → 直接跳转主界面
        if (prefs.isTutorialDone(this)) {
            goMain();
            return;
        }

        // 病毒库默认选择
        RadioGroup virusDbRadioGroup = findViewById(R.id.virusDbRadioGroup);
        virusDbRadioGroup.check(R.id.radioPoint);

        // 欢迎动画
        setupAnimations();

        // 进入按钮
        btnEnter = findViewById(R.id.btnEnter);
        btnCancelDownload.setOnClickListener(v -> {
            // 「取消并进入」：立即中断下载（cancel 会断开连接、打断阻塞），并马上跳转；
            // 不完整文件保留为 .part，可稍后在设置中续传补全。
            if (downloadToken != null) downloadToken.cancel();
            Toast.makeText(OnboardingActivity.this,
                    R.string.virusdb_cancelled_toast, Toast.LENGTH_LONG).show();
            goMainOnce();
        });
        btnEnter.setOnClickListener(v -> {
            // 保存病毒库选择
            String selectedDb = virusDbRadioGroup.getCheckedRadioButtonId() == R.id.radioPoint
                    ? "point" : "region";
            prefs.setVirusDb(selectedDb);
            prefs.setTutorialDone(OnboardingActivity.this, true);
            // 选 Polar Region：内置不含该库，需联网拉取并展示进度；选 Polar Point 则直接进。
            if ("region".equals(selectedDb)) {
                startRegionDownload();
            } else {
                goMain();
            }
        });
    }

    private void setupAnimations() {
        // 图标缩放动画
        findViewById(R.id.welcomeIcon).animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(600)
                .setInterpolator(new OvershootInterpolator())
                .start();

        // 延迟显示文字
        findViewById(R.id.welcomeIcon).postDelayed(() -> {
            // 图标上移
            findViewById(R.id.welcomeIcon).animate()
                    .translationY(-200f)
                    .setDuration(800)
                    .setInterpolator(new OvershootInterpolator())
                    .start();

            // 文字淡入
            LinearLayout textGroup = findViewById(R.id.welcomeTextGroup);
            textGroup.animate().alpha(1f).translationY(0).setDuration(400).start();
        }, 400);

        // 延迟显示 ML 引擎卡片（最早出现，位于病毒库选择之前）
        findViewById(R.id.welcomeIcon).postDelayed(() -> {
            LinearLayout mlCard = findViewById(R.id.mlEngineCard);
            mlCard.animate().alpha(1f).setDuration(400).start();
        }, 600);

        // 延迟显示病毒库选择卡片
        findViewById(R.id.welcomeIcon).postDelayed(() -> {
            LinearLayout virusDbCard = findViewById(R.id.virusDbCard);
            virusDbCard.animate().alpha(1f).setDuration(400).start();
        }, 850);

        // 延迟显示按钮
        findViewById(R.id.welcomeIcon).postDelayed(() -> {
            MaterialButton btnEnter = findViewById(R.id.btnEnter);
            btnEnter.animate().alpha(1f).translationY(0).setDuration(400).start();
        }, 1050);
    }

    /** ML 引擎卡片：是否开启 + 选择本地检测模型（mh100k 轻量 / mh1m 强力）。 */
    private void setupMlEngineCard() {
        mlEngineSwitch = findViewById(R.id.mlEngineSwitch);
        mlModelRadioGroup = findViewById(R.id.mlModelRadioGroup);

        boolean mlOn = prefs.isMlEnabled();
        mlEngineSwitch.setChecked(mlOn);
        mlModelRadioGroup.setVisibility(mlOn ? View.VISIBLE : View.GONE);
        mlModelRadioGroup.check("mh1m".equals(prefs.getMlModel())
                ? R.id.radioMh1m : R.id.radioMh100k);

        mlEngineSwitch.setOnCheckedChangeListener((b, checked) -> {
            prefs.setMlEnabled(checked);
            mlModelRadioGroup.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        mlModelRadioGroup.setOnCheckedChangeListener((group, id) -> {
            if (id == R.id.radioMh1m) {
                prefs.setMlModel("mh1m");
            } else if (id == R.id.radioMh100k) {
                prefs.setMlModel("mh100k");
            }
        });
    }

    private void goMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("from_onboarding", true);
        startActivity(intent);
        finish();
    }

    /** 只跳转一次，避免用户取消后后台回调再次触发跳转。 */
    private void goMainOnce() {
        if (leaving) return;
        leaving = true;
        goMain();
    }

    /** 选择 Polar Region：内置不含该库，自动从仓库下载缺失的 iodb.json 并显示进度条。 */
    private void startRegionDownload() {
        bottomPanel.setVisibility(View.GONE);
        btnEnter.setEnabled(false);
        btnEnter.setText(R.string.virusdb_downloading);
        btnCancelDownload.setVisibility(View.VISIBLE);
        downloadStatus.setVisibility(View.VISIBLE);
        downloadProgress.setVisibility(View.VISIBLE);
        downloadProgress.setIndeterminate(true);
        downloadStatus.setText(R.string.virusdb_download_start);

        downloadToken = IocDatabase.getInstance(this, "region").refreshResumable(
                new IocDatabase.CancellableRefreshCallback() {
                    @Override
                    public void onUpdated(int newVersion) {
                        runOnUiThread(() -> {
                            if (leaving) return;
                            downloadProgress.setIndeterminate(false);
                            downloadProgress.setProgressCompat(100, false);
                            downloadStatus.setText(R.string.virusdb_download_done);
                            goMainOnce();
                        });
                    }

                    @Override
                    public void onCancelled() {
                        // 用户主动中断：保留不完整文件，带内置库进入，稍后可在设置续传
                        runOnUiThread(() -> {
                            if (leaving) return;
                            Toast.makeText(OnboardingActivity.this,
                                    R.string.virusdb_cancelled_toast, Toast.LENGTH_LONG).show();
                            goMainOnce();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (leaving) return;
                            downloadProgress.setVisibility(View.GONE);
                            downloadStatus.setVisibility(View.GONE);
                            btnCancelDownload.setVisibility(View.GONE);
                            // 超时 / 中断 / 网络异常：提示用户，可重试或带内置库进入
                            new AlertDialog.Builder(OnboardingActivity.this)
                                    .setTitle(R.string.virusdb_download_failed_title)
                                    .setMessage(getString(R.string.virusdb_download_failed_msg, message))
                                    .setPositiveButton(R.string.virusdb_retry,
                                            (d, w) -> startRegionDownload())
                                    .setNegativeButton(R.string.virusdb_enter_builtin,
                                            (d, w) -> goMainOnce())
                                    .setCancelable(false)
                                    .show();
                        });
                    }
                },
                (downloaded, total) -> runOnUiThread(() -> updateProgress(downloaded, total))
        );
    }

    private void updateProgress(long downloaded, long total) {
        if (total > 0) {
            downloadProgress.setIndeterminate(false);
            int pct = (int) (downloaded * 100 / total);
            downloadProgress.setProgressCompat(pct, true);
            downloadStatus.setText(String.format(java.util.Locale.US,
                    "正在下载 Polar Region 病毒库… %.1f / %.1f MB",
                    downloaded / 1048576.0, total / 1048576.0));
        }
    }

    @Override
    public void onBackPressed() {
        // 禁止返回键跳过欢迎页
    }
}
