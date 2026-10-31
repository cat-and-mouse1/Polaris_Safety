package com.polaris.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.polaris.app.R;
import com.polaris.app.MainActivity;
import com.polaris.app.scan.AppScanRunner;
import com.polaris.app.util.AiProvider;
import com.polaris.app.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 检测配置子页：AI 智能判定 与 ML 机器学习的完整配置（默认引擎在设置主页检测配置卡内）。
 * 从主设置页「检测配置」入口进入。包含：
 * - AI 引擎：接入状态 + 跳转 AI 配置（AiSetupActivity）+ 多模型策略（交叉检测 / 最快优先）；
 * - ML 引擎：本地模型开关 + 模型选择 + 模型详情 + 立即 ML 扫描。
 */
public class SettingsDetectionActivity extends BaseActivity {

    private Prefs prefs;
    private TextView aiStatusTitle, aiStatusDesc;
    private SwitchMaterial mlEngineSwitch;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings_detection);

        prefs = new Prefs(this);
        aiStatusTitle = findViewById(R.id.aiStatusTitle);
        aiStatusDesc = findViewById(R.id.aiStatusDesc);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.aiConfigCard).setOnClickListener(v ->
                startActivity(new Intent(this, AiSetupActivity.class)));

        // IP 连接检测开关（仅在 Shizuku / Root 模式下实际生效）
        SwitchMaterial ipCheckSwitch = findViewById(R.id.ipCheckSwitch);
        TextView ipCheckStatus = findViewById(R.id.ipCheckStatus);
        ipCheckSwitch.setChecked(prefs.isIpCheckEnabled());
        updateIpCheckStatus(ipCheckStatus);
        ipCheckSwitch.setOnCheckedChangeListener((b, checked) -> {
            prefs.setIpCheckEnabled(checked);
            updateIpCheckStatus(ipCheckStatus);
        });

        // ML 引擎开关
        mlEngineSwitch = findViewById(R.id.mlEngineSwitch);
        mlEngineSwitch.setChecked(prefs.isMlEnabled());
        mlEngineSwitch.setOnCheckedChangeListener((b, checked) -> prefs.setMlEnabled(checked));

        // ML 扫描按钮
        MaterialButton mlScanButton = findViewById(R.id.mlScanButton);
        mlScanButton.setOnClickListener(v ->
                AppScanRunner.launchQuickScan(this, prefs, mlScanButton));

        // ML 模型选择器
        AutoCompleteTextView mlModelSelector = findViewById(R.id.mlModelSelector);
        TextView mlModelDesc = findViewById(R.id.mlModelDesc);
        View mlDescHeader = findViewById(R.id.mlDescHeader);
        ImageView mlDescArrow = findViewById(R.id.mlDescArrow);
        String[] modelOptions = new String[]{
                getString(R.string.ml_model_mh100k),
                getString(R.string.ml_model_mh1m)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, modelOptions);
        mlModelSelector.setAdapter(adapter);
        boolean isMh1m = prefs.getMlModel().equals("mh1m");
        mlModelSelector.setText(isMh1m ?
                getString(R.string.ml_model_mh1m) : getString(R.string.ml_model_mh100k), false);
        mlModelDesc.setText(isMh1m ?
                getString(R.string.ml_model_desc_mh1m) : getString(R.string.ml_model_desc_mh100k));
        mlModelSelector.setOnItemClickListener((parent, view, position, id) -> {
            boolean is1m = position == 1;
            String modelId = is1m ? "mh1m" : "mh100k";
            prefs.setMlModel(modelId);
            mlModelDesc.setText(is1m ?
                    getString(R.string.ml_model_desc_mh1m) : getString(R.string.ml_model_desc_mh100k));
            Toast.makeText(this, getString(R.string.ml_model_changed, modelOptions[position]), Toast.LENGTH_SHORT).show();
        });

        // 模型详情折叠
        mlDescHeader.setOnClickListener(v -> {
            boolean visible = mlModelDesc.getVisibility() == View.VISIBLE;
            mlModelDesc.setVisibility(visible ? View.GONE : View.VISIBLE);
            mlDescArrow.animate().rotation(visible ? 0 : 180).setDuration(200).start();
        });

        // AI 多模型策略（互斥：始终有一个保持开启）
        SwitchMaterial aiEnsembleSwitch = findViewById(R.id.aiEnsembleSwitch);
        SwitchMaterial aiFastestSwitch = findViewById(R.id.aiFastestSwitch);
        aiEnsembleSwitch.setChecked(Prefs.MULTI_STRATEGY_ENSEMBLE.equals(prefs.getMultiModelStrategy()));
        aiFastestSwitch.setChecked(Prefs.MULTI_STRATEGY_FASTEST.equals(prefs.getMultiModelStrategy()));
        aiEnsembleSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                aiFastestSwitch.setChecked(false);
                prefs.setMultiModelStrategy(Prefs.MULTI_STRATEGY_ENSEMBLE);
            } else if (!aiFastestSwitch.isChecked()) {
                // 不能同时关闭，恢复
                aiEnsembleSwitch.setChecked(true);
            }
        });
        aiFastestSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                aiEnsembleSwitch.setChecked(false);
                prefs.setMultiModelStrategy(Prefs.MULTI_STRATEGY_FASTEST);
            } else if (!aiEnsembleSwitch.isChecked()) {
                // 不能同时关闭，恢复
                aiFastestSwitch.setChecked(true);
            }
        });

        // AI 检测模式（复核 / 独立检测）
        com.google.android.material.button.MaterialButtonToggleGroup aiModeGroup =
                findViewById(R.id.aiModeGroup);
        boolean independent = prefs.isAiIndependentMode();
        aiModeGroup.check(independent ? R.id.aiModeIndependent : R.id.aiModeReview);
        aiModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return; // 只处理被选中的按钮
            if (checkedId == R.id.aiModeIndependent) {
                prefs.setAiDetectionMode(Prefs.AI_MODE_INDEPENDENT);
            } else {
                prefs.setAiDetectionMode(Prefs.AI_MODE_REVIEW);
            }
        });
    }

    // ---------- 默认引擎已移回设置主页检测配置卡 ----------

    @Override
    protected void onResume() {
        super.onResume();
        refreshAiStatus();
    }

    /** 刷新 AI 接入状态（从 AiSetupActivity 返回后可能已变化）。 */
    private void refreshAiStatus() {
        AiProvider p = AiProvider.fromId(prefs.getAiProviderId());
        if (p != null && prefs.isAiConfigured()) {
            aiStatusTitle.setText(getString(R.string.ai_home_status_on, p.displayName(this)));
            aiStatusDesc.setText(R.string.ai_configured_badge);
        } else {
            aiStatusTitle.setText(R.string.ai_home_status_off);
            aiStatusDesc.setText(R.string.ai_not_configured);
        }
    }

    /** 启动 ML 扫描（仅 ML 引擎，快速扫描已安装应用）。 */
    /** IP 连接检测的状态说明：关闭 / 未满足模式前提 / 已启用。 */
    private void updateIpCheckStatus(TextView tv) {
        if (tv == null) return;
        if (!prefs.isIpCheckEnabled()) {
            tv.setText(R.string.ip_check_status_off);
        } else if (prefs.getActiveMode() != Prefs.MODE_SHIZUKU
                && prefs.getActiveMode() != Prefs.MODE_ROOT) {
            // 注意：Accessibility 模式虽然也是「深层扫描」，但读不到全局连接表，IP 检查不会跑
            tv.setText(R.string.ip_check_status_need_mode);
        } else {
            tv.setText(R.string.ip_check_status_on);
        }
    }

}
