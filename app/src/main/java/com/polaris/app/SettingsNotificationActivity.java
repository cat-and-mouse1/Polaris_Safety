package com.polaris.app;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.polaris.app.scan.Notifier;
import com.polaris.app.service.GuardService;
import com.polaris.app.util.Prefs;

/**
 * 通知子页：三类通知的独立开关（守护常驻 / 拦截常驻 / 病毒库更新）。
 * 从主设置页「通知」入口进入。
 */
public class SettingsNotificationActivity extends BaseActivity {

    private MaterialSwitch notifyGuardSwitch;
    private MaterialSwitch notifyBlockSwitch;
    private MaterialSwitch notifyVirusSwitch;
    private Prefs prefs;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_notification);

        prefs = new Prefs(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        notifyGuardSwitch = findViewById(R.id.notifyGuardSwitch);
        notifyBlockSwitch = findViewById(R.id.notifyBlockSwitch);
        notifyVirusSwitch = findViewById(R.id.notifyVirusSwitch);

        notifyGuardSwitch.setChecked(prefs.isGuardNotifyEnabled());
        notifyBlockSwitch.setChecked(prefs.isBlockNotifyEnabled());
        notifyVirusSwitch.setChecked(prefs.isVirusUpdateNotifyEnabled());

        // 守护常驻通知：来自前台服务，无法彻底移除，关闭仅转为静音；运行中则重建以立即生效
        notifyGuardSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.setGuardNotifyEnabled(on);
            int mode = prefs.getActiveMode();
            if (mode == Prefs.MODE_ACCESSIBILITY || mode == Prefs.MODE_SHIZUKU || mode == Prefs.MODE_ROOT) {
                GuardService.start(this, mode);
            }
        });
        // 拦截常驻通知：关闭则完全撤销，开启则按当前状态重建
        notifyBlockSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.setBlockNotifyEnabled(on);
            if (on) Notifier.updateBlockNotification(this);
            else Notifier.cancelBlockNotification(this);
        });
        // 病毒库更新通知：仅控制后续自动更新是否提醒
        notifyVirusSwitch.setOnCheckedChangeListener((b, on) ->
                prefs.setVirusUpdateNotifyEnabled(on));
    }
}
