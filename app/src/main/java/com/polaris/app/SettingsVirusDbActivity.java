package com.polaris.app;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.polaris.app.R;
import com.polaris.app.scan.IocDatabase;
import com.polaris.app.scan.IocRefreshWorker;
import com.polaris.app.util.Prefs;

import java.util.Map;

/**
 * 威胁情报病毒库子页：自动更新开关 + Polar Region（云端全量）/ Point（内置精选）两张卡。
 * 从主设置页「威胁情报病毒库」入口进入。
 */
public class SettingsVirusDbActivity extends BaseActivity {

    private TextView regionUpdatedText, regionEntriesText;
    private TextView pointUpdatedText, pointEntriesText;
    private MaterialButton regionUpdateButton;
    private MaterialButton regionAbortButton;
    private LinearProgressIndicator regionProgress;
    private TextView regionProgressText;
    /** 上次写入卡片「介绍」行的下载百分比：仅在整数百分比变化时刷新，避免高频重绘。 */
    private int lastShownRegionPct = -1;
    private SwitchMaterial virusDbAutoSwitch;
    private Prefs prefs;
    /** 每个库各自的下载令牌（可中断并保留 .part 供续传）；key 为 region / point。 */
    private final java.util.Map<String, IocDatabase.DownloadToken> dbTokens = new java.util.HashMap<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_virusdb);

        prefs = new Prefs(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        regionUpdatedText = findViewById(R.id.regionUpdatedText);
        regionEntriesText = findViewById(R.id.regionEntriesText);
        pointUpdatedText = findViewById(R.id.pointUpdatedText);
        pointEntriesText = findViewById(R.id.pointEntriesText);
        regionUpdateButton = findViewById(R.id.regionUpdateButton);
        regionAbortButton = findViewById(R.id.regionAbortButton);
        regionProgress = findViewById(R.id.regionProgress);
        regionProgressText = findViewById(R.id.regionProgressText);
        virusDbAutoSwitch = findViewById(R.id.virusDbAutoSwitch);

        regionAbortButton.setOnClickListener(v -> {
            IocDatabase.DownloadToken t = dbTokens.get("region");
            if (t != null) t.cancel();
        });
        virusDbAutoSwitch.setOnCheckedChangeListener((b, checked) -> {
            prefs.setVirusAutoUpdate(checked);
            if (checked) {
                IocRefreshWorker.schedule(this);
            } else {
                IocRefreshWorker.cancel(this);
            }
        });
        regionUpdateButton.setOnClickListener(v -> updateVirusDb("region"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshVirusDb();
    }

    // ---------- 病毒库（两张卡片各自刷新） ----------

    private void refreshVirusDb() {
        // 两个库各自显示状态（只读文件头，避免为显示版本而加载全量库）
        int vRegion = IocDatabase.peekDbVersion(this, "region");
        int vPoint = IocDatabase.peekDbVersion(this, "point");

        // region 本地无版本（未下载 / .part 头部不足）→ 异步探测远端最新版本后刷新状态行
        if (vRegion < 0) {
            IocDatabase.peekRemoteVersion("region", ver -> runOnUiThread(() -> {
                // 回调期间可能已开始下载：本地有版本了就不再用远端值覆盖
                if (ver > 0 && IocDatabase.peekDbVersion(this, "region") < 0) {
                    // 本地确实无版本，远端探测值仅用于显示「可下载的最新版本」，状态仍为未下载
                    regionEntriesText.setText(getString(R.string.virusdb_region_scale) + " · "
                            + dbStatusText("region", ver, false));
                }
            }));
        }

        IocDatabase dbR = IocDatabase.getInstance(this, "region");
        IocDatabase dbP = IocDatabase.getInstance(this, "point");
        regionUpdatedText.setText(getString(R.string.virusdb_updated, dbR.getUpdatedAt()));
        regionEntriesText.setText(getString(R.string.virusdb_region_scale) + " · "
                + dbStatusText("region", vRegion, vRegion >= 0));
        pointUpdatedText.setText(getString(R.string.virusdb_updated, dbP.getUpdatedAt()));
        pointEntriesText.setText(getString(R.string.virusdb_point_scale) + " · "
                + dbStatusText("point", vPoint, true));
        virusDbAutoSwitch.setOnCheckedChangeListener(null);
        virusDbAutoSwitch.setChecked(prefs.getVirusAutoUpdate());
        virusDbAutoSwitch.setOnCheckedChangeListener((b, checked) ->
                prefs.setVirusAutoUpdate(checked));
    }

    /**
     * 病毒库状态行（版本号前置）：完整缓存 → 「版本v14·已下载」；
     * 部分下载中 → 「版本v14·45%」（.part 达 100% 但未落盘也视为已下载）；
     * 无任何本地数据 → 「版本v14·未下载」。本地完全无版本信息时版本位显示 v?。
     *
     * @param version        用于显示的版本号（本地优先；本地无版本时可传远端探测值）
     * @param localAvailable 本地是否已有可用库。true → 命中「已下载」；
     *                       false → 「未下载」（用于 region 未下载、仅靠远端探测拿到版本号时，
     *                       避免把「远端存在新版本」误显示为「本地已下载」）
     */
    private String dbStatusText(String type, int version, boolean localAvailable) {
        String v = version >= 0 ? ("v" + version) : "v?";
        IocDatabase.PartialDownload partial = IocDatabase.peekPartialDownload(this, type);
        if (partial != null && partial.downloaded > 0) {
            int pct = partial.percent();
            if (pct < 0) {
                // 进度未知（旧版残留的 .part、无总大小记录）：
                // point 内置种子始终可用 → 显示「已下载」；region 无内置，保守显示「下载中」
                if ("point".equals(type)) return getString(R.string.virusdb_status_downloaded, v);
                return getString(R.string.virusdb_status_downloading, v);
            }
            if (pct >= 100) return getString(R.string.virusdb_status_downloaded, v);
            return getString(R.string.virusdb_status_partial, v, pct);
        }
        if (localAvailable) return getString(R.string.virusdb_status_downloaded, v);
        return getString(R.string.virusdb_status_not_downloaded, v);
    }

    private void updateVirusDb(String type) {
        IocDatabase db = IocDatabase.getInstance(this, type);
        MaterialButton btn = regionUpdateButton;
        btn.setEnabled(false);
        btn.setText(R.string.virusdb_updating);
        showVirusDbProgress(type, true);
        lastShownRegionPct = -1; // 重置节流，确保首个进度回调即刷新卡片「介绍」行

        IocDatabase.DownloadToken token = db.refreshResumable(new IocDatabase.CancellableRefreshCallback() {
            @Override
            public void onUpdated(int newVersion) {
                runOnUiThread(() -> {
                    dbTokens.remove(type);
                    showVirusDbProgress(type, false);
                    btn.setEnabled(true);
                    btn.setText(R.string.virusdb_update_now);
                    Toast.makeText(SettingsVirusDbActivity.this,
                            getString(R.string.virusdb_updated_toast, newVersion),
                            Toast.LENGTH_SHORT).show();
                    refreshVirusDb();
                });
            }

            @Override
            public void onCancelled() {
                // 用户主动中断：保留 .part，下次点「立即更新」自动从断点续传
                runOnUiThread(() -> {
                    dbTokens.remove(type);
                    showVirusDbProgress(type, false);
                    btn.setEnabled(true);
                    btn.setText(R.string.virusdb_update_now);
                    Toast.makeText(SettingsVirusDbActivity.this,
                            R.string.virusdb_aborted_toast,
                            Toast.LENGTH_LONG).show();
                    refreshVirusDb(); // 回读已保留的 .part，卡片「介绍」行显示真实进度
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    dbTokens.remove(type);
                    showVirusDbProgress(type, false);
                    btn.setEnabled(true);
                    btn.setText(R.string.virusdb_update_now);
                    Toast.makeText(SettingsVirusDbActivity.this,
                            getString(R.string.virusdb_update_failed, message),
                            Toast.LENGTH_LONG).show();
                    refreshVirusDb(); // 出错时同样回读已保留的 .part 进度
                });
            }
        }, (downloaded, total) -> runOnUiThread(() -> {
            if (total > 0) {
                LinearProgressIndicator p = regionProgress;
                TextView pt = regionProgressText;
                p.setIndeterminate(false);
                int pct = (int) (downloaded * 100 / total);
                p.setProgressCompat(pct, true);
                pt.setText(getString(R.string.virusdb_progress,
                        downloaded / 1048576.0, total / 1048576.0, pct));
                // 同步卡片「介绍」行的下载百分比：否则卡片只在 refreshVirusDb() 时计算，
                // 下载过程中不刷新，会出现「进度条 4% / 介绍 0%」的不一致。
                if (pct != lastShownRegionPct) {
                    lastShownRegionPct = pct;
                    int vNow = IocDatabase.peekDbVersion(SettingsVirusDbActivity.this, "region");
                    String vLabel = vNow >= 0 ? ("v" + vNow) : "v?";
                    regionEntriesText.setText(getString(R.string.virusdb_region_scale) + " · "
                            + getString(R.string.virusdb_status_partial, vLabel, pct));
                }
            }
        }));
        dbTokens.put(type, token);
    }

    /** 显示/隐藏指定病毒库的更新进度（含状态文字与「中断下载」按钮）。 */
    private void showVirusDbProgress(String type, boolean show) {
        LinearProgressIndicator p = regionProgress;
        TextView pt = regionProgressText;
        MaterialButton abort = regionAbortButton;
        p.setVisibility(show ? View.VISIBLE : View.GONE);
        pt.setVisibility(show ? View.VISIBLE : View.GONE);
        abort.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            p.setIndeterminate(true);
            p.setProgressCompat(0, false);
            pt.setText(R.string.virusdb_updating);
        }
    }

    @Override
    protected void onDestroy() {
        // 离开子页时中断仍在进行的下载（.part 保留，可续传），
        // 避免回调操作已销毁的界面（并防止 Activity 泄漏）。
        for (IocDatabase.DownloadToken t : dbTokens.values()) {
            if (t != null) t.cancel();
        }
        dbTokens.clear();
        super.onDestroy();
    }
}
