package com.polaris.app;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.polaris.app.R;
import com.polaris.app.scan.AppRiskInfo;
import com.polaris.app.util.AiClient;
import com.polaris.app.util.Prefs;
import com.polaris.app.util.RootChecker;
import com.polaris.app.util.ShizukuHelper;

import java.util.List;
import java.util.Map;

/**
 * 扫描结果页：展示风险应用列表，按模式能力提供处置入口。
 *
 * AI 扫描时（intent extra "ai" = true）额外展示每项应用的
 * AI 判定结果：建议清除 / 建议保留 + 置信度 + AI 理由。
 */
public class ResultActivity extends AppCompatActivity {

    private List<AppRiskInfo> risks;
    private List<AiClient.AiVerdict> verdicts;
    private Map<String, AiClient.AiVerdict> verdictIndex;
    private boolean aiMode;
    private RecyclerView list;
    private TextView emptyText;
    private MaterialCardView aiSummaryCard;
    private TextView aiSummaryText;
    private int activeMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result);

        activeMode = new Prefs(this).getActiveMode();
        risks = MainActivity.lastRisks;
        aiMode = getIntent().getBooleanExtra("ai", false);
        verdicts = MainActivity.aiVerdicts;
        if (verdicts != null) {
            verdictIndex = AiClient.indexOf(verdicts);
        }

        TextView badge = findViewById(R.id.scanTypeBadge);
        boolean deep = getIntent().getBooleanExtra("deep", false);
        badge.setText(deep ? R.string.scan_deep_badge : R.string.scan_shallow_badge);

        aiSummaryCard = findViewById(R.id.aiSummaryCard);
        aiSummaryText = findViewById(R.id.aiSummaryText);

        list = findViewById(R.id.riskList);
        emptyText = findViewById(R.id.emptyText);
        list.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        render();
    }

    private void render() {
        TextView summary = findViewById(R.id.summaryText);
        if (risks == null || risks.isEmpty()) {
            summary.setText(R.string.scan_done_clean);
            emptyText.setVisibility(View.VISIBLE);
            emptyText.setText(R.string.scan_done_clean);
            list.setVisibility(View.GONE);
            return;
        }

        int suspicious = 0;
        for (AppRiskInfo r : risks) {
            if (r.level >= AppRiskInfo.LEVEL_MEDIUM) suspicious++;
        }
        summary.setText(suspicious > 0
                ? getString(R.string.scan_done_risk, suspicious)
                : getString(R.string.scan_done_clean));

        // AI 汇总横幅
        if (aiMode) {
            renderAiSummary();
        }
        list.setAdapter(new RiskAdapter(risks));
    }

    private void renderAiSummary() {
        if (verdictIndex == null || verdictIndex.isEmpty()) {
            return;
        }
        int remove = 0, keep = 0;
        float confSum = 0f;
        int n = 0;
        for (AiClient.AiVerdict v : verdictIndex.values()) {
            if (v.remove) remove++; else keep++;
            confSum += v.confidence;
            n++;
        }
        int avg = n > 0 ? Math.round(confSum * 100 / n) : 0;
        aiSummaryText.setText(getString(R.string.ai_summary, remove, keep, avg));
        aiSummaryCard.setVisibility(View.VISIBLE);
    }

    private void handle(AppRiskInfo info) {
        switch (activeMode) {
            case Prefs.MODE_SHIZUKU:
                boolean ok = ShizukuHelper.runShell("am", "force-stop", info.packageName) != null;
                Toast.makeText(this, ok ? R.string.action_force_stop : R.string.mode_status_no_permission,
                        Toast.LENGTH_SHORT).show();
                openAppDetails(info.packageName);
                break;
            case Prefs.MODE_ROOT:
                boolean stopped = RootChecker.runAsRoot("am force-stop " + info.packageName);
                boolean frozen = RootChecker.runAsRoot("pm disable-user --user 0 " + info.packageName);
                Toast.makeText(this, stopped && frozen ? R.string.action_freeze : R.string.mode_status_no_permission,
                        Toast.LENGTH_SHORT).show();
                openAppDetails(info.packageName);
                break;
            default:
                openAppDetails(info.packageName);
                break;
        }
    }

    private void openAppDetails(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.action_app_details, Toast.LENGTH_SHORT).show();
        }
    }

    /** 绑定某应用的 AI 判定面板。 */
    private void bindAiVerdict(View itemView, AppRiskInfo info) {
        View panel = itemView.findViewById(R.id.aiVerdictPanel);
        TextView chip = itemView.findViewById(R.id.aiVerdictChip);
        TextView confidence = itemView.findViewById(R.id.aiConfidenceText);
        TextView reason = itemView.findViewById(R.id.aiReasonText);

        AiClient.AiVerdict v = verdictIndex != null ? verdictIndex.get(info.packageName) : null;
        panel.setVisibility(View.VISIBLE);
        if (v == null) {
            chip.setText(R.string.ai_verdict_pending);
            chip.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurfaceVariant));
            chip.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.md_theme_surfaceContainerHighest)));
            confidence.setText(R.string.ai_verdict_keep);
            reason.setText("");
            return;
        }

        if (v.remove) {
            chip.setText(R.string.ai_verdict_remove);
            chip.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onError));
            chip.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.md_theme_error)));
        } else {
            chip.setText(R.string.ai_verdict_keep);
            chip.setTextColor(ContextCompat.getColor(this, android.R.color.white));
            chip.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.ai_success)));
        }
        int conf = Math.round(v.confidence * 100);
        confidence.setText(getString(R.string.ai_confidence, conf));
        reason.setText(v.reason == null || v.reason.isEmpty()
                ? "" : getString(R.string.ai_reason_prefix, v.reason));
    }

    private class RiskAdapter extends RecyclerView.Adapter<RiskAdapter.VH> {

        private final List<AppRiskInfo> data;

        RiskAdapter(List<AppRiskInfo> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_risk, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            AppRiskInfo info = data.get(position);
            holder.name.setText(info.appName != null ? info.appName : info.packageName);
            holder.pkg.setText(info.packageName);

            if (info.icon != null) {
                holder.icon.setImageDrawable(info.icon);
            }

            String scoreStr = "风险指数 " + info.score + " / 100"
                    + (info.isSystem ? " · 系统应用" : "");
            holder.score.setText(scoreStr);

            // 等级 chip
            switch (info.level) {
                case AppRiskInfo.LEVEL_HIGH:
                    holder.chip.setText(R.string.risk_high);
                    holder.chip.setTextColor(ContextCompat.getColor(ResultActivity.this,
                            android.R.color.white));
                    holder.chip.setBackgroundTintList(ColorStateList.valueOf(
                            getColor(R.color.md_theme_error)));
                    break;
                case AppRiskInfo.LEVEL_MEDIUM:
                    holder.chip.setText(R.string.risk_medium);
                    holder.chip.setTextColor(getColor(R.color.md_theme_onTertiaryContainer));
                    holder.chip.setBackgroundTintList(ColorStateList.valueOf(
                            getColor(R.color.md_theme_tertiaryContainer)));
                    break;
                case AppRiskInfo.LEVEL_LOW:
                    holder.chip.setText(R.string.risk_low);
                    holder.chip.setTextColor(getColor(R.color.md_theme_onSecondaryContainer));
                    holder.chip.setBackgroundTintList(ColorStateList.valueOf(
                            getColor(R.color.md_theme_secondaryContainer)));
                    break;
                default:
                    holder.chip.setText(R.string.risk_safe);
                    holder.chip.setTextColor(getColor(R.color.md_theme_onSurfaceVariant));
                    holder.chip.setBackgroundTintList(ColorStateList.valueOf(
                            getColor(R.color.md_theme_surfaceContainerHighest)));
                    break;
            }

            // 理由摘要
            StringBuilder sb = new StringBuilder();
            for (String r : info.reasons) {
                if (sb.length() > 0) sb.append("；");
                sb.append(r);
            }
            if (sb.length() == 0) sb.append(getString(R.string.scan_done_clean));
            holder.reason.setText(sb.toString());

            // AI 判定面板
            if (aiMode) {
                bindAiVerdict(holder.itemView, info);
            }

            // 处置按钮：低风险/安全应用只提供详情
            if (info.level < AppRiskInfo.LEVEL_MEDIUM) {
                holder.handle.setText(R.string.action_app_details);
            } else {
                holder.handle.setText(R.string.action_handle);
            }
            holder.handle.setOnClickListener(v -> handle(info));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name, pkg, score, reason, chip;
            MaterialButton handle;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.appIcon);
                name = v.findViewById(R.id.appName);
                pkg = v.findViewById(R.id.packageName);
                score = v.findViewById(R.id.scoreText);
                reason = v.findViewById(R.id.reasonText);
                chip = v.findViewById(R.id.riskLevelChip);
                handle = v.findViewById(R.id.handleButton);
            }
        }
    }
}
