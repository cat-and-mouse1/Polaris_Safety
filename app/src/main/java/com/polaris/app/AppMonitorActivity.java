package com.polaris.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;
import com.polaris.app.util.Prefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 应用监控（拦截板块功能）。
 *
 * 查看手机上所有应用，通过开关选择要监控的应用；
 * 开启后由无障碍服务实时检查其行为，一旦发现异常
 * （后台启动界面 / 后台发送通知）立即通知用户并记入「行为」日志。
 */
public class AppMonitorActivity extends BaseActivity {

    /** 单个可监控应用。 */
    private static class AppItem {
        String pkg;
        String label;
        Drawable icon;
    }

    private Prefs prefs;
    private List<AppItem> apps = new ArrayList<>();
    private AppAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_app_monitor);

        prefs = new Prefs(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        adapter = new AppAdapter();
        ListView list = findViewById(R.id.appMonitorList);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.appMonitorEmpty));

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
    }

    /** 枚举所有带启动入口的应用（排除自身），按名称排序。 */
    private void loadApps() {
        apps.clear();
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            AppItem a = new AppItem();
            a.pkg = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                a.label = pm.getApplicationLabel(ai).toString();
                a.icon = ai.loadIcon(pm);
            } catch (Exception e) {
                a.label = pkg;
            }
            apps.add(a);
        }
        Collections.sort(apps, Comparator.comparing(a -> a.label.toLowerCase()));
        adapter.notifyDataSetChanged();
    }

    private class AppAdapter extends BaseAdapter {

        private final LayoutInflater inflater = LayoutInflater.from(AppMonitorActivity.this);

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = inflater.inflate(R.layout.item_app_monitor, parent, false);
            }
            final AppItem app = apps.get(position);
            ImageView icon = v.findViewById(R.id.monitorAppIcon);
            TextView label = v.findViewById(R.id.monitorAppLabel);
            TextView pkg = v.findViewById(R.id.monitorAppPkg);
            Switch toggle = v.findViewById(R.id.monitorSwitch);

            icon.setImageDrawable(app.icon);
            label.setText(app.label);
            pkg.setText(app.pkg);
            toggle.setOnCheckedChangeListener(null);
            Set<String> monitored = prefs.getMonitoredApps();
            toggle.setChecked(monitored.contains(app.pkg));
            toggle.setOnCheckedChangeListener((btn, checked) -> {
                if (checked) {
                    prefs.addMonitoredApp(app.pkg);
                    Toast.makeText(AppMonitorActivity.this,
                            getString(R.string.monitor_on_toast, app.label),
                            Toast.LENGTH_SHORT).show();
                } else {
                    prefs.removeMonitoredApp(app.pkg);
                    Toast.makeText(AppMonitorActivity.this,
                            getString(R.string.monitor_off_toast, app.label),
                            Toast.LENGTH_SHORT).show();
                }
            });
            return v;
        }
    }

    // ---------- 紧急遇险：应用在前台时也能用三击音量上键逃生 ----------

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP
                && event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            if (com.polaris.app.service.PolarisAccessibilityService.handleVolumeUp(this)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    static void start(Context context) {
        context.startActivity(new Intent(context, AppMonitorActivity.class));
    }
}
