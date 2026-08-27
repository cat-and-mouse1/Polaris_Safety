package com.polaris.app;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.textfield.TextInputEditText;
import com.polaris.app.util.AiClient;
import com.polaris.app.util.AiProvider;
import com.polaris.app.util.Prefs;

/**
 * AI 智能判定引擎接入页。
 *
 * 以品牌色卡片网格展示 7 家模型服务商（Hy3 / GLM / Kimi / ChatGPT / Gemini / Claude / DeepSeek），
 * 选中后填写 API Key 与模型名，可先测试连接再保存启用。
 */
public class AiSetupActivity extends AppCompatActivity {

    private Prefs prefs;
    private GridLayout providerGrid;
    private final java.util.Map<AiProvider, MaterialCardView> cards = new java.util.HashMap<>();

    private AiProvider selected;
    private TextView configTitle, testResult;
    private TextInputEditText modelInput, apiKeyInput;
    private MaterialButton testButton, saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ai_setup);

        prefs = new Prefs(this);
        providerGrid = findViewById(R.id.providerGrid);
        configTitle = findViewById(R.id.configTitle);
        testResult = findViewById(R.id.testResult);
        modelInput = findViewById(R.id.modelInput);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        testButton = findViewById(R.id.testButton);
        saveButton = findViewById(R.id.saveButton);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        buildProviderGrid();

        // 默认选中：已配置的服务商，否则 DeepSeek（默认推荐）
        AiProvider current = AiProvider.fromId(prefs.getAiProviderId());
        select(current != null ? current : AiProvider.DEEPSEEK);

        testButton.setOnClickListener(v -> runConnectionTest());
        saveButton.setOnClickListener(v -> saveAndFinish());
    }

    private void buildProviderGrid() {
        LayoutInflater inflater = LayoutInflater.from(this);
        String configuredId = prefs.getAiProviderId();

        // DeepSeek 置顶大卡片（不在网格中重复出现）
        MaterialCardView dsCard = findViewById(R.id.deepSeekCard);
        ((TextView) findViewById(R.id.deepSeekModelText)).setText(
                getString(R.string.ai_provider_model_default, AiProvider.DEEPSEEK.defaultModel));
        dsCard.setOnClickListener(v -> select(AiProvider.DEEPSEEK));
        cards.put(AiProvider.DEEPSEEK, dsCard);
        // 官网链接：跳转浏览器
        findViewById(R.id.deepSeekLink).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(getString(R.string.ai_deepseek_site))));
            } catch (Exception e) {
                Toast.makeText(this, R.string.file_scan_no_file_manager, Toast.LENGTH_SHORT).show();
            }
        });

        for (final AiProvider p : AiProvider.values()) {
            if (p == AiProvider.DEEPSEEK) continue; // 已由置顶大卡片承载
            View cardView = inflater.inflate(R.layout.item_ai_provider, providerGrid, false);
            MaterialCardView card = cardView.findViewById(R.id.providerCard);

            FrameLayout circle = cardView.findViewById(R.id.providerBadgeCircle);
            TextView badge = cardView.findViewById(R.id.providerBadgeText);
            TextView name = cardView.findViewById(R.id.providerName);
            TextView model = cardView.findViewById(R.id.providerModel);
            TextView state = cardView.findViewById(R.id.providerState);

            circle.getBackground().setTint(ContextCompat.getColor(this, p.brandColorRes));
            badge.setText(p.badgeText);
            name.setText(p.displayName(this));
            model.setText(getString(R.string.ai_provider_model_default, p.defaultModel));
            state.setVisibility(p.id.equals(configuredId) ? View.VISIBLE : View.GONE);

            card.setOnClickListener(v -> select(p));
            cards.put(p, card);

            // GridLayout 子项需设置列权重以撑满两列
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            cardView.setLayoutParams(lp);
            providerGrid.addView(cardView);
        }
    }

    private void select(AiProvider p) {
        selected = p;
        configTitle.setText(getString(R.string.ai_config_title, p.displayName(this)));

        // 更新卡片选中态：选中的描边高亮且保持明亮，其余（含 DeepSeek 大卡片）变暗
        for (java.util.Map.Entry<AiProvider, MaterialCardView> e : cards.entrySet()) {
            boolean isSel = e.getKey() == p;
            MaterialCardView card = e.getValue();
            card.setStrokeWidth(isSel ? 2 : 1);
            card.setStrokeColor(ContextCompat.getColor(this,
                    isSel ? p.brandColorRes : R.color.md_theme_outlineVariant));
            card.animate().alpha(isSel ? 1f : 0.4f).setDuration(200).start();
        }

        // 预填模型名与 Key（同一服务商保留自定义，切换则用默认）
        String savedModel = prefs.getAiModel();
        if (p.id.equals(prefs.getAiProviderId()) && !TextUtils.isEmpty(savedModel)) {
            modelInput.setText(savedModel);
        } else {
            modelInput.setText(p.defaultModel);
        }
        if (p.id.equals(prefs.getAiProviderId())) {
            apiKeyInput.setText(prefs.getAiApiKey());
        } else {
            apiKeyInput.setText("");
        }
        testResult.setVisibility(View.GONE);
    }

    private void runConnectionTest() {
        final String key = text(apiKeyInput);
        final String model = text(modelInput);
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.ai_key_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        testButton.setEnabled(false);
        testResult.setText(R.string.ai_testing);
        testResult.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant));
        testResult.setVisibility(View.VISIBLE);

        final AiProvider p = selected;
        new Thread(() -> {
            try {
                String reply = AiClient.chat(this, p, key, model,
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

    private void saveAndFinish() {
        final String key = text(apiKeyInput);
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.ai_key_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setAiProviderId(selected.id);
        prefs.setAiApiKey(key);
        prefs.setAiModel(text(modelInput));
        Toast.makeText(this, getString(R.string.ai_saved_toast, selected.displayName(this)),
                Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private String text(TextInputEditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
