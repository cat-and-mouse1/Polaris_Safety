package com.polaris.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.polaris.app.R;
import com.polaris.app.scan.IocDatabase;
import com.polaris.app.util.AiProvider;
import com.polaris.app.util.Prefs;

/**
 * 设置页：重新选择点击模式后的默认引擎（Normal 机械扫描 / Artificial Intelligence AI 判定）；
 * 查看并跳转 AI 引擎配置；语言切换（中 / 英 / 日三种）；应用信息入口。
 *
 * 守护模式的选择已移至主页「重新配置」入口（扫描中心左上角按钮），此处不再重复提供。
 */
public class SettingsActivity extends AppCompatActivity {

    /** 三种语言（中 / 英 / 日），名称以各自语言原样显示。 */
    private static final String[][] LANGUAGES = {
            // {语言标签, 显示名}
            {"zh-CN", "简体中文"},
            {"en", "English"},
            {"ja", "日本語"},
    };

    private Prefs prefs;
    private LinearLayout engineGroup;
    private TextView aiStatusTitle, aiStatusDesc, languageCurrentText;
    private TextView virusDbVersionText, virusDbUpdatedText, virusDbEntriesText;
    private SwitchMaterial virusDbAutoSwitch;
    private MaterialButton virusDbUpdateButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);
        engineGroup = findViewById(R.id.engineGroup);
        aiStatusTitle = findViewById(R.id.aiStatusTitle);
        aiStatusDesc = findViewById(R.id.aiStatusDesc);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.aiConfigCard).setOnClickListener(v ->
                startActivity(new Intent(this, AiSetupActivity.class)));
        findViewById(R.id.languageCard).setOnClickListener(v -> showLanguageSheet());
        findViewById(R.id.aboutCard).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        languageCurrentText = findViewById(R.id.languageCurrentText);

        virusDbVersionText = findViewById(R.id.virusDbVersionText);
        virusDbUpdatedText = findViewById(R.id.virusDbUpdatedText);
        virusDbEntriesText = findViewById(R.id.virusDbEntriesText);
        virusDbAutoSwitch = findViewById(R.id.virusDbAutoSwitch);
        virusDbUpdateButton = findViewById(R.id.virusDbUpdateButton);
        virusDbAutoSwitch.setOnCheckedChangeListener((b, checked) ->
                prefs.setVirusAutoUpdate(checked));
        virusDbUpdateButton.setOnClickListener(v -> updateVirusDb());

        buildEngineGroup();
    }

    // ---------- 默认引擎 ----------

    private void buildEngineGroup() {
        engineGroup.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        String preferred = prefs.getPreferredEngine();

        String[][] engines = {
                {Prefs.ENGINE_NORMAL, getString(R.string.ai_choice_normal),
                        getString(R.string.ai_choice_normal_desc)},
                {Prefs.ENGINE_AI, getString(R.string.ai_choice_ai),
                        getString(R.string.ai_choice_ai_desc)},
        };
        for (String[] e : engines) {
            final String engineId = e[0];
            View item = inflater.inflate(R.layout.item_option, engineGroup, false);
            ((TextView) item.findViewById(R.id.optionTitle)).setText(e[1]);
            ((TextView) item.findViewById(R.id.optionDesc)).setText(e[2]);
            boolean selected = engineId.equals(preferred);
            item.findViewById(R.id.optionRadio).setBackgroundResource(
                    selected ? R.drawable.bg_radio_on : R.drawable.bg_radio_off);
            item.findViewById(R.id.optionCard).setOnClickListener(v -> {
                prefs.setPreferredEngine(engineId);
                buildEngineGroup();
            });
            engineGroup.addView(item);
        }
    }

    // ---------- 语言切换 ----------

    /** 当前应用语言显示名；未设置（跟随系统）时返回对应资源。 */
    private String currentLanguageName() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty()) return getString(R.string.language_system);
        String tag = locales.toLanguageTags();
        for (String[] l : LANGUAGES) {
            if (l[0].equals(tag)) return l[1];
        }
        return tag;
    }

    private void showLanguageSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_language_choice, null);
        LinearLayout list = v.findViewById(R.id.languageList);
        LayoutInflater inflater = LayoutInflater.from(this);
        String current = AppCompatDelegate.getApplicationLocales().toLanguageTags();

        // 跟随系统
        View sysRow = inflater.inflate(R.layout.item_option, list, false);
        ((TextView) sysRow.findViewById(R.id.optionTitle)).setText(R.string.language_system);
        ((TextView) sysRow.findViewById(R.id.optionDesc)).setText(R.string.language_system);
        sysRow.findViewById(R.id.optionDesc).setVisibility(View.GONE);
        sysRow.findViewById(R.id.optionRadio).setBackgroundResource(
                current.isEmpty() ? R.drawable.bg_radio_on : R.drawable.bg_radio_off);
        sysRow.findViewById(R.id.optionCard).setOnClickListener(x -> {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
            dialog.dismiss();
        });
        list.addView(sysRow);

        for (final String[] lang : LANGUAGES) {
            View row = inflater.inflate(R.layout.item_option, list, false);
            ((TextView) row.findViewById(R.id.optionTitle)).setText(lang[1]);
            row.findViewById(R.id.optionDesc).setVisibility(View.GONE);
            row.findViewById(R.id.optionRadio).setBackgroundResource(
                    lang[0].equals(current) ? R.drawable.bg_radio_on : R.drawable.bg_radio_off);
            row.findViewById(R.id.optionCard).setOnClickListener(x -> {
                AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(lang[0]));
                dialog.dismiss();
            });
            list.addView(row);
        }

        dialog.setContentView(v);
        dialog.show();
    }

    // ---------- AI 状态 ----------

    @Override
    protected void onResume() {
        super.onResume();
        languageCurrentText.setText(currentLanguageName());
        refreshVirusDb();
        AiProvider p = AiProvider.fromId(prefs.getAiProviderId());
        if (p != null && prefs.isAiConfigured()) {
            aiStatusTitle.setText(getString(R.string.ai_home_status_on, p.displayName(this)));
            aiStatusDesc.setText(R.string.ai_configured_badge);
        } else {
            aiStatusTitle.setText(R.string.ai_home_status_off);
            aiStatusDesc.setText(R.string.ai_not_configured);
        }
    }

    // ---------- 病毒库 · Polar Region ----------

    private void refreshVirusDb() {
        IocDatabase db = IocDatabase.getInstance(this);
        virusDbVersionText.setText(getString(R.string.virusdb_version, db.getDbVersion()));
        virusDbUpdatedText.setText(getString(R.string.virusdb_updated, db.getUpdatedAt()));
        virusDbEntriesText.setText(getString(R.string.virusdb_entries, db.size()));
        virusDbAutoSwitch.setOnCheckedChangeListener(null);
        virusDbAutoSwitch.setChecked(prefs.getVirusAutoUpdate());
        virusDbAutoSwitch.setOnCheckedChangeListener((b, checked) ->
                prefs.setVirusAutoUpdate(checked));
    }

    private void updateVirusDb() {
        IocDatabase db = IocDatabase.getInstance(this);
        virusDbUpdateButton.setEnabled(false);
        virusDbUpdateButton.setText(R.string.virusdb_updating);
        db.refresh(new IocDatabase.RefreshCallback() {
            @Override
            public void onUpdated(int newVersion) {
                runOnUiThread(() -> {
                    virusDbUpdateButton.setEnabled(true);
                    virusDbUpdateButton.setText(R.string.virusdb_update_now);
                    Toast.makeText(SettingsActivity.this,
                            getString(R.string.virusdb_updated_toast, newVersion),
                            Toast.LENGTH_SHORT).show();
                    refreshVirusDb();
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    virusDbUpdateButton.setEnabled(true);
                    virusDbUpdateButton.setText(R.string.virusdb_update_now);
                    Toast.makeText(SettingsActivity.this,
                            getString(R.string.virusdb_update_failed, message),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
