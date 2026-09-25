package com.polaris.app.view;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import android.net.TrafficStats;
import com.polaris.app.R;
import com.polaris.app.util.Prefs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 工具箱面板：由主界面的下拉手势展开，全屏承载。
 *
 * 内容与交互原属独立的 ToolsActivity（已下线）：
 * 搜索 + 两个 Tab（应用使用的权限 / 最近文件）+ 单文件风险扫描。
 */
public class ToolsPanelView extends LinearLayout {

    /** 面板请求关闭（点击右上角 ✕）。 */
    public interface OnCloseListener {
        void onClose();
    }

    private static final long MIN_RELOAD_INTERVAL_MS = 20_000L;

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView emptyText;
    private EditText searchInput;
    private TabLayout tabLayout;
    private TextView[] navItems;

    private PermissionAdapter permissionAdapter;
    private RecentFileAdapter recentFileAdapter;
    private TrafficAdapter trafficAdapter;

    private final List<PermAppItem> permApps = new ArrayList<>();
    private final List<RecentFileItem> recentFiles = new ArrayList<>();
    private final List<TrafficAppItem> trafficApps = new ArrayList<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int currentTab = 0; // 0=权限使用 1=最近文件 2=流量检测

    private Prefs prefs;
    private OnCloseListener closeListener;

    private boolean loaded = false;
    private long lastLoadMs = 0L;
    private volatile boolean released = false;

    public ToolsPanelView(Context context) {
        this(context, null);
    }

    public ToolsPanelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ToolsPanelView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOrientation(VERTICAL);
        LayoutInflater.from(getContext()).inflate(R.layout.layout_tools_panel, this, true);
        prefs = new Prefs(getContext());

        recyclerView = findViewById(R.id.panelList);
        progressBar = findViewById(R.id.panelProgress);
        emptyText = findViewById(R.id.panelEmpty);
        searchInput = findViewById(R.id.panelSearchInput);
        tabLayout = findViewById(R.id.panelTabs);
        android.widget.LinearLayout navList = findViewById(R.id.panelNavList);

        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        permissionAdapter = new PermissionAdapter();
        recentFileAdapter = new RecentFileAdapter();
        trafficAdapter = new TrafficAdapter();

        if (tabLayout != null) {
            // 竖屏：横向 Tab
            tabLayout.addTab(tabLayout.newTab().setText(R.string.tools_tab_permission));
            tabLayout.addTab(tabLayout.newTab().setText(R.string.tools_tab_recent));
            tabLayout.addTab(tabLayout.newTab().setText(R.string.tools_tab_traffic));
            tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(TabLayout.Tab tab) {
                    selectTab(tab.getPosition());
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) { }
                @Override public void onTabReselected(TabLayout.Tab tab) { }
            });
        } else if (navList != null) {
            // 横屏：左栏竖排导航
            navItems = new TextView[]{
                    navList.findViewById(R.id.panelNavPerm),
                    navList.findViewById(R.id.panelNavRecent),
                    navList.findViewById(R.id.panelNavTraffic)};
            int[] navIds = {R.id.panelNavPerm, R.id.panelNavRecent, R.id.panelNavTraffic};
            for (int i = 0; i < navIds.length; i++) {
                final int pos = i;
                navList.findViewById(navIds[i]).setOnClickListener(v -> selectNavTab(pos));
            }
            selectNavTab(0);
        }

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterData();
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        findViewById(R.id.panelClose).setOnClickListener(v -> {
            if (closeListener != null) closeListener.onClose();
        });
    }

    public void setOnCloseListener(OnCloseListener listener) {
        this.closeListener = listener;
    }

    /** 统一 Tab 切换：重置搜索并刷新列表。 */
    private void selectTab(int pos) {
        currentTab = pos;
        if (searchInput.getText() != null && searchInput.getText().length() > 0) {
            searchInput.setText("");
        }
        showCurrentTab();
    }

    /** 横屏左栏竖排导航：切换 + 高亮。 */
    private void selectNavTab(int pos) {
        selectTab(pos);
        if (navItems != null) {
            for (int i = 0; i < navItems.length; i++) {
                TextView item = navItems[i];
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

    /** 面板展开时调用；{@code force=false} 时 20 秒内不重复加载。 */
    public void refresh() {
        refresh(false);
    }

    public void refresh(boolean force) {
        if (released) return;
        long now = System.currentTimeMillis();
        if (!force && loaded && now - lastLoadMs < MIN_RELOAD_INTERVAL_MS) {
            showCurrentTab();
            return;
        }
        lastLoadMs = now;
        loaded = true;

        progressBar.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);

        executor.execute(() -> {
            loadPermApps();
            loadRecentFiles();
            loadTrafficApps();
            mainHandler.post(() -> {
                if (released) return;
                progressBar.setVisibility(View.GONE);
                showCurrentTab();
            });
        });
    }

    /** 面板销毁时释放线程池。 */
    public void release() {
        released = true;
        executor.shutdownNow();
    }

    // ---------- 数据加载 ----------

    private void loadPermApps() {
        List<PermAppItem> out = new ArrayList<>();
        PackageManager pm = getContext().getPackageManager();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } catch (Exception e) {
            packages = new ArrayList<>();
        }
        UsageStatsManager usm =
                (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);

        long now = System.currentTimeMillis();
        long sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000;
        Map<String, UsageStats> statsMap = new HashMap<>();
        try {
            List<UsageStats> statsList =
                    usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, sevenDaysAgo, now);
            if (statsList != null) {
                for (UsageStats stats : statsList) {
                    statsMap.put(stats.getPackageName(), stats);
                }
            }
        } catch (Exception ignored) {
            // 未授予「使用情况访问」权限时跳过运行态判定
        }

        for (PackageInfo pkg : packages) {
            if (pkg.applicationInfo == null) continue;
            if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;

            String pkgName = pkg.packageName;
            Drawable icon = pkg.applicationInfo.loadIcon(pm);
            String appName = pkg.applicationInfo.loadLabel(pm).toString();
            List<PermItem> perms = new ArrayList<>();

            if (pkg.requestedPermissions != null) {
                for (int i = 0; i < pkg.requestedPermissions.length; i++) {
                    boolean granted = pkg.requestedPermissionsFlags != null
                            && i < pkg.requestedPermissionsFlags.length
                            && (pkg.requestedPermissionsFlags[i]
                            & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                    String permName = pkg.requestedPermissions[i];
                    String shortName = permName.substring(permName.lastIndexOf('.') + 1);
                    perms.add(new PermItem(shortName, permName, granted));
                }
            }

            UsageStats stats = statsMap.get(pkgName);
            long lastUsed = stats != null ? stats.getLastTimeUsed() : 0;
            boolean isRunning = lastUsed > (now - 10 * 60 * 1000);

            out.add(new PermAppItem(pkgName, appName, icon, perms, isRunning));
        }

        Collections.sort(out, (a, b) -> {
            if (a.isRunning != b.isRunning) return a.isRunning ? -1 : 1;
            return a.appName.compareToIgnoreCase(b.appName);
        });

        synchronized (permApps) {
            permApps.clear();
            permApps.addAll(out);
        }
    }

    private void loadRecentFiles() {
        List<RecentFileItem> out = new ArrayList<>();

        File[] dirs = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        };

        long oneWeekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        for (File dir : dirs) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File file : files) {
                if (!file.isFile() || file.lastModified() <= oneWeekAgo) continue;
                out.add(new RecentFileItem(
                        file.getName(),
                        file.getAbsolutePath(),
                        sdf.format(new Date(file.lastModified())),
                        formatSize(file.length()),
                        file.lastModified(),
                        file));
            }
        }

        Collections.sort(out, (a, b) -> Long.compare(b.lastModified, a.lastModified));

        synchronized (recentFiles) {
            recentFiles.clear();
            recentFiles.addAll(out);
        }
    }

    private void loadTrafficApps() {
        List<TrafficAppItem> out = new ArrayList<>();
        PackageManager pm = getContext().getPackageManager();
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(0);
        } catch (Exception e) {
            packages = new ArrayList<>();
        }
        for (PackageInfo pkg : packages) {
            if (pkg.applicationInfo == null) continue;
            if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            int uid = pkg.applicationInfo.uid;
            long rx = TrafficStats.getUidRxBytes(uid);
            long tx = TrafficStats.getUidTxBytes(uid);
            if (rx < 0) rx = 0;
            if (tx < 0) tx = 0;
            if (rx == 0 && tx == 0) continue;
            Drawable icon = pkg.applicationInfo.loadIcon(pm);
            String appName = pkg.applicationInfo.loadLabel(pm).toString();
            out.add(new TrafficAppItem(pkg.packageName, appName, icon, rx, tx));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.total, a.total));
        synchronized (trafficApps) {
            trafficApps.clear();
            trafficApps.addAll(out);
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ---------- 列表展示 ----------

    private void showCurrentTab() {
        String query = searchInput.getText() == null
                ? "" : searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);

        if (currentTab == 0) {
            recyclerView.setAdapter(permissionAdapter);
            permissionAdapter.filter(query);
        } else if (currentTab == 1) {
            recyclerView.setAdapter(recentFileAdapter);
            recentFileAdapter.filter(query);
        } else {
            recyclerView.setAdapter(trafficAdapter);
            trafficAdapter.filter(query);
        }
        updateEmptyState();
    }

    private void filterData() {
        showCurrentTab();
    }

    private void updateEmptyState() {
        boolean empty = currentTab == 0
                ? permissionAdapter.getItemCount() == 0
                : currentTab == 1
                    ? recentFileAdapter.getItemCount() == 0
                    : trafficAdapter.getItemCount() == 0;
        if (empty) {
            emptyText.setText(currentTab == 0
                    ? R.string.tools_empty
                    : currentTab == 1 ? R.string.tools_no_recent_files
                        : R.string.tools_no_traffic);
            emptyText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    // ---------- 单文件扫描 ----------

    private void scanSingleFile(RecentFileItem fileItem) {
        File file = fileItem.file;
        if (file == null || !file.exists()) {
            toast(R.string.file_action_failed);
            return;
        }

        progressBar.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);

        executor.execute(() -> {
            com.polaris.app.scan.FileRiskInfo risk = analyzeFile(file);

            int safe = (risk.level == com.polaris.app.scan.FileRiskInfo.LEVEL_SAFE) ? 1 : 0;
            int suspicious = (risk.level == com.polaris.app.scan.FileRiskInfo.LEVEL_LOW) ? 1 : 0;
            int malicious = (risk.level >= com.polaris.app.scan.FileRiskInfo.LEVEL_MEDIUM) ? 1 : 0;
            prefs.saveLastScanSummary(1, safe, suspicious, malicious);
            prefs.setLastScanMs(System.currentTimeMillis());
            prefs.incrementScanCount();

            mainHandler.post(() -> {
                if (released) return;
                progressBar.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                showScanResultDialog(fileItem, risk);
            });
        });
    }

    private com.polaris.app.scan.FileRiskInfo analyzeFile(File file) {
        com.polaris.app.scan.FileRiskInfo risk = new com.polaris.app.scan.FileRiskInfo(
                file.getAbsolutePath(), file.getName(), file.length());

        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".apk")) {
            risk.isApk = true;
            risk.score += 30;
            risk.reasons.add("APK 安装包（不可信来源风险高）");
        } else if (name.endsWith(".dex") || name.endsWith(".jar")) {
            risk.score += 25;
            risk.reasons.add("可执行字节码");
        } else if (name.endsWith(".sh") || name.endsWith(".py") || name.endsWith(".bat")
                || name.endsWith(".vbs") || name.endsWith(".ps1")) {
            risk.score += 20;
            risk.reasons.add("脚本文件");
        } else if (name.endsWith(".lock") || name.endsWith(".locked")
                || name.endsWith(".crypt") || name.endsWith(".encrypted")) {
            risk.score += 40;
            risk.reasons.add("勒索加密文件");
        } else if (name.endsWith(".crdownload")) {
            risk.score += 15;
            risk.reasons.add("未完成的下载文件");
        }

        String[] riskKeywords = {
                "crack", "keygen", "破解", "外挂", "刷机", "spy", "trojan", "virus", "malware"};
        for (String keyword : riskKeywords) {
            if (name.contains(keyword)) {
                risk.score += 25;
                risk.reasons.add("文件名包含风险关键词: " + keyword);
                break;
            }
        }

        if (name.startsWith(".")) {
            risk.score += 15;
            risk.reasons.add("隐藏文件");
        }

        if (file.length() > 100L * 1024 * 1024) {
            String ext = name.substring(name.lastIndexOf('.') + 1);
            if (!ext.equals("mp4") && !ext.equals("mp3")
                    && !ext.equals("avi") && !ext.equals("mkv")) {
                risk.score += 10;
                risk.reasons.add("文件大小异常");
            }
        }

        if (risk.score >= 60) {
            risk.level = com.polaris.app.scan.FileRiskInfo.LEVEL_HIGH;
        } else if (risk.score >= 40) {
            risk.level = com.polaris.app.scan.FileRiskInfo.LEVEL_MEDIUM;
        } else if (risk.score >= 20) {
            risk.level = com.polaris.app.scan.FileRiskInfo.LEVEL_LOW;
        } else {
            risk.level = com.polaris.app.scan.FileRiskInfo.LEVEL_SAFE;
        }

        return risk;
    }

    private void showScanResultDialog(RecentFileItem fileItem,
                                      com.polaris.app.scan.FileRiskInfo risk) {
        String title;
        switch (risk.level) {
            case com.polaris.app.scan.FileRiskInfo.LEVEL_HIGH:
                title = "高风险";
                break;
            case com.polaris.app.scan.FileRiskInfo.LEVEL_MEDIUM:
                title = "中风险";
                break;
            case com.polaris.app.scan.FileRiskInfo.LEVEL_LOW:
                title = "低风险";
                break;
            default:
                title = "安全";
                break;
        }

        StringBuilder message = new StringBuilder();
        message.append("文件: ").append(fileItem.name).append("\n");
        message.append("路径: ").append(fileItem.path).append("\n");
        message.append("大小: ").append(fileItem.sizeStr).append("\n");
        message.append("修改时间: ").append(fileItem.modifiedStr).append("\n\n");
        message.append("风险评分: ").append(risk.score).append("/100\n");
        message.append("风险等级: ").append(title).append("\n\n");

        if (!risk.reasons.isEmpty()) {
            message.append("检测到的风险特征:\n");
            for (String reason : risk.reasons) {
                message.append("• ").append(reason).append("\n");
            }
        } else {
            message.append("未检测到明显风险特征");
        }

        try {
            new androidx.appcompat.app.AlertDialog.Builder(getContext())
                    .setTitle(title)
                    .setMessage(message.toString())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception ignored) {
            // 面板已关闭等场景下静默忽略
        }
    }

    private void toast(int resId) {
        android.widget.Toast.makeText(getContext(), resId, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------- 权限使用 Adapter ----------

    class PermissionAdapter extends RecyclerView.Adapter<PermissionAdapter.ViewHolder> {
        private final List<PermAppItem> filteredList = new ArrayList<>();
        private final java.util.Set<Integer> expandedPositions = new java.util.HashSet<>();

        public void filter(String query) {
            filteredList.clear();
            synchronized (permApps) {
                if (query.isEmpty()) {
                    filteredList.addAll(permApps);
                } else {
                    for (PermAppItem app : permApps) {
                        if (app.appName.toLowerCase(Locale.ROOT).contains(query)
                                || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                            filteredList.add(app);
                        }
                    }
                }
            }
            expandedPositions.clear();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_perm_usage_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PermAppItem app = filteredList.get(position);
            holder.appIcon.setImageDrawable(app.icon);
            holder.appName.setText(app.appName);
            holder.appPackage.setText(app.packageName);
            holder.permCountBadge.setText(
                    getContext().getString(R.string.perm_usage_count, app.permissions.size()));

            boolean expanded = expandedPositions.contains(position);
            holder.permListContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);

            if (expanded) {
                holder.permList.removeAllViews();
                LayoutInflater inflater = LayoutInflater.from(getContext());
                for (PermItem perm : app.permissions) {
                    View permView = inflater.inflate(
                            R.layout.item_perm_usage_detail, holder.permList, false);
                    TextView permName = permView.findViewById(R.id.permName);
                    TextView permStatus = permView.findViewById(R.id.permStatus);
                    View statusDot = permView.findViewById(R.id.statusDot);

                    permName.setText(perm.shortName);
                    permStatus.setText(perm.granted
                            ? getContext().getString(R.string.perm_usage_granted)
                            : getContext().getString(R.string.perm_usage_not_granted));
                    statusDot.setBackgroundResource(perm.granted
                            ? R.drawable.bg_status_dot : R.drawable.bg_status_dot_not_granted);

                    holder.permList.addView(permView);
                }
            }

            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                if (expandedPositions.contains(pos)) {
                    expandedPositions.remove(pos);
                } else {
                    expandedPositions.add(pos);
                }
                notifyItemChanged(pos);
            });
        }

        @Override
        public int getItemCount() {
            return filteredList.size();
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

    // ---------- 最近文件 Adapter ----------

    class RecentFileAdapter extends RecyclerView.Adapter<RecentFileAdapter.ViewHolder> {
        private final List<RecentFileItem> filteredList = new ArrayList<>();

        public void filter(String query) {
            filteredList.clear();
            synchronized (recentFiles) {
                if (query.isEmpty()) {
                    filteredList.addAll(recentFiles);
                } else {
                    for (RecentFileItem file : recentFiles) {
                        if (file.name.toLowerCase(Locale.ROOT).contains(query)
                                || file.path.toLowerCase(Locale.ROOT).contains(query)) {
                            filteredList.add(file);
                        }
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tools_recent_file, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RecentFileItem file = filteredList.get(position);
            holder.itemName.setText(file.name);
            holder.itemPath.setText(file.path);
            holder.itemMeta.setText(
                    getContext().getString(R.string.tools_file_modified, file.modifiedStr)
                            + " · " + file.sizeStr);

            holder.btnScan.setOnClickListener(v -> scanSingleFile(file));
        }

        @Override
        public int getItemCount() {
            return filteredList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView itemName, itemPath, itemMeta;
            View btnScan;

            ViewHolder(View itemView) {
                super(itemView);
                itemName = itemView.findViewById(R.id.itemName);
                itemPath = itemView.findViewById(R.id.itemPath);
                itemMeta = itemView.findViewById(R.id.itemMeta);
                btnScan = itemView.findViewById(R.id.btnScan);
            }
        }
    }

    // ---------- 数据类 ----------

    static class PermAppItem {
        String packageName;
        String appName;
        Drawable icon;
        List<PermItem> permissions;
        boolean isRunning;

        PermAppItem(String packageName, String appName, Drawable icon,
                    List<PermItem> permissions, boolean isRunning) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
            this.permissions = permissions;
            this.isRunning = isRunning;
        }
    }

    static class PermItem {
        String shortName;
        String fullName;
        boolean granted;

        PermItem(String shortName, String fullName, boolean granted) {
            this.shortName = shortName;
            this.fullName = fullName;
            this.granted = granted;
        }
    }

    // ---------- 流量检测 Adapter ----------

    class TrafficAdapter extends RecyclerView.Adapter<TrafficAdapter.ViewHolder> {
        private final List<TrafficAppItem> filteredList = new ArrayList<>();

        public void filter(String query) {
            filteredList.clear();
            synchronized (trafficApps) {
                if (query.isEmpty()) {
                    filteredList.addAll(trafficApps);
                } else {
                    for (TrafficAppItem app : trafficApps) {
                        if (app.appName.toLowerCase(Locale.ROOT).contains(query)
                                || app.packageName.toLowerCase(Locale.ROOT).contains(query)) {
                            filteredList.add(app);
                        }
                    }
                }
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tools_traffic, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TrafficAppItem app = filteredList.get(position);
            holder.appIcon.setImageDrawable(app.icon);
            holder.appName.setText(app.appName);
            holder.appPackage.setText(app.packageName);
            holder.appTrafficMeta.setText(getContext().getString(
                    R.string.tools_traffic_detail, formatSize(app.rxBytes), formatSize(app.txBytes)));
            holder.appTotal.setText(formatSize(app.total));
        }

        @Override
        public int getItemCount() {
            return filteredList.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView appIcon;
            TextView appName, appPackage, appTrafficMeta, appTotal;

            ViewHolder(View itemView) {
                super(itemView);
                appIcon = itemView.findViewById(R.id.appIcon);
                appName = itemView.findViewById(R.id.appName);
                appPackage = itemView.findViewById(R.id.appPackage);
                appTrafficMeta = itemView.findViewById(R.id.appTrafficMeta);
                appTotal = itemView.findViewById(R.id.appTotal);
            }
        }
    }

    static class TrafficAppItem {
        String packageName;
        String appName;
        Drawable icon;
        long rxBytes;
        long txBytes;
        long total;

        TrafficAppItem(String packageName, String appName, Drawable icon, long rx, long tx) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
            this.rxBytes = rx;
            this.txBytes = tx;
            this.total = rx + tx;
        }
    }

    static class RecentFileItem {
        String name;
        String path;
        String modifiedStr;
        String sizeStr;
        long lastModified;
        File file;

        RecentFileItem(String name, String path, String modifiedStr, String sizeStr,
                       long lastModified, File file) {
            this.name = name;
            this.path = path;
            this.modifiedStr = modifiedStr;
            this.sizeStr = sizeStr;
            this.lastModified = lastModified;
            this.file = file;
        }
    }
}
