package com.polaris.app.service;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.polaris.app.util.Prefs;

/**
 * Accessibility 模式核心：监听前台窗口变化，实时记录当前前台应用，
 * 供深层扫描与 GuardService 使用。同时在服务生命周期内维护启用状态。
 */
public class PolarisAccessibilityService extends AccessibilityService {

    private static final String TAG = "PolarisAccess";

    private Prefs prefs;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = new Prefs(this);
        prefs.setAccessibilityReady(true);
        Log.i(TAG, "Accessibility service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && pkg.length() > 0) {
                if (prefs == null) prefs = new Prefs(this);
                prefs.setLastForegroundPkg(pkg.toString());
            }
        }
    }

    @Override
    public void onInterrupt() {
        // no-op
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (prefs != null) prefs.setAccessibilityReady(false);
        Log.i(TAG, "Accessibility service unbound");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (prefs != null) prefs.setAccessibilityReady(false);
        super.onDestroy();
    }
}
