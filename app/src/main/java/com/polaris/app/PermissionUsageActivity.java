package com.polaris.app;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;
import com.polaris.app.util.RootChecker;
import com.polaris.app.util.ShizukuHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PermissionUsageActivity extends BaseActivity {

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView emptyText;
    private EditText searchInput;
    private TabLayout tabLayout;
    private TextView statusText;

    private AppListAdapter adapter;
    private List<AppItem> allApps = new ArrayList<>();
    private List<AppItem> filteredApps = new ArrayList<>();
    private boolean showRunningOnly = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Shizuku/Root 增强的权限使用数据
    // key: packageName, value: Set of actively used permission op codes
    private Map<String, Set<String>> activeOpsMap = new HashMap<>();
    private boolean hasEnhancedAccess = false;

    // AppOps 操作码映射（常见的敏感操作）
    private static final Map<String, String> OP_NAMES = new HashMap<>();
    static {
        OP_NAMES.put("COARSE_LOCATION", "位置（粗略）");
        OP_NAMES.put("FINE_LOCATION", "位置（精确）");
        OP_NAMES.put("GPS", "GPS 定位");
        OP_NAMES.put("MONITOR_LOCATION", "位置监控");
        OP_NAMES.put("MONITOR_HIGH_POWER_LOCATION", "高精度位置监控");
        OP_NAMES.put("CAMERA", "摄像头");
        OP_NAMES.put("RECORD_AUDIO", "麦克风");
        OP_NAMES.put("PHONE_CALL", "电话");
        OP_NAMES.put("READ_PHONE_STATE", "读取手机状态");
        OP_NAMES.put("READ_PHONE_NUMBERS", "读取电话号码");
        OP_NAMES.put("CALL_PHONE", "拨打电话");
        OP_NAMES.put("SEND_SMS", "发送短信");
        OP_NAMES.put("READ_SMS", "读取短信");
        OP_NAMES.put("RECEIVE_SMS", "接收短信");
        OP_NAMES.put("RECEIVE_MMS", "接收彩信");
        OP_NAMES.put("RECEIVE_WAP_PUSH", "接收 WAP 推送");
        OP_NAMES.put("READ_CONTACTS", "读取联系人");
        OP_NAMES.put("WRITE_CONTACTS", "写入联系人");
        OP_NAMES.put("GET_ACCOUNTS", "获取账户");
        OP_NAMES.put("READ_CALENDAR", "读取日历");
        OP_NAMES.put("WRITE_CALENDAR", "写入日历");
        OP_NAMES.put("READ_CALL_LOG", "读取通话记录");
        OP_NAMES.put("WRITE_CALL_LOG", "写入通话记录");
        OP_NAMES.put("BODY_SENSORS", "体征传感器");
        OP_NAMES.put("ACTIVITY_RECOGNITION", "活动识别");
        OP_NAMES.put("ACCESS_MEDIA_LOCATION", "访问媒体位置");
        OP_NAMES.put("READ_MEDIA_IMAGES", "读取图片");
        OP_NAMES.put("READ_MEDIA_VIDEO", "读取视频");
        OP_NAMES.put("READ_MEDIA_AUDIO", "读取音频");
        OP_NAMES.put("POST_NOTIFICATIONS", "发送通知");
        OP_NAMES.put("WRITE_SETTINGS", "修改系统设置");
        OP_NAMES.put("SYSTEM_ALERT_WINDOW", "悬浮窗");
        OP_NAMES.put("REQUEST_INSTALL_PACKAGES", "安装应用");
        OP_NAMES.put("MANAGE_EXTERNAL_STORAGE", "管理外部存储");
        OP_NAMES.put("ACCESS_BACKGROUND_LOCATION", "后台位置访问");
        OP_NAMES.put("USE_SIP", "SIP 通话");
        OP_NAMES.put("PROCESS_OUTGOING_CALLS", "处理外拨电话");
        OP_NAMES.put("READ_MEDIA_VISUAL_USER_SELECTED", "用户选择的视觉媒体");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission_usage);

        // 返回按钮
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);
        emptyText = findViewById(R.id.emptyText);
        searchInput = findViewById(R.id.searchInput);
        tabLayout = findViewById(R.id.tabLayout);
        statusText = findViewById(R.id.statusText);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppListAdapter();
        recyclerView.setAdapter(adapter);

        // Tab 切换
        tabLayout.addTab(tabLayout.newTab().setText(R.string.perm_usage_tab_installed));
        tabLayout.addTab(tabLayout.newTab().setText(R.string.perm_usage_tab_running));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                showRunningOnly = tab.getPosition() == 1;
                filterApps();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        // 搜索
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        loadApps();
    }

    private void loadApps() {
        progressBar.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);

        executor.execute(() -> {
            // 检测 Shizuku/Root 权限
            hasEnhancedAccess = checkEnhancedAccess();
            if (hasEnhancedAccess) {
                activeOpsMap = loadActiveOps();
            }

            PackageManager pm = getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);

            // 获取最近 7 天的使用统计
            long now = System.currentTimeMillis();
            long sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000;
            Map<String, UsageStats> statsMap = new HashMap<>();
            try {
                List<UsageStats> statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, sevenDaysAgo, now);
                if (statsList != null) {
                    for (UsageStats stats : statsList) {
                        statsMap.put(stats.getPackageName(), stats);
                    }
                }
            } catch (SecurityException e) {
                // 无使用情况访问权限，跳过
            }

            List<AppItem> apps = new ArrayList<>();
            for (PackageInfo pkg : packages) {
                // 跳过系统应用
                if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;

                String pkgName = pkg.packageName;
                Drawable icon = pkg.applicationInfo.loadIcon(pm);
                String appName = pkg.applicationInfo.loadLabel(pm).toString();
                List<PermItem> perms = new ArrayList<>();

                // 已授予的权限
                if (pkg.requestedPermissions != null) {
                    for (int i = 0; i < pkg.requestedPermissions.length; i++) {
                        boolean granted = (pkg.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                        String permName = pkg.requestedPermissions[i];
                        String shortName = permName.substring(permName.lastIndexOf('.') + 1);
                        
                        // 检查此权限是否正在被使用
                        boolean inUse = false;
                        if (hasEnhancedAccess && activeOpsMap.containsKey(pkgName)) {
                            inUse = activeOpsMap.get(pkgName).contains(shortName);
                        }
                        
                        perms.add(new PermItem(shortName, permName, granted, inUse));
                    }
                }

                // 使用统计
                UsageStats stats = statsMap.get(pkgName);
                long lastUsed = stats != null ? stats.getLastTimeUsed() : 0;
                boolean isRunning = lastUsed > (now - 10 * 60 * 1000); // 10 分钟内使用

                // 如果有增强权限，使用 activeOpsMap 判断是否正在使用
                if (hasEnhancedAccess && activeOpsMap.containsKey(pkgName) && !activeOpsMap.get(pkgName).isEmpty()) {
                    isRunning = true;
                }

                apps.add(new AppItem(pkgName, appName, icon, perms, lastUsed, isRunning));
            }

            // 排序：正在使用的在前，然后按名称排序
            Collections.sort(apps, (a, b) -> {
                if (a.isRunning != b.isRunning) return a.isRunning ? -1 : 1;
                return a.appName.compareToIgnoreCase(b.appName);
            });

            mainHandler.post(() -> {
                allApps.clear();
                allApps.addAll(apps);
                filterApps();
                progressBar.setVisibility(View.GONE);
                
                // 显示增强状态
                if (hasEnhancedAccess) {
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("✓ 已启用增强检测（Shizuku/Root）");
                    statusText.setTextColor(getColor(R.color.green_500));
                }
            });
        });
    }

    /**
     * 检查是否具有增强权限访问能力（Shizuku 或 Root）
     */
    private boolean checkEnhancedAccess() {
        // 检查 Root
        if (RootChecker.isRootAvailable()) {
            return true;
        }
        // 检查 Shizuku
        if (ShizukuHelper.isShizukuInstalled(this) && 
            ShizukuHelper.isShizukuRunning() && 
            ShizukuHelper.hasShizukuPermission()) {
            return true;
        }
        return false;
    }

    /**
     * 通过 dumpsys appops 获取正在使用的权限
     */
    private Map<String, Set<String>> loadActiveOps() {
        Map<String, Set<String>> result = new HashMap<>();
        String output = null;

        // 尝试 Root
        if (RootChecker.isRootAvailable()) {
            output = RootChecker.runAsRootOutput("dumpsys appops get-in-use");
        }
        // 尝试 Shizuku
        if (output == null || output.isEmpty()) {
            if (ShizukuHelper.isShizukuInstalled(this) && 
                ShizukuHelper.isShizukuRunning() && 
                ShizukuHelper.hasShizukuPermission()) {
                output = ShizukuHelper.runShell("dumpsys", "appops", "get-in-use");
            }
        }

        if (output == null || output.isEmpty()) {
            return result;
        }

        // 解析 dumpsys appops 输出
        // 格式示例：
        // Package: com.example.app
        //   Op COARSE_LOCATION: uid=10123 state=ALLOWED
        //   Op CAMERA: uid=10123 state=ALLOWED
        Pattern pkgPattern = Pattern.compile("Package:\\s+(\\S+)");
        Pattern opPattern = Pattern.compile("Op\\s+(\\w+):.*state=(ALLOWED|ALLOWED_FOREGROUND)");
        
        String currentPkg = null;
        for (String line : output.split("\n")) {
            Matcher pkgMatcher = pkgPattern.matcher(line);
            if (pkgMatcher.find()) {
                currentPkg = pkgMatcher.group(1);
                continue;
            }
            
            if (currentPkg != null) {
                Matcher opMatcher = opPattern.matcher(line);
                if (opMatcher.find()) {
                    String opName = opMatcher.group(1);
                    String readableName = OP_NAMES.get(opName);
                    if (readableName != null) {
                        result.computeIfAbsent(currentPkg, k -> new HashSet<>()).add(readableName);
                    }
                }
            }
        }

        return result;
    }

    private void filterApps() {
        String query = searchInput.getText().toString().toLowerCase().trim();
        filteredApps.clear();

        for (AppItem app : allApps) {
            if (showRunningOnly && !app.isRunning) continue;
            if (!query.isEmpty() && !app.appName.toLowerCase().contains(query) 
                && !app.packageName.toLowerCase().contains(query)) continue;
            filteredApps.add(app);
        }

        adapter.notifyDataSetChanged();
        emptyText.setVisibility(filteredApps.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(filteredApps.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ---------- 数据类 ----------

    static class AppItem {
        String packageName;
        String appName;
        Drawable icon;
        List<PermItem> permissions;
        long lastUsed;
        boolean isRunning;

        AppItem(String packageName, String appName, Drawable icon, List<PermItem> permissions, long lastUsed, boolean isRunning) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
            this.permissions = permissions;
            this.lastUsed = lastUsed;
            this.isRunning = isRunning;
        }
    }

    static class PermItem {
        String shortName;
        String fullName;
        boolean granted;
        boolean inUse; // 是否正在被使用（仅在 Shizuku/Root 模式下有效）

        PermItem(String shortName, String fullName, boolean granted, boolean inUse) {
            this.shortName = shortName;
            this.fullName = fullName;
            this.granted = granted;
            this.inUse = inUse;
        }
    }

    // ---------- Adapter ----------

    class AppListAdapter extends RecyclerView.Adapter<AppListAdapter.ViewHolder> {

        private final List<Integer> expandedPositions = new ArrayList<>();

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_perm_usage_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppItem app = filteredApps.get(position);

            holder.appIcon.setImageDrawable(app.icon);
            holder.appName.setText(app.appName);
            holder.appPackage.setText(app.packageName);

            int permCount = app.permissions.size();
            holder.permCountBadge.setText(getString(R.string.perm_usage_count, permCount));

            // 展开/折叠
            boolean expanded = expandedPositions.contains(position);
            holder.permListContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);

            if (expanded) {
                holder.permList.removeAllViews();
                LayoutInflater inflater = LayoutInflater.from(PermissionUsageActivity.this);
                for (PermItem perm : app.permissions) {
                    View permView = inflater.inflate(R.layout.item_perm_usage_detail, holder.permList, false);
                    TextView permName = permView.findViewById(R.id.permName);
                    TextView permStatus = permView.findViewById(R.id.permStatus);
                    View statusDot = permView.findViewById(R.id.statusDot);

                    permName.setText(perm.shortName);
                    
                    // 显示权限状态
                    if (perm.inUse) {
                        permStatus.setText(getString(R.string.perm_usage_active));
                        permStatus.setTextColor(getColor(R.color.green_500));
                        statusDot.setBackgroundResource(R.drawable.bg_status_dot_active);
                    } else if (perm.granted) {
                        permStatus.setText(getString(R.string.perm_usage_granted));
                        statusDot.setBackgroundResource(R.drawable.bg_status_dot);
                    } else {
                        permStatus.setText(getString(R.string.perm_usage_not_granted));
                        statusDot.setBackgroundResource(R.drawable.bg_status_dot_not_granted);
                    }

                    holder.permList.addView(permView);
                }
            }

            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (expandedPositions.contains(pos)) {
                    expandedPositions.remove(Integer.valueOf(pos));
                } else {
                    expandedPositions.add(pos);
                }
                notifyItemChanged(pos);
            });
        }

        @Override
        public int getItemCount() {
            return filteredApps.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView appIcon;
            TextView appName, appPackage, permCountBadge;
            View permListContainer;
            ViewGroup permList;

            ViewHolder(View itemView) {
                super(itemView);
                appIcon = itemView.findViewById(R.id.appIcon);
                appName = itemView.findViewById(R.id.appName);
                appPackage = itemView.findViewById(R.id.appPackage);
                permCountBadge = itemView.findViewById(R.id.permCountBadge);
                permListContainer = itemView.findViewById(R.id.permListContainer);
                permList = itemView.findViewById(R.id.permList);
            }
        }
    }
}
