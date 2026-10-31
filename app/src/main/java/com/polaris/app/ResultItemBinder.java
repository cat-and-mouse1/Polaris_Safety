package com.polaris.app;

import android.app.Activity;
import java.io.File;
import java.util.List;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.Manifest;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import com.google.android.material.button.MaterialButton;
import com.polaris.app.util.AiClient;
import com.polaris.app.util.QuarantineManager;
import com.polaris.app.util.BehaviorLog;
import com.polaris.app.util.PermUtil;
import com.polaris.app.util.RootChecker;
import com.polaris.app.util.ShizukuHelper;
import com.polaris.app.service.GuardService;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.polaris.app.scan.AppRiskInfo;
import com.polaris.app.scan.FileRiskInfo;
import com.polaris.app.util.Prefs;

/**
 * ResultItemBinder（MainActivity 拆分）：方法体自 MainActivity 原样搬移，
 * 字段/继承方法经 act. 引用；MainActivity 保留同名委托，调用点零改动。
 */
final class ResultItemBinder {

    private final MainActivity act;

    ResultItemBinder(MainActivity act) {
        this.act = act;
    }

         TextView buildEmptyView(int textRes) {
        TextView empty = new TextView(act);
        empty.setText(textRes);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(act.getColor(R.color.md_theme_onSurfaceVariant));
        empty.setTextSize(15);
        empty.setPadding(0, 24, 0, 24);
        return empty;
    }

         void bindFileRiskCompact(View item, FileRiskInfo info) {
        TextView levelText = item.findViewById(R.id.riskLevelText);
        TextView fileName = item.findViewById(R.id.fileName);
        TextView filePath = item.findViewById(R.id.filePath);

        fileName.setText(info.name);
        filePath.setText(act.displayPath(info));

        switch (info.level) {
            case FileRiskInfo.LEVEL_HIGH:
                levelText.setText(R.string.risk_high);
                levelText.setTextColor(act.getColor(R.color.md_theme_error));
                break;
            case FileRiskInfo.LEVEL_MEDIUM:
                levelText.setText(R.string.risk_medium);
                levelText.setTextColor(act.getColor(R.color.md_theme_tertiary));
                break;
            default:
                levelText.setText(R.string.risk_low);
                levelText.setTextColor(act.getColor(R.color.md_theme_secondary));
                break;
        }
        item.setOnClickListener(v -> showFileActions(info, v));
    }

         void bindAppRiskCompact(View item, AppRiskInfo info) {
        ImageView icon = item.findViewById(R.id.appIcon);
        TextView name = item.findViewById(R.id.appName);
        TextView pkg = item.findViewById(R.id.packageName);
        TextView chip = item.findViewById(R.id.riskLevelChip);

        if (info.icon != null) icon.setImageDrawable(info.icon);
        name.setText(info.appName != null ? info.appName : info.packageName);
        pkg.setText(info.packageName);

        switch (info.level) {
            case AppRiskInfo.LEVEL_HIGH:
                chip.setText(R.string.risk_high);
                chip.setTextColor(act.getColor(R.color.md_theme_error));
                break;
            case AppRiskInfo.LEVEL_MEDIUM:
                chip.setText(R.string.risk_medium);
                chip.setTextColor(act.getColor(R.color.md_theme_tertiary));
                break;
            case AppRiskInfo.LEVEL_LOW:
                chip.setText(R.string.risk_low);
                chip.setTextColor(act.getColor(R.color.md_theme_secondary));
                break;
            default:
                chip.setText(R.string.risk_safe);
                chip.setTextColor(act.getColor(R.color.md_theme_onSurfaceVariant));
                break;
        }
        item.setOnClickListener(v -> handleApp(info));
    }

         void showFileActions(FileRiskInfo info, View anchor) {
        String[] actions = {
                act.getString(R.string.file_action_quarantine),
                act.getString(R.string.file_action_allow),
                act.getString(R.string.file_action_delete),
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                .setTitle(info.name)
                .setItems(actions, (d, which) -> {
                    if (which == 0) quarantine(info, anchor);
                    else if (which == 1) allowFile(info);
                    else deleteFile(info, anchor);
                })
                .show();
    }

         void bindAppRiskItem(View item, AppRiskInfo info) {
        ImageView icon = item.findViewById(R.id.appIcon);
        TextView name = item.findViewById(R.id.appName);
        TextView pkg = item.findViewById(R.id.packageName);
        TextView score = item.findViewById(R.id.scoreText);
        TextView reason = item.findViewById(R.id.reasonText);
        TextView chip = item.findViewById(R.id.riskLevelChip);
        TextView mlBadge = item.findViewById(R.id.mlBadge);
        com.google.android.material.button.MaterialButton handle =
                item.findViewById(R.id.handleButton);
        com.google.android.material.button.MaterialButton mlBtn =
                item.findViewById(R.id.mlResultBtn);

        if (icon != null && info.icon != null) icon.setImageDrawable(info.icon);
        name.setText(info.appName != null ? info.appName : info.packageName);
        pkg.setText(info.packageName);
        score.setText("风险指数 " + info.score + " / 100"
                + (info.isSystem ? " · 系统应用" : ""));

        switch (info.level) {
            case AppRiskInfo.LEVEL_HIGH:
                chip.setText(R.string.risk_high);
                chip.setTextColor(Color.WHITE);
                chip.setBackgroundTintList(ColorStateList.valueOf(act.getColor(R.color.md_theme_error)));
                break;
            case AppRiskInfo.LEVEL_MEDIUM:
                chip.setText(R.string.risk_medium);
                chip.setTextColor(act.getColor(R.color.md_theme_onTertiaryContainer));
                chip.setBackgroundTintList(
                        ColorStateList.valueOf(act.getColor(R.color.md_theme_tertiaryContainer)));
                break;
            case AppRiskInfo.LEVEL_LOW:
                chip.setText(R.string.risk_low);
                chip.setTextColor(act.getColor(R.color.md_theme_onSecondaryContainer));
                chip.setBackgroundTintList(
                        ColorStateList.valueOf(act.getColor(R.color.md_theme_secondaryContainer)));
                break;
            default:
                chip.setText(R.string.risk_safe);
                chip.setTextColor(act.getColor(R.color.md_theme_onSurfaceVariant));
                chip.setBackgroundTintList(
                        ColorStateList.valueOf(act.getColor(R.color.md_theme_surfaceContainerHighest)));
                break;
        }

        String reasonText = act.joinReasons(info.reasons);
        reason.setText(reasonText.isEmpty() ? act.getString(R.string.scan_no_risk) : reasonText);

        if (info.mlScore >= 0) {
            mlBadge.setVisibility(View.VISIBLE);
            mlBadge.setText("ML: " + info.mlScore + "/100");
            mlBadge.setTextColor(Color.WHITE);
            mlBadge.setBackgroundTintList(
                    ColorStateList.valueOf(act.getColor(R.color.md_theme_tertiary)));
            mlBtn.setVisibility(View.VISIBLE);
            mlBtn.setText("查看");
            mlBtn.setOnClickListener(v -> showMlDialog(info));
        } else {
            mlBadge.setVisibility(View.GONE);
            mlBtn.setVisibility(View.GONE);
        }

        bindAppAiPanel(item, info);

        handle.setText(info.level >= AppRiskInfo.LEVEL_MEDIUM
                ? R.string.action_handle : R.string.action_app_details);
        handle.setOnClickListener(v -> handleApp(info));
    }

         void bindAppAiPanel(View item, AppRiskInfo info) {
        View panel = item.findViewById(R.id.aiVerdictPanel);
        TextView chip = item.findViewById(R.id.aiVerdictChip);
        TextView confidence = item.findViewById(R.id.aiConfidenceText);
        TextView reasonText = item.findViewById(R.id.aiReasonText);

        if (!act.useAiEngine()) {
            panel.setVisibility(View.GONE);
            return;
        }
        panel.setVisibility(View.VISIBLE);

        AiClient.AiVerdict v = null;
        if (act.lastAppVerdicts != null) {
            for (AiClient.AiVerdict x : act.lastAppVerdicts) {
                if (info.packageName.equals(x.packageName)) {
                    v = x;
                    break;
                }
            }
        }
        if (v == null) {
            chip.setText(R.string.ai_verdict_pending);
            chip.setTextColor(act.getColor(R.color.md_theme_onSurfaceVariant));
            chip.setBackgroundTintList(
                    ColorStateList.valueOf(act.getColor(R.color.md_theme_surfaceContainerHighest)));
            confidence.setText("");
            reasonText.setText("");
            return;
        }
        if (v.remove) {
            chip.setText(R.string.ai_verdict_remove);
            chip.setTextColor(act.getColor(R.color.md_theme_onError));
            chip.setBackgroundTintList(ColorStateList.valueOf(act.getColor(R.color.md_theme_error)));
        } else {
            chip.setText(R.string.ai_verdict_keep);
            chip.setTextColor(Color.WHITE);
            chip.setBackgroundTintList(ColorStateList.valueOf(act.getColor(R.color.ai_success)));
        }
        confidence.setText(act.getString(R.string.ai_confidence, Math.round(v.confidence * 100)));
        reasonText.setText(v.reason != null && !v.reason.isEmpty()
                ? act.getString(R.string.ai_reason_prefix, v.reason) : "");
    }

         void showMlDialog(AppRiskInfo info) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                .setTitle(R.string.ml_results_title)
                .setMessage(act.getString(R.string.ml_detail_format,
                        info.appName != null ? info.appName : info.packageName,
                        info.mlScore,
                        info.mlReason != null ? info.mlReason : act.getString(R.string.ml_unknown)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

         void handleApp(AppRiskInfo info) {
        int mode = act.prefs.getActiveMode();
        if (mode == Prefs.MODE_SHIZUKU) {
            boolean ok = ShizukuHelper.runShell("am", "force-stop", info.packageName) != null;
            Toast.makeText(act, ok ? R.string.action_force_stop : R.string.mode_status_no_permission,
                    Toast.LENGTH_SHORT).show();
            if (ok) BehaviorLog.log(act, BehaviorLog.TYPE_FORCE_STOP,
                    String.valueOf(info.appName),
                    act.getString(R.string.behavior_detail_force_stop, info.appName));
        } else if (mode == Prefs.MODE_ROOT) {
            boolean stopped = RootChecker.runAsRoot("am force-stop " + info.packageName);
            boolean frozen = RootChecker.runAsRoot("pm disable-user --user 0 " + info.packageName);
            Toast.makeText(act,                     stopped && frozen ? R.string.action_freeze : R.string.mode_status_no_permission,
                    Toast.LENGTH_SHORT).show();
            if (stopped) BehaviorLog.log(act, BehaviorLog.TYPE_FORCE_STOP,
                    String.valueOf(info.appName),
                    act.getString(R.string.behavior_detail_force_stop, info.appName));
            if (frozen) BehaviorLog.log(act, BehaviorLog.TYPE_FREEZE,
                    String.valueOf(info.appName),
                    act.getString(R.string.behavior_detail_freeze, info.appName));
        }
        openAppDetails(info.packageName);
    }

         void openAppDetails(String pkg) {
        try {
            act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)));
        } catch (Exception e) {
            Toast.makeText(act, R.string.action_app_details, Toast.LENGTH_SHORT).show();
        }
    }

         void bindRiskItem(View item, FileRiskInfo info) {
        TextView levelText = item.findViewById(R.id.riskLevelText);
        TextView fileName = item.findViewById(R.id.fileName);
        TextView filePath = item.findViewById(R.id.filePath);
        TextView fileMeta = item.findViewById(R.id.fileMeta);
        TextView fileReasons = item.findViewById(R.id.fileReasons);
        TextView mlBadge = item.findViewById(R.id.mlBadge);

        fileName.setText(info.name);
        filePath.setText(act.displayPath(info));
        fileMeta.setText(act.getString(R.string.file_meta, act.humanSize(info.size), info.score));
        fileReasons.setText(act.joinReasons(info.reasons));

        if (info.mlScore >= 0) {
            int mlLevel;
            if (info.mlScore >= 80) mlLevel = FileRiskInfo.LEVEL_HIGH;
            else if (info.mlScore >= 45) mlLevel = FileRiskInfo.LEVEL_MEDIUM;
            else if (info.mlScore >= 20) mlLevel = FileRiskInfo.LEVEL_LOW;
            else mlLevel = FileRiskInfo.LEVEL_SAFE;
            String mlLabel;
            int mlColor;
            switch (mlLevel) {
                case FileRiskInfo.LEVEL_HIGH:
                    mlLabel = act.getString(R.string.risk_high);
                    mlColor = act.getColor(R.color.md_theme_error);
                    break;
                case FileRiskInfo.LEVEL_MEDIUM:
                    mlLabel = act.getString(R.string.risk_medium);
                    mlColor = act.getColor(R.color.md_theme_tertiary);
                    break;
                case FileRiskInfo.LEVEL_LOW:
                    mlLabel = act.getString(R.string.risk_low);
                    mlColor = act.getColor(R.color.md_theme_secondary);
                    break;
                default:
                    mlLabel = act.getString(R.string.risk_safe);
                    mlColor = act.getColor(R.color.ai_success);
                    break;
            }
            mlBadge.setVisibility(View.VISIBLE);
            mlBadge.setText("ML: " + mlLabel);
            mlBadge.setTextColor(Color.WHITE);
            mlBadge.setBackgroundTintList(ColorStateList.valueOf(mlColor));
        } else {
            mlBadge.setVisibility(View.GONE);
        }

        switch (info.level) {
            case FileRiskInfo.LEVEL_HIGH:
                levelText.setText(R.string.risk_high);
                levelText.setTextColor(act.getColor(R.color.md_theme_error));
                break;
            case FileRiskInfo.LEVEL_MEDIUM:
                levelText.setText(R.string.risk_medium);
                levelText.setTextColor(act.getColor(R.color.md_theme_tertiary));
                break;
            default:
                levelText.setText(R.string.risk_low);
                levelText.setTextColor(act.getColor(R.color.md_theme_secondary));
                break;
        }

        View aiPanel = item.findViewById(R.id.aiVerdictPanel);
        if (act.useAiEngine() && info.verdict != null) {
            TextView chip = item.findViewById(R.id.aiVerdictChip);
            TextView conf = item.findViewById(R.id.aiConfidenceText);
            TextView reason = item.findViewById(R.id.aiReasonText);
            aiPanel.setVisibility(View.VISIBLE);
            if (info.verdict.remove) {
                chip.setText(R.string.ai_verdict_remove);
                chip.setTextColor(Color.WHITE);
                chip.setBackgroundTintList(ColorStateList.valueOf(act.getColor(R.color.md_theme_error)));
                conf.setText(act.getString(R.string.ai_confidence, Math.round(info.verdict.confidence * 100)));
                reason.setText(act.getString(R.string.ai_reason_prefix, info.verdict.reason));
            } else {
                chip.setText(R.string.ai_verdict_keep);
                chip.setTextColor(act.getColor(R.color.ai_success));
                chip.setBackgroundTintList(ColorStateList.valueOf(act.getColor(R.color.md_theme_surfaceContainerHighest)));
                conf.setText(act.getString(R.string.ai_confidence, Math.round(info.verdict.confidence * 100)));
                reason.setText(act.getString(R.string.ai_reason_prefix, info.verdict.reason));
            }
            if (info.mlScore >= 0) {
                reason.append("\n\n[ML Detection]\nScore: " + info.mlScore + "/100\nAnalysis: " + info.mlReason);
            }
        } else if (act.useAiEngine()) {
            TextView chip = item.findViewById(R.id.aiVerdictChip);
            aiPanel.setVisibility(View.VISIBLE);
            chip.setText(R.string.ai_verdict_pending);
            chip.setTextColor(act.getColor(R.color.md_theme_onSurfaceVariant));
            chip.setBackgroundTintList(ColorStateList.valueOf(act.getColor(R.color.md_theme_surfaceContainerHighest)));
            if (info.mlScore >= 0) {
                TextView reason = item.findViewById(R.id.aiReasonText);
                reason.setText("[ML Detection]\nScore: " + info.mlScore + "/100\nAnalysis: " + info.mlReason);
            }
        } else {
            aiPanel.setVisibility(View.GONE);
        }

        item.findViewById(R.id.btnQuarantine).setOnClickListener(v -> quarantine(info, v));
        item.findViewById(R.id.btnAllow).setOnClickListener(v -> allowFile(info));
        item.findViewById(R.id.btnDelete).setOnClickListener(v -> deleteFile(info, v));

        com.google.android.material.button.MaterialButton mlBtn = item.findViewById(R.id.mlResultBtn);
        if (info.mlScore >= 0) {
            mlBtn.setVisibility(View.VISIBLE);
            mlBtn.setOnClickListener(v -> showMlResultDialog(info));
        } else {
            mlBtn.setVisibility(View.GONE);
        }
    }

         void quarantine(FileRiskInfo info, View btn) {
        QuarantineManager.QuarantineRecord rec;
        if ("saf".equals(info.source)) {
            // 多文件夹扫描：优先使用该文件所属的那棵授权树
            Uri ownerTree = info.treeUri != null ? Uri.parse(info.treeUri) : act.currentTreeUri;
            rec = act.qm.quarantineSaf(ownerTree, Uri.parse(info.path), info.name,
                    info.name, info.size, act.joinReasons(info.reasons), info.score);
        } else {
            rec = act.qm.quarantineFile(new File(info.path), act.joinReasons(info.reasons), info.score);
        }
        if (rec != null) {
            Toast.makeText(act, R.string.file_quarantined_toast, Toast.LENGTH_SHORT).show();
            removeResultItem(btn);
            BehaviorLog.log(act, BehaviorLog.TYPE_QUARANTINE, info.name,
                    act.joinReasons(info.reasons));
        } else {
            Toast.makeText(act, R.string.file_action_failed, Toast.LENGTH_LONG).show();
        }
    }

         void allowFile(FileRiskInfo info) {
        act.prefs.addToFileAllowlist(info.path);
        Toast.makeText(act, R.string.file_allowed_toast, Toast.LENGTH_SHORT).show();
        // 该条目在下次扫描时跳过；此处不立即移除以免打断浏览
    }

         void deleteFile(FileRiskInfo info, View btn) {
        boolean deleted = false;
        try {
            if ("saf".equals(info.source)
                    && (info.treeUri != null || act.currentTreeUri != null)) {
                DocumentFile df = DocumentFile.fromSingleUri(act, Uri.parse(info.path));
                deleted = df != null && df.exists() && df.delete();
            } else {
                File f = new File(info.path);
                deleted = f.exists() && f.delete();
            }
        } catch (Exception ignored) {
            deleted = false;
        }
        if (deleted) {
            Toast.makeText(act, R.string.file_deleted_toast, Toast.LENGTH_SHORT).show();
            removeResultItem(btn);
            BehaviorLog.log(act, BehaviorLog.TYPE_DELETE, info.name,
                    act.joinReasons(info.reasons));
        } else {
            Toast.makeText(act, R.string.file_action_failed, Toast.LENGTH_LONG).show();
        }
    }

         void removeResultItem(View btn) {
        try {
            View card = (View) ((View) btn.getParent()).getParent();
            if (card.getParent() instanceof ViewGroup) {
                ((ViewGroup) card.getParent()).removeView(card);
            }
        } catch (Exception ignored) {
        }
    }

         void showMlResultDialog(FileRiskInfo info) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(act)
                .setTitle(act.getString(R.string.ml_results_title))
                .setMessage(act.getString(R.string.ml_detail_format,
                        info.name, info.mlScore,
                        info.mlReason != null ? info.mlReason : act.getString(R.string.ml_unknown)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
