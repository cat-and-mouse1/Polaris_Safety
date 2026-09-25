package com.polaris.app;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.polaris.app.util.AiClient;
import com.polaris.app.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiSetupActivity extends BaseActivity {

    private Prefs prefs;
    private LinearLayout providerListContainer;
    private MaterialCardView configCard;
    private TextView configTitle, testResult, fetchModelsStatus;
    private TextInputEditText modelInput, apiKeyInput, customUrlInput;
    private MaterialButton testButton, saveButton, fetchModelsButton;
    private View providerConfigForm;
    private View multiStrategyContainer;
    private LinearLayout fetchedModelsContainer;
    private MaterialCardView addProviderCard;

    private Prefs.AiProviderConfig editingConfig = null;
    private boolean isEditingCustom = false;

    private static final List<ProviderMeta> PRESET_PROVIDERS = new ArrayList<>();
    static {
        PRESET_PROVIDERS.add(new ProviderMeta("custom", "自定义", "\u2699", "", "", true));
        PRESET_PROVIDERS.add(new ProviderMeta("anthropic", "Anthropic", "AC", "https://api.anthropic.com", "claude-sonnet-4-6", false));
        PRESET_PROVIDERS.add(new ProviderMeta("deepseek", "DeepSeek", "DS", "https://api.deepseek.com", "deepseek-v4-pro", false));
        PRESET_PROVIDERS.add(new ProviderMeta("github_copilot", "GitHub Copilot", "GC", "https://api.githubcopilot.com/v1", "gpt-4o", false));
        PRESET_PROVIDERS.add(new ProviderMeta("glm", "GLM", "GM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", false));
        PRESET_PROVIDERS.add(new ProviderMeta("google", "Google", "GL", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", false));
        PRESET_PROVIDERS.add(new ProviderMeta("hy3", "Tencent HY", "HY", "https://api.hunyuan.cloud.tencent.com/v1", "hy3", false));
        PRESET_PROVIDERS.add(new ProviderMeta("kimi", "Kimi", "K", "https://api.moonshot.cn/v1", "moonshot-v1-8k", false));
        PRESET_PROVIDERS.add(new ProviderMeta("llama", "Llama", "LL", "https://api.llama.com/v1", "llama-3.1-405b", false));
        PRESET_PROVIDERS.add(new ProviderMeta("minimax", "MiniMax", "MX", "https://api.minimax.chat/v1", "minimax-01", false));
        PRESET_PROVIDERS.add(new ProviderMeta("openai", "OpenAI", "OI", "https://api.openai.com/v1", "gpt-4o-mini", false));
        PRESET_PROVIDERS.add(new ProviderMeta("opencode_go", "OpenCode Go", "OG", "https://api.opencode.ai/v1", "go-1", false));
        PRESET_PROVIDERS.add(new ProviderMeta("opencode_zen", "OpenCode Zen", "OZ", "https://api.opencode.ai/v1", "zen-3", false));
        PRESET_PROVIDERS.add(new ProviderMeta("openrouter", "OpenRouter", "OR", "https://openrouter.ai/api/v1", "auto", false));
        PRESET_PROVIDERS.add(new ProviderMeta("qwen", "Qwen", "QW", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max", false));
        Collections.sort(PRESET_PROVIDERS, (a, b) -> {
            if (a.isCustom && !b.isCustom) return -1;
            if (!a.isCustom && b.isCustom) return 1;
            return a.displayName.compareToIgnoreCase(b.displayName);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_setup_v2);

        prefs = new Prefs(this);

        providerListContainer = findViewById(R.id.providerListContainer);
        configCard = findViewById(R.id.configCard);
        configTitle = findViewById(R.id.configTitle);
        testResult = findViewById(R.id.testResult);
        fetchModelsStatus = findViewById(R.id.fetchModelsStatus);
        modelInput = findViewById(R.id.modelInput);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        customUrlInput = findViewById(R.id.customUrlInput);
        testButton = findViewById(R.id.testButton);
        saveButton = findViewById(R.id.saveButton);
        fetchModelsButton = findViewById(R.id.fetchModelsButton);
        providerConfigForm = findViewById(R.id.providerConfigForm);
        multiStrategyContainer = findViewById(R.id.multiStrategyContainer);
        fetchedModelsContainer = findViewById(R.id.fetchedModelsContainer);
        addProviderCard = findViewById(R.id.addProviderCard);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        setupMultiStrategySelector();

        addProviderCard.setOnClickListener(v -> showProviderSelector());

        testButton.setOnClickListener(v -> runConnectionTest());
        saveButton.setOnClickListener(v -> saveCurrentConfig());
        fetchModelsButton.setOnClickListener(v -> fetchModelList());

        renderProviderList();
    }

    private void setupMultiStrategySelector() {
        SwitchMaterial ensembleSwitch = findViewById(R.id.ensembleSwitch);
        SwitchMaterial fastestSwitch = findViewById(R.id.fastestSwitch);

        ensembleSwitch.setChecked(Prefs.MULTI_STRATEGY_ENSEMBLE.equals(prefs.getMultiModelStrategy()));
        fastestSwitch.setChecked(Prefs.MULTI_STRATEGY_FASTEST.equals(prefs.getMultiModelStrategy()));

        ensembleSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                fastestSwitch.setChecked(false);
                prefs.setMultiModelStrategy(Prefs.MULTI_STRATEGY_ENSEMBLE);
            } else if (!fastestSwitch.isChecked()) {
                ensembleSwitch.setChecked(true);
            }
        });
        fastestSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                ensembleSwitch.setChecked(false);
                prefs.setMultiModelStrategy(Prefs.MULTI_STRATEGY_FASTEST);
            } else if (!ensembleSwitch.isChecked()) {
                fastestSwitch.setChecked(true);
            }
        });
    }

    private void renderProviderList() {
        providerListContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);

        List<Prefs.AiProviderConfig> configs = prefs.getAllProviders();
        if (configs.isEmpty()) {
            View emptyView = inflater.inflate(R.layout.item_ai_empty, providerListContainer, false);
            providerListContainer.addView(emptyView);
            return;
        }

        for (Prefs.AiProviderConfig cfg : configs) {
            addProviderCard(cfg);
        }
    }

    private void addProviderCard(Prefs.AiProviderConfig cfg) {
        View cardView = LayoutInflater.from(this).inflate(R.layout.item_ai_provider_v2, providerListContainer, false);
        MaterialCardView card = cardView.findViewById(R.id.providerCard);

        FrameLayout circle = cardView.findViewById(R.id.providerBadgeCircle);
        TextView badge = cardView.findViewById(R.id.providerBadgeText);
        TextView name = cardView.findViewById(R.id.providerName);
        TextView model = cardView.findViewById(R.id.providerModel);
        SwitchMaterial enableSwitch = cardView.findViewById(R.id.enableSwitch);
        ImageButton deleteBtn = cardView.findViewById(R.id.deleteBtn);
        MaterialButton editConfigBtn = cardView.findViewById(R.id.editConfigBtn);
        MaterialButton fetchModelsBtn = cardView.findViewById(R.id.fetchModelsBtn);
        ChipGroup modelChipGroup = cardView.findViewById(R.id.modelChipGroup);

        circle.getBackground().setTint(ContextCompat.getColor(this, R.color.md_theme_primaryContainer));
        badge.setText(cfg.badge);
        name.setText(cfg.name);

        // 显示已启用模型数量
        int enabledCount = cfg.getEnabledModelCount();
        int totalCount = cfg.getTotalModelCount();
        if (totalCount > 0 && cfg.models != null && !cfg.models.isEmpty()) {
            model.setText(getString(R.string.ai_model_enabled_count, enabledCount, totalCount));
        } else {
            model.setText(cfg.model);
        }

        enableSwitch.setChecked(cfg.enabled);
        enableSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) prefs.enableProvider(cfg.id);
            else prefs.disableProvider(cfg.id);
            renderProviderList();
        });

        card.setOnClickListener(v -> openConfigForm(cfg));
        editConfigBtn.setOnClickListener(v -> openConfigForm(cfg));
        deleteBtn.setOnClickListener(v -> confirmDeleteProvider(cfg.id));

        // 获取模型列表按钮
        fetchModelsBtn.setOnClickListener(v -> {
            // 打开配置表单
            openConfigForm(cfg);
            // 如果没有 API Key，也允许尝试获取模型列表（某些服务商支持）
            fetchModelList();
        });

        // 渲染已启用模型 chips
        renderModelChips(modelChipGroup, cfg);

        providerListContainer.addView(card);
    }

    private void renderModelChips(ChipGroup chipGroup, Prefs.AiProviderConfig cfg) {
        chipGroup.removeAllViews();
        if (cfg.models == null || cfg.models.isEmpty()) {
            chipGroup.setVisibility(View.GONE);
            return;
        }

        boolean hasEnabled = false;
        for (Prefs.ModelEntry m : cfg.models) {
            if (m.enabled) {
                hasEnabled = true;
                Chip chip = new Chip(this);
                chip.setText(m.id);
                chip.setCheckable(true);
                chip.setChecked(true);
                chip.setTextSize(11);
                chip.setOnCheckedChangeListener((btn, checked) -> {
                    m.enabled = checked;
                    // 同步更新 model 字段为第一个启用的模型
                    syncPrimaryModel(cfg);
                    prefs.saveProviderConfig(cfg);
                    renderProviderList();
                });
                chipGroup.addView(chip);
            }
        }

        chipGroup.setVisibility(hasEnabled ? View.VISIBLE : View.GONE);
    }

    private void syncPrimaryModel(Prefs.AiProviderConfig cfg) {
        List<String> enabled = cfg.getEnabledModelIds();
        if (!enabled.isEmpty()) {
            cfg.model = enabled.get(0);
        }
    }

    private void openConfigForm(Prefs.AiProviderConfig cfg) {
        editingConfig = cfg;
        isEditingCustom = "custom".equals(cfg.id);

        configCard.setVisibility(View.VISIBLE);
        providerConfigForm.setVisibility(View.VISIBLE);
        configTitle.setText(getString(R.string.ai_config_title, cfg.name));

        modelInput.setText(cfg.model);
        apiKeyInput.setText(cfg.apiKey);
        customUrlInput.setText(cfg.baseUrl);
        customUrlInput.setVisibility(isEditingCustom ? View.VISIBLE : View.GONE);

        // 渲染已获取的模型列表
        renderFetchedModels(cfg);

        configCard.post(() -> configCard.requestRectangleOnScreen(new android.graphics.Rect(0, 0, configCard.getWidth(), configCard.getHeight() + 100)));
    }

    private void renderFetchedModels(Prefs.AiProviderConfig cfg) {
        fetchedModelsContainer.removeAllViews();
        if (cfg.models == null || cfg.models.isEmpty()) {
            fetchedModelsContainer.setVisibility(View.GONE);
            fetchModelsStatus.setVisibility(View.GONE);
            return;
        }

        fetchedModelsContainer.setVisibility(View.VISIBLE);
        fetchModelsStatus.setVisibility(View.VISIBLE);
        fetchModelsStatus.setText(getString(R.string.ai_models_fetched, cfg.models.size(), cfg.getEnabledModelCount()));

        LayoutInflater inflater = LayoutInflater.from(this);
        for (Prefs.ModelEntry m : cfg.models) {
            View item = inflater.inflate(R.layout.item_fetched_model, fetchedModelsContainer, false);
            TextView modelName = item.findViewById(R.id.modelName);
            SwitchMaterial modelSwitch = item.findViewById(R.id.modelSwitch);

            modelName.setText(m.id);
            modelSwitch.setChecked(m.enabled);
            modelSwitch.setOnCheckedChangeListener((btn, checked) -> {
                m.enabled = checked;
                syncPrimaryModel(cfg);
                prefs.saveProviderConfig(cfg);
                // 刷新状态文本
                int enabledCount = cfg.getEnabledModelCount();
                fetchModelsStatus.setText(getString(R.string.ai_models_fetched, cfg.models.size(), enabledCount));
            });
            fetchedModelsContainer.addView(item);
        }
    }

    private void showProviderSelector() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_provider_selector_v2, null);
        LinearLayout list = dialogView.findViewById(R.id.providerList);

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(dialogView);

        // 设置 BottomSheetDialog 展开模式
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            android.view.WindowManager wm = (android.view.WindowManager) getSystemService(android.content.Context.WINDOW_SERVICE);
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(metrics);
            int screenHeight = metrics.heightPixels;
            int dialogHeight = (int) (screenHeight * 0.9);
            bottomSheet.getLayoutParams().height = dialogHeight;
            
            // 展开到最大高度
            com.google.android.material.bottomsheet.BottomSheetBehavior behavior = 
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
            behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            behavior.setPeekHeight(dialogHeight, true);
            behavior.setHideable(false);
            behavior.setSkipCollapsed(true);
            behavior.setMaxHeight(dialogHeight);
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (ProviderMeta meta : PRESET_PROVIDERS) {
            View item = inflater.inflate(R.layout.item_provider_selector_v2, list, false);
            FrameLayout circle = item.findViewById(R.id.providerBadgeCircle);
            TextView badge = item.findViewById(R.id.providerBadgeText);
            TextView name = item.findViewById(R.id.providerName);
            TextView desc = item.findViewById(R.id.providerDesc);

            circle.getBackground().setTint(ContextCompat.getColor(this, R.color.md_theme_primaryContainer));
            badge.setText(meta.badge);
            name.setText(meta.displayName);
            
            // 不显示描述
            desc.setVisibility(android.view.View.GONE);

            boolean exists = false;
            for (Prefs.AiProviderConfig p : prefs.getAllProviders()) {
                if (p.id.equals(meta.id)) { exists = true; break; }
            }
            if (exists && !meta.isCustom) {
                item.setAlpha(0.5f);
                TextView configuredTag = new TextView(this);
                configuredTag.setText("已配置");
                configuredTag.setTextSize(11);
                configuredTag.setTextColor(ContextCompat.getColor(this, R.color.ai_success));
                configuredTag.setPadding(8, 2, 8, 2);
                configuredTag.setBackgroundResource(R.drawable.bg_level_chip);
                ((LinearLayout) item).addView(configuredTag);
            }

            item.setOnClickListener(v -> {
                dialog.dismiss();
                Prefs.AiProviderConfig existing = findConfig(meta.id);
                if (existing != null) {
                    openConfigForm(existing);
                } else {
                    Prefs.AiProviderConfig newCfg = new Prefs.AiProviderConfig(
                            meta.id, meta.displayName, meta.badge, meta.baseUrl, meta.defaultModel, "", true
                    );
                    openConfigForm(newCfg);
                }
            });
            list.addView(item);
        }

        dialog.show();
    }

    private Prefs.AiProviderConfig findConfig(String id) {
        for (Prefs.AiProviderConfig cfg : prefs.getAllProviders()) {
            if (cfg.id.equals(id)) return cfg;
        }
        return null;
    }

    private void saveCurrentConfig() {
        if (editingConfig == null) return;

        String key = apiKeyInput.getText().toString().trim();
        String model = modelInput.getText().toString().trim();
        String url = customUrlInput.getText().toString().trim();

        // 如果没有 API Key，允许保存（某些服务商支持无 Key 访问）
        if (model.isEmpty()) {
            Toast.makeText(this, "请填写模型名称", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isEditingCustom && url.isEmpty()) {
            Toast.makeText(this, "自定义服务商需填写 Base URL", Toast.LENGTH_SHORT).show();
            return;
        }

        editingConfig.apiKey = key;
        editingConfig.model = model;
        editingConfig.baseUrl = isEditingCustom ? url : editingConfig.baseUrl;
        editingConfig.enabled = true;

        prefs.saveProviderConfig(editingConfig);

        Toast.makeText(this, getString(R.string.ai_provider_saved_toast, editingConfig.name), Toast.LENGTH_SHORT).show();
        renderProviderList();
        closeConfigForm();
    }

    private void closeConfigForm() {
        editingConfig = null;
        isEditingCustom = false;
        configCard.setVisibility(View.GONE);
        providerConfigForm.setVisibility(View.GONE);
        fetchedModelsContainer.setVisibility(View.GONE);
        fetchModelsStatus.setVisibility(View.GONE);
    }

    private void confirmDeleteProvider(String providerId) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ai_delete_provider_title)
                .setMessage(R.string.ai_delete_provider_confirm)
                .setPositiveButton(R.string.ai_delete, (d, w) -> {
                    prefs.removeProvider(providerId);
                    renderProviderList();
                })
                .setNegativeButton(R.string.update_cancel, null)
                .show();
    }

    private void saveAndFinish() {
        if (prefs.getEnabledProviders().isEmpty()) {
            Toast.makeText(this, R.string.ai_no_provider_enabled, Toast.LENGTH_SHORT).show();
            return;
        }
        setResult(RESULT_OK);
        finish();
    }

    private void runConnectionTest() {
        if (editingConfig == null) return;

        final String key = apiKeyInput.getText().toString().trim();
        final String model = modelInput.getText().toString().trim();
        
        // 如果没有 API Key，只测试连接（不发送消息）
        if (key.isEmpty()) {
            testButton.setEnabled(false);
            testResult.setText("正在测试连接...");
            testResult.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant));
            testResult.setVisibility(View.VISIBLE);

            final String baseUrl = isEditingCustom ? customUrlInput.getText().toString().trim() : editingConfig.baseUrl;
            
            new Thread(() -> {
                try {
                    String modelsUrl = baseUrl.endsWith("/") ? baseUrl + "v1/models" : baseUrl + "/v1/models";
                    URL url = new URL(modelsUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    
                    if (code == 200 || code == 401) {
                        // 200 = 完全可访问，401 = 需要 API Key 但服务可达
                        runOnUiThread(() -> {
                            if (code == 200) {
                                testResult.setText("连接成功（无需 API Key）");
                            } else {
                                testResult.setText("服务可达，但需要 API Key");
                            }
                            testResult.setTextColor(ContextCompat.getColor(this, R.color.ai_success));
                            testButton.setEnabled(true);
                        });
                    } else {
                        throw new Exception("HTTP " + code);
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        testResult.setText(getString(R.string.ai_test_fail, e.getMessage()));
                        testResult.setTextColor(ContextCompat.getColor(this, R.color.md_theme_error));
                        testButton.setEnabled(true);
                    });
                }
            }).start();
            return;
        }

        if (model.isEmpty()) {
            Toast.makeText(this, R.string.ai_key_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        testButton.setEnabled(false);
        testResult.setText(R.string.ai_testing);
        testResult.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant));
        testResult.setVisibility(View.VISIBLE);

        final Prefs.AiProviderConfig cfg = editingConfig;
        final String baseUrl = isEditingCustom ? customUrlInput.getText().toString().trim() : editingConfig.baseUrl;
        final boolean isClaude = "anthropic".equals(editingConfig.id);

        new Thread(() -> {
            try {
                String reply = AiClient.chat(this, baseUrl, isClaude, key, model,
                        "你是连接测试助手", "请只回复：OK");
                runOnUiThread(() -> {
                    testResult.setText(R.string.ai_test_ok);
                    testResult.setTextColor(ContextCompat.getColor(this, R.color.ai_success));
                    testButton.setEnabled(true);
                });
            } catch (AiClient.AiException e) {
                runOnUiThread(() -> {
                    testResult.setText(getString(R.string.ai_test_fail, e.getMessage()));
                    testResult.setTextColor(ContextCompat.getColor(this, R.color.md_theme_error));
                    testButton.setEnabled(true);
                });
            }
        }).start();
    }

    /** 从服务商 API 获取模型列表。 */
    private void fetchModelList() {
        if (editingConfig == null) return;

        String key = apiKeyInput.getText().toString().trim();
        String baseUrl = isEditingCustom
                ? customUrlInput.getText().toString().trim()
                : editingConfig.baseUrl;

        if (baseUrl.isEmpty()) {
            Toast.makeText(this, "请填写 Base URL", Toast.LENGTH_SHORT).show();
            return;
        }

        fetchModelsButton.setEnabled(false);
        fetchModelsButton.setText(R.string.ai_fetching_models);
        fetchModelsStatus.setText(R.string.ai_fetching_models);
        fetchModelsStatus.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                String modelsUrl = baseUrl.endsWith("/") ? baseUrl + "v1/models" : baseUrl + "/v1/models";
                URL url = new URL(modelsUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                // 如果有 API Key 则添加认证头
                if (!key.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + key);
                }
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new Exception("HTTP " + code);
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                conn.disconnect();

                JSONObject response = new JSONObject(sb.toString());
                JSONArray data = response.optJSONArray("data");

                List<String> modelIds = new ArrayList<>();
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject m = data.getJSONObject(i);
                        String id = m.optString("id", "");
                        if (!id.isEmpty()) {
                            modelIds.add(id);
                        }
                    }
                }

                runOnUiThread(() -> {
                    if (modelIds.isEmpty()) {
                        fetchModelsStatus.setText(R.string.ai_no_models_fetched);
                    } else {
                        // 保留已启用的状态
                        List<Prefs.ModelEntry> existingModels = editingConfig.models;
                        List<Prefs.ModelEntry> newModels = new ArrayList<>();
                        for (String id : modelIds) {
                            boolean wasEnabled = false;
                            if (existingModels != null) {
                                for (Prefs.ModelEntry old : existingModels) {
                                    if (old.id.equals(id)) {
                                        wasEnabled = old.enabled;
                                        break;
                                    }
                                }
                            }
                            newModels.add(new Prefs.ModelEntry(id, wasEnabled));
                        }
                        // 如果没有已启用的，默认启用第一个
                        if (newModels.size() > 0) {
                            boolean hasAnyEnabled = false;
                            for (Prefs.ModelEntry m : newModels) {
                                if (m.enabled) { hasAnyEnabled = true; break; }
                            }
                            if (!hasAnyEnabled) {
                                newModels.get(0).enabled = true;
                            }
                        }
                        editingConfig.models = newModels;
                        syncPrimaryModel(editingConfig);
                        prefs.saveProviderConfig(editingConfig);

                        renderFetchedModels(editingConfig);
                        fetchModelsStatus.setText(getString(R.string.ai_models_fetched, modelIds.size(), editingConfig.getEnabledModelCount()));
                        Toast.makeText(this, getString(R.string.ai_models_fetched, modelIds.size(), editingConfig.getEnabledModelCount()), Toast.LENGTH_SHORT).show();
                    }
                    fetchModelsButton.setEnabled(true);
                    fetchModelsButton.setText(R.string.ai_fetch_models);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    fetchModelsStatus.setText(getString(R.string.ai_models_fetch_fail, e.getMessage()));
                    fetchModelsButton.setEnabled(true);
                    fetchModelsButton.setText(R.string.ai_fetch_models);
                });
            }
        }).start();
    }

    private static class ProviderMeta {
        final String id;
        final String displayName;
        final String badge;
        final String baseUrl;
        final String defaultModel;
        final boolean isCustom;

        ProviderMeta(String id, String displayName, String badge, String baseUrl, String defaultModel, boolean isCustom) {
            this.id = id;
            this.displayName = displayName;
            this.badge = badge;
            this.baseUrl = baseUrl;
            this.defaultModel = defaultModel;
            this.isCustom = isCustom;
        }
    }
}
