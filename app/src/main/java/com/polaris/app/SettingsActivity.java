package com.polaris.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.core.view.ViewCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.polaris.app.R;
import com.polaris.app.scan.IocDatabase;
import com.polaris.app.scan.AppScanRunner;
import com.polaris.app.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页：检测配置卡（横幅状态区 + AI 引擎 / 默认引擎 / ML 引擎三个模块卡）；
 * 语言切换（中 / 英 / 日三种）；应用信息入口。
 *
 * 守护模式的选择已移至主页「重新配置」入口（扫描中心左上角按钮），此处不再重复提供。
 * AI / ML 的高级配置（接入向导、模型详情等）在 {@link SettingsDetectionActivity} 子界面。
 */
public class SettingsActivity extends BaseActivity {

    /** 三种语言（中 / 英 / 日），名称以各自语言原样显示。 */
    private static final String[][] LANGUAGES = {
            // {语言标签, 显示名}
            {"zh-CN", "简体中文"},
            {"en", "English"},
            {"ja", "日本語"},
    };

    private Prefs prefs;
    private TextView languageCurrentText;
    private TextView detectionAiStatus;
    private TextView detectionMlStatus;
    private TextView aiQuickStatusTitle;
    private TextView aiQuickStatusDesc;
    private FrameLayout aiBadge;
    private ImageView aiBadgeIcon;
    private LinearLayout engineQuickGroup;
    private MaterialSwitch mlQuickSwitch;
    private MaterialButtonToggleGroup mlModelQuickGroup;
    private MaterialButtonToggleGroup aiModeQuickGroup;
    private MaterialButtonToggleGroup strategyQuickGroup;
    private TextView virusDbRegionStatus;
    private TextView virusDbPointStatus;
    private TextView regionUpdatedText;
    private TextView regionStatusText;
    private TextView pointUpdatedText;
    private TextView pointStatusText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);
        languageCurrentText = findViewById(R.id.languageCurrentText);
        detectionAiStatus = findViewById(R.id.detectionAiStatus);
        detectionMlStatus = findViewById(R.id.detectionMlStatus);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.languageCard).setOnClickListener(v -> showLanguageSheet());
        findViewById(R.id.aboutCard).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        // 子界面入口：病毒库 / 外观 / 检测配置
        findViewById(R.id.virusDbEntry).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsVirusDbActivity.class)));
        // 威胁情报病毒库：两列模块卡（区域库 / 点库）状态显示
        virusDbRegionStatus = findViewById(R.id.virusDbRegionStatus);
        virusDbPointStatus = findViewById(R.id.virusDbPointStatus);
        regionUpdatedText = findViewById(R.id.regionUpdatedText);
        regionStatusText = findViewById(R.id.regionStatusText);
        pointUpdatedText = findViewById(R.id.pointUpdatedText);
        pointStatusText = findViewById(R.id.pointStatusText);
        findViewById(R.id.appearanceEntry).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsAppearanceActivity.class)));
        findViewById(R.id.detectionEntry).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsDetectionActivity.class)));

        // 检测配置卡：高度增至屏幕 3/5，卡内铺简单配置（高级配置仍进子页）
        setupQuickConfigs();

        // 卡内主操作：立即 ML 扫描
        findViewById(R.id.btnQuickMlScan).setOnClickListener(v ->
                AppScanRunner.launchQuickScan(this, prefs, (MaterialButton) v));

        // AI 模块卡：状态行点击进入 AI 配置；默认引擎组
        aiQuickStatusTitle = findViewById(R.id.aiQuickStatusTitle);
        aiQuickStatusDesc = findViewById(R.id.aiQuickStatusDesc);
        aiBadge = findViewById(R.id.aiBadge);
        aiBadgeIcon = findViewById(R.id.aiBadgeIcon);
        applyBadgeSize();
        findViewById(R.id.aiQuickStatusCard).setOnClickListener(v ->
                startActivity(new Intent(this, AiSetupActivity.class)));
        engineQuickGroup = findViewById(R.id.engineQuickGroup);
        buildEngineGroup();
        refreshVirusDbCards();

        // 卡片折叠：点击横幅 header 展开/收起内容区（箭头旋转指示）
        setupCollapse(R.id.detectionHeader, R.id.detectionBody, R.id.detectionChevron);
        setupCollapse(R.id.virusDbHeader, R.id.virusDbBody, R.id.virusDbChevron);

        // 通知子界面入口：三类通知开关在独立页面
        findViewById(R.id.notifyCard).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsNotificationActivity.class)));
    }

    /**
     * 绑定卡片横幅 header 的折叠/展开：默认展开（横幅为强调色 colorPrimaryContainer）；
     * 收起时横幅背景变为与下方设置项一致的 colorSurfaceContainerLow，标题/图标颜色同步切换，
     * 使整张卡片看起来与普通设置项卡片无异。箭头旋转指示状态。
     */
    private void setupCollapse(int headerId, int bodyId, int chevronId) {
        View header = findViewById(headerId);
        View body = findViewById(bodyId);
        ImageView chevron = findViewById(chevronId);
        if (header == null || body == null) return;

        // 解析主题色（按当前主题）
        final int cPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer, 0);
        final int cSurfaceLow = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow, 0);
        final int cOnPrimaryContainer = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer, 0);
        final int cOnSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0);
        final int cPrimary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0);
        final int cOnSurfaceVariant = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0);

        // 收集标题（weight>0 的 TextView）与非 chevron 的图标（ImageView）
        final List<TextView> titles = new ArrayList<>();
        final List<ImageView> icons = new ArrayList<>();
        if (header instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) header;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof ImageView && c.getId() != chevronId) {
                    icons.add((ImageView) c);
                } else if (c instanceof TextView) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) c.getLayoutParams();
                    if (lp != null && lp.weight > 0f) titles.add((TextView) c);
                }
            }
        }

        // 初始状态按 body 实际可见性决定（通知卡默认收起，检测/病毒库默认展开）
        boolean startExpanded = body.getVisibility() == View.VISIBLE;
        chevron.setRotation(startExpanded ? 90f : -90f);
        header.setBackgroundColor(startExpanded ? cPrimaryContainer : cSurfaceLow);
        for (TextView t : titles) t.setTextColor(startExpanded ? cOnPrimaryContainer : cOnSurface);
        for (ImageView iv : icons) iv.setImageTintList(ColorStateList.valueOf(startExpanded ? cOnPrimaryContainer : cPrimary));
        if (chevron != null) chevron.setImageTintList(ColorStateList.valueOf(startExpanded ? cOnPrimaryContainer : cOnSurfaceVariant));

        header.setOnClickListener(v -> {
            boolean expand = body.getVisibility() != View.VISIBLE;
            body.setVisibility(expand ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(expand ? 90f : -90f).setDuration(200).start();
            // 收起 → 横幅融入卡片（与下方设置项同色）；展开 → 恢复强调色横幅
            int bg = expand ? cPrimaryContainer : cSurfaceLow;
            int fgTitle = expand ? cOnPrimaryContainer : cOnSurface;
            int fgIcon = expand ? cOnPrimaryContainer : cPrimary;
            int fgChevron = expand ? cOnPrimaryContainer : cOnSurfaceVariant;
            header.setBackgroundColor(bg);
            for (TextView t : titles) t.setTextColor(fgTitle);
            for (ImageView iv : icons) iv.setImageTintList(ColorStateList.valueOf(fgIcon));
            if (chevron != null) chevron.setImageTintList(ColorStateList.valueOf(fgChevron));
        });
    }

    /** 默认引擎选择（Normal 机械扫描 / AI 判定），点选即时生效。 */
    private void buildEngineGroup() {
        engineQuickGroup.removeAllViews();
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
            View item = inflater.inflate(R.layout.item_option_compact, engineQuickGroup, false);
            ((TextView) item.findViewById(R.id.optionTitle)).setText(e[1]);
            ((TextView) item.findViewById(R.id.optionDesc)).setText(e[2]);
            boolean selected = engineId.equals(preferred);
            item.findViewById(R.id.optionRadio).setBackgroundResource(
                    selected ? R.drawable.bg_radio_on : R.drawable.bg_radio_off);
            item.findViewById(R.id.optionCard).setOnClickListener(v -> {
                prefs.setPreferredEngine(engineId);
                buildEngineGroup();
            });
            engineQuickGroup.addView(item);
        }
    }

    /**
     * AI 徽章：两列等高、底边对齐（左列 match_parent + 内部 Space weight=1 把模式组顶到底边），
     * 底座按列宽取一个合理尺寸即可，圆形直径 = 列宽 60%，上限 200px、下限 140px；
     * 图标为底座一半并在圆形内居中。列内剩余空隙由 AI 卡的弹性 Space 吸收，与右列底边对齐。
     */
    private void applyBadgeSize() {
        aiBadge.post(() -> {
            View inner = findViewById(R.id.aiCardInner);
            int innerW = inner.getWidth();
            if (innerW <= 0) return;
            int badge = (int) (innerW * 0.6f);
            badge = Math.max(140, Math.min(200, badge));
            int icon = badge / 2;
            // 只改宽高、不改引用：保留 XML 里的 layout_gravity（否则 FrameLayout 子视图会掉回左上角）
            ViewGroup.LayoutParams blp = aiBadge.getLayoutParams();
            blp.width = badge;
            blp.height = badge;
            aiBadge.setLayoutParams(blp);
            ViewGroup.LayoutParams ilp = aiBadgeIcon.getLayoutParams();
            ilp.width = icon;
            ilp.height = icon;
            aiBadgeIcon.setLayoutParams(ilp);
        });
    }

    /** 检测配置卡内的简单配置：ML 开关 / ML 模型 / AI 检测模式 / 多模型策略（与子页同源写 Prefs）。 */
    private void setupQuickConfigs() {
        // ML 引擎开关
        mlQuickSwitch = findViewById(R.id.mlQuickSwitch);
        mlQuickSwitch.setChecked(prefs.isMlEnabled());
        mlQuickSwitch.setOnCheckedChangeListener((b, on) -> prefs.setMlEnabled(on));

        // ML 检测模型（轻量 / 强力）
        mlModelQuickGroup = findViewById(R.id.mlModelQuickGroup);
        mlModelQuickGroup.check(prefs.getMlModel().equals("mh1m")
                ? R.id.mlModelQuick1m : R.id.mlModelQuick100k);
        mlModelQuickGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            prefs.setMlModel(id == R.id.mlModelQuick1m ? "mh1m" : "mh100k");
        });

        // AI 检测模式（复核 / 独立）
        aiModeQuickGroup = findViewById(R.id.aiModeQuickGroup);
        aiModeQuickGroup.check(prefs.isAiIndependentMode()
                ? R.id.aiModeQuickIndependent : R.id.aiModeQuickReview);
        aiModeQuickGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            prefs.setAiDetectionMode(id == R.id.aiModeQuickIndependent
                    ? Prefs.AI_MODE_INDEPENDENT : Prefs.AI_MODE_REVIEW);
        });

        // 多模型策略（交叉检测 / 最快优先）
        strategyQuickGroup = findViewById(R.id.strategyQuickGroup);
        strategyQuickGroup.check(Prefs.MULTI_STRATEGY_FASTEST.equals(prefs.getMultiModelStrategy())
                ? R.id.strategyQuickFastest : R.id.strategyQuickEnsemble);
        strategyQuickGroup.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            prefs.setMultiModelStrategy(id == R.id.strategyQuickFastest
                    ? Prefs.MULTI_STRATEGY_FASTEST : Prefs.MULTI_STRATEGY_ENSEMBLE);
        });
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

    // ---------- 状态刷新 ----------

    /** 主页病毒库卡：读取 region / point 两库的版本、更新时间、规模与状态，刷新双列模块卡 + 横幅胶囊。 */
    private void refreshVirusDbCards() {
        final int vRegion = IocDatabase.peekDbVersion(this, "region");
        final int vPoint = IocDatabase.peekDbVersion(this, "point");
        final android.content.Context app = getApplicationContext();
        // 库文件可能是数百 MB 的 JSON，解析必须离开主线程，否则打开设置页会直接 ANR
        new Thread(() -> {
            final String updatedR;
            final String updatedP;
            try {
                updatedR = IocDatabase.getInstance(app, "region").getUpdatedAt();
                updatedP = IocDatabase.getInstance(app, "point").getUpdatedAt();
            } catch (Throwable t) {
                android.util.Log.w("SettingsActivity", "load IOC db failed", t);
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                regionUpdatedText.setText(getString(R.string.virusdb_updated, updatedR));
                regionStatusText.setText(getString(R.string.virusdb_region_scale) + " · "
                        + virusDbStatusTail("region", vRegion, vRegion >= 0));
                pointUpdatedText.setText(getString(R.string.virusdb_updated, updatedP));
                pointStatusText.setText(getString(R.string.virusdb_point_scale) + " · "
                        + virusDbStatusTail("point", vPoint, true));
                virusDbRegionStatus.setText(getString(R.string.virusdb_region_chip,
                        virusDbShortTail("region", vRegion, vRegion >= 0)));
                virusDbPointStatus.setText(getString(R.string.virusdb_point_chip,
                        virusDbShortTail("point", vPoint, true)));
            });
        }, "polaris-vdb-cards").start();
    }

    /** 与子页一致的状态尾部（含版本号）：已下载 / 未下载 / 下载中 xx% / 部分 xx%。 */
    private String virusDbStatusTail(String type, int version, boolean localAvailable) {
        String v = version >= 0 ? ("v" + version) : "v?";
        IocDatabase.PartialDownload partial = IocDatabase.peekPartialDownload(this, type);
        if (partial != null && partial.downloaded > 0) {
            int pct = partial.percent();
            if (pct < 0) {
                if ("point".equals(type)) return getString(R.string.virusdb_status_downloaded, v);
                return getString(R.string.virusdb_status_downloading, v);
            }
            if (pct >= 100) return getString(R.string.virusdb_status_downloaded, v);
            return getString(R.string.virusdb_status_partial, v, pct);
        }
        if (localAvailable) return getString(R.string.virusdb_status_downloaded, v);
        return getString(R.string.virusdb_status_not_downloaded, v);
    }

    /** 横幅胶囊用的简短状态：已下载 / 未下载 / 下载中。 */
    private String virusDbShortTail(String type, int version, boolean localAvailable) {
        IocDatabase.PartialDownload partial = IocDatabase.peekPartialDownload(this, type);
        if (partial != null && partial.downloaded > 0) {
            int pct = partial.percent();
            if (pct < 0) {
                return "point".equals(type) ? getString(R.string.virusdb_status_short_downloaded)
                        : getString(R.string.virusdb_status_short_downloading);
            }
            if (pct >= 100) return getString(R.string.virusdb_status_short_downloaded);
            return getString(R.string.virusdb_status_short_downloading);
        }
        if (localAvailable) return getString(R.string.virusdb_status_short_downloaded);
        return getString(R.string.virusdb_status_short_not_downloaded);
    }

    @Override
    protected void onResume() {
        super.onResume();
        languageCurrentText.setText(currentLanguageName());
        applyBadgeSize();
        // 检测配置卡片：AI / ML 状态两行直接显示在设置主页（开启=主题色，关闭=次要色）
        boolean aiOn = prefs.isAiConfigured();
        boolean mlOn = prefs.isMlEnabled();
        detectionAiStatus.setText(aiOn ? getString(R.string.detection_ai_on)
                : getString(R.string.detection_ai_off));
        detectionMlStatus.setText(mlOn ? getString(R.string.detection_ml_on)
                : getString(R.string.detection_ml_off));
        // 徽章：开启 = 主题色实底 + onPrimary 文字；关闭 = 默认 secondaryContainer 底 + 其文字色
        ViewCompat.setBackgroundTintList(detectionAiStatus, aiOn
                ? ColorStateList.valueOf(MaterialColors.getColor(detectionAiStatus,
                        com.google.android.material.R.attr.colorPrimary))
                : null);
        detectionAiStatus.setTextColor(MaterialColors.getColor(detectionAiStatus,
                aiOn ? com.google.android.material.R.attr.colorOnPrimary
                        : com.google.android.material.R.attr.colorOnSecondaryContainer));
        ViewCompat.setBackgroundTintList(detectionMlStatus, mlOn
                ? ColorStateList.valueOf(MaterialColors.getColor(detectionMlStatus,
                        com.google.android.material.R.attr.colorPrimary))
                : null);
        detectionMlStatus.setTextColor(MaterialColors.getColor(detectionMlStatus,
                mlOn ? com.google.android.material.R.attr.colorOnPrimary
                        : com.google.android.material.R.attr.colorOnSecondaryContainer));
        // AI 模块卡状态摘要（从 AI 配置页返回后可能已变化）+ 默认引擎回刷
        List<Prefs.AiProviderConfig> providers = prefs.getEnabledProviders();
        int modelCount = 0;
        for (Prefs.AiProviderConfig p : providers) modelCount += p.getEnabledModelCount();
        if (!providers.isEmpty() && prefs.isAiConfigured()) {
            aiQuickStatusTitle.setText(getString(R.string.ai_summary_providers, providers.size()));
            aiQuickStatusDesc.setText(getString(R.string.ai_summary_models, modelCount));
        } else {
            aiQuickStatusTitle.setText(R.string.ai_home_status_off);
            aiQuickStatusDesc.setText(R.string.ai_not_configured);
        }
        buildEngineGroup();
        // 简单配置状态回刷（可能从检测配置子页 / AI 配置页改动后返回）
        mlQuickSwitch.setChecked(prefs.isMlEnabled());
        mlModelQuickGroup.check(prefs.getMlModel().equals("mh1m")
                ? R.id.mlModelQuick1m : R.id.mlModelQuick100k);
        aiModeQuickGroup.check(prefs.isAiIndependentMode()
                ? R.id.aiModeQuickIndependent : R.id.aiModeQuickReview);
        strategyQuickGroup.check(Prefs.MULTI_STRATEGY_FASTEST.equals(prefs.getMultiModelStrategy())
                ? R.id.strategyQuickFastest : R.id.strategyQuickEnsemble);
        // 威胁情报病毒库：两库状态（从子页下载/更新后返回需回刷）
        refreshVirusDbCards();
    }
}
