package com.polaris.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.polaris.app.util.BehaviorLog;
import com.polaris.app.util.Prefs;
import com.polaris.app.util.QuarantineManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ManagementActivity extends BaseActivity {

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView emptyText;
    private TabLayout tabLayout;
    private android.widget.TextView[] navItems;
    private TextView introText;

    private QuarantineAdapter quarantineAdapter;
    private AllowlistAdapter allowlistAdapter;
    private BehaviorAdapter behaviorAdapter;

    private List<QuarantineManager.QuarantineRecord> quarantineRecords = new ArrayList<>();
    private List<String> allowlistPaths = new ArrayList<>();
    private List<BehaviorLog.Entry> behaviorEntries = new ArrayList<>();

    private QuarantineManager qm;
    private Prefs prefs;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int currentTab = 0; // 0=quarantine, 1=allowlist, 2=behavior

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_management);

        qm = new QuarantineManager(this);
        prefs = new Prefs(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);
        emptyText = findViewById(R.id.emptyText);
        tabLayout = findViewById(R.id.tabLayout);
        android.widget.LinearLayout navList = findViewById(R.id.tabNavList);
        introText = findViewById(R.id.introText);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 初始化 adapters
        quarantineAdapter = new QuarantineAdapter();
        allowlistAdapter = new AllowlistAdapter();
        behaviorAdapter = new BehaviorAdapter();

        if (tabLayout != null) {
            // 竖屏：横向 Tab
            tabLayout.addTab(tabLayout.newTab().setText(R.string.management_tab_quarantine));
            tabLayout.addTab(tabLayout.newTab().setText(R.string.management_tab_allowlist));
            tabLayout.addTab(tabLayout.newTab().setText(R.string.management_tab_behavior));

            tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    currentTab = tab.getPosition();
                    showCurrentTab();
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        } else if (navList != null) {
            // 横屏：左栏竖排导航
            navItems = new android.widget.TextView[]{
                    navList.findViewById(R.id.tabNavQuarantine),
                    navList.findViewById(R.id.tabNavAllowlist),
                    navList.findViewById(R.id.tabNavBehavior)};
            int[] navIds = {R.id.tabNavQuarantine, R.id.tabNavAllowlist, R.id.tabNavBehavior};
            for (int i = 0; i < navIds.length; i++) {
                final int pos = i;
                navList.findViewById(navIds[i]).setOnClickListener(v -> selectNavTab(pos));
            }
            selectNavTab(0);
        }

        loadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    /** 横屏左栏竖排导航：切换 + 高亮。 */
    private void selectNavTab(int pos) {
        currentTab = pos;
        showCurrentTab();
        if (navItems != null) {
            for (int i = 0; i < navItems.length; i++) {
                android.widget.TextView item = navItems[i];
                boolean sel = (i == pos);
                item.setSelected(sel);
                item.setTextColor(com.google.android.material.color.MaterialColors.getColor(item,
                        sel ? com.google.android.material.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorOnSurfaceVariant));
                item.setTypeface(null, sel ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL);
            }
        }
    }

    private void loadData() {
        progressBar.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);

        executor.execute(() -> {
            // 加载隔离区数据
            quarantineRecords.clear();
            quarantineRecords.addAll(qm.list());

            // 加载放行名单数据
            allowlistPaths.clear();
            allowlistPaths.addAll(prefs.getFileAllowlist());

            // 加载行为日志数据
            behaviorEntries.clear();
            behaviorEntries.addAll(BehaviorLog.list(ManagementActivity.this));

            mainHandler.post(() -> {
                progressBar.setVisibility(View.GONE);
                showCurrentTab();
            });
        });
    }

    private void showCurrentTab() {
        emptyText.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
        introText.setVisibility(View.VISIBLE);

        switch (currentTab) {
            case 0: // 隔离区
                recyclerView.setAdapter(quarantineAdapter);
                introText.setText(R.string.quarantine_intro);
                if (quarantineRecords.isEmpty()) {
                    emptyText.setText(R.string.quarantine_empty);
                    emptyText.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                }
                break;
            case 1: // 放行名单
                recyclerView.setAdapter(allowlistAdapter);
                introText.setText(R.string.allowlist_intro);
                if (allowlistPaths.isEmpty()) {
                    emptyText.setText(R.string.allowlist_empty);
                    emptyText.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                }
                break;
            case 2: // 行为日志
                recyclerView.setAdapter(behaviorAdapter);
                introText.setText(R.string.behavior_log_intro);
                if (behaviorEntries.isEmpty()) {
                    emptyText.setText(R.string.behavior_empty);
                    emptyText.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                }
                break;
        }
    }

    // ========== 隔离区 Adapter ==========

    class QuarantineAdapter extends RecyclerView.Adapter<QuarantineAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_management_quarantine, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            QuarantineManager.QuarantineRecord record = quarantineRecords.get(position);
            holder.itemName.setText(record.fileName);
            holder.itemPath.setText(record.originalPath);

            holder.btnAction1.setText(R.string.quarantine_restore);
            holder.btnAction2.setText(R.string.quarantine_allow);
            holder.btnAction3.setText(R.string.file_action_delete);

            holder.btnAction1.setOnClickListener(v -> {
                boolean ok = qm.restore(record.id);
                Toast.makeText(ManagementActivity.this, ok ? R.string.file_restored_toast : R.string.file_action_failed, Toast.LENGTH_SHORT).show();
                if (ok) {
                    quarantineRecords.remove(position);
                    notifyItemRemoved(position);
                    if (quarantineRecords.isEmpty()) showCurrentTab();
                }
            });

            holder.btnAction2.setOnClickListener(v -> {
                boolean ok = qm.allow(record.id);
                Toast.makeText(ManagementActivity.this, ok ? R.string.file_allowed_toast : R.string.file_action_failed, Toast.LENGTH_SHORT).show();
                if (ok) {
                    quarantineRecords.remove(position);
                    notifyItemRemoved(position);
                    allowlistPaths.clear();
                    allowlistPaths.addAll(prefs.getFileAllowlist());
                    if (quarantineRecords.isEmpty()) showCurrentTab();
                }
            });

            holder.btnAction3.setOnClickListener(v -> {
                new AlertDialog.Builder(ManagementActivity.this)
                    .setMessage(R.string.file_action_delete)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        qm.removeRecord(record.id);
                        quarantineRecords.remove(position);
                        notifyItemRemoved(position);
                        if (quarantineRecords.isEmpty()) showCurrentTab();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            });
        }

        @Override
        public int getItemCount() { return quarantineRecords.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView itemName, itemPath;
            com.google.android.material.button.MaterialButton btnAction1, btnAction2, btnAction3;
            ViewHolder(View itemView) {
                super(itemView);
                itemName = itemView.findViewById(R.id.itemName);
                itemPath = itemView.findViewById(R.id.itemPath);
                btnAction1 = itemView.findViewById(R.id.btnAction1);
                btnAction2 = itemView.findViewById(R.id.btnAction2);
                btnAction3 = itemView.findViewById(R.id.btnAction3);
            }
        }
    }

    // ========== 放行名单 Adapter ==========

    class AllowlistAdapter extends RecyclerView.Adapter<AllowlistAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_management_allowlist, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String path = allowlistPaths.get(position);
            holder.itemPath.setText(path);

            holder.btnRemove.setOnClickListener(v -> {
                prefs.removeFromFileAllowlist(path);
                allowlistPaths.remove(position);
                notifyItemRemoved(position);
                if (allowlistPaths.isEmpty()) showCurrentTab();
            });
        }

        @Override
        public int getItemCount() { return allowlistPaths.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView itemPath;
            View btnRemove;
            ViewHolder(View itemView) {
                super(itemView);
                itemPath = itemView.findViewById(R.id.itemPath);
                btnRemove = itemView.findViewById(R.id.btnRemove);
            }
        }
    }

    // ========== 行为日志 Adapter ==========

    class BehaviorAdapter extends RecyclerView.Adapter<BehaviorAdapter.ViewHolder> {
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_management_behavior, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            BehaviorLog.Entry entry = behaviorEntries.get(position);
            holder.itemTarget.setText(entry.target);
            holder.itemDetail.setText(entry.detail);
            holder.itemTime.setText(BehaviorLog.timeText(entry.ts));

            // 设置图标
            int iconRes;
            int tintRes;
            switch (entry.type) {
                case BehaviorLog.TYPE_FORCE_STOP:
                    iconRes = R.drawable.ic_delete;
                    tintRes = R.color.mode_color_1;
                    break;
                case BehaviorLog.TYPE_BLOCK:
                    iconRes = R.drawable.ic_shield;
                    tintRes = R.color.mode_color_4;
                    break;
                case BehaviorLog.TYPE_RESCUE:
                    iconRes = R.drawable.ic_restore;
                    tintRes = R.color.mode_color_2;
                    break;
                case BehaviorLog.TYPE_EMERGENCY:
                    iconRes = R.drawable.ic_quarantine;
                    tintRes = R.color.mode_color_4;
                    break;
                case BehaviorLog.TYPE_QUARANTINE:
                    iconRes = R.drawable.ic_quarantine;
                    tintRes = R.color.mode_color_3;
                    break;
                case BehaviorLog.TYPE_MONITOR:
                    iconRes = R.drawable.ic_monitor;
                    tintRes = R.color.mode_color_1;
                    break;
                case BehaviorLog.TYPE_UNINSTALL:
                    iconRes = R.drawable.ic_delete;
                    tintRes = R.color.mode_color_4;
                    break;
                case BehaviorLog.TYPE_FREEZE:
                    iconRes = R.drawable.ic_restore;
                    tintRes = R.color.mode_color_3;
                    break;
                default:
                    iconRes = R.drawable.ic_shield;
                    tintRes = R.color.mode_color_1;
                    break;
            }
            holder.typeIcon.setImageResource(iconRes);
            holder.typeIcon.setColorFilter(getResources().getColor(tintRes, getTheme()));
        }

        @Override
        public int getItemCount() { return behaviorEntries.size(); }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView typeIcon;
            TextView itemTarget, itemDetail, itemTime;
            ViewHolder(View itemView) {
                super(itemView);
                typeIcon = itemView.findViewById(R.id.typeIcon);
                itemTarget = itemView.findViewById(R.id.itemTarget);
                itemDetail = itemView.findViewById(R.id.itemDetail);
                itemTime = itemView.findViewById(R.id.itemTime);
            }
        }
    }
}
